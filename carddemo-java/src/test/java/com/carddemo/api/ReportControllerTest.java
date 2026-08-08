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

import com.carddemo.config.JwtTokenProvider;
import com.carddemo.config.SecurityConfig;
import com.carddemo.domain.enums.ReportPeriod;
import com.carddemo.domain.enums.UserType;
import com.carddemo.service.ConversationState;
import com.carddemo.service.NavigationService;
import com.carddemo.service.ReportRequestService;
import com.carddemo.util.JclCardImageBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test for {@link ReportController}, the REST surface of transaction {@code CR00}.
 *
 * <p><strong>What this proves.</strong> The controller is a boundary and nothing else, so the assertions
 * are about the boundary's properties rather than about report behaviour - the latter belongs to
 * {@link ReportRequestService} and is measured by that service's own parity suite. Four properties matter
 * here and each has a nest below:
 * <ul>
 *   <li><strong>An operation is actually delivered</strong> - one documented JSON {@code POST} mapped at
 *       one route, which is what makes the interface description carry a real operation.</li>
 *   <li><strong>The route is gated as the resource definitions gate it</strong> - reachable by any
 *       signed-on caller, so it must sit outside the administrative prefix and must not be the one
 *       anonymous surface. Asserted against the security configuration's own published constants rather
 *       than against a copy of them.</li>
 *   <li><strong>The turn is delegated exactly once and passed through unaltered</strong> - every outcome,
 *       including each rejection, answers {@code 200}, because every one of them is a screen the legacy
 *       program successfully composed and sent.</li>
 *   <li><strong>Identity is taken from the credential, never from the request body</strong> - an echoed
 *       claim is overwritten, and an absent credential yields no identity rather than a guessed one. The
 *       authority the controller matches on is checked against the security layer's own mapping, so the
 *       two cannot drift apart.</li>
 * </ul>
 *
 * <p>The controller is driven through a standalone {@code MockMvc} rather than a booted application, so
 * what is asserted is this class and its adapter and not the container's configuration. The service
 * beneath it is a stub, because its behaviour is asserted elsewhere and what matters here is that the
 * controller hands the turn over once and republishes the answer without editing it. The adapter is real,
 * because a stubbed adapter could not show that the answer reaches the wire intact.
 *
 * <p>Provenance: {@code app/cbl/CORPT00C.cbl} and the {@code CR00} transaction definition at
 * {@code app/csd/CARDDEMO.CSD} line 409, read as read-only reference at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is transcribed.
 */
@DisplayName("ReportController :: the delivered report-request operation")
class ReportControllerTest {

    /** The identifier the credential names. */
    private static final String AUTHENTICATED_USER_ID = "ADMIN001";

    /** An identifier a client might echo in place of its own, eight characters so the width is legal. */
    private static final String ECHOED_USER_ID = "IMPOSTER";

    /** The acknowledgement the service composes for a submitted month-to-date request. */
    private static final String ACKNOWLEDGEMENT = "Monthly report submitted for printing ...";

    /** The prompt the confirmation gate composes when the confirmation has not been answered. */
    private static final String CONFIRMATION_PROMPT = "Please confirm to print the Monthly report...";

    /** Stable token a client repeats when retrying one logical report request. */
    private static final String RETRY_TOKEN = "report-request-retry-001";

    /** Token the service mints when the client deliberately starts a new request. */
    private static final String MINTED_TOKEN = "report-request-minted-002";

    /** The report-request turn, stubbed: its behaviour is asserted by its own suite. */
    private ReportRequestService reportRequestService;

    /** Registry the turn timer is registered against. */
    private MeterRegistry meterRegistry;

    /** The controller under test. */
    private ReportController controller;

    /** Standalone servlet harness over that controller. */
    private MockMvc mockMvc;

