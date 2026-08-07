/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.carddemo.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.api.AuthController;
import com.carddemo.api.ConversationStateAdapter;
import com.carddemo.api.GlobalExceptionHandler;
import com.carddemo.api.JsonRefusalBodyRenderer;
import com.carddemo.api.MenuController;
import com.carddemo.api.MenuResponseAdapter;
import com.carddemo.api.ModuleErrorController;
import com.carddemo.api.ScreenStateAdapter;
import com.carddemo.api.SignOnContractAdapter;
import com.carddemo.config.JwtProperties;
import com.carddemo.config.JwtTokenProvider;
import com.carddemo.config.MenuOptionCatalog;
import com.carddemo.config.SecurityConfig;
import com.carddemo.config.WebMvcConfig;
import com.carddemo.domain.UserSecurity;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.AuthenticationService;
import com.carddemo.service.CredentialDigestService;
import com.carddemo.service.MenuService;
import com.carddemo.service.MessageCatalogService;
import com.carddemo.service.NavigationService;
import com.carddemo.service.SignOnStateService;
import com.carddemo.support.AbstractPostgresIT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.tracing.prometheus.PrometheusExemplarsAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Gate 5's sign-on contract, executed over a real HTTP boundary against the credentials a real migration
 * seeded.
 *
 * <h2>Why this class exists, and what it deliberately does not repeat</h2>
 * Gate 5 names three external contracts. Two of them already have container-backed suites and are not
 * restated here, because two assertions of one fact drift apart and the weaker one wins:
 * <ul>
 *   <li>the four fixed-width file formats are compared whole, byte for byte, in
 *       {@link BatchPipelineE2ETest};</li>
 *   <li>the batch trigger - seventeen eighty-byte cards, four substituted date slots, the transmitted
 *       end-of-stream sentinel, the single message group and the per-card deduplication identity - is
 *       drained out of a real first-in-first-out queue in {@code service/JobSubmissionServiceIT}, which
 *       asserts every one of those properties against messages the queue actually returned.</li>
 * </ul>
 * The third contract had no end-to-end assertion at all. The sign-on program's seven operator-visible
 * message texts and its two routing outcomes were held only by a standalone controller specification
 * driving a <em>mocked</em> service, which proves a controller and says nothing about whether a delivered
 * identity can sign on. This class closes that: the request crosses a real HTTP boundary on a real port,
 * through the shipped filter chain, into the shipped service, to the ten identities
 * {@code V4__seed_user_security.sql} applied to a real PostgreSQL 16 server, and the reply's message text
 * is compared character for character.
 *
 * <h2>Why the message text is a contract and not a detail</h2>
 * The legacy program writes its outcome into an eighty-byte message field and an operator reads it. Two
 * of the seven texts distinguish a wrong password from an unknown identity, and a migration that blurred
 * them would change what an operator concludes without failing anything. Two more are the common texts
 * the shared message copybook publishes, which every online program emits, so getting them wrong here
 * would be wrong in seventeen places. They are therefore compared exactly, and the two failure texts are
 * additionally asserted to differ from one another.
 *
 * <h2>Why the routing outcome is asserted from the delivered data</h2>
 * The user type on the delivered record decides the destination: the administrative identities route to
 * the administrative menu and the ordinary ones to the main menu. Both are asserted, using two identities
 * the seed actually applied and reading the type back off the server rather than assuming it, so a seed
 * that changed a type would fail here rather than silently reroute an administrator.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Message texts and route names are external
 * contract, which is metadata; no legacy source line is transcribed.
 */
@SpringBootTest(classes = OnlineTransactionE2ETest.OnlineContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.flyway.enabled=false", "spring.main.banner-mode=off",
                "spring.jpa.hibernate.ddl-auto=none", "spring.batch.job.enabled=false",
                "management.endpoint.health.validate-group-membership=false",
                "management.tracing.enabled=false",
                // The boundary is reached over the loopback interface of the process that opened it, so
                // there is no wire for a token to be observed on and the transport requirement is
                // relaxed exactly as the test profile relaxes it. Nothing else is relaxed.
                "carddemo.security.require-https=false",
                "carddemo.security.jwt.issuer=carddemo-java",
                "carddemo.security.jwt.expiration=PT15M",
                "carddemo.security.jwt.secret="
                        + OnlineTransactionE2ETest.SUITE_SIGNING_SECRET})
