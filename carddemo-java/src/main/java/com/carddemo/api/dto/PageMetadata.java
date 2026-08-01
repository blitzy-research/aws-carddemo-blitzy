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

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Objects;

/**
 * Immutable cursor-plus-direction paging contract shared by the three paginated CardDemo
 * screens, reproducing the CICS browse protocol as REST metadata.
 *
 * <p>The legacy 3270 screens page by browsing a VSAM key-sequenced cluster: {@code STARTBR}
 * positions the browse at a record key, {@code READNEXT} walks forward, {@code READPREV} walks
 * backward, and {@code ENDBR} releases the browse. This record is the REST projection of that
 * protocol, carrying the key the next browse starts from, the direction the caller asked for, and
 * the two independent end-of-browse indicators the legacy screens render.</p>
 *
 * <p><strong>The three page sizes are legacy screen shape, not tuning parameters.</strong> Each
 * was established from the source member that owns the screen, and each by a different mechanism.
 * Changing any one would put a different number of rows on a screen - a visible behavioural
 * regression rather than a configuration change. They are exposed as three separately named
 * constants, and the two equal values are deliberately neither shared nor derived from one
 * another, because they coincide by accident of two unrelated screen layouts:</p>
 * <ul>
 *   <li><strong>Card list, seven rows</strong> - {@code app/cbl/COCRDLIC.cbl} lines 250 to 260
 *       declare a 196-character all-rows area redefined as a table of seven occurrences named
 *       {@code WS-SCREEN-ROWS} at line 255, whose 28-character element (an 11-character account
 *       identifier, a 16-character card number and a 1-character status indicator) accounts for
 *       all 196 characters.</li>
 *   <li><strong>Transaction list, ten rows</strong> - {@code app/cbl/COTRN00C.cbl} establishes the
 *       count purely from loop bounds: the row-clearing loop bound is ten at line 290, the row
 *       index is reset to one at line 295, and the row-filling loop at line 297 stops once the
 *       index reaches eleven. <em>No table declaration exists for these rows at all.</em></li>
 *   <li><strong>User list, ten rows</strong> - {@code app/cbl/COUSR00C.cbl} lines 56 and 57
 *       declare the screen row group {@code USER-REC} as a table of ten occurrences: a genuine
 *       table, a different mechanism from the transaction count above.</li>
 * </ul>
 * <p>All three counts are corroborated by the corresponding symbolic map under
 * {@code app/cpy-bms}, each of which carries exactly that many row families.</p>
 *
 * <p><strong>Paging is cursor-based, never offset-based.</strong> The legacy browse has no row
 * number and computes no offset; it remembers the first and last key of the page it just
 * displayed and restarts the browse from one of them. Card paging carries a composite first and
 * last key of 27 characters, a 16-character card number followed by an 11-character account
 * identifier, at {@code app/cbl/COCRDLIC.cbl} lines 230 to 235. Transaction paging carries a
 * 16-character first and last transaction identifier at {@code app/cbl/COTRN00C.cbl} lines 63 and
 * 64. User paging carries an 8-character first and last user identifier at
 * {@code app/cbl/COUSR00C.cbl} lines 68 and 69. Accordingly {@link #previousCursorKey()} and
 * {@link #nextCursorKey()} together are the authoritative navigation state and
 * {@link #displayedPageNumber()} is a display value only.</p>
 *
 * <p><strong>Both boundary keys travel on every page, because the legacy retains both at once.</strong>
 * The two keys are not alternatives and they are not a single value whose meaning shifts with the
 * direction just travelled: each of the three screens declares them as two adjacent fields of one
 * commarea group and keeps both populated across every pseudo-conversational turn. The card list
 * declares {@code WS-CA-FIRST-CARDKEY} and {@code WS-CA-LAST-CARDKEY} as sibling groups of the same
 * program commarea at {@code app/cbl/COCRDLIC.cbl} lines 230 to 235; the transaction list declares
 * {@code CDEMO-CT00-TRNID-FIRST} and {@code CDEMO-CT00-TRNID-LAST} as adjacent 16-character fields
 * of {@code CDEMO-CT00-INFO} at {@code app/cbl/COTRN00C.cbl} lines 63 and 64; the user list declares
 * {@code CDEMO-CU00-USRID-FIRST} and {@code CDEMO-CU00-USRID-LAST} as adjacent 8-character fields of
 * {@code CDEMO-CU00-INFO} at {@code app/cbl/COUSR00C.cbl} lines 68 and 69. The backward attention
 * key restarts the browse from the first key and the forward attention key from the last, so a page
 * in the middle of a browse - which every screen can reach, and from which either attention key is
 * live - needs both keys present simultaneously. A single cursor component could carry only one of
 * them and would silently strand one of the two directions.</p>
 *
 * <p><strong>Backward fill order is preserved by the service and is never reversed here.</strong>
 * A backward page is filled from the bottom row upward and then presented ascending: the card list
 * seeds its fill counter one past the screen-line count and decrements it, filling rows seven down
 * to one; the transaction list enters its backward paragraph at {@code app/cbl/COTRN00C.cbl}
 * line 333, seeds the row index to ten at line 349 and fills slots ten down to one in the loop at
 * lines 351 to 357, reading backward at line 352; the user list has the same shape. Row ordering
 * therefore belongs to the service, which reverses the read order before building the response.
 * This record carries the direction as data and performs no ordering.</p>
 *
 * <p><strong>The legacy browse exposes no total row count.</strong> It never counts the cluster; it
 * discovers that a further page exists by attempting one more read and observing the outcome - see
 * the forward guard at {@code app/cbl/COTRN00C.cbl} lines 285 to 287 and its mirror on the
 * backward path. A total-row or total-page component would therefore be fabricated information and
 * would force a counting query the original never issued. The two conditions the screens actually
 * know are modelled as the separate flags {@link #hasMorePages()} and
 * {@link #hasPreviousPages()}, which the legacy signals with distinct messages per screen.</p>
 *
 * <p>This type is framework-free by design - it references no persistence, web or data-access
 * abstraction, so the paging contract is defined by the legacy screens rather than by a library -
 * and it is deeply immutable, with every component a primitive, a {@code String} or an enum
 * constant, and every factory pure.</p>
 *
 * <p><strong>A boundary cursor is a record key, so it crosses the wire and stays out of
 * diagnostics.</strong> The card-list cursor is a primary account number followed by an account
 * identifier, as the widths above establish, which makes both cursor components cardholder data even
 * though the client must receive them to resume the browse. The two obligations are separated by
 * scope rather than traded off: the accessors and the JSON wire form carry both cursors byte for
 * byte, and {@link #toString()} withholds both. Decision log entry DL-081 records the
 * arrangement.</p>
 *
 * @param pageSize the number of screen rows this page carries, supplied by the caller. The
 *     legacy antecedent is the per-screen row count proven above — the card-list table of seven
 *     occurrences, the transaction-list loop bound of ten, and the user-list table of ten
 *     occurrences. It travels as data so a single contract serves all three screens; it is never
 *     defaulted here, and the three named constants exist so a caller or a test can name the
 *     contractual value of the screen it is serving.
 * @param previousCursorKey the opaque record key at which a <em>backward</em> browse restarts: the
 *     key of the first row on this page, which is what the legacy programs retain as their FIRST
 *     field and reposition on when the operator asks for the preceding page — the card composite
 *     key, the transaction identifier or the user identifier cited above. It is opaque to the
 *     client and is never interpreted as a row number. It is {@code null} when there is no
 *     preceding page to walk back to, which is where the legacy leaves its FIRST field unusable and
 *     reports the top-of-browse condition instead.
 * @param nextCursorKey the opaque record key at which a <em>forward</em> browse restarts: the key of
 *     the last row on this page, which is what the legacy programs retain as their LAST field and
 *     reposition on when the operator asks for the following page. Same opacity and same widths as
 *     {@code previousCursorKey}, and independent of it — a page in the middle of a browse carries
 *     both, the first page of a browse carries only this one, and the final page only the other.
 *     It is {@code null} when no page follows, which is where the legacy reports the
 *     bottom-of-browse condition.
 * @param direction the browse direction the caller asked for, mirroring the explicit attention
 *     key the legacy programs branch on. Always supplied; there is no default. It records which
 *     direction produced <em>this</em> page and does not select which key is present: both keys are
 *     reported for whichever directions remain available.
 * @param hasMorePages whether a page follows this one, discovered by the legacy browse through one
 *     further read rather than by counting. Independent of {@code hasPreviousPages} because the
 *     legacy renders a distinct message for each condition.
 * @param hasPreviousPages whether a page precedes this one, corresponding to the first-page
 *     condition the legacy evaluates on the backward key. Independent of {@code hasMorePages}.
 * @param displayedPageNumber the page indicator the screens display, carried verbatim as text
 *     because the map fields are alphanumeric rather than numeric and their widths differ across
 *     the three screens: the card list has a three-character field named {@code PAGENO} in
 *     {@code app/cpy-bms/COCRDLI.CPY}, while the transaction and user lists each have an
 *     eight-character field named {@code PAGENUM} in {@code app/cpy-bms/COTRN00.CPY} and
 *     {@code app/cpy-bms/COUSR00.CPY}. Leading characters are preserved exactly as received.
 *     Display value only — the two boundary keys are authoritative for navigation. May be
 *     {@code null} when the screen has no page indicator to render.
 */
