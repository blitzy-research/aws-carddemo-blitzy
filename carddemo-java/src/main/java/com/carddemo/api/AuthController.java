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
import com.carddemo.service.SessionTokenIssuer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * <p><strong>Why the route constant is declared here.</strong> The security rules exempt exactly one path
 * from authentication, and that exemption and this mapping have to name one authority or a mapping typo
 * becomes an unauthenticated surface. The constant therefore lives with the controller that serves it and
 * the security configuration reads it from here. The direction matters: configuration is permitted to
 * depend on the boundary, and the boundary is not permitted to depend on configuration, so declaring it
 * the other way round would invert the module's layering.
 *
 * <p><strong>Why the token travels in a header.</strong> The screen contract declares fifteen components
 * and none of them is a credential. Placing a token in the body would add a sixteenth to a contract that
 * is frozen against the symbolic map, so the session is issued as a standard bearer credential in the
 * response header instead. An unsuccessful turn is issued nothing at all, which is the property worth
 * stating: the header is present only when the credential verified.
 *
 * <p><strong>Why the response status is always {@code 200}.</strong> Every one of the seven outcomes is a
 * screen the legacy program successfully composed and sent, including the rejections. The transaction
 * completed on the mainframe in all seven cases, so it completes here, and the outcome is read from the
 * body exactly as an operator read it from the screen. Reporting a rejected sign-on as a client or server
 * error would be a modernising change to an externally observable contract, and it would also leak which
 * of the rejections occurred to anything that inspects only the status line. A malformed request - one
 * whose fields exceed the widths the map declares - is a different matter and is rejected by declarative
 * validation before this method runs, which the shared failure adapter turns into a {@code 400}.
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
public class AuthController {

    /**
     * The one route reachable without a credential, because it is the route that issues them.
     *
     * <p>Declared here so that this mapping and the security rule which exempts it name one authority. A
     * controller mapped to any other path would be caught by the catch-all authentication rule and would
     * fail closed, which is the safe direction for that mistake to fail in.
     */
    public static final String SIGN_ON_PATH = "/api/auth/signon";

    /** Diagnostic channel. */
    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);

    /** Timer name for one sign-on turn, following the batch tier's naming. */
    private static final String METRIC_SIGN_ON_TURN = "carddemo.online.signon.turn";

    /** Tag naming which of the seven outcomes the turn reached. */
    private static final String TAG_OUTCOME = "outcome";

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
     * Serves one turn of the sign-on screen.
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
    public ResponseEntity<SignOnResponse> signOn(@Valid @RequestBody final SignOnRequest request) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);

        final AuthenticationService.SignOnScreen screen = this.authenticationService.handle(
                request.keyAction(), request.userId(), request.password());
        final SignOnResponse body = this.signOnContractAdapter.toResponse(screen);

        recordTurn(sample, screen.decision());

        if (!screen.decision().isAdmitted()) {
            return ResponseEntity.ok(body);
        }
        // Only an admitted turn is issued a session. The service's own invariant guarantees that an
        // admitted turn names both the operator and the resolved role, so neither argument can be absent.
        final String token = this.sessionTokenIssuer.issue(screen.userId(), screen.userType());
        LOG.debug("Sign-on session issued: userId={}", screen.userId());
        return ResponseEntity.ok()
                .header(HttpHeaders.AUTHORIZATION, SessionTokenIssuer.BEARER_PREFIX + token)
                .body(body);
    }

    /**
     * Records the elapsed time of one turn, tagged by the outcome it reached.
     *
     * <p>Tagged by outcome rather than counted as one aggregate because the operationally interesting
     * question is the shape of the mix - a rise in the not-found outcome and a rise in the wrong-secret
     * outcome mean different things. The tag is the decision's own name, which is bounded and enumerated,
     * so it cannot become a high-cardinality label.
     *
     * @param sample the timing sample started at the head of the turn
     * @param decision the decision the turn reached
     */
    private void recordTurn(final Timer.Sample sample, final AuthenticationService.Decision decision) {
        sample.stop(Timer.builder(METRIC_SIGN_ON_TURN)
                .description("Elapsed time of one CardDemo sign-on turn, transaction CC00")
                .tag(TAG_OUTCOME, decision.name())
                .register(this.meterRegistry));
    }
}
