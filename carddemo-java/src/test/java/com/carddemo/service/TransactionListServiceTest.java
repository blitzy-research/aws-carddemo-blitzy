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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.TransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for the transaction-list transaction.
 *
 * <p>The class under test is the migrated form of {@code app/cbl/COTRN00C.cbl} - transaction
 * {@code CT00}, 699 lines, 16 paragraphs - at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p>Every expected message, identifier, padding width and route token below is a literal declared in
 * this class, so the oracle is independent of the code it judges: no expected value is obtained by
 * calling the service, the message catalogue or the navigation service. Fixed-width padding is written
 * as an explicit repeat count so the count is visible to a reviewer, and no fixed-width value is
 * trimmed before comparison.
 *
 * <p>The repository is a stub that behaves like an ordered cluster: it honours the sort direction and
 * the window the service asks for, and it records every page request so the tests can assert what was
 * asked rather than only what came back. That is what makes the descending-read assertion possible.
 *
 * <p>Five behaviours carry parity traps and are asserted deliberately rather than incidentally.
 *
 * <ol>
 *   <li><em>A backward page is read descending and presented ascending.</em> Paging forward and then
 *       back must return the earlier page's ten identifiers in the same ascending order. This is the
 *       assertion that fails if the reversal is dropped.</li>
 *   <li><em>The two guard reads step past the boundary key.</em> The enter key includes its filter row;
 *       the eighth program function key excludes the cursor row; the seventh excludes it going the other
 *       way.</li>
 *   <li><em>The source code keeps its trailing spaces</em> and the amount keeps its scale, because both
 *       cross this layer untouched.</li>
 *   <li><em>The unmapped-key message is the fifty-byte catalogue text, untrimmed.</em></li>
 *   <li><em>Two error paths fall through rather than returning.</em> An unsupported row action and a
 *       non-numeric filter both leave the paragraph running.</li>
 * </ol>
 */
@DisplayName("Transaction list service: the CT00 browse transaction")
final class TransactionListServiceTest {

    /** Fixed instant so the screen header is deterministic; the stamp date of the legacy member. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:34Z"), ZoneOffset.UTC);

    /** Header renderings the fixed instant must produce, written independently of the service. */
    private static final String EXPECTED_HEADER_DATE = "07/19/22";

    private static final String EXPECTED_HEADER_TIME = "23:12:34";

    /**
     * The common catalogue's invalid-key text at its declared fifty-character width: forty characters
     * of text followed by ten spaces, exactly as {@code app/cpy/CSMSG01Y.cpy} declares it.
     */
    private static final String EXPECTED_INVALID_KEY_MESSAGE =
            "Invalid key pressed. Please see below..." + " ".repeat(10);

    /** Width of that message, which is also the assertion of test (g). */
    private static final int COMMON_MESSAGE_WIDTH = 50;

    /** Screen rows a page carries, asserted rather than imported from the service. */
    private static final int EXPECTED_PAGE_SIZE = 10;

    /** A source code at its stored ten-character width, trailing spaces included. */
    private static final String PADDED_SOURCE = "POS TERM  ";

    /** A description at a width the screen would truncate, so truncation here would be visible. */
    private static final String DESCRIPTION = "PURCHASE AT A MERCHANT OF SOME KIND";

    /** Fixed timestamps at their stored twenty-six-character width. */
    private static final String ORIGINATION_TIMESTAMP = "2022-07-19 23:12:34.000000";

    private static final String PROCESSING_TIMESTAMP = "2022-07-20 01:02:03.000000";

    private static final String EXPECTED_FOCUS_FIELD = "TRNIDIN";

    private static final String EXPECTED_LIST_ROUTE = "transaction-list";

    private static final String EXPECTED_VIEW_ROUTE = "transaction-view";

    private static final String EXPECTED_USER_MENU_ROUTE = "user-menu";

    private static final String EXPECTED_SIGN_ON_ROUTE = "sign-on";

    private static final String EXPECTED_INVALID_SELECTION = "Invalid selection. Valid value is S";

    private static final String EXPECTED_NOT_NUMERIC = "Tran ID must be Numeric ...";

    private static final String EXPECTED_ALREADY_AT_TOP = "You are already at the top of the page...";

    private static final String EXPECTED_ALREADY_AT_BOTTOM =
            "You are already at the bottom of the page...";

    private static final String EXPECTED_AT_TOP = "You are at the top of the page...";

    private static final String EXPECTED_REACHED_BOTTOM = "You have reached the bottom of the page...";

    private static final String EXPECTED_REACHED_TOP = "You have reached the top of the page...";

    private static final String EXPECTED_UNABLE_TO_LOOKUP = "Unable to lookup transaction...";

    private static final String REDACTED = "***REDACTED***";

    /** Keyset reads the stub repository was asked for, in order. */
    private final List<KeysetRead> keysetReads = new ArrayList<>();

    /** One bounded repository read, retaining its direction, exclusive cursor and limit. */
    private record KeysetRead(boolean ascending, String cursor, int limit) {
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    /** A transaction whose identifier is the ordinal rendered as sixteen zero-padded digits. */
    private static Transaction transaction(final int ordinal) {
        return new Transaction(
                identifier(ordinal),
                "01",
                "0005",
                PADDED_SOURCE,
                DESCRIPTION,
                new BigDecimal("123.45"),
                "000000123",
                "A MERCHANT",
                "A CITY",
                "12345",
                "4111111111111111",
                ORIGINATION_TIMESTAMP,
                PROCESSING_TIMESTAMP);
    }

    private static String identifier(final int ordinal) {
        return String.format(Locale.ROOT, "%016d", ordinal);
    }

    private static List<Transaction> transactions(final int count) {
        final List<Transaction> rows = new ArrayList<>(count);
        for (int ordinal = 1; ordinal <= count; ordinal++) {
            rows.add(transaction(ordinal));
        }
        return rows;
    }

