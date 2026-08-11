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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.config.SecurityConfig;
import com.carddemo.domain.UserSecurity;
import com.carddemo.domain.enums.UserType;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.AuthenticationService;
import com.carddemo.service.CredentialDigestService;
import com.carddemo.service.MessageCatalogService;
import com.carddemo.service.NavigationService;
import com.carddemo.service.InMemorySignOnAttemptLedger;
import com.carddemo.service.SignOnAttemptGovernor;
import com.carddemo.util.SessionTokenIssuer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test for {@link AuthController}, the REST surface of transaction {@code CC00}.
 *
 * <p><strong>What this proves, and why it is the finding's closure.</strong> Before this controller
 * existed the module published no operation at all: eleven repositories, twenty-six services and a full
 * DTO layer with nothing reachable over HTTP. The first nest below asserts the delivered inventory - that a
 * documented POST operation is mapped at the one route the security rules exempt - which is the property
 * the review found missing. The rest asserts the behaviour that makes the route usable and safe:
 * <ul>
 *   <li><strong>Every outcome answers {@code 200}.</strong> All nine are screens the legacy program
 *       successfully composed, rejections included, so the outcome is read from the body exactly as an
 *       operator read it from the screen.</li>
 *   <li><strong>Only an admitted turn is issued a session.</strong> Asserted for the admitted turn and for
 *       every refusal, because a token on a rejected sign-on would be the single worst defect this surface
 *       could have.</li>
 *   <li><strong>A request wider than the map is rejected before the service runs.</strong> The credential
 *       master is never consulted for input the screen could not have transmitted.</li>
 * </ul>
 *
 * <p>The controller is driven through a standalone {@code MockMvc} rather than a booted application, so
 * the assertions are about this class and its collaborators and not about the container's configuration.
 * The service beneath it is real, over a mocked repository, because a mocked service could not show that
 * the turn's outcome and the header's presence stay in step.
 *
 * <p>Provenance: {@code app/cbl/COSGN00C.cbl} and {@code app/csd/CARDDEMO.CSD}, read as read-only
 * reference at commit SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is transcribed.
 */
@DisplayName("AuthController :: the delivered sign-on operation")
class AuthControllerTest {

    /** Fixed clock at the upstream release stamp. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:33Z"), ZoneOffset.UTC);

    /** A seeded administrator identifier. */
    private static final String ADMIN_USER_ID = "ADMIN001";

    /** The literal secret the seed records carry. */
    private static final String SEEDED_SECRET = "PASSWORD";

    /** The token the stub issuer returns, distinctive so its presence is unambiguous. */
    private static final String ISSUED_TOKEN = "issued.session.token";

    /** The credential master. */
    private UserSecurityRepository repository;

    /** The digest verifier, real so the fold is genuinely exercised end to end. */
    private CredentialDigestService credentialDigestService;

    /** The session issuer, stubbed so no key material is needed. */
    private SessionTokenIssuer sessionTokenIssuer;

    /** Registry the turn timer is registered against. */
    private MeterRegistry meterRegistry;

    /** The controller under test. */
    private AuthController controller;

    /** Standalone servlet harness over that controller. */
    private MockMvc mockMvc;

    /** Captures only controller diagnostics, not the service diagnostics beneath it. */
    private ListAppender<ILoggingEvent> controllerLogCapture;

    private Logger controllerLogger;

    private Level previousControllerLogLevel;

    /**
     * The abuse-resistance governor at the figures the shipped defaults declare.
     *
     * <p>Written out here rather than relaxed, so the graph under test is the one a deployment runs. The
     * allowance is far above what any specification in this file spends, and a fresh instance is built per
     * test so no test can inherit another's accumulated count.
     *
     * @return the governor
     */
    private static SignOnAttemptGovernor shippedGovernor() {
        return new SignOnAttemptGovernor(true, 10, Duration.ofMinutes(5), Duration.ofMinutes(1),
                10_000, FIXED_CLOCK, new SimpleMeterRegistry(), new InMemorySignOnAttemptLedger());
    }

