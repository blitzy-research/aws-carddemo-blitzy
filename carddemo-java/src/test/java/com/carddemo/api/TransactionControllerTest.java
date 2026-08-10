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
package com.carddemo.api;

import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.api.dto.NavigationContext;
import com.carddemo.api.dto.TransactionAddRequest;
import com.carddemo.api.dto.TransactionAddResponse;
import com.carddemo.api.dto.TransactionListRequest;
import com.carddemo.api.dto.TransactionListResponse;
import com.carddemo.api.dto.TransactionViewResponse;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.exception.ValidationException;
import com.carddemo.service.BrowseWindow;
import com.carddemo.service.NavigationService;
import com.carddemo.service.ScreenNavigationState;
import com.carddemo.service.TransactionAddService;
import com.carddemo.service.TransactionListService;
import com.carddemo.service.TransactionViewService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The transaction screens' boundary: the list route carrying legacy transaction CT00, the view route
 * carrying CT01 and the add route carrying CT02.
 *
 * <p>All three routes translate between the transport vocabulary and the service vocabulary, and the
 * translation is not a pass-through, so it is what this class holds. Three behaviours in particular
 * are decisions taken here and nowhere else, and each would survive a change silently if it were not
 * asserted:
 *
 * <ul>
 *   <li><em>An unnamed attention key becomes the clear key.</em> The list transaction distinguishes a
 *       submission that named no key from one that named the enter key, so the boundary substitutes a
 *       specific constant rather than passing absence through.</li>
 *   <li><em>Four stored values are cut back to the widths the maps declare</em> - the list
 *       description, and the view screen's description, merchant name, merchant city and the two
 *       timestamps - and a value already inside its width is carried whole.</li>
 *   <li><em>The list date column is built from the origination timestamp by position</em>, month and
 *       day and the last two digits of the year, and a timestamp too short to carry a date yields no
 *       column at all rather than a partial one.</li>
 * </ul>
 *
 * <p>The outcome vocabulary is also asserted directly. The list and view routes report two outcomes
 * and the add route reports three, because an add turn that was neither rejected nor written is an
 * unconfirmed one, and collapsing that third state would make a confirmation prompt indistinguishable
 * from a successful write in a metric.
 */
@DisplayName("TransactionController - the transaction list, view and add routes")
class TransactionControllerTest {

    /** The sixteen-digit transaction identifier the screens carry. */
    private static final String TRANSACTION_ID = "0000000000000001";

    /** A stored origination timestamp in the layout's twenty-six character form. */
    private static final String ORIGIN_TS = "2022-07-19 14:23:07.123456";

    /**
     * The identity the filter chain establishes for these turns. Every one of the three routes is
     * authenticated, so a turn always reaches the boundary with a principal, and it is that principal - not
     * a field the caller echoed - that the carried state names.
     */
    private static final Authentication IDENTITY = identityOf("USER0001", UserType.USER);

