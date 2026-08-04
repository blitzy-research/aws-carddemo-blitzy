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

import com.carddemo.api.dto.MenuResponse;
import com.carddemo.api.dto.NavigationContext;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.service.ConversationState;
import com.carddemo.service.MenuService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.Collection;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface of the two menu transactions: {@code CM00}, the menu a regular operator reaches, and
 * {@code CA00}, the menu an administrator reaches instead.
 *
 * <p><strong>What this class does and does not do.</strong> Each endpoint binds the three things a
 * legacy menu turn was driven by - the echoed communication area, the attention key, and the
 * two-character option field - hands them to {@link MenuService} once, projects the answer through
 * {@link MenuResponseAdapter}, and records how long the turn took. It holds no rule of its own. The
 * option catalogues, the blank-to-zero normalisation of the option field, the range test, the
 * administrator-only gate, the suppressed-dispatch text and every message literal live in the service
 * tier and are reached from here by delegation, never reproduced. That is what keeps this class from
 * becoming a second, divergent copy of the menu rules, and it is what lets all of that behaviour be
 * exercised without a servlet.
 *
 * <p><strong>Why there are two endpoints and not one.</strong> The estate defines two transactions
 * bound to two programs reading two catalogues, and the resource definitions gate them differently:
 * the administrative menu is administrator-only while the main menu admits any signed-on operator.
 * Collapsing them into one route with a mode parameter would put the entitlement decision inside a
 * request body, where the filter chain cannot see it. Two addresses keep the decision where the chain
 * makes it.
 *
 * <p><strong>Why the administrative address is written out here.</strong> The filter chain gates one
 * prefix and everything beneath it, so this route has to sit beneath that prefix or it would fall
 * through to the catch-all rule and admit any signed-on caller. The prefix is published by the
 * security configuration as the authority a route binds against, but this module's layering forbids
 * the API layer from importing the configuration layer, and that direction is asserted rather than
 * merely stated. The address is therefore spelled here and the agreement between the two is pinned by
 * {@code MenuControllerTest}, which is free to read both. A literal guarded by an assertion is the
 * honest arrangement; an unguarded literal would not be.
 *
 * <p><strong>Why a turn is a {@code POST} that carries no request record.</strong> A turn has to carry
 * the navigation state the client echoes back, which is the whole sixteen-field communication area, so
 * it travels as the request body. The two remaining inputs are the attention key and the option field,
 * and they travel as query parameters rather than as components of a new request record: the delivered
 * transport contract has no menu request type, and inventing one would add a shape to a contract that
 * is frozen against the symbolic maps. The body is optional because the legacy programs have a defined
 * behaviour for a turn that arrives with no communication area at all, and that behaviour has to stay
 * reachable.
 *
 * <p><strong>Why every outcome answers {@code 200}.</strong> A rejected option, a refused
 * administrator-only option and a suppressed dispatch are all screens the legacy program successfully
 * composed and sent; the transaction completed in each case. The outcome is read from the body exactly
 * as an operator read it from the screen. A request the screen could not have transmitted is a
 * different matter and is rejected by declarative validation before the handler runs, which the shared
 * failure surface turns into a {@code 400}; an absent or insufficient credential is answered by the
 * filter chain as {@code 401} or {@code 403} and never reaches here.
 *
 * <p><strong>The identity handed downwards is the authenticated one.</strong> The navigation record a
 * client echoes carries a user identifier and a user type, and neither may be trusted: the service
 * takes the signed-on type as its own argument precisely so that the administrator-only gate cannot be
 * talked out of by an echoed value, and the response adapter reconciles the record it publishes
 * against the authenticated principal. This class reads that principal and passes it on; it makes no
 * access decision out of it.
 *
 * <p>Stateless apart from its injected collaborators, holding no mutable field, so the singleton is
 * safe for unsynchronised concurrent use.
 *
 * <p>Provenance: {@code app/cbl/COMEN01C.cbl} and {@code app/cbl/COADM01C.cbl}, their symbolic maps
 * {@code app/cpy-bms/COMEN01.CPY} and {@code app/cpy-bms/COADM01.CPY}, their option tables
 * {@code app/cpy/COMEN02Y.cpy} and {@code app/cpy/COADM02Y.cpy}, the communication area
 * {@code app/cpy/COCOM01Y.cpy}, and the resource definitions in {@code app/csd/CARDDEMO.CSD} whose
 * transaction entries bind {@code CM00} and {@code CA00} to those two programs - all read as read-only
 * reference at commit SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is transcribed and no
 * screen attribute, length or position byte is published.
 *
 * @since 1.0.0
 */
