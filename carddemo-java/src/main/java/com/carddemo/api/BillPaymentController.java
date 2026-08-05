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

import com.carddemo.api.dto.BillPaymentRequest;
import com.carddemo.api.dto.BillPaymentResponse;
import com.carddemo.service.BillPaymentService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface of transaction {@code CB00}, the bill-payment screen that settles an account balance in
 * full and posts one transaction recording the settlement.
 *
 * <p><strong>What this class does.</strong> It receives one request, hands the four transmitted values
 * to {@link BillPaymentService}, records how long the turn took, and projects the returned value onto
 * the published response contract. That is the whole of it. There is no rule of its own here: no
 * emptiness test, no confirmation interpretation, no balance comparison, no account or cross-reference
 * lookup, no identifier minting, no timestamp construction, no arithmetic, no persistence and no
 * message text. Every one of those lives in the service, which is what lets the entire transaction be
 * exercised without a servlet and what keeps this class from becoming the place where a second,
 * divergent copy of the payment rules accumulates.
 *
 * <p><strong>Why the projection is here rather than in an adapter.</strong> Several sibling screens in
 * this package project through a dedicated adapter component, so the absence of one here invites the
 * question. The projection this boundary needs is a field-for-field transfer between two immutable
 * records whose components already correspond one to one, and it makes no decision: it selects no
 * value, derives no value, formats no value and compares no value. An adapter would add a type and an
 * injection point without adding a decision for either to own. The adapters that do exist earn their
 * keep by holding a choice - which of several outcomes maps onto which contract shape, or which
 * protected values a response may carry - and this screen has no such choice to hold.
 *
 * <p><strong>Why the response status is always {@code 200}.</strong> Every outcome the legacy program
 * can reach is a screen it successfully composed and sent, and that includes each of its twelve
 * refusals: an empty account identifier, an unacceptable confirmation character, a non-positive
 * balance, a missing or unreadable account, a missing or unreadable cross-reference, a missing or
 * unreadable transaction, a duplicate key and a failed insert or update. The transaction completed on
 * the mainframe in all of those cases and it completes here, so the outcome is read from the body
 * exactly as an operator read it from the screen. Reporting a refused payment as a client or server
 * error would be a modernising change to an externally observable contract, and it would additionally
 * leak which refusal occurred to anything that inspects only the status line. Three statuses other
 * than {@code 200} are still reachable, and none of them is a screen the legacy could compose: a
 * request whose values exceed the widths the symbolic map declares is rejected by declarative
 * validation before this method runs, an unauthenticated caller never reaches the method at all, and a
 * concurrent modification of the account is a conflict the legacy file rules had no way to express.
 * All three are turned into a response body by the shared handler, not here.
 *
 * <p><strong>Why nothing is forwarded.</strong> The legacy program ends a turn either by re-arming
 * itself, so the terminal's next transmission returns to the same program, or by transferring control
 * to another program outright - to the caller it came from, or to the main menu when it came from
 * nowhere. Neither has a transport equivalent that preserves the contract: a server-side forward would
 * hide the destination from the client, and a redirect would turn one logical turn into two round
 * trips and would put screen state into a location header. The destination therefore travels in the
 * body as opaque route text alongside the navigation state the client echoes back on its next call, so
 * the client drives the conversation and each endpoint stays independently callable and independently
 * testable.
 *
 * <p><strong>Statelessness, and what that costs the caller.</strong> Nothing is retained between
 * requests: no session, no conversation store, no cached account, no partially assembled payment and
 * no server-side notion of which screen the operator is on. Everything the legacy carried in its
 * communication area arrives in the request and leaves in the response. Two consequences follow and
 * both are legacy behaviour rather than concessions. The confirmation character is not remembered, so
 * an affirmative answer must arrive on the very turn that settles - the service resets its own
 * confirmation flag at the head of each turn, exactly as the program does. And a caller that submits
 * the same request twice gets whatever the second submission is worth against the state the first one
 * left, which is the definition of idempotence the service's contract provides: the first affirmative
 * turn drives the balance to zero, so a replayed affirmative turn finds nothing to settle and is
 * refused by the non-positive-balance rule rather than settling twice. This class adds no
 * idempotency key, no request fingerprint, no replay cache and no retry, because inventing any of them
 * would change the observable contract.
 *
 * <p><strong>Authorization.</strong> The route is an online-data operator surface. Both user types the
 * estate declares retain access, because the sign-on program sends them into one of the two operator
 * menus, while an unrelated authenticated principal does not acquire account-wide payment authority
 * merely by carrying a credential. The user-security record declares no account ownership relation, so
 * none is inferred from the caller-supplied account identifier. The route is neither public nor
 * administrator-only; it is protected by the closed operator-authority set in the security chain.
 *
 * <p><strong>Instrumentation.</strong> One timer per turn, tagged with two bounded values read
 * straight off the returned outcome, following the naming the rest of the module uses. No latency,
 * throughput, heap or timeout figure is stated anywhere in this class: the module has no legacy
 * performance baseline to compare against, so the instrumentation exists to establish the first one
 * rather than to assert a threshold.
 *
 * <p><strong>Diagnostics.</strong> The one statement here is parameterized, is emitted at debug level,
 * and carries only the route, the two outcome flags and the elapsed-turn tags. It names no account
 * identifier, no card number, no transaction identifier, no balance and no confirmation character. The
 * request record withholds its own identifying components from its diagnostic rendering, so it could
 * be interpolated safely, but it is not interpolated at all - a diagnostic that never receives a value
 * cannot disclose one however the value's owner is later changed.
 *
 * <p>Provenance: {@code app/cbl/COBIL00C.cbl}, its symbolic map {@code app/cpy-bms/COBIL00.CPY}, its
 * mapset {@code app/bms/COBIL00.bms} and the resource definition {@code app/csd/CARDDEMO.CSD}, whose
 * transaction definition binds {@code CB00} to the bill-payment program. All four are read-only
 * reference at checkout commit {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. They are cited by member name and line
 * number only; no COBOL statement, screen definition, picture clause or record offset is reproduced
 * here, and nothing under {@code app/} is read at run time.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping(BillPaymentController.BILL_PAYMENT_PATH)
public class BillPaymentController {

    /**
     * The address of the bill-payment surface.
     *
     * <p>Declared on the controller rather than in configuration so that this mapping has exactly one
     * authority. The direction of that dependency is deliberate and matches the sign-on surface:
     * configuration is permitted to read a path from the boundary, and the boundary is not permitted to
     * read one from configuration, because the reverse would invert the module's layering.
     *
     * <p>The final segment is the same text the navigation vocabulary publishes as this screen's route
     * value, so the destination a response nominates and the address a client calls to reach it read
     * identically. It sits under the shared interface prefix and deliberately not under the
     * administrative prefix the security chain gates on an administrative authority. Instead, the
     * security chain reads this constant for its online-data operator rule, so mapping and entitlement
     * cannot drift. It also shadows neither the management endpoints nor the published contract document,
     * both of which live outside this prefix.
     */
    public static final String BILL_PAYMENT_PATH = "/api/bill-payment";

    /** Diagnostic channel. */
    private static final Logger LOG = LoggerFactory.getLogger(BillPaymentController.class);

    /**
     * Timer name for one bill-payment turn, following the naming the online and batch tiers share.
     *
     * <p>One timer for the whole turn rather than one per stage, because the turn is the unit the
     * legacy transaction had and the unit an operator waited on.
     */
    private static final String METRIC_BILL_PAYMENT_TURN = "carddemo.online.billpayment.turn";

    /**
     * Tag naming whether the operator had confirmed when the turn ended.
     *
     * <p>Bounded by construction: the value is the name of a two-constant enumeration the service
     * publishes, so it cannot become a high-cardinality label however the request varies.
     */
    private static final String TAG_CONFIRMATION = "confirmation";

    /**
     * Tag naming whether the turn actually settled the balance.
     *
     * <p>Distinct from the confirmation tag on purpose, and the pair is the reason both exist. An
     * affirmative answer only selects the settlement path; every step on that path can still fail, so
     * a confirmed turn that settled and a confirmed turn that was refused are operationally different
     * events and a single tag would merge them. The value is a rendered boolean, so the two tags
     * together admit at most four series.
     */
    private static final String TAG_PAYMENT_ACCEPTED = "paymentAccepted";

    /** Tag separating a completed boundary call from one that raised. */
    private static final String TAG_OUTCOME = "outcome";

    private static final String OUTCOME_COMPLETED = "completed";

    private static final String OUTCOME_FAILED = "failed";

    private static final String CONFIRMATION_UNRESOLVED = "UNRESOLVED";

    /**
     * The bill-payment transaction, and the only collaborator that holds a rule.
     *
     * <p>Constructor injected and final. This is the sole domain collaborator: no repository, no
     * navigation service, no message catalogue, no codec and no clock is injected here, because every
     * value this boundary needs from any of them already reaches it inside the outcome the service
     * returns.
     */
    private final BillPaymentService billPaymentService;

    /**
     * Registry the turn timer is registered against.
     *
     * <p>Injected because the module instruments through the registry directly rather than through a
     * declarative timing annotation. That is not a preference: an annotation of that kind is realised
     * by an aspect bean, the module registers none and declares no aspect-weaving dependency, so an
     * annotation here would compile, read as though it were measuring something, and record nothing.
     */
    private final MeterRegistry meterRegistry;

    /**
     * The only permitted converter between the wire navigation record and the service-owned state.
     *
     * <p>The transaction takes the echoed state, and returns it, in the form the service layer owns,
     * because nothing may depend upward on {@code api.dto}. This collaborator is where both crossings
     * happen, and it converts positionally: none of the sixteen fixed-width, blank-significant members
     * is trimmed, padded, defaulted or reconciled on the way through, so what the client echoed is what
     * the transaction receives and what the transaction produced is what the response carries.
     */
    private final ScreenStateAdapter screenStateAdapter;

    /**
     * Creates the controller over its collaborators.
     *
     * <p>All three arguments are required. Rejecting an absent collaborator at construction rather than
     * at the first request means a misconfigured context fails while it is starting, where the failure
     * names the missing bean, instead of failing later inside a payment.
     *
     * @param billPaymentService the bill-payment transaction; must not be {@code null}
     * @param screenStateAdapter the converter between the wire navigation record and the service-owned
     *     state; must not be {@code null}
     * @param meterRegistry the metrics registry the turn timer is registered against; must not be
     *     {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public BillPaymentController(final BillPaymentService billPaymentService,
                                 final ScreenStateAdapter screenStateAdapter,
                                 final MeterRegistry meterRegistry) {
        this.billPaymentService = Objects.requireNonNull(billPaymentService,
                "billPaymentService must not be null");
        this.screenStateAdapter = Objects.requireNonNull(screenStateAdapter,
                "screenStateAdapter must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    /**
     * Serves one turn of the bill-payment screen.
     *
     * <p>The body is the whole of the request: the account whose balance is to be settled, the single
     * confirmation character, the attention key the operator pressed, and the navigation state the
     * client echoes back in place of the communication area the legacy program received and returned.
     * All four are handed to the service unaltered - not trimmed, not padded, not case folded and not
     * defaulted - because each of them has a legitimately absent or blank state that the service's own
     * ordered checks are what report.
     *
     * <p>The confirmation character in particular is passed through as typed. Both letter cases of both
     * acceptable answers are accepted by the service, an absent character is the ordinary first pass
     * that displays the balance without settling it, and any other character has to survive intact so
     * that the refusal can name the two acceptable values back to the operator. Case folding or
     * mapping the character onto a two-valued flag here would erase the distinction between three of
     * those four outcomes.
     *
     * <p>Exactly one call is made into the service, and it is the call that performs the entire turn.
     * The declarative validation that runs before this method is reached bounds each transmitted value
     * to the width its screen field declares and cascades into the echoed navigation state, and it does
     * nothing else: it applies no presence rule, no pattern and no numeric range, so it cannot pre-empt
     * the service's ordered, message-bearing checks or replace their exact text with its own.
     *
     * @param request the transmitted screen, the attention key and the echoed navigation state; never
     *     {@code null}, because a request with no body is refused before this method is reached
     * @return the screen the turn produces, always with status {@code 200}
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Pay an account balance in full",
            description = "One turn of legacy transaction CB00. Settles the whole of the account's "
                    + "outstanding balance and posts one transaction recording it; there is no partial "
                    + "payment and no caller-supplied amount. Answers 200 for every outcome the legacy "
                    + "screen could compose, including each of its refusals: read the outcome from the "
                    + "body, where paymentAccepted reports whether the balance was actually settled "
                    + "and errorMessage carries the screen text verbatim. An absent confirmation "
                    + "character displays the balance without settling it, which is how the screen is "
                    + "first filled.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The turn completed. The body carries the pre-payment balance, the "
                        + "screen message, the settlement and error flags, the field the cursor "
                        + "returns to, the next route and the navigation context to echo back. A "
                        + "settled turn additionally carries the identifier of the transaction it "
                        + "posted and returns the echoed screen fields blank, exactly as the legacy "
                        + "screen does."),
        @ApiResponse(responseCode = "400",
                description = "The request exceeded the widths the bill-payment map declares."),
        @ApiResponse(responseCode = "401",
                description = "The request carried no established identity."),
        @ApiResponse(responseCode = "403",
                description = "The authenticated principal is not an approved online-data operator."),
        @ApiResponse(responseCode = "409",
                description = "Another writer changed the account between this turn's read and its "
                        + "write, so nothing was settled and the turn may be retried.")})
    public ResponseEntity<BillPaymentResponse> payBill(
            @Valid @RequestBody final BillPaymentRequest request) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);
        String outcome = OUTCOME_FAILED;
        String confirmation = CONFIRMATION_UNRESOLVED;
        String paymentAccepted = Boolean.FALSE.toString();
        try {
            final BillPaymentService.BillPaymentResult result =
                    this.billPaymentService.processBillPayment(
                            new BillPaymentService.BillPaymentScreenInput(
                                    request.accountId(),
                                    request.confirm(),
                                    request.keyAction(),
                                    this.screenStateAdapter.toNavigationState(
                                            request.navigationContext())));
            final BillPaymentResponse response = toResponse(result);

            confirmation = result.confirmationState().name();
            paymentAccepted = Boolean.toString(result.paymentAccepted());
            outcome = OUTCOME_COMPLETED;

            LOG.debug("Bill-payment turn served: route={} paymentAccepted={} generalError={}",
                    result.route().getRouteValue(), result.paymentAccepted(), result.errorFlag());
            return ResponseEntity.ok(response);
        } finally {
            recordTurn(sample, outcome, confirmation, paymentAccepted);
        }
    }

    /**
     * Records the elapsed time of one turn, tagged by the two facts that distinguish its outcomes.
     *
     * <p>Tagged rather than counted as one aggregate because the operationally interesting question is
     * the shape of the mix: a rise in unconfirmed turns and a rise in confirmed-but-refused turns mean
     * different things and call for different responses. Both tag values come straight off the returned
     * outcome - one is the name of a two-constant enumeration and the other a rendered boolean - so the
     * label set is bounded at four series and no request value can widen it.
     *
     * <p>The sample is stopped only on the path that produced an outcome. A turn that raised a conflict
     * instead of returning one records nothing here, which is deliberate: every tag this timer carries
     * is read from an outcome, so there is no honest value to record for a turn that reached none, and
     * the request itself is already timed by the server's own request metrics.
     *
     * @param sample the timing sample started at the head of the turn
     * @param result the outcome the turn reached
     */
    private void recordTurn(final Timer.Sample sample, final String outcome,
            final String confirmation, final String paymentAccepted) {
        sample.stop(Timer.builder(METRIC_BILL_PAYMENT_TURN)
                .description("Elapsed time of one CardDemo bill-payment turn, transaction CB00")
                .tag(TAG_OUTCOME, outcome)
                .tag(TAG_CONFIRMATION, confirmation)
                .tag(TAG_PAYMENT_ACCEPTED, paymentAccepted)
                .register(this.meterRegistry));
    }

    /**
     * Projects one turn's outcome onto the published response contract.
     *
     * <p>A field-for-field transfer and nothing more. No value is selected, derived, compared,
     * defaulted, trimmed, padded, case folded, scaled, rounded or formatted, so every value the service
     * produced crosses this boundary byte for byte - which is what lets each refusal text, the
     * confirmation prompt, the shared invalid-key text with all fifty of its characters, and the
     * assembled success text with its two consecutive spaces reach a client exactly as the screen
     * carried them, and what lets a settled turn's blank echoed fields stay blank.
     *
     * <p>Three of the transfers are worth naming because a neighbouring component would have been the
     * wrong source.
     *
     * <ul>
     *   <li><strong>The balance.</strong> The outcome carries the pre-payment balance twice: once as a
     *       fixed-scale decimal and once as the edited fixed-width characters the screen field held.
     *       The decimal is taken. The edited form is terminal geometry, and publishing it would put a
     *       presentation decision and a padding rule into a machine contract. The value is already at
     *       the scale of the record field it came from, so nothing here rescales it - the response's own
     *       constructor confirms that shape and refuses a value that lacks it.</li>
     *   <li><strong>The message.</strong> The outcome carries both the program's wider message work
     *       area and the narrower outbound screen field the send fills from it. The screen field is
     *       taken, because it is the narrower of the two and therefore the binding external width, and
     *       because it is what an operator actually saw.</li>
     *   <li><strong>The account balance after settlement.</strong> The outcome also carries the account
     *       as the turn left it, whose balance is zero once a payment completes. It is deliberately not
     *       published: the screen has one balance field, the legacy never wrote a post-settlement figure
     *       to it, and the amount settled is always the whole balance, so a second figure would be
     *       either zero or a duplicate and would imply a partial-payment capability the transaction
     *       does not have.</li>
     * </ul>
     *
     * <p>The route travels as the opaque text the navigation vocabulary publishes for it. No route
     * table, enumeration or dispatch appears here, and nothing is forwarded: the client reads the
     * destination from the body and drives the next call itself.
     *
     * @param result the outcome to project; never {@code null}, because the service's contract
     *     guarantees an outcome or a raised conflict
     * @return the response body, never {@code null}
     */
    private BillPaymentResponse toResponse(final BillPaymentService.BillPaymentResult result) {
        return new BillPaymentResponse(
                result.screen().accountId(),
                result.screenBalance(),
                result.screen().confirm(),
                postedTransactionId(result),
                result.header().transactionName(),
                result.header().title01(),
                result.header().currentDate(),
                result.header().programName(),
                result.header().title02(),
                result.header().currentTime(),
                result.header().errorMessage(),
                result.paymentAccepted(),
                result.errorFlag(),
                result.focusField(),
                result.route().getRouteValue(),
                this.screenStateAdapter.toNavigationContext(result.navigationContext()));
    }

    /**
     * Reads the identifier of the transaction the turn posted, or nothing when it posted none.
     *
     * <p>The guard is required rather than defensive: the outcome carries no transaction on every turn
     * that reached no settlement, which is every unconfirmed turn, every refused turn, every cleared
     * screen, every transfer and every unmapped key. An absent identifier is therefore an ordinary
     * state and not an error, and it is reported as absent so that a client cannot mistake a refused
     * payment for a completed one.
     *
     * <p>Nothing here generates, increments, parses, pads or reformats an identifier. The service mints
     * it from the highest existing key, which is why the first identifier issued against an empty table
     * carries its full width of leading zeros; carrying it as text is what preserves them.
     *
     * @param result the outcome to read; never {@code null}
     * @return the identifier of the posted transaction, or {@code null} when the turn posted none
     */
    private static String postedTransactionId(final BillPaymentService.BillPaymentResult result) {
        final BillPaymentService.TransactionProjection posted = result.transaction();
        return posted == null ? null : posted.tranId();
    }
}