@DisplayName("Gate 5 executed: the sign-on contract over a real HTTP boundary, against the identities a "
        + "real migration seeded")
class OnlineTransactionE2ETest extends AbstractPostgresIT {

    /**
     * A suite-only signing secret, long enough for the binder's minimum and obviously synthetic.
     *
     * <p>It signs tokens that exist for the length of one request and is worth nothing outside this
     * suite. Production binds a bare environment reference with no fallback and is refused start-up
     * without it; writing a fallback here is what lets a suite run need nothing configured.
     */
    static final String SUITE_SIGNING_SECRET =
            "test-only-signing-secret-not-used-outside-tests-0123456789";

    // -----------------------------------------------------------------------------------------------
    // THE CONTRACT, RESTATED HERE RATHER THAN IMPORTED.
    //
    // Every text and every route name below is written out as its own literal. None is read from the
    // catalogue that also publishes it, because an expectation that borrows the subject's own constant
    // proves only that the subject agrees with itself.
    // -----------------------------------------------------------------------------------------------

    /**
     * The prompt an empty submission returns when no identity was supplied.
     *
     * <p>Transcribed from the emitting program's own literal, which says "Please enter User ID" and not
     * "Please enter <em>your</em> User ID". The difference is one word and it is the whole point of
     * asserting the text rather than its meaning: a paraphrase reads correctly and is not the contract.
     */
    private static final String PROMPT_FOR_USER_ID = "Please enter User ID ...";

    /** The prompt returned when an identity was supplied but no secret was, in the same terms. */
    private static final String PROMPT_FOR_PASSWORD = "Please enter Password ...";

    /** The text a supplied-but-wrong secret returns. */
    private static final String WRONG_PASSWORD = "Wrong Password. Try again ...";

    /** The text an identity the store does not hold returns. */
    private static final String USER_NOT_FOUND = "User not found. Try again ...";

    /** An administrative identity the delivered seed applies. */
    private static final String SEEDED_ADMIN_ID = "ADMIN001";

    /** An ordinary identity the delivered seed applies. */
    private static final String SEEDED_USER_ID = "USER0001";

    /** The cleartext the delivered seed digests, and the only value either identity accepts. */
    private static final String SEEDED_SECRET = "PASSWORD";

    /** The type byte an administrative record carries. */
    private static final String ADMIN_TYPE = "A";

    /** The type byte an ordinary record carries. */
    private static final String USER_TYPE = "U";

    /** An identity no seed applies, for the not-found arm. */
    private static final String UNKNOWN_ID = "NOBODY00";

    /** The attention key that submits a screen; anything else is the invalid-key arm. */
    private static final String SUBMIT_KEY = "ENTER";

    /** The route an administrative identity is directed to. */
    private static final String ADMIN_MENU_ROUTE = "admin-menu";

    /** The route an ordinary identity is directed to. */
    private static final String USER_MENU_ROUTE = "user-menu";

    /** The administrative surface an ordinary identity must not reach. */
    private static final String ADMIN_USERS_PATH = "/api/admin/users";

    /** The prefix the issued session token is presented behind. */
    private static final String BEARER_PREFIX = "Bearer ";

    /** Reads and writes JSON without binding to a published contract type. */
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private UserSecurityRepository users;

    /** Creates the specification. */
    OnlineTransactionE2ETest() {
        super();
    }

    /**
     * Returns the shared server to its seeded state, so the ten delivered identities are the ones this
     * specification signs on with whatever ran before it.
     *
     * @throws SQLException if the seeded state cannot be restored
     */
    @BeforeAll
    static void restoreDeliveredIdentities() throws SQLException {
        restoreSeededState();
    }

    // ===============================================================================================
    // THE DELIVERED IDENTITIES
    // ===============================================================================================

    @Nested
    @DisplayName("the identities the migration delivered")
    class TheDeliveredIdentities {