@RestController
public final class MenuController {

    /**
     * Address of one turn of the main menu, legacy transaction {@code CM00}.
     *
     * <p>Deliberately outside the administrative prefix: the estate lets every signed-on operator
     * reach this menu, whatever type the credential carries. It therefore carries no dedicated
     * authorization rule and is answered by the chain's closing rule, which requires a credential.
     */
    public static final String USER_MENU_PATH = "/api/menu";

    /**
     * Address of one turn of the administrative menu, legacy transaction {@code CA00}.
     *
     * <p>Beneath the administrative prefix the filter chain gates, which is what makes this route
     * administrator-only. The gate is the chain's, not this class's: nothing here inspects a role.
     * {@code MenuControllerTest} asserts that this address begins with the prefix the security
     * configuration publishes, so the two cannot drift apart unnoticed.
     */
    public static final String ADMIN_MENU_PATH = "/api/admin/menu";

    /**
     * Name of the query parameter carrying the operator's option entry.
     *
     * <p>The screen field it stands for is two characters wide, right-justified and zero-filled. No
     * width bound is declared on the parameter, because the service reproduces the terminal's own
     * truncation and blank-to-zero normalisation; rejecting a wider value here would refuse input the
     * legacy screen accepted.
     */
    public static final String OPTION_PARAMETER = "option";

    /**
     * Name of the query parameter carrying the attention key the operator pressed.
     *
     * <p>Optional, because the legacy key evaluation has an any-other-key alternative that already
     * covers a turn arriving without one.
     */
    public static final String KEY_ACTION_PARAMETER = "keyAction";

    /** Timer name for one menu turn, following the naming the sign-on surface established. */
    private static final String METRIC_MENU_TURN = "carddemo.online.menu.turn";

    /** Tag naming which of the two menu transactions the turn belongs to. */
    private static final String TAG_TRANSACTION = "transaction";

    /** Tag naming how the turn's message is to be presented, or that it carries none. */
    private static final String TAG_OUTCOME = "outcome";

    /** Tag value for a turn that reports nothing, so the tag is never absent and never {@code null}. */
    private static final String OUTCOME_NONE = "NONE";

    /**
     * Prefix the framework's default authority naming puts in front of a role.
     *
     * <p>The chain grants one authority per credential, spelled as this prefix followed by the name of
     * the user type the credential carries. Reading the type back out is the inverse of that single
     * mapping and nothing more; it is not an access decision, and the chain has already made every
     * access decision by the time a handler here runs.
     */
    private static final String ROLE_AUTHORITY_PREFIX = "ROLE_";

    /** The two menu transactions. */
    private final MenuService menuService;

    /** The single conversion between the echoed navigation record and the carried state. */
    private final ConversationStateAdapter conversationStateAdapter;

    /** The single projection of a menu turn onto the published contract. */
    private final MenuResponseAdapter menuResponseAdapter;

    /** Registry the turn timer is registered against. */
    private final MeterRegistry meterRegistry;