    /** Assembles the controller over a stubbed service and the real contract conversion. */
    @BeforeEach
    void setUp() {
        reportRequestService = mock(ReportRequestService.class);
        meterRegistry = new SimpleMeterRegistry();
        controller = new ReportController(reportRequestService,
                new ReportContractAdapter(new ConversationStateAdapter(new NavigationService())), meterRegistry);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * A turn outcome carrying the given period, message and state.
     *
     * @param period            the period the ordered evaluation resolved, or {@code null} for none
     * @param cardsPublished    how many cards the queue accepted
     * @param blockedByConfirm  whether the confirmation gate stopped the submission
     * @param message           the summary message the turn composed
     * @param errorFlag         the state of the legacy error flag
     * @param focusField        the field the cursor returns to
     * @return the outcome a stubbed service returns
     */
    private static ReportRequestService.ReportRequestResult resultOf(final ReportPeriod period,
            final int cardsPublished, final boolean blockedByConfirm, final String message,
            final boolean errorFlag, final String focusField) {
        return resultOf(period, cardsPublished, blockedByConfirm, message, errorFlag, focusField, null);
    }

    /**
     * A turn outcome carrying an optional logical-request token.
     */
    private static ReportRequestService.ReportRequestResult resultOf(final ReportPeriod period,
            final int cardsPublished, final boolean blockedByConfirm, final String message,
            final boolean errorFlag, final String focusField, final String submissionToken) {
        return new ReportRequestService.ReportRequestResult(
                NavigationService.Route.REPORT_REQUEST,
                new ConversationState("CR00", "CORPT00C", "CR00", "CORPT00C",
                        ConversationState.EntryMode.RE_ENTRY),
                "CR00",
                period,
                (period == null) ? "" : period.getValue(),
                "2022-07-01",
                "2022-07-31",
                cardsPublished,
                submissionToken,
                blockedByConfirm,
                message,
                !errorFlag,
                focusField,
                errorFlag,
                List.of(),
                new ReportRequestService.ScreenHeader("CardDemo", "Report Request", "CR00",
                        "CORPT00C", "07/19/22", "23:12:33", message),
                new ReportRequestService.ScreenFields(null, null, null,
                        null, null, null, null, null, null, null));
    }

    /** @return the outcome of a month-to-date request the queue accepted in full */
    private static ReportRequestService.ReportRequestResult submitted() {
        return resultOf(ReportPeriod.MONTHLY, JclCardImageBuilder.CARD_COUNT, false, ACKNOWLEDGEMENT,
                false, "MONTHLY");
    }

    /** @return the outcome of a submitted request carrying the given retry token */
    private static ReportRequestService.ReportRequestResult submittedWithToken(final String token) {
        return resultOf(ReportPeriod.MONTHLY, JclCardImageBuilder.CARD_COUNT, false, ACKNOWLEDGEMENT,
                false, "MONTHLY", token);
    }

    /** @return the outcome of a request the confirmation gate stopped */
    private static ReportRequestService.ReportRequestResult awaitingConfirmation() {
        return resultOf(ReportPeriod.MONTHLY, 0, true, CONFIRMATION_PROMPT, true, "CONFIRM");
    }

    /**
     * A request body marking the month-to-date report and confirming it.
     *
     * @param echoedUserId the identity to echo in the navigation record, or {@code null} to omit it
     * @return the JSON body
     */
    private static String body(final String echoedUserId) {
        final String navigation = (echoedUserId == null)
                ? "{\"fromTransactionId\":\"CR00\",\"programContext\":\"REENTER\"}"
                : "{\"fromTransactionId\":\"CR00\",\"programContext\":\"REENTER\",\"userId\":\""
                        + echoedUserId + "\",\"userType\":\"A\"}";
        return "{\"monthlySelection\":\"S\",\"confirm\":\"Y\",\"keyAction\":\"ENTER\","
                + "\"navigationContext\":" + navigation + "}";
    }

    /**
     * A principal carrying the authority the security layer grants the given user type.
     *
     * @param userType the type the credential names
     * @return the principal a verified bearer credential establishes
     */
    private static Principal principalOf(final UserType userType) {
        return new PreAuthenticatedAuthenticationToken(AUTHENTICATED_USER_ID, null,
                List.of(new SimpleGrantedAuthority(JwtTokenProvider.authorityOf(userType))));
    }

    // ------------------------------------------------------------------------------------------
    // An operation is actually delivered, and it is gated as the resource definitions gate it
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the delivered operation inventory and its gating")
    class TheDeliveredOperationInventory {

        @Test
        @DisplayName("the class is a REST controller mapped at the report-request route, so the document "
                + "builder has an operation to contribute")
        void theClassIsARestControllerAtTheReportRequestRoute() {
            assertThat(ReportController.class.getAnnotation(RestController.class))
                    .as("without this the class is not scanned and publishes nothing")
                    .isNotNull();

            final RequestMapping mapping = ReportController.class.getAnnotation(RequestMapping.class);
            assertThat(mapping).isNotNull();
            assertThat(mapping.value()).containsExactly(ReportController.REPORT_REQUEST_PATH);
            assertThat(ReportController.REPORT_REQUEST_PATH).isEqualTo("/api/reports/request");
        }

        @Test
        @DisplayName("exactly one handler is published, it accepts and produces JSON, and it is "
                + "documented, so no second report surface exists to diverge from the first")
        void oneDocumentedJsonHandlerIsPublished() {
            final List<Method> handlers = Arrays.stream(ReportController.class.getDeclaredMethods())
                    .filter(method -> method.getAnnotation(PostMapping.class) != null)
                    .toList();

            assertThat(handlers).hasSize(1);
            final Method handler = handlers.get(0);
            final PostMapping post = handler.getAnnotation(PostMapping.class);
            assertThat(post.consumes()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
            assertThat(post.produces()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
            assertThat(handler.getAnnotation(Operation.class))
                    .as("an undocumented operation publishes a path with no description")
                    .isNotNull();
            assertThat(handler.getAnnotation(ApiResponses.class)).isNotNull();
            assertThat(Arrays.stream(ReportController.class.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .filter(method -> !method.isSynthetic())
                    .count())
                    .as("one public method means one surface")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("the route is reachable by any signed-on caller: outside the administrative prefix "
                + "and not the one anonymous surface, so the chain's catch-all authenticates it")
        void theRouteIsGatedAsAnOrdinaryTransaction() {
            assertThat(ReportController.REPORT_REQUEST_PATH)
                    .as("a path beneath the administrative prefix would gate the report screen to "
                            + "administrators, which the route-to-role table does not")
                    .doesNotStartWith(SecurityConfig.ADMIN_PATH_PREFIX)
                    .as("a path named among the anonymous surfaces would open it to a caller with no "
                            + "credential at all")
                    .isNotEqualTo(SecurityConfig.SIGN_ON_PATH);
            assertThat(SecurityConfig.TransactionRoute.forTransactionId("CR00"))
                    .isPresent()
                    .get()
                    .satisfies(route -> assertThat(route.getGating())
                            .isEqualTo(SecurityConfig.Gating.AUTHENTICATED));
        }

        @Test
        @DisplayName("the route shadows neither the management base path nor the interface description, "
                + "both of which the chain answers separately")
        void theRouteShadowsNoInfrastructureSurface() {
            assertThat(ReportController.REPORT_REQUEST_PATH)
                    .doesNotStartWith("/actuator")
                    .doesNotStartWith("/v3/api-docs")
                    .doesNotStartWith("/swagger-ui");
        }

        @Test
        @DisplayName("the controller holds no rule of its own: its only instance fields are the three "
                + "collaborators, so there is nowhere for report logic to be kept")
        void theControllerHoldsNoRuleOfItsOwn() {
            assertThat(Arrays.stream(ReportController.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .peek(field -> assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("a non-final field would be mutable shared state on a singleton")
                            .isTrue())
                    .map(field -> field.getType().getSimpleName())
                    .toList())
                    .containsExactlyInAnyOrder("ReportRequestService", "ReportContractAdapter",
                            "MeterRegistry");
        }

        @Test
        @DisplayName("every collaborator is required, so a part-wired controller cannot be constructed")
        void everyCollaboratorIsRequired() {
            final ReportContractAdapter adapter =
                    new ReportContractAdapter(new ConversationStateAdapter(new NavigationService()));

            assertThatNullPointerException().isThrownBy(() ->
                    new ReportController(null, adapter, meterRegistry));
            assertThatNullPointerException().isThrownBy(() ->
                    new ReportController(reportRequestService, null, meterRegistry));
            assertThatNullPointerException().isThrownBy(() ->
                    new ReportController(reportRequestService, adapter, null));
        }
    }

    // ------------------------------------------------------------------------------------------
    // The turn is handed over once and republished unaltered
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the turn is delegated exactly once and republished unaltered")
    class TheTurnIsDelegatedOnce {

        @Test
        @DisplayName("a submitted request answers 200 carrying the acknowledgement, the resolved period "
                + "and the route the client drives its next call from")
        void aSubmittedRequestAnswersTheAcknowledgement() throws Exception {
            when(reportRequestService.processReportRequest(any(), nullable(String.class), nullable(String.class)))
                    .thenReturn(submitted());

            mockMvc.perform(post(ReportController.REPORT_REQUEST_PATH)
                            .principal(principalOf(UserType.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(null)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.submissionAccepted").value(true))
                    .andExpect(jsonPath("$.message").value(ACKNOWLEDGEMENT))
                    .andExpect(jsonPath("$.reportPeriod").value(ReportPeriod.MONTHLY.name()))
                    .andExpect(jsonPath("$.generalError").value(false))
                    .andExpect(jsonPath("$.nextRoute")
                            .value(NavigationService.Route.REPORT_REQUEST.getRouteValue()))
                    .andExpect(jsonPath("$.navigationContext.toTransactionId").value("CR00"));

            verify(reportRequestService, times(1))
                    .processReportRequest(any(), nullable(String.class), nullable(String.class));
        }

        @Test
        @DisplayName("a submission the confirmation gate stopped also answers 200, because a prompt is a "
                + "screen the legacy program composed and sent rather than a transport failure")
        void aBlockedConfirmationStillAnswersOk() throws Exception {
            when(reportRequestService.processReportRequest(any(), nullable(String.class), nullable(String.class)))
                    .thenReturn(awaitingConfirmation());

            mockMvc.perform(post(ReportController.REPORT_REQUEST_PATH)
                            .principal(principalOf(UserType.USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(null)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.submissionAccepted").value(false))
                    .andExpect(jsonPath("$.message").value(CONFIRMATION_PROMPT))
                    .andExpect(jsonPath("$.generalError").value(true))
                    .andExpect(jsonPath("$.focusScreenFieldId").value("CONFIRM"));

            verify(reportRequestService, times(1))
                    .processReportRequest(any(), nullable(String.class), nullable(String.class));
        }

        @Test
        @DisplayName("a component wider than the map's own field is rejected with 400 before the turn "
                + "runs, so the service is never handed input the screen could not have transmitted")
        void anOverWideComponentIsRejectedBeforeTheTurnRuns() throws Exception {
            mockMvc.perform(post(ReportController.REPORT_REQUEST_PATH)
                            .principal(principalOf(UserType.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"monthlySelection\":\"SS\",\"keyAction\":\"ENTER\"}"))
                    .andExpect(status().isBadRequest());

            verify(reportRequestService, never())
                    .processReportRequest(any(), nullable(String.class), nullable(String.class));
        }

        @Test
        @DisplayName("a bound the echoed navigation record declares is applied too, because the record is "
                + "cascaded rather than merely annotated")
        void anOverWideEchoedIdentityIsRejectedToo() throws Exception {
            mockMvc.perform(post(ReportController.REPORT_REQUEST_PATH)
                            .principal(principalOf(UserType.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("TOOLONGUSERID")))
                    .andExpect(status().isBadRequest());

            verify(reportRequestService, never())
                    .processReportRequest(any(), nullable(String.class), nullable(String.class));
        }

        @Test
        @DisplayName("a caller retry token is passed to the service and returned in the response header")
        void aCallerRetryTokenRoundTripsThroughTheHeader() throws Exception {
            when(reportRequestService.processReportRequest(any(), eq(RETRY_TOKEN), nullable(String.class)))
                    .thenReturn(submittedWithToken(RETRY_TOKEN));

            mockMvc.perform(post(ReportController.REPORT_REQUEST_PATH)
                            .principal(principalOf(UserType.ADMIN))
                            .header(ReportController.IDEMPOTENCY_KEY_HEADER, RETRY_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(null)))
                    .andExpect(status().isOk())
                    .andExpect(header().string(
                            ReportController.IDEMPOTENCY_KEY_HEADER, RETRY_TOKEN));

            verify(reportRequestService).processReportRequest(any(), eq(RETRY_TOKEN), nullable(String.class));
        }

        @Test
        @DisplayName("when the caller supplies no token the service-minted token is returned for a retry")
        void aMintedTokenIsReturnedToTheCaller() throws Exception {
            when(reportRequestService.processReportRequest(any(), isNull(), nullable(String.class)))
                    .thenReturn(submittedWithToken(MINTED_TOKEN));

            mockMvc.perform(post(ReportController.REPORT_REQUEST_PATH)
                            .principal(principalOf(UserType.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(null)))
                    .andExpect(status().isOk())
                    .andExpect(header().string(
                            ReportController.IDEMPOTENCY_KEY_HEADER, MINTED_TOKEN))
                    .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Identity comes from the credential, and the turn is timed
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("identity comes from the credential, and every turn is timed")
    class IdentityAndTiming {

        @Test
        @DisplayName("an echoed identity is overwritten by the authenticated one, so a client-chosen "
                + "identity cannot survive a turn and come back looking server-asserted")
        void anEchoedIdentityIsOverwritten() throws Exception {
            when(reportRequestService.processReportRequest(any(), nullable(String.class), nullable(String.class)))
                    .thenReturn(submitted());

            mockMvc.perform(post(ReportController.REPORT_REQUEST_PATH)
                            .principal(principalOf(UserType.USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ECHOED_USER_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.navigationContext.userId").value(AUTHENTICATED_USER_ID))
                    .andExpect(jsonPath("$.navigationContext.userType")
                            .value(UserType.USER.getCode()));
        }

        @Test
        @DisplayName("each user type the security layer grants an authority for is resolved to that type, "
                + "so the controller's matching cannot drift from the layer that issues it")
        void everyGrantedUserTypeIsResolved() throws Exception {
            when(reportRequestService.processReportRequest(any(), nullable(String.class), nullable(String.class)))
                    .thenReturn(submitted());

            for (final UserType userType : UserType.values()) {
                mockMvc.perform(post(ReportController.REPORT_REQUEST_PATH)
                                .principal(principalOf(userType))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(ECHOED_USER_ID)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.navigationContext.userId").value(AUTHENTICATED_USER_ID))
                        .andExpect(jsonPath("$.navigationContext.userType")
                                .value(userType.getCode()));
            }
        }

        @Test
        @DisplayName("with no credential established the response asserts no identity at all rather than "
                + "republishing the claim the request carried")
        void withNoCredentialNoIdentityIsAsserted() throws Exception {
            when(reportRequestService.processReportRequest(any(), nullable(String.class), nullable(String.class)))
                    .thenReturn(submitted());

            mockMvc.perform(post(ReportController.REPORT_REQUEST_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ECHOED_USER_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.navigationContext.userId").doesNotExist())
                    .andExpect(jsonPath("$.navigationContext.userType").doesNotExist());
        }

        @Test
        @DisplayName("an identity carrying no authority this module grants resolves to no identity, so "
                + "nothing is inferred from the absence of a known authority")
        void anUnrecognisedAuthorityResolvesToNoIdentity() throws Exception {
            when(reportRequestService.processReportRequest(any(), nullable(String.class), nullable(String.class)))
                    .thenReturn(submitted());

            mockMvc.perform(post(ReportController.REPORT_REQUEST_PATH)
                            .principal(new PreAuthenticatedAuthenticationToken(
                                    AUTHENTICATED_USER_ID, null,
                                    List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ECHOED_USER_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.navigationContext.userId").doesNotExist());
        }

        @Test
        @DisplayName("each turn is timed and tagged by the outcome it reached and the period it resolved, "
                + "so a rise in blocked confirmations can be told from a rise in submissions")
        void eachTurnIsTimedAndTagged() throws Exception {
            when(reportRequestService.processReportRequest(any(), nullable(String.class), nullable(String.class)))
                    .thenReturn(submitted(), awaitingConfirmation());

            mockMvc.perform(post(ReportController.REPORT_REQUEST_PATH)
                            .principal(principalOf(UserType.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(null)))
                    .andExpect(status().isOk());
            mockMvc.perform(post(ReportController.REPORT_REQUEST_PATH)
                            .principal(principalOf(UserType.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(null)))
                    .andExpect(status().isOk());

            assertThat(meterRegistry.find("carddemo.online.reportrequest.turn")
                    .tag("outcome", "submitted")
                    .tag("period", ReportPeriod.MONTHLY.name())
                    .timer())
                    .isNotNull()
                    .satisfies(timer -> assertThat(timer.count()).isEqualTo(1L));
            assertThat(meterRegistry.find("carddemo.online.reportrequest.turn")
                    .tag("outcome", "confirmation-blocked")
                    .timer())
                    .as("one aggregate timer would hide which outcome the traffic reached")
                    .isNotNull()
                    .satisfies(timer -> assertThat(timer.count()).isEqualTo(1L));
        }

        @Test
        @DisplayName("a turn that resolved no report type is still timed, under a fixed period name, so "
                + "the label stays bounded and no series is lost")
        void aTurnResolvingNoPeriodIsStillTimed() throws Exception {
            when(reportRequestService.processReportRequest(any(), nullable(String.class), nullable(String.class))).thenReturn(
                    resultOf(null, 0, false, "Select a report type to print report...", true,
                            "MONTHLY"));

            mockMvc.perform(post(ReportController.REPORT_REQUEST_PATH)
                            .principal(principalOf(UserType.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"keyAction\":\"ENTER\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reportPeriod").doesNotExist());

            assertThat(meterRegistry.find("carddemo.online.reportrequest.turn")
                    .tag("outcome", "error")
                    .tag("period", "none")
                    .timer())
                    .isNotNull()
                    .satisfies(timer -> assertThat(timer.count()).isEqualTo(1L));
        }

        @Test
        @DisplayName("a turn that neither submitted nor faulted is timed as a served screen, which is the "
                + "first-entry and return-to-previous shape")
        void aServedScreenIsTimedAsSuch() throws Exception {
            when(reportRequestService.processReportRequest(any(), nullable(String.class), nullable(String.class))).thenReturn(
                    resultOf(null, 0, false, "", false, "MONTHLY"));

            mockMvc.perform(post(ReportController.REPORT_REQUEST_PATH)
                            .principal(principalOf(UserType.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"keyAction\":\"PFK03\"}"))
                    .andExpect(status().isOk());

            assertThat(meterRegistry.find("carddemo.online.reportrequest.turn")
                    .tag("outcome", "screen-sent")
                    .timer())
                    .isNotNull()
                    .satisfies(timer -> assertThat(timer.count()).isEqualTo(1L));
        }

        @Test
        @DisplayName("every observed outcome tag is one of the four fixed names, so the label cannot "
                + "become a high-cardinality series")
        void everyOutcomeTagIsOneOfTheFourFixedNames() throws Exception {
            when(reportRequestService.processReportRequest(any(), nullable(String.class), nullable(String.class)))
                    .thenReturn(submitted(), awaitingConfirmation(),
                            resultOf(null, 0, false, "", false, "MONTHLY"));

            for (int turn = 0; turn < 3; turn++) {
                mockMvc.perform(post(ReportController.REPORT_REQUEST_PATH)
                                .principal(principalOf(UserType.ADMIN))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(null)))
                        .andExpect(status().isOk());
            }

            final List<String> permittedOutcomes =
                    List.of("submitted", "confirmation-blocked", "error", "screen-sent");
            final List<String> permittedPeriods = Arrays.stream(ReportPeriod.values())
                    .map(Enum::name)
                    .toList();

            assertThat(meterRegistry.find("carddemo.online.reportrequest.turn").timers())
                    .isNotEmpty()
                    .allSatisfy(timer -> {
                        assertThat(permittedOutcomes).contains(timer.getId().getTag("outcome"));
                        assertThat(timer.getId().getTag("period"))
                                .satisfiesAnyOf(
                                        period -> assertThat(period).isEqualTo("none"),
                                        period -> assertThat(permittedPeriods).contains(period));
                    });
        }
    }
}
