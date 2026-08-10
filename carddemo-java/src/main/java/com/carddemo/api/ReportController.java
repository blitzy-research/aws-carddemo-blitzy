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

import com.carddemo.api.dto.NavigationContext;
import com.carddemo.api.dto.ReportRequest;
import com.carddemo.api.dto.ReportResponse;
import com.carddemo.domain.enums.ReportPeriod;
import com.carddemo.domain.enums.UserType;
import com.carddemo.service.ReportRequestService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface of transaction {@code CR00}, the transaction-report request screen.
 *
 * <p><strong>What this class does.</strong> It receives one submitted screen, converts it through
 * {@link ReportContractAdapter}, hands it to {@link ReportRequestService} exactly once, projects the turn
 * back onto the published contract, and records how long the turn took. That is the whole of it.
 *
 * <p><strong>What this class deliberately does not do, and why the list is worth stating.</strong> It
 * resolves no report period, derives no month-to-date or year-to-date range, parses and reformats no date,
 * evaluates no confirmation character, authors no message text, assembles no job image, names no queue,
 * publishes no message and launches no job. Every one of those lives behind
 * {@link ReportRequestService} - which owns the ordered evaluation, the two-level date acceptance test and
 * the confirmation gate - or behind the queue bridge and card builder it injects. The reason is not
 * tidiness: the legacy program's behaviour is measured by parity fixtures that exercise the service, so a
 * fragment of it re-implemented here would be a second copy that no parity fixture measures and that could
 * drift away from the one that does. A controller with a rule in it is how an externally observable
 * contract quietly acquires two answers.
 *
 * <p><strong>Why the response status is always {@code 200}.</strong> Every outcome the screen can reach is
 * a screen the legacy program successfully composed and sent: the acknowledgement, the confirmation
 * prompt, the quoted-back invalid confirmation, each missing-date-part message, each invalid-date message,
 * the unmarked-report-type message and the queue-write failure. The transaction completed on the mainframe
 * in all of them, so the request completes here and the outcome is read from the body exactly as an
 * operator read it from the screen. Reporting a rejected turn as a client or server error would be a
 * modernising change to a frozen contract, and it would also leak which rejection occurred to anything
 * that inspects only the status line. A malformed request - one whose components exceed the widths the
 * symbolic map declares - is a different matter: declarative validation rejects it before this method
 * runs, and the shared failure policy turns that into a {@code 400}.
 *
 * <p><strong>Why a failed submission is still {@code 200}.</strong> The queue this transaction writes to
 * is defined ignore-on-error, so the legacy program reports a write failure to the operator and returns
 * control normally rather than abending. The service reproduces that by reporting the failure through its
 * returned value instead of raising, so there is no submission exception for this class to catch and none
 * to convert into a fatal request failure. Nothing here catches anything: an exception that did arise -
 * necessarily from somewhere other than the submission path - is left to the module's central exception
 * policy, which sanitises it rather than letting this class decide what to disclose.
 *
 * <p><strong>Why the identity comes from the credential and not from the request.</strong> The echoed
 * navigation record stands in for the legacy communication area and is client-supplied request state, so
 * the identity it carries is a claim rather than a fact. The authenticated principal is passed to the
 * adapter, which reconciles the record against it, and that is what stops a client-chosen identity
 * surviving a turn and coming back looking as though the server had asserted it. The routing members of
 * the record are echoed onward unchanged, because carrying them across the pseudo-conversation is exactly
 * what the legacy return does.
 *
 * <p><strong>There is no retry, idempotency or deadline protocol on this operation.</strong> The screen it
 * reproduces defines none, and the transient data queue behind it was defined with append disposition and
 * no notion of identity, so every confirmed submission is a submission of its own and publishes its own
 * card stream - including a second submission of a period an earlier turn already requested, which the
 * legacy region answered by appending the cards and running the job again. The deduplication identifier the
 * target queue requires is minted inside the bridge with a nonce and is never exposed, so no header is read
 * and none is returned; a caller that sends one anyway is answered exactly as one that does not. No state
 * is held here, no field is added to the screen DTO, and there is no session, redirect or server-side
 * dispatch.
 *
 * <p><strong>Nothing is logged here.</strong> The service already records the turn's shape - route,
 * resolved period, cards published and error state - and everything this class additionally holds is
 * either the operator's dates or the identity context, neither of which may be written to a log. A log
 * line that repeated what the service said would add noise; one that added what it withheld would add a
 * disclosure.
 *
 * <p>Stateless with final collaborators and no mutable field, so the singleton is safe for unsynchronised
 * concurrent use.
 *
 * <p>That there is no retry, idempotency or deadline protocol on this operation is recorded as decision
 * {@code DL-322} in {@code docs/decision-log.md}.
 *
 * <p>Provenance: {@code app/cbl/CORPT00C.cbl}, its symbolic map {@code app/cpy-bms/CORPT00.CPY} and mapset
 * {@code app/bms/CORPT00.bms}, and the {@code CR00} transaction definition at
 * {@code app/csd/CARDDEMO.CSD} line 409 which binds it to program {@code CORPT00C}; read as read-only
 * reference at commit SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement, screen declaration or job
 * card is transcribed.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping(ReportController.REPORT_REQUEST_PATH)
