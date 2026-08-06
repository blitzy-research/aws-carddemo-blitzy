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

import com.carddemo.api.dto.SignOnRequest;
import com.carddemo.api.dto.SignOnResponse;
import com.carddemo.service.AuthenticationService;
import com.carddemo.util.ApiRoutePaths;
import com.carddemo.util.SessionTokenIssuer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface of transaction {@code CC00}, the sign-on screen.
 *
 * <p><strong>What this class does and does not do.</strong> It receives a request, hands it to
 * {@link AuthenticationService}, projects the answer through {@link SignOnContractAdapter}, attaches a
 * session to an admitted turn, and records how long the turn took. It contains no rule of its own: no
 * presence test, no comparison, no routing decision, no message text. Every one of those lives in the
 * service or the adapter, which is what lets both be exercised without a servlet and what keeps this
 * class from becoming the place where a second, divergent copy of the sign-on rules accumulates.
 *
 * <p><strong>The rules that live elsewhere, listed so that no later edit migrates one of them here.</strong>
 * Each of the following is externally observable, each belongs to the component named against it, and
 * each would be broken by an apparently harmless convenience added at this boundary:
 *
 * <ul>
 *   <li><em>The blank cascade is ordered, and the identifier is tested first.</em> A submission with
 *       both fields empty answers the identifier prompt only, never the credential prompt and never
 *       both. {@link AuthenticationService} runs that cascade, which is also why
 *       {@link SignOnRequest} carries no presence constraint: Bean Validation evaluates constraints in
 *       an unspecified order and would report two violations where the legacy reports one.</li>
 *   <li><em>Both submitted values are folded to upper case unconditionally.</em> The legacy fold sits
 *       outside the end of the cascade, so it runs on every submission including the rejected ones. It
 *       is part of the authentication algorithm, so it belongs to the service. This class therefore
 *       hands over exactly the characters the client sent - it does not upper-case, lower-case, trim,
 *       strip, pad or canonicalise either value, and it does not short-circuit a submission it judges
 *       empty.</li>
 *   <li><em>The five direct screen texts, and the two shared ones, are declared once.</em> The five
 *       the transaction composes itself are constants of {@link SignOnResponse}; the pair it draws
 *       from the shared catalogue are fifty-character values held untrimmed by the message catalogue,
 *       and a separate forty-character courtesy text in a different copybook is a different value that
 *       must never be merged with them. {@link SignOnContractAdapter} chooses between them. No message
 *       literal appears in this file.</li>
 *   <li><em>A failed credential comparison is not a general error.</em> The legacy program composes a
 *       message and moves the cursor on that path without raising its error switch, while the
 *       not-found and cannot-verify paths do raise it. The flag is therefore carried explicitly from
 *       the path the service took and is never inferred here from a message being present.</li>
 *   <li><em>Routing is a two-way split with no third branch.</em> The administrator code reaches the
 *       administrative menu and every other stored value, including the administrator letter in lower
 *       case, reaches the user main menu. The navigation service owns that vocabulary; this class
 *       neither resolves nor rewrites it.</li>
 * </ul>
 *
 * <p><strong>Why the route constant comes from the neutral route contract.</strong> The security rules
 * exempt exactly one path from authentication, and that exemption and this mapping have to name one
 * authority or a mapping typo becomes an unauthenticated surface. Both this boundary and configuration
 * therefore read {@link ApiRoutePaths#SIGN_ON_PATH}; neither imports the other, so the package dependency
 * remains downward in both cases.
 *
 * <p><strong>Why the token travels in a header.</strong> The screen contract declares fifteen components
 * and none of them is a credential. Placing a token in the body would add a sixteenth to a contract that
 * is frozen against the symbolic map, so the session is issued as a standard bearer credential in the
 * response header instead. An unsuccessful turn is issued nothing at all, which is the property worth
 * stating: the header is present only when the credential verified.
 *
 * <p><strong>Why the response status is always {@code 200}.</strong> Every one of the nine outcomes is a
 * screen the legacy program successfully composed and sent, including the rejections. The transaction
 * completed on the mainframe in all of them, so it completes here, and the outcome is read from the
 * body exactly as an operator read it from the screen. Reporting a rejected sign-on as a client or server
 * error would be a modernising change to an externally observable contract, and it would also leak which
 * of the rejections occurred to anything that inspects only the status line. A malformed request - one
 * whose fields exceed the widths the map declares - is a different matter and is rejected by declarative
 * validation before this method runs, which the shared failure adapter turns into a {@code 400}.
 *
 * <p><strong>Why the turn is timed the way the batch tier times a step.</strong> The elapsed time is
 * taken with an explicit sample rather than an annotation, which is this module's established practice
 * and needs no aspect, no proxy and therefore no reflection. The sample is stopped in a {@code finally}
 * so that an unforeseen turn failure is still measured. Credential-store failures are ordinary screen
 * outcomes translated by the service and therefore receive the bounded {@code UNABLE_TO_VERIFY} tag rather
 * than escaping through this failure path. No latency, throughput or memory figure is asserted anywhere
 * in this class: the meter establishes the baseline rather than testing against one.
 *
 * <p>Provenance: {@code app/cbl/COSGN00C.cbl} and {@code app/csd/CARDDEMO.CSD}, whose transaction
 * definition binds {@code CC00} to the sign-on program, read as read-only reference at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is transcribed.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping(AuthController.SIGN_ON_PATH)
public final class AuthController {

    /**
     * The one route reachable without a credential, because it is the route that issues them.
     *
     * <p>Declared here so that this mapping and the security rule which exempts it name one authority. A
     * controller mapped to any other path would be caught by the catch-all authentication rule and would
     * fail closed, which is the safe direction for that mistake to fail in.
     */
    public static final String SIGN_ON_PATH = ApiRoutePaths.SIGN_ON_PATH;

    /** Diagnostic channel. */
    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);

    /** Timer name for one sign-on turn, following the batch tier's naming. */
    private static final String METRIC_SIGN_ON_TURN = "carddemo.online.signon.turn";

    /** Description published alongside the turn timer. */
    private static final String METRIC_SIGN_ON_TURN_DESCRIPTION =
            "Elapsed time of one CardDemo sign-on turn, transaction CC00";

    /** Tag naming which of the nine outcomes the turn reached. */
    private static final String TAG_OUTCOME = "outcome";

    /**
     * Outcome recorded for a turn that reached no decision because it failed in flight.
     *
     * <p>Spelled as the batch tier spells it, so one vocabulary covers both tiers. It is a fixed
     * constant rather than anything derived from the failure, which is what keeps the tag bounded: the
     * complete set of values this timer can ever carry is the eight decision names plus this one.
     */
    private static final String OUTCOME_FAILED = "FAILED";

    /** The sign-on transaction. */
    private final AuthenticationService authenticationService;

    /** Projects a turn onto the published contract. */
    private final SignOnContractAdapter signOnContractAdapter;

    /** Issues the session an admitted turn earns. */
    private final SessionTokenIssuer sessionTokenIssuer;

    /** Registry the turn timer is registered against. */
    private final MeterRegistry meterRegistry;

    /**
     * Creates the controller over its collaborators.
     *
     * @param authenticationService the sign-on transaction
     * @param signOnContractAdapter the contract projection
     * @param sessionTokenIssuer the session issuer
     * @param meterRegistry the metrics registry
     */
    public AuthController(final AuthenticationService authenticationService,
                          final SignOnContractAdapter signOnContractAdapter,
                          final SessionTokenIssuer sessionTokenIssuer,
                          final MeterRegistry meterRegistry) {
        this.authenticationService = Objects.requireNonNull(authenticationService,
                "authenticationService must not be null");
        this.signOnContractAdapter = Objects.requireNonNull(signOnContractAdapter,
                "signOnContractAdapter must not be null");
        this.sessionTokenIssuer = Objects.requireNonNull(sessionTokenIssuer,
                "sessionTokenIssuer must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    /**
     * Serves the first entry to the sign-on screen.
     *
     * <p>The source tests the communication-area length before it evaluates the attention key. In the REST
     * contract an HTTP GET with no request body is that distinct state: it returns the cleared screen with
     * the user-id field focused and cannot be mistaken for an absent or unmapped key.
     *
     * @return the blank first-entry screen
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirements
    @Operation(summary = "Initialize the CardDemo sign-on screen",
            description = "The first entry to legacy transaction CC00. Returns the cleared sign-on "
                    + "screen with USERID focused before any attention key is evaluated.")
    @ApiResponse(responseCode = "200",
            description = "The blank first-entry screen, with no bearer session.")
    public ResponseEntity<SignOnResponse> initialEntry() {
        return serveTurn(this.authenticationService::initialEntry);
    }

    /**
     * Serves one submitted turn of the sign-on screen.
     *
     * <p>Binds the request, delegates once, and answers what came back. The request reaches the service
     * exactly as the client sent it, because every rule that would alter it belongs to the service.
     *
     * <p>The turn is timed and the measurement is tagged by the outcome it reached, rather than counted
     * as one aggregate, because the operationally interesting question is the shape of the mix - a rise
     * in the not-found outcome and a rise in the wrong-secret outcome mean different things. The tag is
     * the decision's own enumerated name, or the fixed failure constant when the turn reached no
     * decision at all, so it cannot become a high-cardinality label.
     *
     * @param request the operator's entry and the attention key they pressed
     * @return the screen the turn produces, carrying a bearer session when the credential verified
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirements
    @Operation(summary = "Sign on to CardDemo",
            description = "One turn of legacy transaction CC00. Answers 200 for every outcome the "
                    + "legacy screen could compose, including a rejection: the outcome is read from the "
                    + "body, and an admitted turn additionally carries a bearer session in the "
                    + "Authorization response header.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The turn completed. An admitted turn carries a route, a navigation "
                        + "context and an Authorization header; a rejected turn carries the screen "
                        + "message and the field the cursor returns to."),
        @ApiResponse(responseCode = "400",
                description = "The request exceeded the widths the sign-on map declares.")})
    public ResponseEntity<SignOnResponse> signOn(
            @Valid @RequestBody(required = false) final SignOnRequest request) {
        if (request == null) {
            return initialEntry();
        }
        return serveTurn(() -> this.authenticationService.handle(
                request.keyAction(), request.userId(), request.password()));
    }

    /**
     * Times, projects and answers one already-selected sign-on operation.
     *
     * @param operation service operation for the requested turn
     * @return the published response
     */
    private ResponseEntity<SignOnResponse> serveTurn(
            final Supplier<AuthenticationService.SignOnScreen> operation) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);
        // Assumed failed until the turn has been served end to end, so a turn that throws is recorded
        // as a failure rather than as whichever decision it had reached before it threw.
        String outcome = OUTCOME_FAILED;
        try {
            final AuthenticationService.SignOnScreen screen = operation.get();
            final ResponseEntity<SignOnResponse> answer = answerFor(screen);
            outcome = screen.decision().name();
            return answer;
        } finally {
            sample.stop(Timer.builder(METRIC_SIGN_ON_TURN)
                    .description(METRIC_SIGN_ON_TURN_DESCRIPTION)
                    .tag(TAG_OUTCOME, outcome)
                    .register(this.meterRegistry));
        }
    }

    /**
     * Projects a served turn onto the published contract and attaches a session where one is earned.
     *
     * <p>The projection itself is the adapter's, and the branch below reads a decision the service has
     * already taken rather than taking one: whether a session is earned is exactly whether the service
     * admitted the operator, and no other property of the turn is consulted.
     *
     * <p><strong>An issuer that refuses is not caught here, deliberately.</strong> The issuer refuses when
     * the credential record has gone, or no longer carries the role being minted, between the service's
     * verification and the mint - a concurrent administrative change to that record. Answering the turn as
     * a success without a session would leave a client reading an admitted turn and proceeding
     * unauthenticated, so the refusal is allowed to reach the boundary's failure handling, which renders
     * the module's own neutral summary and records only a failure chain. Nothing about the condition, and
     * nothing about the identity, reaches the caller.
     *
     * @param screen the turn the service served
     * @return the answer, carrying a bearer session only when the operator was admitted
     */
    private ResponseEntity<SignOnResponse> answerFor(final AuthenticationService.SignOnScreen screen) {
        final SignOnResponse body = this.signOnContractAdapter.toResponse(screen);
        if (!screen.decision().isAdmitted()) {
            return ResponseEntity.ok(body);
        }
        // Only an admitted turn is issued a session. The service's own invariant guarantees that an
        // admitted turn names the operator, the resolved authority and the stored type code, so no
        // argument can be absent. All three are passed: the resolved authority decides what the session
        // permits, and the stored code is what a later currency check reconciles against the record. They
        // are not the same value for a record whose code the estate never declared, and requiring them to
        // be was what refused a session to an operator this very turn had just admitted.
        final String token = this.sessionTokenIssuer.issue(
                screen.userId(), screen.userType(), screen.userTypeCode());
        LOG.debug("Sign-on session issued: outcome=issued");
        return ResponseEntity.ok()
                .header(HttpHeaders.AUTHORIZATION, SessionTokenIssuer.BEARER_PREFIX + token)
                .body(body);
    }
}
