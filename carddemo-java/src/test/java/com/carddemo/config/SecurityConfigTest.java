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

import com.carddemo.api.GlobalExceptionHandler;
import com.carddemo.domain.enums.UserType;
import jakarta.servlet.Filter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Asserts what the request-authorization boundary actually answers, by exercising the real filter chain
 * and reading real response statuses.
 *
 * <p>Two review findings are answered here, and both were findings about a <em>claim</em> that nothing
 * enforced. The configuration described the metrics scrape endpoint as an unauthenticated surface and
 * reopened the interface description in one profile, while no filter chain existed at all - so the
 * framework's defaults decided reachability, permitting the health probe and authenticating the scrape
 * endpoint. Separately, a token issuer, a token lifetime and a mandatory-transport setting were published
 * with no binder, provider, filter or channel rule consuming any of them, leaving the framework's
 * generated-user login in place as the actual authentication mechanism.</p>
 *
 * <p><strong>Every assertion below is a response status or a response body, never a reading of the
 * configuration that produced it.</strong> That distinction is the point: a test that inspected the rules
 * would agree with a chain that had been assembled in the wrong order, and rule order is precisely what
 * decides whether a management endpoint added to a profile's exposure list becomes anonymous. The chain is
 * obtained from a real context and driven through real requests, so the order is exercised rather than
 * described.</p>
 *
 * <p>The probe endpoints exist only to give each rule a reachable target: without a handler, a permitted
 * path could only be shown to be "not refused", which cannot be told apart from a path that is missing.
 * With one, a permitted path answers {@code 200} and the distinction between permitted and refused is
 * unambiguous.</p>
 *
 * <p>No real credential appears in this class, and no value any profile ships is restated here.</p>
 */
@DisplayName("Request authorization: what the filter chain actually answers, asserted by real status")
class SecurityConfigTest {

    /** Signing material for the chain under test. Not a credential; only its length matters. */
    private static final String SECRET = "security-config-test-signing-secret-0123456789abcdef";

    /** Issuer the chain's provider claims and requires. */
    private static final String ISSUER = "carddemo-java";

    /** Base path the probe management endpoints are published beneath. */
    private static final String MANAGEMENT_BASE = "/actuator";

    /** Address the probe interface description is published at. */
    private static final String API_DOCS = "/v3/api-docs";

    /** An ordinary protected business route, beneath no special prefix. */
    private static final String ORDINARY_ROUTE = "/api/accounts/00000000001";

    /** An administrative route, beneath the administrative prefix. */
    private static final String ADMIN_ROUTE = SecurityConfig.ADMIN_PATH_PREFIX + "/users";

    /** A protected route that commits its answer before failing authorization. */
    private static final String COMMITTED_ROUTE = "/api/accounts/commit-then-deny";

    /** Instant the fixed clock reports, so token windows are exact. */
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    /** Clears any identity a request established, so no test can inherit another's. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Supplies the fixed clock the token provider mints and judges expiry against.
     */
    @Configuration(proxyBeanMethods = false)
    static class FixedClockConfig {

        /**
         * A clock pinned to {@link #NOW}.
         *
         * @return the fixed clock
         */
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    /**
     * Handlers standing in for each surface the rules name, so a permitted request can answer {@code 200}.
     *
     * <p>They implement nothing and are deliberately trivial: what is under test is whether a request
     * reaches a handler at all, not what a handler does.</p>
     */
    @RestController
    static class ProbeEndpoints {

        /**
         * Stands in for the health probe.
         *
         * @return a fixed body
         */
        @GetMapping(MANAGEMENT_BASE + "/health")
        String health() {
            return "health";
        }

        /**
         * Stands in for a health probe group beneath the health path.
         *
         * @return a fixed body
         */
        @GetMapping(MANAGEMENT_BASE + "/health/liveness")
        String liveness() {
            return "liveness";
        }

        /**
         * Stands in for the metrics scrape endpoint.
         *
         * @return a fixed body
         */
        @GetMapping(MANAGEMENT_BASE + "/prometheus")
        String prometheus() {
            return "prometheus";
        }

