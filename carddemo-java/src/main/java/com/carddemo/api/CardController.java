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
import com.carddemo.service.CardConcurrencyTokenService;
import com.carddemo.service.CardDetailService;
import com.carddemo.service.CardListService;
import com.carddemo.service.CardUpdateService;
import com.carddemo.service.NavigationService;
import com.carddemo.service.ScreenNavigationState;
import com.carddemo.util.ApiRoutePaths;
import com.carddemo.util.PfKeyTranslator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface of the three card screens: {@code CCLI} card list, {@code CCDL} card detail and
 * {@code CCUP} card update.
 *
 * <p><strong>What this class does.</strong> Each operation binds one request, hands it to the one
 * service that owns that screen, projects the returned turn onto the published response contract and
 * records how long the turn took. Every rule - filtering, selector parsing, paging, lookup, validation,
 * before-image comparison and decoration - lives in {@link CardListService},
 * {@link CardDetailService} or {@link CardUpdateService}, which is what lets all three be exercised
 * without a servlet and keeps this class from becoming a second, divergent copy of the card rules.
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
 * <p><strong>Authorization.</strong> Each of the three routes is named individually by the filter chain
 * as an online-data operator surface. Both user types the estate declares keep access, matching the three
 * ordinary transaction definitions, while an unrelated authenticated principal reaches none of them and
 * neither does a caller addressing anything else beneath {@link #CARDS_BASE_PATH}, which the chain's
 * closing refusal covers. No ownership relation is inferred from a caller-supplied
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
 * <p>Cited by name, width and line number only; no legacy source statement is reproduced.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping(CardController.CARDS_BASE_PATH)
public class CardController {

    /**
     * Common prefix of the three card routes.
     *
     * <p>Composed from the neutral route contract in the base layer, which the security configuration
     * reads as well, so that configuration may depend on the boundary and the boundary never depends on
     * configuration. It is deliberately neither the single anonymous path nor beneath the administrative
     * prefix. Only the three composed addresses are granted, so a mistyped mapping beneath this prefix is
     * refused by the chain's closing refusal over the API root - unreachable rather than open, which is
     * the safe direction for that mistake to fail in.
     */
    public static final String CARDS_BASE_PATH = ApiRoutePaths.CARDS_PATH_PREFIX;

    /** Route of one card-list turn, legacy transaction {@code CCLI}. */
    public static final String CARD_LIST_PATH = ApiRoutePaths.LIST_SUBPATH;

    /** Route of one card-detail turn, legacy transaction {@code CCDL}. */
    public static final String CARD_DETAIL_PATH = ApiRoutePaths.DETAIL_SUBPATH;

    /** Route of one card-update turn, legacy transaction {@code CCUP}. */
    public static final String CARD_UPDATE_PATH = ApiRoutePaths.UPDATE_SUBPATH;

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

    /**
     * Tag naming the destination the turn settled on.
     *
     * <p>Bounded by construction: the value is a route from the navigation vocabulary, or one of the two
     * labels below for a turn that settled on no destination or raised before reaching one, so the tag
     * cannot become a high-cardinality label whatever a caller sends.
     */
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
     * Page number supplied to the card-list service when the caller carried no paging state at all.
     *
     * <p>Zero rather than one, and it is the service's own initial value rather than a choice made here:
     * {@code WS-CA-SCREEN-NUM} at {@code app/cbl/COCRDLIC.cbl:L237} starts at zero and the program's test
     * at line 1177 looks for that zero before raising the number to one. Supplying one here would skip
     * that test and assert a page the browse had not yet walked to.
     */
    private static final int NO_RETAINED_PAGE_NUMBER = 0;

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

    /**
     * Seals and opens the update screen's conversation state.
     *
     * <p>The card-update screen is a state machine whose state lived in the CICS communication area -
     * server-held storage the terminal could not reach. Nothing equivalent exists here, so the state has
     * to cross the wire, and a state that crosses the wire has to be authenticated: a client that could
     * name its own change action would be able to present itself as already past the validate-and-confirm
     * stages and reach the rewrite at {@code app/cbl/COCRDUPC.cbl:L1461-L1474} without ever having had a
     * change validated, and a client that could name its own carried image would be able to choose the
     * before-image the change comparison at line 1498 runs against. This collaborator is what makes both
     * impossible: it seals the settled state and the fetched image into one opaque token on the way out
     * and refuses anything but its own token on the way back in.
     */
    private final CardConcurrencyTokenService cardConcurrencyTokenService;

    /**
     * Evaluates the constraint group that only the confirming submission is subject to.
     *
     * <p>The framework's own {@code @Valid} evaluates the default group and nothing else, so a constraint
     * scoped to a group is inert until some caller names that group. One constraint on the update contract
     * is scoped that way - the account identifier must be <em>absent</em> on the turn that writes, because
     * the legacy screen has it protected in that state and the write takes the owning account from the
     * carried image rather than from the map - and the turn it applies to is not knowable from the body
     * alone: it is knowable only from the verified conversation state. Hence a second, explicit evaluation
     * here rather than a second annotation there.
     */
    private final Validator validator;

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
     * @param cardConcurrencyTokenService the sealer and opener of the update screen's conversation state
     * @param validator the evaluator of the confirming submission's own constraint group
     * @param meterRegistry the metrics registry the turn timers are registered against
     */
    public CardController(final CardListService cardListService,
                          final CardDetailService cardDetailService,
                          final CardUpdateService cardUpdateService,
                          final ScreenStateAdapter screenStateAdapter,
                          final CardConcurrencyTokenService cardConcurrencyTokenService,
                          final Validator validator,
                          final MeterRegistry meterRegistry) {
        this.cardListService = Objects.requireNonNull(cardListService,
                "cardListService must not be null");
        this.cardDetailService = Objects.requireNonNull(cardDetailService,
                "cardDetailService must not be null");
        this.cardUpdateService = Objects.requireNonNull(cardUpdateService,
                "cardUpdateService must not be null");
        this.screenStateAdapter = Objects.requireNonNull(screenStateAdapter,
                "screenStateAdapter must not be null");
        this.cardConcurrencyTokenService = Objects.requireNonNull(cardConcurrencyTokenService,
                "cardConcurrencyTokenService must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
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
                    + "is read from the body. TO MARK A ROW, echo rowSnapshotToken exactly as the "
                    + "previous response returned it: the legacy program reads the marked row out of "
                    + "the seven displayed rows it carries in its own communication area, and this "
                    + "opaque snapshot is that area's equivalent. Without it a marked row cannot be "
                    + "resolved and the turn answers 'INVALID ACTION CODE' and re-presents the page.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The turn completed. Carries the page of rows in presentation order, the "
                        + "browse cursors, any screen message, the positional selection indicator, the "
                        + "opaque row snapshot to echo with a marked selection, and the route the client "
                        + "calls next."),
        @ApiResponse(responseCode = "400",
                description = "The request carried a value that could not have occupied its legacy "
                        + "screen field."),
        @ApiResponse(responseCode = "401", description = "No authenticated caller."),
        @ApiResponse(responseCode = "403",
                description = "The authenticated principal is not an approved online-data operator.")})
    public ResponseEntity<CardListResponse> listCards(
            @Valid @RequestBody final CardListRequest request,
            final Authentication authentication) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);
        String outcome = OUTCOME_FAILED;
        try {
            final CardListService.CardListScreenInput input = new CardListService.CardListScreenInput(
                    terminalIdentifierFor(request.keyAction()),
                    this.screenStateAdapter.toInputState(screenWorkAreaOf(request)),
                    request.selectionsInRowOrder(),
                    this.screenStateAdapter.toCursorRequest(request.pageMetadata()),
                    retainedPageNumber(request.pageMetadata()),
                    request.lastPageAlreadyShown(),
                    retainedNextPageFlag(request.pageMetadata()),
                    request.rowSnapshotToken(),
                    this.screenStateAdapter.toNavigationState(request.navigationContext(),
                            authentication));

            final CardListService.CardListResult result = this.cardListService.processCardList(input);
            final CardListResponse response = toListResponse(request, result, authentication);

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
     * <p><strong>A read that is nonetheless a {@code POST} with an optional body.</strong> It reads and
     * changes nothing, so it is idempotent in effect - but it is not reached by the idempotent method and
     * it does carry a body: the turn's inputs are the two optional filters, the attention key and the
     * echoed navigation record, and that record is a structured object rather than a scalar. Carrying it
     * as a body is what keeps its component names distinct from the filter names, and every other screen
     * turn on this surface is posted for the same reason, so the one contract is uniform. The body is
     * declared optional and an absent one reads as the empty request, which is how a first arrival with
     * nothing typed presents. <strong>Both business keys are optional and neither is a path
     * segment.</strong> That is not a stylistic choice: the
     * screen distinguishes an absent account from an absent card from both absent, and answers each with
     * its own message, so a mandatory path segment would make two of those three outcomes unreachable
     * and would silently drop a behaviour the legacy screen has.
     *
     * <p>The echoed navigation record is a component of the submitted body, so its own component names
     * stay distinct from the two filter names and the carried account and card - what the previous screen
     * selected - can never be confused with the account and card the operator typed. {@link
     * CardDetailService} needs both pairs and reads them separately.
     *
     * <p>An arrival here from the card list is response metadata on that screen's turn and nothing more.
     * No request is forwarded, and this operation is reached by the client calling it.
     *
     * @param request the submitted screen: the eleven-character account filter, the sixteen-character
     *        card filter, the attention key and the echoed navigation state, any of which may be absent;
     *        an entirely absent body reads as the empty request, which is how a first arrival presents
     * @param authentication the authenticated caller, whose approved-operator role the route already
     *        required
     * @return the screen the turn produces, carrying the card when one was found and the screen message
     *         in every case
     */
    @PostMapping(path = CARD_DETAIL_PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "View one card's details",
            description = "One turn of legacy transaction " + TRANSACTION_CARD_DETAIL + ". A read that "
                    + "changes nothing, posted with an optional body: the body is how the echoed "
                    + "navigation record keeps its component names distinct from the two search keys, "
                    + "and an absent body reads as the empty request. Both search keys are optional, "
                    + "because the legacy screen answers an absent account, an absent card and no input "
                    + "at all with three different messages. Answers 200 for every outcome the legacy "
                    + "screen could compose, including not finding the card: the outcome is read from "
                    + "the body.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The turn completed. Carries the card's details when one was found, the "
                        + "screen message, the field the cursor returns to and the route the client "
                        + "calls next."),
        @ApiResponse(responseCode = "400",
                description = "The body carried a value that could not have occupied its legacy screen "
                        + "field."),
        @ApiResponse(responseCode = "401", description = "No authenticated caller."),
        @ApiResponse(responseCode = "403",
                description = "The authenticated principal is not an approved online-data operator.")})
    public ResponseEntity<CardDetailResponse> viewCardDetail(
            @Valid @RequestBody(required = false) final CardDetailRequest request,
            final Authentication authentication) {
        final CardDetailRequest bounded = request == null ? CardDetailRequest.empty() : request;
        return viewCardDetail(bounded.accountIdFilter(), bounded.cardNumberFilter(),
                bounded.keyAction(), bounded.navigationContext(), authentication);
    }

    /**
     * The turn itself, shared by the mapped handler above and exercised directly by its unit tests.
     *
     * <p>Not a request handler: it carries no mapping, so it publishes no operation and binds no request.
     * It carries no copy of the operation description and no parameter-binding annotations, because such a
     * copy would be inert - the framework maps only annotated <em>mapped</em> methods and the interface
     * description is generated only from those - so the richer of the two descriptions would be the one
     * that was never published. The description sits on the mapped handler and this method carries none.
     *
     * @param  accountIdFilter   the account the operator typed, or {@code null}
     * @param  cardNumberFilter  the card the operator typed, or {@code null}
     * @param  keyAction         the attention key, or {@code null}
     * @param  navigationContext the echoed navigation state
     * @param  authentication    the authenticated caller
     * @return the screen the turn produces
     */
    public ResponseEntity<CardDetailResponse> viewCardDetail(final String accountIdFilter,
            final String cardNumberFilter, final KeyAction keyAction,
            final NavigationContext navigationContext, final Authentication authentication) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);
        String outcome = OUTCOME_FAILED;
        try {
            final CardDetailService.CardDetailScreenInput input =
                    new CardDetailService.CardDetailScreenInput(
                            accountIdFilter,
                            cardNumberFilter,
                            terminalIdentifierFor(keyAction),
                            this.screenStateAdapter.toNavigationState(navigationContext,
                                    authentication));

            final CardDetailService.CardDetailResult result =
                    this.cardDetailService.processCardDetail(input);
            final CardDetailResponse response = toDetailResponse(result, authentication);

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
     * <p><strong>The state that travels is authenticated, not trusted.</strong> It travels sealed, in the
     * one opaque token this contract carries, and is opened here before the turn begins. Three things
     * follow from the opened state and from nothing a client typed. The change action the service is given
     * is the one the previous turn settled on, so a client cannot present itself as already past the
     * validate-and-confirm stages and reach the rewrite at {@code app/cbl/COCRDUPC.cbl:L1461-L1474}
     * without a change ever having been validated. The carried image the service is given is the one the
     * previous turn fetched, so a client cannot choose the before-image that the change comparison at line
     * 1498 runs against - which is the comparison that decides whether the record moved underneath the
     * operator. And the constraint group that applies is chosen from that verified action rather than from
     * the body, because the body cannot say truthfully which state it is in.
     *
     * <p>An absent token is the genuine first turn and is not an error: the legacy reaches the same state
     * through a zero-length communication area at line 388. A present token that cannot be authenticated
     * is refused, rather than being treated as a first turn - treating it as one would let a client
     * discard a state it did not like by corrupting a byte of it.
     *
     * <p>Two values are protected carry-through rather than input and are treated as such here. The
     * expiry day is hidden and unwritable on every state of the legacy screen, so the request contract
     * declines to bind it and this boundary supplies it from the verified carried image - the same value
     * the legacy terminal transmitted back, because the send paragraph writes the fetched day into that
     * protected field at line 1123. The account identifier is protected once a card has been fetched, and
     * the confirming submission's own constraint group requires its absence. Neither gets an editing
     * surface here.
     *
     * @param request the transmitted screen: the two business keys, the three editable values, the
     *        attention key, the echoed navigation state and the sealed conversation state
     * @param authentication the established principal, whose identity replaces any the client echoed
     * @return the screen the turn produces, carrying the values to redisplay, the screen message, any
     *         field-level error state, the newly sealed conversation state and the route the client calls
     *         next
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
                description = "The authenticated principal is not an approved online-data operator."),
        @ApiResponse(responseCode = "409",
                description = "The echoed conversation state was not the one this server sealed. Not a "
                        + "legacy screen outcome: the legacy held this state in server storage the "
                        + "terminal could not reach, so there is no screen to reproduce. A write that "
                        + "fails because the record moved is reported on the 200 screen instead, which "
                        + "is what the legacy does.")})
    public ResponseEntity<CardUpdateResponse> updateCard(
            @Valid @RequestBody final CardUpdateRequest request,
            final Authentication authentication) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);
        String outcome = OUTCOME_FAILED;
        try {
            // The conversation state is opened before anything else, because everything after this depends
            // on it: which state machine arm the turn may reach, which before-image the change comparison
            // runs against, which constraint group applies, and what the protected expiry day holds. An
            // absent token opens as the genuine first turn; a present one that cannot be authenticated is
            // refused rather than downgraded to a first turn, because downgrading would let a client
            // discard a state it did not like.
            final CardConcurrencyTokenService.Continuation continuation =
                    this.cardConcurrencyTokenService.openContinuation(request.concurrencyToken());
            enforceConfirmingSubmissionContract(request, continuation);

            final CardUpdateService.CardUpdateScreenInput input =
                    new CardUpdateService.CardUpdateScreenInput(
                            request.accountId(),
                            request.cardNumber(),
                            request.embossedName(),
                            request.activeStatus(),
                            request.expiryMonth(),
                            request.expiryYear(),
                            continuation.carriedImage().expiryDay(),
                            terminalIdentifierFor(request.keyAction()),
                            carriedUpdateState(request.navigationContext(), authentication),
                            continuation.changeAction(),
                            continuation.carriedImage());

            final CardUpdateService.CardUpdateResult result =
                    this.cardUpdateService.processCardUpdate(input);
            final CardUpdateResponse response = toUpdateResponse(request, result, authentication,
                    this.cardConcurrencyTokenService.sealContinuation(
                            result.changeAction(), result.carriedImage()));

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
     * all-blank and treat the two identically, so handing them the empty carrier in place of nothing
     * changes no outcome. The update screen does not: its reset condition is the analogue of {@code
     * EIBCALEN IS EQUAL TO 0}, which asks whether a communication area arrived at all, and it answers
     * differently for a turn that echoed nothing than for one that echoed an all-blank record. Filling the
     * absence in would turn the first turn of a conversation into a continuation of one, losing the
     * first-entry gate the reset raises and with it the fetch the screen performs on entry.
     *
     * @param context the record the client echoed, which may be {@code null}
     * @param authentication the established identity the echoed state is reconciled against
     * @return the service-owned state, or {@code null} when the client echoed no record at all
     */
    private ScreenNavigationState carriedUpdateState(final NavigationContext context,
                                                     final Authentication authentication) {
        return (context == null)
                ? null
                : this.screenStateAdapter.toNavigationState(context, authentication);
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
     * <p>The heading is taken whole from the turn and nothing in it is invented here. The legacy screen
     * builds all six values in one paragraph - {@code 1100-SCREEN-INIT} at
     * {@code app/cbl/COCRDLIC.cbl:L642-L674} moves the two title lines and the two literal identifiers,
     * then formats the date and the time from a single clock reading - so the turn that reads the clock
     * owns the whole heading. Sourcing any part of it from constants at this boundary would give the two
     * clock values a different reading than the rest of the turn, and leaving them absent would drop
     * output the legacy screen unconditionally produces.
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
            final CardListService.CardListResult result, final Authentication authentication) {
        final PageMetadata paging = this.screenStateAdapter.toPageMetadata(result.pageMetadata());
        final CardListService.ScreenHeader header = result.header();
        return new CardListResponse(
                header.transactionName(),
                header.title01(),
                header.currentDate(),
                header.programName(),
                header.title02(),
                header.currentTime(),
                (paging == null) ? null : paging.displayedPageNumber(),
                request.accountIdFilter(),
                request.cardNumberFilter(),
                toResponseRows(result.rows()),
                alignedSelectionErrorFlags(result),
                result.infoMessage(),
                result.errorMessage(),
                result.errorFlag(),
                paging,
                result.lastPageAlreadyShown(),
                toResponseFieldErrors(result.fieldErrors()),
                result.focusField(),
                routeValueOf(result.route()),
                this.screenStateAdapter.toNavigationContext(result.navigationContext(), authentication),
                result.rowSnapshotToken());
    }

    /**
     * Projects the settled rows onto the published row contract, in the order given.
     *
     * <p>Component for component, with no transformation of any kind: the account identifier and the card
     * number are carried at full width because a shortened business key identifies nothing, and the
     * status code is carried raw because the legacy takes it straight from the record and a code outside
     * the known pair must flow through untouched rather than being rejected or absorbed. No attribute,
     * colour or coordinate exists to publish.
     *
     * <p>The row's screen slot <em>is</em> published, because the list order does not carry it. The
     * backward browse fills the screen's slots downward from the last, so a partial backward page leaves
     * the low slots empty and presents its rows at the bottom; the settled list holds only the populated
     * rows, so on that page the list position and the slot differ. The selection field a client sends back
     * is addressed by slot, so a client that inferred the slot from the position would mark its action
     * against a different card than the operator chose.
     *
     * @param rows the settled rows in presentation order
     * @return the published rows, unmodifiable and in the same order
     */
    private static List<CardListResponse.CardListRow> toResponseRows(
            final List<CardListService.CardListRow> rows) {
        final List<CardListResponse.CardListRow> projected = new ArrayList<>(rows.size());
        for (final CardListService.CardListRow row : rows) {
            projected.add(new CardListResponse.CardListRow(
                    row.screenSlot(),
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
            final CardDetailService.CardDetailResult result, final Authentication authentication) {
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
                toResponseFieldErrors(result.fieldErrors()),
                result.focusField(),
                routeValueOf(result.route()),
                this.screenStateAdapter.toNavigationContext(result.navigationContext(), authentication));
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
            final CardUpdateService.CardUpdateResult result, final Authentication authentication,
            final String sealedContinuation) {
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
                this.screenStateAdapter.toNavigationContext(result.navigationContext(), authentication),
                sealedContinuation);
    }

    /**
     * Evaluates the constraint group that only the confirming submission is subject to.
     *
     * <p>The confirming submission is the single combination the legacy screen writes from: the state
     * machine standing at changes-accepted-not-yet-confirmed with the save key pressed, which is clause
     * five of the dispatch at {@code app/cbl/COCRDUPC.cbl:L988-L1001}. In that state the attribute branch
     * at line 1193 leaves the account identifier and the card number protected, so the terminal offered the
     * operator nothing to type into them, and the rewrite at lines 1461 to 1474 takes the owning account
     * from the carried work area rather than from the map.
     *
     * <p>Which is why the group cannot be evaluated by the framework's {@code @Valid}: that evaluates the
     * default group, and the turn this group describes is not identifiable from the body. It is
     * identifiable only from the <em>verified</em> conversation state, so the state is opened first and the
     * group is evaluated here, against the action the previous turn settled on rather than against
     * anything a client asserted about its own position.
     *
     * <p>A violation is raised rather than absorbed into a screen. It is not a state the legacy screen can
     * reach - the terminal physically could not transmit a protected field - so there is no legacy outcome
     * to reproduce, and the honest answer is that the submission could not have come from this screen.
     *
     * @param request the transmitted screen
     * @param continuation the opened, authenticated conversation state
     * @throws ConstraintViolationException if the confirming submission supplied a value that state
     *                                      protects
     */
    private void enforceConfirmingSubmissionContract(final CardUpdateRequest request,
            final CardConcurrencyTokenService.Continuation continuation) {
        if (!continuation.changeAction().changesOkNotConfirmed()
                || request.keyAction() != KeyAction.PFK05) {
            return;
        }
        final Set<ConstraintViolation<CardUpdateRequest>> violations =
                this.validator.validate(request, CardUpdateRequest.ConfirmSave.class);
        if (!violations.isEmpty()) {
            LOG.debug("Confirming card-update submission refused: violationCount={}", violations.size());
            throw new ConstraintViolationException(violations);
        }
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
     * Reads the retained page number out of the paging state the caller echoed.
     *
     * <p>Read rather than reset. {@code WS-CA-SCREEN-NUM} is a communication-area field that the program
     * hands back on return and reads on the next turn, incrementing it at
     * {@code app/cbl/COCRDLIC.cbl:L1556} and decrementing it at line 1567 rather than recomputing it, so a
     * boundary that supplied a constant here would put every turn back on page one and would make the
     * displayed indicator disagree with the page the browse actually walked to.
     *
     * @param carried the paging state the caller echoed, which is {@code null} on a first entry
     * @return the retained page number, or zero when the caller carried none
     */
    private static int retainedPageNumber(final PageMetadata.PageCursorRequest carried) {
        return (carried == null) ? NO_RETAINED_PAGE_NUMBER : carried.retainedPageNumber();
    }

    /**
     * Reads the retained further-pages flag out of the paging state the caller echoed.
     *
     * <p>Read for the same reason: {@code WS-CA-NEXT-PAGE-IND} is a communication-area field, and the
     * forward-paging arm at {@code app/cbl/COCRDLIC.cbl:L486} tests it <em>before</em> the browse
     * recomputes it. A boundary that supplied a cleared flag would make the forward key refuse to advance
     * on the one turn the operator pressed it.
     *
     * @param carried the paging state the caller echoed, which is {@code null} on a first entry
     * @return the retained flag, or {@code false} when the caller carried none
     */
    private static boolean retainedNextPageFlag(final PageMetadata.PageCursorRequest carried) {
        return carried != null && carried.nextPageIndicated();
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
     * Records the elapsed time of one turn under the timer naming its screen.
     *
     * <p>Every turn is recorded, including one that raised. Each caller invokes this from its {@code
     * finally} arm, so an exception propagating out of the boundary still stops the sample, carrying the
     * failed label the caller seeded before the attempt rather than any destination.
     *
     * @param sample the timing sample started at the head of the turn
     * @param metricName the timer naming the screen whose turn this was
     * @param description the timer's description
     * @param outcome the already-resolved tag value: a route from the navigation vocabulary, the
     *     unresolved stand-in for a turn that chose no destination, or the failed label for a turn that
     *     raised
     */
    private void recordTurn(final Timer.Sample sample, final String metricName,
            final String description, final String outcome) {
        sample.stop(Timer.builder(metricName)
                .description(description)
                .tag(TAG_OUTCOME, outcome)
                .register(this.meterRegistry));
    }
}
