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

import com.carddemo.api.dto.BillPaymentResponse;
import com.carddemo.config.SecurityConfig;
import com.carddemo.domain.enums.UserType;
import com.carddemo.exception.ValidationException;
import com.carddemo.service.BillPaymentService;
import com.carddemo.service.NavigationService;
import com.carddemo.service.ScreenNavigationState;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The delivered bill-payment operation of transaction {@code CB00}.
 *
 * <p>The controller under test is a boundary and nothing else, so this suite asserts boundary
 * properties rather than payment rules. It checks that the operation is actually published and
 * published once; that the four transmitted values reach the transaction unaltered; that the returned
 * outcome is projected onto the response contract field for field, including the three transfers where
 * a neighbouring component would have been the wrong source; that the exact screen texts survive the
 * crossing; that the balance is carried as a fixed-scale decimal rather than a floating or edited
 * value; that the turn is timed under a bounded label set; and that the surface is reachable only by a
 * caller carrying an identity. The payment rules themselves - the ordered checks, the refusals, the
 * identifier allocation, the posted record and the balance arithmetic - are the service's, and are
 * exercised against the service directly.
 *
 * <p>The transaction is stubbed rather than assembled over real repositories, which is deliberate:
 * every property asserted here is a property of the crossing, and a stub is what lets an outcome be
 * placed at the boundary exactly - a refusal, an unconfirmed prompt, a completed settlement - without
 * the assertion depending on the rules that would otherwise have to produce it. The controller is
 * driven through a standalone servlet harness rather than a booted application so that request binding,
 * declarative validation, the shared failure handler and JSON rendering are all genuinely exercised.
 *
 * <p>Provenance of the behaviour being reproduced: {@code app/cbl/COBIL00C.cbl} with its symbolic map
 * {@code app/cpy-bms/COBIL00.CPY} and mapset {@code app/bms/COBIL00.bms}, read as read-only reference
 * at checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL text is reproduced.
 */
@DisplayName("BillPaymentController :: the delivered bill-payment operation")
class BillPaymentControllerTest {

    /** An eleven-character account identifier whose leading zeros are contractual. */
    private static final String ACCOUNT_ID = "00000000011";

    /** The sixteen-character identifier the first settlement against an empty table would carry. */
    private static final String FIRST_TRANSACTION_ID = "0000000000000001";

    /** The twenty-six-character online timestamp form, distinct from the batch form. */
    private static final String ONLINE_TIMESTAMP = "2022-07-19 23:12:33.000000";

    /** The two screen titles, at their contractual width. */
    private static final String TITLE_01 = "      AWS Mainframe Modernization       ";

    /** The second screen title, at its contractual width. */
    private static final String TITLE_02 = "              CardDemo                  ";

    /** The bill-payment transaction, stubbed so an outcome can be placed at the boundary exactly. */
    private BillPaymentService billPaymentService;

    /** The converter between the wire navigation record and the service-owned state. */
    private ScreenStateAdapter screenStateAdapter;

    /** Registry the turn timer is registered against. */
    private MeterRegistry meterRegistry;

    /** Standalone servlet harness over the controller. */
    private MockMvc mockMvc;

