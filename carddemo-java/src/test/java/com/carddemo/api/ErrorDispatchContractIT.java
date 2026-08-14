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

import com.carddemo.config.JwtProperties;
import com.carddemo.config.JwtTokenProvider;
import com.carddemo.config.SecurityConfig;
import com.carddemo.config.WebMvcConfig;
import com.carddemo.domain.UserSecurity;
import com.carddemo.domain.enums.UserType;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.AuthenticationService;
import com.carddemo.service.CredentialDigestService;
import com.carddemo.service.MessageCatalogService;
import com.carddemo.service.NavigationService;
import com.carddemo.service.SignOnStateService;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.util.ApiRoutePaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
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
import org.springframework.context.annotation.Bean;
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
 * The container's error dispatch, driven end to end over a bound port.
 *
 * <h2>Why this class exists, and what it caught</h2>
 *
 * <p>Four request conditions are refused by the framework <em>before</em> a handler exists - an unmatched
 * path, an unsupported method, an unreadable request media type and an unsatisfiable {@code Accept}
 * header. No controller advice can answer them, because there is no handler method for one to be selected
 * against. The container records a status, re-dispatches the request to the error path, the authorization
 * rules run again over a request that no longer carries an authentication, and {@link ModuleErrorController}
 * answers. <strong>Every link in that chain is a place the answer can be lost, and not one of them can be
 * established by calling a method.</strong>
 *
 * <p>That is not a hypothetical. The error mapping declared a producible media type, and an error dispatch
 * reproduces the original request's headers, so on the unsatisfiable-{@code Accept} condition the mapping
 * was matched against the very header that could not be satisfied and <em>was not selectable</em>: the
 * caller received a bare {@code 406} with no body. The unit specification passed throughout - it invoked
 * the handler directly, and its registration assertion read the annotation and therefore locked the defect
 * in place. This class is what fails when that happens: it asks a real container for a representation the
 * operation cannot produce and requires the module's envelope to come back.
 *
 * <h2>What each case establishes</h2>
 * <ul>
 *   <li><strong>The status the condition actually was.</strong> Before the error-dispatch permit in
 *       {@link SecurityConfig} existed, all four were answered {@code 401 Authentication required},
 *       because the bearer filter is a once-per-request filter and does not run on an error dispatch. A
 *       {@code 401} on any of these four is therefore a regression of a specific, previously delivered
 *       defect, and each case asserts against it by name.</li>
 *   <li><strong>The module's envelope, as JSON.</strong> One document shape for every refusal, including
 *       for a caller whose {@code Accept} header excluded JSON - the honest alternative to answering a
 *       client that cannot read the answer with no answer at all.</li>
 *   <li><strong>The neutral summary for the status.</strong> Derived from the status alone, which is what
 *       makes it incapable of echoing a path, a message, a type name or a value.</li>
 *   <li><strong>Nothing of the framework's own document.</strong> Its {@code timestamp}, {@code error},
 *       {@code path} and {@code trace} members are asserted absent, because their presence means the
 *       framework's controller answered and this module's did not.</li>
 * </ul>
 *
 * <h2>Why the sign-on route is the route these conditions are provoked on</h2>
 *
 * <p>Three of the four conditions need an existing mapping to be refused against, and the sign-on route is
 * the module's one <em>anonymous</em> route. Provoking them there proves the property that matters most
 * about the permit: a client that sent the wrong content type to a route needing no credential is told
 * about the content type, not sent looking for a credential problem. The fourth condition - an unmatched
 * path - is exercised twice, once with a session and once without, because the two answers differ
 * legitimately and a reader has to be able to tell the legitimate {@code 401} from the defective one.
 *
 * <p>No credential is submitted anywhere in this class and no identity is read: none of the four conditions
 * reaches the authentication service, and the one case that needs a session mints it from the shipped token
 * provider. The legacy cleartext credential appears nowhere here in any form.
 *
 * <p>Provenance: no legacy antecedent. The estate's transaction manager answered an unmapped transaction
 * identifier with its own terminal message; the container error dispatch is a servlet concept the estate
 * had no equivalent of.
 *
 * @since 1.0.0
 */
