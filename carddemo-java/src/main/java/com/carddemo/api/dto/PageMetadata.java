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

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Objects;

/**
 * Immutable cursor-plus-direction paging contract shared by the three paginated screens, reproducing
 * the CICS browse protocol as REST metadata: the key a browse restarts from, the direction the caller
 * asked for, and the two independent end-of-browse indicators the legacy screens render.
 *
 * <p><strong>The three page sizes are legacy screen shape, not tuning parameters.</strong> Changing one
 * would put a different number of rows on a screen, which is a visible behavioural regression rather
 * than a configuration change. Each was established from the member that owns the screen, and each by a
 * different mechanism: the card list presents seven rows, declared as a seven-occurrence row table in
 * {@code app/cbl/COCRDLIC.cbl}; the transaction list presents ten, established purely from loop bounds
 * in {@code app/cbl/COTRN00C.cbl} with <em>no</em> row table declared at all; the user list presents
 * ten, declared as a genuine ten-occurrence table in {@code app/cbl/COUSR00C.cbl}. All three are
 * corroborated by the row families of the corresponding symbolic map. They are exposed as three
 * separately named constants and the two equal values are neither shared nor derived from one another,
 * because they coincide by accident of two unrelated layouts.
 *
 * <p><strong>Paging is cursor-based, never offset-based.</strong> The legacy browse has no row number
 * and computes no offset: it remembers the first and last key of the page it just displayed and
 * restarts from one of them. {@link #previousCursorKey()} and {@link #nextCursorKey()} together are
 * therefore the authoritative navigation state, and {@link #displayedPageNumber()} is a display value
 * only.
 *
 * <p><strong>Both boundary keys travel on every page, because the legacy retains both at once.</strong>
 * They are not alternatives and not one value whose meaning shifts with the direction just travelled:
 * each screen declares them as two adjacent fields of one commarea group and keeps both populated
 * across every turn. The backward attention key restarts from the first key and the forward key from
 * the last, so a page in the middle of a browse - which every screen can reach, and from which either
 * attention key is live - needs both present simultaneously. A single cursor component would silently
 * strand one direction.
 *
 * <p><strong>Backward fill order is preserved by the service and is never reversed here.</strong> A
 * backward page is filled from the bottom row upward and then presented ascending, so row ordering
 * belongs to the service, which reverses the read order before building the response. This record
 * carries the direction as data and performs no ordering.
 *
 * <p><strong>The legacy browse exposes no total row count.</strong> It never counts the cluster; it
 * discovers that a further page exists by attempting one more read and observing the outcome. A
 * total-row or total-page component would be fabricated information and would force a counting query
 * the original never issued. The two conditions the screens actually know are the separate flags
 * {@link #hasMorePages()} and {@link #hasPreviousPages()}, which the legacy signals with distinct
 * messages per screen.
 *
 * <p><strong>A boundary cursor is a record key, so it crosses the wire and stays out of
 * diagnostics.</strong> The card-list cursor is a primary account number in full, which makes both
 * cursor components cardholder data even though the client must receive them to resume the browse. The
 * two obligations are separated by scope rather than traded off: the accessors and the JSON wire form
 * carry both cursors byte for byte, and {@link #toString()} withholds both (decision log DL-081).
 *
 * @param pageSize the number of screen rows this page carries, supplied by the caller. The
 *     legacy antecedent is the per-screen row count proven above — the card-list table of seven
 *     occurrences, the transaction-list loop bound of ten, and the user-list table of ten
 *     occurrences. It travels as data so a single contract serves all three screens; it is never
 *     defaulted here, and the three named constants exist so a caller or a test can name the
 *     contractual value of the screen it is serving. Bounded above by
 *     {@link #LARGEST_SCREEN_PAGE_SIZE}, the largest of those three figures, because no screen in the
 *     estate presents an eleventh row; requiring the exact figure of seven or ten is the serving
 *     endpoint's obligation rather than this record's, for the reason recorded on that constant. Like
 *     every bound here it is declarative and is evaluated only where something asks for it, so
 *     construction remains unchecked and nothing is ever clamped.
 * @param previousCursorKey the opaque record key at which a <em>backward</em> browse restarts: the
 *     key of the first row on this page, which is what the legacy programs retain as their FIRST
 *     field and reposition on when the operator asks for the preceding page — the card number, the
 *     transaction identifier or the user identifier cited above. It is opaque to the
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
        @Positive @Max(PageMetadata.LARGEST_SCREEN_PAGE_SIZE) int pageSize,
        @Size(max = PageMetadata.CURSOR_KEY_MAX_LENGTH) String previousCursorKey,
        @Size(max = PageMetadata.CURSOR_KEY_MAX_LENGTH) String nextCursorKey,
        PagingDirection direction,
        boolean hasMorePages,
        boolean hasPreviousPages,
        @Size(max = PageMetadata.DISPLAYED_PAGE_NUMBER_MAX_LENGTH) String displayedPageNumber) {

    /**
     * Card-list page size: the shape of that screen, not a tunable value. Proven from the
     * seven-occurrence row table in {@code app/cbl/COCRDLIC.cbl} and corroborated by the seven row
     * families of its symbolic map.
     */
    public static final int CARD_LIST_PAGE_SIZE = 7;

    /**
     * Transaction-list page size: the shape of that screen, not a tunable value. Proven from loop
     * bounds alone in {@code app/cbl/COTRN00C.cbl} - the program declares no row table for these rows
     * at all - and corroborated by the ten row families of its symbolic map. Declared separately from
     * {@link #USER_LIST_PAGE_SIZE}: the two values are equal by accident of unrelated layouts and were
     * proven by different mechanisms, so a divergence in one screen must not propagate to the other.
     */
    public static final int TRANSACTION_LIST_PAGE_SIZE = 10;

    /**
     * User-list page size: the shape of that screen, not a tunable value. Proven from a genuine
     * ten-occurrence row table in {@code app/cbl/COUSR00C.cbl}, unlike the transaction-list count, and
     * declared separately from {@link #TRANSACTION_LIST_PAGE_SIZE} for the reason given there.
     */
    public static final int USER_LIST_PAGE_SIZE = 10;

    /**
     * Largest row count any screen in the estate presents, and therefore the upper bound on
     * {@link #pageSize()}: ten.
     *
     * <p>The maximum of the three proven screen figures above - seven for the card list, ten for the
     * transaction list and ten for the administrative user list. It is derived from them rather than
     * chosen: no screen in the estate presents an eleventh row, so no legitimate instance of this
     * contract carries a larger count.</p>
     *
     * <p><strong>Why the bound is the maximum of the three rather than the exact figure for one.</strong>
     * One contract serves three screens with two different row counts, so this record cannot know which
     * screen it is describing and therefore cannot require seven or ten specifically. That requirement is
     * real and it is <em>screen-owned</em>, and it is discharged by the three response contracts that do
     * know which screen they are: {@link CardListResponse} refuses a page larger than
     * {@link #CARD_LIST_PAGE_SIZE}, {@link TransactionListResponse} larger than
     * {@link #TRANSACTION_LIST_PAGE_SIZE}, and {@link UserResponse} larger than
     * {@link #USER_LIST_PAGE_SIZE} - each reading the named constant here rather than a figure a caller
     * sent, so the count a page carries is a property of the screen and never of the request. All three
     * screens are served by delivered operations: {@code /api/cards/list}, {@code /api/transactions/list}
     * and {@code /api/admin/users/list}.</p>
     *
     * <p>So this bound is a ceiling that sits behind three exact figures rather than standing in for them.
     * It still earns its place, because it is evaluated at the request boundary where the exact figure is
     * not yet known: what it prevents is an inbound page size larger than any screen amplifying the work a
     * query does before the screen-specific contract ever sees the result. One residual duplication is
     * worth naming rather than glossing - the browse services carry their own private row counts, which
     * agree in value with the constants here but are separate declarations, so the agreement is asserted by
     * their tests rather than guaranteed by a single reference.</p>
     *
     * <p><strong>Why a declarative bound and not a construction check.</strong> This record is a
     * carrier, and its canonical constructor deliberately evaluates no numeric bound at all - the
     * ordered, first-error-wins check belongs to the service layer, which is the module-wide reading of
     * the estate's message-bearing cascades. A constructor that rejected a count would also make the
     * record unable to represent a page a test or a service legitimately builds with a single row.
     * A declarative bound is evaluated where a request boundary asks for it and nowhere else, which is
     * exactly the layer that needs it. What it prevents is an inbound page size larger than any screen
     * amplifying the work a query does; it is not a field edit and it reports rather than clamps, so no
     * count is silently reduced.</p>
     */
    public static final int LARGEST_SCREEN_PAGE_SIZE = 10;

    /**
     * Width in characters of the widest browse key any of the three screens retains: 16.
     *
     * <p>One bound governs both keys because every screen declares its first-key and last-key fields at
     * identical widths, so a per-key bound would be two names for one number.
     *
     * <p><strong>The widest key is the sixteen-character card number, and not a twenty-seven-character
     * composite.</strong> The three retained keys are the user identifier at eight characters
     * ({@code app/cbl/COUSR00C.cbl}), the transaction identifier at sixteen
     * ({@code app/cbl/COTRN00C.cbl}) and the card number at sixteen ({@code app/cbl/COCRDLIC.cbl}). The
     * card-list program does <em>declare</em> a twenty-seven-character work field - a sixteen-character
     * card number followed by an eleven-digit account identifier - but at every one of its four browse
     * repositioning sites, covering the backward key on the first page, the return from a detail screen,
     * the page-down key and the page-up key, only the card-number half is moved into the browse key and
     * the companion move of the account half is commented out in the source. The account identifier is
     * therefore never part of the resumption key on any turn, and a bound of twenty-seven would admit a
     * cursor no screen can produce.
     */
    public static final int CURSOR_KEY_MAX_LENGTH = 16;

    /**
     * Bound on the displayed page indicator: the widest such field across the three maps. All three are
     * alphanumeric rather than numeric, which is why the indicator crosses this API as text, and the
     * bound only reports an over-long value.
     */
    public static final int DISPLAYED_PAGE_NUMBER_MAX_LENGTH = 8;

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of each boundary cursor. A constant rather
     * than any transformation of the value, so neither the length nor a prefix nor a digest of a
     * redacted cursor survives into a stringified instance. A partial mask was rejected deliberately: a
     * card cursor is a primary account number in full, so every fragment of it is still regulated data,
     * and a digest of a fixed-width numeric key is reversible by enumeration.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Canonical constructor. Requires an explicit browse direction and leaves every other component
     * exactly as supplied: nothing is defaulted, normalised, padded or trimmed, so both cursors and the
     * page indicator cross byte for byte including any legacy space padding, and the page size is
     * accepted as given so one contract serves screens of seven and ten rows alike.
     *
     * <p>Neither cursor is derived from the other, from the direction or from the availability flags.
     * The legacy programs set their first-key and last-key fields independently as they fill a page, so
     * only the service that assembled the page knows either value, and a cursor synthesised here would
     * be a guess at a record key.
     *
     * @throws NullPointerException if {@code direction} is {@code null}, since the legacy programs
     *     branch on an explicit attention key and this contract has no default direction
     */
    public PageMetadata {
        Objects.requireNonNull(direction, "direction must be supplied explicitly");
    }

    /**
     * Creates metadata for a page reached by walking forward. Pure: returns a new instance and mutates
     * nothing. Both boundary keys are supplied because a page reached forward can still be walked
     * backward out of - the legacy leaves the backward attention key live and repositions on the
     * retained first key - so recording only the forward key would discard exactly what that path needs.
     *
     * @param pageSize            the number of screen rows the page carries
     * @param previousCursorKey   the key of the first row, from which a backward browse restarts, or
     *                            {@code null} when no page precedes this one
     * @param nextCursorKey       the key of the last row, from which a forward browse restarts, or
     *                            {@code null} when no page follows this one
     * @param hasMorePages        whether a page follows this one
     * @param hasPreviousPages    whether a page precedes this one
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
     * Creates metadata for a page reached by walking backward. Pure: returns a new instance and mutates
     * nothing, and performs no reordering - the service has already reversed the read order so the page
     * presents ascending, reproducing the legacy bottom-row-upward fill. Both boundary keys are supplied
     * for the mirror of the reason given on {@link #forward}: a page reached backward can be walked
     * forward again from the retained last key.
     *
     * @param pageSize            the number of screen rows the page carries
     * @param previousCursorKey   the key of the first row, from which a further backward browse
     *                            restarts, or {@code null} when no page precedes this one
     * @param nextCursorKey       the key of the last row, from which a forward browse restarts, or
     *                            {@code null} when no page follows this one
     * @param hasMorePages        whether a page follows this one
     * @param hasPreviousPages    whether a page precedes this one
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
     * cursors. The generated record rendering could not stand: it prints every component, and two of
     * them are record keys taken straight from the cluster being browsed - the card-list cursor is a
     * card number - while metadata of this type accompanies every page of every browse, so a generated
     * rendering would have written cardholder data into any log line or diagnostic dump that touched an
     * instance.
     *
     * <p>Retained are the five components that describe the paging state and name no record: the row
     * count, the direction, the two availability flags and the displayed page indicator, which is the
     * screen's own display value and never a key. Both cursors are replaced by a fixed placeholder
     * rather than a partial mask, and are withheld unconditionally - including when a cursor is absent -
     * so the rendering discloses nothing about either value, not even presence, which the two flags
     * already report as data.
     *
     * <p>Only the rendering changes: the accessors, the JSON wire form, {@code equals} and
     * {@code hashCode} continue to carry and compare both cursors byte for byte, because the client
     * cannot resume the browse without them (decision log DL-081).
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
     * Direction in which the browse walks the key sequence. Nested because paging direction is part of
     * this contract and of nothing else, and because it is screen navigation rather than a persisted
     * domain value. Exactly two constants, one per legacy browse verb; there is deliberately no
     * "unknown" constant and no default, because the legacy programs always branch on an explicit
     * attention key.
     */
    public enum PagingDirection {

        /**
         * Walk forward through the key sequence, as the legacy screens do when the operator requests the
         * following page. The accompanying rows are read ascending and presented ascending.
         */
        FORWARD,

        /**
         * Walk backward through the key sequence, as the legacy screens do when the operator requests
         * the preceding page. The rows are read descending and the service reverses them so the page
         * still presents ascending, reproducing the legacy bottom-row-upward fill.
         */
        BACKWARD
    }

    /**
     * The inbound half of the paging contract: which way to walk, and from which boundary key.
     *
     * <p>This is what a client is entitled to choose, and it is deliberately much less than what the
     * enclosing record reports. A paginated request supplies the two boundary keys it was last given
     * and the direction the operator's attention key implies, and nothing else. The enclosing
     * {@link PageMetadata} is the response-side statement of paging <em>state</em>, and four of its
     * seven components are facts only the server can know:</p>
     *
     * <ul>
     *   <li>the page size, which is the legacy screen's row count - seven for the card list and ten for
     *       the transaction and user lists, each an independent screen shape rather than a tunable
     *       figure - so a request that carried it could put a different number of rows on a screen;</li>
     *   <li>{@link PageMetadata#hasMorePages()} and {@link PageMetadata#hasPreviousPages()}, which the
     *       legacy browse discovers only by attempting the next read, so they are outcomes of walking
     *       the key sequence and cannot be asserted by the caller that asked for the walk;</li>
     *   <li>the displayed page indicator, which the legacy programs only ever <em>write</em> to the
     *       screen - {@code app/cbl/COUSR00C.cbl} assigns it at lines 327 and 376,
     *       {@code app/cbl/COTRN00C.cbl} at lines 324 and 373 and {@code app/cbl/COCRDLIC.cbl} at line
     *       667, and none of the three ever reads it back - so it is display state, never authority.</li>
     * </ul>
     *
     * <p>Separating the two shapes is what keeps that asymmetry structural rather than documentary.
     * Reusing the enclosing record as a request body would make every one of those four values
     * client-supplied, and a value the server needs but the client controls is a value the client can
     * be wrong about. Nesting the request shape here rather than declaring a further top-level type
     * keeps the two halves of one contract adjacent, and keeps this package at its declared size.</p>
     *
     * <p><strong>The direction may legitimately be absent here, unlike in the enclosing record.</strong>
     * The enclosing record requires it, because a page that has already been assembled was necessarily
     * reached in one direction or the other. A request has no such history: the first entry to a list
     * screen arrives on the enter key rather than on a paging key, and the legacy key evaluation has no
     * catch-all branch and applies no default, so an absent direction is the faithful representation of
     * "not a paging action". Resolving it from the accompanying attention key belongs to the service
     * that owns the browse, which is also where the higher function keys are folded onto the lower
     * twelve. No default is applied and none may be inferred here.</p>
     *
     * <p>Both keys are opaque. Neither is parsed, compared, ordered, truncated, padded or re-cased, and
     * neither is derived from the other; each is bounded only at the widest key the three browses use,
     * which is the twenty-seven characters {@link PageMetadata#CURSOR_KEY_MAX_LENGTH} records. A bound
     * measures and never alters, so a space-padded key survives byte for byte.</p>
     *
     * @param previousCursorKey the record key at which a backward browse resumes, echoed from the
     *     boundary key the previous response reported. Opaque, at most
     *     {@link PageMetadata#CURSOR_KEY_MAX_LENGTH} characters, and {@code null} on a first entry
     * @param nextCursorKey the record key at which a forward browse resumes, echoed on the same terms
     *     and likewise {@code null} on a first entry
     * @param direction the way the browse should walk, or {@code null} when the request is not a paging
     *     action; see the paragraph above for why absence is legitimate on the inbound side
     */
    public record PageCursorRequest(
            @Size(max = PageMetadata.CURSOR_KEY_MAX_LENGTH) String previousCursorKey,
            @Size(max = PageMetadata.CURSOR_KEY_MAX_LENGTH) String nextCursorKey,
            PagingDirection direction) {

        /**
         * Renders the request with both boundary keys withheld, for the same reason the enclosing
         * record withholds them.
         *
         * <p>A boundary key is a record key, and on the card-list browse the record key <em>is</em> the
         * sixteen-character card number. Stringifying this object into a log line, an exception message,
         * a diagnostic dump or a test-failure report would put that number in a diagnostic channel that
         * has none of the protections the response body has. The direction is not sensitive and is
         * rendered as it stands.</p>
         *
         * <p>Only the rendering changes. {@link #previousCursorKey()}, {@link #nextCursorKey()}, the
         * JSON wire form, {@code equals} and {@code hashCode} continue to carry and compare both keys
         * byte for byte, because the server cannot resume the browse without them. The arrangement
         * mirrors decision log entry DL-081.</p>
         *
         * @return the inbound paging choice, with both boundary keys replaced by a fixed placeholder
         */
        @Override
        public String toString() {
            return "PageCursorRequest["
                    + "previousCursorKey=" + REDACTION_PLACEHOLDER
                    + ", nextCursorKey=" + REDACTION_PLACEHOLDER
                    + ", direction=" + direction
                    + "]";
        }
    }
}