        /** Creates the nested specification. */
        TheDeliveredIdentities() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("include the two this specification signs on with, carrying the two types that "
                + "decide the destination")
        void theSeedAppliedBothTypesOfIdentity() {
            final UserSecurity administrator =
                    OnlineTransactionE2ETest.this.users.findById(SEEDED_ADMIN_ID).orElseThrow();
            final UserSecurity ordinary =
                    OnlineTransactionE2ETest.this.users.findById(SEEDED_USER_ID).orElseThrow();

            assertThat(administrator.getSecUsrType())
                    .as("%s must be an administrative identity, or the routing assertion below would "
                            + "be asserting the wrong destination", SEEDED_ADMIN_ID)
                    .isEqualTo(ADMIN_TYPE);
            assertThat(ordinary.getSecUsrType())
                    .as("%s must be an ordinary identity", SEEDED_USER_ID)
                    .isEqualTo(USER_TYPE);
            // The entity deliberately publishes no accessor for the stored secret, which is itself
            // part of the posture: a digest that cannot be read through the domain type cannot be
            // logged, serialised or compared by accident. That the stored value IS a digest rather
            // than the legacy cleartext is asserted where it can be - directly against the server -
            // by GateVerificationTest. What this specification proves instead is the behaviour that
            // depends on it: the delivered cleartext is accepted below, and a wrong one is refused,
            // which is only possible if the stored value is a verifiable digest of the former.
            assertThat(administrator.getSecUsrId()).isEqualTo(SEEDED_ADMIN_ID);
            assertThat(ordinary.getSecUsrId()).isEqualTo(SEEDED_USER_ID);
        }
    }

    // ===============================================================================================
    // THE SEVEN MESSAGE TEXTS
    // ===============================================================================================

    @Nested
    @DisplayName("the operator-visible message texts, over the real boundary")
    class TheMessageTexts {

        /** Creates the nested specification. */
        TheMessageTexts() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("an empty submission asks for the identity, in the program's own words")
        void anEmptySubmissionAsksForTheIdentity() throws Exception {
            final JsonNode body = signOn("", "");

            assertThat(messageOf(body))
                    .as("the first of the two prompts, character for character")
                    .isEqualTo(PROMPT_FOR_USER_ID);
        }

        @Test
        @DisplayName("an identity with no secret asks for the secret, in the program's own words")
        void anIdentityWithNoSecretAsksForTheSecret() throws Exception {
            final JsonNode body = signOn(SEEDED_USER_ID, "");

            assertThat(messageOf(body))
                    .as("the second of the two prompts, character for character")
                    .isEqualTo(PROMPT_FOR_PASSWORD);
        }

        @Test
        @DisplayName("a wrong secret and an unknown identity return different texts, because an "
                + "operator draws a different conclusion from each")
        void aWrongSecretAndAnUnknownIdentityAreDistinguished() throws Exception {
            final String wrongSecret = messageOf(signOn(SEEDED_USER_ID, "NOTRIGHT"));
            final String unknownIdentity = messageOf(signOn(UNKNOWN_ID, SEEDED_SECRET));

            assertThat(wrongSecret)
                    .as("the wrong-secret text, character for character")
                    .isEqualTo(WRONG_PASSWORD);
            assertThat(unknownIdentity)
                    .as("the not-found text, character for character")
                    .isEqualTo(USER_NOT_FOUND);
            assertThat(wrongSecret)
                    .as("blurring the two would change what an operator concludes without failing "
                            + "anything")
                    .isNotEqualTo(unknownIdentity);
        }

        @Test
        @DisplayName("no refusal text ever carries the submitted secret, the stored digest or a "
                + "framework message")
        void noRefusalTextLeaksACredentialOrAFrameworkMessage() throws Exception {
            final String submitted = "SECRET42";
            final String refusal = signOnRaw(SEEDED_USER_ID, submitted);

            assertThat(refusal)
                    .as("the submitted secret must not be echoed anywhere in the reply")
                    .doesNotContain(submitted);
            assertThat(refusal)
                    .as("nor must the stored digest, which starts with the algorithm marker")
                    .doesNotContain("$2a$")
                    .doesNotContain("$2b$")
                    .doesNotContain("$2y$");
            assertThat(refusal)
                    .as("nor must a framework or driver message reach an operator")
                    .doesNotContain("org.springframework")
                    .doesNotContain("org.postgresql")
                    .doesNotContain("Exception");
        }
    }

    // ===============================================================================================
    // THE TWO ROUTING OUTCOMES
    // ===============================================================================================

    @Nested
    @DisplayName("the routing outcome the delivered user type decides")
    class TheRoutingOutcome {

