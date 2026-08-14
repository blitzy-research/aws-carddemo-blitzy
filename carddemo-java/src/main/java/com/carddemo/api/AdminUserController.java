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

import com.carddemo.api.dto.UserRequest;
import com.carddemo.api.dto.UserResponse;
import com.carddemo.service.UserManagementService;
import com.carddemo.util.ApiRoutePaths;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.groups.Default;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface of the four administrator-only user-maintenance transactions {@code CU00},
 * {@code CU01}, {@code CU02} and {@code CU03}.
 *
 * <h2>What this class does, and the far longer list of what it does not</h2>
 *
 * <p>Each of the four methods below binds a request body, makes <strong>one</strong> call to
 * {@link UserManagementService}, records how long that call took, and returns the answer. That is the
 * whole of it. Every rule - presence tests and their ordering, change detection, selector scanning,
 * paging, credential handling, message selection and routing - lives in the service, which is what lets
 * all four transactions be exercised without a servlet and, more importantly, stops this class from
 * becoming a second, quietly divergent copy of rules the legacy programs state exactly once.
 *
 * <p>The distinction matters because these four screens are unusually rule-dense for their size, and
 * several of the rules are ones a reader would reasonably expect a controller to hold:
 *
 * <ul>
 *   <li><strong>The presence cascades are ordered, and the two orders differ.</strong> The add screen
 *       tests the given name, the family name, then the identifier <em>third</em>, then the credential,
 *       then the type. The update screen tests the <em>identifier first</em> and the other four after
 *       it. One message is reported per submission, first-satisfied-wins. Bean Validation cannot
 *       express an order, so the cascades stay in the service and this class never adds a constraint
 *       that could pre-empt one.</li>
 *   <li><strong>Only the first non-blank row selector is actionable.</strong> Ten selectors arrive with
 *       the list turn; the service scans them in row order, dispatches {@code U} or {@code u} toward
 *       the update screen and {@code D} or {@code d} toward the delete screen, and answers anything
 *       else non-blank with a fixed text. Nothing here inspects the collection.</li>
 *   <li><strong>An unaccepted selector does not suppress the page.</strong> Uniquely among these
 *       programs' error arms, that one reports a message without raising the error flag, so control
 *       falls through and the page is still built. A response carrying that message <em>together
 *       with</em> ten populated rows is therefore correct, and no code here treats a message as
 *       terminal.</li>
 *   <li><strong>The delete screen has no credential item at all,</strong> and the request contract
 *       refuses one on that operation rather than accepting and ignoring it.</li>
 * </ul>
 *
 * <h2>Ten rows, because the screen has ten rows</h2>
 *
 * <p>The list turn presents at most ten rows. That figure is the shape of a 3270 screen and not a
 * tuning value: {@code app/cbl/COUSR00C.cbl} declares its row table {@code OCCURS 10 TIMES} at line 57
 * and the two loops that fill and clear it bound themselves at the same figure. It is consequently
 * owned by the contract rather than by a caller - the request type declares no page size, the response
 * type's own constructor refuses an eleventh row, and the service takes the count from a named
 * constant. This class therefore neither sends a page size nor checks one, which is the only
 * arrangement in which a client cannot widen a page the legacy screen could not widen.
 *
 * <p>Fill order is likewise preserved rather than normalised. A page reached by walking forward fills
 * rows one through ten; a page reached by walking backward fills them ten down to one. The service
 * settles that, and nothing here re-orders, reverses or re-indexes a page on its way out.
 *
 * <h2>Why every route is administrator-only, and how that is actually enforced</h2>
 *
 * <p>All four routes are mapped beneath {@link #USERS_PATH}, which sits under the {@code /api/admin}
 * prefix the security configuration gates. The chain installs one rule over that prefix and everything
 * below it, requiring the administrative authority, so a route added beneath the prefix is
 * administrator-only from the moment it exists rather than from the moment somebody remembers to write
 * a rule for it. Why the prefix is spelled out here instead of imported from the configuration is set
 * out on {@link #USERS_PATH}, and it is a layering requirement rather than a preference.
 *
 * <p><strong>The prefix is not a stylistic choice; it is the only mechanism available.</strong> Method
 * security is not enabled anywhere in this module, so a method-level authority annotation here would
 * be a silent no-op - it would read as a guard while enforcing nothing, which is strictly worse than
 * no annotation. Mapping beneath the gated prefix is the first of the two arrangements the navigation
 * layer names as acceptable, and it is the one that works. A route placed outside the prefix but still
 * beneath the API root is now refused outright by the chain's closing refusal, because the ordinary
 * grants name eleven exact addresses and this would not be one of them - so the failure mode of
 * forgetting the prefix is a route nobody can reach rather than one every signed-on caller can, which
 * is why the prefix is read from the published constant instead of being retyped as a literal.
 *
 * <p>No credential is parsed here and no identity is inspected here. The bearer session is established
 * upstream by the chain's own filter, and the published interface description applies its bearer
 * requirement globally, so these four operations inherit it without declaring anything.
 *
 * <h2>Why the request body is validated against a named operation</h2>
 *
 * <p>One request type serves all four screens, and the four screens declare different input items.
 * {@link UserRequest} therefore publishes four marker interfaces so that each endpoint can name the
 * operation it serves, and the components that operation's map does not declare are rejected as
 * inapplicable rather than silently carried. Each method below names its marker <em>together with</em>
 * {@link Default}, because the width bounds carry no group of their own: naming only the marker would
 * drop every width bound, and naming neither would drop every inapplicability rule.
 *
 * <p>This does not disturb the ordered cascades described above. Every group-scoped constraint asserts
 * <em>absence</em> and not one of them makes a component mandatory, so none can report before the
 * service reaches its own first presence test.
 *
 * <h2>Why every outcome is answered {@code 200}</h2>
 *
 * <p>Each of these turns is a screen the legacy program successfully composed and transmitted, and that
 * includes every rejection: a duplicate identifier, an absent record, an unaccepted selector and an
 * unsupported key were all screens an operator read, not transmission failures. The transaction
 * completed on the mainframe in each case, so it completes here, and the outcome is read from the body
 * exactly as it was read from the screen. Reporting an absent record as {@code 404} or a duplicate
 * identifier as {@code 409} would be a modernising change to an externally observable contract, and it
 * would additionally disclose which rejection occurred to anything that inspects only the status line.
 *
 * <p>A malformed request is a different matter and is not a screen: a body exceeding the widths the maps
 * declare, or carrying a component its operation has no field for, is refused by declarative validation
 * before any method here runs, and the shared failure contract renders that as {@code 400}. Absent or
 * insufficient authority is refused by the chain before that, as {@code 401} or {@code 403}. Nothing
 * here catches an exception, because the shared failure contract already maps every one the service can
 * raise.
 *
 * <h2>What the instrumentation is allowed to record</h2>
 *
 * <p>Each operation has its own timer, so the four transactions are separable without a per-request
 * label, and each carries one tag drawn from a fixed set of four outcomes: the three a composed screen
 * reduces to, and the seeded one a turn that raised before composing anything leaves in place. The three
 * screen outcomes are derived from the response's two boolean indicators and from whether it carries
 * field-level errors, never from its message text. That restriction is
 * deliberate and load-bearing: three of these screens' success texts embed the eight-character
 * identifier of the affected record, so tagging by message would publish personal data into the metric
 * store and give the label unbounded cardinality at the same time. No identifier, name, credential,
 * token, route or message is ever attached to a meter or written to a diagnostic here, and the two
 * values the debug record does carry are compile-time constants rather than caller-supplied text, so
 * neither can inject a line break into a log record.
 *
 * <h2>Provenance</h2>
 *
 * <p>Legacy authorities: {@code app/cbl/COUSR00C.cbl} ({@code CU00}, list),
 * {@code app/cbl/COUSR01C.cbl} ({@code CU01}, add), {@code app/cbl/COUSR02C.cbl} ({@code CU02}, update)
 * and {@code app/cbl/COUSR03C.cbl} ({@code CU03}, delete), with their symbolic maps, mapsets and the
 * shared copybooks {@code app/cpy/COCOM01Y.cpy}, {@code app/cpy/CSMSG01Y.cpy},
 * {@code app/cpy/COTTL01Y.cpy} and {@code app/cpy/COADM02Y.cpy}. {@code app/csd/CARDDEMO.CSD} binds
 * {@code CU00} at line 449, {@code CU01} at 459, {@code CU02} at 469 and {@code CU03} at 479, each to
 * the correspondingly named program, and {@code CA00} at 327 to the administrative menu these four
 * return toward - which is where the administrative gating below comes from.
 * return toward.
 *
 * <p><strong>Immutability and thread safety.</strong> Both collaborators are final and are supplied
 * through the only constructor; the class declares no mutable field, no static state and nothing
 * request-scoped, so one instance is safely shared across threads. Nothing is retained between turns,
 * which is what makes the conversation stateless: the navigation context travels in the payload rather
 * than in a session, and the client drives the next call rather than the server forwarding to it.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping(AdminUserController.USERS_PATH)
public class AdminUserController {

    /**
     * Path prefix all four user-maintenance routes share, and the reason they are administrator-only:
     * it sits beneath the {@code /api/admin} prefix that the security chain gates with one rule.
     *
     * <p><strong>The prefix is spelled out here rather than imported, and that is a layering
     * requirement rather than a preference.</strong> The module's package table licenses the
     * configuration package to depend on this boundary package and forbids the reverse, so importing
     * the configuration's prefix constant would both breach the table and close an
     * {@code api}-to-{@code config} cycle around the edge the configuration already holds in the
     * other direction. The declaring site therefore has to be the controller - which is exactly the
     * arrangement the anonymous sign-on route already uses, where the controller declares the path
     * and the configuration reads it.
     *
     * <p>What that costs is a textual coupling, so it is stated plainly: this value must remain
     * beneath {@code /api/admin}. A route moved outside that prefix is refused by the chain's closing
     * refusal over the API root, because it would be neither an administrative address nor one of the
     * eleven named ordinary ones - so the mistake makes the surface unreachable instead of widening it.
     * That is the direction it must fail in, and it is still a regression. The coupling is not merely
     * documented: the security configuration's
     * own suite probes this exact base path, admitting an administrator's session and refusing a
     * standard user's with a forbidden answer, so a drift here is caught there.
     *
     * <p>A single compile-time constant, which is what allows it to appear in an annotation.
     */
    public static final String USERS_PATH = ApiRoutePaths.ADMIN_USERS_PATH;

    /** Sub-path of the list turn, legacy transaction {@code CU00}. */
    public static final String LIST_SUBPATH = ApiRoutePaths.LIST_SUBPATH;

    /** Sub-path of the add turn, legacy transaction {@code CU01}. */
    public static final String ADD_SUBPATH = ApiRoutePaths.ADD_SUBPATH;

    /** Sub-path of the fetch-and-update turn, legacy transaction {@code CU02}. */
    public static final String UPDATE_SUBPATH = ApiRoutePaths.UPDATE_SUBPATH;

    /** Sub-path of the fetch-and-delete turn, legacy transaction {@code CU03}. */
    public static final String DELETE_SUBPATH = ApiRoutePaths.DELETE_SUBPATH;

    /** Diagnostic channel. */
    private static final Logger LOG = LoggerFactory.getLogger(AdminUserController.class);

    /** Legacy transaction identifier of the list screen. */
    private static final String LIST_TRANSACTION_ID = "CU00";

    /** Legacy transaction identifier of the add screen. */
    private static final String ADD_TRANSACTION_ID = "CU01";

    /** Legacy transaction identifier of the update screen. */
    private static final String UPDATE_TRANSACTION_ID = "CU02";

    /** Legacy transaction identifier of the delete screen. */
    private static final String DELETE_TRANSACTION_ID = "CU03";

    /** Timer name for one list turn, following the sign-on screen's naming. */
    private static final String METRIC_LIST_TURN = "carddemo.online.userlist.turn";

    /** Timer name for one add turn. */
    private static final String METRIC_ADD_TURN = "carddemo.online.useradd.turn";

    /** Timer name for one update turn. */
    private static final String METRIC_UPDATE_TURN = "carddemo.online.userupdate.turn";

    /** Timer name for one delete turn. */
    private static final String METRIC_DELETE_TURN = "carddemo.online.userdelete.turn";

    /**
     * Tag naming which of four bounded outcomes a turn reached.
     *
     * <p>Four values and no more, all declared below as constants: three that a composed screen reduces
     * to and one that a turn which raised carries. The tag is never derived from a message, an
     * identifier or any other value a caller can influence, so it cannot grow cardinality and cannot
     * carry personal data into the metric store.
     */
    private static final String TAG_OUTCOME = "outcome";

    /** Outcome of a turn whose write or delete completed. */
    private static final String OUTCOME_SUCCEEDED = "succeeded";

    /** Outcome of a turn the transaction refused, whether at field level or as a whole. */
    private static final String OUTCOME_REJECTED = "rejected";

    /** Outcome of a turn that neither completed a change nor was refused - a screen simply presented. */
    private static final String OUTCOME_PRESENTED = "presented";

    /** Outcome of a turn that raised before composing a screen. */
    private static final String OUTCOME_FAILED = "failed";

    /** The four user-maintenance transactions. */
    private final UserManagementService userManagementService;

    /**
     * The only permitted converter between the wire contract and the service-owned command and outcome.
     *
     * <p>All four transactions take the transmitted screen, and return the settled turn, in the forms the
     * service layer owns, because nothing may depend upward on {@code api.dto}. This collaborator is where
     * every crossing happens, and it converts positionally: no value, no selection character, no page of
     * rows and no leading-zero page indicator is trimmed, padded, defaulted or reordered on the way
     * through.
     */
    private final UserContractAdapter userContractAdapter;

    /** Registry the four turn timers are registered against. */
    private final MeterRegistry meterRegistry;

    /**
     * Creates the controller over its three collaborators.
     *
     * @param userManagementService the four user-maintenance transactions
     * @param userContractAdapter the converter between the wire contract and the service-owned types
     * @param meterRegistry the metrics registry
     * @throws NullPointerException if any argument is {@code null}
     */
    public AdminUserController(final UserManagementService userManagementService,
                              final UserContractAdapter userContractAdapter,
                              final MeterRegistry meterRegistry) {
        this.userManagementService = Objects.requireNonNull(userManagementService,
                "userManagementService must not be null");
        this.userContractAdapter = Objects.requireNonNull(userContractAdapter,
                "userContractAdapter must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    /**
     * Serves one turn of the user-list screen, legacy transaction {@code CU00}.
     *
     * <p>One turn covers listing, searching from a start key, paging in either direction and picking a
     * row, because the legacy screen covered all four in one transmission and distinguished them by the
     * attention key and the ten row selectors rather than by four separate entry points. Splitting them
     * would invent a contract the screen never had.
     *
     * <p>A blank start key is a value and not an omission: it means "begin at the very first record".
     * The request contract keeps it in a component of its own for exactly that reason, so the service
     * can still tell "list from the beginning" from "operate on nobody".
     *
     * <p>The answer carries at most ten rows, the paging metadata describing where the page sits, the
     * echoed navigation context, and - when a selector or an attention key nominated one - the logical
     * label of the destination the client should call next. Backward paging is presented in the order
     * the legacy screen filled it, and this method does not reverse it.
     *
     * @param request the operator's start key, ten row selectors, retained page boundaries, attention
     *                key and echoed navigation context
     * @return the screen the turn produces, always {@code 200}
     */
    @PostMapping(path = LIST_SUBPATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List, search, page and select sign-on records",
            description = "One turn of legacy transaction CU00, administrator-only. Presents at most "
                    + "ten rows - the shape of the legacy screen, not a tunable page size - preserving "
                    + "forward fill order for a page walked forward and reverse fill order for one "
                    + "walked backward. Only the first non-blank row selector is actionable: U or u "
                    + "nominates the update screen, D or d the delete screen, and any other non-blank "
                    + "value is reported while the page is still returned. Answers 200 for every "
                    + "outcome the legacy screen could compose, including a rejection; the outcome is "
                    + "read from the body.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The turn completed, carrying up to ten rows, paging metadata, the "
                        + "echoed navigation context and any screen message."),
        @ApiResponse(responseCode = "400",
                description = "The body exceeded the widths the list map declares, or carried a "
                        + "component that map does not declare."),
        @ApiResponse(responseCode = "401", description = "No bearer session was presented."),
        @ApiResponse(responseCode = "403",
                description = "The session does not carry the administrative authority.")})
    public ResponseEntity<UserResponse> listUsers(
            @Validated({Default.class, UserRequest.ListOperation.class})
            @RequestBody final UserRequest request,
            final Authentication authentication) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);
        String outcome = OUTCOME_FAILED;
        try {
            final UserResponse body = this.userContractAdapter.toResponse(
                    this.userManagementService.listUsers(
                            this.userContractAdapter.toCommand(request, authentication)),
                    authentication);
            outcome = outcomeOf(body);
            logCompletedTurn(LIST_TRANSACTION_ID, outcome);
            return ResponseEntity.ok(body);
        } finally {
            recordTurn(sample, METRIC_LIST_TURN, LIST_TRANSACTION_ID, outcome);
        }
    }

    /**
     * Serves one turn of the add-user screen, legacy transaction {@code CU01}.
     *
     * <p>The presence cascade runs given name, family name, identifier, credential, type - the
     * identifier third, which is the order this screen uses and not the order the update screen uses -
     * and reports one message per submission. A duplicate identifier is reported as a screen message
     * rather than raised, which is why this route answers {@code 200} rather than {@code 409} for it.
     *
     * <p>The submitted credential is hashed by the service on write. It is accepted inbound, never
     * returned, never logged, never attached to a meter and never rendered by any diagnostic
     * representation of the request.
     *
     * @param request the record to create, the attention key and the echoed navigation context
     * @return the screen the turn produces, always {@code 200}
     */
    @PostMapping(path = ADD_SUBPATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Add a sign-on record",
            description = "One turn of legacy transaction CU01, administrator-only. Required items are "
                    + "reported in the screen's own order - first name, last name, user id, password, "
                    + "user type - one message per submission. A duplicate identifier and a failed "
                    + "write are reported as screen messages, so this route answers 200 for every "
                    + "outcome the legacy screen could compose. The credential is accepted inbound "
                    + "only: it is hashed on write and is never returned.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The turn completed, carrying the outcome message, any field-level "
                        + "errors and the echoed navigation context."),
        @ApiResponse(responseCode = "400",
                description = "The body exceeded the widths the add map declares, or carried a "
                        + "component that map does not declare."),
        @ApiResponse(responseCode = "401", description = "No bearer session was presented."),
        @ApiResponse(responseCode = "403",
                description = "The session does not carry the administrative authority.")})
    public ResponseEntity<UserResponse> addUser(
            @Validated({Default.class, UserRequest.AddOperation.class})
            @RequestBody final UserRequest request,
            final Authentication authentication) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);
        String outcome = OUTCOME_FAILED;
        try {
            final UserResponse body = this.userContractAdapter.toResponse(
                    this.userManagementService.addUser(
                            this.userContractAdapter.toCommand(request, authentication)),
                    authentication);
            outcome = outcomeOf(body);
            logCompletedTurn(ADD_TRANSACTION_ID, outcome);
            return ResponseEntity.ok(body);
        } finally {
            recordTurn(sample, METRIC_ADD_TURN, ADD_TRANSACTION_ID, outcome);
        }
    }

    /**
     * Serves one turn of the update-user screen, legacy transaction {@code CU02}.
     *
     * <p>One turn covers both halves of the screen - retrieving a record and rewriting it - because the
     * legacy program did, distinguishing them by the attention key. The retrieval half requires the
     * identifier and confirms success by prompting for the save key; the rewrite half runs its own
     * presence cascade in <em>this</em> screen's order, identifier first, and then compares the four
     * mutable items against what was stored, reporting a distinct message when nothing changed.
     *
     * <h2>The credential has three states on this screen, not two</h2>
     *
     * <p>The presence cascade tests {@code PASSWDI} for spaces or low values at
     * {@code app/cbl/COUSR02C.cbl} L198-L203, which on a fixed-width map is a single test because the
     * map item is always transmitted. Over JSON the item can also be <em>omitted</em>, and that is a
     * third state the legacy map had no way to express. The three are distinguished deliberately:
     *
     * <ul>
     *   <li><strong>Absent</strong> - the component is not present in the body at all. The credential is
     *       unchanged: the stored digest is carried forward and is not re-hashed, because hashing a
     *       digest would lock the identity out. No fault is reported and the cascade's credential clause
     *       cannot fire. This is what lets an administrator amend a name without being made to retype,
     *       or to know, the credential.</li>
     *   <li><strong>Present but blank</strong> - the component is present and holds only spaces or is
     *       empty. This is the legacy's own empty-item condition and is reported as a
     *       {@code MISSING} field error carrying this screen's own text, with the cursor placed on the
     *       credential item.</li>
     *   <li><strong>Present and populated</strong> - the value is hashed and replaces the stored digest.
     *       It is never compared against the stored digest for equality first, because each digest
     *       carries its own salt and such a comparison would report every credential as changed.</li>
     * </ul>
     *
     * <p>So the credential is <em>conditionally</em> required rather than required: mandatory once
     * supplied, optional when withheld. The add screen differs and requires it outright, because a
     * record being created has no stored digest to carry forward. All of that reasoning lives in the
     * service; this method neither reads, compares nor hashes a credential.
     *
     * @param request the identifier to fetch or the amended record to store, the attention key and the
     *                echoed navigation context
     * @return the screen the turn produces, always {@code 200}
     */
    @PostMapping(path = UPDATE_SUBPATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Fetch and update a sign-on record",
            description = "One turn of legacy transaction CU02, administrator-only, covering both the "
                    + "retrieval and the rewrite halves of the screen as the legacy program did. "
                    + "Required items are reported in this screen's own order - user id, first name, "
                    + "last name, password, user type - which differs from the add screen, and only "
                    + "the first of them is reported because the legacy cascade stops at its first "
                    + "empty item. A submission that changes nothing is reported as such. An absent "
                    + "record and a failed rewrite are screen messages, so this route answers 200 for "
                    + "every outcome the legacy screen could compose. "
                    + "The password is CONDITIONALLY required on this operation, which is three states "
                    + "rather than two: omit the component entirely to leave the credential unchanged, "
                    + "in which case the stored digest is carried forward and nothing is reported; "
                    + "send it present but blank and it is reported as a missing field, which is the "
                    + "legacy screen's own empty-item condition; send it populated and it is hashed and "
                    + "replaces the stored digest. The add operation differs and requires it outright, "
                    + "because a new record has no stored digest to carry forward. The credential is "
                    + "accepted inbound only and is never returned.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The turn completed, carrying the retrieved record or the outcome "
                        + "message, any field-level errors and the echoed navigation context."),
        @ApiResponse(responseCode = "400",
                description = "The body exceeded the widths the update map declares, or carried a "
                        + "component that map does not declare."),
        @ApiResponse(responseCode = "401", description = "No bearer session was presented."),
        @ApiResponse(responseCode = "403",
                description = "The session does not carry the administrative authority.")})
    public ResponseEntity<UserResponse> updateUser(
            @Validated({Default.class, UserRequest.UpdateOperation.class})
            @RequestBody final UserRequest request,
            final Authentication authentication) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);
        String outcome = OUTCOME_FAILED;
        try {
            final UserResponse body = this.userContractAdapter.toResponse(
                    this.userManagementService.updateUser(
                            this.userContractAdapter.toCommand(request, authentication)),
                    authentication);
            outcome = outcomeOf(body);
            logCompletedTurn(UPDATE_TRANSACTION_ID, outcome);
            return ResponseEntity.ok(body);
        } finally {
            recordTurn(sample, METRIC_UPDATE_TURN, UPDATE_TRANSACTION_ID, outcome);
        }
    }

    /**
     * Serves one turn of the delete-user screen, legacy transaction {@code CU03}.
     *
     * <p>One turn covers retrieving a record and removing it, distinguished by the attention key. The
     * identifier is the only item this screen reads: the names and the type it displays are values the
     * server produces from the record it fetched, and the map declares no credential item at all, so
     * neither a credential nor a name is accepted inbound and no credential appears in the answer.
     *
     * <p>Nothing is removed implicitly. Retrieval does not delete, and neither the exit key nor the
     * cancel key deletes; only the confirming key does.
     *
     * <p><strong>One legacy defect is reproduced deliberately.</strong> An unexpected failure on the
     * removal path reports the <em>update</em> screen's failure text rather than a delete-specific one.
     * That text is part of the observable contract, so it is preserved exactly and is not corrected.
     * The text itself is a constant on the response type and is selected by the service; this method
     * only returns what it is given.
     *
     * @param request the identifier to fetch or remove, the attention key and the echoed navigation
     *                context
     * @return the screen the turn produces, always {@code 200}
     */
    @PostMapping(path = DELETE_SUBPATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Fetch and delete a sign-on record",
            description = "One turn of legacy transaction CU03, administrator-only, covering both the "
                    + "retrieval and the removal halves of the screen. The identifier is its only "
                    + "input; the names and type it returns are produced from the fetched record, and "
                    + "the screen has no credential field. Nothing is removed implicitly - only the "
                    + "confirming key deletes. An absent record and a failed removal are screen "
                    + "messages, so this route answers 200 for every outcome the legacy screen could "
                    + "compose; the failure text on the removal path reproduces a legacy defect and is "
                    + "preserved verbatim rather than corrected.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The turn completed, carrying the retrieved record or the outcome "
                        + "message, any field-level errors and the echoed navigation context."),
        @ApiResponse(responseCode = "400",
                description = "The body exceeded the widths the delete map declares, or carried a "
                        + "component that map does not declare - including a credential, which that "
                        + "map does not have."),
        @ApiResponse(responseCode = "401", description = "No bearer session was presented."),
        @ApiResponse(responseCode = "403",
                description = "The session does not carry the administrative authority.")})
    public ResponseEntity<UserResponse> deleteUser(
            @Validated({Default.class, UserRequest.DeleteOperation.class})
            @RequestBody final UserRequest request,
            final Authentication authentication) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);
        String outcome = OUTCOME_FAILED;
        try {
            final UserResponse body = this.userContractAdapter.toResponse(
                    this.userManagementService.deleteUser(
                            this.userContractAdapter.toCommand(request, authentication)),
                    authentication);
            outcome = outcomeOf(body);
            logCompletedTurn(DELETE_TRANSACTION_ID, outcome);
            return ResponseEntity.ok(body);
        } finally {
            recordTurn(sample, METRIC_DELETE_TURN, DELETE_TRANSACTION_ID, outcome);
        }
    }

    /**
     * Records the elapsed time of one turn under its own timer name.
     *
     * <p>Shared by all four operations because the closing sequence is identical for each: stop the
     * timer under the operation's own name and tag it with the bounded outcome. Four copies of that
     * would be four opportunities for one of them to start tagging something it should not. Composing
     * the response is the caller's, and so is the debug record, which each operation writes for itself
     * on the path that produced a screen.
     *
     * <p>Every turn is recorded, including one that raised. Each caller invokes this from its {@code
     * finally} arm, so an exception propagating out of the boundary still stops the sample, carrying the
     * failed label the caller seeded before the attempt. No raising turn is therefore folded into a
     * success timing, and none is silently omitted either.
     *
     * @param sample the timing sample started at the head of the turn
     * @param metricName the timer name for this operation
     * @param transactionId the legacy transaction identifier this operation serves
     * @param outcome the bounded outcome label, one of the four this class declares
     */
    private void recordTurn(final Timer.Sample sample, final String metricName,
            final String transactionId, final String outcome) {
        sample.stop(Timer.builder(metricName)
                .description("Elapsed time of one CardDemo administrative user turn, transaction "
                        + transactionId)
                .tag(TAG_OUTCOME, outcome)
                .register(this.meterRegistry));
    }

    private static void logCompletedTurn(final String transactionId, final String outcome) {
        // Both values are compile-time constants of this class, never caller-supplied text, so this
        // record cannot be made to carry a line break, a credential or any identifying value.
        LOG.debug("Administrative user turn completed: transaction={} outcome={}", transactionId,
                outcome);
    }

    /**
     * Reduces a composed screen to one of three bounded outcome labels.
     *
     * <p>Read from the response's two boolean indicators and from whether it carries field-level errors
     * - never from its message text, which on three of these screens embeds the eight-character
     * identifier of the affected record and would therefore make the tag both unbounded and
     * disclosing.
     *
     * <p>The three labels are not a re-statement of the transaction's rules and are not consumed by
     * anything: they exist so that a rise in refusals is visible without a per-request label. A turn
     * that completed a change reads as succeeded; a turn the transaction refused, at field level or as
     * a whole, reads as rejected; anything else - a retrieval, a page, an unsupported key - reads as
     * presented.
     *
     * @param body the screen the turn produced
     * @return one of {@link #OUTCOME_SUCCEEDED}, {@link #OUTCOME_REJECTED} or
     *         {@link #OUTCOME_PRESENTED}
     */
    private static String outcomeOf(final UserResponse body) {
        if (body.actionSucceeded()) {
            return OUTCOME_SUCCEEDED;
        }
        if (body.generalError() || body.hasFieldErrors()) {
            return OUTCOME_REJECTED;
        }
        return OUTCOME_PRESENTED;
    }
}