@SpringBootTest(classes = ErrorDispatchContractIT.ErrorBoundaryContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // The shared server was migrated to the head of the delivered set by the base class.
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.batch.job.enabled=false",
            "spring.main.banner-mode=off",
            "management.endpoint.health.validate-group-membership=false",
            "management.tracing.enabled=false",
            // The boundary is reached over the loopback interface of the process that opened it, so
            // there is no wire for a session to be observed on. Nothing else is relaxed.
            "carddemo.security.require-https=false"})
@DisplayName("the container's error dispatch answers in the module's envelope at the real status")
class ErrorDispatchContractIT extends AbstractPostgresIT {

    /** Reads served bodies. Configured like the module's own mapper is for the members under test. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * A path no mapping claims and no authorization rule names, used to provoke the unmatched-path
     * condition.
     *
     * <p><strong>It sits OUTSIDE the API root, and that is load-bearing.</strong> A path such as
     * {@code /api/no-such-route} reaches the unmatched-path condition only while the chain grants either
     * sign-on authority the whole API root and leaves the dispatcher to report that nothing is mapped
     * there. The chain names the eleven delivered ordinary addresses and refuses everything
     * else beneath the root, so an unknown address under {@code /api} is answered before a handler is
     * looked for and never becomes an unmatched path at all - see {@code docs/decision-log.md} DL-345.
     * Outside the root the closing rule still asks only for an established identity, so a signed-on
     * caller reaches the dispatcher and the condition this group exists for still arises. The refusal
     * that replaced it beneath the root is asserted in its own case below.
     */
    private static final String UNMATCHED_PATH = "/no-such-route";

    /**
     * A path no mapping claims BENEATH the API root, where the chain's closing refusal answers first.
     */
    private static final String UNMATCHED_PATH_BENEATH_THE_API_ROOT =
            ApiRoutePaths.API_PATH_PREFIX + "/no-such-route";

    /** Summary the boundary publishes when a rule refused the caller rather than the caller's session. */
    private static final String ACCESS_DENIED = "Access denied";

    /** A representation no operation of this module produces, used to provoke the negotiation refusal. */
    private static final MediaType UNSATISFIABLE_ACCEPT = MediaType.APPLICATION_PDF;

    /** Summary the boundary publishes for an unmatched path. */
    private static final String ROUTE_NOT_FOUND = "Requested resource was not found";

    /** Summary the boundary publishes for an unsupported method. */
    private static final String METHOD_NOT_SUPPORTED = "Request method is not supported";

    /** Summary the boundary publishes for an unreadable request media type. */
    private static final String MEDIA_TYPE_NOT_SUPPORTED = "Request media type is not supported";

    /** Summary the boundary publishes when no producible representation satisfies the request. */
    private static final String REPRESENTATION_NOT_AVAILABLE = "Requested representation is not available";

    /** Summary a refusal for want of a session carries, which none of the four conditions may answer. */
    private static final String AUTHENTICATION_REQUIRED = "Authentication required";

    /**
     * Members of the framework's own error document.
     *
     * <p>Their presence means the framework's error controller answered and this module's did not, which
     * is a different failure from a wrong status and is worth naming separately.
     */
    private static final List<String> FRAMEWORK_DOCUMENT_MEMBERS =
            List.of("\"timestamp\"", "\"error\"", "\"path\"", "\"trace\"", "\"exception\"");

    /**
     * Identity the minted session names: one of the ten the credential seed applies.
     *
     * <p>A delivered identity rather than one written here, because the shipped issuer refuses to mint a
     * session for an identifier no credential record carries and seals a fingerprint of that record into
     * the token. Nothing about the record is asserted, no credential is submitted and the row is neither
     * written nor mutated: the session's only job is to carry an unmatched path past the catch-all rule.
     */
    private static final String SEEDED_USER_IDENTITY = "USER0001";

    /** The port the boundary opened. */
    @LocalServerPort
    private int port;

    /** The client every case here drives the boundary through. */
    @Autowired
    private TestRestTemplate http;