    /** Assembles the controller over a real service and a stubbed issuer. */
    @BeforeEach
    void setUp() {
        repository = mock(UserSecurityRepository.class);
        credentialDigestService = new CredentialDigestService();
        sessionTokenIssuer = mock(SessionTokenIssuer.class);
        meterRegistry = new SimpleMeterRegistry();

        final AuthenticationService authenticationService = new AuthenticationService(repository,
                credentialDigestService, new NavigationService(), new MessageCatalogService(),
                FIXED_CLOCK, shippedGovernor());
        controller = new AuthController(authenticationService,
                new SignOnContractAdapter(new MessageCatalogService()), sessionTokenIssuer,
                meterRegistry);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        controllerLogger = (Logger) LoggerFactory.getLogger(AuthController.class);
        previousControllerLogLevel = controllerLogger.getLevel();
        controllerLogger.setLevel(Level.TRACE);
        controllerLogCapture = new ListAppender<>();
        controllerLogCapture.start();
        controllerLogger.addAppender(controllerLogCapture);
    }

    @AfterEach
    void tearDown() {
        controllerLogger.detachAppender(controllerLogCapture);
        controllerLogger.setLevel(previousControllerLogLevel);
        controllerLogCapture.stop();
        meterRegistry.close();
    }

    private List<String> controllerLogMessages() {
        return controllerLogCapture.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /**
     * Stubs the credential master to hold one record.
     *
     * @param userId the identifier
     * @param typeCode the one-character role code
     */
    private void givenStoredOperator(final String userId, final String typeCode) {
        when(repository.findById(userId)).thenReturn(Optional.of(new UserSecurity(userId, "Test",
                "Operator", credentialDigestService.encode(SEEDED_SECRET), typeCode)));
    }

    /**
     * Renders a sign-on request body.
     *
     * @param userId the identifier, or {@code null} to omit it
     * @param password the secret, or {@code null} to omit it
     * @return the JSON body
     */
    private static String body(final String userId, final String password) {
        return "{\"userId\":" + quotedOrNull(userId)
                + ",\"password\":" + quotedOrNull(password)
                + ",\"keyAction\":\"ENTER\"}";
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

    // ------------------------------------------------------------------------------------------
    // The finding: an operation is actually delivered
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the delivered operation inventory")
    class TheDeliveredOperationInventory {

        @Test
        @DisplayName("the class is a REST controller mapped at the sign-on route, so the document builder "
                + "has an operation to contribute where before it had none")
        void theClassIsARestControllerAtTheSignOnRoute() {
            assertThat(AuthController.class.getAnnotation(RestController.class))
                    .as("without this the class is not scanned and publishes nothing")
                    .isNotNull();

            final RequestMapping mapping = AuthController.class.getAnnotation(RequestMapping.class);
            assertThat(mapping).isNotNull();
            assertThat(mapping.value()).containsExactly(AuthController.SIGN_ON_PATH);
        }

        @Test
        @DisplayName("the route and the rule that exempts it from authentication name one authority, so a "
                + "mapping typo cannot open an unauthenticated surface")
        void theRouteAndTheExemptionNameOneAuthority() {
            assertThat(SecurityConfig.SIGN_ON_PATH)
                    .as("the security rule reads the controller's own constant")
                    .isEqualTo(AuthController.SIGN_ON_PATH);
            assertThat(AuthController.SIGN_ON_PATH).isEqualTo("/api/auth/signon");
        }

        @Test
        @DisplayName("both documented JSON operations are actually mapped: the POST that serves a "
                + "submitted turn and the GET that serves first entry")
        void bothDocumentedJsonOperationsArePublished() {
            final List<Method> postHandlers = Arrays.stream(AuthController.class.getDeclaredMethods())
                    .filter(method -> method.getAnnotation(PostMapping.class) != null)
                    .toList();

            assertThat(postHandlers).hasSize(1);
            final Method postHandler = postHandlers.get(0);
            final PostMapping post = postHandler.getAnnotation(PostMapping.class);
            assertThat(post.consumes()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
            assertThat(post.produces()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
            assertThat(postHandler.getAnnotation(Operation.class))
                    .as("an undocumented operation publishes a path with no description")
                    .isNotNull();
            assertThat(postHandler.getAnnotation(ApiResponses.class)).isNotNull();

            // The first-entry operation carries a documented GET contract, so it has to carry the mapping
            // that serves it. Documenting an operation the router does not publish is the same defect in
            // the other direction: a client reads the contract, calls the method, and is answered 405.
            final List<Method> getHandlers = Arrays.stream(AuthController.class.getDeclaredMethods())
                    .filter(method -> method.getAnnotation(GetMapping.class) != null)
                    .toList();

            assertThat(getHandlers).hasSize(1);
            final Method getHandler = getHandlers.get(0);
            assertThat(getHandler.getName()).isEqualTo("initialEntry");
            assertThat(getHandler.getParameterCount())
                    .as("first entry takes nothing: it is the state before anything was keyed")
                    .isZero();
            assertThat(getHandler.getAnnotation(GetMapping.class).produces())
                    .containsExactly(MediaType.APPLICATION_JSON_VALUE);
            assertThat(getHandler.getAnnotation(Operation.class)).isNotNull();
        }

        @Test
        @DisplayName("the mapped first-entry GET answers the blank screen with no session, so the "
                + "documented operation is reachable rather than only described")
        void theMappedFirstEntryGetAnswersTheBlankScreen() throws Exception {
            mockMvc.perform(get(AuthController.SIGN_ON_PATH))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.AUTHORIZATION))
                    .andExpect(jsonPath("$.message").doesNotExist())
                    .andExpect(jsonPath("$.generalError").value(false))
                    .andExpect(jsonPath("$.focusScreenFieldId").value("USERID"));

            verify(sessionTokenIssuer, never()).issue(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("the controller holds no rule of its own: no message literal, no comparison and no "
                + "routing decision appear in it")
        void theControllerHoldsNoRuleOfItsOwn() {
            // Asserted structurally because the property is an absence. The controller's only fields are
            // its four collaborators, so there is nowhere for a rule to be kept, and every one of the
            // frozen literals lives in the adapter or the response contract instead.
            assertThat(Arrays.stream(AuthController.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                    .map(field -> field.getType().getSimpleName())
                    .toList())
                    .containsExactlyInAnyOrder("AuthenticationService", "SignOnContractAdapter",
                            "SessionTokenIssuer", "MeterRegistry");
        }

        @Test
        @DisplayName("every collaborator is required, so a part-wired controller cannot be constructed")
        void everyCollaboratorIsRequired() {
            final AuthenticationService service = new AuthenticationService(repository,
                    credentialDigestService, new NavigationService(), new MessageCatalogService(),
                    FIXED_CLOCK, shippedGovernor());
            final SignOnContractAdapter adapter =
                    new SignOnContractAdapter(new MessageCatalogService());

            assertThatNullPointerException().isThrownBy(() ->
                    new AuthController(null, adapter, sessionTokenIssuer, meterRegistry));
            assertThatNullPointerException().isThrownBy(() ->
                    new AuthController(service, null, sessionTokenIssuer, meterRegistry));
            assertThatNullPointerException().isThrownBy(() ->
                    new AuthController(service, adapter, null, meterRegistry));
            assertThatNullPointerException().isThrownBy(() ->
                    new AuthController(service, adapter, sessionTokenIssuer, null));
        }
    }

    // ------------------------------------------------------------------------------------------
    // The route serves, and only an admitted turn earns a session
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the route serves every outcome and issues a session only to an admitted one")
    class TheRouteServesAndIssuesCarefully {

        @Test
        @DisplayName("an empty POST returns the cleared screen with USERID focused before a key can "
                + "be evaluated")
        void firstEntryReturnsTheBlankScreen() throws Exception {
            mockMvc.perform(post(AuthController.SIGN_ON_PATH)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.AUTHORIZATION))
                    .andExpect(jsonPath("$.message").doesNotExist())
                    .andExpect(jsonPath("$.generalError").value(false))
                    .andExpect(jsonPath("$.focusScreenFieldId").value("USERID"))
                    .andExpect(jsonPath("$.nextRoute").doesNotExist())
                    .andExpect(jsonPath("$.navigationContext").doesNotExist());

            verify(repository, never()).findById(org.mockito.ArgumentMatchers.any());
            verify(sessionTokenIssuer, never()).issue(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("an admitted sign-on answers 200 with the route, the derived context and a bearer "
                + "session in the header rather than in the body")
        void anAdmittedSignOnCarriesABearerHeader() throws Exception {
            givenStoredOperator(ADMIN_USER_ID, "A");
            when(sessionTokenIssuer.issue(ADMIN_USER_ID, UserType.ADMIN, UserType.ADMIN.getCode())).thenReturn(ISSUED_TOKEN);

            mockMvc.perform(post(AuthController.SIGN_ON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ADMIN_USER_ID, SEEDED_SECRET)))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.AUTHORIZATION,
                            SessionTokenIssuer.BEARER_PREFIX + ISSUED_TOKEN))
                    .andExpect(jsonPath("$.nextRoute")
                            .value(NavigationService.Route.ADMIN_MENU.getRouteValue()))
                    .andExpect(jsonPath("$.userId").value(ADMIN_USER_ID))
                    .andExpect(jsonPath("$.userType").value("A"))
                    .andExpect(jsonPath("$.navigationContext.userId").value(ADMIN_USER_ID))
                    .andExpect(jsonPath("$.message").doesNotExist())
                    .andExpect(jsonPath("$.generalError").value(false));

            verify(sessionTokenIssuer).issue(ADMIN_USER_ID, UserType.ADMIN, UserType.ADMIN.getCode());
            assertThat(controllerLogMessages())
                    .contains("Sign-on session issued: outcome=issued")
                    .noneMatch(message -> message.contains(ADMIN_USER_ID));
        }

        @Test
        @DisplayName("an issuer that refuses to mint answers a neutral server error rather than a "
                + "credential-less success, and discloses nothing about why")
        void anIssuerThatRefusesToMintAnswersNeutrally() throws Exception {
            // The issuer refuses when the credential record has gone, or no longer carries the role being
            // minted, between verification and minting - a concurrent administrative change. The route
            // must not answer 200 with no session, because a client reading an admitted turn would then
            // proceed unauthenticated; and the answer must not describe the condition, because the subject
            // of a credential being minted is in it.
            givenStoredOperator(ADMIN_USER_ID, "A");
            when(sessionTokenIssuer.issue(ADMIN_USER_ID, UserType.ADMIN, UserType.ADMIN.getCode()))
                    .thenThrow(new IllegalStateException(
                            "The user-security record no longer carries the user type a session was "
                                    + "requested for; no session is issued"));

            mockMvc.perform(post(AuthController.SIGN_ON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ADMIN_USER_ID, SEEDED_SECRET)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(header().doesNotExist(HttpHeaders.AUTHORIZATION))
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                            .as("a refusal to mint names neither the identity nor the reason")
                            .doesNotContain(ADMIN_USER_ID)
                            .doesNotContain("user type"));
        }

        @Test
        @DisplayName("the session never appears in the body, because the screen contract declares fifteen "
                + "components and none of them is a credential")
        void theSessionNeverAppearsInTheBody() throws Exception {
            givenStoredOperator(ADMIN_USER_ID, "A");
            when(sessionTokenIssuer.issue(ADMIN_USER_ID, UserType.ADMIN, UserType.ADMIN.getCode())).thenReturn(ISSUED_TOKEN);

            final String rendered = mockMvc.perform(post(AuthController.SIGN_ON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ADMIN_USER_ID, SEEDED_SECRET)))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(rendered).doesNotContain(ISSUED_TOKEN);
        }

        @Test
        @DisplayName("a wrong secret answers 200 with the screen's own text, no session header and the "
                + "error flag lowered, exactly as the program leaves it")
        void aWrongSecretAnswersWithoutASession() throws Exception {
            givenStoredOperator(ADMIN_USER_ID, "A");

            mockMvc.perform(post(AuthController.SIGN_ON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ADMIN_USER_ID, "WRONGONE")))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.AUTHORIZATION))
                    .andExpect(jsonPath("$.message").value("Wrong Password. Try again ..."))
                    .andExpect(jsonPath("$.generalError").value(false))
                    .andExpect(jsonPath("$.focusScreenFieldId").value("PASSWD"))
                    // The identifier is restated so the operator retypes only the credential, which is
                    // what failed. It establishes nothing: the route and the state are both absent.
                    .andExpect(jsonPath("$.userId").value(ADMIN_USER_ID))
                    .andExpect(jsonPath("$.nextRoute").doesNotExist())
                    .andExpect(jsonPath("$.navigationContext").doesNotExist());

            verify(sessionTokenIssuer, never()).issue(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("a redisplayed screen restates the keyed identifier folded to upper case, on every "
                + "rejected outcome, without establishing any state from it")
        void aRedisplayedScreenRestatesTheKeyedIdentifier() throws Exception {
            when(repository.findById(ADMIN_USER_ID)).thenReturn(Optional.empty());

            // Keyed in lower case: the program folds both submitted values unconditionally before it does
            // anything else, so the echo is the folded value and not the characters as transmitted.
            mockMvc.perform(post(AuthController.SIGN_ON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ADMIN_USER_ID.toLowerCase(java.util.Locale.ROOT),
                                    SEEDED_SECRET)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(ADMIN_USER_ID))
                    .andExpect(jsonPath("$.userType").doesNotExist())
                    .andExpect(jsonPath("$.nextRoute").doesNotExist())
                    .andExpect(jsonPath("$.navigationContext").doesNotExist())
                    .andExpect(header().doesNotExist(HttpHeaders.AUTHORIZATION));
        }

        @Test
        @DisplayName("an unknown identifier answers 200 with the not-found text, the flag raised and no "
                + "session")
        void anUnknownIdentifierAnswersWithoutASession() throws Exception {
            when(repository.findById(ADMIN_USER_ID)).thenReturn(Optional.empty());

            mockMvc.perform(post(AuthController.SIGN_ON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ADMIN_USER_ID, SEEDED_SECRET)))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.AUTHORIZATION))
                    .andExpect(jsonPath("$.message").value("User not found. Try again ..."))
                    .andExpect(jsonPath("$.generalError").value(true))
                    .andExpect(jsonPath("$.focusScreenFieldId").value("USERID"));