        /**
         * Stands in for the environment endpoint, which only the local overlay publishes.
         *
         * @return a fixed body
         */
        @GetMapping(MANAGEMENT_BASE + "/env")
        String environment() {
            return "env";
        }

        /**
         * Stands in for the build-identity endpoint, which the shared baseline publishes.
         *
         * @return a fixed body
         */
        @GetMapping(MANAGEMENT_BASE + "/info")
        String info() {
            return "info";
        }

        /**
         * Stands in for the meter-name endpoint, which only the local overlay publishes.
         *
         * @return a fixed body
         */
        @GetMapping(MANAGEMENT_BASE + "/metrics")
        String metrics() {
            return "metrics";
        }

        /**
         * Stands in for the interface description.
         *
         * @return a fixed body
         */
        @GetMapping(API_DOCS)
        String apiDocs() {
            return "api-docs";
        }

        /**
         * Stands in for the sign-on route, the one anonymous business route.
         *
         * @return a fixed body
         */
        @GetMapping(SecurityConfig.SIGN_ON_PATH)
        String signOn() {
            return "sign-on";
        }

        /**
         * Stands in for an ordinary protected business route.
         *
         * @return a fixed body
         */
        @GetMapping(ORDINARY_ROUTE)
        String ordinary() {
            return "ordinary";
        }

        /**
         * Stands in for an administrative route.
         *
         * @return a fixed body
         */
        @GetMapping(ADMIN_ROUTE)
        String admin() {
            return "admin";
        }

        /**
         * Begins answering and only then fails authorization, which is the one way a refusal can be
         * reached after the response has already been committed.
         *
         * @param response response to commit before failing
         * @throws java.io.IOException if committing the response fails
         */
        @GetMapping(COMMITTED_ROUTE)
        void commitThenDeny(final jakarta.servlet.http.HttpServletResponse response)
                throws java.io.IOException {
            response.getWriter().write("partial");
            response.flushBuffer();
            throw new AccessDeniedException("denied after the answer had begun");
        }
    }

    /**
     * Builds a runner in the posture the local and test overlays take for the scrape endpoint, which is
     * open to an anonymous collector.
     *
     * @param requireHttps whether the chain must require a secure channel
     * @param publishDocs  whether the running profile publishes the interface description
     * @return the configured runner
     */
    private static WebApplicationContextRunner runner(final boolean requireHttps,
            final boolean publishDocs) {
        return runner(requireHttps, publishDocs, true);
    }

