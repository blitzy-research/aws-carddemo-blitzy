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

import com.carddemo.api.dto.AccountUpdateRequest;
import com.carddemo.api.dto.AccountUpdateResponse;
import com.carddemo.api.dto.AccountViewResponse;
import com.carddemo.api.dto.NavigationContext;
import com.carddemo.api.dto.ScreenWorkArea;
import com.carddemo.domain.enums.UserType;
import com.carddemo.service.AccountUpdateService;
import com.carddemo.service.AccountViewService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.Collection;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface of the two account-servicing screens: transaction {@code CAVW}, the account view, and
 * transaction {@code CAUP}, the account update.
 *
 * <p><strong>What this class does and does not do.</strong> It binds one screen turn out of the HTTP
 * request, hands that turn to the service that owns the transaction, publishes the answer on the
 * declared response contract and records how long the turn took. It holds no rule of its own: no
 * presence test, no range test, no lookup, no comparison against a previously fetched image, no lock,
 * no write, no field decoration and no message text. Every one of those lives in
 * {@link AccountViewService} or {@link AccountUpdateService}, which is what lets both be exercised
 * without a servlet and what keeps this class from becoming a second, divergent copy of the account
 * rules.
 *
 * <p><strong>Exactly two surfaces, and no third.</strong> The resource-definition file registers
 * eighteen transactions; two of them are account transactions and both are bound here. Nothing else is
 * published from this class - in particular the developer transaction {@code CDV1}, whose bound program
 * has no source member anywhere in the estate, gets no route, because a route that answers nothing is
 * worse than no route at all.
 *
 * <p><strong>Why both routes share one operator gate.</strong> The resource definition makes both account
 * transactions reachable by either declared CardDemo user type, so neither is administrator-only. Both
 * paths sit under one controller-owned prefix which the filter chain names as an online-data operator
 * surface. That preserves both legacy user types and refuses an unrelated authenticated principal without
 * inventing the account ownership relation the user-security record lacks.
 *
 * <p><strong>Route-level authentication is not the whole of the access control, and this class carries
 * the rest of it.</strong> The account identifier is a request field rather than a property of the
 * caller, so a signed-on caller may name any account - which is what the estate allowed and what the
 * route must keep allowing. What it may then <em>see</em> is a separate question, because four of the
 * customer values on both screens are regulated. Both turns therefore pass their regulated components
 * through {@link AccountProtectedDataAdapter}: an administrator reveals, and every other caller receives
 * masks at the widths the revealed values occupy. The view turn has always done so; the update turn now
 * does too, through {@code gatedForUpdate}, and the two use the same gate so they cannot come to disagree
 * about what a masked screen looks like.
 *
 * <p><strong>Why the turn is submitted rather than fetched, even for the view.</strong> Both turns need
 * the communication area the client echoed back - the record that carries the enter-or-re-enter flag on
 * which the whole dispatch turns - and that is a structured document which a request with no body
 * cannot carry. It also carries a customer identifier and three name parts, none of which belongs in a
 * request line that proxies and access logs retain. Both turns are nonetheless idempotent in effect:
 * neither creates a resource, and re-submitting the same turn composes the same screen, which is
 * exactly the property the pseudo-conversational original had.
 *
 * <p><strong>Why every outcome answers {@code 200}.</strong> A rejected edit, an account that was not
 * found and a conflict detected against a concurrently changed record are all screens the legacy
 * program successfully composed and sent; the transaction completed on the mainframe in each case, so
 * it completes here and the outcome is read from the body exactly as an operator read it from the
 * screen. A malformed request - one whose fields exceed the widths the symbolic map declares - is a
 * different matter, and declarative validation rejects it before either method body runs, which the
 * shared failure handler turns into a {@code 400}.
 *
 * <p><strong>Routing is declarative.</strong> The response carries the next route as a value; nothing
 * here forwards, redirects or holds server-side conversation state. That is what replaces the legacy
 * transfer-control dispatch and the re-arm that followed it, and it is why each endpoint can be
 * exercised on its own.
 *
 * <p>Provenance: {@code app/cbl/COACTVWC.cbl} and {@code app/cbl/COACTUPC.cbl} with their symbolic maps
 * {@code app/cpy-bms/COACTVW.CPY} and {@code app/cpy-bms/COACTUP.CPY}, and the transaction definitions
 * at {@code app/csd/CARDDEMO.CSD} L306 and L317, all read as read-only reference at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement, map declaration or
 * copybook layout is transcribed here.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping(AccountController.ACCOUNTS_PATH)
