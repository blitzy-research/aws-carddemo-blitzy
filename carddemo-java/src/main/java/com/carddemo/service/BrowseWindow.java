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

import java.util.Objects;

/**
 * One assembled page of a cursor-based browse, as a type the service layer owns: the row count, both
 * boundary cursors, the direction the page was reached in, the two availability flags and the page
 * indicator the screen displays.
 *
 * <p>The legacy antecedents are the three paginated screens and their programs. Card list assembles seven
 * rows from the seven-occurrence table of {@code app/cbl/COCRDLIC.cbl}; user list assembles ten from the
 * ten-occurrence table of {@code app/cbl/COUSR00C.cbl}; transaction list assembles ten established purely
 * by loop bounds in {@code app/cbl/COTRN00C.cbl}. All three walk the key sequence with
 * {@code STARTBR}/{@code READNEXT}/{@code READPREV}/{@code ENDBR} and retain the first and last key of the
 * displayed page so either direction can resume, which is exactly what the two cursors here carry.
 *
 * <p><strong>Why this is not the wire record.</strong> The wire form lives in {@code api.dto} with its own
 * bounds and its own serialization contract, and the module's layering forbids a service depending upward
 * on the API package. This type carries the same seven components under the same names, and one adapter in
 * the API layer converts between the two.
 *
 * <p><strong>Neither cursor is derived from anything.</strong> Not from the other cursor, not from the
 * direction and not from the availability flags. The legacy programs set their first-key and last-key
 * fields independently as they fill a page, so only the service that assembled the page knows either
 * value, and a cursor synthesised here would be a guess at a record key.
 *
 * <p><strong>Direction is explicit and has no default.</strong> The legacy programs branch on an explicit
 * attention key, so there is no "unknown" direction to fall back to and the canonical constructor refuses
 * an absent one.
 *
 * <p><strong>Page size is accepted as given.</strong> One carrier serves screens of seven and ten rows
 * alike, and it is neither validated against those figures nor defaulted to either, because a partial page
 * is an ordinary outcome and a single-row page is a page a service legitimately assembles.
 *
 * <p>{@link #toString()} withholds both boundary cursors, because a boundary cursor is a record key and on
 * the card-list browse that key <em>is</em> the sixteen-character card number.
 *
 * <p>Deeply immutable: every component is a scalar or a {@code String}, so an instance is safe for
 * unsynchronised concurrent use.
 *
 * <p>Provenance: {@code app/cbl/COCRDLIC.cbl}, {@code app/cbl/COUSR00C.cbl} and
 * {@code app/cbl/COTRN00C.cbl}, read as read-only reference at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No program text is transcribed.
 *
 * @param pageSize the number of screen rows the assembled page carries
 * @param previousCursorKey the key of the first row, from which a backward browse restarts, or
 *        {@code null} when no page precedes this one
 * @param nextCursorKey the key of the last row, from which a forward browse restarts, or {@code null}
 *        when no page follows this one
 * @param direction the direction this page was reached in, never {@code null}
 * @param hasMorePages whether a page follows this one
 * @param hasPreviousPages whether a page precedes this one
 * @param displayedPageNumber the page indicator the screen displays, or {@code null}
 * @since 1.0.0
 */