public class ReportController {

    /**
     * The address of transaction {@code CR00}.
     *
     * <p>Declared here, as the sign-on route is, so that the mapping and anything that reasons about the
     * route read one authority. It sits outside the administrative prefix on purpose: the route-to-role
     * table classifies {@code CR00} as reachable by any signed-on caller, which the security chain answers
     * with its closing catch-all rule, so a path beneath the administrative prefix would gate the report
     * screen to administrators and a path named among the anonymous surfaces would open it to callers with
     * no credential at all. It also shadows neither the management base path nor the interface-description
     * path, both of which the chain treats separately.
     */
    public static final String REPORT_REQUEST_PATH = "/api/reports/request";

    /** Timer name for one report-request turn, following the module's metric naming. */
    private static final String METRIC_REPORT_REQUEST_TURN = "carddemo.online.reportrequest.turn";

    /** Tag naming which outcome the turn reached. */
    private static final String TAG_OUTCOME = "outcome";

    /** Tag naming which reporting period the turn resolved. */
    private static final String TAG_PERIOD = "period";

    /** Outcome tag: the complete card stream reached the queue. */
    private static final String OUTCOME_SUBMITTED = "submitted";

    /** Outcome tag: the confirmation gate stopped the submission. */
    private static final String OUTCOME_CONFIRMATION_BLOCKED = "confirmation-blocked";

    /** Outcome tag: the turn raised the error flag, so the screen came back carrying a fault. */
    private static final String OUTCOME_ERROR = "error";

    /** Outcome tag: the screen was served without a submission and without a fault. */
    private static final String OUTCOME_SCREEN_SENT = "screen-sent";

    /** Outcome tag: the boundary raised before returning a screen. */
    private static final String OUTCOME_FAILED = "failed";

    /** Period tag used when the ordered evaluation resolved no report type at all. */
    private static final String PERIOD_NONE = "none";

    /** The report-request transaction. */
    private final ReportRequestService reportRequestService;

    /** The single lossless conversion between the transport contract and the service's turn types. */
    private final ReportContractAdapter reportContractAdapter;

    /** Registry the turn timer is registered against. */
    private final MeterRegistry meterRegistry;