public class AccountController {

    /**
     * Common prefix of both account routes.
     *
     * <p>Under {@code /api} and named directly by the filter chain's online-data operator rule. The
     * resource definitions make both declared CardDemo user types trusted operators of these screens but
     * declare no account ownership relation, so the rule admits those two authorities and refuses an
     * unrelated authenticated principal rather than inventing an owner. It is also not {@code /actuator}
     * and not the published API-document prefix, so this class shadows neither the management surface nor
     * the contract document.
     */
    public static final String ACCOUNTS_PATH = "/api/accounts";

    /** Route of transaction {@code CAVW}, relative to {@link #ACCOUNTS_PATH}. */
    public static final String VIEW_SUBPATH = "/view";

    /** Route of transaction {@code CAUP}, relative to {@link #ACCOUNTS_PATH}. */
    public static final String UPDATE_SUBPATH = "/update";

    /**
     * Absolute route of transaction {@code CAVW}.
     *
     * <p>Composed from the two constants above rather than written out a second time, so the mapping
     * this class installs and any rule or test that names the route cannot drift apart.
     */
    public static final String ACCOUNT_VIEW_PATH = ACCOUNTS_PATH + VIEW_SUBPATH;

    /** Absolute route of transaction {@code CAUP}, composed for the same reason. */
    public static final String ACCOUNT_UPDATE_PATH = ACCOUNTS_PATH + UPDATE_SUBPATH;

    /**
     * Name of the request parameter carrying the account-identifier search field of the view screen.
     *
     * <p>Optional, and that is contractual rather than lenient: an absent or blank field is exactly how
     * the operator reaches the "not provided" and "no input received" outcomes, and a required parameter
     * would make both unreachable.
     */
    public static final String ACCOUNT_ID_PARAM = "accountId";

    /**
     * Name of the request parameter carrying the terminal attention identifier as transmitted.
     *
     * <p>Passed through untranslated. The high program-function keys fold onto their low twins, and that
     * fold is implemented once in the utility-layer translator behind the service boundary; decoding a
     * key here would put a second copy of it on the wrong side of that boundary.
     */
    public static final String ATTENTION_KEY_PARAM = "attentionKey";

    /** Diagnostic channel. Carries no field value of either screen. */
    private static final Logger LOG = LoggerFactory.getLogger(AccountController.class);

    /** Timer name for one account-view turn, following the naming the sign-on and batch tiers use. */
    private static final String METRIC_VIEW_TURN = "carddemo.online.account.view.turn";

    /** Timer name for one account-update turn. */
    private static final String METRIC_UPDATE_TURN = "carddemo.online.account.update.turn";

    /**
     * Tag naming which of the four terminal presentations the view turn reached.
     *
     * <p>The presentation vocabulary is a closed enumeration of four constants, so the label is bounded
     * by construction and cannot become a high-cardinality series.
     */
    private static final String TAG_PRESENTATION = "presentation";

    /** Tag naming whether the update turn was accepted or rejected. */
    private static final String TAG_OUTCOME = "outcome";

    /** Value of {@link #TAG_OUTCOME} for a turn that raised no input error. */
    private static final String OUTCOME_ACCEPTED = "accepted";

    /** Value of {@link #TAG_OUTCOME} for a turn that raised one. */
    private static final String OUTCOME_REJECTED = "rejected";

    /** Value of {@link #TAG_OUTCOME} for a view turn that returned a screen. */
    private static final String OUTCOME_COMPLETED = "completed";

    /** Value of {@link #TAG_OUTCOME} for a turn that raised before returning a screen. */
    private static final String OUTCOME_FAILED = "failed";

