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

import com.carddemo.api.dto.CardDetailRequest;
import com.carddemo.api.dto.CardDetailResponse;
import com.carddemo.api.dto.CardListRequest;
import com.carddemo.api.dto.CardListResponse;
import com.carddemo.api.dto.CardUpdateRequest;
import com.carddemo.api.dto.CardUpdateResponse;
import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.api.dto.NavigationContext;
import com.carddemo.api.dto.PageMetadata;
import com.carddemo.api.dto.ScreenWorkArea;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.ValidationException;
import com.carddemo.service.CardDetailService;
import com.carddemo.service.CardListService;
import com.carddemo.service.CardUpdateService;
import com.carddemo.service.NavigationService;
import com.carddemo.service.ScreenNavigationState;
import com.carddemo.util.PfKeyTranslator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface of the three card screens: {@code CCLI} card list, {@code CCDL} card detail and
 * {@code CCUP} card update.
 *
 * <p><strong>What this class does and does not do.</strong> Each operation binds one request, hands it
 * to the one service that owns that screen, projects the returned turn onto the published response
 * contract and records how long the turn took. It holds no rule of its own. There is no filter test
 * here, no cursor arithmetic, no selector parsing, no page assembly, no record lookup, no field
 * validation, no before-image comparison, no persistence and no field decoration - every one of those
 * lives in {@link CardListService}, {@link CardDetailService} or {@link CardUpdateService}, which is
 * what lets all three be exercised without a servlet and what keeps this class from becoming a second,
 * divergent copy of the card rules.
 *
 * <p><strong>Three screens and exactly three operations.</strong> {@code app/csd/CARDDEMO.CSD} binds
 * {@code CCDL} to {@code COCRDSLC} at line 347, {@code CCLI} to {@code COCRDLIC} at line 357 and
 * {@code CCUP} to {@code COCRDUPC} at line 367. A fourth definition, {@code CDV1} at line 388, names
 * program {@code COCRDSEC}, and <strong>that program has no source member anywhere in the estate</strong>
 * - anomaly register entry 3 of {@code docs/decision-log.md}, and the reason
 * {@code config/SecurityConfig} carries that transaction marked as having no implemented program. It was
 * therefore never dispatchable on the mainframe either, so no method, route, service call, response type
 * or placeholder for it appears here. Three operations is the whole surface.
 *
 * <p><strong>Why transfer of control became a value in the response body.</strong> The legacy screens
 * move between one another with {@code XCTL} and re-arm themselves with {@code RETURN TRANSID}. Neither
 * is reproduced. Each service settles on a destination in the navigation vocabulary and this class
 * publishes that destination as {@code nextRoute}, a plain string the client calls next. Nothing is
 * forwarded on the server, no servlet dispatch is used, no transaction is re-armed and no workflow is
 * stored between calls, so each operation is independently reachable and independently testable, and the
 * client drives the sequence exactly as the terminal operator used to.
 *
 * <p><strong>Why every outcome answers {@code 200}.</strong> Each of these turns is a screen the legacy
 * program successfully composed and sent, including the ones that report a rejection: on the mainframe
 * the transaction completed in every case and the operator read the outcome from the screen. The outcome
 * is therefore read from the body here too. Reporting a rejected filter or a failed edit as a client or
 * server error would modernise an externally observable contract and would additionally leak which
 * rejection occurred to anything inspecting only the status line. A malformed request - one whose values
 * could not have occupied the legacy screen field at all - is a different matter and is rejected by
 * declarative validation before the method body runs, which {@link GlobalExceptionHandler} shapes into a
 * {@code 400}.
 *
 * <p><strong>Authorization.</strong> All three routes sit under {@link #CARDS_BASE_PATH}, which the
 * filter chain names as an online-data operator surface. Both user types the estate declares keep access,
 * matching the three ordinary transaction definitions, while an unrelated authenticated principal does
 * not inherit card-wide access from the catch-all. No ownership relation is inferred from a caller-supplied
 * account or card number because the sign-on record declares none. Nothing here shadows or reroutes the
 * management endpoints or the generated interface description; the OpenAPI document remains the sole
 * responsibility of {@code config/OpenApiConfig}.
 *
 * <p><strong>The page size of seven is a contract, not a tuning parameter.</strong>
 * {@code app/cbl/COCRDLIC.cbl} fixes it three separate ways - the screen-line constant at lines 177 to
 * 178, the seven-occurrence row table at line 255, and the 196-byte presentation area at lines 250 to
 * 253 that is exactly twenty-eight bytes of account, card and status per row for seven rows. It is
 * published as {@link PageMetadata#CARD_LIST_PAGE_SIZE} and is never adjusted, negotiated or overridden
 * from a request.
 *
 * <p><strong>Four places where the two published contracts differ, and how each is resolved.</strong>
 * <ul>
 *   <li><em>The attention key.</em> Every card service consumes the raw terminal identifier and decodes
 *       it through {@link PfKeyTranslator}, the single owner of the legacy key store, while the request
 *       contracts carry the already-resolved {@link KeyAction}. The projection back to an identifier is
 *       built once, at class initialisation, out of that translator's own published vocabulary, so the
 *       mapping is not restated here and the fold of the high program-function keys onto their low twins
 *       continues to hold. See {@link #TERMINAL_IDENTIFIERS}.</li>
 *   <li><em>The list paging conclusions.</em> {@link CardListRequest} states that the page size, the two
 *       availability indicators and the displayed page number are server conclusions which it does not
 *       accept from a caller - which is why its inbound paging shape is the cursor request rather than
 *       the full metadata - and declares the browse keys authoritative. The cursor request is therefore
 *       passed through untouched and the three conclusions are supplied as the first-page neutral that
 *       {@link CardListService} itself applies when no paging state has been carried. No inference, no
 *       arithmetic and no cursor logic happens at this boundary.</li>
 *   <li><em>The update carry-through.</em> The change action and the before-image are optional on the
 *       service input and default there, and the sealed proof that would carry them can only be minted
 *       or verified against a loaded card record, which is service-tier work and needs a persistence
 *       type this class must not import. The proof is therefore echoed back exactly as the client sent
 *       it - never minted, unsealed, parsed, compared or logged - so that it survives the round trip the
 *       request contract requires of it.</li>
 *   <li><em>The list heading.</em> The card-list turn returns no screen heading, where the detail and
 *       update turns both do. The transaction and program identifiers are published from the constants
 *       below, because those are fixed facts about the screen; the two title lines and the clock
 *       readings are left absent rather than fabricated, and the module omits absent properties from the
 *       serialized form.</li>
 * </ul>
 *
 * <p><strong>No screen artefact crosses this boundary.</strong> The services return field protection
 * states, highlight flags, darkening flags and confirmation-key emphasis alongside the values; none of
 * them is read here and none appears in any response. No attribute byte, colour, highlight, cursor
 * position, map coordinate, stopper field, duplicated symbolic-map item, blank-field marker or
 * fixed-width offset is present in this file or in anything it publishes. Field-level error state
 * travels as the two-state semantic contract on {@link ErrorResponse} and nothing else.
 *
 * <p>Stateless and immutable: four final collaborators, no mutable field, no request state retained
 * between calls and no mutable collection published, so the singleton is safe for unsynchronised
 * concurrent use.
 *
 * <p>Provenance: {@code app/cbl/COCRDLIC.cbl}, {@code app/cbl/COCRDSLC.cbl},
 * {@code app/cbl/COCRDUPC.cbl}, the three symbolic maps under {@code app/cpy-bms}, the three mapsets
 * under {@code app/bms} and {@code app/csd/CARDDEMO.CSD}, all read as read-only reference at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Cited by name, width and line number only; no
 * legacy source statement is reproduced.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping(CardController.CARDS_BASE_PATH)
public class CardController {

    /**
     * Common prefix of the three card routes.
     *
     * <p>Declared here, with the security configuration reading path constants from the boundary rather
     * than the other way round, so that configuration may depend on the boundary and the boundary never
     * depends on configuration. It is deliberately neither the single anonymous path nor beneath the
     * administrative prefix, so the catch-all rule applies and a mistyped mapping fails closed - which is
     * the safe direction for that mistake to fail in.
     */
    public static final String CARDS_BASE_PATH = "/api/cards";

    /** Route of one card-list turn, legacy transaction {@code CCLI}. */
    public static final String CARD_LIST_PATH = "/list";

    /** Route of one card-detail turn, legacy transaction {@code CCDL}. */
    public static final String CARD_DETAIL_PATH = "/detail";

    /** Route of one card-update turn, legacy transaction {@code CCUP}. */
    public static final String CARD_UPDATE_PATH = "/update";

    /**
     * Transaction identifier of the card-list screen, {@code app/csd/CARDDEMO.CSD} line 357.
     *
     * <p>Published as the transaction name the list response echoes for display. A transaction
     * identifier is one of the few pieces of legacy material this boundary is entitled to name, and it is
     * a fixed fact rather than a value any turn computes.
     */
    public static final String TRANSACTION_CARD_LIST = "CCLI";

    /** Transaction identifier of the card-detail screen, {@code app/csd/CARDDEMO.CSD} line 347. */
    public static final String TRANSACTION_CARD_DETAIL = "CCDL";

    /** Transaction identifier of the card-update screen, {@code app/csd/CARDDEMO.CSD} line 367. */
    public static final String TRANSACTION_CARD_UPDATE = "CCUP";

    /**
     * Program name of the card-list screen, bound to {@link #TRANSACTION_CARD_LIST} at
     * {@code app/csd/CARDDEMO.CSD} line 357.
     *
     * <p>Published as the program name the list response echoes for display, for the reason given on the
     * transaction constant: the card-list turn returns no heading of its own, and this is a fixed fact
     * about the screen rather than an invention.
     */
    public static final String PROGRAM_CARD_LIST = "COCRDLIC";

    /** Diagnostic channel. Carries no business key, no cardholder value and no request body. */
    private static final Logger LOG = LoggerFactory.getLogger(CardController.class);

    /** Timer name for one card-list turn, following the sign-on screen's naming. */
    private static final String METRIC_CARD_LIST_TURN = "carddemo.online.cardlist.turn";

    /** Timer name for one card-detail turn. */
    private static final String METRIC_CARD_DETAIL_TURN = "carddemo.online.carddetail.turn";

    /** Timer name for one card-update turn. */
    private static final String METRIC_CARD_UPDATE_TURN = "carddemo.online.cardupdate.turn";

    /** Tag naming the destination the turn settled on. */
    private static final String TAG_OUTCOME = "outcome";

    /**
     * Tag value used when a turn settled on no destination at all.
     *
     * <p>Every service initialises its destination to its own screen and never clears it, so this value
     * is not expected to be recorded. It exists because a meter tag may not be absent, and answering a
     * request must never fail inside the instrumentation that measures it.
     */
    private static final String OUTCOME_UNRESOLVED = "unresolved";

    /** Tag value used when a turn raises before settling on a destination. */
    private static final String OUTCOME_FAILED = "failed";

    /**
     * Stand-in supplied to a service when the caller pressed a key that resolved to nothing.
     *
     * <p>An empty identifier matches no arm of the key store, which is precisely the state the legacy
     * reaches when the terminal sends an identifier its own translation does not recognise, and the
     * services already report that state. It is used rather than an absent reference because the update
     * screen's decode is unconditional and an absent reference is a caller defect there, not a key that
     * meant nothing.
     */
    private static final String ABSENT_ATTENTION_KEY_IDENTIFIER = "";

    /**
     * Page number supplied to the card-list service when the caller has carried no paging conclusion.
     *
     * <p>The request contract does not accept a page number, an end-of-browse indicator or a
     * further-pages indicator, because all three are conclusions the browse reaches rather than facts a
     * caller may assert. This is the same first-page value the service applies on its own no-carried-
     * state path, so supplying it asserts nothing the service would not have assumed anyway.
     */
    private static final int FIRST_PAGE_NUMBER = 1;

    /** Empty text used where a response component requires a value the source may leave absent. */
    private static final String EMPTY = "";

    /**
     * Projection from a resolved attention key back to the raw terminal identifier the services decode.
     *
     * <p>Built once, during class initialisation, by asking {@link PfKeyTranslator} what each of its own
     * recognised identifiers denotes. Nothing about the key store is restated here: the translator
     * remains its single owner, and if an arm of it changed this map would change with it rather than
     * drift away from it. Its published vocabulary is ordered as the legacy construct's arms are, and the
     * first identifier reaching each action is kept, so an action produced by both a low and a high
     * program-function key is projected back onto the low one - which is what keeps the two
     * indistinguishable in both directions.
     *
     * <p>Immutable and unmodifiable, so the singleton publishes no mutable state.
     */
    private static final Map<KeyAction, String> TERMINAL_IDENTIFIERS = terminalIdentifiers();

    /** The card-list screen, legacy transaction {@code CCLI}. */
    private final CardListService cardListService;

    /** The card-detail screen, legacy transaction {@code CCDL}. */
    private final CardDetailService cardDetailService;

    /** The card-update screen, legacy transaction {@code CCUP}. */
    private final CardUpdateService cardUpdateService;

    /**
     * The only permitted converter between the wire screen carriers and the service-owned ones.
     *
     * <p>All three screens take the echoed navigation record, and return it, in the form the service layer
     * owns; the list and detail screens additionally hand over the work area in that form, the list screen
     * hands over the inbound cursor pair and publishes the assembled browse window, and the update screen
     * publishes its field marks through the same seam. None of that may travel as an {@code api.dto} type,
     * because nothing may depend upward. This collaborator is where every crossing happens, and it converts
     * positionally: no blank-significant member, boundary cursor or leading-zero page indicator is trimmed,
     * padded, defaulted or reconciled on the way through.
     */
    private final ScreenStateAdapter screenStateAdapter;

    /** Registry the three turn timers are registered against. */
    private final MeterRegistry meterRegistry;

    /**
     * Creates the controller over its collaborators.
     *
     * <p>Constructor injection only, with every collaborator final and checked here, so an instance is
     * either fully formed or does not exist. No field is injected and no field is mutable.
     *
     * @param cardListService the card-list screen
     * @param cardDetailService the card-detail screen
     * @param cardUpdateService the card-update screen
     * @param screenStateAdapter the converter between the wire screen carriers and the service-owned ones
     * @param meterRegistry the metrics registry the turn timers are registered against
     */
    public CardController(final CardListService cardListService,
                          final CardDetailService cardDetailService,
                          final CardUpdateService cardUpdateService,
                          final ScreenStateAdapter screenStateAdapter,
                          final MeterRegistry meterRegistry) {
        this.cardListService = Objects.requireNonNull(cardListService,
                "cardListService must not be null");
        this.cardDetailService = Objects.requireNonNull(cardDetailService,
                "cardDetailService must not be null");
        this.cardUpdateService = Objects.requireNonNull(cardUpdateService,
                "cardUpdateService must not be null");
        this.screenStateAdapter = Objects.requireNonNull(screenStateAdapter,
                "screenStateAdapter must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    // ==================================================================================================
    // CCLI - card list and search
    // ==================================================================================================

    /**
     * Serves one turn of the card-list screen, legacy transaction {@code CCLI}, program
     * {@code COCRDLIC}.
     *
     * <p>The turn is submitted as a body rather than as query parameters because the screen carries a
     * nested browse cursor and a nested navigation record, neither of which can round-trip through flat
     * parameters without losing the distinction between an absent value and a blank one - a distinction
     * both filters depend on. It changes no state: the whole turn is a browse plus, at most, a decision
     * about where the client goes next.
     *
     * <p>Everything the screen does happens in {@link CardListService}: the two optional filters and
     * their digit-format rejections, the seven action selectors with their single-action limit, the
     * forward and backward browse, the end-of-browse messages and the choice between the detail and the
     * update destination. This method supplies the transmitted values, hands them over once, and
     * publishes what comes back - including the row order exactly as the service settled it, which for a
     * backward browse is the presentation order the legacy screen produced and must not be re-sorted.
     *
     * @param request the transmitted screen: two optional filters, seven action selectors, the browse
     *        cursor the previous turn handed back, the attention key and the echoed navigation state
     * @return the screen the turn produces, carrying at most seven rows, the paging state and the route
     *         the client calls next
     */
    @PostMapping(path = CARD_LIST_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List and search cards",
            description = "One turn of legacy transaction " + TRANSACTION_CARD_LIST + ". Lists at most "
                    + "seven cards - the page "
                    + "size the legacy screen fixes - narrowed by an optional eleven-character account "
                    + "filter and an optional sixteen-character card filter, and reports where a row "
                    + "selection sends the client next. Answers 200 for every outcome the legacy screen "
                    + "could compose, including a rejected filter or an invalid selection: the outcome "
                    + "is read from the body.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The turn completed. Carries the page of rows in presentation order, the "
                        + "browse cursors, any screen message, the positional selection indicator and "
                        + "the route the client calls next."),
        @ApiResponse(responseCode = "400",
                description = "The request carried a value that could not have occupied its legacy "
                        + "screen field."),
        @ApiResponse(responseCode = "401", description = "No authenticated caller."),
        @ApiResponse(responseCode = "403",
                description = "The authenticated principal is not an approved online-data operator.")})
    public ResponseEntity<CardListResponse> listCards(
            @Valid @RequestBody final CardListRequest request) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);
        String outcome = OUTCOME_FAILED;
        try {
            final CardListService.CardListScreenInput input = new CardListService.CardListScreenInput(
                    terminalIdentifierFor(request.keyAction()),
                    this.screenStateAdapter.toInputState(screenWorkAreaOf(request)),
                    request.selectionsInRowOrder(),
                    this.screenStateAdapter.toCursorRequest(request.pageMetadata()),
                    FIRST_PAGE_NUMBER,
                    false,
                    false,
                    this.screenStateAdapter.toNavigationState(request.navigationContext()));

            final CardListService.CardListResult result = this.cardListService.processCardList(input);
            final CardListResponse response = toListResponse(request, result);

            outcome = outcomeOf(result.route());
            LOG.debug("Card-list turn complete: route={} rows={} error={}",
                    outcome, result.rows().size(), result.errorFlag());
            return ResponseEntity.ok(response);
        } finally {
            recordTurn(sample, METRIC_CARD_LIST_TURN,
                    "Elapsed time of one CardDemo card-list turn, transaction CCLI", outcome);
        }
    }

    // ==================================================================================================
    // CCDL - card detail
    // ==================================================================================================

    /**
     * Serves one turn of the card-detail screen, legacy transaction {@code CCDL}, program
     * {@code COCRDSLC}.
     *
     * <p>A read, so it is reachable by the idempotent method and carries no body. <strong>Both business
     * keys are optional and neither is a path segment.</strong> That is not a stylistic choice: the
     * screen distinguishes an absent account from an absent card from both absent, and answers each with
     * its own message, so a mandatory path segment would make two of those three outcomes unreachable
     * and would silently drop a behaviour the legacy screen has.
     *
     * <p>The echoed navigation record is bound from its own component names, which are distinct from the
     * two filter parameter names, so the carried account and card - what the previous screen selected -
     * can never be confused with the account and card the operator typed. {@link CardDetailService}
     * needs both pairs and reads them separately.
     *
     * <p>An arrival here from the card list is response metadata on that screen's turn and nothing more.
     * No request is forwarded, and this operation is reached by the client calling it.
     *
     * @param accountIdFilter the eleven-character account the operator typed, or {@code null} when the
     *        field was left alone
     * @param cardNumberFilter the sixteen-character card the operator typed, or {@code null} when the
     *        field was left alone
     * @param keyAction the attention key the operator pressed, or {@code null} when the key resolved to
     *        nothing
     * @param navigationContext the echoed navigation state, whose components bind from their own names;
     *        an entirely absent state reads as no carry-over, which is how a first arrival presents
     * @return the screen the turn produces, carrying the card when one was found and the screen message
     *         in every case
     */
    @PostMapping(path = CARD_DETAIL_PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "View one card's details",
            description = "One turn of legacy transaction " + TRANSACTION_CARD_DETAIL + ". Both search "
                    + "keys are optional, because the legacy screen answers an absent account, an absent "
                    + "card and no input at all with three different messages.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The turn completed and carries the card details when one was found.")})
    public ResponseEntity<CardDetailResponse> viewCardDetail(
            @Valid @RequestBody(required = false) final CardDetailRequest request) {
        final CardDetailRequest bounded = request == null ? CardDetailRequest.empty() : request;
        return viewCardDetail(bounded.accountIdFilter(), bounded.cardNumberFilter(),
                bounded.keyAction(), bounded.navigationContext());
    }

    @Operation(summary = "View one card's details",
            description = "One turn of legacy transaction " + TRANSACTION_CARD_DETAIL + ". Both search "
                    + "keys are optional, because "
                    + "the legacy screen answers an absent account, an absent card and no input at all "
                    + "with three different messages. Answers 200 for every outcome the legacy screen "
                    + "could compose, including not finding the card: the outcome is read from the "
                    + "body.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The turn completed. Carries the card's details when one was found, the "
                        + "screen message, the field the cursor returns to and the route the client "
                        + "calls next."),
        @ApiResponse(responseCode = "400",
                description = "A parameter carried a value that could not have occupied its legacy "
                        + "screen field."),
        @ApiResponse(responseCode = "401", description = "No authenticated caller."),
        @ApiResponse(responseCode = "403",
                description = "The authenticated principal is not an approved online-data operator.")})
    public ResponseEntity<CardDetailResponse> viewCardDetail(
            @RequestParam(name = "accountIdFilter", required = false) final String accountIdFilter,
            @RequestParam(name = "cardNumberFilter", required = false) final String cardNumberFilter,
            @RequestParam(name = "keyAction", required = false) final KeyAction keyAction,
            @Valid @ModelAttribute final NavigationContext navigationContext) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);
        String outcome = OUTCOME_FAILED;
        try {
            final CardDetailService.CardDetailScreenInput input =
                    new CardDetailService.CardDetailScreenInput(
                            accountIdFilter,
                            cardNumberFilter,
                            terminalIdentifierFor(keyAction),
                            this.screenStateAdapter.toNavigationState(navigationContext));

            final CardDetailService.CardDetailResult result =
                    this.cardDetailService.processCardDetail(input);
            final CardDetailResponse response = toDetailResponse(result);

            outcome = outcomeOf(result.route());
            LOG.debug("Card-detail turn complete: route={} cardPresented={} error={}",
                    outcome, result.cardPresented(), result.errorFlag());
            return ResponseEntity.ok(response);
        } finally {
            recordTurn(sample, METRIC_CARD_DETAIL_TURN,
                    "Elapsed time of one CardDemo card-detail turn, transaction CCDL", outcome);
        }
    }

    // ==================================================================================================
    // CCUP - card update
    // ==================================================================================================

    /**
     * Serves one turn of the card-update screen, legacy transaction {@code CCUP}, program
     * {@code COCRDUPC}.
     *
     * <p>The screen is a state machine - search, show the details, take the changes, validate them,
     * confirm, and then report success, a lock conflict or a failure - and every one of those states
     * belongs to {@link CardUpdateService}. This method carries no part of it. Nothing is stored between
     * calls: the state travels in the request and the response, which is what makes each turn
     * independently replayable and what stops a second, server-held copy of the screen's position from
     * existing at all.
     *
     * <p>Two values are protected carry-through rather than input and are treated as such here. The
     * expiry day is hidden and unwritable on every state of the legacy screen, so the request contract
     * declines to bind it and the service supplies the stored value; the response publishes it so the
     * round trip survives. The account identifier is protected once a card has been fetched, and the
     * request contract requires its absence on the one turn that writes. Neither gets an editing surface
     * here.
     *
     * <p>The sealed proof of the record as it stood when the screen was presented is echoed back
     * untouched. This boundary does not mint it, does not open it, does not compare it and does not log
     * it - it cannot, because doing any of those needs the loaded card record, which belongs to the
     * service tier.
     *
     * @param request the transmitted screen: the two business keys, the three editable values, the
     *        attention key, the echoed navigation state and the sealed proof
     * @return the screen the turn produces, carrying the values to redisplay, the screen message, any
     *         field-level error state and the route the client calls next
     */
    @PostMapping(path = CARD_UPDATE_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update one card's details",
            description = "One turn of legacy transaction " + TRANSACTION_CARD_UPDATE + ". The screen "
                    + "is a state machine that "
                    + "searches, presents, validates, confirms and writes across successive turns, and "
                    + "the whole of that state travels in the request and the response rather than "
                    + "being held on the server. Answers 200 for every outcome the legacy screen could "
                    + "compose, including a rejected edit, a concurrency notice and a failed write: the "
                    + "outcome is read from the body.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The turn completed. Carries the values to redisplay, the screen message, "
                        + "per-field missing-or-invalid state, the sealed proof to echo on the next turn "
                        + "and the route the client calls next."),
        @ApiResponse(responseCode = "400",
                description = "The request carried a value that could not have occupied its legacy "
                        + "screen field, or supplied a value the confirming turn protects."),
        @ApiResponse(responseCode = "401", description = "No authenticated caller."),
        @ApiResponse(responseCode = "403",
                description = "The authenticated principal is not an approved online-data operator.")})
    public ResponseEntity<CardUpdateResponse> updateCard(
            @Valid @RequestBody final CardUpdateRequest request) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);
        String outcome = OUTCOME_FAILED;
        try {
            final CardUpdateService.CardUpdateScreenInput input =
                    new CardUpdateService.CardUpdateScreenInput(
                            request.accountId(),
                            request.cardNumber(),
                            request.embossedName(),
                            request.activeStatus(),
                            request.expiryMonth(),
                            request.expiryYear(),
                            request.expiryDay(),
                            terminalIdentifierFor(request.keyAction()),
                            carriedUpdateState(request.navigationContext()),
                            null,
                            null);

            final CardUpdateService.CardUpdateResult result =
                    this.cardUpdateService.processCardUpdate(input);
            final CardUpdateResponse response = toUpdateResponse(request, result);

            outcome = outcomeOf(result.route());
            LOG.debug("Card-update turn complete: route={} committed={} error={} fieldErrors={}",
                    outcome, result.updateCommitted(), result.errorFlag(),
                    result.fieldErrors().size());
            return ResponseEntity.ok(response);
        } finally {
            recordTurn(sample, METRIC_CARD_UPDATE_TURN,
                    "Elapsed time of one CardDemo card-update turn, transaction CCUP", outcome);
        }
    }

    // ==================================================================================================
    // Request projection - transmitted values onto the shapes the three screens accept
    // ==================================================================================================

    /**
     * Stages the two card-list filters and the attention key into the work area the screen reads them
     * from.
     *
     * <p>Both filters are carried exactly as transmitted: not trimmed, not padded, not upper-folded and
     * not defaulted, because the browse compares a filter to a retrieved record character for character,
     * and because the screen distinguishes blank from supplied-but-unusable from supplied-and-usable. The
     * six remaining components describe where the screen is going and what it is saying, which is the
     * screen's conclusion rather than the caller's, so they are left absent for it to settle.
     *
     * @param request the transmitted screen
     * @return the work area the card-list screen reads, never {@code null}
     */
    private static ScreenWorkArea screenWorkAreaOf(final CardListRequest request) {
        return new ScreenWorkArea(
                request.keyAction(),
                null,
                null,
                null,
                null,
                null,
                request.accountIdFilter(),
                request.cardNumberFilter(),
                null);
    }

    /**
     * Converts the echoed navigation record for the card-update screen, preserving a wholly absent one.
     *
     * <p>The conversion itself is the shared positional one. What is deliberate here is the guard in front
     * of it. The list and detail screens ask only whether the state they were handed is absent <em>or</em>
     * all-blank and treat the two identically, so handing them the empty carrier in place of nothing changes
     * no outcome. The update screen does not: its reset condition is the analogue of {@code EIBCALEN IS
     * EQUAL TO 0}, which asks whether a communication area arrived at all, and it answers differently for a
     * turn that echoed nothing than for one that echoed an all-blank record. Filling the absence in would
     * turn the first turn of a conversation into a continuation of one, losing the first-entry gate the
     * reset raises and with it the fetch the screen performs on entry.
     *
     * @param context the record the client echoed, which may be {@code null}
     * @return the service-owned state, or {@code null} when the client echoed no record at all
     */
    private ScreenNavigationState carriedUpdateState(final NavigationContext context) {
        return (context == null) ? null : this.screenStateAdapter.toNavigationState(context);
    }

    /**
     * Projects a resolved attention key back onto the raw terminal identifier the services decode.
     *
     * <p>A key that resolved to nothing, and a key outside the translator's vocabulary, both yield the
     * empty identifier. That is deliberate and it is not a substitute action: an empty identifier matches
     * no arm of the key store, which is exactly the state the legacy reaches when the terminal sends
     * something its own translation does not recognise, and each service already has an outcome for it.
     * Supplying an absent reference instead is not equivalent - the update screen decodes
     * unconditionally and treats an absent reference as a caller defect rather than as a key that meant
     * nothing.
     *
     * @param keyAction the resolved attention key, or {@code null} when the key resolved to nothing
     * @return the raw identifier the services decode, never {@code null}
     */
    private static String terminalIdentifierFor(final KeyAction keyAction) {
        if (keyAction == null) {
            return ABSENT_ATTENTION_KEY_IDENTIFIER;
        }
        return TERMINAL_IDENTIFIERS.getOrDefault(keyAction, ABSENT_ATTENTION_KEY_IDENTIFIER);
    }

    /**
     * Builds the immutable projection from action back to raw terminal identifier, once.
     *
     * <p>Assembled by asking {@link PfKeyTranslator} what each of its own published identifiers denotes,
     * so the key store is consulted rather than copied and the two directions cannot drift apart. The
     * published order is the legacy construct's arm order and the first identifier to reach an action is
     * the one kept, which is why an action reachable from both a low and a high program-function key
     * projects back onto the low one - the two must stay indistinguishable in both directions.
     *
     * @return the projection, unmodifiable and complete over every action the translator recognises
     */
    private static Map<KeyAction, String> terminalIdentifiers() {
        final Map<KeyAction, String> projection = new EnumMap<>(KeyAction.class);
        for (final String identifier : PfKeyTranslator.recognisedIdentifiers()) {
            PfKeyTranslator.translate(identifier)
                    .ifPresent(action -> projection.putIfAbsent(action, identifier));
        }
        return Collections.unmodifiableMap(projection);
    }

    // ==================================================================================================
    // Response projection - a settled turn onto the published contract
    // ==================================================================================================

    /**
     * Projects a settled card-list turn onto the published contract.
     *
     * <p>The heading is the one place this screen differs from the other two: its turn returns no heading
     * at all, so the transaction and program identifiers come from this class's constants, being fixed
     * facts about the screen, and the two title lines and the two clock readings are left absent rather
     * than invented. The module omits absent properties from the serialized form, so a client sees them
     * missing rather than blank.
     *
     * <p>The rows are published in the order the service settled them and are neither re-ordered,
     * re-sorted nor padded. For a backward browse that order is the one the legacy screen presented after
     * filling its rows downward from the last slot, so reversing it here would put a page of a backward
     * browse on the screen in the wrong sequence.
     *
     * <p>The selection indicator is positional, and the published contract requires it to align element
     * for element with the rows it describes. The turn reports one flag per row slot whether or not the
     * slot is populated, so it is realigned to the populated rows by asking for each row's own slot.
     *
     * @param request the transmitted screen, whose two filters are echoed for redisplay
     * @param result the settled turn
     * @return the published screen, never {@code null}
     */
    private CardListResponse toListResponse(final CardListRequest request,
            final CardListService.CardListResult result) {
        final PageMetadata paging = this.screenStateAdapter.toPageMetadata(result.pageMetadata());
        return new CardListResponse(
                TRANSACTION_CARD_LIST,
                null,
                null,
                PROGRAM_CARD_LIST,
                null,
                null,
                (paging == null) ? null : paging.displayedPageNumber(),
                request.accountIdFilter(),
                request.cardNumberFilter(),
                toResponseRows(result.rows()),
                alignedSelectionErrorFlags(result),
                result.infoMessage(),
                result.errorMessage(),
                result.errorFlag(),
                paging,
                result.focusField(),
                routeValueOf(result.route()),
                this.screenStateAdapter.toNavigationContext(result.navigationContext()));
    }

    /**
     * Projects the settled rows onto the published row contract, in the order given.
     *
     * <p>Component for component, with no transformation of any kind: the account identifier and the card
     * number are carried at full width because a shortened business key identifies nothing, and the
     * status code is carried raw because the legacy takes it straight from the record and a code outside
     * the known pair must flow through untouched rather than being rejected or absorbed. The row's screen
     * slot is not published - it is positional information the list order already carries - and no
     * attribute, colour or coordinate exists to publish.
     *
     * @param rows the settled rows in presentation order
     * @return the published rows, unmodifiable and in the same order
     */
    private static List<CardListResponse.CardListRow> toResponseRows(
            final List<CardListService.CardListRow> rows) {
        final List<CardListResponse.CardListRow> projected = new ArrayList<>(rows.size());
        for (final CardListService.CardListRow row : rows) {
            projected.add(new CardListResponse.CardListRow(
                    row.selection(),
                    row.accountId(),
                    row.cardNumber(),
                    row.cardActiveStatus()));
        }
        return Collections.unmodifiableList(projected);
    }

    /**
     * Realigns the positional selection indicator from row slots onto the published rows.
     *
     * <p>The turn reports one flag for every row slot the screen has, including the slots a partial page
     * leaves empty, while the published contract requires the indicator to be either empty or exactly as
     * long as the rows it describes. Asking each published row for its own slot satisfies both: an
     * unmarked row is present and false, and a page with no rows produces no indicator.
     *
     * @param result the settled turn
     * @return one flag per published row, unmodifiable and in the same order
     */
    private static List<Boolean> alignedSelectionErrorFlags(
            final CardListService.CardListResult result) {
        final List<Boolean> aligned = new ArrayList<>(result.rows().size());
        for (final CardListService.CardListRow row : result.rows()) {
            aligned.add(result.selectionErrorAt(row.screenSlot()));
        }
        return Collections.unmodifiableList(aligned);
    }

    /**
     * Projects a settled card-detail turn onto the published contract.
     *
     * <p>The heading and the screen values are taken from the turn's own heading and screen groups. The
     * five presentation states that travel beside those values - whether each key is protected, whether
     * each is highlighted, and whether the informational line is darkened - are deliberately not read:
     * they are how a 3270 achieved an effect, and the published contract expresses field state
     * semantically or not at all.
     *
     * @param result the settled turn
     * @return the published screen, never {@code null}
     */
    private CardDetailResponse toDetailResponse(
            final CardDetailService.CardDetailResult result) {
        final CardDetailService.ScreenHeader header = result.header();
        final CardDetailService.ScreenFields screen = result.screen();
        return new CardDetailResponse(
                header.transactionName(),
                header.title01(),
                header.currentDate(),
                header.programName(),
                header.title02(),
                header.currentTime(),
                screen.accountIdFilter(),
                screen.cardNumberFilter(),
                screen.embossedName(),
                screen.cardActiveStatus(),
                screen.expiryMonth(),
                screen.expiryYear(),
                screen.infoMessage(),
                screen.errorMessage(),
                result.errorFlag(),
                result.focusField(),
                routeValueOf(result.route()),
                this.screenStateAdapter.toNavigationContext(result.navigationContext()));
    }

    /**
     * Projects a settled card-update turn onto the published contract.
     *
     * <p>The expiry day is published from the turn's screen group, which is what makes the hidden
     * protected value survive a round trip the operator can neither see nor type into. The field
     * protection state and the confirmation-key emphasis that travel beside it are not read, for the
     * reason given on the detail projection.
     *
     * <p>The sealed proof is the caller's own, returned unchanged. Nothing here mints one, opens one,
     * measures one or writes one to a log; the value is opaque at this boundary by construction, and
     * echoing it is what lets the client complete the confirming turn with the proof it was given.
     *
     * @param request the transmitted screen, whose sealed proof is echoed back
     * @param result the settled turn
     * @return the published screen, never {@code null}
     */
    private CardUpdateResponse toUpdateResponse(final CardUpdateRequest request,
            final CardUpdateService.CardUpdateResult result) {
        final CardUpdateService.ScreenHeader header = result.header();
        final CardUpdateService.ScreenFields screen = result.screen();
        return new CardUpdateResponse(
                header.transactionName(),
                header.title01(),
                header.currentDate(),
                header.programName(),
                header.title02(),
                header.currentTime(),
                screen.accountId(),
                screen.cardNumber(),
                screen.embossedName(),
                screen.activeStatus(),
                screen.expiryMonth(),
                screen.expiryYear(),
                screen.expiryDay(),
                screen.infoMessage(),
                screen.errorMessage(),
                result.errorFlag(),
                toResponseFieldErrors(result.fieldErrors()),
                result.focusField(),
                routeValueOf(result.route()),
                this.screenStateAdapter.toNavigationContext(result.navigationContext()),
                request.concurrencyToken());
    }

    /**
     * Projects the turn's field-level findings onto the published error contract.
     *
     * <p>A component-for-component rename across one layer boundary and nothing more: no finding is
     * added, dropped, reordered, reworded or re-classified, so the ordered cascade the screen ran remains
     * exactly as it reported itself. This follows the projection {@link GlobalExceptionHandler} already
     * applies to the same pair of types, so a finding reported through a returned turn and the same
     * finding reported through a raised one reach a client in one shape.
     *
     * @param fieldErrors the findings the turn reported, never {@code null}
     * @return the published findings in the same order, never {@code null}
     */
    private static List<ErrorResponse.FieldError> toResponseFieldErrors(
            final List<ValidationException.FieldError> fieldErrors) {
        final List<ErrorResponse.FieldError> projected = new ArrayList<>(fieldErrors.size());
        for (final ValidationException.FieldError fieldError : fieldErrors) {
            projected.add(new ErrorResponse.FieldError(
                    orEmpty(fieldError.field()),
                    orEmpty(fieldError.bmsFieldId()),
                    toResponseFieldState(fieldError.state()),
                    fieldError.message()));
        }
        return Collections.unmodifiableList(projected);
    }

    /**
     * Maps one field state across the layer boundary.
     *
     * <p>One-to-one and total, over an exhaustive arrow switch with no default arm, so adding a third
     * state to either enumeration stops the build here rather than silently degrading a response, and no
     * arm can fall through into the next. There is deliberately no arm for an absent state, because the
     * carrier rejects one at construction: the legacy flag these two constants derive from is either
     * not-supplied or unusable and is never nothing.
     *
     * @param state the state the turn reported
     * @return the published state
     */
    private static ErrorResponse.FieldState toResponseFieldState(
            final ValidationException.FieldState state) {
        return switch (state) {
            case MISSING -> ErrorResponse.FieldState.MISSING;
            case INVALID -> ErrorResponse.FieldState.INVALID;
        };
    }

    /**
     * Substitutes the empty string for an absent value.
     *
     * <p>Used only where the published contract rejects an absent value and the carrier permits one. It
     * neither trims nor alters a value that is present.
     *
     * @param value the value, possibly {@code null}
     * @return the value, or the empty string when it was absent
     */
    private static String orEmpty(final String value) {
        return (value == null) ? EMPTY : value;
    }

    // ==================================================================================================
    // Navigation vocabulary and instrumentation
    // ==================================================================================================

    /**
     * Reads the destination a turn settled on as the route value a client calls next.
     *
     * <p>The vocabulary belongs to the navigation authority; this only reads it. An absent destination is
     * published as absent rather than as a fabricated route, so a client is never sent somewhere the turn
     * did not choose.
     *
     * @param route the destination the turn settled on, possibly absent
     * @return the route value, or {@code null} when the turn settled on none
     */
    private static String routeValueOf(final NavigationService.Route route) {
        return (route == null) ? null : route.getRouteValue();
    }

    /**
     * Reads the same destination as a meter tag value.
     *
     * <p>Tagged by destination because the operationally interesting question about a screen that routes
     * is where its turns go: a rise in card-detail arrivals and a rise in card-update arrivals mean
     * different things. The value is the navigation vocabulary's own route, which is bounded and
     * enumerated, so the tag cannot become a high-cardinality label whatever a caller sends.
     *
     * @param route the destination the turn settled on, possibly absent
     * @return the tag value, never {@code null}
     */
    private static String outcomeOf(final NavigationService.Route route) {
        return Objects.requireNonNullElse(routeValueOf(route), OUTCOME_UNRESOLVED);
    }

    /**
     * Records the elapsed time of one turn, tagged by the destination it settled on.
     *
     * @param sample the timing sample started at the head of the turn
     * @param metricName the timer naming the screen whose turn this was
     * @param description the timer's description
     * @param route the destination the turn settled on
     */
    private void recordTurn(final Timer.Sample sample, final String metricName,
            final String description, final String outcome) {
        sample.stop(Timer.builder(metricName)
                .description(description)
                .tag(TAG_OUTCOME, outcome)
                .register(this.meterRegistry));
    }
}