    /** The shipped issuer, used to mint the one session the unmatched-path case needs. */
    @Autowired
    private JwtTokenProvider tokenProvider;

    @Nested
    @DisplayName("each of the four conditions the framework refuses before a handler exists")
    class TheFourFrameworkRefusals {

        @Test
        @DisplayName("an unmatched path answered to a signed-on caller is 404 in the module envelope, "
                + "never 401")
        void anUnmatchedPathIsNotFound() throws IOException {
            final ResponseEntity<String> answer = exchange(HttpMethod.GET, UNMATCHED_PATH,
                    headers(MediaType.APPLICATION_JSON, null, session()));

            assertModuleEnvelope(answer, HttpStatus.NOT_FOUND, ROUTE_NOT_FOUND);
        }

        @Test
        @DisplayName("an unsupported method on the anonymous sign-on route is 405, so a client is told "
                + "about the method rather than sent looking for a credential problem")
        void anUnsupportedMethodIsMethodNotAllowed() throws IOException {
            final ResponseEntity<String> answer = exchange(HttpMethod.PUT, ApiRoutePaths.SIGN_ON_PATH,
                    headers(MediaType.APPLICATION_JSON, MediaType.APPLICATION_JSON, null));

            assertModuleEnvelope(answer, HttpStatus.METHOD_NOT_ALLOWED, METHOD_NOT_SUPPORTED);
        }

        @Test
        @DisplayName("an unreadable request media type is 415, which is the condition that used to be "
                + "reported as an authentication failure on a route needing no credential")
        void anUnreadableRequestMediaTypeIsUnsupportedMediaType() throws IOException {
            final ResponseEntity<String> answer = exchange(HttpMethod.POST, ApiRoutePaths.SIGN_ON_PATH,
                    headers(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN, null),
                    "this is not a sign-on document");

            assertModuleEnvelope(answer, HttpStatus.UNSUPPORTED_MEDIA_TYPE, MEDIA_TYPE_NOT_SUPPORTED);
        }

        @Test
        @DisplayName("a request accepting only a representation the module never produces is 406 AND "
                + "carries the envelope, which is the case a producible type on the error mapping made "
                + "unanswerable")
        void anUnsatisfiableAcceptIsNotAcceptableAndStillCarriesTheEnvelope() throws IOException {
            final ResponseEntity<String> answer = exchange(HttpMethod.GET, ApiRoutePaths.SIGN_ON_PATH,
                    headers(UNSATISFIABLE_ACCEPT, null, null));

            // The whole point: the body is present, it is JSON, and it is this module's document - for a
            // caller who said it would not accept JSON. An empty body here is the defect returning.
            assertThat(answer.getBody())
                    .as("the envelope must be served even though the caller accepted only %s. An absent "
                            + "body means the error mapping was refused a second time by content "
                            + "negotiation, which is exactly the defect this case exists for",
                            UNSATISFIABLE_ACCEPT)
                    .isNotBlank();
            assertModuleEnvelope(answer, HttpStatus.NOT_ACCEPTABLE, REPRESENTATION_NOT_AVAILABLE);
        }
    }

    @Nested
    @DisplayName("the boundary between a legitimate authentication refusal and the defect")
    class TheLegitimateRefusalIsStillTheAnswer {

        @Test
        @DisplayName("an unmatched path reached with no session is 401, because the authorization rules "
                + "answer a request dispatch before any handler is looked for")
        void anUnmatchedPathWithNoSessionIsStillAuthenticationRequired() throws IOException {
            final ResponseEntity<String> answer = exchange(HttpMethod.GET, UNMATCHED_PATH,
                    headers(MediaType.APPLICATION_JSON, null, null));

            assertThat(answer.getStatusCode())
                    .as("this 401 is correct and must stay: the closing rule governs an unknown path "
                            + "outside the API root on an ordinary request dispatch, and the module never "
                            + "discloses whether the path exists")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(JSON.readTree(answer.getBody()).path("message").asText())
                    .isEqualTo(AUTHENTICATION_REQUIRED);
        }