    /**
     * Creates the controller over its collaborators.
     *
     * @param reportRequestService  the report-request transaction
     * @param reportContractAdapter the contract conversion
     * @param meterRegistry         the metrics registry
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public ReportController(final ReportRequestService reportRequestService,
                            final ReportContractAdapter reportContractAdapter,
                            final MeterRegistry meterRegistry) {
        this.reportRequestService = Objects.requireNonNull(reportRequestService,
                "reportRequestService must not be null");
        this.reportContractAdapter = Objects.requireNonNull(reportContractAdapter,
                "reportContractAdapter must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    /**
     * Serves one turn of the report-request screen.
     *
     * @param request        the marked report type, the operator-supplied date parts, the confirmation
     *                       character, the attention key that arrived and the echoed navigation state
     * @param authentication the established identity, supplied by the framework from the presented
     *                       credential; the route requires one, so it is absent only where this handler is
     *                       driven without a security chain
     * @return the screen the turn produces, carrying the resolved period, the summary message, the field
     *         the cursor returns to, and the route and navigation state the client drives its next call
     *         from
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Request a transaction report",
            description = "One turn of legacy transaction CR00. Marking the month-to-date, year-to-date or "
                    + "operator-range report type submits the corresponding request for printing once the "
                    + "confirmation has been answered; every other outcome - the confirmation prompt, an "
                    + "invalid confirmation, a missing or invalid date part, no report type marked, or a "
                    + "failed hand-over - answers 200 and is read from the body, exactly as an operator "
                    + "read it from the screen.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The turn completed. A submitted request carries the acknowledgement and "
                        + "the resolved period; any other outcome carries the screen message, the field "
                        + "the cursor returns to, and the marks and date parts as the turn leaves them. "
                        + "Every confirmed submission is a submission of its own and publishes its own "
                        + "card stream, exactly as the appending transient data queue this replaces did: "
                        + "there is no retry token, no idempotency header and no operator deadline, "
                        + "because the screen this reproduces defines none."),
        @ApiResponse(responseCode = "400",
                description = "The request exceeded the widths the report-request map declares. Nothing "
                        + "is published."),
        @ApiResponse(responseCode = "401",
                description = "No credential was presented, or the one presented did not verify.")})
    public ResponseEntity<ReportResponse> requestReport(
            @Valid @RequestBody final ReportRequest request,
            final Authentication authentication) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);
        String outcome = OUTCOME_FAILED;
        String period = PERIOD_NONE;
        try {
            final NavigationContext echoedContext = request.navigationContext();
            final UserType authenticatedUserType = authenticatedUserTypeOf(authentication);
            final String authenticatedUserId = (authenticatedUserType == null)
                    ? null
                    : ScreenStateAdapter.authenticatedUserId(authentication);

            // The turn takes the transmitted screen and nothing else. The authenticated identity reaches
            // the response - the screen publishes the operator and their type - but it takes no part in
            // the submission itself: each confirmed submission carries an identity the queue bridge mints
            // with a nonce, so no caller-supplied value can place one caller's cards in another's stream.
            final ReportRequestService.ReportRequestResult result = this.reportRequestService
                    .processReportRequest(this.reportContractAdapter.toScreenInput(request));

            final ReportResponse body = this.reportContractAdapter.toResponse(result, echoedContext,
                    authenticatedUserId, authenticatedUserType);
            outcome = outcomeTagOf(result);
            period = periodTagOf(result);

            return ResponseEntity.ok(body);
        } finally {
            recordTurn(sample, outcome, period);
        }
    }

    /**
     * Reads the user type the presented credential carries.
     *
     * <p>Delegated to the one reader every authenticated screen route uses, rather than resolved again
     * here. Resolution is the inverse of the single mapping the security layer applies when it grants the
     * authority, so two independent copies of it could drift apart and leave one route reading a type
     * another route would not - which is precisely the divergence a single authenticated boundary exists to
     * prevent. Nothing is inferred from an absence: an identity that carries neither declared authority - an
     * anonymous caller, or this handler driven without a security chain - resolves to {@code null} and the
     * response then asserts no identity rather than a guessed one. The security chain grants exactly one of
     * the two for a verified credential, so a caller that reached this route resolves to that one.
     *
     * @param authentication the established identity, which may be {@code null}
     * @return the user type the credential carries, or {@code null} when no known authority is present
     */
    private static UserType authenticatedUserTypeOf(final Authentication authentication) {
        return ScreenStateAdapter.authenticatedUserType(authentication);
    }

    /**
     * Records the elapsed time of one turn, tagged by the outcome it reached and the period it resolved.
     *
     * <p>Every turn is recorded, including one that raised. The caller invokes this from its {@code
     * finally} arm, so an exception propagating out of the boundary still stops the sample, carrying the
     * failed outcome and the no-period label it seeded before the attempt. Both tag values are constants
     * of this class or a resolved period name, never caller-supplied text, so the label set stays bounded.
     *
     * @param sample the timing sample started at the head of the turn
     * @param outcome the bounded outcome label the turn settled on, one of the five this class declares
     * @param period the reporting period the ordered evaluation resolved, or the no-period label
     */
    private void recordTurn(final Timer.Sample sample,
            final String outcome, final String period) {
        sample.stop(Timer.builder(METRIC_REPORT_REQUEST_TURN)
                .description("Elapsed time of one CardDemo report-request turn, transaction CR00")
                .tag(TAG_OUTCOME, outcome)
                .tag(TAG_PERIOD, period)
                .register(this.meterRegistry));
    }

    /**
     * Names the outcome of a turn for the metric label.
     *
     * <p>Read straight off the members the turn already publishes, in a fixed precedence, so this decides
     * nothing: an accepted submission and a blocked confirmation are mutually exclusive because the gate
     * stops the submission before a card is published, and the two remaining names separate a turn that
     * came back carrying a fault from one that simply served the screen. Four names and nothing derived
     * from a date, an identity or a message, so the label stays bounded.
     *
     * @param result the turn's outcome
     * @return one of four fixed names
     */
    private static String outcomeTagOf(final ReportRequestService.ReportRequestResult result) {
        if (result.submissionAccepted()) {
            return OUTCOME_SUBMITTED;
        }
        if (result.confirmationBlocked()) {
            return OUTCOME_CONFIRMATION_BLOCKED;
        }
        if (result.errorFlag()) {
            return OUTCOME_ERROR;
        }
        return OUTCOME_SCREEN_SENT;
    }

    /**
     * Names the resolved reporting period for the metric label.
     *
     * @param result the turn's outcome
     * @return the resolved period's own name, or a fixed name when the turn resolved none
     */
    private static String periodTagOf(final ReportRequestService.ReportRequestResult result) {
        final ReportPeriod period = result.reportPeriod();
        return (period == null) ? PERIOD_NONE : period.name();
    }

}
