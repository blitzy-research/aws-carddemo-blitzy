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
import com.carddemo.api.dto.PageMetadata;
import com.carddemo.api.dto.TransactionAddRequest;
import com.carddemo.api.dto.TransactionAddResponse;
import com.carddemo.api.dto.TransactionListRequest;
import com.carddemo.api.dto.TransactionListResponse;
import com.carddemo.api.dto.TransactionViewResponse;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.ValidationException;
import com.carddemo.service.TransactionAddService;
import com.carddemo.service.TransactionListService;
import com.carddemo.service.TransactionViewService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface of the three transaction screens: {@code CT00} the transaction list and search,
 * {@code CT01} the transaction detail view, and {@code CT02} the transaction add.
 *
 * <p><strong>What this class does.</strong> It receives one screen turn, hands it to the service that
 * owns that screen, projects the returned turn onto the published response contract, and records how
 * long the turn took. Each endpoint delegates exactly once.
 *
 * <p><strong>What this class deliberately does not do.</strong> It resolves no row selector, walks no
 * browse, orders no page, validates no field, looks up no account or card, parses no amount and no
 * date, allocates no transaction identifier, copies no previous transaction, writes nothing, retains
 * nothing between calls and composes no message text. Every one of those lives in
 * {@link TransactionListService}, {@link TransactionViewService} or {@link TransactionAddService},
 * which is what lets all three be exercised without a servlet and what stops this class from becoming a
 * second, divergent copy of the screen rules. No message literal is declared here at all: the texts
 * belong to the services and to the response contracts, and duplicating one would create two sources
 * for a text that an operator matches on.
 *
 * <p><strong>Why every outcome answers {@code 200}.</strong> Each outcome the three screens can reach -
 * including every rejection, the not-found report, the invalid-selection report, the end-of-browse
 * reports and the confirmation prompt - is a screen the legacy program successfully composed and sent.
 * The transaction completed on the mainframe in all of them, so it completes here and the outcome is
 * read from the body exactly as an operator read it from the screen. Reporting a rejected turn as a
 * client or server error would modify an externally observable contract. A malformed request - one
 * whose values exceed the widths the maps declare - is a different matter and is refused by declarative
 * validation before the handler runs, which the shared failure adapter turns into a {@code 400}.
 *
 * <p><strong>Navigation is a value, never a forward.</strong> The legacy transferred control with
 * {@code EXEC CICS XCTL} and re-armed itself with {@code EXEC CICS RETURN TRANSID}. Both become data:
 * the resolved route travels in the response beside the {@link NavigationContext} the client echoes,
 * and the client drives the next call. There is no servlet forwarding, no session, no server-side
 * conversation state and no terminal emulation anywhere in this class.
 *
 * <p><strong>Ten rows is the shape of the list screen, not a tuning value.</strong> The count comes
 * from the loop bounds of {@code app/cbl/COTRN00C.cbl} - the row index is reset to one at line 295 and
 * the fill loop at lines 297 to 303 stops once it passes ten, while the backward paragraph seeds the
 * index to ten at line 349 and walks down to one - and it is published as
 * {@link PageMetadata#TRANSACTION_LIST_PAGE_SIZE}. Changing it would put a different number of rows on
 * a screen, which is a behavioural regression rather than a configuration change. This class neither
 * sets it nor reads it as a parameter.
 *
 * <p><strong>Backward pages are published exactly as the service ordered them.</strong> The legacy
 * backward browse fills the bottom row first and works upward, and the service reverses that read
 * order before returning the page so that the rows arrive in presentation order. Nothing here sorts,
 * reverses, re-indexes or otherwise reorders a returned page; doing so would invert the order the
 * legacy screen displayed.
 *
 * <p><strong>The presentation bounds are applied here because the services state that they must
 * be.</strong> {@link TransactionViewService} carries the record at the record's own widths and
 * documents that applying the screen widths is the presentation layer's work, and
 * {@link TransactionListService} carries both timestamps whole and documents that the date column the
 * screen renders belongs to the presentation boundary. Those two projections and the field-state
 * translation below are the only transformations in this file. Each is structural, total and free of
 * any rule: no value is validated, compared, parsed, rescaled, rounded, re-cased, trimmed or padded,
 * and no map attribute, colour, highlight, marker byte, cursor coordinate, filler or record offset
 * crosses this boundary.
 *
 * <p><strong>Amounts are exact decimals and are never touched here.</strong> Every monetary value
 * arrives from a service as a {@code BigDecimal} at the scale the record's picture clause declares and
 * is published unchanged. This class performs no arithmetic, applies no scale and selects no rounding
 * mode; scale and rounding belong to the module's zoned-decimal codec alone.
 *
 * <p><strong>Dates and timestamps stay text.</strong> They cross this boundary as the bounded strings
 * the maps and records declare, so a blank ten-character date and a twenty-six-space timestamp both
 * survive. The online timestamp form and the batch timestamp form are distinct in the estate and are
 * never unified here, and no value is converted to a Java temporal type on the way out.
 *
 * <p>Stateless apart from the injected collaborators, holding no mutable field and exposing no mutable
 * collection, so the singleton is safe for unsynchronised concurrent use.
 *
 * <p>Provenance: {@code app/cbl/COTRN00C.cbl}, {@code app/cbl/COTRN01C.cbl},
 * {@code app/cbl/COTRN02C.cbl} and {@code app/csd/CARDDEMO.CSD}, whose transaction definitions bind
 * {@code CT00}, {@code CT01} and {@code CT02} to those three programs, read as read-only reference at
 * commit SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement, map declaration or
 * picture clause is transcribed.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping(TransactionController.TRANSACTION_PATH)
public final class TransactionController {

    /**
     * Root of the three transaction routes.
     *
     * <p>Declared here, and read from here by anything that needs it, for the reason the sign-on route
     * constant is declared on its own controller: a route and the rules that protect it must name one
     * authority. It deliberately sits outside the administrative prefix the security rules gate,
     * because all three transactions are reachable by any signed-on caller - the resource definition
     * classifies {@code CT00}, {@code CT01} and {@code CT02} as ordinary transactions, and the sign-on
     * program's alternative branch admits every non-administrative user type unconditionally. The
     * closing authentication rule therefore protects these routes without a rule of their own, so the
     * failure mode of a mapping typo is a refused request rather than an open surface.
     *
     * <p>It shadows neither the management endpoints nor the API-description endpoints, both of which
     * are published under roots of their own.
     */
    public static final String TRANSACTION_PATH = "/api/transactions";

    /** Route of one transaction-list turn, legacy transaction {@code CT00}. */
    public static final String LIST_PATH = "/list";

    /** Route of one transaction-view turn, legacy transaction {@code CT01}. */
    public static final String VIEW_PATH = "/view";

    /** Route of one transaction-add turn, legacy transaction {@code CT02}. */
    public static final String ADD_PATH = "/add";

    /** Diagnostic channel. Carries outcomes and counts only, never a record value. */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionController.class);

    /** Timer name for one transaction-list turn, following the module's metric naming. */
    private static final String METRIC_LIST_TURN = "carddemo.online.transaction.list.turn";

    /** Timer name for one transaction-view turn. */
    private static final String METRIC_VIEW_TURN = "carddemo.online.transaction.view.turn";

    /** Timer name for one transaction-add turn. */
    private static final String METRIC_ADD_TURN = "carddemo.online.transaction.add.turn";

    /**
     * Tag naming what the turn concluded.
     *
     * <p>Its values are the four constants below and nothing else, so the tag cannot become a
     * high-cardinality label whatever a caller submits.
     */
    private static final String TAG_OUTCOME = "outcome";

    /**
     * Tag naming the destination the turn resolved to.
     *
     * <p>Bounded by construction: the value is one of the seventeen route tokens the navigation
     * vocabulary declares, so it is a low-cardinality label by the same argument.
     */
    private static final String TAG_ROUTE = "route";

    /** Outcome of a turn that raised no error flag. */
    private static final String OUTCOME_ACCEPTED = "accepted";

    /** Outcome of a turn that raised its error flag, whatever the reported reason. */
    private static final String OUTCOME_REJECTED = "rejected";

    /** Outcome of an add turn that wrote a transaction. */
    private static final String OUTCOME_ADDED = "added";

    /** Outcome of an add turn that raised no error and wrote nothing, the confirmation gate. */
    private static final String OUTCOME_UNCONFIRMED = "unconfirmed";

    /** Tag value used when a turn resolved no route, which no current path does. */
    private static final String ROUTE_ABSENT = "none";

    /**
     * Width of the list screen's date column: eight characters.
     *
     * <p>The column is month, day and a two-digit year separated by solidus, which is exactly eight
     * positions. Taken from the response contract's own published figure rather than restated, so the
     * rendering below and the bound the contract declares on the same value cannot come to disagree.
     */
    private static final int LIST_DATE_WIDTH = TransactionListResponse.DISPLAYED_DATE_LENGTH;

    /** Separator the list screen's date column places between its three parts. */
    private static final String LIST_DATE_SEPARATOR = "/";

    /**
     * Stand-in for an absent field label in a translated per-field entry.
     *
     * <p>The empty string and not a placeholder word: the response contract requires both identifying
     * components of an entry, and inventing a name a client might try to act on would be worse than
     * saying nothing.
     */
    private static final String EMPTY = "";

    /**
     * Index at which the four-digit year begins inside an origination timestamp.
     *
     * <p>The timestamp is stored as a calendar date followed by a time of day, so its leading four
     * characters are the year, the two after the first separator are the month and the two after the
     * second are the day. The list screen renders the year's low-order two digits, so the two
     * characters the column takes begin two positions further on.
     */
    private static final int TIMESTAMP_YEAR_INDEX = 0;

    /** Number of leading year characters the list screen's two-digit year omits. */
    private static final int TIMESTAMP_CENTURY_LENGTH = 2;

    /** Index at which the two month characters begin inside an origination timestamp. */
    private static final int TIMESTAMP_MONTH_INDEX = 5;

    /** Index at which the two day characters begin inside an origination timestamp. */
    private static final int TIMESTAMP_DAY_INDEX = 8;

    /** Number of characters each of the three rendered date parts occupies. */
    private static final int DATE_PART_LENGTH = 2;

    /**
     * Smallest timestamp length from which the list screen's date column can be rendered.
     *
     * <p>A shorter value cannot carry a day, so there is nothing to render and the column is left
     * absent rather than assembled from whatever characters happen to be present.
     */
    private static final int TIMESTAMP_DATE_LENGTH = TIMESTAMP_DAY_INDEX + DATE_PART_LENGTH;

    /**
     * Width of the description column on the list screen: twenty-six characters.
     *
     * <p>The stored description is wider than the column, so the column is a genuine truncation of the
     * stored value and the response contract says so. The figure is the list map's own, and it is
     * deliberately not shared with the view screen's description width, which is a different item on a
     * different map.
     */
    private static final int LIST_DESCRIPTION_WIDTH = TransactionListResponse.DESCRIPTION_LENGTH;

    /**
     * Width of the description item on the view screen: sixty characters.
     *
     * <p>One of the five places where the view screen is narrower than the record it displays. The
     * record's own width is wider, and the legacy assignment into the narrower screen item keeps the
     * leading characters, which is what the bounding below reproduces.
     */
    private static final int VIEW_DESCRIPTION_WIDTH = 60;

    /** Width of the merchant-name item on the view screen: thirty characters, against fifty stored. */
    private static final int VIEW_MERCHANT_NAME_WIDTH = 30;

    /** Width of the merchant-city item on the view screen: twenty-five characters, against fifty stored. */
    private static final int VIEW_MERCHANT_CITY_WIDTH = 25;

    /**
     * Width of each timestamp item on the view screen: ten characters, against twenty-six stored.
     *
     * <p>The two items display the calendar date of a stored timestamp. The value is not parsed,
     * reformatted or converted to a temporal type on the way out; the narrower item simply holds the
     * leading characters of the wider stored value, which keeps the two timestamp forms the estate uses
     * distinguishable rather than unifying them.
     */
    private static final int VIEW_TIMESTAMP_WIDTH = 10;

    /**
     * Attention key carried to the list turn when the submission decoded none.
     *
     * <p><strong>Why a substitution is needed at all.</strong> The list turn's inbound type requires an
     * attention key, because a 3270 terminal always supplied one and two of the paging guards branch on
     * it. A REST submission can legitimately carry none: the first entry into the screen arrives from a
     * menu hand-off, and the legacy first-entry branch at {@code app/cbl/COTRN00C.cbl} lines 112 to 116
     * neither receives the map nor evaluates the attention key, so a client has nothing to send. Passing
     * an absent key through would fail that invariant and report a client's ordinary first call as a
     * server fault.
     *
     * <p><strong>Why this particular key, and why it loses nothing.</strong> The screen's key evaluation
     * at lines 119 to 134 names four keys - enter, the third, seventh and eighth program function keys -
     * and sends every other key to its catch-all, which reports the shared invalid-key text. The clear
     * key is one of those others, so a turn carrying it reaches precisely the arm the legacy reaches for
     * a key this screen does not act on, which is also how the sibling view and add screens treat a key
     * that was never decoded. On the first-entry path the value is not read at all, so the substitution
     * is invisible there.
     *
     * <p>No synthetic enumeration constant is introduced to model absence, and none may be: the
     * attention-key vocabulary is exactly the sixteen condition names the estate declares.
     */
    private static final KeyAction UNNAMED_LIST_ACTION = KeyAction.CLEAR;

    /** The transaction-list screen, legacy transaction {@code CT00}. */
    private final TransactionListService transactionListService;

    /** The transaction-view screen, legacy transaction {@code CT01}. */
    private final TransactionViewService transactionViewService;

    /** The transaction-add screen, legacy transaction {@code CT02}. */
    private final TransactionAddService transactionAddService;

    /** Registry the three turn timers are registered against. */
    private final MeterRegistry meterRegistry;

    /**
     * Creates the controller over its collaborators.
     *
     * <p>Constructor injection only, every field final, and every argument checked, so an instance is
     * either fully formed or not created at all.
     *
     * @param transactionListService the transaction-list screen, never {@code null}
     * @param transactionViewService the transaction-view screen, never {@code null}
     * @param transactionAddService the transaction-add screen, never {@code null}
     * @param meterRegistry the metrics registry, never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public TransactionController(final TransactionListService transactionListService,
                                final TransactionViewService transactionViewService,
                                final TransactionAddService transactionAddService,
                                final MeterRegistry meterRegistry) {
        this.transactionListService = Objects.requireNonNull(transactionListService,
                "transactionListService must not be null");
        this.transactionViewService = Objects.requireNonNull(transactionViewService,
                "transactionViewService must not be null");
        this.transactionAddService = Objects.requireNonNull(transactionAddService,
                "transactionAddService must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    /**
     * Serves one turn of the transaction-list screen, legacy transaction {@code CT00}.
     *
     * <p>The body carries the operator's entry: the identifier filter, the ten positional row
     * selectors, the attention key and the echoed navigation record, together with the two browse
     * cursors and the direction. The three parameters carry the cross-turn state the legacy held in its
     * communication area and that the request contract deliberately does not accept in its body, each
     * of them a value the previous response published so the client is echoing rather than asserting:
     *
     * <ul>
     *   <li>the ten identifiers the previous page displayed, which the legacy reads back from its map
     *       when it resolves which row was marked - without them a marked row names no transaction and
     *       neither the hand-off to the view screen nor its rejection message could occur;</li>
     *   <li>the page counter the browse settled on, which the legacy keeps in its own state and never
     *       reads from the terminal, and which the backward guard tests to decide whether a preceding
     *       page exists;</li>
     *   <li>the next-page indicator the previous turn discovered by attempting one further read, which
     *       the forward guard tests. The service's own contract records that losing it would make the
     *       eighth program function key report the bottom of the browse on every page.</li>
     * </ul>
     *
     * <p>None of the three is interpreted here. The selectors are not scanned, the cursors are not
     * followed, the counter is not incremented and the indicator is not recomputed; all of that is the
     * service's, which reproduces the legacy ordering.
     *
     * <p>No width bound is declared on the echoed identifiers, deliberately. The service's own checks
     * are message-bearing and ordered, and a declarative bound here would answer with a generic
     * rejection where the legacy answers with one specific text.
     *
     * @param request the submitted screen: filter, selectors, attention key, navigation record and
     *     browse cursors
     * @param displayedTransactionIds the identifiers of the rows the previous page displayed, in row
     *     order with blank slots preserved, or {@code null} on a first entry that displayed none
     * @param currentPageNumber the page counter the previous turn settled on; zero on a first entry,
     *     and never negative because the legacy counter is an unsigned field
     * @param nextPageAvailable whether the previous turn found a page beyond the one it displayed
     * @return the screen the turn produces, carrying the rows in presentation order, the paging
     *     metadata, the resolved route and the navigation record to echo next
     */
    @PostMapping(path = LIST_PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List or search transactions",
            description = "One turn of legacy transaction CT00. The screen presents ten rows; paging "
                    + "is cursor-based in both directions and a backward page arrives in the order "
                    + "the legacy screen displayed it. Answers 200 for every outcome the legacy "
                    + "screen could compose, including a rejected filter, an unaccepted row selector "
                    + "and either end-of-browse report: the outcome is read from the body.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The turn completed. The body carries the page, the paging metadata, "
                        + "the screen message, the field the cursor returns to and the route the "
                        + "client should call next."),
        @ApiResponse(responseCode = "400",
                description = "The submission exceeded a width the transaction-list map declares, "
                        + "carried more selectors than the screen has rows, or supplied a negative "
                        + "page counter."),
        @ApiResponse(responseCode = "401", description = "No valid session was presented.")})
    public TransactionListResponse listTransactions(
            @Valid @RequestBody final TransactionListRequest request,
            @RequestParam(name = "displayedTransactionIds", required = false)
                    final List<String> displayedTransactionIds,
            @RequestParam(name = "currentPageNumber", required = false, defaultValue = "0")
                    @PositiveOrZero final int currentPageNumber,
            @RequestParam(name = "nextPageAvailable", required = false, defaultValue = "false")
                    final boolean nextPageAvailable) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);

        final PageMetadata.PageCursorRequest pageCursor = request.pageMetadata();
        final TransactionListService.TransactionListResult result =
                this.transactionListService.listTransactions(
                        new TransactionListService.TransactionListCommand(
                                attentionKeyOrUnnamed(request.keyAction()),
                                request.navigationContext(),
                                request.transactionIdFilter(),
                                request.rowSelectors(),
                                displayedTransactionIds,
                                pageCursor,
                                nextPageAvailable,
                                currentPageNumber));

        final String nextRoute =
                (result.route() == null) ? null : result.route().getRouteValue();
        final TransactionListResponse body =
                toListResponse(result, request.rowSelectors(), nextRoute);

        recordTurn(sample, METRIC_LIST_TURN,
                "Elapsed time of one CardDemo transaction-list turn, transaction CT00",
                outcomeOf(result.error()), nextRoute);
        LOG.debug("Transaction-list turn complete: route={} rejected={} rowCount={} reEntry={}",
                nextRoute, result.error(), body.rows().size(), result.reEntry());
        return body;
    }

    /**
     * Serves one turn of the transaction-view screen, legacy transaction {@code CT01}.
     *
     * <p>The screen has two ways of naming a record and both are preserved. The operator can type an
     * identifier into the search field, and the list screen can hand one over: the legacy carries that
     * hand-off in its communication area, and on a first entry it moves the carried value into the
     * search field and runs the enter-key path immediately, so an operator arriving from the list sees
     * the record on the very first turn. The two therefore arrive as two separate values here, and
     * which of them the turn uses is the service's decision, not this method's.
     *
     * <p>The navigation record is the request body because it is the state the legacy communication
     * area carried, and a turn that carries none is the zero-length-area case the legacy answers by
     * returning to sign-on. Omitting the body therefore reproduces that path exactly rather than
     * failing.
     *
     * <p>The identifier is not bounded, defaulted or pre-checked here. A blank one is a legitimate
     * submission that the screen answers with its own emptiness text, and an over-wide one is answered
     * with the not-found text; a declarative bound would replace both with a generic rejection.
     *
     * <p>The record this screen displays is cardholder data in its entirety, so nothing about it
     * reaches the diagnostic channel - the line below reports the outcome of the turn and never its
     * content.
     *
     * @param transactionId the identifier typed into the search field, or {@code null} when the field
     *     was left blank
     * @param selectedTransactionId the identifier the calling screen handed over, or {@code null} when
     *     the turn did not arrive from the list screen
     * @param keyAction the operator's attention key, or {@code null} when the submission decoded none,
     *     which the screen answers exactly as it answers a key it does not act on
     * @param navigationContext the echoed navigation record, or {@code null} for a turn carrying none
     * @return the screen the turn produces, carrying the record at the widths this screen displays,
     *     the screen message, the field the cursor returns to and the route to call next
     */
    @PostMapping(path = VIEW_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "View one transaction",
            description = "One turn of legacy transaction CT01. Answers 200 for every outcome the "
                    + "legacy screen could compose, including an empty identifier, an identifier that "
                    + "matches no record and a failed lookup: the outcome is read from the body. The "
                    + "record is published at the widths this screen displays, which are narrower than "
                    + "the stored widths for the description, the merchant name, the merchant city and "
                    + "both timestamps.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The turn completed. The body carries the record or a blank display, the "
                        + "screen message, the field the cursor returns to and the route the client "
                        + "should call next."),
        @ApiResponse(responseCode = "400",
                description = "The echoed navigation record exceeded a width its contract declares."),
        @ApiResponse(responseCode = "401", description = "No valid session was presented.")})
    public TransactionViewResponse viewTransaction(
            @RequestParam(name = "transactionId", required = false) final String transactionId,
            @RequestParam(name = "selectedTransactionId", required = false)
                    final String selectedTransactionId,
            @RequestParam(name = "keyAction", required = false) final KeyAction keyAction,
            @Valid @RequestBody(required = false) final NavigationContext navigationContext) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);

        final TransactionViewService.TransactionViewResult result =
                this.transactionViewService.viewTransaction(
                        new TransactionViewService.TransactionViewInput(transactionId,
                                selectedTransactionId, keyAction, navigationContext));

        final String nextRoute =
                (result.route() == null) ? null : result.route().getRouteValue();
        final TransactionViewResponse body = toViewResponse(result, nextRoute);

        recordTurn(sample, METRIC_VIEW_TURN,
                "Elapsed time of one CardDemo transaction-view turn, transaction CT01",
                outcomeOf(result.errorFlag()), nextRoute);
        LOG.debug("Transaction-view turn complete: route={} rejected={} recordRetrieved={} reEntry={}",
                nextRoute, result.errorFlag(), result.retrievedTransaction().isPresent(),
                result.reEntry());
        return body;
    }

    /**
     * Serves one turn of the transaction-add screen, legacy transaction {@code CT02}.
     *
     * <p>Every rule of this screen is the service's. The key precedence - an account identifier wins
     * and resolves the card, and otherwise a card number resolves the account - the eleven-step
     * mandatory cascade that stops at its first failure, the class and shape checks that follow it, the
     * calendar checks and the one validator message number the legacy ignores, the confirmation gate
     * that distinguishes an affirmative answer from a negative or blank one and both from an
     * unrecognised one, the copy of the previous transaction the fifth program function key performs,
     * the derivation of the new identifier from the highest existing one, and the write itself: none of
     * it is reproduced, pre-empted or duplicated here. This method assembles the turn, delegates once
     * and publishes what it is given.
     *
     * <p>The identifier of the new record and its amount are published only when the turn wrote one,
     * because that is the only path on which they exist. The response contract carries the amount as an
     * exact decimal rather than as the screen's edited display form, so a turn that was rejected before
     * the amount was converted publishes none - and this method does not parse the submitted text to
     * manufacture one, because converting it is the service's step and doing it twice would be a second
     * source of truth for a monetary value.
     *
     * <p>The carried hand-off value is a value the calling screen placed in the legacy communication
     * area, which the first-entry path moves into the card-number field before running the enter-key
     * path. It is accepted as a parameter because the fixed request contract models the fourteen map
     * items and the navigation record only, and it is passed through untouched.
     *
     * @param request the submitted screen: the fourteen map items, the attention key and the echoed
     *     navigation record
     * @param selectedTransaction the transaction the calling screen handed over, or {@code null} when
     *     the turn did not arrive from another screen
     * @return the screen the turn produces, carrying the echoed items, the new identifier when one was
     *     written, the screen message, the per-field states and the route to call next
     */
    @PostMapping(path = ADD_PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Add a transaction",
            description = "One turn of legacy transaction CT02. Answers 200 for every outcome the "
                    + "legacy screen could compose, including each validation rejection, the "
                    + "confirmation prompt, a duplicate identifier and a failed write: the outcome is "
                    + "read from the body. A turn is a write only when the confirmation answer is "
                    + "affirmative, and the identifier of the written record is published only then.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The turn completed. The body carries the echoed screen, the summary "
                        + "message, the per-field states in the legacy two-state vocabulary, the field "
                        + "the cursor returns to and the route the client should call next."),
        @ApiResponse(responseCode = "400",
                description = "The submission exceeded a width the transaction-add map declares."),
        @ApiResponse(responseCode = "401", description = "No valid session was presented.")})
    public TransactionAddResponse addTransaction(
            @Valid @RequestBody final TransactionAddRequest request,
            @RequestParam(name = "selectedTransaction", required = false)
                    final String selectedTransaction) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);

        final TransactionAddService.TransactionAddResult result =
                this.transactionAddService.processTransactionAdd(
                        new TransactionAddService.TransactionAddScreenInput(
                                request.accountId(),
                                request.cardNumber(),
                                request.typeCode(),
                                request.categoryCode(),
                                request.transactionSource(),
                                request.description(),
                                request.amount(),
                                request.originationDate(),
                                request.processingDate(),
                                request.merchantId(),
                                request.merchantName(),
                                request.merchantCity(),
                                request.merchantZip(),
                                request.confirm(),
                                selectedTransaction,
                                request.keyAction(),
                                request.navigationContext()));

        final String nextRoute =
                (result.route() == null) ? null : result.route().getRouteValue();
        final TransactionAddResponse body = toAddResponse(result, nextRoute);

        recordTurn(sample, METRIC_ADD_TURN,
                "Elapsed time of one CardDemo transaction-add turn, transaction CT02",
                addOutcome(result), nextRoute);
        LOG.debug("Transaction-add turn complete: route={} rejected={} transactionAdded={}"
                        + " fieldErrors={} reEntry={}",
                nextRoute, result.errorFlag(), result.transactionAdded(),
                body.fieldErrors().size(), result.reEnter());
        return body;
    }

    /**
     * Resolves the attention key the list turn is given.
     *
     * <p>A decoded key is carried exactly as submitted. An absent one is carried as the key this screen
     * does not act on, for the reasons recorded on {@link #UNNAMED_LIST_ACTION}: the turn's inbound type
     * requires a key, a first entry legitimately has none and never reads it, and a re-submission
     * without one reaches the same arm as a key the screen does not name.
     *
     * @param submitted the decoded attention key, or {@code null} when the submission carried none
     * @return the key to carry into the turn, never {@code null}
     */
    private static KeyAction attentionKeyOrUnnamed(final KeyAction submitted) {
        return (submitted == null) ? UNNAMED_LIST_ACTION : submitted;
    }

    /**
     * Projects one transaction-list turn onto the published response.
     *
     * <p>Structural throughout. The rows arrive in the order the service settled on and are published
     * in that order, so nothing here sorts, reverses or re-indexes a page. Each row contributes the
     * four values the screen presents: the identifier verbatim, the date column the screen renders, the
     * description at the column's width, and the amount as the exact decimal it already is. The screen
     * header, the summary message, the error flag, the cursor field, the echoed filter, the paging
     * metadata and the navigation record all cross unchanged.
     *
     * <p>The page indicator is taken from the paging metadata the service computed rather than from
     * anything a client submitted, which is the arrangement the request contract requires: the legacy
     * program writes that item and never reads it back, taking the authoritative figure from its own
     * state.
     *
     * <p>The row list is handed over as it stands because the response contract copies it defensively
     * and refuses a page longer than the screen, so no unmodifiable wrapper is added here.
     *
     * @param result the service's turn outcome, never {@code null}
     * @param submittedSelectors the selectors the submission carried, echoed back per row; may be
     *     {@code null}
     * @param nextRoute the resolved route token, or {@code null} when the turn resolved none
     * @return the transport response, never {@code null}
     */
    private static TransactionListResponse toListResponse(
            final TransactionListService.TransactionListResult result,
            final List<String> submittedSelectors,
            final String nextRoute) {
        final List<TransactionListResponse.TransactionRow> rows =
                new ArrayList<>(result.rows().size());
        for (final TransactionListService.TransactionListRow row : result.rows()) {
            rows.add(new TransactionListResponse.TransactionRow(
                    selectorForRow(submittedSelectors, row.screenRow()),
                    row.tranId(),
                    listDateColumn(row.tranOrigTs()),
                    boundToScreenWidth(row.tranDesc(), LIST_DESCRIPTION_WIDTH),
                    row.tranAmt()));
        }

        final PageMetadata pageMetadata = result.pageMetadata();
        return new TransactionListResponse(
                rows,
                pageMetadata,
                result.navigationContext(),
                nextRoute,
                result.transactionIdFilterEcho(),
                (pageMetadata == null) ? null : pageMetadata.displayedPageNumber(),
                result.message(),
                result.error(),
                result.focusScreenFieldId(),
                result.screenTitle01(),
                result.screenTitle02(),
                result.currentDate(),
                result.currentTime(),
                result.transactionName(),
                result.programName());
    }

    /**
     * Echoes back the selector the submission left on one row.
     *
     * <p>The screen keeps a typed selector visible when it re-sends the page: the paragraph that clears
     * a row before refilling it clears the four value items and leaves the selector alone, so a selector
     * the operator typed is still on the screen afterwards. Echoing it therefore reproduces what the
     * legacy screen showed.
     *
     * <p>The lookup is by slot rather than by list position. A page that does not fill every slot
     * produces a shorter row list, so the row's own slot index is what aligns it with the positional
     * selector sequence; using the list position would re-point the echo at the wrong row.
     *
     * <p>Nothing about the value is interpreted, compared, folded or trimmed here - resolving which row
     * was chosen is the service's, and this is presentation echo only.
     *
     * @param submittedSelectors the positional selector sequence, or {@code null} when none was
     *     submitted
     * @param screenRow the row's one-based slot index
     * @return the selector for that slot, or {@code null} when the submission carried none for it
     */
    private static String selectorForRow(final List<String> submittedSelectors, final int screenRow) {
        final int slotIndex = screenRow - 1;
        if (submittedSelectors == null || slotIndex < 0 || slotIndex >= submittedSelectors.size()) {
            return null;
        }
        return submittedSelectors.get(slotIndex);
    }

    /**
     * Renders the list screen's date column from a stored origination timestamp.
     *
     * <p>The column is month, day and the low-order two digits of the year, separated by solidus - the
     * screen's own eight-character rendering of the calendar date part of a stored timestamp. The
     * service carries that timestamp whole, at the twenty-six characters it is stored as, and its
     * contract states that this derivation belongs to the presentation boundary, which is here.
     *
     * <p>It is a rendering and not a conversion: the value is not parsed into a temporal type, not
     * validated as a calendar date, not re-ordered and not padded. The two timestamp forms the estate
     * uses stay distinct because neither is normalised on the way through.
     *
     * <p>Total by construction. A timestamp that is absent, blank or too short to carry a day has no
     * date to render, so the column is left absent rather than assembled from whatever characters are
     * present; a row the browse did not fill is blank on the legacy screen for the same reason.
     *
     * @param originationTimestamp the stored origination timestamp, possibly {@code null}
     * @return the eight-character column, or {@code null} when there is no date to render
     */
    private static String listDateColumn(final String originationTimestamp) {
        if (originationTimestamp == null || originationTimestamp.length() < TIMESTAMP_DATE_LENGTH) {
            return null;
        }
        final int yearIndex = TIMESTAMP_YEAR_INDEX + TIMESTAMP_CENTURY_LENGTH;
        final StringBuilder column = new StringBuilder(LIST_DATE_WIDTH)
                .append(originationTimestamp, TIMESTAMP_MONTH_INDEX,
                        TIMESTAMP_MONTH_INDEX + DATE_PART_LENGTH)
                .append(LIST_DATE_SEPARATOR)
                .append(originationTimestamp, TIMESTAMP_DAY_INDEX,
                        TIMESTAMP_DAY_INDEX + DATE_PART_LENGTH)
                .append(LIST_DATE_SEPARATOR)
                .append(originationTimestamp, yearIndex, yearIndex + DATE_PART_LENGTH);
        return column.toString();
    }

    /**
     * Projects one transaction-view turn onto the published response.
     *
     * <p>Structural throughout, and the only transformation is the screen-width bound described on
     * {@link #boundToScreenWidth(String, int)}: the service carries the record at the record's own
     * widths and states that applying the screen's narrower widths is this layer's work. Four items are
     * narrower on this screen than in the record - the description, the merchant name, the merchant city
     * and both timestamps - and every other value crosses byte for byte, including the amount, which is
     * published as the exact decimal the service returned.
     *
     * <p>Two parts of the outcome are legitimately absent and are read as absent rather than
     * substituted. A turn that transferred control without composing a screen has no header, and a turn
     * that retrieved nothing has no record - which is how the screen expresses a blank display. Reading
     * an absent part yields an absent value for every item it would have supplied, so the assembly stays
     * a single straight line.
     *
     * @param result the service's turn outcome, never {@code null}
     * @param nextRoute the resolved route token, or {@code null} when the turn resolved none
     * @return the transport response, never {@code null}
     */
    private static TransactionViewResponse toViewResponse(
            final TransactionViewService.TransactionViewResult result, final String nextRoute) {
        final TransactionViewService.ScreenHeader header = Objects.requireNonNullElseGet(
                result.header(),
                () -> new TransactionViewService.ScreenHeader(null, null, null, null, null, null));
        final TransactionViewService.TransactionProjection record = Objects.requireNonNullElseGet(
                result.transaction(),
                () -> new TransactionViewService.TransactionProjection(null, null, null, null, null,
                        null, null, null, null, null, null, null, null));

        return new TransactionViewResponse(
                header.transactionName(),
                header.title01(),
                header.currentDate(),
                header.programName(),
                header.title02(),
                header.currentTime(),
                result.searchTransactionId(),
                record.transactionId(),
                record.cardNumber(),
                record.typeCode(),
                record.categoryCode(),
                record.source(),
                boundToScreenWidth(record.description(), VIEW_DESCRIPTION_WIDTH),
                record.amount(),
                boundToScreenWidth(record.originationTimestamp(), VIEW_TIMESTAMP_WIDTH),
                boundToScreenWidth(record.processingTimestamp(), VIEW_TIMESTAMP_WIDTH),
                record.merchantId(),
                boundToScreenWidth(record.merchantName(), VIEW_MERCHANT_NAME_WIDTH),
                boundToScreenWidth(record.merchantCity(), VIEW_MERCHANT_CITY_WIDTH),
                record.merchantZip(),
                result.message(),
                result.errorFlag(),
                result.focusField(),
                nextRoute,
                result.navigationContext());
    }

    /**
     * Projects one transaction-add turn onto the published response.
     *
     * <p>Structural throughout, and narrower than the sibling projections because this screen's own
     * items are already at their declared widths when the service leaves them: the fourteen echoed
     * values, the six header values, the summary message, the error flag and the cursor field all cross
     * unchanged, and no width bound applies.
     *
     * <p>Two values exist only on the path that wrote a record - the identifier the turn derived and the
     * amount it stored - and both are published only from that record. The submitted amount text is not
     * converted here to fill the gap on a rejected turn: converting it is the service's step, and doing
     * it twice would make this class a second authority for a monetary value.
     *
     * <p>The header and the echoed screen are always present in a turn outcome, because the service
     * composes both on every path, so they are read directly.
     *
     * @param result the service's turn outcome, never {@code null}
     * @param nextRoute the resolved route token, or {@code null} when the turn resolved none
     * @return the transport response, never {@code null}
     */
    private static TransactionAddResponse toAddResponse(
            final TransactionAddService.TransactionAddResult result, final String nextRoute) {
        final TransactionAddService.ScreenFields screen = result.screen();
        final TransactionAddService.ScreenHeader header = result.header();
        final TransactionAddService.TransactionProjection written = result.transaction();

        return new TransactionAddResponse(
                (written == null) ? null : written.tranId(),
                screen.accountId(),
                screen.cardNumber(),
                screen.typeCd(),
                screen.categoryCd(),
                screen.source(),
                screen.description(),
                (written == null) ? null : written.tranAmt(),
                screen.origDate(),
                screen.procDate(),
                screen.merchantId(),
                screen.merchantName(),
                screen.merchantCity(),
                screen.merchantZip(),
                screen.confirm(),
                header.transactionName(),
                header.title01(),
                header.currentDate(),
                header.programName(),
                header.title02(),
                header.currentTime(),
                result.message(),
                result.errorFlag(),
                toResponseFieldErrors(result.fieldErrors()),
                result.focusField(),
                nextRoute,
                result.navigationContext());
    }

    /**
     * Translates the per-field detail of a turn into the response contract's own field-error vocabulary.
     *
     * <p>Both vocabularies distinguish the same two legacy states - a field that was not supplied from
     * one supplied wrongly - so the mapping is one to one and total. The switch is exhaustive over the
     * carrier's two constants with no default arm, so adding a third state to either enumeration stops
     * the build here rather than silently degrading a response, and arrow form means no arm can fall
     * through into the next.
     *
     * <p>Order is preserved: the entries arrive in the order the service checked the fields, and that
     * order is what tells a client which failure came first. Nothing is reordered, merged, de-duplicated
     * or filtered, and no text is composed - a per-field explanation is carried exactly as the service
     * supplied it, including its absence.
     *
     * <p>The two identifying components are read as the empty string when absent, because the response
     * contract requires both: an entry a client cannot locate is worse than no entry, and refusing the
     * whole response over a missing label would hide a populated summary message the operator needs.
     *
     * @param fieldErrors the turn's per-field detail, never {@code null} as the outcome normalises it
     * @return the translated detail in the same order, never {@code null}
     */
    private static List<ErrorResponse.FieldError> toResponseFieldErrors(
            final List<ValidationException.FieldError> fieldErrors) {
        final List<ErrorResponse.FieldError> translated = new ArrayList<>(fieldErrors.size());
        for (final ValidationException.FieldError fieldError : fieldErrors) {
            translated.add(new ErrorResponse.FieldError(
                    Objects.requireNonNullElse(fieldError.field(), EMPTY),
                    Objects.requireNonNullElse(fieldError.bmsFieldId(), EMPTY),
                    switch (fieldError.state()) {
                        case MISSING -> ErrorResponse.FieldState.MISSING;
                        case INVALID -> ErrorResponse.FieldState.INVALID;
                    },
                    fieldError.message()));
        }
        return translated;
    }

    /**
     * Bounds one stored value to the width of the screen item that displays it.
     *
     * <p>This is the presentation bound the two read screens require and nothing more. The legacy
     * assigns a stored value into a narrower screen item, which keeps the item's worth of leading
     * characters, and that is what this reproduces. It is a width and not an offset into a record: the
     * figure comes from the map item the value is displayed in, no position is computed, and no value is
     * decoded, split or re-assembled.
     *
     * <p>Total and non-altering otherwise. An absent value stays absent, and a value already within the
     * width crosses byte for byte with every leading and trailing space intact, so a blank display area
     * and a space-padded stored value both survive.
     *
     * @param value the stored value, possibly {@code null}
     * @param screenWidth the width of the screen item that displays it
     * @return the value as the screen shows it, or {@code null} when there is none
     */
    private static String boundToScreenWidth(final String value, final int screenWidth) {
        if (value == null || value.length() <= screenWidth) {
            return value;
        }
        return value.substring(0, screenWidth);
    }

    /**
     * Names what a read turn concluded, for the turn timer's outcome tag.
     *
     * <p>Two values, from the one flag the two read screens raise. The reason for a rejection is not
     * folded into the tag: the screens report several and the reason is carried in the response body,
     * where a client reads it, whereas putting it in a label would multiply the series without telling
     * an operator anything the body does not already say.
     *
     * @param errorFlag whether the turn raised its error flag
     * @return one of the two outcome constants, never {@code null}
     */
    private static String outcomeOf(final boolean errorFlag) {
        return errorFlag ? OUTCOME_REJECTED : OUTCOME_ACCEPTED;
    }

    /**
     * Names what an add turn concluded, for the turn timer's outcome tag.
     *
     * <p>Three values, because the screen has three conclusions and they are operationally different: a
     * rejected turn changed nothing and told the operator why, an unconfirmed turn changed nothing and
     * asked the operator to confirm, and an added turn wrote a record. Collapsing the middle one into
     * either neighbour would make the confirmation gate invisible in the metric.
     *
     * @param result the service's turn outcome, never {@code null}
     * @return one of the three outcome constants, never {@code null}
     */
    private static String addOutcome(final TransactionAddService.TransactionAddResult result) {
        if (result.errorFlag()) {
            return OUTCOME_REJECTED;
        }
        return result.transactionAdded() ? OUTCOME_ADDED : OUTCOME_UNCONFIRMED;
    }

    /**
     * Records the elapsed time of one turn, tagged by what it concluded and where it resolved to.
     *
     * <p>Tagged rather than counted as one aggregate because the operationally interesting question is
     * the shape of the mix: a rise in rejected list turns and a rise in unconfirmed add turns mean
     * different things. Both tags are bounded by construction - the outcome is one of four declared
     * constants and the route is one of the seventeen tokens the navigation vocabulary declares - so
     * neither can become a high-cardinality label whatever a caller submits. No threshold, budget or
     * target is expressed here or anywhere in this class; the timer records what happened.
     *
     * @param sample the timing sample started at the head of the turn
     * @param metricName the timer's name, one of the three declared above
     * @param description the timer's description, naming the screen it measures
     * @param outcome what the turn concluded
     * @param route the resolved route token, or {@code null} when the turn resolved none
     */
    private void recordTurn(final Timer.Sample sample, final String metricName,
                            final String description, final String outcome, final String route) {
        sample.stop(Timer.builder(metricName)
                .description(description)
                .tag(TAG_OUTCOME, outcome)
                .tag(TAG_ROUTE, Objects.requireNonNullElse(route, ROUTE_ABSENT))
                .register(this.meterRegistry));
    }
}