    /** Presentation tag used when no view outcome was available. */
    private static final String PRESENTATION_UNRESOLVED = "UNRESOLVED";

    /**
     * Width at which the view contract publishes the postal code.
     *
     * <p>The stored column is wider than the screen item the contract mirrors, and the original placed
     * the wider value into the narrower item, keeping its leading characters. Publishing the stored
     * width instead would break the declared bound on the response; publishing more characters than the
     * screen ever showed would invent data the operator never saw. This is the width the published
     * contract declares, and no offset is computed and no record image is parsed to honour it.
     */
    private static final int PUBLISHED_ZIP_CODE_WIDTH = 5;

    /** Width at which the view contract publishes either telephone number, for the same reason. */
    private static final int PUBLISHED_PHONE_NUMBER_WIDTH = 13;

    /**
     * The authority under which the view turn asks for the four regulated values.
     *
     * <p>Masked by default, which is the documented default of the gate itself. Revealing requires
     * either the administrative role or an ownership determination the caller made for itself, and a
     * screen boundary can honestly assert neither: the estate's sign-on record carries no account
     * linkage, so there is no ownership to establish, and the echoed communication area is a
     * client-supplied value that must never be read for an authorization decision. Asking for a mask is
     * therefore the truthful request, and the gate answers it with values at the same widths the
     * revealed ones would occupy, so no client has to lay the screen out differently.
     */
    private static final AccountProtectedDataAdapter.RevealAuthorization VIEW_REVEAL_AUTHORIZATION =
            AccountProtectedDataAdapter.RevealAuthorization.unprivileged(
                    AccountProtectedDataAdapter.RevealPurpose.ACCOUNT_VIEW, null);

    /**
     * The four regulated values of a turn that resolved no customer record.
     *
     * <p>All absent, which is what the original showed: the customer field group was placed on the map
     * only when the customer master returned a row, and the fields were left as the initialising
     * statement had them otherwise.
     */
    private static final AccountProtectedDataAdapter.AccountViewProtectedValues NO_PROTECTED_VALUES =
            new AccountProtectedDataAdapter.AccountViewProtectedValues(null, null, null, null);

    /**
     * Prefix the filter chain puts in front of a user type when it grants the authority.
     *
     * <p>Resolving a user type out of the granted authority is the inverse of that single mapping, and
     * the prefix is named once here rather than spelled into the resolution, exactly as the menu surface
     * names it.
     */
    private static final String ROLE_AUTHORITY_PREFIX = "ROLE_";

    /** The account-view transaction. */
    private final AccountViewService accountViewService;

    /** The account-update transaction. */
    private final AccountUpdateService accountUpdateService;

    /**
     * The only permitted source of the national identifier, the date of birth, the government-issued
     * identifier and the electronic-funds account identifier.
     *
     * <p>Two of the four columns hold a protected-value envelope rather than cleartext, so reading them
     * through the record accessors would publish ciphertext; the other two are gated by policy. This
     * collaborator is where both directions happen, and it is the reason no stored protected column is
     * read anywhere in this class.
     */
    private final AccountProtectedDataAdapter accountProtectedDataAdapter;

    /**
     * The only permitted converter between the wire screen carriers and the service-owned ones.
     *
     * <p>The account-view transaction takes the work area and the echoed navigation state in the forms
     * the service layer owns, and returns the navigation state in the same form, because nothing may
     * depend upward on {@code api.dto}. This collaborator is where each of those crossings happens, and
     * it converts positionally: no value is trimmed, padded, defaulted or reconciled on the way through.
     */
    private final ScreenStateAdapter screenStateAdapter;

    /**
     * The only permitted converter between the account-update wire contract and the service-owned pair.
     *
     * <p>The update transaction takes the transmitted screen, and returns the settled turn, in the forms the
     * service layer owns, because nothing may depend upward on {@code api.dto}. This collaborator is where
     * both crossings happen, and it converts positionally over forty-six components inbound and fifty-seven
     * outbound: no operator-typed value is trimmed, padded, defaulted, parsed or re-scaled on the way
     * through, and no monetary component is rounded.
     */
    private final AccountUpdateContractAdapter accountUpdateContractAdapter;