    /** A repository that behaves like an ordered primary-key cluster in either keyset direction. */
    private TransactionRepository repositoryOf(final List<Transaction> rows) {
        final TransactionRepository repository = Mockito.mock(TransactionRepository.class);
        Mockito.when(repository.findAll(ArgumentMatchers.any(Pageable.class)))
                .thenAnswer(invocation -> {
                    final Pageable pageable = invocation.getArgument(0);
                    final Sort.Order order = pageable.getSort().getOrderFor("tranId");
                    keysetReads.add(new KeysetRead(
                            order == null || order.isAscending(), "", pageable.getPageSize()));
                    return page(rows, pageable);
                });
        return repository;
    }

    /** Returns the ordered page the production repository contract exposes. */
    private static PageImpl<Transaction> page(
            final List<Transaction> rows, final Pageable pageable) {
        final List<Transaction> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing(Transaction::getTranId));
        final Sort.Order order = pageable.getSort().getOrderFor("tranId");
        if (order != null && order.isDescending()) {
            Collections.reverse(ordered);
        }
        final int from = Math.min((int) pageable.getOffset(), ordered.size());
        final int to = Math.min(from + pageable.getPageSize(), ordered.size());
        return new PageImpl<>(new ArrayList<>(ordered.subList(from, to)), pageable, ordered.size());
    }

