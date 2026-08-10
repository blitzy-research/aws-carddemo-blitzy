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
import com.carddemo.exception.ValidationException;
import com.carddemo.service.ReportRequestService;
import com.carddemo.util.ReportRetryTokens;
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
import org.springframework.web.bind.annotation.RequestHeader;
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
 * <p><strong>Retry identity travels in a header, not in the frozen screen body.</strong> A caller may send
 * {@link #IDEMPOTENCY_KEY_HEADER} to identify one logical submission and repeat it on a retry. When it is
 * absent the service mints a fresh token, making the request a deliberate new submission even when its date
 * range matches an earlier one; the effective token is returned in the same response header. The service
 * combines that token with the date range before the queue bridge adds each card ordinal, so a retry is
 * stable and a new request cannot be mistaken for one. No state is held here, no field is added to the
 * screen DTO, and there is still no session, redirect or server-side dispatch.
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

    /**
     * Standard request/response header carrying the stable token of one logical report submission.
     *
     * <p>A submitted turn returns the token it used. Presenting that token again retries the same logical
     * submission, so a stream the queue had partly accepted is completed rather than appended a second time,
     * and the retry is honoured for {@link ReportRetryTokens#VALIDITY_MINUTES} minutes from the attempt that
     * minted it. The horizon is the queue service's own: it recognises a repeated deduplication identifier
     * for that long and then forgets it, after which repeating the token would publish the stream again
     * while telling the caller it had retried. So the token is minted by this service, carries the instant
     * it was minted under an authentication code, and a presented value this service did not issue or that
     * is older than the window is refused rather than honoured. Callers treat the value as opaque and echo
     * it unchanged; omitting the header is a deliberate new submission. See {@code docs/decision-log.md}
     * entry DL-310.
     */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /**
     * Longest retry token this boundary accepts, in characters.
     *
     * <p>The token is entirely the caller's to choose, so the only question is how much of a
     * caller-chosen value the module is willing to take in. It is digested, it is retained for the life
     * of the deduplication window, and it is reflected in the response, so an unbounded value is
     * unbounded work and unbounded storage on a route a signed-on caller may invoke repeatedly. Nothing
     * bounded it before this constant: the container's own header ceiling - eight kibibytes across the
     * whole request line and header block - was the only limit, which is four orders of magnitude wider
     * than any legitimate token and is a transport limit rather than an application contract.
     *
     * <p>One hundred and twenty-eight is chosen against what a token is for rather than against what a
     * transport permits. A random identifier is thirty-six characters, a hexadecimal digest of a
     * client-side request is sixty-four, and a caller who needs more than double the longer of those is
     * not identifying a submission. The service's own account of the deduplication digest already
     * asserted that a token "is header text the request contract bounds to printable characters" - this
     * is the bound that assertion depended on.
     */
    public static final int IDEMPOTENCY_KEY_MAX_LENGTH = 128;

    /**
     * Lowest character code this boundary accepts inside a retry token: the first visible ASCII
     * character. Everything below it is a space or a control byte.
     */
    private static final char IDEMPOTENCY_KEY_LOWEST_CHARACTER = '!';

    /**
     * Highest character code this boundary accepts inside a retry token: the last visible ASCII
     * character.
     */
    private static final char IDEMPOTENCY_KEY_HIGHEST_CHARACTER = '~';

    /**
     * The rejection the boundary answers a malformed retry token with.
     *
     * <p>Deliberately says what was wrong with the header and repeats nothing the caller sent. A message
     * that echoed the offending value would put an arbitrary caller-chosen string into a response body,
     * a log line and any diagnostic downstream of either, which is the same reflection the bound exists
     * to stop.
     */
    private static final String IDEMPOTENCY_KEY_REJECTION = "The " + IDEMPOTENCY_KEY_HEADER
            + " header must be at most " + IDEMPOTENCY_KEY_MAX_LENGTH
            + " visible ASCII characters with no space or control byte.";

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
     * @param retryToken     the token an earlier attempt returned, or absent for a deliberate new
     *                       submission; honoured for {@link ReportRetryTokens#VALIDITY_MINUTES} minutes
     *                       from the attempt that issued it
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
                        + "the resolved period, with the effective retry token returned in the "
                        + "Idempotency-Key header; any other outcome carries the screen message, the "
                        + "field the cursor returns to, and the marks and date parts as the turn leaves "
                        + "them. Echo the returned token to retry the same submission rather than "
                        + "duplicating it; the token is opaque, is issued by this service, and is "
                        + "honoured for " + ReportRetryTokens.VALIDITY_MINUTES + " minutes from the "
                        + "attempt that issued it."),
        @ApiResponse(responseCode = "400",
                description = "The request exceeded the widths the report-request map declares, or the "
                        + "Idempotency-Key presented was not issued by this service or is older than the "
                        + ReportRetryTokens.VALIDITY_MINUTES + " minute retry window. Nothing is "
                        + "published in either case; omit the header to submit a new request."),
        @ApiResponse(responseCode = "401",
                description = "No credential was presented, or the one presented did not verify.")})
    public ResponseEntity<ReportResponse> requestReport(
            @Valid @RequestBody final ReportRequest request,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false)
            final String retryToken,
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

            // The authenticated operator is handed to the turn as well as to the response, because the
            // submission the turn may publish is deduplicated by the queue and a deduplication namespace
            // shared between callers is one caller able to suppress another's submission. It is the identity
            // the chain established, never the navigation state the client echoed - see the service's own
            // account of why that distinction is the whole of the protection.
            final ReportRequestService.ReportRequestResult result = this.reportRequestService
                    .processReportRequest(this.reportContractAdapter.toScreenInput(request),
                            canonicalRetryToken(retryToken), authenticatedUserId);

            final ReportResponse body = this.reportContractAdapter.toResponse(result, echoedContext,
                    authenticatedUserId, authenticatedUserType);
            outcome = outcomeTagOf(result);
            period = periodTagOf(result);

            final ResponseEntity.BodyBuilder response = ResponseEntity.ok();
            if (result.submissionToken() != null) {
                response.header(IDEMPOTENCY_KEY_HEADER, result.submissionToken());
            }
            return response.body(body);
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

    /**
     * Bounds and canonicalises a caller-supplied retry token before anything downstream sees it.
     *
     * <p>An absent or blank header is passed through as {@code null}, which the service reads as "this
     * is a new logical request" and answers by minting a token of its own. That is the existing
     * contract and it is unchanged; blank is normalised to absent here so the service is not handed two
     * spellings of the same thing.
     *
     * <p>A present token is accepted only if it is at most {@link #IDEMPOTENCY_KEY_MAX_LENGTH}
     * characters and every character is visible ASCII. Surrounding whitespace is stripped first,
     * because a header value is transported with optional whitespace around it and a token that
     * differed from an earlier one only by a leading space would be a different deduplication identity
     * for the same logical submission - which is the one thing a retry token exists to prevent.
     *
     * <p>Everything else is refused with a neutral {@code 400}. The refusal is raised here rather than
     * inside the service because it is a statement about the transport contract and not about the
     * report screen: the screen has no field for it, no mark to set on it and no message for it, and a
     * turn that never began cannot report one.
     *
     * @param retryToken the header value as received, possibly {@code null}
     * @return the canonical token, or {@code null} when none was supplied
     * @throws ValidationException when a token was supplied and is not a bounded visible-ASCII value
     */
    private static String canonicalRetryToken(final String retryToken) {
        if (retryToken == null) {
            return null;
        }
        final String canonical = retryToken.strip();
        if (canonical.isEmpty()) {
            return null;
        }
        if (canonical.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new ValidationException(IDEMPOTENCY_KEY_REJECTION);
        }
        for (int index = 0; index < canonical.length(); index++) {
            final char character = canonical.charAt(index);
            if (character < IDEMPOTENCY_KEY_LOWEST_CHARACTER
                    || character > IDEMPOTENCY_KEY_HIGHEST_CHARACTER) {
                throw new ValidationException(IDEMPOTENCY_KEY_REJECTION);
            }
        }
        return canonical;
    }

}