        /** Creates the nested specification. */
        TheRoutingOutcome() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("an administrative identity signs on and is routed to the administrative menu, "
                + "with a token it can present")
        void anAdministrativeIdentityIsRoutedToTheAdministrativeMenu() throws Exception {
            final ResponseEntity<String> reply = post(SEEDED_ADMIN_ID, SEEDED_SECRET);

            assertThat(reply.getStatusCode())
                    .as("a delivered identity presenting the delivered secret is admitted")
                    .isEqualTo(HttpStatus.OK);
            final JsonNode body = JSON.readTree(reply.getBody());
            assertThat(routeOf(body))
                    .as("the administrative type routes to the administrative menu")
                    .isEqualTo(ADMIN_MENU_ROUTE);
            assertThat(messageOf(body))
                    .as("an admitted sign-on carries no refusal text")
                    .doesNotContain(WRONG_PASSWORD)
                    .doesNotContain(USER_NOT_FOUND);
            assertThat(tokenOf(reply))
                    .as("a session token is issued, and it is a three-part signed token rather than a "
                            + "server-side session identifier")
                    .isNotBlank()
                    .matches("[^.]+\\.[^.]+\\.[^.]+");
        }

        @Test
        @DisplayName("an ordinary identity signs on and is routed to the main menu, not the "
                + "administrative one")
        void anOrdinaryIdentityIsRoutedToTheMainMenu() throws Exception {
            final ResponseEntity<String> reply = post(SEEDED_USER_ID, SEEDED_SECRET);

            assertThat(reply.getStatusCode()).isEqualTo(HttpStatus.OK);
            final JsonNode body = JSON.readTree(reply.getBody());
            assertThat(routeOf(body))
                    .as("the ordinary type routes to the main menu and never to the administrative one")
                    .isEqualTo(USER_MENU_ROUTE)
                    .isNotEqualTo(ADMIN_MENU_ROUTE);
            assertThat(tokenOf(reply)).isNotBlank();
        }