    /**
     * Builds a runner carrying the chain, the token provider and the probe handlers.
     *
     * @param requireHttps     whether the chain must require a secure channel
     * @param publishDocs      whether the running profile publishes the interface description
     * @param anonymousScrape  whether the running profile opens the metrics scrape endpoint to a
     *                         collector presenting no credential; the local and test overlays open it
     *                         and the shared baseline and production leave it closed
     * @return the configured runner
     */
    private static WebApplicationContextRunner runner(final boolean requireHttps,
            final boolean publishDocs, final boolean anonymousScrape) {
        return new WebApplicationContextRunner()
                // The two security auto-configurations are present deliberately. They are what would
                // supply a generated user and a default permit-nothing-named chain if this module
                // supplied neither, so including them is the only way an assertion that they stood down
                // can mean anything. Without them, "no generated user exists" would be true because
                // nothing had offered one.
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class))
                .withUserConfiguration(SecurityConfig.class, JwtTokenProvider.class,
                        FixedClockConfig.class)
                .withPropertyValues(
                        JwtProperties.PREFIX + ".secret=" + SECRET,
                        JwtProperties.PREFIX + ".issuer=" + ISSUER,
                        JwtProperties.PREFIX + ".expiration=PT30M",
                        "carddemo.security.require-https=" + requireHttps,
                        "carddemo.security.anonymous-metrics-scrape=" + anonymousScrape,
                        "springdoc.api-docs.enabled=" + publishDocs,
                        "springdoc.api-docs.path=" + API_DOCS,
                        "management.endpoints.web.base-path=" + MANAGEMENT_BASE);
    }

    /** A runner in the posture the local and test overlays take: no transport requirement. */
    private static WebApplicationContextRunner plainTransport() {
        return runner(false, false);
    }

    /**
     * A runner in the posture the shared baseline and production take for the scrape endpoint: closed to
     * a collector presenting no credential, with transport left plain so the assertion reads an
     * authorization outcome rather than a redirect.
     *
     * @return the configured runner
     */
    private static WebApplicationContextRunner closedScrape() {
        return runner(false, false, false);
    }

    /**
     * Builds a client that drives the context's real security chain ahead of the probe handlers.
     *
     * @param context a started context carrying the chain
     * @return a client whose requests pass through the chain
     */
    private static MockMvc clientFor(final AssertableWebApplicationContext context) {
        final Filter chain = context.getBean("springSecurityFilterChain", Filter.class);
        return MockMvcBuilders.standaloneSetup(new ProbeEndpoints())
                .addFilters(chain)
                .build();
    }

    /**
     * Mints a token for the given user type using the context's own provider.
     *
     * @param context a started context
     * @param type    user type the token should carry
     * @return the credential value to present
     */
    private static String tokenFor(final AssertableWebApplicationContext context, final UserType type) {
        return context.getBean(JwtTokenProvider.class).issue("TESTUSR1", type);
    }

    @Nested
    @DisplayName("The surfaces a sibling file resolves literally")
    class OperationalSurfaces {

        @ParameterizedTest(name = "GET {0}")
        @ValueSource(strings = {MANAGEMENT_BASE + "/health", MANAGEMENT_BASE + "/health/liveness"})
        @DisplayName("answer the health probe without a credential, because the image health check and "
                + "every waiting Compose service present none")
        void permitTheHealthProbe(final String path) throws Exception {
            plainTransport().run(context -> clientFor(context).perform(get(path))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("the health probe must be reachable anonymously")
                            .isEqualTo(200)));
        }

        @Test
        @DisplayName("answer the metrics scrape endpoint without a credential WHERE THE PROFILE OPENS "
                + "IT, because the local and test collector runs on the same machine and supplies none")
        void permitTheScrapeEndpointWhereTheProfileOpensIt() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(MANAGEMENT_BASE + "/prometheus"))
                    .andExpect(result -> {
                        assertThat(result.getResponse().getStatus()).isEqualTo(200);
                        // Reading the body proves the request reached a handler rather than merely
                        // failing to be refused, which a missing mapping would also look like.
                        assertThat(result.getResponse().getContentAsString()).isEqualTo("prometheus");
                    }));
        }

        @Test
        @DisplayName("REFUSE the metrics scrape endpoint where the profile does not open it - the shared "
                + "baseline and production - because the exposition is the shape of the workload rather "
                + "than a status word")
        void refuseTheScrapeEndpointWhereTheProfileDoesNotOpenIt() throws Exception {
            closedScrape().run(context -> clientFor(context)
                    .perform(get(MANAGEMENT_BASE + "/prometheus"))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("per-endpoint latency distributions, per-batch-step record counts, pool "
                                    + "saturation and JVM internals must not be readable by a client "
                                    + "that presents nothing")
                            .isEqualTo(401)));
        }

        @Test
        @DisplayName("still answer the metrics scrape endpoint for a CREDENTIALED collector where the "
                + "profile does not open it, so closing anonymity does not unpublish the endpoint and "
                + "the performance gate keeps its data source")
        void answerTheScrapeEndpointForACredentialedCollector() throws Exception {
            closedScrape().run(context -> clientFor(context)
                    .perform(get(MANAGEMENT_BASE + "/prometheus").header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenFor(context, UserType.USER)))
                    .andExpect(result -> {
                        assertThat(result.getResponse().getStatus())
                                .as("a production collector authenticates and collects; that is the "
                                        + "whole reason the endpoint stays in the exposure list")
                                .isEqualTo(200);
                        assertThat(result.getResponse().getContentAsString()).isEqualTo("prometheus");
                    }));
        }

        @Test
        @DisplayName("keep answering the health probe anonymously with the scrape endpoint closed, so "
                + "closing one anonymous surface does not close the other")
        void keepTheHealthProbeAnonymousWithTheScrapeEndpointClosed() throws Exception {
            closedScrape().run(context -> clientFor(context)
                    .perform(get(MANAGEMENT_BASE + "/health"))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("the image health check presents no credential and must keep working")
                            .isEqualTo(200)));
        }

        @ParameterizedTest(name = "GET {0}")
        @ValueSource(strings = {MANAGEMENT_BASE + "/env", MANAGEMENT_BASE + "/info",
                MANAGEMENT_BASE + "/metrics"})
        @DisplayName("refuse every other management endpoint, including the ones only a profile's wider "
                + "exposure list publishes - so widening that list never widens anonymity")
        void refuseEveryOtherManagementEndpoint(final String path) throws Exception {
            plainTransport().run(context -> clientFor(context).perform(get(path))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("%s describes the running system and must require a credential", path)
                            .isEqualTo(401)));
        }

        @Test
        @DisplayName("refuse the interface description in a profile that does not publish it")
        void refuseTheDescriptionWhereUnpublished() throws Exception {
            runner(false, false).run(context -> clientFor(context).perform(get(API_DOCS))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401)));
        }

        @Test
        @DisplayName("permit the interface description in a profile that does publish it, so one switch "
                + "decides both whether it is served and whether it may be read")
        void permitTheDescriptionWherePublished() throws Exception {
            runner(false, true).run(context -> clientFor(context).perform(get(API_DOCS))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200)));
        }
    }

    @Nested
    @DisplayName("Business routes")
    class BusinessRoutes {

        @Test
        @DisplayName("permit the sign-on route without a credential, because it is the route that "
                + "issues them")
        void permitTheSignOnRoute() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(SecurityConfig.SIGN_ON_PATH))
                    .andExpect(result -> {
                        assertThat(result.getResponse().getStatus()).isEqualTo(200);
                        assertThat(result.getResponse().getContentAsString()).isEqualTo("sign-on");
                    }));
        }

        @Test
        @DisplayName("refuse an ordinary route presenting no credential, which is the closed default "
                + "rather than a rule written for that route")
        void refuseAnOrdinaryRouteWithoutACredential() throws Exception {
            plainTransport().run(context -> clientFor(context).perform(get(ORDINARY_ROUTE))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401)));
        }

        @Test
        @DisplayName("admit an ordinary route to a standard user's token")
        void admitAnOrdinaryRouteToAStandardUser() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(ORDINARY_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenFor(context, UserType.USER)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200)));
        }

        @Test
        @DisplayName("admit an administrative route to an administrator's token")
        void admitAnAdminRouteToAnAdministrator() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(ADMIN_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenFor(context, UserType.ADMIN)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200)));
        }

        @Test
        @DisplayName("refuse an administrative route to a standard user's token with a forbidden rather "
                + "than an unauthorized answer, which is the distinction the estate drew")
        void refuseAnAdminRouteToAStandardUser() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(ADMIN_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenFor(context, UserType.USER)))
                    .andExpect(result -> {
                        assertThat(result.getResponse().getStatus())
                                .as("a principal exists and is not entitled")
                                .isEqualTo(403);
                        // Were the entitlement rule absent, this same token would satisfy the closing
                        // catch-all and the handler would answer 200 - so this assertion is what proves
                        // the administrative gate is present and reached, not merely written.
                        assertThat(result.getResponse().getContentAsString()).doesNotContain("admin");
                    }));
        }

        @Test
        @DisplayName("refuse an administrative route presenting no credential at all as unauthorized, "
                + "not forbidden")
        void refuseAnAdminRouteWithoutACredential() throws Exception {
            plainTransport().run(context -> clientFor(context).perform(get(ADMIN_ROUTE))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401)));
        }
    }

    @Nested
    @DisplayName("A credential that may not be trusted")
    class UntrustedCredentials {

        @ParameterizedTest(name = "Authorization: {0}")
        @ValueSource(strings = {"Bearer not-a-token", "Bearer ", "Bearer a.b.c",
                "Basic dXNlcjpwYXNzd29yZA==", "token-without-a-scheme"})
        @DisplayName("is refused, and refused identically however it is malformed, so nothing is "
                + "learned from the difference")
        void isRefused(final String headerValue) throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(ORDINARY_ROUTE).header(HttpHeaders.AUTHORIZATION, headerValue))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401)));
        }

        @Test
        @DisplayName("is refused when the token has expired, so a lapsed credential is not a standing one")
        void isRefusedWhenExpired() throws Exception {
            plainTransport().run(context -> {
                // Minted by a provider whose clock is far enough in the past that the token is beyond
                // both its lifetime and the verifier's permitted skew by the time the chain sees it.
                final JwtTokenProvider past = new JwtTokenProvider(
                        new JwtProperties(SECRET, ISSUER, Duration.ofMinutes(30)),
                        Clock.fixed(NOW.minus(Duration.ofHours(2)), ZoneOffset.UTC));

                clientFor(context)
                        .perform(get(ORDINARY_ROUTE).header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + past.issue("TESTUSR1", UserType.ADMIN)))
                        .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
            });
        }

        @Test
        @DisplayName("is refused when signed with foreign material, so a token minted elsewhere carries "
                + "no authority here")
        void isRefusedWhenSignedElsewhere() throws Exception {
            plainTransport().run(context -> {
                final JwtTokenProvider foreign = new JwtTokenProvider(
                        new JwtProperties("a-foreign-signing-secret-value-0123456789abcdef", ISSUER,
                                Duration.ofMinutes(30)),
                        Clock.fixed(NOW, ZoneOffset.UTC));

                clientFor(context)
                        .perform(get(ADMIN_ROUTE).header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + foreign.issue("TESTUSR1", UserType.ADMIN)))
                        .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
            });
        }

        @Test
        @DisplayName("is accepted when the scheme is spelled in a different case, which the credential "
                + "specification permits")
        void isAcceptedWhateverTheSchemeCase() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(ORDINARY_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "bearer " + tokenFor(context, UserType.USER)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200)));
        }
    }

    @Nested
    @DisplayName("The body of a refusal")
    class RefusalBody {

        @Test
        @DisplayName("is the module's own error shape carrying the shared authentication summary, so a "
                + "filter-boundary refusal reads exactly like a dispatch-boundary one")
        void carriesTheSharedAuthenticationSummary() throws Exception {
            plainTransport().run(context -> clientFor(context).perform(get(ORDINARY_ROUTE))
                    .andExpect(result -> {
                        assertThat(result.getResponse().getContentAsString())
                                .contains(GlobalExceptionHandler.AUTHENTICATION_REQUIRED_MESSAGE)
                                .contains("\"fieldErrors\"");
                        assertThat(result.getResponse().getContentType())
                                .as("a refusal must be JSON, not a container error page")
                                .startsWith("application/json");
                    }));
        }

        @Test
        @DisplayName("carries the shared access-denied summary when an established principal is refused")
        void carriesTheSharedAccessDeniedSummary() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(ADMIN_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenFor(context, UserType.USER)))
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                            .contains(GlobalExceptionHandler.ACCESS_DENIED_MESSAGE)));
        }

        @Test
        @DisplayName("names the credential scheme it expects when none was presented, so the answer is "
                + "actionable without describing the rule that refused")
        void namesTheExpectedScheme() throws Exception {
            plainTransport().run(context -> clientFor(context).perform(get(ORDINARY_ROUTE))
                    .andExpect(result -> assertThat(
                            result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                            .isEqualTo("Bearer")));
        }

        @Test
        @DisplayName("sends no challenge when the refusal was an entitlement rather than a credential, "
                + "since presenting a different credential is not what the caller should do")
        void sendsNoChallengeForAnEntitlementRefusal() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(ADMIN_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenFor(context, UserType.USER)))
                    .andExpect(result -> assertThat(
                            result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull()));
        }

        @Test
        @DisplayName("is never appended to an answer already sent: the attempt fails loudly instead, "
                + "which is why the refusal writer carries no committed-response guard of its own")
        void isNeverAppendedToACommittedAnswer() {
            plainTransport().run(context -> {
                final MockMvc client = clientFor(context);
                final String credential = "Bearer " + tokenFor(context, UserType.USER);

                // The probe route commits its answer and only then fails authorization. The chain's
                // exception-translation filter detects the committed response and raises rather than
                // invoking the access-denied handler, so a partial answer can never be followed by a
                // refusal body. Asserting that here is what makes the absence of a duplicate guard in
                // the refusal writer a verified fact rather than an assumption.
                assertThatThrownBy(() -> client.perform(
                        get(COMMITTED_ROUTE).header(HttpHeaders.AUTHORIZATION, credential)))
                        .as("the framework must report that it could not refuse a committed response")
                        .hasStackTraceContaining("already committed")
                        .rootCause()
                        .as("and must preserve the original reason for diagnosis rather than replace it")
                        .isInstanceOf(AccessDeniedException.class);
            });
        }

        @Test
        @DisplayName("names neither the rule that refused nor the entitlement that would have satisfied it")
        void describesNeitherRuleNorEntitlement() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(ADMIN_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenFor(context, UserType.USER)))
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                            .doesNotContain(JwtTokenProvider.ADMIN_AUTHORITY)
                            .doesNotContain(SecurityConfig.ADMIN_PATH_PREFIX)));
        }
    }

    @Nested
    @DisplayName("Transport")
    class Transport {

        @ParameterizedTest(name = "GET {0}")
        @ValueSource(strings = {MANAGEMENT_BASE + "/health", ORDINARY_ROUTE,
                SecurityConfig.SIGN_ON_PATH})
        @DisplayName("redirects every insecure request when a secure channel is required, exempting "
                + "nothing - not even the surfaces that need no credential")
        void redirectsWhenSecureChannelRequired(final String path) throws Exception {
            runner(true, false).run(context -> clientFor(context).perform(get(path))
                    .andExpect(result -> {
                        assertThat(result.getResponse().getStatus())
                                .as("an insecure request must be redirected, not served")
                                .isEqualTo(302);
                        assertThat(result.getResponse().getRedirectedUrl()).startsWith("https://");
                    }));
        }

        @Test
        @DisplayName("serves an insecure request when the profile has cleared the requirement, which is "
                + "what keeps loopback development workable")
        void servesPlainRequestsWhenTheRequirementIsCleared() throws Exception {
            runner(false, false).run(context -> clientFor(context)
                    .perform(get(MANAGEMENT_BASE + "/health"))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200)));
        }
    }

    @Nested
    @DisplayName("Statelessness")
    class Statelessness {

        @Test
        @DisplayName("creates no session for an authenticated request, so nothing is carried between "
                + "requests and there is no ambient authority for a cross-site request to borrow")
        void createsNoSession() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(ORDINARY_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenFor(context, UserType.USER)))
                    .andExpect(result -> assertThat(result.getRequest().getSession(false))
                            .as("an authenticated request must not establish a session")
                            .isNull()));
        }

        @Test
        @DisplayName("sets no cookie on a refusal either, so a refused request leaves nothing behind")
        void setsNoCookieOnRefusal() throws Exception {
            plainTransport().run(context -> clientFor(context).perform(get(ORDINARY_ROUTE))
                    .andExpect(result -> assertThat(result.getResponse().getCookies()).isEmpty()));
        }

        @Test
        @DisplayName("leaves no identity behind in the holder after the response, so one request's "
                + "identity cannot serve another")
        void leavesNoIdentityBehind() throws Exception {
            plainTransport().run(context -> {
                clientFor(context).perform(get(ORDINARY_ROUTE).header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + tokenFor(context, UserType.ADMIN)));

                assertThat(SecurityContextHolder.getContext().getAuthentication())
                        .as("the chain must clear the identity it established")
                        .isNull();
            });
        }
    }

    @Nested
    @DisplayName("Container wiring")
    class ContainerWiring {

        @Test
        @DisplayName("starts and publishes exactly one filter chain")
        void publishesOneChain() {
            plainTransport().run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasBean("springSecurityFilterChain");
            });
        }

        @Test
        @DisplayName("withdraws the framework's generated-user login by declaring an authentication "
                + "manager, so no generated password is ever produced or logged")
        void withdrawsTheGeneratedUser() {
            plainTransport().run(context -> {
                assertThat(context)
                        .as("the auto-configured in-memory user stands down only when a manager exists")
                        .hasSingleBean(AuthenticationManager.class);
                assertThat(context)
                        .as("no user-details service means no generated user and no logged password")
                        .doesNotHaveBean(UserDetailsService.class);
                assertThat(context)
                        .as("the generated credential is held by that one bean type and nothing else")
                        .doesNotHaveBean(InMemoryUserDetailsManager.class);
            });
        }

        @Test
        @DisplayName("control: the same surroundings do supply a generated user when this module declares "
                + "no manager, which is what makes the assertion above discriminate")
        void theGeneratedUserWouldOtherwiseExist() {
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                            SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context)
                                .as("if this were absent, the assertion that it stands down would prove "
                                        + "nothing at all")
                                .hasSingleBean(InMemoryUserDetailsManager.class);
                    });
        }

        @Test
        @DisplayName("is the only chain deciding requests, so the framework's default chain has stood "
                + "down rather than sitting alongside this one")
        void isTheOnlyChain() {
            plainTransport().run(context -> assertThat(context)
                    .hasSingleBean(SecurityFilterChain.class));
        }

        @Test
        @DisplayName("publishes one password hasher, so the module has a single hashing policy rather "
                + "than a per-component choice")
        void publishesOnePasswordHasher() {
            plainTransport().run(context -> assertThat(context).hasSingleBean(PasswordEncoder.class));
        }

        @Test
        @DisplayName("hashes a credential to something that is neither the credential nor a repeat of "
                + "an earlier hash of it")
        void hashesCredentials() {
            plainTransport().run(context -> {
                final PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
                final String candidate = "not-a-real-credential";
                final String first = encoder.encode(candidate);
                final String second = encoder.encode(candidate);

                assertThat(first).isNotEqualTo(candidate).isNotEqualTo(second);
                assertThat(encoder.matches(candidate, first)).isTrue();
                assertThat(encoder.matches("something-else", first)).isFalse();
            });
        }

        @Test
        @DisplayName("refuses any credential presented to the authentication manager, because this "
                + "module authenticates by token and has no credential-verifying provider")
        void refusesCredentialsAtTheManager() {
            plainTransport().run(context -> {
                final AuthenticationManager manager = context.getBean(AuthenticationManager.class);

                assertThat(manager).isNotNull();
                assertThat(org.assertj.core.api.Assertions
                        .catchThrowableOfType(org.springframework.security.core.AuthenticationException.class,
                                () -> manager.authenticate(
                                        org.springframework.security.authentication
                                                .UsernamePasswordAuthenticationToken
                                                .unauthenticated("someone", "something"))))
                        .as("accepting something here is how an accidental authentication happens")
                        .isNotNull();
            });
        }

        @Test
        @DisplayName("does not start when the signing secret is absent, so the chain and the token "
                + "settings fail together rather than the chain running unprotected")
        void doesNotStartWithoutASigningSecret() {
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                    .withUserConfiguration(SecurityConfig.class, JwtTokenProvider.class,
                            FixedClockConfig.class)
                    .withPropertyValues(
                            JwtProperties.PREFIX + ".issuer=" + ISSUER,
                            JwtProperties.PREFIX + ".expiration=PT30M",
                            "carddemo.security.require-https=false",
                            "carddemo.security.anonymous-metrics-scrape=false")
                    .run(context -> assertThat(context)
                            .as("a chain with no material to verify tokens with must not start")
                            .hasFailed());
        }
    }

    /**
     * Asserts the rules travel with the dispatching servlet.
     *
     * <p>Rules are written relative to the servlet, exactly as a request mapping is. If the servlet moves
     * beneath a prefix and the rules do not, every rule addresses a path no request carries: the
     * administrative gate stops matching the administrative routes while the closing catch-all keeps
     * refusing anonymous callers, so the only visible change is that an administrator's entitlement is no
     * longer required - a weakening with no symptom. These assertions read the outcome rather than the
     * setting, using the difference between a refusal and a missing handler as the evidence.</p>
     */
    @Nested
    @DisplayName("Rules relative to the dispatching servlet")
    class ServletRelativeRules {

        /**
         * Builds a runner whose dispatching servlet is mapped beneath a prefix.
         *
         * @param declaredPath value the profile would declare for the servlet path
         * @return the configured runner
         */
        private WebApplicationContextRunner beneath(final String declaredPath) {
            return runner(false, false)
                    .withPropertyValues("spring.mvc.servlet.path=" + declaredPath);
        }

        @ParameterizedTest(name = "servlet at {0}")
        @ValueSource(strings = {"/app", "/app/"})
        @DisplayName("stop permitting the unprefixed health path once the servlet moves, whether or not "
                + "the declared prefix carries a trailing separator")
        void stopPermittingTheUnprefixedPath(final String declaredPath) throws Exception {
            beneath(declaredPath).run(context -> clientFor(context)
                    .perform(get(MANAGEMENT_BASE + "/health"))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("the health rule now addresses the prefixed path, so this one is refused "
                                    + "by the closing catch-all")
                            .isEqualTo(401)));
        }

        @ParameterizedTest(name = "servlet at {0}")
        @ValueSource(strings = {"/app", "/app/"})
        @DisplayName("permit the prefixed health path, shown by its reaching a missing handler rather "
                + "than being refused")
        void permitThePrefixedPath(final String declaredPath) throws Exception {
            beneath(declaredPath).run(context -> clientFor(context)
                    .perform(get("/app" + MANAGEMENT_BASE + "/health"))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("404 means the chain allowed it through and no probe handler is mapped "
                                    + "there; 401 would mean the rule had not moved with the servlet")
                            .isEqualTo(404)));
        }

        @Test
        @DisplayName("gate the prefixed administrative routes, so the entitlement requirement moves with "
                + "the servlet instead of silently lapsing")
        void gateThePrefixedAdministrativeRoutes() throws Exception {
            beneath("/app").run(context -> clientFor(context)
                    .perform(get("/app" + ADMIN_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenFor(context, UserType.USER)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("a standard user must still be refused the administrative region")
                            .isEqualTo(403)));
        }

        @ParameterizedTest(name = "servlet declared as {0}")
        @ValueSource(strings = {"/", ""})
        @DisplayName("apply no prefix for the default root mapping, including when the value is declared "
                + "empty, which the matcher would otherwise reject outright")
        void applyNoPrefixForTheRootMapping(final String declaredPath) throws Exception {
            beneath(declaredPath).run(context -> clientFor(context)
                    .perform(get(MANAGEMENT_BASE + "/health"))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200)));
        }
    }

    @Nested
    @DisplayName("The published route constants")
    class RouteConstants {

        @Test
        @DisplayName("give the sign-on route and the administrative prefix exactly one home each, so a "
                + "controller and the rule exempting or gating it cannot drift apart")
        void areTheSingleHomeForEachAddress() {
            assertThat(SecurityConfig.SIGN_ON_PATH).startsWith("/").isNotBlank();
            assertThat(SecurityConfig.ADMIN_PATH_PREFIX).startsWith("/").isNotBlank();
        }

        @Test
        @DisplayName("keep the sign-on route outside the administrative prefix, since a route that "
                + "issues credentials must not require an administrator's")
        void keepSignOnOutsideTheAdminPrefix() {
            assertThat(SecurityConfig.SIGN_ON_PATH)
                    .doesNotStartWith(SecurityConfig.ADMIN_PATH_PREFIX);
        }

        @Test
        @DisplayName("keep both outside the management base path, so neither is affected by a change to "
                + "the management exposure list")
        void keepBothOutsideTheManagementBasePath() {
            assertThat(SecurityConfig.SIGN_ON_PATH).doesNotStartWith(MANAGEMENT_BASE);
            assertThat(SecurityConfig.ADMIN_PATH_PREFIX).doesNotStartWith(MANAGEMENT_BASE);
        }
    }
}
