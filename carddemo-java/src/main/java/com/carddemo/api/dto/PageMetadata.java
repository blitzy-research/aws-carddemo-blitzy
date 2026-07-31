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
 * protocol. It carries the key the next browse starts from, the direction the caller asked for,
 * and the two independent end-of-browse indicators the legacy screens render.</p>
 *
 * <p><strong>The three page sizes are legacy screen shape, not tuning parameters.</strong> Each
 * was established independently, from the source member that owns the screen, and each by a
 * different mechanism. Changing any one of them would put a different number of rows on a screen,
 * which is a visible behavioural regression rather than a configuration change. They are exposed
 * as three separately named constants, and the two values that happen to be equal are
 * deliberately <em>not</em> shared and <em>not</em> derived from one another, because they
 * coincide by accident of two unrelated screen layouts:</p>
 * <ul>
 *   <li><strong>Card list, seven rows</strong> — {@code app/cbl/COCRDLIC.cbl} lines 250 to 260
 *       declare a 196-character all-rows screen area redefined as a table of seven occurrences
 *       named {@code WS-SCREEN-ROWS} at line 255, whose element is 28 characters wide (an
 *       11-character account identifier, a 16-character card number and a 1-character status
 *       indicator), so 28 multiplied by 7 accounts for all 196 characters. Corroborated by the
 *       screen-line counter {@code WS-MAX-SCREEN-LINES}, and by
 *       {@code app/cpy-bms/COCRDLI.CPY} carrying exactly seven row families.</li>
 *   <li><strong>Transaction list, ten rows</strong> — {@code app/cbl/COTRN00C.cbl} establishes the
 *       count <em>purely from loop bounds</em>: the row-clearing loop bound is ten at line 290,
 *       the row index is reset to one at line 295, and the row-filling loop at line 297 stops
 *       once the index reaches eleven. <em>No table declaration exists for these rows at all.</em>
 *       Corroborated by {@code app/cpy-bms/COTRN00.CPY} carrying exactly ten row families.</li>
 *   <li><strong>User list, ten rows</strong> — {@code app/cbl/COUSR00C.cbl} lines 56 and 57
 *       declare the screen row group {@code USER-REC} as a table of ten occurrences. Corroborated
 *       by {@code app/cpy-bms/COUSR00.CPY} carrying exactly ten row families. This is a genuine
 *       table, a different mechanism from the transaction count above.</li>
 * </ul>
 *
 * <p><strong>Paging is cursor-based, never offset-based.</strong> The legacy browse has no row
 * number and computes no offset; it remembers the first and last key of the page it just
 * displayed and restarts the browse from one of them. Card paging carries a composite first and
 * last key of 27 characters, a 16-character card number followed by an 11-character account
 * identifier, at {@code app/cbl/COCRDLIC.cbl} lines 230 to 235. Transaction paging carries a
 * 16-character first and last transaction identifier at {@code app/cbl/COTRN00C.cbl} lines 63 and
 * 64. User paging carries an 8-character first and last user identifier at
 * {@code app/cbl/COUSR00C.cbl} lines 68 and 69. Accordingly {@link #cursorKey()} is the
 * authoritative navigation state and {@link #displayedPageNumber()} is a display value only.</p>
 *
 * <p><strong>Backward fill order is preserved by the service, and is never reversed here.</strong>
 * A backward page is filled from the bottom row upward and then presented ascending: the card list
 * seeds its fill counter one past the screen-line count and decrements it, filling rows seven down
 * to one; the transaction list enters its backward paragraph at {@code app/cbl/COTRN00C.cbl}
 * line 333, seeds the row index to ten at line 349 and fills slots ten down to one in the loop at
 * lines 351 to 357, reading backward at line 352; the user list has the same shape. Row ordering
 * therefore belongs to the service, which reverses the read order before it builds the response.
 * This record carries the direction as data and performs no ordering of any kind.</p>
 *
 * <p><strong>The legacy browse exposes no total row count.</strong> It never counts the cluster;
 * it discovers that a further page exists by attempting one more read and observing the outcome —
 * see the forward guard at {@code app/cbl/COTRN00C.cbl} lines 285 to 287 and its mirror on the
 * backward path. A total-row or total-page component would therefore be fabricated information and
 * would force a counting query the original never issued. The two conditions the screens actually
 * know are modelled as the separate flags {@link #hasMorePages()} and
 * {@link #hasPreviousPages()}, which the legacy signals with distinct messages: the card list
 * reports the absence of a following page and the absence of a preceding page with two different
 * texts, and the transaction and user lists report reaching the bottom and reaching the top with
 * two more.</p>
 *
 * <p>This type is framework-free by design: it references no persistence, web or data-access
 * abstraction, so the paging contract is defined by the legacy screens rather than by a library.
 * It is deeply immutable — every component is a primitive, a {@code String} or an enum constant —
 * and every factory is pure, returning a new instance and mutating nothing.</p>
 *
 * <p>Migration provenance: derived from the CardDemo mainframe estate at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL source text is reproduced
 * here; the citations above name members, identifiers, widths, counts and line numbers only.</p>
 *
 * @param pageSize the number of screen rows this page carries, supplied by the caller. The
 *     legacy antecedent is the per-screen row count proven above — the card-list table of seven
 *     occurrences, the transaction-list loop bound of ten, and the user-list table of ten
 *     occurrences. It travels as data so a single contract serves all three screens; it is never
 *     defaulted here, and the three named constants exist so a caller or a test can name the
 *     contractual value of the screen it is serving.
 * @param cursorKey the opaque record key at which the next browse starts, corresponding to the
 *     first or last key the legacy programs retain across a pseudo-conversational turn — the
 *     card composite key, the transaction identifier or the user identifier cited above. It is
 *     opaque to the client, is never interpreted as a row number, and is {@code null} on the
 *     first request of a browse, where the legacy positions at the low or high key of the
 *     cluster instead.
 * @param direction the browse direction the caller asked for, mirroring the explicit attention
 *     key the legacy programs branch on. Always supplied; there is no default.
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
 *     Display value only — {@code cursorKey} is authoritative for navigation. May be
 *     {@code null} when the screen has no page indicator to render.
 */
public record PageMetadata(
        @Positive int pageSize,
        @Size(max = PageMetadata.CURSOR_KEY_MAX_LENGTH) String cursorKey,
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
     * {@link #cursorKey()} at the widest legacy key means no legal cursor is ever rejected, and
     * the bound only reports over-long input — it never alters, pads or trims a value.</p>
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
     * Canonical constructor. Requires an explicit browse direction and leaves every other
     * component exactly as supplied.
     *
     * <p>No component is defaulted, normalised, padded or trimmed: the cursor and the displayed
     * page indicator cross this boundary byte for byte, including any leading or trailing space
     * the legacy fixed-width fields carry. The page size is accepted as given, so one contract
     * serves screens of seven and ten rows alike.</p>
     *
     * @throws NullPointerException if {@code direction} is {@code null}, since the legacy programs
     *     branch on an explicit attention key and this contract therefore has no default direction
     */
    public PageMetadata {
        Objects.requireNonNull(direction, "direction must be supplied explicitly");
    }

    /**
     * Creates metadata for a page walked forward from the given cursor, the REST equivalent of a
     * {@code READNEXT} browse.
     *
     * <p>Pure factory: it returns a new instance and mutates nothing.</p>
     *
     * @param pageSize the number of screen rows the page carries
     * @param cursorKey the opaque record key the next forward browse starts from, or {@code null}
     *     at the start of a browse
     * @param hasMorePages whether a page follows this one
     * @param hasPreviousPages whether a page precedes this one
     * @param displayedPageNumber the page indicator the screen displays, or {@code null}
     * @return a new forward-direction instance
     */
    public static PageMetadata forward(
            int pageSize,
            String cursorKey,
            boolean hasMorePages,
            boolean hasPreviousPages,
            String displayedPageNumber) {
        return new PageMetadata(
                pageSize,
                cursorKey,
                PagingDirection.FORWARD,
                hasMorePages,
                hasPreviousPages,
                displayedPageNumber);
    }

    /**
     * Creates metadata for a page walked backward from the given cursor, the REST equivalent of a
     * {@code READPREV} browse.
     *
     * <p>Pure factory: it returns a new instance and mutates nothing. It performs no reordering —
     * the service has already reversed the read order so that the page it accompanies presents
     * ascending, exactly as the legacy screens fill a backward page from the bottom row upward.</p>
     *
     * @param pageSize the number of screen rows the page carries
     * @param cursorKey the opaque record key the next backward browse starts from, or {@code null}
     *     at the start of a browse
     * @param hasMorePages whether a page follows this one
     * @param hasPreviousPages whether a page precedes this one
     * @param displayedPageNumber the page indicator the screen displays, or {@code null}
     * @return a new backward-direction instance
     */
    public static PageMetadata backward(
            int pageSize,
            String cursorKey,
            boolean hasMorePages,
            boolean hasPreviousPages,
            String displayedPageNumber) {
        return new PageMetadata(
                pageSize,
                cursorKey,
                PagingDirection.BACKWARD,
                hasMorePages,
                hasPreviousPages,
                displayedPageNumber);
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