    /**
     * Creates the controller over its collaborators.
     *
     * @param menuService the two menu transactions
     * @param conversationStateAdapter the navigation-state boundary conversion
     * @param menuResponseAdapter the contract projection
     * @param meterRegistry the metrics registry
     */
    public MenuController(final MenuService menuService,
                          final ConversationStateAdapter conversationStateAdapter,
                          final MenuResponseAdapter menuResponseAdapter,
                          final MeterRegistry meterRegistry) {
        this.menuService = Objects.requireNonNull(menuService, "menuService must not be null");
        this.conversationStateAdapter = Objects.requireNonNull(conversationStateAdapter,
                "conversationStateAdapter must not be null");
        this.menuResponseAdapter = Objects.requireNonNull(menuResponseAdapter,
                "menuResponseAdapter must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    /**
     * Serves one turn of the main menu, legacy transaction {@code CM00}.
     *
     * <p>Reachable by any signed-on operator, administrators included, because the estate's routing to
     * this menu is unconditional for every user type that is not the administrative one and no rule
     * excludes the administrative one from reaching it.
     *
     * @param navigationContext the navigation state the client echoed, absent on a turn that carries
     *                          none
     * @param keyAction the attention key the operator pressed, absent when the client reports none
     * @param option the operator's option entry, absent when nothing was typed
     * @param authentication the established identity, supplied by the framework
     * @return the screen the turn produces: its option rows, its header, its message and the route and
     *         navigation state for the client's next call
     */
    @PostMapping(path = USER_MENU_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Take one turn of the CardDemo main menu",
            description = "One turn of legacy transaction CM00. Answers 200 for every outcome the "
                    + "legacy screen could compose, a rejected option included: the outcome is read "
                    + "from the body, which also carries the route to call next and the navigation "
                    + "state to echo back on that call.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The turn completed, carrying the menu rows, the screen header, any "
                        + "message the turn produced, the next route and the navigation state."),
        @ApiResponse(responseCode = "400",
                description = "The echoed navigation state exceeded the widths the communication "
                        + "area declares, or the attention key was not one of its declared values."),
        @ApiResponse(responseCode = "401",
                description = "No verifiable credential was presented.")})
    public MenuResponse userMenu(
            @Valid @RequestBody(required = false) final NavigationContext navigationContext,
            @RequestParam(name = KEY_ACTION_PARAMETER, required = false) final KeyAction keyAction,
            @RequestParam(name = OPTION_PARAMETER, required = false) final String option,
            final Authentication authentication) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);

        final ConversationState inbound =
                this.conversationStateAdapter.toConversationState(navigationContext);
        final String signedOnUserId = signedOnUserId(authentication);
        final UserType signedOnUserType = signedOnUserType(authentication);

        final MenuService.MenuScreen screen =
                this.menuService.userMenu(inbound, keyAction, option, signedOnUserType);
        final MenuResponse body = this.menuResponseAdapter.toResponse(screen, navigationContext,
                signedOnUserId, signedOnUserType);

        recordTurn(sample, MenuService.USER_MENU_TRANSACTION_ID, screen.severity());
        return body;
    }

    /**
     * Serves one turn of the administrative menu, legacy transaction {@code CA00}.
     *
     * <p>Reachable only by a credential carrying the administrative authority, and that is enforced by
     * the filter chain over the prefix this address sits beneath rather than by anything below. The
     * transaction itself has no administrator-only option gate of its own - the whole menu is the gate
     * - which is why the delegate takes no signed-on type.
     *
     * @param navigationContext the navigation state the client echoed, absent on a turn that carries
     *                          none
     * @param keyAction the attention key the operator pressed, absent when the client reports none
     * @param option the operator's option entry, absent when nothing was typed
     * @param authentication the established identity, supplied by the framework
     * @return the screen the turn produces: its option rows, its header, its message and the route and
     *         navigation state for the client's next call
     */
    @PostMapping(path = ADMIN_MENU_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Take one turn of the CardDemo administrative menu",
            description = "One turn of legacy transaction CA00, reachable only with the "
                    + "administrative authority. Answers 200 for every outcome the legacy screen "
                    + "could compose, a rejected option included: the outcome is read from the body, "
                    + "which also carries the route to call next and the navigation state to echo "
                    + "back on that call.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The turn completed, carrying the menu rows, the screen header, any "
                        + "message the turn produced, the next route and the navigation state."),
        @ApiResponse(responseCode = "400",
                description = "The echoed navigation state exceeded the widths the communication "
                        + "area declares, or the attention key was not one of its declared values."),
        @ApiResponse(responseCode = "401",
                description = "No verifiable credential was presented."),
        @ApiResponse(responseCode = "403",
                description = "The credential presented does not carry the administrative "
                        + "authority.")})
    public MenuResponse adminMenu(
            @Valid @RequestBody(required = false) final NavigationContext navigationContext,
            @RequestParam(name = KEY_ACTION_PARAMETER, required = false) final KeyAction keyAction,
            @RequestParam(name = OPTION_PARAMETER, required = false) final String option,
            final Authentication authentication) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);

        final ConversationState inbound =
                this.conversationStateAdapter.toConversationState(navigationContext);
        final String signedOnUserId = signedOnUserId(authentication);
        final UserType signedOnUserType = signedOnUserType(authentication);

        final MenuService.MenuScreen screen =
                this.menuService.adminMenu(inbound, keyAction, option);
        final MenuResponse body = this.menuResponseAdapter.toResponse(screen, navigationContext,
                signedOnUserId, signedOnUserType);

        recordTurn(sample, MenuService.ADMIN_MENU_TRANSACTION_ID, screen.severity());
        return body;
    }

    /**
     * Records the elapsed time of one turn, tagged by the transaction it belongs to and by what the
     * turn had to report.
     *
     * <p>Two tags rather than one aggregate, because the operationally interesting question is the
     * shape of the mix: a rise in the error outcome on the main menu means operators are fumbling the
     * option field, while the same rise on the administrative menu means something else entirely. Both
     * tag values are drawn from bounded, enumerated vocabularies - two transactions and three
     * presentation outcomes - so neither can become a high-cardinality label, and neither is a
     * measurement of anything this module asserts a service level for.
     *
     * <p>A turn that fails is deliberately not timed: the failure surface reports it, and stopping the
     * sample on a path that produced no screen would mix two different measurements into one series.
     *
     * @param sample the timing sample started at the head of the turn
     * @param transactionId the four-character transaction identifier of the menu served
     * @param severity how the turn's message is to be presented, or {@code null} when it carries none
     */
    private void recordTurn(final Timer.Sample sample, final String transactionId,
                            final MenuService.MessageSeverity severity) {
        sample.stop(Timer.builder(METRIC_MENU_TURN)
                .description("Elapsed time of one CardDemo menu turn, transactions CM00 and CA00")
                .tag(TAG_TRANSACTION, transactionId)
                .tag(TAG_OUTCOME, (severity == null) ? OUTCOME_NONE : severity.name())
                .register(this.meterRegistry));
    }

    /**
     * Reads the identifier of the established identity.
     *
     * <p>Never the identifier the client echoed. The response adapter reconciles the navigation record
     * it publishes against this value, which is what stops a client-supplied identifier surviving a
     * turn and coming back looking as though the server had asserted it.
     *
     * @param authentication the established identity, which may be {@code null} on a route the chain
     *                       does not authenticate
     * @return the identifier, or {@code null} when no identity is established
     */
    private static String signedOnUserId(final Authentication authentication) {
        return (authentication == null) ? null : authentication.getName();
    }

    /**
     * Reads the user type of the established identity out of the authority the chain granted it.
     *
     * <p>This is a read of an identity that has already been established, not a check of it: no branch
     * here admits or refuses anybody, and the chain has already refused every caller that had no
     * business reaching the handler. The value is needed because the menu service takes the signed-on
     * type as its own argument - the administrator-only option gate must not be reachable through an
     * echoed value - and because the response adapter reconciles the published navigation record
     * against it.
     *
     * <p>Resolution is the inverse of the single mapping the chain applies when it grants the
     * authority: the framework's role prefix followed by the name of the user type. An identity
     * carrying neither declared authority yields {@code null}, which the service treats as no type at
     * all rather than as a type it must guess, exactly as the legacy program treated an unrecognised
     * type code.
     *
     * @param authentication the established identity, which may be {@code null} on a route the chain
     *                       does not authenticate
     * @return the user type the granted authority names, or {@code null} when none does
     */
    private static UserType signedOnUserType(final Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        final Collection<? extends GrantedAuthority> granted = authentication.getAuthorities();
        if (granted == null) {
            return null;
        }
        for (final GrantedAuthority authority : granted) {
            for (final UserType candidate : UserType.values()) {
                if ((ROLE_AUTHORITY_PREFIX + candidate.name()).equals(authority.getAuthority())) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