    /** Returns one strict keyset window and records the repository call that requested it. */
    private List<Transaction> keysetWindow(final List<Transaction> rows, final String cursor,
            final Limit limit, final boolean ascending) {
        final List<Transaction> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing(Transaction::getTranId));
        if (!ascending) {
            Collections.reverse(ordered);
        }
        keysetReads.add(new KeysetRead(ascending, cursor, limit.max()));
        return ordered.stream()
                .filter(row -> ascending
                        ? row.getTranId().compareTo(cursor) > 0
                        : row.getTranId().compareTo(cursor) < 0)
                .limit(limit.max())
                .toList();
    }

    /**
     * A repository that serves the given number of page requests and then fails, so a failure can be
     * placed on a <em>read</em> rather than only on the opening browse.
     */
    private TransactionRepository repositoryFailingAfter(final List<Transaction> rows,
            final int successfulRequests) {
        final TransactionRepository repository = Mockito.mock(TransactionRepository.class);
        final int[] requests = {0};
        Mockito.when(repository.findAll(ArgumentMatchers.any(Pageable.class)))
                .thenAnswer(invocation -> {
                    requests[0]++;
                    if (requests[0] > successfulRequests) {
                        throw new QueryTimeoutException("the driver message must not be echoed");
                    }
                    final Pageable pageable = invocation.getArgument(0);
                    final Sort.Order order = pageable.getSort().getOrderFor("tranId");
                    keysetReads.add(new KeysetRead(
                            order == null || order.isAscending(), "", pageable.getPageSize()));
                    return page(rows, pageable);
                });
        return repository;
    }

    /** Applies the requested failure point before serving one recorded keyset read. */
    private List<Transaction> failingKeysetWindow(final List<Transaction> rows, final String cursor,
            final Limit limit, final boolean ascending, final int[] requests,
            final int successfulRequests) {
        requests[0]++;
        if (requests[0] > successfulRequests) {
            throw new QueryTimeoutException("the driver message must not be echoed");
        }
        return keysetWindow(rows, cursor, limit, ascending);
    }

    private TransactionListService serviceOf(final TransactionRepository repository) {
        return new TransactionListService(repository, new MessageCatalogService(),
                new NavigationService(), FIXED_CLOCK);
    }

    private TransactionListService serviceOver(final List<Transaction> rows) {
        return serviceOf(repositoryOf(rows));
    }

    /** A first entry: a present but not-yet-re-entered navigation state, arriving on the enter key. */
    private static TransactionListService.TransactionListCommand firstEntry() {
        return new TransactionListService.TransactionListCommand(
                KeyAction.ENTER,
                ScreenNavigationState.empty().withFirstEntry(),
                null,
                List.of(),
                List.of(),
                null,
                false,
                0);
    }

    /** A re-entry carrying the paging state a previous response reported. */
    private static TransactionListService.TransactionListCommand reEntry(
            final KeyAction keyAction,
            final String filter,
            final List<String> selectors,
            final List<String> displayedIds,
            final BrowseWindow metadata) {
        return new TransactionListService.TransactionListCommand(
                keyAction,
                ScreenNavigationState.empty().withReEntry(),
                filter,
                selectors,
                displayedIds,
                new BrowseWindow.CursorRequest(metadata.previousCursorKey(),
                        metadata.nextCursorKey(), metadata.direction()),
                metadata.hasMorePages(),
                Integer.parseInt(metadata.displayedPageNumber()));
    }

    private static List<String> identifiersOf(final TransactionListService.TransactionListResult result) {
        return result.rows().stream().map(TransactionListService.TransactionListRow::tranId).toList();
    }

    private static List<Integer> slotsOf(final TransactionListService.TransactionListResult result) {
        return result.rows().stream()
                .map(TransactionListService.TransactionListRow::screenRow).toList();
    }

    // ------------------------------------------------------------------------------------------
    // (a) page size
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The page is ten rows, and a short page is never padded")
    final class PageSize {

        @Test
        @DisplayName("a full page holds exactly ten rows in ascending order")
        void fullPageHoldsTen() {
            final TransactionListService.TransactionListResult result =
                    serviceOver(transactions(25)).listTransactions(firstEntry());

            assertThat(result.rows()).hasSize(EXPECTED_PAGE_SIZE);
            assertThat(identifiersOf(result)).containsExactly(
                    identifier(1), identifier(2), identifier(3), identifier(4), identifier(5),
                    identifier(6), identifier(7), identifier(8), identifier(9), identifier(10));
            assertThat(slotsOf(result)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
            assertThat(result.pageMetadata().pageSize()).isEqualTo(EXPECTED_PAGE_SIZE);
            assertThat(result.pageMetadata().displayedPageNumber()).isEqualTo("00000001");
            assertThat(result.pageMetadata().hasMorePages()).isTrue();
            assertThat(result.pageMetadata().hasPreviousPages()).isFalse();
        }

        @Test
        @DisplayName("a partial page holds fewer rows without padding, and reports no further page")
        void partialPageIsNotPadded() {
            final TransactionListService.TransactionListResult result =
                    serviceOver(transactions(4)).listTransactions(firstEntry());

            assertThat(result.rows()).hasSize(4);
            assertThat(identifiersOf(result)).containsExactly(
                    identifier(1), identifier(2), identifier(3), identifier(4));
            assertThat(result.pageMetadata().hasMorePages()).isFalse();
            assertThat(result.pageMetadata().displayedPageNumber()).isEqualTo("00000001");
            assertThat(result.message()).isEqualTo(EXPECTED_REACHED_BOTTOM);
        }

        @Test
        @DisplayName("every page request asks for exactly ten rows")
        void everyWindowIsTenRows() {
            serviceOver(transactions(25)).listTransactions(firstEntry());

            assertThat(keysetReads).isNotEmpty();
            assertThat(keysetReads)
                    .filteredOn(read -> read.limit() > 1)
                    .allSatisfy(read -> assertThat(read.limit())
                            .isEqualTo(EXPECTED_PAGE_SIZE + 1));
        }
    }

    // ------------------------------------------------------------------------------------------
    // (b) and (c) the backward reversal
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("A backward page is read descending and presented ascending")
    final class BackwardReversal {

        @Test
        @DisplayName("forward twice then backward once returns the first page's ten identifiers in"
                + " the same ascending order")
        void backwardReturnsTheEarlierPageInTheSameOrder() {
            final TransactionListService service = serviceOver(transactions(25));

            final TransactionListService.TransactionListResult firstPage =
                    service.listTransactions(firstEntry());
            final TransactionListService.TransactionListResult secondPage = service.listTransactions(
                    reEntry(KeyAction.PFK08, null, List.of(), List.of(), firstPage.pageMetadata()));
            final TransactionListService.TransactionListResult backAgain = service.listTransactions(
                    reEntry(KeyAction.PFK07, null, List.of(), List.of(), secondPage.pageMetadata()));

            assertThat(identifiersOf(secondPage)).containsExactly(
                    identifier(11), identifier(12), identifier(13), identifier(14), identifier(15),
                    identifier(16), identifier(17), identifier(18), identifier(19), identifier(20));

            // The assertion that catches a missing reverse: identical content AND identical order.
            assertThat(identifiersOf(backAgain)).containsExactlyElementsOf(identifiersOf(firstPage));
            assertThat(identifiersOf(backAgain)).isSorted();
            assertThat(slotsOf(backAgain)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
            assertThat(backAgain.pageMetadata().displayedPageNumber()).isEqualTo("00000001");
        }

        @Test
        @DisplayName("the backward query is issued with a descending sort while the returned list"
                + " ascends")
        void backwardQueriesDescendingAndReturnsAscending() {
            final TransactionListService service = serviceOver(transactions(25));
            final TransactionListService.TransactionListResult firstPage =
                    service.listTransactions(firstEntry());
            final TransactionListService.TransactionListResult secondPage = service.listTransactions(
                    reEntry(KeyAction.PFK08, null, List.of(), List.of(), firstPage.pageMetadata()));

            keysetReads.clear();
            final TransactionListService.TransactionListResult backAgain = service.listTransactions(
                    reEntry(KeyAction.PFK07, null, List.of(), List.of(), secondPage.pageMetadata()));

            assertThat(keysetReads).isNotEmpty();
            assertThat(keysetReads).allSatisfy(read -> assertThat(read.ascending()).isFalse());
            assertThat(identifiersOf(backAgain)).isSorted();
            assertThat(backAgain.pageMetadata().direction())
                    .isEqualTo(BrowseWindow.PagingDirection.BACKWARD);
        }

        @Test
        @DisplayName("a forward query is issued with an ascending sort")
        void forwardQueriesAscending() {
            serviceOver(transactions(25)).listTransactions(firstEntry());

            assertThat(keysetReads).isNotEmpty();
            assertThat(keysetReads).allSatisfy(read -> assertThat(read.ascending()).isTrue());
        }

        @Test
        @DisplayName("a short backward page keeps the echoed first cursor, because slot one never"
                + " filled")
        void shortBackwardPageIsBottomAligned() {
            final TransactionListService service = serviceOver(transactions(14));
            final TransactionListService.TransactionListResult firstPage =
                    service.listTransactions(firstEntry());
            final TransactionListService.TransactionListResult secondPage = service.listTransactions(
                    reEntry(KeyAction.PFK08, null, List.of(), List.of(), firstPage.pageMetadata()));

            final TransactionListService.TransactionListResult backAgain = service.listTransactions(
                    reEntry(KeyAction.PFK07, null, List.of(), List.of(), secondPage.pageMetadata()));

            assertThat(identifiersOf(backAgain)).containsExactlyElementsOf(identifiersOf(firstPage));
            assertThat(slotsOf(backAgain)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
            assertThat(identifiersOf(secondPage)).containsExactly(
                    identifier(11), identifier(12), identifier(13), identifier(14));
            assertThat(slotsOf(secondPage)).containsExactly(1, 2, 3, 4);
        }
    }

    // ------------------------------------------------------------------------------------------
    // (d) the two guard reads
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The guards suppress the extra read for the documented attention keys")
    final class GuardReads {

        @Test
        @DisplayName("the enter key suppresses the forward guard read, so a filter row is included")
        void enterKeyIncludesTheFilterRow() {
            final TransactionListService.TransactionListResult result = serviceOver(transactions(25))
                    .listTransactions(new TransactionListService.TransactionListCommand(
                            KeyAction.ENTER,
                            ScreenNavigationState.empty().withReEntry(),
                            identifier(5),
                            List.of(),
                            List.of(),
                            null,
                            false,
                            0));

            assertThat(identifiersOf(result)).startsWith(identifier(5));
            assertThat(result.rows()).hasSize(EXPECTED_PAGE_SIZE);
            assertThat(result.error()).isFalse();
        }

        @Test
        @DisplayName("the eighth key takes the forward guard read, so the cursor row is excluded")
        void eighthKeyExcludesTheCursorRow() {
            final TransactionListService service = serviceOver(transactions(25));
            final TransactionListService.TransactionListResult firstPage =
                    service.listTransactions(firstEntry());

            final TransactionListService.TransactionListResult secondPage = service.listTransactions(
                    reEntry(KeyAction.PFK08, null, List.of(), List.of(), firstPage.pageMetadata()));

            assertThat(firstPage.pageMetadata().nextCursorKey()).isEqualTo(identifier(10));
            assertThat(identifiersOf(secondPage)).doesNotContain(identifier(10));
            assertThat(identifiersOf(secondPage)).startsWith(identifier(11));
        }

        @Test
        @DisplayName("the seventh key takes the backward guard read, so the cursor row is excluded")
        void seventhKeyExcludesTheCursorRow() {
            final TransactionListService service = serviceOver(transactions(25));
            final TransactionListService.TransactionListResult firstPage =
                    service.listTransactions(firstEntry());
            final TransactionListService.TransactionListResult secondPage = service.listTransactions(
                    reEntry(KeyAction.PFK08, null, List.of(), List.of(), firstPage.pageMetadata()));

            final TransactionListService.TransactionListResult backAgain = service.listTransactions(
                    reEntry(KeyAction.PFK07, null, List.of(), List.of(), secondPage.pageMetadata()));

            assertThat(secondPage.pageMetadata().previousCursorKey()).isEqualTo(identifier(11));
            assertThat(identifiersOf(backAgain)).doesNotContain(identifier(11));
            assertThat(identifiersOf(backAgain)).endsWith(identifier(10));
        }
    }

    // ------------------------------------------------------------------------------------------
    // (e) and (f) values cross untouched
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Stored values cross this layer untouched")
    final class ValueFidelity {

        @Test
        @DisplayName("the source code keeps its trailing spaces and is never an enumerated type")
        void sourceKeepsItsPadding() {
            final TransactionListService.TransactionListResult result =
                    serviceOver(transactions(3)).listTransactions(firstEntry());

            assertThat(result.rows()).isNotEmpty();
            assertThat(result.rows().getFirst().tranSource())
                    .isEqualTo(PADDED_SOURCE)
                    .hasSize(PADDED_SOURCE.length())
                    .endsWith("  ");
        }

        @Test
        @DisplayName("the amount is a decimal at scale two and is neither scaled nor rounded here")
        void amountKeepsItsScale() {
            final TransactionListService.TransactionListResult result =
                    serviceOver(transactions(3)).listTransactions(firstEntry());

            final BigDecimal amount = result.rows().getFirst().tranAmt();
            assertThat(amount).isEqualTo(new BigDecimal("123.45"));
            assertThat(amount.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("both timestamps stay twenty-six-character strings and the description is not"
                + " truncated")
        void timestampsAndDescriptionAreVerbatim() {
            final TransactionListService.TransactionListRow row =
                    serviceOver(transactions(3)).listTransactions(firstEntry()).rows().getFirst();

            assertThat(row.tranOrigTs()).isEqualTo(ORIGINATION_TIMESTAMP).hasSize(26);
            assertThat(row.tranProcTs()).isEqualTo(PROCESSING_TIMESTAMP).hasSize(26);
            assertThat(row.tranDesc()).isEqualTo(DESCRIPTION);
        }

        @Test
        @DisplayName("the four merchant values use this entity's unprefixed property names")
        void merchantValuesAreCarried() {
            final TransactionListService.TransactionListRow row =
                    serviceOver(transactions(3)).listTransactions(firstEntry()).rows().getFirst();

            assertThat(row.merchantId()).isEqualTo("000000123");
            assertThat(row.merchantName()).isEqualTo("A MERCHANT");
            assertThat(row.merchantCity()).isEqualTo("A CITY");
            assertThat(row.merchantZip()).isEqualTo("12345");
            assertThat(row.tranCardNum()).isEqualTo("4111111111111111");
            assertThat(row.tranTypeCd()).isEqualTo("01");
            assertThat(row.tranCatCd()).isEqualTo("0005");
        }

        @Test
        @DisplayName("the identifier keeps its leading zeros, so it was never parsed as a number")
        void identifierKeepsItsLeadingZeros() {
            final TransactionListService.TransactionListResult result =
                    serviceOver(transactions(3)).listTransactions(firstEntry());

            assertThat(result.rows().getFirst().tranId()).isEqualTo("0000000000000001").hasSize(16);
        }
    }

    // ------------------------------------------------------------------------------------------
    // (g) the attention-key surface
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Attention keys and navigation")
    final class AttentionKeys {

        @ParameterizedTest
        @EnumSource(value = KeyAction.class,
                names = {"CLEAR", "PA1", "PA2", "PFK01", "PFK02", "PFK04", "PFK05", "PFK06",
                        "PFK09", "PFK10", "PFK11", "PFK12"})
        @DisplayName("an unmapped key produces the fifty-byte catalogue message, untrimmed")
        void unmappedKeyProducesTheCatalogueMessage(final KeyAction keyAction) {
            final TransactionListService.TransactionListResult result = serviceOver(transactions(25))
                    .listTransactions(new TransactionListService.TransactionListCommand(
                            keyAction, ScreenNavigationState.empty().withReEntry(), null, List.of(),
                            List.of(), null, false, 1));

            assertThat(result.message()).isEqualTo(EXPECTED_INVALID_KEY_MESSAGE);
            assertThat(result.message().getBytes(StandardCharsets.UTF_8))
                    .hasSize(COMMON_MESSAGE_WIDTH);
            assertThat(result.message()).endsWith(" ");
            assertThat(result.error()).isTrue();
            assertThat(result.rows()).isEmpty();
            assertThat(result.route().getRouteValue()).isEqualTo(EXPECTED_LIST_ROUTE);
            assertThat(result.focusScreenFieldId()).isEqualTo(EXPECTED_FOCUS_FIELD);
        }

        @Test
        @DisplayName("the third key returns to the user main menu with this screen stamped as the"
                + " originator")
        void thirdKeyReturnsToTheUserMenu() {
            final TransactionListService.TransactionListResult result = serviceOver(transactions(25))
                    .listTransactions(new TransactionListService.TransactionListCommand(
                            KeyAction.PFK03, ScreenNavigationState.empty().withReEntry(), null,
                            List.of(), List.of(), null, false, 1));

            assertThat(result.route().getRouteValue()).isEqualTo(EXPECTED_USER_MENU_ROUTE);
            assertThat(result.navigationContext().fromTransactionId()).isEqualTo("CT00");
            assertThat(result.navigationContext().fromProgram()).isEqualTo("COTRN00C");
            assertThat(result.navigationContext().toProgram()).isEqualTo("COMEN01C");
            assertThat(result.reEntry()).isFalse();
            assertThat(result.message()).isEmpty();
            assertThat(result.rows()).isEmpty();
        }

        @Test
        @DisplayName("a turn carrying no navigation state routes to sign-on and sends nothing")
        void absentContextRoutesToSignOn() {
            final TransactionListService.TransactionListResult result = serviceOver(transactions(25))
                    .listTransactions(new TransactionListService.TransactionListCommand(
                            KeyAction.ENTER, null, null, List.of(), List.of(), null, false, 0));

            assertThat(result.route().getRouteValue()).isEqualTo(EXPECTED_SIGN_ON_ROUTE);
            assertThat(result.navigationContext().toProgram()).isEqualTo("COSGN00C");
            assertThat(result.message()).isEmpty();
            assertThat(result.rows()).isEmpty();
            assertThat(keysetReads).isEmpty();
        }

        @Test
        @DisplayName("a wholly empty navigation state is treated as absent, as a zero-length"
                + " communication area is")
        void emptyContextIsAbsent() {
            final TransactionListService.TransactionListResult result = serviceOver(transactions(25))
                    .listTransactions(new TransactionListService.TransactionListCommand(
                            KeyAction.ENTER, ScreenNavigationState.empty(), null, List.of(), List.of(),
                            null, false, 0));

            assertThat(result.route().getRouteValue()).isEqualTo(EXPECTED_SIGN_ON_ROUTE);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Boundary reports, selection and the filter
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Boundary reports keep the screen intact")
    final class BoundaryReports {

        @Test
        @DisplayName("the seventh key on the first page reports the top and does not erase")
        void seventhKeyOnFirstPageReportsTheTop() {
            final TransactionListService service = serviceOver(transactions(25));
            final TransactionListService.TransactionListResult firstPage =
                    service.listTransactions(firstEntry());

            keysetReads.clear();
            final TransactionListService.TransactionListResult blocked = service.listTransactions(
                    reEntry(KeyAction.PFK07, null, List.of(), List.of(), firstPage.pageMetadata()));

            assertThat(blocked.message()).isEqualTo(EXPECTED_ALREADY_AT_TOP);
            assertThat(blocked.eraseScreen()).isFalse();
            assertThat(blocked.error()).isFalse();
            assertThat(blocked.rows()).isEmpty();
            assertThat(keysetReads).isEmpty();
        }

        @Test
        @DisplayName("the eighth key with no further page reports the bottom and does not erase")
        void eighthKeyAtTheEndReportsTheBottom() {
            final TransactionListService service = serviceOver(transactions(4));
            final TransactionListService.TransactionListResult onlyPage =
                    service.listTransactions(firstEntry());

            keysetReads.clear();
            final TransactionListService.TransactionListResult blocked = service.listTransactions(
                    reEntry(KeyAction.PFK08, null, List.of(), List.of(), onlyPage.pageMetadata()));

            assertThat(blocked.message()).isEqualTo(EXPECTED_ALREADY_AT_BOTTOM);
            assertThat(blocked.eraseScreen()).isFalse();
            assertThat(blocked.error()).isFalse();
            assertThat(keysetReads).isEmpty();
        }

        @Test
        @DisplayName("an empty cluster reports that the browse is at the top, with the error flag off")
        void emptyClusterReportsTheTop() {
            final TransactionListService.TransactionListResult result =
                    serviceOver(List.of()).listTransactions(firstEntry());

            assertThat(result.message()).isEqualTo(EXPECTED_AT_TOP);
            assertThat(result.error()).isFalse();
            assertThat(result.rows()).isEmpty();
            assertThat(result.pageMetadata().displayedPageNumber()).isEqualTo("00000000");
            assertThat(result.pageMetadata().hasMorePages()).isFalse();
        }

        @Test
        @DisplayName("the eighth key with no retained last key positions on high values and reports"
                + " the top")
        void highValuesPositioningReportsTheTop() {
            final TransactionListService.TransactionListResult result = serviceOver(transactions(25))
                    .listTransactions(new TransactionListService.TransactionListCommand(
                            KeyAction.PFK08, ScreenNavigationState.empty().withReEntry(), null,
                            List.of(), List.of(), null, true, 1));

            assertThat(result.error()).isTrue();
            assertThat(result.message()).isEqualTo(EXPECTED_UNABLE_TO_LOOKUP);
            assertThat(result.rows()).isEmpty();
        }

        @Test
        @DisplayName("a backward request with no retained first key resolves the low end of the"
                + " cluster, and its guard read then consumes the only record above the start")
        void lowValuesBackwardResolvesTheLowestKey() {
            final TransactionListService.TransactionListResult result = serviceOver(transactions(25))
                    .listTransactions(new TransactionListService.TransactionListCommand(
                            KeyAction.PFK07, ScreenNavigationState.empty().withReEntry(), null,
                            List.of(), List.of(), null, true, 3));

            // Low values position the browse on the lowest key; the seventh key's guard read returns
            // that record and discards it, so the fill loop immediately reaches the top of the browse.
            assertThat(result.rows()).isEmpty();
            assertThat(result.message()).isEqualTo(EXPECTED_REACHED_TOP);
            assertThat(result.error()).isFalse();
            assertThat(keysetReads).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Row selection and the transaction-id filter")
    final class SelectionAndFilter {

        @Test
        @DisplayName("an upper-case or lower-case view action transfers to the transaction-view"
                + " screen")
        void viewActionTransfers() {
            for (final String action : List.of("S", "s")) {
                final TransactionListService.TransactionListResult result =
                        serviceOver(transactions(25)).listTransactions(
                                new TransactionListService.TransactionListCommand(
                                        KeyAction.ENTER, ScreenNavigationState.empty().withReEntry(),
                                        null, List.of(" ", action), List.of(" ", identifier(2)),
                                        null, false, 1));

                assertThat(result.route().getRouteValue()).isEqualTo(EXPECTED_VIEW_ROUTE);
                assertThat(result.selectedTransactionId()).isEqualTo(identifier(2));
                assertThat(result.navigationContext().toProgram()).isEqualTo("COTRN01C");
                assertThat(result.navigationContext().fromProgram()).isEqualTo("COTRN00C");
                assertThat(result.reEntry()).isFalse();
                assertThat(result.message()).isEmpty();
            }
        }

        @Test
        @DisplayName("an unsupported row action reports its message and still pages, because the"
                + " send is commented out")
        void unsupportedActionFallsThroughAndStillPages() {
            final TransactionListService.TransactionListResult result = serviceOver(transactions(25))
                    .listTransactions(new TransactionListService.TransactionListCommand(
                            KeyAction.ENTER, ScreenNavigationState.empty().withReEntry(), null,
                            List.of("X"), List.of(identifier(1)), null, false, 1));

            assertThat(result.route().getRouteValue()).isEqualTo(EXPECTED_LIST_ROUTE);
            assertThat(result.message()).isEqualTo(EXPECTED_INVALID_SELECTION);
            assertThat(result.rows()).hasSize(EXPECTED_PAGE_SIZE);
            assertThat(result.error()).isFalse();
            assertThat(result.fieldErrors()).hasSize(1);
            assertThat(result.fieldErrors().getFirst().state())
                    .isEqualTo(ValidationException.FieldState.INVALID);
            assertThat(result.fieldErrors().getFirst().bmsFieldId()).isEqualTo("SEL0001");
            assertThat(result.fieldErrors().getFirst().field()).isEqualTo("rowSelectors");
        }

        @Test
        @DisplayName("the first non-blank selector wins, in ascending row order")
        void firstNonBlankSelectorWins() {
            final TransactionListService.TransactionListResult result = serviceOver(transactions(25))
                    .listTransactions(new TransactionListService.TransactionListCommand(
                            KeyAction.ENTER, ScreenNavigationState.empty().withReEntry(), null,
                            List.of(" ", " ", "S", " ", "S"),
                            List.of(" ", " ", identifier(3), " ", identifier(5)), null, false, 1));

            assertThat(result.selectedTransactionId()).isEqualTo(identifier(3));
        }

        @Test
        @DisplayName("a selector with no identifier beside it selects nothing")
        void selectorWithoutIdentifierSelectsNothing() {
            final TransactionListService.TransactionListResult result = serviceOver(transactions(25))
                    .listTransactions(new TransactionListService.TransactionListCommand(
                            KeyAction.ENTER, ScreenNavigationState.empty().withReEntry(), null,
                            List.of("S"), List.of("   "), null, false, 1));

            assertThat(result.route().getRouteValue()).isEqualTo(EXPECTED_LIST_ROUTE);
            assertThat(result.rows()).hasSize(EXPECTED_PAGE_SIZE);
        }

        @Test
        @DisplayName("a non-numeric filter is rejected, and the paragraph still falls through to the"
                + " browse")
        void nonNumericFilterIsRejectedAndFallsThrough() {
            final TransactionListService.TransactionListResult result = serviceOver(transactions(25))
                    .listTransactions(new TransactionListService.TransactionListCommand(
                            KeyAction.ENTER, ScreenNavigationState.empty().withReEntry(), "ABC",
                            List.of(), List.of(), null, false, 1));

            assertThat(result.error()).isTrue();
            assertThat(result.message()).isEqualTo(EXPECTED_NOT_NUMERIC);
            assertThat(result.rows()).isEmpty();
            assertThat(result.transactionIdFilterEcho()).isEqualTo("ABC");
            assertThat(result.fieldErrors()).hasSize(1);
            assertThat(result.fieldErrors().getFirst().bmsFieldId()).isEqualTo(EXPECTED_FOCUS_FIELD);
            assertThat(result.fieldErrors().getFirst().field()).isEqualTo("transactionIdFilter");
            // The browse was still opened, which is what the fall-through means.
            assertThat(keysetReads).isNotEmpty();
        }

        @Test
        @DisplayName("a blank filter browses from the low end and clears the echoed field")
        void blankFilterBrowsesFromTheStart() {
            final TransactionListService.TransactionListResult result = serviceOver(transactions(25))
                    .listTransactions(new TransactionListService.TransactionListCommand(
                            KeyAction.ENTER, ScreenNavigationState.empty().withReEntry(), "    ",
                            List.of(), List.of(), null, false, 1));

            assertThat(identifiersOf(result)).startsWith(identifier(1));
            assertThat(result.transactionIdFilterEcho()).isEmpty();
            assertThat(result.error()).isFalse();
        }

        @Test
        @DisplayName("a first entry ignores every submitted screen field, because the map is never"
                + " received")
        void firstEntryIgnoresSubmittedFields() {
            final TransactionListService.TransactionListResult result = serviceOver(transactions(25))
                    .listTransactions(new TransactionListService.TransactionListCommand(
                            KeyAction.ENTER, ScreenNavigationState.empty().withFirstEntry(), "ABC",
                            List.of("X"), List.of(identifier(1)), null, false, 0));

            assertThat(result.error()).isFalse();
            assertThat(result.message()).isEmpty();
            assertThat(result.fieldErrors()).isEmpty();
            assertThat(identifiersOf(result)).startsWith(identifier(1));
            assertThat(result.reEntry()).isTrue();
        }
    }

    // ------------------------------------------------------------------------------------------
    // The header, the failure arm and the carried shapes
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The screen header, the failure arm and the carried shapes")
    final class HeaderFailureAndShapes {

        @Test
        @DisplayName("the header carries both titles, the transaction, the program and the fixed"
                + " instant")
        void headerIsPopulated() {
            final TransactionListService.TransactionListResult result =
                    serviceOver(transactions(25)).listTransactions(firstEntry());

            assertThat(result.currentDate()).isEqualTo(EXPECTED_HEADER_DATE);
            assertThat(result.currentTime()).isEqualTo(EXPECTED_HEADER_TIME);
            assertThat(result.transactionName()).isEqualTo("CT00");
            assertThat(result.programName()).isEqualTo("COTRN00C");
            assertThat(result.screenTitle01()).hasSize(40).contains("AWS Mainframe Modernization");
            assertThat(result.screenTitle02()).hasSize(40).contains("CardDemo");
        }

        @Test
        @DisplayName("a data-access failure raises the error flag with the unable-to-look-up message")
        void dataAccessFailureIsReported() {
            final TransactionRepository repository = Mockito.mock(TransactionRepository.class);
            Mockito.when(repository.findAll(ArgumentMatchers.any(Pageable.class)))
                    .thenThrow(new QueryTimeoutException("the driver message must not be echoed"));

            final TransactionListService.TransactionListResult result =
                    serviceOf(repository).listTransactions(firstEntry());

            assertThat(result.error()).isTrue();
            assertThat(result.message()).isEqualTo(EXPECTED_UNABLE_TO_LOOKUP);
            assertThat(result.message()).doesNotContain("driver message");
            assertThat(result.rows()).isEmpty();
        }

        @Test
        @DisplayName("the command rejects an absent attention key and a negative page number")
        void commandValidatesItsInputs() {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new TransactionListService.TransactionListCommand(null,
                            ScreenNavigationState.empty().withReEntry(), null, List.of(), List.of(),
                            null, false, 0));

            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    new TransactionListService.TransactionListCommand(KeyAction.ENTER,
                            ScreenNavigationState.empty().withReEntry(), null, List.of(), List.of(),
                            null, false, -1));
        }

        @Test
        @DisplayName("the command normalises its optional aggregates and its cursor")
        void commandNormalisesOptionalComponents() {
            final TransactionListService.TransactionListCommand command =
                    new TransactionListService.TransactionListCommand(KeyAction.ENTER, null, null,
                            null, null, null, false, 0);

            assertThat(command.navigationContext()).isEqualTo(ScreenNavigationState.empty());
            assertThat(command.rowSelectors()).isEmpty();
            assertThat(command.displayedTransactionIds()).isEmpty();
            assertThat(command.pageCursor()).isNotNull();
            assertThat(command.pageCursor().previousCursorKey()).isNull();
            assertThat(command.pageCursor().nextCursorKey()).isNull();
        }

        @Test
        @DisplayName("the result's collections are unmodifiable")
        void resultCollectionsAreUnmodifiable() {
            final TransactionListService.TransactionListResult result =
                    serviceOver(transactions(25)).listTransactions(firstEntry());

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> result.rows().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> result.fieldErrors().clear());
        }

        @Test
        @DisplayName("every carried shape withholds its record keys from its diagnostic rendering")
        void carriedShapesRedactRecordKeys() {
            final TransactionListService.TransactionListResult result =
                    serviceOver(transactions(25)).listTransactions(firstEntry());
            final TransactionListService.TransactionListCommand command = reEntry(
                    KeyAction.PFK08, identifier(7), List.of("S"), List.of(identifier(7)),
                    result.pageMetadata());

            assertThat(result.toString()).contains(REDACTED).doesNotContain(identifier(1));
            assertThat(result.rows().getFirst().toString())
                    .contains(REDACTED)
                    .doesNotContain(identifier(1))
                    .doesNotContain("4111111111111111")
                    .doesNotContain("123.45");
            assertThat(command.toString()).contains(REDACTED).doesNotContain(identifier(7));
        }

        @Test
        @DisplayName("a read that fails mid-page reaches the forward read's own failure arm")
        void forwardReadFailureIsReported() {
            // The opening browse serves its window; the page-boundary read then fails, so the
            // failure lands on READNEXT rather than on STARTBR.
            final TransactionListService.TransactionListCommand nearWindowEnd =
                    new TransactionListService.TransactionListCommand(
                            KeyAction.ENTER,
                            ScreenNavigationState.empty().withReEntry(),
                            identifier(10),
                            List.of(),
                            List.of(),
                            null,
                            false,
                            0);
            final TransactionListService.TransactionListResult result =
                    serviceOf(repositoryFailingAfter(transactions(25), 1))
                            .listTransactions(nearWindowEnd);

            assertThat(result.error()).isTrue();
            assertThat(result.message()).isEqualTo(EXPECTED_UNABLE_TO_LOOKUP);
            assertThat(result.message()).doesNotContain("driver message");
        }

        @Test
        @DisplayName("a read that fails mid-page reaches the backward read's own failure arm")
        void backwardReadFailureIsReported() {
            final BrowseWindow metadata = BrowseWindow.backward(EXPECTED_PAGE_SIZE, identifier(20),
                    identifier(25), true, true, "00000003");

            final TransactionListService.TransactionListResult result =
                    serviceOf(repositoryFailingAfter(transactions(25), 1))
                            .listTransactions(reEntry(KeyAction.PFK07, null, List.of(), List.of(),
                                    metadata));

            assertThat(result.error()).isTrue();
            assertThat(result.message()).isEqualTo(EXPECTED_UNABLE_TO_LOOKUP);
            assertThat(result.message()).doesNotContain("driver message");
        }

        @Test
        @DisplayName("a backward guard read with no open browse reaches the invalid-request arm")
        void backwardGuardReadWithNoBrowseIsReported() {
            // An empty cluster makes the opening browse report not-found, which leaves the error flag
            // off, so the seventh key still takes its guard read against a browse that was never
            // opened - the invalid-request response the source's catch-all arm handles.
            final BrowseWindow metadata = BrowseWindow.backward(EXPECTED_PAGE_SIZE, identifier(5),
                    identifier(9), true, true, "00000002");

            final TransactionListService.TransactionListResult result = serviceOver(List.of())
                    .listTransactions(reEntry(KeyAction.PFK07, null, List.of(), List.of(), metadata));

            assertThat(result.error()).isTrue();
            assertThat(result.message()).isEqualTo(EXPECTED_UNABLE_TO_LOOKUP);
            assertThat(result.rows()).isEmpty();
        }

        @Test
        @DisplayName("an opening browse that fails on a backward turn stops the paragraph at its"
                + " error guard")
        void backwardOpeningBrowseFailureStopsTheParagraph() {
            final BrowseWindow metadata = BrowseWindow.backward(EXPECTED_PAGE_SIZE, identifier(11),
                    identifier(20), true, true, "00000002");

            final TransactionListService.TransactionListResult result =
                    serviceOf(repositoryFailingAfter(transactions(25), 0))
                            .listTransactions(reEntry(KeyAction.PFK07, null, List.of(), List.of(),
                                    metadata));

            assertThat(result.error()).isTrue();
            assertThat(result.message()).isEqualTo(EXPECTED_UNABLE_TO_LOOKUP);
            assertThat(result.rows()).isEmpty();
            // The page number is untouched, because the paragraph returned before settling it.
            assertThat(result.pageMetadata().displayedPageNumber()).isEqualTo("00000002");
        }

        @Test
        @DisplayName("a backward request on an empty cluster with no retained key cannot resolve a"
                + " low end and reports the top")
        void lowValuesBackwardOnEmptyClusterReportsTheTop() {
            final TransactionListService.TransactionListResult result = serviceOver(List.of())
                    .listTransactions(new TransactionListService.TransactionListCommand(
                            KeyAction.PFK07, ScreenNavigationState.empty().withReEntry(), null,
                            List.of(), List.of(), null, true, 2));

            assertThat(result.message()).isEqualTo(EXPECTED_UNABLE_TO_LOOKUP);
            assertThat(result.error()).isTrue();
            assertThat(result.rows()).isEmpty();
        }

        @Test
        @DisplayName("a filter above every stored key cannot be positioned and reports the top")
        void filterAboveEveryKeyReportsTheTop() {
            final TransactionListService.TransactionListResult result = serviceOver(transactions(25))
                    .listTransactions(new TransactionListService.TransactionListCommand(
                            KeyAction.ENTER, ScreenNavigationState.empty().withReEntry(),
                            "9".repeat(16), List.of(), List.of(), null, false, 0));

            assertThat(result.message()).isEqualTo(EXPECTED_AT_TOP);
            assertThat(result.error()).isFalse();
            assertThat(result.rows()).isEmpty();
        }

        @Test
        @DisplayName("stepping back from the third page decrements the page number rather than"
                + " pinning it to one")
        void backwardFromThirdPageDecrementsThePageNumber() {
            final TransactionListService service = serviceOver(transactions(35));
            final TransactionListService.TransactionListResult page1 =
                    service.listTransactions(firstEntry());
            final TransactionListService.TransactionListResult page2 = service.listTransactions(
                    reEntry(KeyAction.PFK08, null, List.of(), List.of(), page1.pageMetadata()));
            final TransactionListService.TransactionListResult page3 = service.listTransactions(
                    reEntry(KeyAction.PFK08, null, List.of(), List.of(), page2.pageMetadata()));

            assertThat(page3.pageMetadata().displayedPageNumber()).isEqualTo("00000003");

            final TransactionListService.TransactionListResult backToTwo = service.listTransactions(
                    reEntry(KeyAction.PFK07, null, List.of(), List.of(), page3.pageMetadata()));

            // A record still exists below the page just assembled, so the number is decremented.
            assertThat(backToTwo.pageMetadata().displayedPageNumber()).isEqualTo("00000002");
            assertThat(identifiersOf(backToTwo)).containsExactlyElementsOf(identifiersOf(page2));
            assertThat(identifiersOf(backToTwo)).isSorted();
        }

        @Test
        @DisplayName("the row carries the slot it occupies, which is what makes the backward fill"
                + " visible")
        void rowCarriesItsSlot() {
            final TransactionListService.TransactionListRow row =
                    new TransactionListService.TransactionListRow(4, identifier(4), "01", "0005",
                            PADDED_SOURCE, DESCRIPTION, new BigDecimal("1.00"), "1", "m", "c", "z",
                            "4111111111111111", ORIGINATION_TIMESTAMP, PROCESSING_TIMESTAMP);

            assertThat(row.screenRow()).isEqualTo(4);
            assertThat(row.tranSource()).isEqualTo(PADDED_SOURCE);
            assertThat(row.toString()).contains("screenRow=4");
        }
    }
}
