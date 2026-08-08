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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.config.OpenApiConfig;
import com.carddemo.config.SecurityConfig;
import com.carddemo.service.AccountUpdateService;
import com.carddemo.service.AccountViewService;
import com.carddemo.service.AuthenticationService;
import com.carddemo.service.BatchJobLaunchService;
import com.carddemo.service.BillPaymentService;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The one independent oracle for the delivered HTTP surface.
 *
 * <h2>Why an oracle, and why literals</h2>
 * Every other route specification in this module reaches its expectation through the production
 * constants: {@code CardController.CARDS_BASE_PATH + CardController.CARD_DETAIL_PATH} is the expected
 * path, and the controller's own mapping is the actual one. Both sides then move together. A test built
 * that way proves that a constant is used consistently and proves nothing about what the constant
 * <em>is</em>, so a wrong verb or a missing operation can sit inside a green suite indefinitely - which is
 * exactly what happened: the security classification table recorded card detail as a {@code GET} while the
 * controller mapped a {@code POST}, and the published contract text announced nineteen operations while
 * twenty were mapped, because sign-on's first-entry turn was never counted.
 *
 * <p>So the table below is written in <strong>literal method-and-full-path strings</strong>. It references
 * no controller constant, no configuration constant and no path fragment, and
 * {@link TheOracleIsIndependent#theOracleReferencesNoProductionConstant()} reads this file's own source
 * and fails if one appears inside it. The oracle is therefore an independent statement of what this module
 * publishes, in the form a caller sees it, and everything else in this class is a comparison against it.
 *
 * <h2>What is compared against the oracle</h2>
 * <ul>
 *   <li><strong>The router's own inventory.</strong> The real
 *       {@link RequestMappingHandlerMapping} of a started MVC context is enumerated, which is what
 *       actually decides whether a request reaches a handler - not an annotation read by reflection and
 *       not a regular expression over source text.</li>
 *   <li><strong>The served interface description.</strong> {@code /v3/api-docs} is fetched and its
 *       path-and-method inventory compared, so the document a client generates a client from agrees.</li>
 *   <li><strong>The figures the published description states.</strong> The twenty operations and nineteen
 *       paths named in {@code OpenApiConfig}'s description are parsed out of the served document and
 *       compared to the oracle's own counts, so published prose cannot disagree with the surface.</li>
 *   <li><strong>The security classification.</strong> Each literal path is classified by the prefixes the
 *       filter chain publishes, and the classification is compared to the one the oracle declares.</li>
 *   <li><strong>The module manual.</strong> The route table in {@code README.md} is parsed and compared,
 *       so the documented surface and the served surface cannot drift apart.</li>
 *   <li><strong>Dispatch of the three paths this defect concerned.</strong> {@code /api/cards/detail},
 *       {@code /api/menu} and {@code /api/admin/menu} are requested at their literal addresses through
 *       {@code MockMvc}; the detail route is additionally requested with the wrong verb, which must be
 *       refused as an unsupported method rather than answered.</li>
 * </ul>
 *
 * <p>Business behaviour is deliberately absent here. Every collaborator is a mock and no assertion below
 * concerns a response body: what is under test is the surface, and each operation's behaviour is specified
 * by its own controller and service suites.
 *
 * <p>Provenance: legacy checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy transaction identifiers below are
 * metadata read from {@code app/csd/CARDDEMO.CSD}; no legacy source text is reproduced.
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
@DisplayName("the delivered API surface, against one independent literal oracle")
class DeliveredApiSurfaceOracleTest {

    /** How an operation is entitled: by nothing, by any verified credential, or by administrative right. */
    private enum Access {

        /** Reachable with no credential at all. */
        ANONYMOUS,

        /** Reachable by any caller whose credential verified. */
        AUTHENTICATED,

        /** Reachable only by a caller carrying the administrative authority. */
        ADMINISTRATOR
    }

    /**
     * One published operation, stated as a caller sees it.
     *
     * @param method      the HTTP method, in upper case
     * @param path        the full path, from the leading solidus
     * @param access      the entitlement the operation must require
     * @param transaction the legacy transaction identifier it reproduces, or {@code null} where it has no
     *                    legacy counterpart
     */
    private record PublishedOperation(String method, String path, Access access, String transaction) {

        /**
         * Renders the operation the way both compared inventories render it.
         *
         * @return the method and path, separated by one space
         */
        String signature() {
            return this.method + " " + this.path;
        }
    }

    // ===================================================================================================
    // ORACLE BEGIN - literal strings only. No production constant may appear between these two markers;
    // theOracleReferencesNoProductionConstant() reads this file and fails if one does.
    // ===================================================================================================

    /** Every operation this module publishes, written out rather than derived. */
    private static final List<PublishedOperation> ORACLE = List.of(
            new PublishedOperation("GET", "/api/auth/signon", Access.ANONYMOUS, "CC00"),
            new PublishedOperation("POST", "/api/auth/signon", Access.ANONYMOUS, "CC00"),
            new PublishedOperation("POST", "/api/menu", Access.AUTHENTICATED, "CM00"),
            new PublishedOperation("POST", "/api/admin/menu", Access.ADMINISTRATOR, "CA00"),
            new PublishedOperation("POST", "/api/accounts/view", Access.AUTHENTICATED, "CAVW"),
            new PublishedOperation("POST", "/api/accounts/update", Access.AUTHENTICATED, "CAUP"),
            new PublishedOperation("POST", "/api/cards/list", Access.AUTHENTICATED, "CCLI"),
            new PublishedOperation("POST", "/api/cards/detail", Access.AUTHENTICATED, "CCDL"),
            new PublishedOperation("POST", "/api/cards/update", Access.AUTHENTICATED, "CCUP"),
            new PublishedOperation("POST", "/api/transactions/list", Access.AUTHENTICATED, "CT00"),
            new PublishedOperation("POST", "/api/transactions/view", Access.AUTHENTICATED, "CT01"),
            new PublishedOperation("POST", "/api/transactions/add", Access.AUTHENTICATED, "CT02"),
            new PublishedOperation("POST", "/api/reports/request", Access.AUTHENTICATED, "CR00"),
            new PublishedOperation("POST", "/api/bill-payment", Access.AUTHENTICATED, "CB00"),
            new PublishedOperation("POST", "/api/admin/users/list", Access.ADMINISTRATOR, "CU00"),
            new PublishedOperation("POST", "/api/admin/users/add", Access.ADMINISTRATOR, "CU01"),
            new PublishedOperation("POST", "/api/admin/users/update", Access.ADMINISTRATOR, "CU02"),
            new PublishedOperation("POST", "/api/admin/users/delete", Access.ADMINISTRATOR, "CU03"),
            new PublishedOperation("POST", "/api/batch/jobs/{jobName}/launch", Access.ADMINISTRATOR, null),
            new PublishedOperation("GET", "/api/batch/jobs/executions/{executionId}",
                    Access.ADMINISTRATOR, null));

    /** The one path reachable without a credential, because it is the path that issues them. */
    private static final String SIGN_ON_ADDRESS = "/api/auth/signon";

    /** The prefix every administrative screen route sits beneath. */
    private static final String ADMIN_REGION = "/api/admin";

    /** The prefix the batch-control routes sit beneath. */
    private static final String BATCH_REGION = "/api/batch";

    /** The card-detail address, which this defect concerned directly. */
    private static final String CARD_DETAIL_ADDRESS = "/api/cards/detail";

    /** The ordinary menu address. */
    private static final String USER_MENU_ADDRESS = "/api/menu";

    /** The administrative menu address. */
    private static final String ADMIN_MENU_ADDRESS = "/api/admin/menu";

    // ===================================================================================================
    // ORACLE END
    // ===================================================================================================

    /** Where the served interface description is published. */
    private static final String CONTRACT_PATH = "/v3/api-docs";

    /** This class's own source, read so the oracle's independence can be asserted rather than promised. */
    private static final Path OWN_SOURCE = Path.of("src", "test", "java", "com", "carddemo", "api",
            "DeliveredApiSurfaceOracleTest.java");

    /** The module manual, whose route table must publish the same surface. */
    private static final Path MODULE_README = Path.of("README.md");

    /** Marker opening the region of this file the independence check applies to. */
    private static final String ORACLE_REGION_START = "// ORACLE BEGIN";

    /** Marker closing that region. */
    private static final String ORACLE_REGION_END = "// ORACLE END";

    /** The HTTP methods a published document may carry an operation under. */
    private static final List<String> DOCUMENT_METHODS =
            List.of("get", "post", "put", "patch", "delete", "options", "head", "trace");

    /**
     * A row of the manual's route table: a backquoted method and path in the first cell.
     *
     * <p>Anchored at the row start so a mention of a route in prose cannot be mistaken for a table row,
     * and bounded by the cell separator so trailing description text is not swept into the path.
     */
    private static final Pattern README_ROUTE_ROW =
            Pattern.compile("^\\|\\s*`(GET|POST|PUT|PATCH|DELETE) (/api/\\S*)`\\s*\\|", Pattern.MULTILINE);

    /** The operation count the published description states. */
    private static final Pattern PUBLISHED_OPERATION_COUNT =
            Pattern.compile("publishes (\\d+) operations");

    /** The path count the published description states. */
    private static final Pattern PUBLISHED_PATH_COUNT = Pattern.compile("over (\\d+) paths");

    /** The operation count the module manual states. */
    private static final Pattern README_OPERATION_COUNT =
            Pattern.compile("publishes \\*\\*(\\d+) operations");

    @Autowired
    private MockMvc client;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

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

    /**
     * The oracle's signatures, in sorted order so a difference reads as a difference and not as an order.
     *
     * @return every published operation as {@code METHOD /path}
     */
    private static Set<String> oracleSignatures() {
        final Set<String> signatures = new TreeSet<>();
        for (final PublishedOperation operation : ORACLE) {
            signatures.add(operation.signature());
        }
        return signatures;
    }

    /**
     * The distinct paths the oracle names, which is fewer than the operations because sign-on has two.
     *
     * @return the distinct paths
     */
    private static Set<String> oraclePaths() {
        final Set<String> paths = new LinkedHashSet<>();
        for (final PublishedOperation operation : ORACLE) {
            paths.add(operation.path());
        }
        return paths;
    }

    /**
     * Classifies a literal path by the prefixes the filter chain publishes.
     *
     * <p>This is the chain's precedence written once: the sign-on exemption is declared before the region
     * rules, the region rules before the closing rule, and the closing rule admits any verified caller.
     *
     * @param path the full path
     * @return the entitlement the chain requires of it
     */
    private static Access chainClassificationOf(final String path) {
        if (SIGN_ON_ADDRESS.equals(path)) {
            return Access.ANONYMOUS;
        }
        if (path.startsWith(ADMIN_REGION) || path.startsWith(BATCH_REGION)) {
            return Access.ADMINISTRATOR;
        }
        return Access.AUTHENTICATED;
    }

    /**
     * Reads the served interface description.
     *
     * @return the parsed document
     * @throws Exception if the document cannot be fetched or parsed
     */
    private JsonNode servedContract() throws Exception {
        final String payload = this.client.perform(get(CONTRACT_PATH))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return this.objectMapper.readTree(payload);
    }

    /**
     * Extracts a stated figure from published text.
     *
     * @param  pattern the pattern whose first group is the figure
     * @param  text    the published text
     * @return the figure
     */
    private static int statedFigure(final Pattern pattern, final String text) {
        final Matcher stated = pattern.matcher(text);
        assertThat(stated.find())
                .as("the published text must state the figure this pattern reads, so that a wrong figure "
                        + "is a failing comparison rather than a misleading sentence: %s", pattern)
                .isTrue();
        return Integer.parseInt(stated.group(1));
    }

    @Nested
    @DisplayName("the oracle is independent of the code it measures")
    class TheOracleIsIndependent {

        /** Creates the nested specification. */
        TheOracleIsIndependent() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("references no production constant, so it states the surface rather than restating "
                + "whatever the surface currently says")
        void theOracleReferencesNoProductionConstant() throws IOException {
            final String source = Files.readString(OWN_SOURCE, StandardCharsets.UTF_8);
            final int start = source.indexOf(ORACLE_REGION_START);
            final int end = source.indexOf(ORACLE_REGION_END);
            assertThat(start).as("the oracle region must be delimited").isNotNegative();
            assertThat(end).as("the oracle region must be closed").isGreaterThan(start);

            final String region = source.substring(start, end);

            assertThat(region)
                    .as("a path assembled from a controller's own constant moves whenever the controller "
                            + "moves, which is the coupling this oracle exists to break")
                    .doesNotContain("_PATH")
                    .doesNotContain("_SUBPATH")
                    .doesNotContain("_PREFIX")
                    .doesNotContain("Controller.")
                    .doesNotContain("SecurityConfig.")
                    .doesNotContain("ApiRoutePaths");
            assertThat(region)
                    .as("and the addresses must be present as literals, or there is nothing to compare "
                            + "the code against")
                    .contains("\"/api/cards/detail\"")
                    .contains("\"/api/auth/signon\"")
                    .contains("\"/api/menu\"")
                    .contains("\"/api/admin/menu\"");
        }

        @Test
        @DisplayName("names twenty operations over nineteen paths, and no operation twice")
        void theOracleNamesTwentyOperationsOverNineteenPaths() {
            assertAll(
                    () -> assertThat(ORACLE).hasSize(20),
                    () -> assertThat(oracleSignatures())
                            .as("two operations with one signature would make the comparison ambiguous")
                            .hasSize(20),
                    () -> assertThat(oraclePaths())
                            .as("sign-on's two turns share one path, so nineteen paths carry twenty "
                                    + "operations")
                            .hasSize(19));
        }

        @Test
        @DisplayName("and the literal prefixes it classifies by are the prefixes the filter chain "
                + "actually publishes")
        void theLiteralPrefixesAreTheChainsOwn() {
            // The only place this class touches the configuration: the literals above are compared to the
            // constants the chain gates by, so a renamed prefix fails here instead of silently leaving
            // every classification below measuring an address nothing gates.
            assertAll(
                    () -> assertThat(SecurityConfig.ADMIN_PATH_PREFIX).isEqualTo(ADMIN_REGION),
                    () -> assertThat(SecurityConfig.BATCH_CONTROL_PATH_PREFIX)
                            .isEqualTo(BATCH_REGION));
        }
    }

    @Nested
    @DisplayName("the router publishes exactly the oracle")
    class TheRouterAgrees {

        /** Creates the nested specification. */
        TheRouterAgrees() {
            // Intentionally empty.
        }

        /**
         * Every operation the started context's router will dispatch.
         *
         * @return the router's inventory as {@code METHOD /path}
         */
        private Set<String> routerInventory() {
            final Set<String> mapped = new TreeSet<>();
            for (final RequestMappingInfo info
                    : DeliveredApiSurfaceOracleTest.this.handlerMapping.getHandlerMethods().keySet()) {
                final Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                for (final String pattern : info.getPatternValues()) {
                    if (pattern.startsWith("/api/")) {
                        for (final RequestMethod method : methods) {
                            mapped.add(method.name() + " " + pattern);
                        }
                    }
                }
            }
            return mapped;
        }

        @Test
        @DisplayName("so every method and path a caller may address is the one written down, and the "
                + "card-detail operation is a POST")
        void theRouterInventoryIsTheOracle() {
            assertThat(routerInventory())
                    .as("this is the inventory that decides whether a request reaches a handler. A "
                            + "difference here means the surface a caller meets is not the surface this "
                            + "module says it publishes")
                    .isEqualTo(oracleSignatures());
            assertThat(routerInventory())
                    .as("the verb the earlier classification table got wrong")
                    .contains("POST " + CARD_DETAIL_ADDRESS)
                    .doesNotContain("GET " + CARD_DETAIL_ADDRESS);
        }

        @Test
        @DisplayName("and both sign-on turns are published on the one exempted path, which is why the "
                + "operation count exceeds the path count by exactly one")
        void bothSignOnTurnsArePublished() {
            assertThat(routerInventory())
                    .contains("GET " + SIGN_ON_ADDRESS, "POST " + SIGN_ON_ADDRESS);
            assertThat(routerInventory()).hasSize(oraclePaths().size() + 1);
        }
    }

    @Nested
    @DisplayName("the served interface description publishes exactly the oracle")
    class TheServedContractAgrees {

        /** Creates the nested specification. */
        TheServedContractAgrees() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("so a client generated from the document addresses the operations the router serves")
        void theDocumentInventoryIsTheOracle() throws Exception {
            final JsonNode paths = servedContract().path("paths");
            final Set<String> published = new TreeSet<>();
            for (final Map.Entry<String, JsonNode> entry : paths.properties()) {
                for (final String method : DOCUMENT_METHODS) {
                    if (entry.getValue().has(method)) {
                        published.add(method.toUpperCase(Locale.ROOT) + " " + entry.getKey());
                    }
                }
            }

            assertThat(published)
                    .as("the document and the router are two publications of one surface")
                    .isEqualTo(oracleSignatures());
        }

        @Test
        @DisplayName("and the figures its description states are the oracle's own counts, so the prose "
                + "cannot announce an inventory the code does not have")
        void theStatedFiguresAreTheOraclesCounts() throws Exception {
            final String description = servedContract().at("/info/description").asText();

            assertAll(
                    () -> assertThat(statedFigure(PUBLISHED_OPERATION_COUNT, description))
                            .as("the description once announced nineteen while twenty were mapped")
                            .isEqualTo(ORACLE.size()),
                    () -> assertThat(statedFigure(PUBLISHED_PATH_COUNT, description))
                            .isEqualTo(oraclePaths().size()),
                    () -> assertThat(description)
                            .as("and the composition must be stated, because the two figures differ for "
                                    + "one reason and a reader is owed it")
                            .contains("18 operations")
                            .contains("2 administrator-only batch-control operations"));
        }
    }

    @Nested
    @DisplayName("every operation's entitlement is the one the oracle declares")
    class TheEntitlementsAgree {

        /** Creates the nested specification. */
        TheEntitlementsAgree() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("so no operation is on a side of the chain its published classification does not name")
        void everyClassificationAgreesWithTheChain() {
            final List<String> disagreements = new ArrayList<>();
            for (final PublishedOperation operation : ORACLE) {
                final Access actual = chainClassificationOf(operation.path());
                if (actual != operation.access()) {
                    disagreements.add(operation.signature() + " is published as " + operation.access()
                            + " but the chain's prefixes place it in " + actual);
                }
            }

            assertThat(disagreements).isEmpty();
        }

        @Test
        @DisplayName("and exactly one path is anonymous, five legacy transactions are administrative, "
                + "and the batch-control pair carries no legacy transaction at all")
        void theRegionsCarryTheExpectedMembership() {
            assertAll(
                    () -> assertThat(ORACLE.stream()
                            .filter(operation -> operation.access() == Access.ANONYMOUS)
                            .map(PublishedOperation::path)
                            .distinct()
                            .toList())
                            .containsExactly(SIGN_ON_ADDRESS),
                    () -> assertThat(ORACLE.stream()
                            .filter(operation -> operation.access() == Access.ADMINISTRATOR)
                            .map(PublishedOperation::transaction)
                            .filter(transaction -> transaction != null)
                            .sorted()
                            .toList())
                            .as("the administrative menu and the four sign-on-record maintenance "
                                    + "transactions")
                            .containsExactly("CA00", "CU00", "CU01", "CU02", "CU03"),
                    () -> assertThat(ORACLE.stream()
                            .filter(operation -> operation.transaction() == null)
                            .map(PublishedOperation::signature)
                            .toList())
                            .as("the batch-control surface reproduces no transaction: the legacy route "
                                    + "from an operator to a job was a queue write read by a scheduler")
                            .containsExactly("POST /api/batch/jobs/{jobName}/launch",
                                    "GET /api/batch/jobs/executions/{executionId}"));
        }
    }

    @Nested
    @DisplayName("the module manual publishes exactly the oracle")
    class TheManualAgrees {

        /** Creates the nested specification. */
        TheManualAgrees() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("so the table a reader works from is the surface the router serves, and its stated "
                + "count is the oracle's")
        void theManualRouteTableIsTheOracle() throws IOException {
            final String manual = Files.readString(MODULE_README, StandardCharsets.UTF_8);
            final Matcher rows = README_ROUTE_ROW.matcher(manual);
            final Set<String> documented = new TreeSet<>();
            while (rows.find()) {
                documented.add(rows.group(1) + " " + rows.group(2));
            }

            assertThat(documented)
                    .as("a manual missing an operation sends a reader looking for a surface that is "
                            + "there, and a manual naming a wrong verb sends them to a refusal")
                    .isEqualTo(oracleSignatures());
            assertThat(statedFigure(README_OPERATION_COUNT, manual))
                    .as("the manual stated nineteen while twenty were mapped")
                    .isEqualTo(ORACLE.size());
        }
    }

    @Nested
    @DisplayName("the three literal addresses this defect concerned dispatch as published")
    class TheLiteralAddressesDispatch {

        /** Creates the nested specification. */
        TheLiteralAddressesDispatch() {
            // Intentionally empty.
        }

        /**
         * Whether the router will dispatch this method and path to a handler.
         *
         * @param  method the HTTP method
         * @param  path   the literal full path
         * @return {@code true} when a handler is selected
         */
        private boolean dispatches(final String method, final String path) {
            for (final RequestMappingInfo info
                    : DeliveredApiSurfaceOracleTest.this.handlerMapping.getHandlerMethods().keySet()) {
                final boolean methodMatches = info.getMethodsCondition().getMethods().stream()
                        .anyMatch(candidate -> candidate.name().equals(method));
                if (methodMatches && info.getPatternValues().contains(path)) {
                    return true;
                }
            }
            return false;
        }

        @Test
        @DisplayName("card detail answers a POST at its literal address and refuses a GET there, which "
                + "is the exact disagreement the published classification carried")
        void cardDetailIsAPostAtItsLiteralAddress() {
            assertAll(
                    () -> assertThat(dispatches("POST", CARD_DETAIL_ADDRESS)).isTrue(),
                    () -> assertThat(dispatches("GET", CARD_DETAIL_ADDRESS)).isFalse());
        }

        @Test
        @DisplayName("and both menu addresses dispatch at their literal paths, one inside the gated "
                + "prefix and one outside it")
        void bothMenuAddressesDispatch() {
            assertAll(
                    () -> assertThat(dispatches("POST", USER_MENU_ADDRESS)).isTrue(),
                    () -> assertThat(dispatches("POST", ADMIN_MENU_ADDRESS)).isTrue(),
                    () -> assertThat(ADMIN_MENU_ADDRESS).startsWith(ADMIN_REGION + "/"),
                    () -> assertThat(USER_MENU_ADDRESS).doesNotStartWith(ADMIN_REGION));
        }

        @Test
        @DisplayName("and a GET at the literal card-detail address is answered as an unsupported method "
                + "by the running dispatcher, not as an absent resource")
        void aGetAtTheDetailAddressIsRefusedAsAnUnsupportedMethod() throws Exception {
            this.assertMethodNotAllowed(CARD_DETAIL_ADDRESS);
        }

        /**
         * Requests the path with a method it does not publish and requires the dispatcher's own refusal.
         *
         * @param  path the literal full path
         * @throws Exception if the request cannot be performed
         */
        private void assertMethodNotAllowed(final String path) throws Exception {
            DeliveredApiSurfaceOracleTest.this.client.perform(get(path))
                    .andExpect(status().isMethodNotAllowed());
        }
    }
}