        @Test
        @DisplayName("an unknown address BENEATH the API root is refused to a signed-on caller rather "
                + "than reported absent, because no rule names it")
        void anUnknownAddressBeneathTheApiRootIsRefused() throws IOException {
            final ResponseEntity<String> answer = exchange(HttpMethod.GET,
                    UNMATCHED_PATH_BENEATH_THE_API_ROOT,
                    headers(MediaType.APPLICATION_JSON, null, session()));

            assertThat(answer.getStatusCode())
                    .as("the chain grants eleven ordinary addresses and refuses the rest of the root, so "
                            + "this answer comes from authorization and not from the dispatcher. A 404 "
                            + "here would mean an address nothing serves had been authorized")
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(JSON.readTree(answer.getBody()).path("message").asText())
                    .as("the refusal names neither the rule that refused nor whether the address exists")
                    .isEqualTo(ACCESS_DENIED);
            assertThat(answer.getBody().toUpperCase(Locale.ROOT))
                    .doesNotContain(UNMATCHED_PATH_BENEATH_THE_API_ROOT.toUpperCase(Locale.ROOT));
        }

        @Test
        @DisplayName("and it is refused the same way with no session at all, so the two cases are not "
                + "distinguishable by whether the address exists")
        void anUnknownAddressBeneathTheApiRootWithNoSessionIsAuthenticationRequired() throws IOException {
            final ResponseEntity<String> answer = exchange(HttpMethod.GET,
                    UNMATCHED_PATH_BENEATH_THE_API_ROOT,
                    headers(MediaType.APPLICATION_JSON, null, null));

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(JSON.readTree(answer.getBody()).path("message").asText())
                    .isEqualTo(AUTHENTICATION_REQUIRED);
        }

        @Test
        @DisplayName("a request addressed to the error path directly is an ordinary dispatch, so it is "
                + "still authorized and is not a way to ask for an error that did not happen")
        void addressingTheErrorPathDirectlyIsStillAuthorized() {
            final ResponseEntity<String> answer = exchange(HttpMethod.GET,
                    ModuleErrorController.ERROR_PATH_DEFAULT,
                    headers(MediaType.APPLICATION_JSON, null, null));

            assertThat(answer.getStatusCode())
                    .as("the error path carries no exemption of its own; only the container's ERROR "
                            + "dispatch is permitted")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    /**
     * Asserts the whole published answer of one refused request.
     *
     * @param  answer   the served response
     * @param  expected the status the condition actually was
     * @param  summary  the neutral summary the boundary publishes for that status
     * @throws IOException if the body cannot be parsed
     */
    private static void assertModuleEnvelope(final ResponseEntity<String> answer,
            final HttpStatus expected, final String summary) throws IOException {

        assertThat(answer.getStatusCode())
                .as("the status the container recorded must cross unchanged. A 401 here is the "
                        + "authorization rules answering an error dispatch that carries no "
                        + "authentication, which is the defect the error-dispatch permit removed")
                .isEqualTo(expected);
        assertThat(answer.getHeaders().getContentType())
                .as("one document shape for every refusal, whatever the request would have accepted")
                .isNotNull()
                .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_JSON))
                        .as("served as %s", type)
                        .isTrue());

        final String body = answer.getBody();
        assertThat(body).as("a refusal carries the envelope, never an empty payload").isNotBlank();
        final JsonNode document = JSON.readTree(body);

        assertThat(document.path("message").asText())
                .as("the summary is derived from the status alone, which is why it cannot echo the "
                        + "requested path, the framework's message or a type name")
                .isEqualTo(summary);
        assertThat(document.path("message").asText())
                .as("and it is never the want-of-a-session summary, which is what all four of these "
                        + "conditions used to be reported as")
                .isNotEqualTo(AUTHENTICATION_REQUIRED);
        assertThat(document.has("fieldErrors"))
                .as("the envelope's per-field list is always present rather than absent, so a client "
                        + "never tests for null")
                .isTrue();
        assertThat(document.path("fieldErrors"))
                .as("and empty, because a refused request reached no screen field")
                .isEmpty();