public record BrowseWindow(
        int pageSize,
        String previousCursorKey,
        String nextCursorKey,
        PagingDirection direction,
        boolean hasMorePages,
        boolean hasPreviousPages,
        String displayedPageNumber) {

    /**
     * Card-list page size: the shape of that screen, not a tunable value. Proven from the seven-occurrence
     * row table in {@code app/cbl/COCRDLIC.cbl}.
     */
    public static final int CARD_LIST_PAGE_SIZE = 7;

    /**
     * Transaction-list page size, established by the loop bounds of {@code app/cbl/COTRN00C.cbl} rather
     * than by an occurrence table.
     */
    public static final int TRANSACTION_LIST_PAGE_SIZE = 10;

    /**
     * User-list page size, proven from the ten-occurrence row table of {@code app/cbl/COUSR00C.cbl}.
     *
     * <p>Declared separately from the transaction-list figure even though the two coincide, because they
     * are the shapes of two unrelated screens and a change to one must not propagate to the other.
     */
    public static final int USER_LIST_PAGE_SIZE = 10;

    /** The largest row count any of the three screens presents, for a caller that needs a ceiling. */
    public static final int LARGEST_SCREEN_PAGE_SIZE = 10;

    /** Width in characters of a boundary cursor: the widest browse key in the estate, the card number. */
    public static final int CURSOR_KEY_MAX_LENGTH = 16;

    /** Width in characters of the displayed page indicator. */
    public static final int DISPLAYED_PAGE_NUMBER_MAX_LENGTH = 8;

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of each boundary cursor.
     *
     * <p>A constant rather than any transformation of the value, so neither the length nor a prefix nor a
     * digest of a withheld cursor survives into a stringified instance. A partial mask was rejected
     * deliberately: a card cursor is a primary account number in full, so every fragment of it is still
     * regulated data, and a digest of a fixed-width numeric key is reversible by enumeration.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Canonical constructor. Requires an explicit browse direction and leaves every other component
     * exactly as supplied: nothing is defaulted, normalised, padded or trimmed, so both cursors and the
     * page indicator cross byte for byte including any legacy space padding, and the page size is
     * accepted as given so one carrier serves screens of seven and ten rows alike.
     *
     * @throws NullPointerException if {@code direction} is {@code null}
     */
    public BrowseWindow {
        Objects.requireNonNull(direction, "direction must be supplied explicitly");
    }

    /**
     * Creates a window for a page reached by walking forward.
     *
     * <p>Both boundary keys are supplied because a page reached forward can still be walked backward out
     * of - the legacy leaves the backward attention key live and repositions on the retained first key -
     * so recording only the forward key would discard exactly what that path needs.
     *
     * @param pageSize the number of screen rows the page carries
     * @param previousCursorKey the key of the first row, or {@code null} when no page precedes this one
     * @param nextCursorKey the key of the last row, or {@code null} when no page follows this one
     * @param hasMorePages whether a page follows this one
     * @param hasPreviousPages whether a page precedes this one
     * @param displayedPageNumber the page indicator the screen displays, or {@code null}
     * @return a new forward-direction window
     */
    public static BrowseWindow forward(
            final int pageSize,
            final String previousCursorKey,
            final String nextCursorKey,
            final boolean hasMorePages,
            final boolean hasPreviousPages,
            final String displayedPageNumber) {
        return new BrowseWindow(
                pageSize,
                previousCursorKey,
                nextCursorKey,
                PagingDirection.FORWARD,
                hasMorePages,
                hasPreviousPages,
                displayedPageNumber);
    }

    /**
     * Creates a window for a page reached by walking backward.
     *
     * <p>Performs no reordering: the service has already reversed the read order so the page presents
     * ascending, reproducing the legacy bottom-row-upward fill. Both boundary keys are supplied for the
     * mirror of the reason given on {@link #forward}.
     *
     * @param pageSize the number of screen rows the page carries
     * @param previousCursorKey the key of the first row, or {@code null} when no page precedes this one
     * @param nextCursorKey the key of the last row, or {@code null} when no page follows this one
     * @param hasMorePages whether a page follows this one
     * @param hasPreviousPages whether a page precedes this one
     * @param displayedPageNumber the page indicator the screen displays, or {@code null}
     * @return a new backward-direction window
     */
    public static BrowseWindow backward(
            final int pageSize,
            final String previousCursorKey,
            final String nextCursorKey,
            final boolean hasMorePages,
            final boolean hasPreviousPages,
            final String displayedPageNumber) {
        return new BrowseWindow(
                pageSize,
                previousCursorKey,
                nextCursorKey,
                PagingDirection.BACKWARD,
                hasMorePages,
                hasPreviousPages,
                displayedPageNumber);
    }

    /**
     * Returns a diagnostic representation carrying the paging state and withholding both boundary cursors.
     *
     * <p>Only the rendering changes: the accessors, {@code equals} and {@code hashCode} continue to carry
     * and compare both cursors byte for byte, because the browse cannot be resumed without them.
     *
     * @return the paging state, with both boundary cursors replaced by a fixed placeholder
     */
    @Override
    public String toString() {
        return "BrowseWindow["
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
     * <p>Exactly two constants, one per legacy browse verb; there is deliberately no "unknown" constant
     * and no default, because the legacy programs always branch on an explicit attention key.
     */
    public enum PagingDirection {

        /**
         * Walk forward through the key sequence, as the legacy screens do when the operator requests the
         * following page. The accompanying rows are read ascending and presented ascending.
         */
        FORWARD,

        /**
         * Walk backward through the key sequence. The legacy reads descending and fills its row slots from
         * the last downward, so the assembled page still presents ascending.
         */
        BACKWARD
    }

    /**
     * The inbound half of the browse contract: the two boundary keys a paginated submission echoes back
     * and the direction it asks for.
     *
     * <p>Separate from the enclosing window because a submission carries strictly less: it cannot know the
     * row count of the page it is asking for, nor whether a further page exists in either direction, nor
     * what indicator that page will display. Every one of those four is discovered while the page is
     * assembled, so requiring them inbound would ask a client to predict them.
     *
     * <p>Both keys may legitimately be absent: the first turn of a browse has nothing to resume from.
     *
     * @param previousCursorKey the key at which a backward browse resumes, echoed from the last page, or
     *        {@code null}
     * @param nextCursorKey the key at which a forward browse resumes, echoed on the same terms, or
     *        {@code null}
     * @param direction the direction the submission asks for, or {@code null} when it asks for neither
     * @since 1.0.0
     */
    public record CursorRequest(String previousCursorKey, String nextCursorKey,
            PagingDirection direction) {

        /**
         * Renders the request with both boundary keys withheld, for the same reason the enclosing window
         * withholds them.
         *
         * <p>Only the rendering changes. The accessors, {@code equals} and {@code hashCode} continue to
         * carry and compare both keys byte for byte, because the browse cannot be resumed without them.
         *
         * @return the inbound paging choice, with both boundary keys replaced by a fixed placeholder
         */
        @Override
        public String toString() {
            return "CursorRequest["
                    + "previousCursorKey=" + REDACTION_PLACEHOLDER
                    + ", nextCursorKey=" + REDACTION_PLACEHOLDER
                    + ", direction=" + direction
                    + "]";
        }
    }
}
