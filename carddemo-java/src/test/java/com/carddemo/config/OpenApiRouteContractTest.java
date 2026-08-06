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
package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.carddemo.api.AccountController;
import com.carddemo.api.AccountProtectedDataAdapter;
import com.carddemo.api.AccountUpdateContractAdapter;
import com.carddemo.api.AdminUserController;
import com.carddemo.api.AuthController;
import com.carddemo.api.BatchJobController;
import com.carddemo.api.BillPaymentController;
import com.carddemo.api.CardController;
import com.carddemo.api.ConversationStateAdapter;
import com.carddemo.api.MenuController;
import com.carddemo.api.MenuResponseAdapter;
import com.carddemo.api.PublishedContractTypeRoster;
import com.carddemo.api.ReportContractAdapter;
import com.carddemo.api.ReportController;
import com.carddemo.api.ScreenStateAdapter;
import com.carddemo.api.SignOnContractAdapter;
import com.carddemo.api.TransactionController;
import com.carddemo.api.UserContractAdapter;
import com.carddemo.service.AccountUpdateService;
import com.carddemo.service.AccountViewService;
import com.carddemo.service.AuthenticationService;
import com.carddemo.service.BillPaymentService;
import com.carddemo.service.BatchJobLaunchService;
import com.carddemo.service.CardConcurrencyTokenService;
import com.carddemo.service.CardDetailService;
import com.carddemo.service.CardListService;
import com.carddemo.service.CardUpdateService;
import com.carddemo.service.MenuService;
import com.carddemo.service.ReportRequestService;
import com.carddemo.service.TransactionAddService;
import com.carddemo.service.TransactionListService;
import com.carddemo.service.TransactionViewService;
import com.carddemo.service.UserManagementService;
import com.carddemo.util.SessionTokenIssuer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Served-document verification for the complete controller inventory.
 *
 * <p>The standalone {@link OpenApiConfig} tests prove that shared metadata and schemas are assembled
 * correctly. They cannot prove that the controller scan merges those components with the routes the
 * application actually publishes. This slice therefore starts a real MVC application context, fetches
 * {@code /v3/api-docs}, parses the bytes returned by Springdoc, and verifies the nineteen-operation
 * contract end to end without invoking a business collaborator.</p>
 */
@WebMvcTest(controllers = {
    AccountController.class,
    AdminUserController.class,
    AuthController.class,
    BatchJobController.class,
    BillPaymentController.class,
    CardController.class,
    MenuController.class,
    ReportController.class,
    TransactionController.class
}, properties = {
    "springdoc.api-docs.enabled=true",
    "springdoc.api-docs.path=/v3/api-docs"
})
@AutoConfigureMockMvc(addFilters = false)
@Import({
    OpenApiConfig.class,
    PublishedContractTypeRoster.class,
    SpringDocConfiguration.class,
    SpringDocConfigProperties.class,
    SpringDocWebMvcConfiguration.class
})
@DisplayName("served OpenAPI contract - all delivered routes and schemas")
class OpenApiRouteContractTest {

    private static final List<String> HTTP_METHODS =
            List.of("get", "post", "put", "patch", "delete", "options", "head", "trace");

    private static final Map<String, String> ERROR_RESPONSES = Map.of(
            "400", OpenApiConfig.BAD_REQUEST_RESPONSE,
            "401", OpenApiConfig.UNAUTHORIZED_RESPONSE,
            "403", OpenApiConfig.FORBIDDEN_RESPONSE,
            "404", OpenApiConfig.NOT_FOUND_RESPONSE,
            "409", OpenApiConfig.CONFLICT_RESPONSE,
            "500", OpenApiConfig.INTERNAL_SERVER_ERROR_RESPONSE);

