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
import java.math.BigDecimal;
import java.util.List;

/**
 * Immutable response for the transaction-list screen, legacy transaction {@code CT00}.
 *
 * <p>Values are not defaulted or normalised on the way out; the rows arrive in the order they are to
 * be presented. <strong>The one thing refused is a page longer than the screen</strong>: the map
 * declares ten row slots, so an eleventh row corresponds to no slot the legacy screen ever rendered
 * and is reported to the producer at construction rather than published to a client.
 *
 * <p><strong>Row order is screen order, ascending, on both directions of travel.</strong> A forward
 * page is read ascending and presented ascending. A backward page is read descending, because the
 * legacy browse fills the bottom row first and works upward, and the service reverses that read order
 * before building this response so the page still presents ascending - which is what the legacy
 * screen showed once the fill completed. This type performs no reordering of its own; it publishes
 * what it is given, and what it is given is already in presentation order.
 *
 * <p>Row amounts are exact decimals at the scale the transaction record's picture clause declares.
 * The row's canonical constructor <em>refuses</em> a value at any other scale rather than re-scaling
 * it: rounding belongs to the module's zoned-decimal codec alone, so a wrong scale is reported to the
 * producer at construction instead of being quietly repaired into a value a client would receive as
 * though it had been published that way.
 *
 * <p>Paging is cursor-based and this type performs no paging arithmetic: {@link PageMetadata} carries
 * the cursors and direction the legacy browse carried.
 */