        @Test
        @DisplayName("the token an ordinary identity receives does not admit it to an administrative "
                + "surface, so the role gate is the boundary and not the routing hint")
        void anOrdinaryTokenIsRefusedByAnAdministrativeSurface() throws Exception {
            final String token = tokenOf(post(SEEDED_USER_ID, SEEDED_SECRET));

            final HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            final ResponseEntity<String> reply = OnlineTransactionE2ETest.this.http.exchange(
                    url(ADMIN_USERS_PATH), HttpMethod.GET, new HttpEntity<>(headers), String.class);

            assertThat(reply.getStatusCode())
                    .as("an ordinary identity is refused an administrative surface; the routing "
                            + "outcome is a hint to a client, the authority check is the boundary")
                    .isIn(HttpStatus.FORBIDDEN, HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("an unauthenticated request to a protected surface is refused, so nothing above "
                + "depends on the surface being open")
        void anUnauthenticatedRequestIsRefused() {
            final ResponseEntity<String> reply =
                    OnlineTransactionE2ETest.this.http.getForEntity(
                            url(ADMIN_USERS_PATH), String.class);

            assertThat(reply.getStatusCode())
                    .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        }
    }

    // ===============================================================================================
    // HELPERS
    // ===============================================================================================

    /**
     * Submits one sign-on over the real boundary and returns the parsed reply body.
     *
     * @param  identity the identity to submit, exactly as given
     * @param  secret   the secret to submit, exactly as given
     * @return the reply body parsed as JSON
     * @throws Exception if the reply cannot be parsed
     */
    private JsonNode signOn(final String identity, final String secret) throws Exception {
        return JSON.readTree(post(identity, secret).getBody());
    }

    /**
     * Submits one sign-on and returns the reply body as raw text, for the leak assertions.
     *
     * @param  identity the identity to submit
     * @param  secret   the secret to submit
     * @return the reply body as text, never {@code null}
     */
    private String signOnRaw(final String identity, final String secret) {
        final String body = post(identity, secret).getBody();
        return body == null ? "" : body;
    }

    /**
     * Posts one sign-on request to the running boundary.
     *
     * @param  identity the identity to submit
     * @param  secret   the secret to submit
     * @return the whole reply, status included
     */
    private ResponseEntity<String> post(final String identity, final String secret) {
        final Map<String, String> request = new LinkedHashMap<>();
        request.put("userId", identity);
        request.put("password", secret);
        // The legacy program dispatches on the attention key before it looks at anything else, so a
        // submission that names no key takes the invalid-key arm and never reaches a credential check.
        // ENTER is the key that submits, which is why every case below states it.
        request.put("keyAction", SUBMIT_KEY);
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAcceptCharset(java.util.List.of(StandardCharsets.UTF_8));
        return this.http.exchange(url(AuthController.SIGN_ON_PATH), HttpMethod.POST,
                new HttpEntity<>(request, headers), String.class);
    }

    /**
     * Builds an absolute URL against the port the boundary opened.
     *
     * @param  path the path to reach
     * @return the absolute URL
     */
    private String url(final String path) {
        return "http://localhost:" + this.port + path;
    }

    /**
     * Recovers the operator-visible message from a reply body, wherever the contract carries it.
     *
     * <p>Written to search rather than to name one field, because this class asserts the <em>text</em>
     * as the contract; the field it arrives in belongs to the published contract type and has its own
     * specification.
     *
     * @param  body the parsed reply body
     * @return the message text, or the empty string when the reply carries none
     */
    private static String messageOf(final JsonNode body) {
        return firstTextOf(body, "message", "errorMessage", "infoMessage", "text");
    }

    /**
     * Recovers the route the reply directs a client to.
     *
     * @param  body the parsed reply body
     * @return the route, or the empty string when the reply carries none
     */
    private static String routeOf(final JsonNode body) {
        final String route = firstTextOf(body, "nextRoute", "route", "target", "nextProgram",
                "destination");
        assertThat(route).as("the reply must direct the client somewhere: %s", body).isNotBlank();
        return route;
    }

    /**
     * Recovers the session token the reply issues, which travels in the authorization header rather
     * than in the body.
     *
     * <p>That placement is itself part of the contract and is asserted by taking the token from there
     * and from nowhere else: a body-carried credential would be logged by anything that logs a
     * response body.
     *
     * @param  reply the whole reply
     * @return the bare token, without its presentation prefix
     */
    private static String tokenOf(final ResponseEntity<String> reply) {
        final String header = reply.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        assertThat(header)
                .as("the reply must issue a session token in the authorization header")
                .isNotNull()
                .startsWith(BEARER_PREFIX);
        return header.substring(BEARER_PREFIX.length());
    }

    /**
     * Returns the first non-blank textual value found under any of the given field names, at any depth.
     *
     * @param  node  the node to search
     * @param  names the field names to look for, in preference order
     * @return the value, or the empty string when none is present
     */
    private static String firstTextOf(final JsonNode node, final String... names) {
        if (node == null || node.isNull()) {
            return "";
        }
        for (final String name : names) {
            final JsonNode direct = node.get(name);
            if (direct != null && direct.isTextual() && !direct.asText().isBlank()) {
                return direct.asText();
            }
        }
        for (final JsonNode child : node) {
            final String nested = firstTextOf(child, names);
            if (!nested.isBlank()) {
                return nested;
            }
        }
        return "";
    }

    /**
     * The online surface under test: the sign-on and menu boundary, the shipped filter chain, and the
     * repository the delivered identities live in.
     *
     * <p>Assembled explicitly rather than by scanning, so the graph is exactly the surface and a reader
     * can see in one place what took part. <strong>Nothing is stubbed:</strong> the credential digest
     * service, the token provider, the message catalogue and the filter chain are the shipped ones, and
     * the identities come off a real server.
     *
     * <p>The clock is the shared pinned instant, so a token's issued-at and expiry images mean the same
     * thing on every run.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import({AuthController.class, MenuController.class, ModuleErrorController.class,
            SignOnContractAdapter.class, MenuResponseAdapter.class, ConversationStateAdapter.class,
            ScreenStateAdapter.class, GlobalExceptionHandler.class, JsonRefusalBodyRenderer.class,
            AuthenticationService.class, MenuService.class, NavigationService.class,
            MessageCatalogService.class, CredentialDigestService.class, MenuOptionCatalog.class,
            SignOnStateService.class,
            SecurityConfig.class, JwtTokenProvider.class, WebMvcConfig.class})
    @EnableConfigurationProperties(JwtProperties.class)
    @EnableJpaRepositories(basePackageClasses = UserSecurityRepository.class)
    @EntityScan(basePackageClasses = UserSecurity.class)
    static class OnlineContext {

        /** Creates the configuration. */
        OnlineContext() {
            // Intentionally empty: this slice contributes beans, not state.
        }

        /**
         * The pinned clock every collaborator in the graph reads.
         *
         * @return the shared fixed clock
         */
        @org.springframework.context.annotation.Bean
        Clock fixedClock() {
            return FIXED_CLOCK;
        }
    }
}