public record PageMetadata(
        @Positive int pageSize,
        @Size(max = PageMetadata.CURSOR_KEY_MAX_LENGTH) String previousCursorKey,
        @Size(max = PageMetadata.CURSOR_KEY_MAX_LENGTH) String nextCursorKey,
        PagingDirection direction,
        boolean hasMorePages,
        boolean hasPreviousPages,
        @Size(max = PageMetadata.DISPLAYED_PAGE_NUMBER_MAX_LENGTH) String displayedPageNumber) {

    /**
     * Number of card rows the card-list screen presents: seven.
     *
     * <p>Proven at {@code app/cbl/COCRDLIC.cbl} lines 250 to 260, where a 196-character all-rows
     * area is redefined as a table of seven occurrences named {@code WS-SCREEN-ROWS} at line 255
     * whose 28-character element is an 11-character account identifier, a 16-character card number
     * and a 1-character status indicator, so 28 multiplied by 7 accounts for all 196 characters.
     * Corroborated by the screen-line counter {@code WS-MAX-SCREEN-LINES} and by the seven row
     * families in {@code app/cpy-bms/COCRDLI.CPY}.</p>
     *
     * <p>This is the shape of the card-list screen, not a tunable value.</p>
     */
    public static final int CARD_LIST_PAGE_SIZE = 7;

    /**
     * Number of transaction rows the transaction-list screen presents: ten.
     *
     * <p>Proven at {@code app/cbl/COTRN00C.cbl} entirely from loop bounds — the row-clearing loop
     * bound is ten at line 290, the row index is reset to one at line 295, and the row-filling
     * loop at line 297 stops once the index reaches eleven. No table declaration for these rows
     * exists in the program at all. Corroborated by the ten row families in
     * {@code app/cpy-bms/COTRN00.CPY}.</p>
     *
     * <p>Declared separately from {@link #USER_LIST_PAGE_SIZE} on purpose. The two values are
     * equal by accident of two unrelated screen layouts and were proven by different mechanisms,
     * so neither is derived from the other and a future divergence in one screen must not
     * propagate silently to the other. This is the shape of the transaction-list screen, not a
     * tunable value.</p>
     */
    public static final int TRANSACTION_LIST_PAGE_SIZE = 10;

    /**
     * Number of user rows the administrative user-list screen presents: ten.
     *
     * <p>Proven at {@code app/cbl/COUSR00C.cbl} lines 56 and 57, where the screen row group
     * {@code USER-REC} is declared as a table of ten occurrences — a genuine table, unlike the
     * transaction-list count which comes from loop bounds alone. Corroborated by the ten row
     * families in {@code app/cpy-bms/COUSR00.CPY}.</p>
     *
     * <p>Declared separately from {@link #TRANSACTION_LIST_PAGE_SIZE} on purpose, for the reason
     * given there. This is the shape of the user-list screen, not a tunable value.</p>
     */
    public static final int USER_LIST_PAGE_SIZE = 10;

    /**
     * Width in characters of the widest browse key any of the three screens retains: 27.
     *
     * <p>This is a legacy field width, not a restriction invented here. The card list retains a
     * composite key of a 16-character card number followed by an 11-character account identifier
     * at {@code app/cbl/COCRDLIC.cbl} lines 230 to 235, which is the widest of the three; the
     * transaction list retains a 16-character transaction identifier at
     * {@code app/cbl/COTRN00C.cbl} lines 63 and 64, and the user list an 8-character user
     * identifier at {@code app/cbl/COUSR00C.cbl} lines 68 and 69. Bounding
     * {@link #previousCursorKey()} and {@link #nextCursorKey()} at the widest legacy key means no
     * legal cursor is ever rejected, and the bound only reports over-long input — it never alters,
     * pads or trims a value.</p>
     *
     * <p>One bound governs both boundary keys because the two are the same field on the same screen:
     * every one of the three screens declares its FIRST and its LAST field with identical widths -
     * 27 and 27 for the card list, 16 and 16 for the transaction list, 8 and 8 for the user list -
     * so a per-key bound would be two names for one number.</p>
     */
    public static final int CURSOR_KEY_MAX_LENGTH = 27;

    /**
     * Width in characters of the widest displayed page indicator across the three maps: 8.
     *
     * <p>This is a legacy field width, not a restriction invented here. The card-list map
     * {@code app/cpy-bms/COCRDLI.CPY} declares a three-character field named {@code PAGENO},
     * while {@code app/cpy-bms/COTRN00.CPY} and {@code app/cpy-bms/COUSR00.CPY} each declare an
     * eight-character field named {@code PAGENUM}. All three are alphanumeric rather than
     * numeric, which is why the indicator crosses this API as text. The bound only reports
     * over-long input — it never alters, pads or trims a value.</p>
     */
    public static final int DISPLAYED_PAGE_NUMBER_MAX_LENGTH = 8;

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of each boundary cursor.
     *
     * <p>A constant rather than any transformation of the value, so nothing about a redacted cursor -
     * not its length, not a prefix or suffix, not a digest - survives into a stringified instance. A
     * partial mask was rejected deliberately: the leading sixteen characters of a card cursor are a
     * primary account number in full and the trailing eleven are an account identifier, so every
     * fragment of that key is still regulated data, and a digest of a 27-character numeric key is
     * reversible by enumeration.</p>
     *
     * <p>Private because it is a rendering detail and not part of the paging contract.</p>
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Canonical constructor. Requires an explicit browse direction and leaves every other
     * component exactly as supplied.
     *
     * <p>No component is defaulted, normalised, padded or trimmed: both boundary cursors and the
     * displayed page indicator cross this boundary byte for byte, including any leading or trailing
     * space the legacy fixed-width fields carry. The page size is accepted as given, so one contract
     * serves screens of seven and ten rows alike.</p>
     *
     * <p>Neither boundary cursor is derived from the other and neither is inferred from the
     * direction or from the two availability flags. The legacy programs set their FIRST and LAST
     * fields independently as they fill a page, so the service that assembled the page is the only
     * component that knows either value, and a cursor this constructor synthesised would be a guess
     * at a record key.</p>
     *
     * @throws NullPointerException if {@code direction} is {@code null}, since the legacy programs
     *     branch on an explicit attention key and this contract therefore has no default direction
     */
    public PageMetadata {
        Objects.requireNonNull(direction, "direction must be supplied explicitly");
    }

    /**
     * Creates metadata for a page that was reached by walking forward, the REST equivalent of a
     * {@code READNEXT} browse.
     *
     * <p>Pure factory: it returns a new instance and mutates nothing.</p>
     *
     * <p>Both boundary keys are supplied because a page reached by walking forward can still be
     * walked backward out of — the legacy transaction list, having paged down, leaves its backward
     * attention key live and repositions on the retained FIRST key. Recording only the forward key
     * here would discard exactly the value that path needs.</p>
     *
     * @param pageSize the number of screen rows the page carries
     * @param previousCursorKey the key of the first row on this page, from which a backward browse
     *     restarts, or {@code null} when no page precedes this one
     * @param nextCursorKey the key of the last row on this page, from which a forward browse
     *     restarts, or {@code null} when no page follows this one
     * @param hasMorePages whether a page follows this one
     * @param hasPreviousPages whether a page precedes this one
     * @param displayedPageNumber the page indicator the screen displays, or {@code null}
     * @return a new forward-direction instance
     */
    public static PageMetadata forward(
            int pageSize,
            String previousCursorKey,
            String nextCursorKey,
            boolean hasMorePages,
            boolean hasPreviousPages,
            String displayedPageNumber) {
        return new PageMetadata(
                pageSize,
                previousCursorKey,
                nextCursorKey,
                PagingDirection.FORWARD,
                hasMorePages,
                hasPreviousPages,
                displayedPageNumber);
    }

    /**
     * Creates metadata for a page that was reached by walking backward, the REST equivalent of a
     * {@code READPREV} browse.
     *
     * <p>Pure factory: it returns a new instance and mutates nothing. It performs no reordering —
     * the service has already reversed the read order so that the page it accompanies presents
     * ascending, exactly as the legacy screens fill a backward page from the bottom row upward.</p>
     *
     * <p>Both boundary keys are supplied for the mirror of the reason given on the forward factory:
     * a page reached by walking backward can be walked forward again, and the legacy repositions on
     * the retained LAST key to do it.</p>
     *
     * @param pageSize the number of screen rows the page carries
     * @param previousCursorKey the key of the first row on this page, from which a further backward
     *     browse restarts, or {@code null} when no page precedes this one
     * @param nextCursorKey the key of the last row on this page, from which a forward browse
     *     restarts, or {@code null} when no page follows this one
     * @param hasMorePages whether a page follows this one
     * @param hasPreviousPages whether a page precedes this one
     * @param displayedPageNumber the page indicator the screen displays, or {@code null}
     * @return a new backward-direction instance
     */
    public static PageMetadata backward(
            int pageSize,
            String previousCursorKey,
            String nextCursorKey,
            boolean hasMorePages,
            boolean hasPreviousPages,
            String displayedPageNumber) {
        return new PageMetadata(
                pageSize,
                previousCursorKey,
                nextCursorKey,
                PagingDirection.BACKWARD,
                hasMorePages,
                hasPreviousPages,
                displayedPageNumber);
    }

    /**
     * Returns a diagnostic representation carrying the paging state and withholding both boundary
     * cursors.
     *
     * <p><strong>Why the implicit record rendering could not stand.</strong> A record's generated
     * {@code toString()} prints every component, and two of the seven here are record keys taken
     * straight from the cluster being browsed. The card-list cursor is the widest of the three and is
     * a composite of a 16-character card number - a primary account number - followed by an
     * 11-character account identifier, declared as the sibling groups {@code WS-CA-FIRST-CARDKEY} and
     * {@code WS-CA-LAST-CARDKEY} at {@code app/cbl/COCRDLIC.cbl} lines 230 to 235. Metadata of this
     * type accompanies every page of every browse, so a generated rendering would have written
     * cardholder data into any log line, assertion failure or diagnostic dump that touched an
     * instance.</p>
     *
     * <p><strong>What is retained.</strong> The five components that describe the paging state and
     * name no record: the row count, the browse direction, the two independent availability flags and
     * the displayed page indicator. None of them identifies a cardholder - the indicator is the
     * screen's own three- or eight-character display value, never a key.</p>
     *
     * <p><strong>What is withheld.</strong> Both boundary cursors, each replaced by a fixed
     * placeholder rather than a partial mask, for the reason given on the placeholder constant. Both
     * are withheld unconditionally, including when a cursor is absent, so the rendering discloses
     * nothing about either value - not even whether one is present, which
     * {@link #hasPreviousPages()} and {@link #hasMorePages()} already report as data.</p>
     *
     * <p><strong>Only the rendering changes.</strong> {@link #previousCursorKey()},
     * {@link #nextCursorKey()}, the JSON wire form, {@code equals} and {@code hashCode} continue to
     * carry and compare both cursors byte for byte, because the client cannot resume the browse
     * without them. Decision log entry DL-081 records the arrangement.</p>
     *
     * @return the paging state, with both boundary cursors replaced by a fixed placeholder
     */
    @Override
    public String toString() {
        return "PageMetadata["
                + "pageSize=" + pageSize
                + ", previousCursorKey=" + REDACTION_PLACEHOLDER
                + ", nextCursorKey=" + REDACTION_PLACEHOLDER
                + ", direction=" + direction
                + ", hasMorePages=" + hasMorePages
                + ", hasPreviousPages=" + hasPreviousPages
                + ", displayedPageNumber=" + displayedPageNumber
                + "]";
    }

    /**
     * Direction in which the browse walks the key sequence.
     *
     * <p>Nested here because paging direction is part of this paging contract and of nothing else,
     * and because it is a screen-navigation concept rather than a persisted domain value. There
     * are exactly two constants, one per CICS browse verb; there is deliberately no third
     * "unknown" constant and no default, because the legacy programs always branch on an explicit
     * attention key.</p>
     */
    public enum PagingDirection {

        /**
         * Walk forward through the key sequence, the equivalent of the CICS {@code READNEXT}
         * browse the legacy screens issue when the operator requests the following page. The
         * accompanying rows are read ascending and presented ascending.
         */
        FORWARD,

        /**
         * Walk backward through the key sequence, the equivalent of the CICS {@code READPREV}
         * browse the legacy screens issue when the operator requests the preceding page. The
         * accompanying rows are read descending and the service reverses them so the page still
         * presents ascending, reproducing the legacy bottom-row-upward fill.
         */
        BACKWARD
    }
}