public record TransactionListResponse(
        List<TransactionRow> rows,
        PageMetadata pageMetadata,
        NavigationContext navigationContext,
        String nextRoute,
        @Size(max = TransactionListResponse.TRANSACTION_ID_LENGTH) String transactionIdFilter,
        @Size(max = TransactionListResponse.DISPLAYED_PAGE_NUMBER_LENGTH) String displayedPageNumber,
        @Size(max = TransactionListResponse.MESSAGE_LENGTH) String message,
        boolean error,
        @Size(max = TransactionListResponse.SCREEN_FIELD_ID_LENGTH) String focusScreenFieldId,
        @Size(max = TransactionListResponse.SCREEN_TITLE_LENGTH) String title01,
        @Size(max = TransactionListResponse.SCREEN_TITLE_LENGTH) String title02,
        @Size(max = TransactionListResponse.CURRENT_DATE_LENGTH) String currentDate,
        @Size(max = TransactionListResponse.CURRENT_TIME_LENGTH) String currentTime,
        @Size(max = TransactionListResponse.TRANSACTION_NAME_LENGTH) String transactionName,
        @Size(max = TransactionListResponse.PROGRAM_NAME_LENGTH) String programName) {
    public static final int SELECTION_LENGTH = 1;

    public static final int TRANSACTION_ID_LENGTH = 16;

    public static final int DISPLAYED_DATE_LENGTH = 8;

    public static final int DESCRIPTION_LENGTH = 26;

    public static final int AMOUNT_SCALE = 2;

    public static final int AMOUNT_INTEGER_DIGITS = 9;

    public static final int DISPLAYED_PAGE_NUMBER_LENGTH = 8;

    public static final int MESSAGE_LENGTH = 78;

    public static final int SCREEN_FIELD_ID_LENGTH = 7;

    public static final int SCREEN_TITLE_LENGTH = 40;

    public static final int CURRENT_DATE_LENGTH = 8;

    public static final int CURRENT_TIME_LENGTH = 8;

    public static final int TRANSACTION_NAME_LENGTH = 4;

    public static final int PROGRAM_NAME_LENGTH = 8;

    public static final String MESSAGE_INVALID_SELECTION = "Invalid selection. Valid value is S";

    public static final String MESSAGE_TRAN_ID_NOT_NUMERIC = "Tran ID must be Numeric ...";

    public static final String MESSAGE_ALREADY_AT_TOP = "You are already at the top of the page...";

    public static final String MESSAGE_ALREADY_AT_BOTTOM = "You are already at the bottom of the page...";

    public static final String MESSAGE_AT_TOP = "You are at the top of the page...";

    public static final String MESSAGE_REACHED_BOTTOM = "You have reached the bottom of the page...";

    public static final String MESSAGE_REACHED_TOP = "You have reached the top of the page...";

    /**
     * Normalizes the row collection and refuses a page longer than the screen can present.
     *
     * <p>A {@code null} collection becomes the empty immutable list, and a non-{@code null} one is
     * defensively copied with {@link List#copyOf(java.util.Collection)}, which detaches it from the
     * caller and rejects a {@code null} element. Order, length and content are otherwise preserved
     * exactly: the rows are already in presentation order when they arrive and nothing here re-sorts,
     * filters, de-duplicates or pads them.
     *
     * <p><strong>Two independent bounds are checked, and they are not the same bound.</strong> The
     * first is structural: {@link PageMetadata#TRANSACTION_LIST_PAGE_SIZE} is the number of row slots
     * {@code app/cbl/COTRN00C.cbl} fills, established by its loop bounds at lines 290 and 297 rather
     * than by any {@code OCCURS} clause, and no submission of the legacy screen could ever have
     * produced an eleventh row. The figure is taken from the named constant rather than restated here,
     * so the screen's shape is declared in one place. The second compares the page against the paging
     * metadata actually travelling with it: a response whose row count exceeds the page size its own
     * metadata declares describes two different pages at once, and a client that trusted the metadata
     * would silently drop rows or mis-attribute them to the wrong page. That check is skipped when no
     * metadata accompanies the response, which is the ordinary shape for an error or first-entry
     * screen that presents no page at all.
     *
     * <p>Both bounds refuse rather than truncate. Truncating would discard a row the producer believed
     * it had published and would make an internal defect look like a short page to every client.
     *
     * @throws NullPointerException if the row list contains a {@code null} element, which
     *     {@link List#copyOf(java.util.Collection)} does not admit; a row is either present or the
     *     list is shorter
     * @throws IllegalArgumentException if the row list holds more entries than the screen has row
     *     slots, or more entries than the accompanying paging metadata declares as its page size
     */
    public TransactionListResponse {
        rows = (rows == null) ? List.of() : List.copyOf(rows);
        if (rows.size() > PageMetadata.TRANSACTION_LIST_PAGE_SIZE) {
            throw new IllegalArgumentException("rows may hold at most "
                    + PageMetadata.TRANSACTION_LIST_PAGE_SIZE + " entries, because that is how many"
                    + " row slots the transaction-list screen has, but it holds " + rows.size());
        }
        if (pageMetadata != null && rows.size() > pageMetadata.pageSize()) {
            throw new IllegalArgumentException("rows may hold at most the " + pageMetadata.pageSize()
                    + " entries the accompanying paging metadata declares as its page size, but it"
                    + " holds " + rows.size());
        }
    }

    /**
     * One row of the transaction-list screen &mdash; the five values a single row family of
     * {@code app/cpy-bms/COTRN00.CPY} presents, modelled once instead of ten times.
     *
     * <p>Every family has an identical shape, so one record type carried in a list reproduces the
     * screen exactly while collapsing what would otherwise be fifty discrete components. The
     * generated field-name suffixes are not reproduced - they are inconsistent artefacts of how the
     * map generator forms names and carry no meaning - and a row's position is its index in the
     * enclosing list. The generated length, flag and attribute items and the terminal-area filler are
     * likewise absent as terminal plumbing.</p>
     *
     * <p>Every component may be {@code null} or blank, because a row the browse did not fill is blank
     * on the legacy screen, and none is validated beyond its declared width.</p>
     *
     * @param selection the selection indicator echoed back for this row. Echoed only: nothing here
     *     interprets it. Blank is the ordinary state of an unselected row.
     * @param transactionId the transaction identifier, sixteen alphanumeric characters. Text, never a
     *     number: leading zeros and the external width are contractual.
     * @param displayedDate the date as this screen renders it, carried verbatim and never parsed,
     *     reformatted or widened; see {@link TransactionListResponse#DISPLAYED_DATE_LENGTH}.
     * @param description the description as this screen presents it - a genuine truncation of the
     *     stored value; see {@link TransactionListResponse#DESCRIPTION_LENGTH}.
     * @param amount the transaction amount as an exact decimal at a scale of two. Legitimately
     *     negative for returns, which is why no sign constraint applies. The numeric value only: the
     *     edited form the screen displays is never carried here, and nothing in this package
     *     rescales, rounds or formats it. May be {@code null} where the row is blank.
     */
    public record TransactionRow(
            @Size(max = TransactionListResponse.SELECTION_LENGTH) String selection,
            @Size(max = TransactionListResponse.TRANSACTION_ID_LENGTH) String transactionId,
            @Size(max = TransactionListResponse.DISPLAYED_DATE_LENGTH) String displayedDate,
            @Size(max = TransactionListResponse.DESCRIPTION_LENGTH) String description,
            BigDecimal amount) {
        public TransactionRow {
            requireRecordShape(amount);
        }

        @Override
        public String toString() {
            return "TransactionRow["
                    + "selection=" + selection
                    + ", transactionId=" + REDACTION_PLACEHOLDER
                    + ", displayedDate=" + displayedDate
                    + ", description=" + REDACTION_PLACEHOLDER
                    + ", amount=" + REDACTION_PLACEHOLDER
                    + "]";
        }
    }

    private static void requireRecordShape(final BigDecimal amount) {
        if (amount == null) {
            return;
        }
        if (amount.scale() != AMOUNT_SCALE) {
            throw new IllegalArgumentException("amount must carry scale " + AMOUNT_SCALE
                    + ", because its record field stores two decimal places, but its scale is "
                    + amount.scale());
        }
        final int integerDigits = amount.precision() - amount.scale();
        if (integerDigits > AMOUNT_INTEGER_DIGITS) {
            throw new IllegalArgumentException("amount must fit " + AMOUNT_INTEGER_DIGITS
                    + " integer digits, because that is the width of its record field, but it needs "
                    + integerDigits);
        }
    }

    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    @Override
    public String toString() {
        return "TransactionListResponse["
                + "rowCount=" + rows.size()
                + ", rows=" + REDACTION_PLACEHOLDER
                + ", pageMetadata=" + REDACTION_PLACEHOLDER
                + ", navigationContext=" + navigationContext
                + ", nextRoute=" + nextRoute
                + ", transactionIdFilter=" + REDACTION_PLACEHOLDER
                + ", displayedPageNumber=" + displayedPageNumber
                + ", message=" + message
                + ", error=" + error
                + ", focusScreenFieldId=" + focusScreenFieldId
                + ", title01=" + title01
                + ", title02=" + title02
                + ", currentDate=" + currentDate
                + ", currentTime=" + currentTime
                + ", transactionName=" + transactionName
                + ", programName=" + programName
                + "]";
    }
}