    /**
     * Builds an established identity carrying the single authority the chain grants for a user type.
     *
     * <p>Supplied as the request principal rather than through a security filter, because this route is
     * exercised standalone: the framework resolves an {@code Authentication} parameter from the request's own
     * user principal, so the boundary sees exactly what the chain would have handed it.
     *
     * @param userId the principal name
     * @param userType the type whose declared authority is granted
     * @return an authenticated token the boundary can read identity from
     */
    private static Authentication identityOf(final String userId, final UserType userType) {
        return new TestingAuthenticationToken(userId, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + userType.name())));
    }

    /** Assembles the controller over a stubbed transaction and the shared failure handler. */
    @BeforeEach
    void setUp() {
        billPaymentService = mock(BillPaymentService.class);
        // The real converter rather than a mock: it holds no state and performs a positional copy, so
        // stubbing it would measure the stub instead of the crossing it is here to prove.
        screenStateAdapter = new ScreenStateAdapter(new NavigationService());
        meterRegistry = new SimpleMeterRegistry();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new BillPaymentController(billPaymentService, screenStateAdapter,
                        meterRegistry))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * Places one outcome at the boundary.
     *
     * @param message the screen message, byte exact
     * @param screenBalance the pre-payment balance at scale two, or {@code null}
     * @param errorFlag the program's own error flag
     * @param confirmation the confirmation flag as the turn ended
     * @param posted the transaction the turn posted, or {@code null} when it posted none
     * @param focusField the field the cursor returns to
     * @param echoedAccountId the account identifier as the turn leaves it
     * @param echoedConfirm the confirmation character as the turn leaves it
     */
    private void givenOutcome(final String message, final BigDecimal screenBalance,
            final boolean errorFlag, final BillPaymentService.ConfirmPaymentFlag confirmation,
            final BillPaymentService.TransactionProjection posted, final String focusField,
            final String echoedAccountId, final String echoedConfirm) {
        when(billPaymentService.processBillPayment(any())).thenReturn(
                new BillPaymentService.BillPaymentResult(
                        NavigationService.Route.BILL_PAYMENT,
                        ScreenNavigationState.empty(),
                        "CB00",
                        posted,
                        posted == null ? null
                                : new BillPaymentService.AccountProjection(ACCOUNT_ID,
                                        new BigDecimal("0.00"), 1L),
                        screenBalance,
                        message,
                        posted != null,
                        focusField,
                        errorFlag,
                        confirmation,
                        true,
                        List.of(),
                        new BillPaymentService.ScreenHeader(TITLE_01, TITLE_02, "CB00", "COBIL00C",
                                "07/19/22", "23:12:33", message),
                        new BillPaymentService.ScreenFields(echoedAccountId, "+0000000123.45",
                                echoedConfirm)));
    }

    /**
     * Places the completed-settlement outcome at the boundary.
     *
     * <p>The message is assembled from the two published halves exactly as the service assembles it, so
     * the two consecutive spaces after the first period are present rather than tidied.
     */
    private void givenSettlementCompleted() {
        givenOutcome(BillPaymentResponse.MSG_PAYMENT_SUCCESSFUL_PREFIX
                        + BillPaymentResponse.MSG_TRANSACTION_ID_FRAGMENT + FIRST_TRANSACTION_ID + ".",
                new BigDecimal("123.45"), false, BillPaymentService.ConfirmPaymentFlag.YES,
                postedTransaction(), BillPaymentResponse.ACCOUNT_ID_FIELD_ID, "", "");
    }

    /**
     * The transaction a completed settlement posts, at the fixed values the service stamps into it.
     *
     * @return the projection
     */
    private static BillPaymentService.TransactionProjection postedTransaction() {
        return new BillPaymentService.TransactionProjection(FIRST_TRANSACTION_ID, "02", "0002",
                "POS TERM  ", "BILL PAYMENT - ONLINE", new BigDecimal("123.45"), "999999999",
                "BILL PAYMENT", "N/A", "N/A", "4111111111111111", ONLINE_TIMESTAMP, ONLINE_TIMESTAMP);
    }

    /**
     * Renders a request body.
     *
     * @param accountId the account identifier, or {@code null} to render the JSON null literal
     * @param confirm the confirmation character, or {@code null} to render the JSON null literal
     * @return the JSON body
     */
    private static String body(final String accountId, final String confirm) {
        return "{\"accountId\":" + quotedOrNull(accountId)
                + ",\"confirm\":" + quotedOrNull(confirm)
                + ",\"keyAction\":\"ENTER\",\"navigationContext\":null}";
    }

    /**
     * Quotes a value or renders the JSON null literal.
     *
     * @param value the value
     * @return the rendered JSON
     */
    private static String quotedOrNull(final String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    /**
     * Captures the input the controller handed to the transaction.
     *
     * @return the captured input
     */
    private BillPaymentService.BillPaymentScreenInput capturedInput() {
        final ArgumentCaptor<BillPaymentService.BillPaymentScreenInput> captor =
                ArgumentCaptor.forClass(BillPaymentService.BillPaymentScreenInput.class);
        verify(billPaymentService).processBillPayment(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------------------------------------------
    // An operation is actually delivered, and exactly one
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the published surface")
    class ThePublishedSurface {

        @Test
        @DisplayName("exactly one documented JSON handler is published, so the contract document "
                + "describes a real operation rather than a bare path")
        void oneDocumentedJsonHandlerIsPublished() {
            final List<Method> handlers = Arrays.stream(BillPaymentController.class.getDeclaredMethods())
                    .filter(method -> method.getAnnotation(PostMapping.class) != null)
                    .toList();

            assertThat(handlers).hasSize(1);
            final PostMapping post = handlers.get(0).getAnnotation(PostMapping.class);
            assertThat(post.consumes()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
            assertThat(post.produces()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
            assertThat(handlers.get(0).getAnnotation(Operation.class)).isNotNull();
            assertThat(handlers.get(0).getAnnotation(ApiResponses.class)).isNotNull();
        }

        @Test
        @DisplayName("the class is a REST controller mapped at its own published constant, so the "
                + "address and the constant cannot drift apart")
        void theClassIsMappedAtItsOwnConstant() {
            assertThat(BillPaymentController.class.getAnnotation(RestController.class)).isNotNull();
            assertThat(BillPaymentController.class.getAnnotation(RequestMapping.class).value())
                    .containsExactly(BillPaymentController.BILL_PAYMENT_PATH);
            assertThat(BillPaymentController.BILL_PAYMENT_PATH).isEqualTo("/api/bill-payment");
        }

        @Test
        @DisplayName("the address is neither the anonymous route nor beneath the administrative "
                + "prefix, so the surface is reachable by a caller carrying either sign-on authority "
                + "and by no other identity")
        void theAddressIsGatedByTheOrdinaryBusinessRule() {
            assertThat(BillPaymentController.BILL_PAYMENT_PATH)
                    .as("the one anonymous route issues credentials; this one must not be it")
                    .isNotEqualTo(SecurityConfig.SIGN_ON_PATH)
                    .as("the administrative prefix is gated on an administrative authority")
                    .doesNotStartWith(SecurityConfig.ADMIN_PATH_PREFIX)
                    .as("it must lie inside the region the ordinary business rule governs, or it would "
                            + "fall to the closing rule instead")
                    .startsWith(SecurityConfig.API_PATH_PREFIX + "/");
            assertThat(SecurityConfig.TransactionRoute.BILL_PAYMENT.getGating())
                    .isEqualTo(SecurityConfig.Gating.AUTHENTICATED);
            assertThat(SecurityConfig.Gating.AUTHENTICATED.enforcementPattern())
                    .as("the ordinary entitlement names one rule over the API root, which requires either "
                            + "sign-on authority by name rather than merely an established identity")
                    .isEqualTo(SecurityConfig.API_PATH_PREFIX + "/**");
        }

        @Test
        @DisplayName("the address shadows neither the management endpoints nor the published contract "
                + "document")
        void theAddressShadowsNoInfrastructurePath() {
            assertThat(BillPaymentController.BILL_PAYMENT_PATH)
                    .doesNotStartWith("/actuator")
                    .doesNotStartWith("/v3/api-docs")
                    .doesNotStartWith("/swagger-ui");
        }

        @Test
        @DisplayName("an absent collaborator is refused at construction, so a misconfigured context "
                + "fails while it is starting rather than inside a payment")
        void anAbsentCollaboratorIsRefusedAtConstruction() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new BillPaymentController(null, screenStateAdapter,
                            meterRegistry))
                    .withMessageContaining("billPaymentService");
            assertThatNullPointerException()
                    .isThrownBy(() -> new BillPaymentController(billPaymentService, null,
                            meterRegistry))
                    .withMessageContaining("screenStateAdapter");
            assertThatNullPointerException()
                    .isThrownBy(() -> new BillPaymentController(billPaymentService,
                            screenStateAdapter, null))
                    .withMessageContaining("meterRegistry");
        }
    }

    // ------------------------------------------------------------------------------------------
    // What crosses inbound
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the inbound crossing")
    class TheInboundCrossing {

        @Test
        @DisplayName("the transaction is invoked exactly once, with the four transmitted values "
                + "unaltered")
        void theTransactionIsInvokedOnceWithTheTransmittedValues() throws Exception {
            givenSettlementCompleted();

            mockMvc.perform(post(BillPaymentController.BILL_PAYMENT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ACCOUNT_ID, "Y")))
                    .andExpect(status().isOk());

            verify(billPaymentService, times(1)).processBillPayment(any());
            final BillPaymentService.BillPaymentScreenInput input = capturedInput();
            assertThat(input.accountId())
                    .as("leading zeros and the eleven-character width are contractual")
                    .isEqualTo(ACCOUNT_ID);
            assertThat(input.confirm()).isEqualTo("Y");
            assertThat(input.keyAction().name()).isEqualTo("ENTER");
            // A body that echoed no navigation record reaches the transaction as the empty carried state
            // rather than as a null reference. The two are the same outcome to this screen, whose own
            // absence test answers identically for a null reference and for an all-blank state, and the
            // empty carrier is what the legacy treats as no carry-over at all.
            assertThat(input.navigationContext()).isEqualTo(ScreenNavigationState.empty());
        }

        @Test
        @DisplayName("an echoed navigation record crosses component for component, except the two identity "
                + "members, which the authenticated principal supplies instead of the caller, so nothing the client "
                + "echoed is trimmed, dropped or defaulted on the way in")
        void anEchoedNavigationRecordCrossesComponentForComponent() throws Exception {
            givenSettlementCompleted();

            mockMvc.perform(post(BillPaymentController.BILL_PAYMENT_PATH)
                            .principal(identityOf("USER0001", UserType.USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"accountId\":\"" + ACCOUNT_ID + "\",\"confirm\":\"Y\","
                                    + "\"keyAction\":\"ENTER\",\"navigationContext\":"
                                    + "{\"fromTransactionId\":\"CM00\","
                                    + "\"fromProgram\":\"COMEN01C\","
                                    + "\"toTransactionId\":\"CB00\","
                                    + "\"toProgram\":\"COBIL00C\","
                                    + "\"userId\":\"USER0001\",\"userType\":\"U\","
                                    + "\"programContext\":\"REENTER\","
                                    + "\"customerId\":\"000000123\","
                                    + "\"customerFirstName\":\"ANN\","
                                    + "\"customerMiddleName\":\"B\","
                                    + "\"customerLastName\":\"SMITH\","
                                    + "\"accountId\":\"" + ACCOUNT_ID + "\","
                                    + "\"accountStatus\":\"Y\","
                                    + "\"cardNumber\":\"4111111111111111\","
                                    + "\"lastMap\":\"CBILL0A\",\"lastMapset\":\"COBIL00\"}}"))
                    .andExpect(status().isOk());

            final ScreenNavigationState carried = capturedInput().navigationContext();
            assertThat(carried).isEqualTo(new ScreenNavigationState("CM00", "COMEN01C", "CB00",
                    "COBIL00C", "USER0001", "U", ScreenNavigationState.ProgramContext.REENTER,
                    "000000123", "ANN", "B", "SMITH", ACCOUNT_ID, "Y", "4111111111111111", "CBILL0A",
                    "COBIL00"));
        }

        @ParameterizedTest(name = "the confirmation character {0} crosses as typed")
        @ValueSource(strings = {"Y", "y", "N", "n", " ", "q"})
        @DisplayName("both letter cases of both answers, a blank and an unacceptable character all "
                + "cross unfolded, because the legacy evaluation has four outcomes and has to be able "
                + "to quote an unacceptable character back")
        void theConfirmationCharacterCrossesAsTyped(final String typed) throws Exception {
            givenOutcome(BillPaymentResponse.MSG_CONFIRM_BILL_PAYMENT, new BigDecimal("123.45"), false,
                    BillPaymentService.ConfirmPaymentFlag.NO, null,
                    BillPaymentResponse.CONFIRM_FIELD_ID, ACCOUNT_ID, typed);

            mockMvc.perform(post(BillPaymentController.BILL_PAYMENT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ACCOUNT_ID, typed)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.confirm").value(typed));

            assertThat(capturedInput().confirm())
                    .as("no trimming, padding or case folding at this boundary")
                    .isEqualTo(typed);
        }

        @Test
        @DisplayName("an absent account identifier and an absent confirmation character both cross, "
                + "because each is a legitimate legacy state whose report belongs to the transaction")
        void absentValuesCrossRatherThanBeingRejectedHere() throws Exception {
            givenOutcome(BillPaymentResponse.MSG_ACCT_ID_EMPTY, null, true,
                    BillPaymentService.ConfirmPaymentFlag.NO, null,
                    BillPaymentResponse.ACCOUNT_ID_FIELD_ID, null, null);

            mockMvc.perform(post(BillPaymentController.BILL_PAYMENT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(null, null)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errorMessage").value("Acct ID can NOT be empty..."));

            assertThat(capturedInput().accountId()).isNull();
            assertThat(capturedInput().confirm()).isNull();
        }

        @Test
        @DisplayName("a value wider than the screen field it came from is refused before the "
                + "transaction is reached, so declarative width checking cannot be confused with the "
                + "transaction's own ordered checks")
        void anOverWideValueIsRefusedBeforeTheTransactionIsReached() throws Exception {
            mockMvc.perform(post(BillPaymentController.BILL_PAYMENT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("000000000123", "Y")))
                    .andExpect(status().isBadRequest());

            verify(billPaymentService, never()).processBillPayment(any());
        }

        @Test
        @DisplayName("the width check cascades into the echoed navigation state, so a bound declared "
                + "on the nested record is actually applied")
        void theWidthCheckCascadesIntoTheEchoedNavigationState() throws Exception {
            mockMvc.perform(post(BillPaymentController.BILL_PAYMENT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"accountId\":\"" + ACCOUNT_ID + "\",\"confirm\":\"Y\","
                                    + "\"keyAction\":\"ENTER\",\"navigationContext\":"
                                    + "{\"fromProgram\":\"FAR_TOO_LONG_A_PROGRAM_NAME\"}}"))
                    .andExpect(status().isBadRequest());

            verify(billPaymentService, never()).processBillPayment(any());
        }
    }

    // ------------------------------------------------------------------------------------------
    // What crosses outbound
    // ------------------------------------------------------------------------------------------

    /**
     * Places an outcome carrying the supplied field findings at the boundary.
     *
     * @param fieldErrors the findings the turn raised, in the order it raised them
     */
    private void givenOutcomeFaulting(
            final List<ValidationException.FieldError> fieldErrors) {
        when(billPaymentService.processBillPayment(any())).thenReturn(
                new BillPaymentService.BillPaymentResult(
                        NavigationService.Route.BILL_PAYMENT,
                        ScreenNavigationState.empty(),
                        "CB00",
                        null,
                        null,
                        null,
                        BillPaymentResponse.MSG_ACCT_ID_EMPTY,
                        false,
                        BillPaymentResponse.ACCOUNT_ID_FIELD_ID,
                        true,
                        BillPaymentService.ConfirmPaymentFlag.NO,
                        true,
                        fieldErrors,
                        new BillPaymentService.ScreenHeader(TITLE_01, TITLE_02, "CB00", "COBIL00C",
                                "07/19/22", "23:12:33", BillPaymentResponse.MSG_ACCT_ID_EMPTY),
                        new BillPaymentService.ScreenFields("", "+0000000000.00", "")));
    }

    /**
     * The per-field detail the turn computed reaches the client rather than being dropped here.
     *
     * <p>This screen has two inputs and each can fail in either of the two ways the legacy distinguishes:
     * an account number can be absent or present-and-unusable, and a confirmation can be absent or carry
     * an answer other than the two accepted ones. The legacy draws the first kind with an asterisk beside
     * the field and the second with only a colour change, so the whole-screen flag alone cannot tell a
     * client which happened, nor on which of the two fields.</p>
     */
    @Nested
    @DisplayName("the outbound projection publishes the per-field detail the turn computed")
    class TheOutboundProjectionPublishesFieldDetail {

        @Test
        @DisplayName("an absent account number publishes as missing, under its own map identifier")
        void anAbsentAccountNumberPublishesAsMissing() throws Exception {
            givenOutcomeFaulting(List.of(new ValidationException.FieldError("accountId",
                    BillPaymentResponse.ACCOUNT_ID_FIELD_ID,
                    ValidationException.FieldState.MISSING,
                    BillPaymentResponse.MSG_ACCT_ID_EMPTY)));

            mockMvc.perform(post(BillPaymentController.BILL_PAYMENT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("", "")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fieldErrors").isArray())
                    .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                    .andExpect(jsonPath("$.fieldErrors[0].fieldName").value("accountId"))
                    .andExpect(jsonPath("$.fieldErrors[0].screenFieldId")
                            .value(BillPaymentResponse.ACCOUNT_ID_FIELD_ID))
                    .andExpect(jsonPath("$.fieldErrors[0].state").value("MISSING"))
                    .andExpect(jsonPath("$.fieldErrors[0].message")
                            .value(BillPaymentResponse.MSG_ACCT_ID_EMPTY));
        }

        @Test
        @DisplayName("an unusable confirmation answer publishes as invalid, which is a different "
                + "state from missing")
        void anUnusableConfirmationPublishesAsInvalid() throws Exception {
            givenOutcomeFaulting(List.of(new ValidationException.FieldError("confirm",
                    BillPaymentResponse.CONFIRM_FIELD_ID, ValidationException.FieldState.INVALID,
                    "\"X\" is not a valid value to confirm...")));

            mockMvc.perform(post(BillPaymentController.BILL_PAYMENT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ACCOUNT_ID, "X")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fieldErrors[0].fieldName").value("confirm"))
                    .andExpect(jsonPath("$.fieldErrors[0].screenFieldId")
                            .value(BillPaymentResponse.CONFIRM_FIELD_ID))
                    .andExpect(jsonPath("$.fieldErrors[0].state").value("INVALID"));
        }

        @Test
        @DisplayName("two findings publish in the order the turn raised them, the account before the "
                + "confirmation")
        void twoFindingsPublishInOrder() throws Exception {
            givenOutcomeFaulting(List.of(
                    new ValidationException.FieldError("accountId",
                            BillPaymentResponse.ACCOUNT_ID_FIELD_ID,
                            ValidationException.FieldState.MISSING, "first"),
                    new ValidationException.FieldError("confirm",
                            BillPaymentResponse.CONFIRM_FIELD_ID,
                            ValidationException.FieldState.INVALID, "second")));

            mockMvc.perform(post(BillPaymentController.BILL_PAYMENT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("", "X")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                    .andExpect(jsonPath("$.fieldErrors[0].fieldName").value("accountId"))
                    .andExpect(jsonPath("$.fieldErrors[0].state").value("MISSING"))
                    .andExpect(jsonPath("$.fieldErrors[1].fieldName").value("confirm"))
                    .andExpect(jsonPath("$.fieldErrors[1].state").value("INVALID"));
        }

        @Test
        @DisplayName("a turn that faulted nothing publishes an empty array, not an absent member")
        void aCleanTurnPublishesAnEmptyArray() throws Exception {
            givenSettlementCompleted();

            mockMvc.perform(post(BillPaymentController.BILL_PAYMENT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ACCOUNT_ID, "Y")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fieldErrors").isArray())
                    .andExpect(jsonPath("$.fieldErrors").isEmpty());
        }
    }

    @Nested
    @DisplayName("the outbound projection")
    class TheOutboundProjection {

        @Test
        @DisplayName("a completed settlement answers 200 and carries the posted identifier, the "
                + "settlement flag and the assembled success text with its two consecutive spaces "
                + "intact")
        void aCompletedSettlementIsProjectedInFull() throws Exception {
            givenSettlementCompleted();

            mockMvc.perform(post(BillPaymentController.BILL_PAYMENT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ACCOUNT_ID, "Y")))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.paymentAccepted").value(true))
                    .andExpect(jsonPath("$.generalError").value(false))
                    .andExpect(jsonPath("$.newTransactionId").value(FIRST_TRANSACTION_ID))
                    .andExpect(jsonPath("$.errorMessage").value("Payment successful.  Your "
                            + "Transaction ID is " + FIRST_TRANSACTION_ID + "."))
                    .andExpect(jsonPath("$.transactionName").value("CB00"))
                    .andExpect(jsonPath("$.programName").value("COBIL00C"))
                    .andExpect(jsonPath("$.title01").value(TITLE_01))
                    .andExpect(jsonPath("$.title02").value(TITLE_02))
                    .andExpect(jsonPath("$.currentDate").value("07/19/22"))
                    .andExpect(jsonPath("$.currentTime").value("23:12:33"))
                    .andExpect(jsonPath("$.focusScreenFieldId")
                            .value(BillPaymentResponse.ACCOUNT_ID_FIELD_ID))
                    .andExpect(jsonPath("$.nextRoute").value("bill-payment"))
                    .andExpect(jsonPath("$.navigationContext").exists());
        }

        @Test
        @DisplayName("a turn that posted nothing reports no identifier at all, so a refused payment "
                + "cannot be mistaken for a completed one")
        void aTurnThatPostedNothingReportsNoIdentifier() throws Exception {
            givenOutcome(BillPaymentResponse.MSG_CONFIRM_BILL_PAYMENT, new BigDecimal("123.45"), false,
                    BillPaymentService.ConfirmPaymentFlag.NO, null,
                    BillPaymentResponse.CONFIRM_FIELD_ID, ACCOUNT_ID, " ");

            mockMvc.perform(post(BillPaymentController.BILL_PAYMENT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ACCOUNT_ID, " ")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.newTransactionId").doesNotExist())
                    .andExpect(jsonPath("$.paymentAccepted").value(false))
                    .andExpect(jsonPath("$.errorMessage")
                            .value("Confirm to make a bill payment..."));
        }

        @Test
        @DisplayName("the pre-payment balance is carried as a fixed-scale decimal in plain notation, "
                + "never as the edited fixed-width characters the screen field held and never in "
                + "scientific notation")
        void theBalanceIsCarriedAsAFixedScaleDecimal() throws Exception {
            givenOutcome(BillPaymentResponse.MSG_CONFIRM_BILL_PAYMENT,
                    new BigDecimal("1234567890.10"), false, BillPaymentService.ConfirmPaymentFlag.NO,
                    null, BillPaymentResponse.CONFIRM_FIELD_ID, ACCOUNT_ID, " ");

            final String payload = mockMvc.perform(post(BillPaymentController.BILL_PAYMENT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ACCOUNT_ID, " ")))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            // Asserted against the rendered payload rather than through a JSON path expression,
            // because the path evaluator parses the number into its own representation and discards a
            // trailing zero while doing so. The scale being asserted is a property of what crosses the
            // wire, so the wire form is what has to be read.
            assertThat(payload)
                    .as("the trailing zero of the second decimal place must survive")
                    .contains("\"currentBalance\":1234567890.10")
                    .as("the edited screen form is terminal geometry and must not be published")
                    .doesNotContain("+0000000123.45")
                    .doesNotContain("E+");
        }

        @Test
        @DisplayName("the account's post-settlement balance is not published, because the screen has "
                + "one balance field and the amount settled is always the whole of it")
        void thePostSettlementBalanceIsNotPublished() throws Exception {
            givenSettlementCompleted();

            final String payload = mockMvc.perform(post(BillPaymentController.BILL_PAYMENT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ACCOUNT_ID, "Y")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.currentBalance").value(new BigDecimal("123.45")))
                    .andReturn().getResponse().getContentAsString();

            assertThat(payload)
                    .doesNotContain("resultingBalance")
                    .doesNotContain("acctCurrBal")
                    .doesNotContain("version");
        }

        @Test
        @DisplayName("neither timestamp form is published, because the response has no timestamp "
                + "component and this boundary formats none")
        void neitherTimestampFormIsPublished() throws Exception {
            givenSettlementCompleted();

            final String payload = mockMvc.perform(post(BillPaymentController.BILL_PAYMENT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ACCOUNT_ID, "Y")))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(payload)
                    .as("the online form the service stamped into the posted record stays there")
                    .doesNotContain(ONLINE_TIMESTAMP)
                    .doesNotContain("tranOrigTs")
                    .doesNotContain("tranProcTs")
                    .as("the batch form is a different contract and appears nowhere on this screen")
                    .doesNotContain("2022-07-19-23.12.33");
        }

        @Test
        @DisplayName("no value the service stamps into the posted record leaks into the response, "
                + "because those are properties of the record rather than of this screen")
        void noPostedRecordConstantLeaksIntoTheResponse() throws Exception {
            givenSettlementCompleted();

            final String payload = mockMvc.perform(post(BillPaymentController.BILL_PAYMENT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ACCOUNT_ID, "Y")))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(payload)
                    .doesNotContain("BILL PAYMENT - ONLINE")
                    .doesNotContain("POS TERM")
                    .doesNotContain("999999999")
                    .doesNotContain("4111111111111111");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "Acct ID can NOT be empty...",
            "Invalid value. Valid values are (Y/N)...",
            "You have nothing to pay...",
            "Confirm to make a bill payment...",
            "Account ID NOT found...",
            "Unable to lookup Account...",
            "Unable to Update Account...",
            "Unable to lookup XREF AIX file...",
            "Transaction ID NOT found...",
            "Unable to lookup Transaction...",
            "Tran ID already exist...",
            "Unable to Add Bill pay Transaction...",
            "Invalid key pressed. Please see below...         "
        })
        @DisplayName("every screen text the transaction can report crosses byte for byte, including "
                + "the shared invalid-key text with all fifty of its characters untrimmed")
        void everyScreenTextCrossesByteForByte(final String text) throws Exception {
            givenOutcome(text, new BigDecimal("0.00"), true,
                    BillPaymentService.ConfirmPaymentFlag.NO, null,
                    BillPaymentResponse.ACCOUNT_ID_FIELD_ID, ACCOUNT_ID, "Y");

            mockMvc.perform(post(BillPaymentController.BILL_PAYMENT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ACCOUNT_ID, "Y")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errorMessage").value(text))
                    .andExpect(jsonPath("$.generalError").value(true));
        }

        @Test
        @DisplayName("the non-positive-balance refusal answers 200 with the refusal text and the "
                + "settlement flag clear, because the legacy composed that screen successfully")
        void theNonPositiveBalanceRefusalAnswersTwoHundred() throws Exception {
            givenOutcome(BillPaymentResponse.MSG_NOTHING_TO_PAY, new BigDecimal("0.00"), true,
                    BillPaymentService.ConfirmPaymentFlag.NO, null,
                    BillPaymentResponse.ACCOUNT_ID_FIELD_ID, ACCOUNT_ID, "Y");

            final String payload = mockMvc.perform(post(BillPaymentController.BILL_PAYMENT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ACCOUNT_ID, "Y")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errorMessage").value("You have nothing to pay..."))
                    .andExpect(jsonPath("$.paymentAccepted").value(false))
                    .andExpect(jsonPath("$.generalError").value(true))
                    .andReturn().getResponse().getContentAsString();

            // A zero balance still carries both decimal places on the wire, which is the point of the
            // refusal: the field is a fixed-scale decimal and not a rendered quantity.
            assertThat(payload).contains("\"currentBalance\":0.00");
        }

        @Test
        @DisplayName("a settled turn returns the echoed screen fields blank, exactly as the legacy "
                + "screen does, so no component may be required to be present")
        void aSettledTurnReturnsTheEchoedFieldsBlank() throws Exception {
            givenSettlementCompleted();

            mockMvc.perform(post(BillPaymentController.BILL_PAYMENT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ACCOUNT_ID, "Y")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountId").value(""))
                    .andExpect(jsonPath("$.confirm").value(""));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Replay, statelessness and instrumentation
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("replay, statelessness and instrumentation")
    class ReplayAndInstrumentation {

        @Test
        @DisplayName("a replayed submission is handed to the transaction again and answered from the "
                + "state the transaction reports, because the boundary keeps no replay cache, no "
                + "idempotency key and no memory of the previous turn")
        void aReplayedSubmissionIsAnsweredFromTheReportedState() throws Exception {
            givenSettlementCompleted();
            mockMvc.perform(post(BillPaymentController.BILL_PAYMENT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ACCOUNT_ID, "Y")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paymentAccepted").value(true));

            // The settlement drove the balance to zero, so the replay meets the non-positive-balance
            // refusal rather than settling a second time. That rule is the transaction's; what is
            // asserted here is that the boundary forwards the replay instead of short-circuiting it.
            givenOutcome(BillPaymentResponse.MSG_NOTHING_TO_PAY, new BigDecimal("0.00"), true,
                    BillPaymentService.ConfirmPaymentFlag.NO, null,
                    BillPaymentResponse.ACCOUNT_ID_FIELD_ID, ACCOUNT_ID, "Y");
            mockMvc.perform(post(BillPaymentController.BILL_PAYMENT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ACCOUNT_ID, "Y")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paymentAccepted").value(false))
                    .andExpect(jsonPath("$.errorMessage").value("You have nothing to pay..."))
                    .andExpect(jsonPath("$.newTransactionId").doesNotExist());

            verify(billPaymentService, times(2)).processBillPayment(any());
        }

        @Test
        @DisplayName("the controller holds no mutable state, so two turns cannot influence each other")
        void theControllerHoldsNoMutableState() {
            assertThat(Arrays.stream(BillPaymentController.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .filter(field -> !java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                    .toList())
                    .as("every declared field must be final")
                    .isEmpty();
        }

        @Test
        @DisplayName("the turn is timed once under a label set bounded to four series")
        void theTurnIsTimedUnderABoundedLabelSet() throws Exception {
            givenSettlementCompleted();

            mockMvc.perform(post(BillPaymentController.BILL_PAYMENT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ACCOUNT_ID, "Y")))
                    .andExpect(status().isOk());

            assertThat(meterRegistry.find("carddemo.online.billpayment.turn")
                    .tag("confirmation", "YES")
                    .tag("paymentAccepted", "true")
                    .timer())
                    .isNotNull()
                    .extracting(timer -> timer.count())
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("an unconfirmed turn and a settled turn are timed under different labels, so a "
                + "confirmed-but-refused turn cannot be merged with a settled one")
        void differentOutcomesAreTimedUnderDifferentLabels() throws Exception {
            givenOutcome(BillPaymentResponse.MSG_CONFIRM_BILL_PAYMENT, new BigDecimal("123.45"), false,
                    BillPaymentService.ConfirmPaymentFlag.NO, null,
                    BillPaymentResponse.CONFIRM_FIELD_ID, ACCOUNT_ID, " ");
            mockMvc.perform(post(BillPaymentController.BILL_PAYMENT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ACCOUNT_ID, " ")))
                    .andExpect(status().isOk());

            givenOutcome(BillPaymentResponse.MSG_UNABLE_TO_ADD_BILL_PAY_TRANSACTION,
                    new BigDecimal("123.45"), true, BillPaymentService.ConfirmPaymentFlag.YES, null,
                    BillPaymentResponse.ACCOUNT_ID_FIELD_ID, ACCOUNT_ID, "Y");
            mockMvc.perform(post(BillPaymentController.BILL_PAYMENT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ACCOUNT_ID, "Y")))
                    .andExpect(status().isOk());

            assertThat(meterRegistry.find("carddemo.online.billpayment.turn")
                    .tag("confirmation", "NO").timer())
                    .isNotNull();
            assertThat(meterRegistry.find("carddemo.online.billpayment.turn")
                    .tag("confirmation", "YES").tag("paymentAccepted", "false").timer())
                    .as("a confirmed turn that failed to post is not a settled turn")
                    .isNotNull();
        }
    }
}