    /**
     * Builds an established identity carrying the single authority the chain grants for a user type.
     *
     * @param userId the principal name
     * @param userType the type whose declared authority is granted
     * @return an authenticated token the boundary can read identity from
     */
    private static Authentication identityOf(final String userId, final UserType userType) {
        return new TestingAuthenticationToken(userId, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + userType.name())));
    }

    private TransactionListService transactionListService;

    private TransactionViewService transactionViewService;

    private TransactionAddService transactionAddService;

    private ScreenStateAdapter screenStateAdapter;

    private MeterRegistry meterRegistry;

    private TransactionController controller;

    @BeforeEach
    void setUp() {
        transactionListService = mock(TransactionListService.class);
        transactionViewService = mock(TransactionViewService.class);
        transactionAddService = mock(TransactionAddService.class);
        // The real converter rather than a mock: it holds no state and performs a positional copy, so
        // stubbing it would measure the stub instead of the crossing.
        screenStateAdapter = new ScreenStateAdapter(new NavigationService());
        meterRegistry = new SimpleMeterRegistry();
        controller = new TransactionController(transactionListService, transactionViewService,
                transactionAddService, screenStateAdapter, meterRegistry);
    }

    /**
     * Builds a list row for one screen slot.
     *
     * @param screenRow the one-based slot the row occupies
     * @param description the stored description
     * @param originationTimestamp the stored origination timestamp
     * @return the row
     */
    private static TransactionListService.TransactionListRow listRow(final int screenRow,
            final String description, final String originationTimestamp) {
        return new TransactionListService.TransactionListRow(screenRow, TRANSACTION_ID, "01", "05",
                "POS TERM", description, new BigDecimal("123.45"), "M001", "MERCHANT",
                "CITY", "12345", "4111111111111111", originationTimestamp, ORIGIN_TS);
    }

    /**
     * Builds a list result over the supplied rows.
     *
     * @param rows the rows the turn presented
     * @param error whether the turn was rejected
     * @param route the route the turn resolved, possibly {@code null}
     * @param pageMetadata the browse window the turn assembled, possibly {@code null}
     * @return the result
     */
    private static TransactionListService.TransactionListResult listResult(
            final List<TransactionListService.TransactionListRow> rows, final boolean error,
            final NavigationService.Route route, final BrowseWindow pageMetadata) {
        return new TransactionListService.TransactionListResult(route, ScreenNavigationState.empty(),
                rows, pageMetadata, "MESSAGE TEXT", List.of(), "TRNIDIN", error, false, false,
                "FILTER ECHO", TRANSACTION_ID, "TITLE ONE", "TITLE TWO", "07/19/22", "14:23:07",
                "CT00", "COTRN00C");
    }

    /**
     * Builds a list result carrying the supplied field findings and nothing else of note.
     *
     * @param fieldErrors the findings the turn raised, in the order it raised them
     * @return the result
     */
    private static TransactionListService.TransactionListResult listResultWithFindings(
            final List<ValidationException.FieldError> fieldErrors) {
        return new TransactionListService.TransactionListResult(
                NavigationService.Route.TRANSACTION_LIST, ScreenNavigationState.empty(), List.of(),
                browseWindow(), "MESSAGE TEXT", fieldErrors, "TRNIDIN", true, true, false,
                "FILTER ECHO", null, "TITLE ONE", "TITLE TWO", "07/19/22", "14:23:07", "CT00",
                "COTRN00C");
    }

    /**
     * Builds a list result carrying the supplied selection handoff and screen-erase instruction.
     *
     * @param rows the rows the turn presented
     * @param selectedTransactionId the identifier the operator's selection resolved to, possibly
     *     {@code null}
     * @param eraseScreen whether the turn asks the next screen to be built from scratch
     * @param route the route the turn resolved, possibly {@code null}
     * @return the result
     */
    private static TransactionListService.TransactionListResult listResult(
            final List<TransactionListService.TransactionListRow> rows,
            final String selectedTransactionId, final boolean eraseScreen,
            final NavigationService.Route route) {
        return new TransactionListService.TransactionListResult(route, ScreenNavigationState.empty(),
                rows, browseWindow(), "MESSAGE TEXT", List.of(), "TRNIDIN", false, false,
                eraseScreen, "FILTER ECHO", selectedTransactionId, "TITLE ONE", "TITLE TWO",
                "07/19/22", "14:23:07", "CT00", "COTRN00C");
    }

    /**
     * Builds a view result carrying the supplied field findings.
     *
     * @param fieldErrors the findings the turn raised, in the order it raised them
     * @return the result
     */
    private static TransactionViewService.TransactionViewResult viewResultWithFindings(
            final List<ValidationException.FieldError> fieldErrors) {
        return new TransactionViewService.TransactionViewResult(
                NavigationService.Route.TRANSACTION_VIEW, ScreenNavigationState.empty(), "CT01",
                "SEARCH ID", null, "MESSAGE TEXT", "TRNIDIN", true, true, false, fieldErrors,
                viewHeader());
    }

    /**
     * Builds the browse window the list transaction assembles.
     *
     * @return the browse window
     */
    private static BrowseWindow browseWindow() {
        return new BrowseWindow(10, "PREV", "NEXT", BrowseWindow.PagingDirection.FORWARD, true,
                false, "3");
    }

    /**
     * Builds a list request carrying the supplied selectors and key.
     *
     * @param keyAction the transmitted key, possibly {@code null}
     * @param rowSelectors the selector column as submitted
     * @return the request
     */
    private static TransactionListRequest listRequest(final KeyAction keyAction,
            final List<String> rowSelectors) {
        return new TransactionListRequest("FILTER", "2", rowSelectors, keyAction,
                NavigationContext.empty(),
                new com.carddemo.api.dto.PageMetadata.PageCursorRequest(
                        "PREV", "NEXT",
                        com.carddemo.api.dto.PageMetadata.PagingDirection.FORWARD, "2", true));
    }

    /**
     * Builds a view projection over the supplied text values.
     *
     * @param description the stored description
     * @param merchantName the stored merchant name
     * @param merchantCity the stored merchant city
     * @param originationTimestamp the stored origination timestamp
     * @return the projection
     */
    private static TransactionViewService.TransactionProjection viewProjection(
            final String description, final String merchantName, final String merchantCity,
            final String originationTimestamp) {
        return new TransactionViewService.TransactionProjection(TRANSACTION_ID, "4111111111111111",
                "01", "05", "POS TERM", new BigDecimal("123.45"), description,
                originationTimestamp, ORIGIN_TS, "M001", merchantName, merchantCity, "12345");
    }

    /**
     * Builds a view result over the supplied projection.
     *
     * @param projection the retrieved record, possibly {@code null}
     * @param errorFlag whether the turn was rejected
     * @param header the header group, possibly {@code null}
     * @param route the route the turn resolved, possibly {@code null}
     * @return the result
     */
    private static TransactionViewService.TransactionViewResult viewResult(
            final TransactionViewService.TransactionProjection projection, final boolean errorFlag,
            final TransactionViewService.ScreenHeader header, final NavigationService.Route route) {
        return new TransactionViewService.TransactionViewResult(route,
                ScreenNavigationState.empty(),
                "CT01", "SEARCH ID", projection, "MESSAGE TEXT", "TRNIDIN", errorFlag, false, false,
                List.of(), header);
    }

    /**
     * Builds the header group of the view screen.
     *
     * @return the header
     */
    private static TransactionViewService.ScreenHeader viewHeader() {
        return new TransactionViewService.ScreenHeader("TITLE ONE", "TITLE TWO", "CT01", "COTRN01C",
                "07/19/22", "14:23:07");
    }

    /**
     * Builds an add result over the supplied written record.
     *
     * @param written the record the turn wrote, or {@code null} when it wrote none
     * @param errorFlag whether the turn was rejected
     * @param fieldErrors the field-level errors the turn composed
     * @return the result
     */
    private static TransactionAddService.TransactionAddResult addResult(
            final TransactionAddService.TransactionProjection written, final boolean errorFlag,
            final List<ValidationException.FieldError> fieldErrors) {
        return new TransactionAddService.TransactionAddResult(NavigationService.Route.TRANSACTION_ADD,
                ScreenNavigationState.empty(), "CT02", written, "MESSAGE TEXT", "ACTIDIN", errorFlag,
                false, fieldErrors,
                new TransactionAddService.ScreenHeader("TITLE ONE", "TITLE TWO", "CT02", "COTRN02C",
                        "07/19/22", "14:23:07", "2022-07-19-14.23.07", null, false),
                new TransactionAddService.ScreenFields("00000000011", "4111111111111111", "01", "05",
                        "POS TERM", "PURCHASE", "123.45", "2022-07-19", "2022-07-19", "M001",
                        "MERCHANT", "CITY", "12345", "Y"));
    }

    /**
     * Builds the written record an accepted add turn reports.
     *
     * @return the projection
     */
    private static TransactionAddService.TransactionProjection writtenRecord() {
        return new TransactionAddService.TransactionProjection(TRANSACTION_ID, "01", "05", "POS TERM",
                "PURCHASE", new BigDecimal("123.45"), "M001", "MERCHANT", "CITY", "12345",
                "4111111111111111", ORIGIN_TS, ORIGIN_TS);
    }

    /**
     * Builds an add request whose sixteen components are all populated.
     *
     * @return the request
     */
    private static TransactionAddRequest addRequest() {
        return new TransactionAddRequest("00000000011", "4111111111111111", "01", "05", "POS TERM",
                "PURCHASE", "123.45", "2022-07-19", "2022-07-19", "M001", "MERCHANT", "CITY",
                "12345", "Y", KeyAction.ENTER,
                NavigationContext.empty());
    }

    /**
     * Reads the count of a turn timer carrying an outcome and a route tag.
     *
     * @param metric the timer name
     * @param outcome the expected outcome tag
     * @param route the expected route tag
     * @return the number of turns recorded
     */
    private long timed(final String metric, final String outcome, final String route) {
        return meterRegistry.get(metric).tag("outcome", outcome).tag("route", route).timer().count();
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("no collaborator may be absent, so a misassembled context fails at construction")
        void noCollaboratorMayBeAbsent() {
            assertThatNullPointerException().isThrownBy(() -> new TransactionController(null,
                    transactionViewService, transactionAddService, screenStateAdapter, meterRegistry))
                    .withMessageContaining("transactionListService");
            assertThatNullPointerException().isThrownBy(() -> new TransactionController(
                    transactionListService, null, transactionAddService, screenStateAdapter,
                    meterRegistry))
                    .withMessageContaining("transactionViewService");
            assertThatNullPointerException().isThrownBy(() -> new TransactionController(
                    transactionListService, transactionViewService, null, screenStateAdapter,
                    meterRegistry))
                    .withMessageContaining("transactionAddService");
            assertThatNullPointerException().isThrownBy(() -> new TransactionController(
                    transactionListService, transactionViewService, transactionAddService, null,
                    meterRegistry))
                    .withMessageContaining("screenStateAdapter");
            assertThatNullPointerException().isThrownBy(() -> new TransactionController(
                    transactionListService, transactionViewService, transactionAddService,
                    screenStateAdapter, null))
                    .withMessageContaining("meterRegistry");
        }
    }

    @Nested
    @DisplayName("The list route's inbound translation")
    class ListInbound {

        @Test
        @DisplayName("every submitted component reaches the transaction in the command it builds")
        void everySubmittedComponentReachesTheTransaction() {
            when(transactionListService.listTransactions(any()))
                    .thenReturn(listResult(List.of(), false, NavigationService.Route.TRANSACTION_LIST,
                            browseWindow()));

            controller.listTransactions(listRequest(KeyAction.PFK08, List.of("S", "")), IDENTITY);

            ArgumentCaptor<TransactionListService.TransactionListCommand> captor =
                    ArgumentCaptor.forClass(TransactionListService.TransactionListCommand.class);
            verify(transactionListService).listTransactions(captor.capture());
            TransactionListService.TransactionListCommand command = captor.getValue();
            assertThat(command.keyAction()).isEqualTo(KeyAction.PFK08);
            assertThat(command.transactionIdFilter()).isEqualTo("FILTER");
            assertThat(command.rowSelectors()).containsExactly("S", "");
            assertThat(command.currentPageNumber())
                    .as("read from the retained figure inside the echoed paging state, which is the "
                            + "communication-area field, and not from the displayed label")
                    .isEqualTo(2);
            assertThat(command.nextPageAvailable()).isTrue();
            assertThat(command.navigationContext()).isEqualTo(ScreenNavigationState.empty()
                    .reconciledWith("USER0001", UserType.USER));
            assertThat(command.pageCursor().previousCursorKey()).isEqualTo("PREV");
            assertThat(command.pageCursor().nextCursorKey()).isEqualTo("NEXT");
        }

        @Test
        @DisplayName("no displayed-row identifier reaches the transaction, because the command declares "
                + "no component to carry one and the identifier is re-read from the cursor instead")
        void noDisplayedRowIdentifierReachesTheTransaction() {
            when(transactionListService.listTransactions(any()))
                    .thenReturn(listResult(List.of(), false, NavigationService.Route.TRANSACTION_LIST,
                            browseWindow()));

            controller.listTransactions(listRequest(KeyAction.ENTER, List.of("S")), IDENTITY);

            ArgumentCaptor<TransactionListService.TransactionListCommand> captor =
                    ArgumentCaptor.forClass(TransactionListService.TransactionListCommand.class);
            verify(transactionListService).listTransactions(captor.capture());

            assertThat(Arrays.stream(
                            TransactionListService.TransactionListCommand.class
                                    .getRecordComponents())
                            .map(RecordComponent::getName))
                    .as("a component here would be an inbound channel for an identifier the submitter "
                            + "states rather than one the browse established")
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT)
                            .contains("transactionids"));
            assertThat(captor.getValue().rowSelectors()).containsExactly("S");
        }

        @Test
        @DisplayName("a submission that carried no paging state at all reaches the transaction with the "
                + "page counter the legacy field holds before any page has been walked")
        void aSubmissionWithoutPagingStateReachesTheTransactionAtPageZero() {
            when(transactionListService.listTransactions(any()))
                    .thenReturn(listResult(List.of(), false, NavigationService.Route.TRANSACTION_LIST,
                            browseWindow()));

            controller.listTransactions(new TransactionListRequest(null, null, List.of(),
                    KeyAction.ENTER, NavigationContext.empty(), null), IDENTITY);

            ArgumentCaptor<TransactionListService.TransactionListCommand> captor =
                    ArgumentCaptor.forClass(TransactionListService.TransactionListCommand.class);
            verify(transactionListService).listTransactions(captor.capture());

            assertThat(captor.getValue().currentPageNumber()).isZero();
            assertThat(captor.getValue().nextPageAvailable()).isFalse();
            assertThat(captor.getValue().pageCursor()).isNotNull();
            assertThat(captor.getValue().pageCursor().previousCursorKey()).isNull();
        }

        @Test
        @DisplayName("a submission that named no key reaches the transaction as the clear key, which "
                + "is the arm the legacy screen takes for an unnamed one")
        void anUnnamedKeyBecomesTheClearKey() {
            when(transactionListService.listTransactions(any()))
                    .thenReturn(listResult(List.of(), false, NavigationService.Route.TRANSACTION_LIST,
                            browseWindow()));

            controller.listTransactions(listRequest(null, List.of()), IDENTITY);

            ArgumentCaptor<TransactionListService.TransactionListCommand> captor =
                    ArgumentCaptor.forClass(TransactionListService.TransactionListCommand.class);
            verify(transactionListService).listTransactions(captor.capture());
            assertThat(captor.getValue().keyAction()).isEqualTo(KeyAction.CLEAR);
        }
    }

    /**
     * The four pieces of browse state the projection is obliged to publish rather than drop.
     *
     * <p>Each was previously computed by the turn and then discarded at this boundary, and each has a
     * consequence an operator can see. The slot a row occupies decides which row a selector addresses,
     * and the legacy screen fills a backward page from the bottom slot upward, so a slot cannot be
     * inferred from a row's position in the published list. The resolved identifier is what the next
     * screen is reached with. The erase instruction is what tells the next turn whether the operator's
     * page survives a browse boundary. The findings are what distinguish a selector that was left
     * blank from one that named something unusable.</p>
     */
    @Nested
    @DisplayName("The list route publishes the browse state the turn settled")
    class ListBrowseStateProjection {

        /**
         * A backward page fills the bottom slot first, and each row publishes the slot it occupies.
         *
         * <p>Reproduces the fill order of {@code app/cbl/COTRN00C.cbl:L349}, where a backward browse
         * writes into slot ten and works upward, so the first row read is the last row displayed. The
         * slot travels on the row itself, which is what lets a client render a bottom-aligned page and
         * address the right line with a selector. Inferring a slot from a row's position in the
         * published list instead would put a single bottom-slot row on line one.</p>
         *
         * <p>No identifier list is published beside the rows, and that is deliberate rather than an
         * omission: the next turn does not tell the server which identifier sat on the row it marked,
         * the server re-reads it from the cursor. A published list would be an inbound channel waiting
         * to be believed.</p>
         */
        @Test
        @DisplayName("a backward page publishes the slot each row occupies and no identifier list "
                + "beside it")
        void aBackwardPagePublishesEachRowsOwnSlotAndNoIdentifierList() {
            when(transactionListService.listTransactions(any())).thenReturn(listResult(
                    List.of(listRow(10, "BOTTOM SLOT", ORIGIN_TS)), false,
                    NavigationService.Route.TRANSACTION_LIST, browseWindow()));

            TransactionListResponse body = controller.listTransactions(
                    listRequest(KeyAction.PFK07, List.of()), IDENTITY);

            assertThat(body.rows()).singleElement()
                    .satisfies(row -> {
                        assertThat(row.screenRow()).isEqualTo(10);
                        assertThat(row.transactionId()).isEqualTo(TRANSACTION_ID);
                    });
            assertThat(Arrays.stream(
                            TransactionListResponse.class.getRecordComponents())
                            .map(RecordComponent::getName))
                    .as("nothing publishes the displayed identifiers as continuation state")
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT)
                            .contains("continuation"));
        }

        /**
         * A forward page fills from the top, which the same slot publication has to render correctly.
         */
        @Test
        @DisplayName("a forward page publishes its rows in the slots the turn filled, from the first "
                + "slot upward")
        void aForwardPagePublishesItsRowsFromTheFirstSlot() {
            when(transactionListService.listTransactions(any())).thenReturn(listResult(
                    List.of(listRow(1, "FIRST SLOT", ORIGIN_TS),
                            listRow(2, "SECOND SLOT", ORIGIN_TS)),
                    false, NavigationService.Route.TRANSACTION_LIST, browseWindow()));

            TransactionListResponse body = controller.listTransactions(
                    listRequest(KeyAction.PFK08, List.of()), IDENTITY);

            assertThat(body.rows()).extracting(
                    TransactionListResponse.TransactionRow::screenRow).containsExactly(1, 2);
            assertThat(body.rows()).extracting(
                    TransactionListResponse.TransactionRow::transactionId)
                    .as("each row carries its own identifier, which is what a client renders")
                    .containsExactly(TRANSACTION_ID, TRANSACTION_ID);
            assertThat(body.pageMetadata().previousCursorKey())
                    .as("the cursor pair is the only browse state a submission echoes back")
                    .isEqualTo("PREV");
            assertThat(body.pageMetadata().nextCursorKey()).isEqualTo("NEXT");
        }

        /**
         * The identifier a selection resolved to is what the next screen is reached with.
         *
         * <p>Withholding it left the client with nothing to carry into the view screen but the row text
         * it could see, so the handoff the legacy screen performs through its communication area had no
         * counterpart here.</p>
         */
        @Test
        @DisplayName("the identifier the selection resolved to is published, so the next screen can "
                + "be reached with it")
        void theResolvedSelectionIsPublished() {
            when(transactionListService.listTransactions(any())).thenReturn(
                    listResult(List.of(listRow(3, "SELECTED ROW", ORIGIN_TS)), TRANSACTION_ID, false,
                            NavigationService.Route.TRANSACTION_VIEW));

            TransactionListResponse body = controller.listTransactions(
                    listRequest(KeyAction.ENTER, List.of("", "", "S")), IDENTITY);

            assertThat(body.selectedTransactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(body.nextRoute())
                    .isEqualTo(NavigationService.Route.TRANSACTION_VIEW.getRouteValue());
        }

        /**
         * A turn that resolved no selection publishes no identifier, rather than an empty one.
         */
        @Test
        @DisplayName("a turn that resolved no selection leaves the identifier absent")
        void aTurnWithoutASelectionLeavesTheIdentifierAbsent() {
            when(transactionListService.listTransactions(any())).thenReturn(
                    listResult(List.of(listRow(1, "ROW", ORIGIN_TS)), null, false,
                            NavigationService.Route.TRANSACTION_LIST));

            TransactionListResponse body = controller.listTransactions(
                    listRequest(KeyAction.ENTER, List.of()), IDENTITY);

            assertThat(body.selectedTransactionId()).isNull();
        }

        /**
         * The browse boundary asks the operator's page to be retained rather than rebuilt.
         *
         * <p>The turn sets its erase instruction to no at the top and bottom of the browse, which is
         * how the legacy screen keeps the displayed page indicator steady when the operator presses
         * past the end. The published flag is the positive form of that instruction, so a false erase
         * becomes a true retain.</p>
         */
        @Test
        @DisplayName("a turn that asks for the page to be retained publishes the instruction, and a "
                + "turn that asks for a rebuild publishes its converse")
        void thePageRetentionInstructionIsPublished() {
            when(transactionListService.listTransactions(any())).thenReturn(
                    listResult(List.of(), null, false, NavigationService.Route.TRANSACTION_LIST));

            assertThat(controller.listTransactions(listRequest(KeyAction.PFK07, List.of()), IDENTITY).preserveDisplayedPage())
                    .as("the turn did not ask for an erase, so the operator's page is retained")
                    .isTrue();

            when(transactionListService.listTransactions(any())).thenReturn(
                    listResult(List.of(), null, true, NavigationService.Route.TRANSACTION_LIST));

            assertThat(controller.listTransactions(listRequest(KeyAction.ENTER, List.of()), IDENTITY).preserveDisplayedPage())
                    .as("the turn asked for an erase, so the page is rebuilt rather than retained")
                    .isFalse();
        }

        /**
         * Both field-error states cross the boundary, in the order the turn raised them.
         *
         * <p>A blank selector and an unusable one are different failures on the legacy screen - the
         * first draws the marker, the second only changes colour - so one flag cannot carry both.</p>
         */
        @Test
        @DisplayName("the selector and filter findings are published in order, each under its own "
                + "state")
        void theSelectorAndFilterFindingsArePublishedInOrder() {
            when(transactionListService.listTransactions(any())).thenReturn(listResultWithFindings(
                    List.of(
                            new ValidationException.FieldError("transactionIdFilter", "TRNIDIN",
                                    ValidationException.FieldState.MISSING,
                                    "Tran ID must be entered"),
                            new ValidationException.FieldError("rowSelector3", "SEL0003",
                                    ValidationException.FieldState.INVALID,
                                    "Invalid selection"))));

            TransactionListResponse body = controller.listTransactions(
                    listRequest(KeyAction.ENTER, List.of("", "", "X")), IDENTITY);

            assertThat(body.fieldErrors()).hasSize(2);
            assertThat(body.fieldErrors().get(0).fieldName()).isEqualTo("transactionIdFilter");
            assertThat(body.fieldErrors().get(0).screenFieldId()).isEqualTo("TRNIDIN");
            assertThat(body.fieldErrors().get(0).state())
                    .isEqualTo(ErrorResponse.FieldState.MISSING);
            assertThat(body.fieldErrors().get(0).message()).isEqualTo("Tran ID must be entered");
            assertThat(body.fieldErrors().get(1).fieldName()).isEqualTo("rowSelector3");
            assertThat(body.fieldErrors().get(1).screenFieldId()).isEqualTo("SEL0003");
            assertThat(body.fieldErrors().get(1).state())
                    .isEqualTo(ErrorResponse.FieldState.INVALID);
        }

        /**
         * A turn that raised nothing publishes an empty list rather than no list.
         */
        @Test
        @DisplayName("a turn that raised no finding publishes an empty list, not an absent one")
        void aTurnWithoutFindingsPublishesAnEmptyList() {
            when(transactionListService.listTransactions(any()))
                    .thenReturn(listResult(List.of(), false, NavigationService.Route.TRANSACTION_LIST,
                            browseWindow()));

            TransactionListResponse body = controller.listTransactions(
                    listRequest(KeyAction.ENTER, List.of()), IDENTITY);

            assertThat(body.fieldErrors()).isNotNull().isEmpty();
        }
    }

    @Nested
    @DisplayName("The list route's outbound projection")
    class ListOutbound {

        @Test
        @DisplayName("the screen and control items are carried across, and the page number is taken "
                + "from the paging record rather than from the submission")
        void theScreenAndControlItemsAreCarriedAcross() {
            when(transactionListService.listTransactions(any()))
                    .thenReturn(listResult(List.of(), false, NavigationService.Route.TRANSACTION_LIST,
                            browseWindow()));

            TransactionListResponse body =
                    controller.listTransactions(listRequest(KeyAction.ENTER, List.of()), IDENTITY);

            assertThat(body.transactionName()).isEqualTo("CT00");
            assertThat(body.programName()).isEqualTo("COTRN00C");
            assertThat(body.title01()).isEqualTo("TITLE ONE");
            assertThat(body.title02()).isEqualTo("TITLE TWO");
            assertThat(body.currentDate()).isEqualTo("07/19/22");
            assertThat(body.currentTime()).isEqualTo("14:23:07");
            assertThat(body.transactionIdFilter()).isEqualTo("FILTER ECHO");
            assertThat(body.message()).isEqualTo("MESSAGE TEXT");
            assertThat(body.error()).isFalse();
            assertThat(body.focusScreenFieldId()).isEqualTo("TRNIDIN");
            assertThat(body.displayedPageNumber()).isEqualTo("3");
            assertThat(body.nextRoute())
                    .isEqualTo(NavigationService.Route.TRANSACTION_LIST.getRouteValue());
        }

        @Test
        @DisplayName("a turn that resolved no paging record answers no page number rather than a "
                + "fabricated one")
        void aTurnWithNoPagingRecordAnswersNoPageNumber() {
            when(transactionListService.listTransactions(any()))
                    .thenReturn(listResult(List.of(), false, NavigationService.Route.TRANSACTION_LIST,
                            null));

            TransactionListResponse body =
                    controller.listTransactions(listRequest(KeyAction.ENTER, List.of()), IDENTITY);

            assertThat(body.pageMetadata()).isNull();
            assertThat(body.displayedPageNumber()).isNull();
        }

        @Test
        @DisplayName("a turn that resolved no route answers no route rather than an empty one")
        void aTurnWithNoRouteAnswersNoRoute() {
            when(transactionListService.listTransactions(any()))
                    .thenReturn(listResult(List.of(), false, null, browseWindow()));

            TransactionListResponse body =
                    controller.listTransactions(listRequest(KeyAction.ENTER, List.of()), IDENTITY);

            assertThat(body.nextRoute()).isNull();
        }

        @Test
        @DisplayName("each row is paired with the selector submitted for its own slot, and a slot the "
                + "submission did not reach carries none")
        void eachRowIsPairedWithItsOwnSubmittedSelector() {
            when(transactionListService.listTransactions(any())).thenReturn(listResult(
                    List.of(listRow(1, "FIRST", ORIGIN_TS), listRow(2, "SECOND", ORIGIN_TS),
                            listRow(3, "THIRD", ORIGIN_TS)),
                    false, NavigationService.Route.TRANSACTION_LIST, browseWindow()));

            TransactionListResponse body = controller.listTransactions(
                    listRequest(KeyAction.ENTER, List.of("S", "U")), IDENTITY);

            assertThat(body.rows()).hasSize(3);
            assertThat(body.rows().get(0).selection()).isEqualTo("S");
            assertThat(body.rows().get(1).selection()).isEqualTo("U");
            assertThat(body.rows().get(2).selection()).isNull();
        }

        @Test
        @DisplayName("a submission carrying no selector column at all leaves every row without one")
        void anAbsentSelectorColumnLeavesEveryRowWithoutOne() {
            when(transactionListService.listTransactions(any())).thenReturn(listResult(
                    List.of(listRow(1, "FIRST", ORIGIN_TS)), false,
                    NavigationService.Route.TRANSACTION_LIST, browseWindow()));

            TransactionListResponse body =
                    controller.listTransactions(listRequest(KeyAction.ENTER, null), IDENTITY);

            assertThat(body.rows()).hasSize(1);
            assertThat(body.rows().get(0).selection()).isNull();
        }

        @Test
        @DisplayName("the date column is built from the origination timestamp by position, as month, "
                + "day and the last two digits of the year")
        void theDateColumnIsBuiltByPosition() {
            when(transactionListService.listTransactions(any())).thenReturn(listResult(
                    List.of(listRow(1, "FIRST", ORIGIN_TS)), false,
                    NavigationService.Route.TRANSACTION_LIST, browseWindow()));

            TransactionListResponse body =
                    controller.listTransactions(listRequest(KeyAction.ENTER, List.of()), IDENTITY);

            assertThat(body.rows().get(0).displayedDate()).isEqualTo("07/19/22").hasSize(8);
        }

        @Test
        @DisplayName("a timestamp too short to carry a date yields no column rather than a partial one")
        void aShortTimestampYieldsNoColumn() {
            when(transactionListService.listTransactions(any())).thenReturn(listResult(
                    List.of(listRow(1, "FIRST", "2022-07"), listRow(2, "SECOND", null)), false,
                    NavigationService.Route.TRANSACTION_LIST, browseWindow()));

            TransactionListResponse body =
                    controller.listTransactions(listRequest(KeyAction.ENTER, List.of()), IDENTITY);

            assertThat(body.rows().get(0).displayedDate()).isNull();
            assertThat(body.rows().get(1).displayedDate()).isNull();
        }

        @Test
        @DisplayName("a description longer than the twenty-six characters the list map declares is cut "
                + "back, and a shorter one is carried whole")
        void aLongDescriptionIsCutBackToTheListWidth() {
            String tooLong = "X".repeat(TransactionListResponse.DESCRIPTION_LENGTH + 9);
            when(transactionListService.listTransactions(any())).thenReturn(listResult(
                    List.of(listRow(1, tooLong, ORIGIN_TS), listRow(2, "SHORT", ORIGIN_TS)), false,
                    NavigationService.Route.TRANSACTION_LIST, browseWindow()));

            TransactionListResponse body =
                    controller.listTransactions(listRequest(KeyAction.ENTER, List.of()), IDENTITY);

            assertThat(body.rows().get(0).description())
                    .hasSize(TransactionListResponse.DESCRIPTION_LENGTH);
            assertThat(body.rows().get(1).description()).isEqualTo("SHORT");
        }

        @Test
        @DisplayName("a list turn is timed under its outcome and its route")
        void aListTurnIsTimedUnderItsOutcomeAndRoute() {
            when(transactionListService.listTransactions(any()))
                    .thenReturn(listResult(List.of(), true, NavigationService.Route.TRANSACTION_LIST,
                            browseWindow()));

            controller.listTransactions(listRequest(KeyAction.ENTER, List.of()), IDENTITY);

            assertThat(timed("carddemo.online.transaction.list.turn", "rejected",
                    NavigationService.Route.TRANSACTION_LIST.getRouteValue())).isEqualTo(1L);
        }
    }

    /**
     * The view screen has one input field, and that field has two distinct failure states.
     *
     * <p>The legacy screen writes a marker beside a search key that was left blank and only changes the
     * colour of one that was supplied and cannot be used. A single whole-screen flag cannot report which
     * of the two happened, so the projection publishes the field-level detail behind it.</p>
     */
    @Nested
    @DisplayName("The view route publishes its field findings")
    class ViewFieldFindings {

        @Test
        @DisplayName("a search key that was left blank is published as missing")
        void aBlankSearchKeyIsPublishedAsMissing() {
            when(transactionViewService.viewTransaction(any())).thenReturn(viewResultWithFindings(
                    List.of(new ValidationException.FieldError("transactionId", "TRNIDIN",
                            ValidationException.FieldState.MISSING,
                            "Tran ID can NOT be empty..."))));

            TransactionViewResponse body = controller.viewTransaction(null, null, KeyAction.ENTER,
                    NavigationContext.empty(), IDENTITY);

            assertThat(body.fieldErrors()).singleElement().satisfies(finding -> {
                assertThat(finding.fieldName()).isEqualTo("transactionId");
                assertThat(finding.screenFieldId()).isEqualTo("TRNIDIN");
                assertThat(finding.state()).isEqualTo(ErrorResponse.FieldState.MISSING);
                assertThat(finding.message()).isEqualTo("Tran ID can NOT be empty...");
            });
        }

        @Test
        @DisplayName("a search key that was supplied and cannot be used is published as invalid, "
                + "which is a different state from missing")
        void anUnusableSearchKeyIsPublishedAsInvalid() {
            when(transactionViewService.viewTransaction(any())).thenReturn(viewResultWithFindings(
                    List.of(new ValidationException.FieldError("transactionId", "TRNIDIN",
                            ValidationException.FieldState.INVALID,
                            "Transaction ID NOT found..."))));

            TransactionViewResponse body = controller.viewTransaction("9999999999999999", null,
                    KeyAction.ENTER, NavigationContext.empty(), IDENTITY);

            assertThat(body.fieldErrors()).singleElement().satisfies(finding -> {
                assertThat(finding.state()).isEqualTo(ErrorResponse.FieldState.INVALID);
                assertThat(finding.state()).isNotEqualTo(ErrorResponse.FieldState.MISSING);
            });
        }

        @Test
        @DisplayName("a turn that raised no finding publishes an empty list, not an absent one")
        void aTurnWithoutFindingsPublishesAnEmptyList() {
            when(transactionViewService.viewTransaction(any())).thenReturn(
                    viewResult(viewProjection("PURCHASE", "MERCHANT", "CITY", ORIGIN_TS), false,
                            viewHeader(), NavigationService.Route.TRANSACTION_VIEW));

            TransactionViewResponse body = controller.viewTransaction(TRANSACTION_ID, null,
                    KeyAction.ENTER, NavigationContext.empty(), IDENTITY);

            assertThat(body.fieldErrors()).isNotNull().isEmpty();
        }
    }

    @Nested
    @DisplayName("The view route")
    class ViewRoute {

        @Test
        @DisplayName("every submitted component reaches the transaction in the input it builds")
        void everySubmittedComponentReachesTheTransaction() {
            when(transactionViewService.viewTransaction(any())).thenReturn(
                    viewResult(viewProjection("PURCHASE", "MERCHANT", "CITY", ORIGIN_TS), false,
                            viewHeader(), NavigationService.Route.TRANSACTION_VIEW));

            controller.viewTransaction(TRANSACTION_ID, "SELECTED", KeyAction.PFK05,
                    NavigationContext.empty(), IDENTITY);

            ArgumentCaptor<TransactionViewService.TransactionViewInput> captor =
                    ArgumentCaptor.forClass(TransactionViewService.TransactionViewInput.class);
            verify(transactionViewService).viewTransaction(captor.capture());
            TransactionViewService.TransactionViewInput input = captor.getValue();
            assertThat(input.transactionIdInput()).isEqualTo(TRANSACTION_ID);
            assertThat(input.selectedTransactionId()).isEqualTo("SELECTED");
            assertThat(input.keyAction()).isEqualTo(KeyAction.PFK05);
            assertThat(input.navigationContext()).isEqualTo(ScreenNavigationState.empty()
                    .reconciledWith("USER0001", UserType.USER));
        }

        @Test
        @DisplayName("the header, the retrieved record and the control items are carried across")
        void theRetrievedRecordIsCarriedAcross() {
            when(transactionViewService.viewTransaction(any())).thenReturn(
                    viewResult(viewProjection("PURCHASE", "MERCHANT", "CITY", ORIGIN_TS), false,
                            viewHeader(), NavigationService.Route.TRANSACTION_VIEW));

            TransactionViewResponse body =
                    controller.viewTransaction(TRANSACTION_ID, null, null, null, IDENTITY);

            assertThat(body.transactionName()).isEqualTo("CT01");
            assertThat(body.programName()).isEqualTo("COTRN01C");
            assertThat(body.title01()).isEqualTo("TITLE ONE");
            assertThat(body.title02()).isEqualTo("TITLE TWO");
            assertThat(body.currentDate()).isEqualTo("07/19/22");
            assertThat(body.currentTime()).isEqualTo("14:23:07");
            assertThat(body.searchTransactionId()).isEqualTo("SEARCH ID");
            assertThat(body.transactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(body.cardNumber()).isEqualTo("4111111111111111");
            assertThat(body.typeCode()).isEqualTo("01");
            assertThat(body.categoryCode()).isEqualTo("05");
            assertThat(body.source()).isEqualTo("POS TERM");
            assertThat(body.description()).isEqualTo("PURCHASE");
            assertThat(body.amount()).isEqualByComparingTo("123.45");
            assertThat(body.merchantId()).isEqualTo("M001");
            assertThat(body.merchantZip()).isEqualTo("12345");
            assertThat(body.errorMessage()).isEqualTo("MESSAGE TEXT");
            assertThat(body.generalError()).isFalse();
            assertThat(body.focusScreenFieldId()).isEqualTo("TRNIDIN");
            assertThat(body.nextRoute())
                    .isEqualTo(NavigationService.Route.TRANSACTION_VIEW.getRouteValue());
        }

        @Test
        @DisplayName("a turn that retrieved nothing answers every record item absent rather than "
                + "failing on the absent projection")
        void aTurnThatRetrievedNothingAnswersEveryRecordItemAbsent() {
            when(transactionViewService.viewTransaction(any()))
                    .thenReturn(viewResult(null, true, viewHeader(),
                            NavigationService.Route.TRANSACTION_VIEW));

            TransactionViewResponse body =
                    controller.viewTransaction(TRANSACTION_ID, null, null, null, IDENTITY);

            assertThat(body.transactionId()).isNull();
            assertThat(body.cardNumber()).isNull();
            assertThat(body.description()).isNull();
            assertThat(body.amount()).isNull();
            assertThat(body.merchantName()).isNull();
            assertThat(body.generalError()).isTrue();
            // The header still arrived, so the screen is still identified.
            assertThat(body.transactionName()).isEqualTo("CT01");
        }

        @Test
        @DisplayName("a turn that assembled no header answers every header item absent")
        void aTurnThatAssembledNoHeaderAnswersEveryHeaderItemAbsent() {
            when(transactionViewService.viewTransaction(any())).thenReturn(
                    viewResult(viewProjection("PURCHASE", "MERCHANT", "CITY", ORIGIN_TS), false,
                            null, NavigationService.Route.TRANSACTION_LIST));

            TransactionViewResponse body =
                    controller.viewTransaction(TRANSACTION_ID, null, null, null, IDENTITY);

            assertThat(body.transactionName()).isNull();
            assertThat(body.programName()).isNull();
            assertThat(body.title01()).isNull();
            assertThat(body.title02()).isNull();
            assertThat(body.currentDate()).isNull();
            assertThat(body.currentTime()).isNull();
        }

        @Test
        @DisplayName("the four view items that exceed their map widths are cut back, and the two "
                + "timestamps are cut to the ten characters the map shows")
        void theViewItemsAreCutBackToTheirMapWidths() {
            when(transactionViewService.viewTransaction(any())).thenReturn(viewResult(
                    viewProjection("D".repeat(75), "N".repeat(40), "C".repeat(30), ORIGIN_TS), false,
                    viewHeader(), NavigationService.Route.TRANSACTION_VIEW));

            TransactionViewResponse body =
                    controller.viewTransaction(TRANSACTION_ID, null, null, null, IDENTITY);

            assertThat(body.description()).hasSize(60);
            assertThat(body.merchantName()).hasSize(30);
            assertThat(body.merchantCity()).hasSize(25);
            assertThat(body.originationDate()).isEqualTo("2022-07-19").hasSize(10);
            assertThat(body.processingDate()).isEqualTo("2022-07-19").hasSize(10);
        }

        @Test
        @DisplayName("a turn that resolved no route answers no route")
        void aTurnWithNoRouteAnswersNoRoute() {
            when(transactionViewService.viewTransaction(any())).thenReturn(
                    viewResult(viewProjection("PURCHASE", "MERCHANT", "CITY", ORIGIN_TS), false,
                            viewHeader(), null));

            assertThat(controller.viewTransaction(TRANSACTION_ID, null, null, null, IDENTITY).nextRoute())
                    .isNull();
        }

        @Test
        @DisplayName("a view turn is timed under its outcome and its route")
        void aViewTurnIsTimedUnderItsOutcomeAndRoute() {
            when(transactionViewService.viewTransaction(any())).thenReturn(
                    viewResult(viewProjection("PURCHASE", "MERCHANT", "CITY", ORIGIN_TS), false,
                            viewHeader(), NavigationService.Route.TRANSACTION_VIEW));

            controller.viewTransaction(TRANSACTION_ID, null, null, null, IDENTITY);

            assertThat(timed("carddemo.online.transaction.view.turn", "accepted",
                    NavigationService.Route.TRANSACTION_VIEW.getRouteValue())).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("The add route")
    class AddRoute {

        @Test
        @DisplayName("all sixteen submitted components reach the transaction in the input it builds")
        void allSubmittedComponentsReachTheTransaction() {
            when(transactionAddService.processTransactionAdd(any()))
                    .thenReturn(addResult(writtenRecord(), false, List.of()));

            controller.addTransaction(addRequest(), "SELECTED", IDENTITY);

            ArgumentCaptor<TransactionAddService.TransactionAddScreenInput> captor =
                    ArgumentCaptor.forClass(TransactionAddService.TransactionAddScreenInput.class);
            verify(transactionAddService).processTransactionAdd(captor.capture());
            TransactionAddService.TransactionAddScreenInput input = captor.getValue();
            assertThat(input.accountId()).isEqualTo("00000000011");
            assertThat(input.cardNumber()).isEqualTo("4111111111111111");
            assertThat(input.typeCd()).isEqualTo("01");
            assertThat(input.categoryCd()).isEqualTo("05");
            assertThat(input.source()).isEqualTo("POS TERM");
            assertThat(input.description()).isEqualTo("PURCHASE");
            assertThat(input.amount()).isEqualTo("123.45");
            assertThat(input.origDate()).isEqualTo("2022-07-19");
            assertThat(input.procDate()).isEqualTo("2022-07-19");
            assertThat(input.merchantId()).isEqualTo("M001");
            assertThat(input.merchantName()).isEqualTo("MERCHANT");
            assertThat(input.merchantCity()).isEqualTo("CITY");
            assertThat(input.merchantZip()).isEqualTo("12345");
            assertThat(input.confirm()).isEqualTo("Y");
            assertThat(input.selectedTransaction()).isEqualTo("SELECTED");
            assertThat(input.keyAction()).isEqualTo(KeyAction.ENTER);
            assertThat(input.navigationContext()).isEqualTo(ScreenNavigationState.empty()
                    .reconciledWith("USER0001", UserType.USER));
        }

        @Test
        @DisplayName("the echoed screen fields come from the transaction's own screen record, and the "
                + "identifier and amount come from the record it wrote")
        void theEchoedScreenFieldsAndTheWrittenRecordAreCarriedAcross() {
            when(transactionAddService.processTransactionAdd(any()))
                    .thenReturn(addResult(writtenRecord(), false, List.of()));

            TransactionAddResponse body = controller.addTransaction(addRequest(), null, IDENTITY);

            assertThat(body.newTransactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(body.amount()).isEqualByComparingTo("123.45");
            assertThat(body.accountId()).isEqualTo("00000000011");
            assertThat(body.cardNumber()).isEqualTo("4111111111111111");
            assertThat(body.typeCode()).isEqualTo("01");
            assertThat(body.categoryCode()).isEqualTo("05");
            assertThat(body.source()).isEqualTo("POS TERM");
            assertThat(body.description()).isEqualTo("PURCHASE");
            assertThat(body.confirmationFlag()).isEqualTo("Y");
            assertThat(body.transactionName()).isEqualTo("CT02");
            assertThat(body.programName()).isEqualTo("COTRN02C");
            assertThat(body.message()).isEqualTo("MESSAGE TEXT");
            assertThat(body.focusScreenFieldId()).isEqualTo("ACTIDIN");
            assertThat(body.nextRoute())
                    .isEqualTo(NavigationService.Route.TRANSACTION_ADD.getRouteValue());
        }

        @Test
        @DisplayName("a turn that wrote nothing answers no identifier and no amount, while still "
                + "echoing the screen the operator submitted")
        void aTurnThatWroteNothingAnswersNoIdentifierOrAmount() {
            when(transactionAddService.processTransactionAdd(any()))
                    .thenReturn(addResult(null, false, List.of()));

            TransactionAddResponse body = controller.addTransaction(addRequest(), null, IDENTITY);

            assertThat(body.newTransactionId()).isNull();
            assertThat(body.amount()).isNull();
            assertThat(body.accountId()).isEqualTo("00000000011");
        }

        @Test
        @DisplayName("field-level errors are translated into the transport vocabulary, preserving "
                + "order, both states and the message")
        void fieldErrorsAreTranslatedPreservingOrderAndState() {
            when(transactionAddService.processTransactionAdd(any())).thenReturn(addResult(null, true,
                    List.of(new ValidationException.FieldError("accountId", "ACTIDIN",
                                    ValidationException.FieldState.MISSING, "must be supplied"),
                            new ValidationException.FieldError("amount", "TRNAMT",
                                    ValidationException.FieldState.INVALID, "not numeric"))));

            TransactionAddResponse body = controller.addTransaction(addRequest(), null, IDENTITY);

            assertThat(body.fieldErrors()).hasSize(2);
            assertThat(body.fieldErrors().get(0).fieldName()).isEqualTo("accountId");
            assertThat(body.fieldErrors().get(0).screenFieldId()).isEqualTo("ACTIDIN");
            assertThat(body.fieldErrors().get(0).state())
                    .isEqualTo(ErrorResponse.FieldState.MISSING);
            assertThat(body.fieldErrors().get(0).message()).isEqualTo("must be supplied");
            assertThat(body.fieldErrors().get(1).fieldName()).isEqualTo("amount");
            assertThat(body.fieldErrors().get(1).state())
                    .isEqualTo(ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("an accepted write is timed as added")
        void anAcceptedWriteIsTimedAsAdded() {
            when(transactionAddService.processTransactionAdd(any()))
                    .thenReturn(addResult(writtenRecord(), false, List.of()));

            controller.addTransaction(addRequest(), null, IDENTITY);

            assertThat(timed("carddemo.online.transaction.add.turn", "added",
                    NavigationService.Route.TRANSACTION_ADD.getRouteValue())).isEqualTo(1L);
        }

        @Test
        @DisplayName("a turn that wrote nothing and was not rejected is timed as unconfirmed, so a "
                + "confirmation prompt is not read as a successful write")
        void anUnwrittenTurnIsTimedAsUnconfirmed() {
            when(transactionAddService.processTransactionAdd(any()))
                    .thenReturn(addResult(null, false, List.of()));

            controller.addTransaction(addRequest(), null, IDENTITY);

            assertThat(timed("carddemo.online.transaction.add.turn", "unconfirmed",
                    NavigationService.Route.TRANSACTION_ADD.getRouteValue())).isEqualTo(1L);
        }

        @Test
        @DisplayName("a rejected turn is timed as rejected even when a record is present")
        void aRejectedTurnIsTimedAsRejected() {
            when(transactionAddService.processTransactionAdd(any()))
                    .thenReturn(addResult(writtenRecord(), true, List.of()));

            controller.addTransaction(addRequest(), null, IDENTITY);

            assertThat(timed("carddemo.online.transaction.add.turn", "rejected",
                    NavigationService.Route.TRANSACTION_ADD.getRouteValue())).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("The published paths")
    class PublishedPaths {

        @Test
        @DisplayName("the three subpaths hang off one transaction prefix")
        void theThreeSubpathsHangOffOneTransactionPrefix() {
            assertThat(TransactionController.TRANSACTION_PATH).isEqualTo("/api/transactions");
            assertThat(TransactionController.LIST_PATH).isEqualTo("/list");
            assertThat(TransactionController.VIEW_PATH).isEqualTo("/view");
            assertThat(TransactionController.ADD_PATH).isEqualTo("/add");
        }
    }
}