        for (final String member : FRAMEWORK_DOCUMENT_MEMBERS) {
            assertThat(body)
                    .as("%s belongs to the framework's own error document; its presence means the "
                            + "framework's controller answered and this module's did not", member)
                    .doesNotContain(member);
        }
        assertThat(body.toUpperCase(Locale.ROOT))
                .as("and nothing about the condition is narrated: no type name, no path, no stack")
                .doesNotContain("EXCEPTION", "ORG.SPRINGFRAMEWORK", "COM.CARDDEMO", "\tAT ",
                        UNMATCHED_PATH.toUpperCase(Locale.ROOT),
                        UNMATCHED_PATH_BENEATH_THE_API_ROOT.toUpperCase(Locale.ROOT));
    }

    /**
     * Mints the one session this specification needs, directly from the shipped issuer.
     *
     * <p>No credential is submitted and no identity is read: the four conditions under test are refused
     * before any handler runs, so the session's only job is to get an unmatched path past the catch-all
     * rule and let the container reach the unmatched-path condition.
     *
     * @return the bearer session value
     */
    private String session() {
        return tokenProvider.issue(SEEDED_USER_IDENTITY, UserType.USER, UserType.USER.getCode());
    }

    /**
     * Builds the request headers one case needs.
     *
     * @param  accept      the representation the caller will accept, or {@code null} for none
     * @param  contentType the representation the caller is sending, or {@code null} for none
     * @param  token       the bearer session to present, or {@code null} for none
     * @return the headers
     */
    private static HttpHeaders headers(final MediaType accept, final MediaType contentType,
            final String token) {
        final HttpHeaders headers = new HttpHeaders();
        if (accept != null) {
            headers.setAccept(List.of(accept));
        }
        if (contentType != null) {
            headers.setContentType(contentType);
        }
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    /**
     * Drives one request against the bound port.
     *
     * @param  method  the request method, which the error dispatch reproduces
     * @param  path    the path to address
     * @param  headers the request headers
     * @return the whole reply, status, headers and body
     */
    private ResponseEntity<String> exchange(final HttpMethod method, final String path,
            final HttpHeaders headers) {
        return exchange(method, path, headers, null);
    }

    /**
     * Drives one request carrying a body against the bound port.
     *
     * @param  method  the request method
     * @param  path    the path to address
     * @param  headers the request headers
     * @param  body    the request body, or {@code null} for none
     * @return the whole reply
     */
    private ResponseEntity<String> exchange(final HttpMethod method, final String path,
            final HttpHeaders headers, final String body) {
        return this.http.exchange("http://localhost:" + this.port + path, method,
                new HttpEntity<>(body, headers), String.class);
    }

    /**
     * The error boundary under test: the shipped filter chain, the shipped advice, the shipped error
     * controller and the one anonymous route three of the four conditions are provoked on.
     *
     * <p>Assembled explicitly rather than by scanning, so the graph is exactly the surface. Nothing is
     * stubbed: the authorization rules, the token provider, the refusal renderer and the error controller
     * are the shipped ones.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import({AuthController.class, ModuleErrorController.class, SignOnContractAdapter.class,
        GlobalExceptionHandler.class, JsonRefusalBodyRenderer.class, AuthenticationService.class,
        NavigationService.class,
        MessageCatalogService.class,
        CredentialDigestService.class, SignOnStateService.class, SecurityConfig.class,
        JwtTokenProvider.class, WebMvcConfig.class})
    @EnableConfigurationProperties(JwtProperties.class)
    @EnableJpaRepositories(basePackageClasses = UserSecurityRepository.class)
    @EntityScan(basePackageClasses = UserSecurity.class)
    static class ErrorBoundaryContext {

        /** Creates the configuration. */
        ErrorBoundaryContext() {
            // Intentionally empty: this slice contributes beans, not state.
        }

        /**
         * The pinned clock every collaborator in the graph reads.
         *
         * @return the shared fixed clock
         */
        @Bean
        Clock fixedClock() {
            return FIXED_CLOCK;
        }
    }
}