    /**
     * The statuses that are reachable on a route carrying a request body, behind a credential, and
     * addressing its record by a value in that body rather than by a path segment: a body can be refused
     * before dispatch, a missing or insufficient credential is refused, and any handler can fail.
     *
     * <p>{@code 404} is deliberately absent. The screen routes answer an absent record with a message on
     * the screen and status {@code 200}, exactly as the legacy transactions did, so publishing an
     * absent-resource status against them would contradict the contract they exist to keep.
     */
    private static final Set<String> SECURED_SCREEN_STATUSES = Set.of("400", "401", "403", "500");

    /** A secured screen route that also rewrites a record, and so can lose a version race. */
    private static final Set<String> SECURED_WRITE_STATUSES = Set.of("400", "401", "403", "409", "500");

    /** A secured route addressing its resource by a path segment, which may name nothing. */
    private static final Set<String> SECURED_PATH_STATUSES = Set.of("400", "401", "403", "404", "500");

    /**
     * The reachable error statuses of every published operation.
     *
     * <p>This table is the whole point of the finding it pins: a customizer that attached the same six
     * statuses to every operation advertised outcomes that cannot occur - an anonymous sign-on that
     * answers {@code 401}, a screen route that answers {@code 404} - and a published outcome that cannot
     * occur is a contract a client writes handling for and never exercises.
     */
    private static final Map<String, Set<String>> REACHABLE_ERRORS = reachableErrors();

    private static final Set<String> EXPECTED_OPERATIONS = Set.of(
            "POST " + AccountController.ACCOUNT_VIEW_PATH,
            "POST " + AccountController.ACCOUNT_UPDATE_PATH,
            "POST " + AdminUserController.USERS_PATH + AdminUserController.LIST_SUBPATH,
            "POST " + AdminUserController.USERS_PATH + AdminUserController.ADD_SUBPATH,
            "POST " + AdminUserController.USERS_PATH + AdminUserController.UPDATE_SUBPATH,
            "POST " + AdminUserController.USERS_PATH + AdminUserController.DELETE_SUBPATH,
            "POST " + AuthController.SIGN_ON_PATH,
            // First entry to the sign-on screen. Documented as a GET operation, and therefore mapped as
            // one: a described operation the router does not publish is a contract a client cannot call.
            "GET " + AuthController.SIGN_ON_PATH,
            "POST " + BatchJobController.BATCH_JOBS_PATH + BatchJobController.LAUNCH_SUBPATH,
            "GET " + BatchJobController.BATCH_JOBS_PATH + BatchJobController.EXECUTION_SUBPATH,
            "POST " + BillPaymentController.BILL_PAYMENT_PATH,
            "POST " + CardController.CARDS_BASE_PATH + CardController.CARD_LIST_PATH,
            "POST " + CardController.CARDS_BASE_PATH + CardController.CARD_DETAIL_PATH,
            "POST " + CardController.CARDS_BASE_PATH + CardController.CARD_UPDATE_PATH,
            "POST " + MenuController.USER_MENU_PATH,
            "POST " + MenuController.ADMIN_MENU_PATH,
            "POST " + ReportController.REPORT_REQUEST_PATH,
            "POST " + TransactionController.TRANSACTION_PATH + TransactionController.LIST_PATH,
            "POST " + TransactionController.TRANSACTION_PATH + TransactionController.VIEW_PATH,
            "POST " + TransactionController.TRANSACTION_PATH + TransactionController.ADD_PATH);

    @Autowired
    private MockMvc client;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AccountViewService accountViewService;

    @MockitoBean
    private AccountUpdateService accountUpdateService;

    @MockitoBean
    private AccountProtectedDataAdapter accountProtectedDataAdapter;

    @MockitoBean
    private ScreenStateAdapter screenStateAdapter;

    @MockitoBean
    private AccountUpdateContractAdapter accountUpdateContractAdapter;

    @MockitoBean
    private UserManagementService userManagementService;

    @MockitoBean
    private UserContractAdapter userContractAdapter;

    @MockitoBean
    private AuthenticationService authenticationService;

    @MockitoBean
    private SignOnContractAdapter signOnContractAdapter;

    @MockitoBean
    private SessionTokenIssuer sessionTokenIssuer;