    /** Registry the two turn timers are registered against. */
    private final MeterRegistry meterRegistry;

    /**
     * Creates the controller over its collaborators.
     *
     * @param accountViewService the account-view transaction
     * @param accountUpdateService the account-update transaction
     * @param accountProtectedDataAdapter the gate over the four regulated values
     * @param screenStateAdapter the converter between the wire screen carriers and the service-owned ones
     * @param accountUpdateContractAdapter the converter between the update wire contract and the
     *     service-owned command and outcome
     * @param meterRegistry the metrics registry
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public AccountController(final AccountViewService accountViewService,
                            final AccountUpdateService accountUpdateService,
                            final AccountProtectedDataAdapter accountProtectedDataAdapter,
                            final ScreenStateAdapter screenStateAdapter,
                            final AccountUpdateContractAdapter accountUpdateContractAdapter,
                            final MeterRegistry meterRegistry) {
        this.accountViewService =
                Objects.requireNonNull(accountViewService, "accountViewService must not be null");
        this.accountUpdateService =
                Objects.requireNonNull(accountUpdateService, "accountUpdateService must not be null");
        this.accountProtectedDataAdapter = Objects.requireNonNull(accountProtectedDataAdapter,
                "accountProtectedDataAdapter must not be null");
        this.screenStateAdapter =
                Objects.requireNonNull(screenStateAdapter, "screenStateAdapter must not be null");
        this.accountUpdateContractAdapter = Objects.requireNonNull(accountUpdateContractAdapter,
                "accountUpdateContractAdapter must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    /**
     * Serves one turn of the account view screen, transaction {@code CAVW}.
     *
     * <p>The screen has exactly one field an operator may type into - the account identifier - so the
     * turn is bound from that field, the attention identifier and the echoed communication area, and
     * nothing else. All three are optional, and each absence is a reachable outcome rather than a client
     * error: no communication area at all is the first turn of a conversation, and an absent identifier
     * is the outcome that asks for one.
     *
     * <p>That is also why this mapping declares what it produces but not what it consumes. Requiring a
     * media type would make a request with no body at all unroutable, and a turn carrying no
     * communication area is precisely the zero-length-area case the original tested for and answered
     * with a screen. Declaring the constraint would have turned a first turn into a client error.
     *
     * @param accountId the account-identifier search field as typed, or {@code null} when the operator
     *                  left it empty
     * @param attentionKey the terminal attention identifier as transmitted, or {@code null} when the
     *                     operator pressed the enter key
     * @param navigationContext the communication area the client echoed, or {@code null} on a turn that
     *                          carries none
     * @return the screen the turn produces, never {@code null}
     */
    @PostMapping(path = VIEW_SUBPATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "View an account",
            description = "One turn of legacy transaction CAVW. Answers 200 for every outcome the "
                    + "legacy screen could compose, including an account that was not found and a "
                    + "search field that was left empty: the outcome is read from the informational and "
                    + "error message components of the body. The four regulated customer values are "
                    + "masked at the widths their revealed forms occupy.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The turn completed. Carries the screen values the turn resolved, the "
                        + "messages it composed, the field the cursor returns to, the next route and "
                        + "the communication area to echo on the following turn."),
        @ApiResponse(responseCode = "400",
                description = "The echoed communication area exceeded the widths it declares."),
        @ApiResponse(responseCode = "401",
                description = "No session was presented, or the one presented did not verify."),
        @ApiResponse(responseCode = "403",
                description = "The authenticated principal is not an approved online-data operator.")})
    public ResponseEntity<AccountViewResponse> viewAccount(
            @RequestParam(name = ACCOUNT_ID_PARAM, required = false)
            @Parameter(description = "Account-identifier search field of the view screen, eleven "
                    + "digits. Optional: an absent field is the outcome that asks for one.")
            final String accountId,
            @RequestParam(name = ATTENTION_KEY_PARAM, required = false)
            @Parameter(description = "Terminal attention identifier as transmitted, for example "
                    + "DFHPF03 to leave the screen. Optional; absent reads as the enter key.")
            final String attentionKey,
            @Valid @RequestBody(required = false) final NavigationContext navigationContext) {

        final Timer.Sample sample = Timer.start(this.meterRegistry);
        String presentation = PRESENTATION_UNRESOLVED;
        String outcome = OUTCOME_FAILED;
        try {
            // The work area is assembled rather than accepted as a body: this screen's only input field is
            // the account identifier, and the transaction reads no other member of it.
            final ScreenWorkArea screenInput = new ScreenWorkArea(
                    null, null, null, null, null, null, accountId, null, null);

            final AccountViewService.AccountViewResult result = this.accountViewService.viewAccount(
                    attentionKey,
                    this.screenStateAdapter.toInputState(screenInput),
                    this.screenStateAdapter.toNavigationState(navigationContext));
            final AccountViewResponse body = toViewResponse(result);

            presentation = result.presentation().name();
            outcome = OUTCOME_COMPLETED;
            LOG.debug("Account view turn completed: presentation={} route={} inputError={}",
                    result.presentation(), result.route(), result.errorFlag());
            return ResponseEntity.ok(body);
        } finally {
            sample.stop(Timer.builder(METRIC_VIEW_TURN)
                    .description("Elapsed time of one CardDemo account view turn, transaction CAVW")
                    .tag(TAG_PRESENTATION, presentation)
                    .tag(TAG_OUTCOME, outcome)
                    .register(this.meterRegistry));
        }
    }

    /**
     * Serves one turn of the account update screen, transaction {@code CAUP}.
     *
     * <p>One turn may fetch a record, edit what was typed, ask for confirmation, save, report a conflict
     * or leave, and which of those it does is decided entirely by the service from the request's own
     * contents and the key that was pressed. This method chooses none of it and reports whatever the
     * service composed, including the per-field error states, the informational text and the token that
     * carries the fetched image forward.
     *
     * <p>The request is bound with declarative validation so that a field wider than the symbolic map
     * declares is refused before the transaction runs. Two of the forty-three map fields carry no
     * constraint at all and none may ever be added: the source states in place that neither the middle
     * name nor the second address line is edited, so constraining them here would reject input the
     * legacy accepts.
     *
     * @param request the screen input for this turn: the typed fields, the key pressed, the echoed
     *                communication area and the token from the previous turn
     * @param attentionKey the terminal attention identifier as transmitted, or {@code null} to use the
     *                     typed action the request already carries
     * @return the screen the turn produces, never {@code null}
     */
    @PostMapping(path = UPDATE_SUBPATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update an account",
            description = "One turn of legacy transaction CAUP. Answers 200 for every outcome the "
                    + "legacy screen could compose - a fetch, a rejected edit, a confirmation prompt, a "
                    + "committed change, a detected conflict or an exit - because each of those is a "
                    + "screen the transaction completed. Rejected fields are reported per field as "
                    + "MISSING or INVALID, and only on a re-submitted turn. The three national-identifier "
                    + "positions, the three date-of-birth positions, the government-issued identifier and "
                    + "the electronic-funds account identifier are masked at the widths their revealed "
                    + "forms occupy unless the caller carries the administrative authority; a caller that "
                    + "receives masks cannot change those fields.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The turn completed. Carries the screen values, the informational and "
                        + "error messages, the per-field error states, the field the cursor returns to, "
                        + "the next route, the communication area to echo and the token that carries "
                        + "the fetched image into the next turn."),
        @ApiResponse(responseCode = "400",
                description = "A field exceeded the width the update map declares."),
        @ApiResponse(responseCode = "401",
                description = "No session was presented, or the one presented did not verify."),
        @ApiResponse(responseCode = "403",
                description = "The authenticated principal is not an approved online-data operator."),
        @ApiResponse(responseCode = "409",
                description = "The fetched record was changed by another actor before this turn could "
                        + "save it.")})
    public ResponseEntity<AccountUpdateResponse> updateAccount(
            @Valid @RequestBody final AccountUpdateRequest request,
            @RequestParam(name = ATTENTION_KEY_PARAM, required = false)
            @Parameter(description = "Terminal attention identifier as transmitted, for example "
                    + "DFHPF05 to save. Optional; absent uses the typed action the body carries.")
            final String attentionKey,
            final Authentication authentication) {

        final Timer.Sample sample = Timer.start(this.meterRegistry);
        String outcome = OUTCOME_FAILED;
        try {
            final AccountUpdateResponse composed = this.accountUpdateContractAdapter.toResponse(
                    this.accountUpdateService.handle(
                            this.accountUpdateContractAdapter.toCommand(request), attentionKey));
            final AccountUpdateResponse body = gatedForUpdate(composed, authentication);

            outcome = body.error() ? OUTCOME_REJECTED : OUTCOME_ACCEPTED;
            LOG.debug("Account update turn completed: route={} inputError={} fieldErrorCount={}",
                    body.nextRoute(), body.error(), body.fieldErrors().size());
            return ResponseEntity.ok(body);
        } finally {
            sample.stop(Timer.builder(METRIC_UPDATE_TURN)
                    .description("Elapsed time of one CardDemo account update turn, transaction CAUP")
                    .tag(TAG_OUTCOME, outcome)
                    .register(this.meterRegistry));
        }
    }

    /**
     * Applies the regulated-data gate to the screen the update transaction composed.
     *
     * <p><strong>What this closes.</strong> The update transaction resolves an account by the identifier
     * the request names, fetches the customer joined to it, and composes a screen carrying that
     * customer's national identifier, date of birth, government-issued identifier and electronic-funds
     * account identifier. The route is reachable by any signed-on caller, exactly as the resource
     * definition makes it, and the identifier is a request field rather than a property of the caller -
     * so before this gate existed, any signed-on caller could name any account and read four regulated
     * values in the clear. The view turn had been gated since it was written; the update turn had not,
     * and that asymmetry was the defect.
     *
     * <p><strong>Why the gate is applied here rather than in the transaction.</strong> The transaction
     * needs the cleartext: it compares every typed field against the stored value to decide whether a
     * change occurred, and two of those fields are the protected identifiers. It also may not name the
     * gate, because a service does not depend on this package. So the transaction keeps composing the
     * screen it always composed, and the boundary that knows who is asking replaces the eight regulated
     * components before the screen is published.
     *
     * <p><strong>What authority is asserted, and what is deliberately not.</strong> An administrator
     * reveals. Every other caller receives masks at the same widths the revealed values occupy, so no
     * client has to lay the screen out differently. Ownership is <em>not</em> asserted: the sign-on
     * record carries no account linkage and no program in the estate checks one, so this boundary has no
     * honest way to establish that a caller owns the account it named - and asking for a mask is the
     * truthful request when that is the case. The echoed communication area is never consulted for this,
     * because it is a client-supplied value and an authorization decided by request content is not an
     * authorization.
     *
     * <p><strong>The consequence is stated rather than hidden.</strong> A caller that receives masks
     * cannot submit the masked values back as changes - the transaction's own edits refuse a
     * non-numeric identifier exactly as they refuse any other malformed one - so changing a regulated
     * field requires the authority to see it. That is a deliberate divergence from the estate, which
     * showed the full identifier to any signed-on operator, and it is recorded in
     * {@code docs/decision-log.md} on the same footing as the credential-hashing exception.
     *
     * @param composed the screen the transaction composed, never {@code null}
     * @param authentication the established identity, which may be {@code null} on a route the chain
     *                       does not authenticate
     * @return the screen with its eight regulated components revealed or masked
     */
    private AccountUpdateResponse gatedForUpdate(final AccountUpdateResponse composed,
                                                 final Authentication authentication) {
        final UserType signedOnType = signedOnUserType(authentication);
        final AccountProtectedDataAdapter.RevealAuthorization authority =
                signedOnType == UserType.ADMIN
                        ? AccountProtectedDataAdapter.RevealAuthorization.administrator(
                                AccountProtectedDataAdapter.RevealPurpose.ACCOUNT_UPDATE)
                        : AccountProtectedDataAdapter.RevealAuthorization.unprivileged(
                                AccountProtectedDataAdapter.RevealPurpose.ACCOUNT_UPDATE, signedOnType);

        final AccountProtectedDataAdapter.AccountUpdateProtectedValues gated =
                this.accountProtectedDataAdapter.gateForUpdate(
                        new AccountProtectedDataAdapter.AccountUpdateProtectedValues(
                                composed.ssnPart1(),
                                composed.ssnPart2(),
                                composed.ssnPart3(),
                                composed.dateOfBirthYear(),
                                composed.dateOfBirthMonth(),
                                composed.dateOfBirthDay(),
                                composed.governmentIssuedId(),
                                composed.eftAccountId()),
                        authority);

        return composed.withRegulatedValues(
                gated.ssnPart1(),
                gated.ssnPart2(),
                gated.ssnPart3(),
                gated.dateOfBirthYear(),
                gated.dateOfBirthMonth(),
                gated.dateOfBirthDay(),
                gated.governmentIssuedId(),
                gated.eftAccountId());
    }

    /**
     * Reads the user type of the established identity out of the authority the chain granted it.
     *
     * <p>This is a read of an identity that has already been established, not a check of one: the chain
     * has already refused every caller with no business reaching the handler, and nothing here admits or
     * refuses anybody. The value decides only whether the regulated components are revealed or masked.
     *
     * <p>Resolution is the inverse of the single mapping the chain applies when it grants the authority:
     * the framework's role prefix followed by the name of the user type. An identity carrying neither
     * declared authority yields {@code null}, which the gate treats as no authority to reveal rather than
     * as a type it must guess.
     *
     * @param authentication the established identity, which may be {@code null}
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

    /**
     * Publishes one account-view turn on the declared response contract.
     *
     * <p>The update transaction publishes its own screen, so only this one needs projecting. What is
     * done here is a projection and nothing more: every value is either carried straight across or
     * withheld, in the component order the contract declares. Nothing is validated, nothing is
     * decorated, nothing is compared, nothing is rounded or re-scaled and nothing is persisted.
     *
     * <p><strong>Two group guards, and they are not the same guard.</strong> The account field group was
     * placed on the map when <em>either</em> master returned a row, and the customer field group only
     * when the customer master did. Both are reproduced as the service reports them rather than as one
     * might expect them, and each is additionally conditioned on the row actually being present, so a
     * turn that satisfies the wider guard without an account row publishes those components as absent -
     * which is what the original showed, the fields still holding what its initialising statement left.
     *
     * <p><strong>The four regulated values never come from the record.</strong> They come from the gate,
     * once, and only when a customer row was resolved. Two of the four columns hold an envelope rather
     * than cleartext, so a direct read would publish ciphertext; the gate is also what applies the
     * masking policy and what composes the national identifier into the single dashed item this screen
     * declared, in place of the three separate positions the update screen declares.
     *
     * <p><strong>Money crosses untouched.</strong> The response contract refuses an amount whose scale
     * is not the scale its record field stores, and honouring that by passing the stored value through
     * is the point: re-scaling here would hide a defect the contract exists to surface, and would put a
     * second, silently different rounding policy in the module.
     *
     * <p>The long diagnostic field the service also reports is deliberately not published. It belongs to
     * a debugging exit that no path through the transaction reaches, and the symbolic map declares no
     * item for it, so publishing it would add a component the screen contract does not have.
     *
     * @param result the turn the transaction produced, never {@code null}
     * @return the response body, never {@code null}
     */
    private AccountViewResponse toViewResponse(final AccountViewService.AccountViewResult result) {
        final AccountViewService.ScreenHeader header = result.screenHeader();
        final boolean accountPresented = result.accountFieldsPresented() && result.account() != null;
        final boolean customerPresented = result.customerFieldsPresented() && result.customer() != null;
        final AccountProtectedDataAdapter.AccountViewProtectedValues regulated = customerPresented
                ? this.accountProtectedDataAdapter.revealForView(result.customer(),
                        VIEW_REVEAL_AUTHORIZATION)
                : NO_PROTECTED_VALUES;

        return new AccountViewResponse(
                // Header group. Absent in full on the path that hands control to another screen, which
                // assembled no map of its own.
                header == null ? null : header.transactionName(),
                header == null ? null : header.title01(),
                header == null ? null : header.currentDate(),
                header == null ? null : header.programName(),
                header == null ? null : header.title02(),
                header == null ? null : header.currentTime(),

                // The search field as echoed back, already blanked by the transaction when its flag is
                // blank.
                result.accountIdFilter(),

                // Account field group.
                accountPresented ? result.account().getAcctActiveStatus() : null,
                accountPresented ? result.account().getAcctOpenDate() : null,
                accountPresented ? result.account().getAcctCreditLimit() : null,
                accountPresented ? result.account().getAcctExpirationDate() : null,
                accountPresented ? result.account().getAcctCashCreditLimit() : null,
                accountPresented ? result.account().getAcctReissueDate() : null,
                accountPresented ? result.account().getAcctCurrBal() : null,
                accountPresented ? result.account().getAcctCurrCycCredit() : null,
                accountPresented ? result.account().getAcctGroupId() : null,
                accountPresented ? result.account().getAcctCurrCycDebit() : null,

                // Customer field group. The identifier and the credit score are ordinary business
                // content of an account-servicing screen and are not gated.
                customerPresented ? result.customer().getCustId() : null,
                regulated.ssn(),
                regulated.dateOfBirth(),
                customerPresented ? result.customer().getFicoCreditScore() : null,
                customerPresented ? result.customer().getFirstName() : null,
                customerPresented ? result.customer().getMiddleName() : null,
                customerPresented ? result.customer().getLastName() : null,
                customerPresented ? result.customer().getAddrLine1() : null,
                customerPresented ? result.customer().getAddrStateCd() : null,
                customerPresented ? result.customer().getAddrLine2() : null,
                customerPresented
                        ? atPublishedWidth(result.customer().getAddrZip(), PUBLISHED_ZIP_CODE_WIDTH)
                        : null,
                // The city item is populated from the third address line. There is no city member in
                // the customer layout at all, and the transaction is the authority for that pairing.
                customerPresented ? result.customer().getAddrLine3() : null,
                customerPresented ? result.customer().getAddrCountryCd() : null,
                customerPresented
                        ? atPublishedWidth(result.customer().getPhoneNum1(),
                                PUBLISHED_PHONE_NUMBER_WIDTH)
                        : null,
                regulated.governmentIssuedId(),
                customerPresented
                        ? atPublishedWidth(result.customer().getPhoneNum2(),
                                PUBLISHED_PHONE_NUMBER_WIDTH)
                        : null,
                regulated.eftAccountId(),
                customerPresented ? result.customer().getPriCardHolderInd() : null,

                // Message and control group. Both messages are published at the transaction's own
                // widths, untrimmed, because the padding is part of what the screen showed.
                result.infoMessage(),
                result.errorMessage(),
                result.errorFlag(),
                result.focusScreenFieldId(),
                result.route(),
                this.screenStateAdapter.toNavigationContext(result.navigationContext()));
    }

    /**
     * Publishes a stored value at the width the response contract declares for it.
     *
     * <p>Two of the customer columns are wider than the screen items this screen declared, and placing
     * the wider value into the narrower item kept its leading characters. Reproducing that is required
     * twice over: the contract's declared bound would otherwise be violated, and publishing characters
     * the operator never saw would invent data. A value that already fits is returned as it is, so
     * nothing is padded, trimmed or otherwise altered.
     *
     * <p>This is not record-image handling. No offset is computed from a layout, no picture clause is
     * decoded and no sign is unpacked; those belong to the mapping layer and are never performed here.
     *
     * @param storedValue the value as stored, or {@code null} when the record holds none
     * @param publishedWidth the width the response contract declares for the component
     * @return the value at the published width, or {@code null} when there was none
     */
    private static String atPublishedWidth(final String storedValue, final int publishedWidth) {
        if (storedValue == null || storedValue.length() <= publishedWidth) {
            return storedValue;
        }
        return storedValue.substring(0, publishedWidth);
    }
}