            verify(sessionTokenIssuer, never()).issue(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("an unavailable credential store answers the exact unable-to-verify screen instead of "
                + "escaping to the generic server-error boundary")
        void anUnavailableCredentialStoreAnswersUnableToVerify() throws Exception {
            when(repository.findById(ADMIN_USER_ID))
                    .thenThrow(new DataAccessResourceFailureException("store unavailable"));

            mockMvc.perform(post(AuthController.SIGN_ON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ADMIN_USER_ID, SEEDED_SECRET)))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.AUTHORIZATION))
                    .andExpect(jsonPath("$.message").value("Unable to verify the User ..."))
                    .andExpect(jsonPath("$.generalError").value(true))
                    .andExpect(jsonPath("$.focusScreenFieldId").value("USERID"));

            verify(sessionTokenIssuer, never()).issue(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("an omitted identifier answers the prompt without reaching the credential master, so "
                + "an empty submission costs no read")
        void anOmittedIdentifierCostsNoRead() throws Exception {
            mockMvc.perform(post(AuthController.SIGN_ON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(null, null)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Please enter User ID ..."))
                    .andExpect(jsonPath("$.focusScreenFieldId").value("USERID"));

            verify(repository, never()).findById(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("the exit key answers the shared farewell with the flag lowered and no session")
        void theExitKeyAnswersTheSharedFarewell() throws Exception {
            mockMvc.perform(post(AuthController.SIGN_ON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"keyAction\":\"PFK03\"}"))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.AUTHORIZATION))
                    .andExpect(jsonPath("$.message")
                            .value(new MessageCatalogService().thankYouMessage()))
                    .andExpect(jsonPath("$.generalError").value(false));
        }

        @Test
        @DisplayName("an unmapped key answers the shared invalid-key text with the flag raised")
        void anUnmappedKeyAnswersTheSharedInvalidKeyText() throws Exception {
            mockMvc.perform(post(AuthController.SIGN_ON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"keyAction\":\"PFK09\"}"))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.AUTHORIZATION))
                    .andExpect(jsonPath("$.message")
                            .value(new MessageCatalogService().invalidKeyMessage()))
                    .andExpect(jsonPath("$.generalError").value(true));
        }

        @Test
        @DisplayName("an ordinary operator is admitted to the main menu rather than the administrative "
                + "one, so the role split is observable over HTTP")
        void anOrdinaryOperatorReachesTheMainMenu() throws Exception {
            givenStoredOperator("USER0001", "U");
            when(sessionTokenIssuer.issue("USER0001", UserType.USER, UserType.USER.getCode())).thenReturn(ISSUED_TOKEN);

            mockMvc.perform(post(AuthController.SIGN_ON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("USER0001", SEEDED_SECRET)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextRoute")
                            .value(NavigationService.Route.USER_MENU.getRouteValue()))
                    .andExpect(jsonPath("$.userType").value("U"));
        }

        @Test
        @DisplayName("an undeclared stored role code is admitted to the main menu with standard authority "
                + "while the raw code remains in the screen contract")
        void anUndeclaredRoleUsesTheStandardBranch() throws Exception {
            givenStoredOperator("USER0001", "X");
            // The stub names the RAW stored code, because that is what a session is minted with. Stubbing
            // the resolved authority's code here instead is what the defect looked like: the issuer was
            // handed a value the record does not carry, refused it, and turned a successful sign-on into a
            // server failure.
            when(sessionTokenIssuer.issue("USER0001", UserType.USER, "X")).thenReturn(ISSUED_TOKEN);

            mockMvc.perform(post(AuthController.SIGN_ON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("USER0001", SEEDED_SECRET)))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.AUTHORIZATION,
                            SessionTokenIssuer.BEARER_PREFIX + ISSUED_TOKEN))
                    .andExpect(jsonPath("$.nextRoute")
                            .value(NavigationService.Route.USER_MENU.getRouteValue()))
                    .andExpect(jsonPath("$.userType").value("X"))
                    .andExpect(jsonPath("$.navigationContext.userType").value("X"));

            // The two arguments are asserted apart: the standard authority is what the session permits and
            // the undeclared code is what the record holds, and neither is derived from the other.
            verify(sessionTokenIssuer).issue("USER0001", UserType.USER, "X");
        }

        @Test
        @DisplayName("a lower-case submission is admitted, so the fold the program applies to both fields "
                + "survives all the way to the wire")
        void aLowerCaseSubmissionIsAdmitted() throws Exception {
            givenStoredOperator(ADMIN_USER_ID, "A");
            when(sessionTokenIssuer.issue(ADMIN_USER_ID, UserType.ADMIN, UserType.ADMIN.getCode())).thenReturn(ISSUED_TOKEN);

            mockMvc.perform(post(AuthController.SIGN_ON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ADMIN_USER_ID.toLowerCase(Locale.ROOT),
                                    SEEDED_SECRET.toLowerCase(Locale.ROOT))))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.AUTHORIZATION,
                            SessionTokenIssuer.BEARER_PREFIX + ISSUED_TOKEN))
                    .andExpect(jsonPath("$.userId").value(ADMIN_USER_ID));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Input the screen could not have transmitted, and the turn timer
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("input wider than the map, and the turn timer")
    class WidthRejectionAndTiming {

        @Test
        @DisplayName("a submission wider than the map's own fields is rejected before the service runs, so "
                + "the credential master is never read for input the screen could not have sent")
        void anOverWideSubmissionIsRejectedBeforeTheServiceRuns() throws Exception {
            mockMvc.perform(post(AuthController.SIGN_ON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("ADMIN0011", SEEDED_SECRET)))
                    .andExpect(status().isBadRequest());

            verify(repository, never()).findById(org.mockito.ArgumentMatchers.any());
            verify(sessionTokenIssuer, never()).issue(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("an over-wide secret is rejected on the same rule, so neither field is bounded more "
                + "loosely than the eight characters its screen field declares")
        void anOverWideSecretIsRejectedToo() throws Exception {
            mockMvc.perform(post(AuthController.SIGN_ON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ADMIN_USER_ID, "PASSWORD9")))
                    .andExpect(status().isBadRequest());

            verify(repository, never()).findById(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("a control character in the identifier is rejected before any credential lookup")
        void aControlCharacterInTheIdentifierIsRejectedBeforeLookup() throws Exception {
            mockMvc.perform(post(AuthController.SIGN_ON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"ADM\\n001\",\"password\":\"PASSWORD\","
                                    + "\"keyAction\":\"ENTER\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].fieldName").value("userId"));

            verify(repository, never()).findById(org.mockito.ArgumentMatchers.any());
            verify(sessionTokenIssuer, never()).issue(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("session issuance logs only a fixed outcome and never the identifier or token")
        void sessionIssuanceLogContainsNoIdentityOrToken() throws Exception {
            givenStoredOperator(ADMIN_USER_ID, "A");
            when(sessionTokenIssuer.issue(ADMIN_USER_ID, UserType.ADMIN, UserType.ADMIN.getCode())).thenReturn(ISSUED_TOKEN);

            final Logger logger = (Logger) LoggerFactory.getLogger(AuthController.class);
            final Level previousLevel = logger.getLevel();
            final ListAppender<ILoggingEvent> recorder = new ListAppender<>();
            recorder.start();
            logger.setLevel(Level.DEBUG);
            logger.addAppender(recorder);
            try {
                mockMvc.perform(post(AuthController.SIGN_ON_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(ADMIN_USER_ID, SEEDED_SECRET)))
                        .andExpect(status().isOk());
            } finally {
                logger.detachAppender(recorder);
                logger.setLevel(previousLevel);
                recorder.stop();
            }

            assertThat(recorder.list).extracting(ILoggingEvent::getFormattedMessage)
                    .contains("Sign-on session issued: outcome=issued")
                    .allSatisfy(message -> assertThat(message)
                            .doesNotContain(ADMIN_USER_ID, ISSUED_TOKEN, "userId=", "\n", "\r"));
        }

        @Test
        @DisplayName("each turn is timed and tagged by the outcome it reached, so a rise in one rejection "
                + "can be told from a rise in another")
        void eachTurnIsTimedAndTaggedByOutcome() throws Exception {
            givenStoredOperator(ADMIN_USER_ID, "A");
            when(sessionTokenIssuer.issue(ADMIN_USER_ID, UserType.ADMIN, UserType.ADMIN.getCode())).thenReturn(ISSUED_TOKEN);

            mockMvc.perform(post(AuthController.SIGN_ON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ADMIN_USER_ID, SEEDED_SECRET)))
                    .andExpect(status().isOk());
            mockMvc.perform(post(AuthController.SIGN_ON_PATH)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());
            mockMvc.perform(post(AuthController.SIGN_ON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ADMIN_USER_ID, "WRONGONE")))
                    .andExpect(status().isOk());

            assertThat(meterRegistry.find("carddemo.online.signon.turn")
                    .tag("outcome", AuthenticationService.Decision.ADMITTED.name())
                    .timer())
                    .isNotNull()
                    .satisfies(timer -> assertThat(timer.count()).isEqualTo(1));
            assertThat(meterRegistry.find("carddemo.online.signon.turn")
                    .tag("outcome", AuthenticationService.Decision.WRONG_PASSWORD.name())
                    .timer())
                    .as("one aggregate timer would hide which outcome the traffic actually reached")
                    .isNotNull()
                    .satisfies(timer -> assertThat(timer.count()).isEqualTo(1));
            assertThat(meterRegistry.find("carddemo.online.signon.turn")
                    .tag("outcome", AuthenticationService.Decision.INITIAL_ENTRY.name())
                    .timer())
                    .isNotNull()
                    .satisfies(timer -> assertThat(timer.count()).isEqualTo(1));
        }

        @Test
        @DisplayName("the outcome tag is drawn from the decision's own enumerated name, so it cannot "
                + "become a high-cardinality label")
        void theOutcomeTagCannotBecomeHighCardinality() throws Exception {
            when(repository.findById(ADMIN_USER_ID)).thenReturn(Optional.empty());
            mockMvc.perform(post(AuthController.SIGN_ON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(ADMIN_USER_ID, SEEDED_SECRET)))
                    .andExpect(status().isOk());

            final List<String> observedTags = meterRegistry.find("carddemo.online.signon.turn")
                    .timers().stream()
                    .map(timer -> timer.getId().getTag("outcome"))
                    .toList();
            final List<String> declared = Arrays.stream(AuthenticationService.Decision.values())
                    .map(Enum::name)
                    .toList();

            assertThat(observedTags).isNotEmpty().allSatisfy(tag ->
                    assertThat(declared).contains(tag));
        }
    }
}