    @MockitoBean
    private JobRegistry jobRegistry;

    @MockitoBean
    private JobOperator jobOperator;

    @MockitoBean
    private JobExplorer jobExplorer;

    @MockitoBean
    private BatchJobLaunchService batchJobLaunchService;

    @MockitoBean
    private BillPaymentService billPaymentService;

    @MockitoBean
    private CardListService cardListService;

    @MockitoBean
    private CardDetailService cardDetailService;

    @MockitoBean
    private CardUpdateService cardUpdateService;

    @MockitoBean
    private CardConcurrencyTokenService cardConcurrencyTokenService;

    @MockitoBean
    private MenuService menuService;

    @MockitoBean
    private ConversationStateAdapter conversationStateAdapter;

    @MockitoBean
    private MenuResponseAdapter menuResponseAdapter;

    @MockitoBean
    private ReportRequestService reportRequestService;

    @MockitoBean
    private ReportContractAdapter reportContractAdapter;

    @MockitoBean
    private TransactionListService transactionListService;

    @MockitoBean
    private TransactionViewService transactionViewService;

    @MockitoBean
    private TransactionAddService transactionAddService;

    @MockitoBean
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("the served document publishes exactly twenty typed operations and shared errors")
    void theServedDocumentPublishesTheCompleteTypedContract() throws Exception {
        final String payload = this.client.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        final JsonNode document = this.objectMapper.readTree(payload);
        final Map<String, JsonNode> operations = operationsOf(document.path("paths"));

        assertThat(operations.keySet())
                .containsExactlyInAnyOrderElementsOf(EXPECTED_OPERATIONS)
                .hasSize(20);
        operations.forEach((route, operation) -> {
            assertTypedSuccess(route, operation.path("responses").path("200"));
            assertTypedRequest(route, operation);
            assertReachableErrorsOnly(route, operation);
        });

        assertThat(document.at("/components/schemas/ErrorResponse").isObject()).isTrue();
        assertThat(document.at("/components/headers/BearerAuthorization").isObject()).isTrue();
        ERROR_RESPONSES.forEach((statusCode, component) ->
                assertThat(document.at("/components/responses/" + component
                        + "/content/application~1json/schema/$ref").asText())
                        .as("shared %s error schema", statusCode)
                        .isEqualTo("#/components/schemas/ErrorResponse"));
        assertThat(document.at("/paths/~1api~1auth~1signon/post/responses/200/headers/"
                + "Authorization/$ref").asText())
                .isEqualTo(OpenApiConfig.AUTHORIZATION_HEADER_REFERENCE);
        assertThat(document.at("/paths/~1api~1auth~1signon/post/security").isArray()).isTrue();
        assertThat(document.at("/paths/~1api~1auth~1signon/post/security").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("the anonymous sign-on operations publish no credential rejection, because a route that "
            + "requires no credential cannot refuse one")
    void theAnonymousOperationsPublishNoCredentialRejection() throws Exception {
        final JsonNode document = servedDocument();

        for (final String method : List.of("get", "post")) {
            final JsonNode operation = document.at("/paths/~1api~1auth~1signon/" + method);

            assertThat(operation.isObject()).as("%s sign-on is published", method).isTrue();
            assertThat(operation.path("responses").path("401").isMissingNode())
                    .as("%s sign-on advertises no 401", method)
                    .isTrue();
            assertThat(operation.path("responses").path("403").isMissingNode())
                    .as("%s sign-on advertises no 403", method)
                    .isTrue();
            assertThat(operation.path("responses").path("500").path("$ref").asText())
                    .as("%s sign-on can still fail, and says so", method)
                    .isEqualTo("#/components/responses/"
                            + OpenApiConfig.INTERNAL_SERVER_ERROR_RESPONSE);
        }
        assertThat(document.at("/paths/~1api~1auth~1signon/get/responses").properties())
                .as("first entry takes no input, so nothing but the terminal failure is reachable")
                .hasSize(2);
    }

    @Test
    @DisplayName("a screen route publishes no absent-resource status, because it answers an absent record "
            + "with a message on the screen and status 200, exactly as the legacy transaction did")
    void aScreenRoutePublishesNoAbsentResourceStatus() throws Exception {
        final JsonNode document = servedDocument();

        for (final String route : List.of(AccountController.ACCOUNT_VIEW_PATH,
                CardController.CARDS_BASE_PATH + CardController.CARD_DETAIL_PATH,
                TransactionController.TRANSACTION_PATH + TransactionController.VIEW_PATH)) {
            final JsonNode operation =
                    document.at("/paths/" + route.replace("/", "~1") + "/post");

            assertThat(operation.isObject()).as("%s is published", route).isTrue();
            assertThat(operation.path("responses").path("404").isMissingNode())
                    .as("%s answers absence on the screen, so it advertises no 404", route)
                    .isTrue();
            assertThat(operation.path("responses").path("200").isObject())
                    .as("%s answers the screen, present record or not", route)
                    .isTrue();
        }
        assertThat(document.at("/paths/" + (BatchJobController.BATCH_JOBS_PATH
                + BatchJobController.EXECUTION_SUBPATH).replace("/", "~1")
                + "/get/responses/404/$ref").asText())
                .as("an operational route addressed by a path segment does publish one, so the rule "
                        + "distinguishes the two rather than suppressing the status everywhere")
                .isEqualTo("#/components/responses/" + OpenApiConfig.NOT_FOUND_RESPONSE);
    }

    @Test
    @DisplayName("the published launch schema declares one property per parameter a registered job "
            + "accepts, and names no generation or dataset the consolidation job resolves for itself")
    void thePublishedLaunchSchemaDeclaresOnlyRealParameters() throws Exception {
        final JsonNode properties =
                servedDocument().at("/components/schemas/BatchJobLaunchRequest/properties");

        final List<String> declared = properties.properties().stream()
                .map(Map.Entry::getKey)
                .toList();

        assertThat(properties.isObject()).isTrue();
        assertThat(declared)
                .containsExactlyInAnyOrder("interestParmDate", "reportStartDate", "reportEndDate",
                        "fileProbeMode");
        assertThat(declared)
                .as("a caller cannot be led to name an input the job reads from the store")
                .doesNotContain("transactionBackupCurrentGeneration",
                        "synthesizedTransactionCurrentGeneration");
    }

    private JsonNode servedDocument() throws Exception {
        return this.objectMapper.readTree(this.client.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private static Map<String, JsonNode> operationsOf(final JsonNode paths) {
        final Map<String, JsonNode> operations = new LinkedHashMap<>();
        paths.properties().forEach(path -> HTTP_METHODS.forEach(method -> {
            if (path.getValue().has(method)) {
                operations.put(method.toUpperCase(java.util.Locale.ROOT) + " " + path.getKey(),
                        path.getValue().path(method));
            }
        }));
        return operations;
    }

    private static void assertTypedSuccess(final String route, final JsonNode response) {
        final JsonNode schema = response.path("content").path("application/json").path("schema");
        assertThat(schema.path("$ref").asText())
                .as("%s successful response schema", route)
                .startsWith("#/components/schemas/");
    }

    private static void assertTypedRequest(final String route, final JsonNode operation) {
        final JsonNode requestBody = operation.path("requestBody");
        if (!requestBody.isMissingNode()) {
            final JsonNode schema =
                    requestBody.path("content").path("application/json").path("schema");
            assertThat(schema.path("$ref").asText())
                    .as("%s request-body schema", route)
                    .startsWith("#/components/schemas/");
        }
        operation.path("parameters").forEach(parameter -> {
            final JsonNode schema = parameter.path("schema");
            assertThat(schema.has("$ref") || schema.has("type"))
                    .as("%s parameter %s has a typed schema", route,
                            parameter.path("name").asText())
                    .isTrue();
        });
    }

    /**
     * Asserts that the operation publishes exactly the error statuses it can reach - each of them
     * referring to the one shared component for that status, and none of the others present at all.
     *
     * @param route     the method and path the operation is published under
     * @param operation the operation node from the served document
     */
    private static void assertReachableErrorsOnly(final String route, final JsonNode operation) {
        final Set<String> expected = REACHABLE_ERRORS.get(route);
        assertThat(expected).as("%s is covered by the reachable-status table", route).isNotNull();

        ERROR_RESPONSES.forEach((statusCode, component) -> {
            final JsonNode declared = operation.path("responses").path(statusCode);
            if (expected.contains(statusCode)) {
                assertThat(declared.path("$ref").asText())
                        .as("%s reaches %s and refers to the one shared component", route, statusCode)
                        .isEqualTo("#/components/responses/" + component);
            } else {
                assertThat(declared.isMissingNode())
                        .as("%s cannot reach %s, so it must not be advertised", route, statusCode)
                        .isTrue();
            }
        });
    }

    /**
     * Builds the reachable-status table, one entry per published operation.
     *
     * @return the statuses each route may answer with, never {@code null}
     */
    private static Map<String, Set<String>> reachableErrors() {
        final Map<String, Set<String>> reachable = new LinkedHashMap<>();

        // Anonymous: the two sign-on entries carry no credential, so neither can be refused for one.
        // First entry takes no input at all, so it cannot even be refused before dispatch.
        reachable.put("GET " + AuthController.SIGN_ON_PATH, Set.of("500"));
        reachable.put("POST " + AuthController.SIGN_ON_PATH, Set.of("400", "500"));

        // Addressed by a path segment, so an absent resource is reachable.
        reachable.put("POST " + BatchJobController.BATCH_JOBS_PATH + BatchJobController.LAUNCH_SUBPATH,
                SECURED_PATH_STATUSES);
        reachable.put("GET " + BatchJobController.BATCH_JOBS_PATH + BatchJobController.EXECUTION_SUBPATH,
                SECURED_PATH_STATUSES);

        // Rewrites a record under an optimistic version, so a lost race is reachable.
        reachable.put("POST " + AccountController.ACCOUNT_UPDATE_PATH, SECURED_WRITE_STATUSES);
        reachable.put("POST " + BillPaymentController.BILL_PAYMENT_PATH, SECURED_WRITE_STATUSES);
        reachable.put("POST " + CardController.CARDS_BASE_PATH + CardController.CARD_UPDATE_PATH,
                SECURED_WRITE_STATUSES);

        // Every remaining route is a secured screen turn reading its record from the body.
        List.of("POST " + AccountController.ACCOUNT_VIEW_PATH,
                        "POST " + AdminUserController.USERS_PATH + AdminUserController.LIST_SUBPATH,
                        "POST " + AdminUserController.USERS_PATH + AdminUserController.ADD_SUBPATH,
                        "POST " + AdminUserController.USERS_PATH + AdminUserController.UPDATE_SUBPATH,
                        "POST " + AdminUserController.USERS_PATH + AdminUserController.DELETE_SUBPATH,
                        "POST " + CardController.CARDS_BASE_PATH + CardController.CARD_LIST_PATH,
                        "POST " + CardController.CARDS_BASE_PATH + CardController.CARD_DETAIL_PATH,
                        "POST " + MenuController.USER_MENU_PATH,
                        "POST " + MenuController.ADMIN_MENU_PATH,
                        "POST " + ReportController.REPORT_REQUEST_PATH,
                        "POST " + TransactionController.TRANSACTION_PATH
                                + TransactionController.LIST_PATH,
                        "POST " + TransactionController.TRANSACTION_PATH
                                + TransactionController.VIEW_PATH,
                        "POST " + TransactionController.TRANSACTION_PATH
                                + TransactionController.ADD_PATH)
                .forEach(route -> reachable.put(route, SECURED_SCREEN_STATUSES));

        return Map.copyOf(reachable);
    }
}
