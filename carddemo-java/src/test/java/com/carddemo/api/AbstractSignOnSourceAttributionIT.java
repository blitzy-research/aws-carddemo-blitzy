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
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.AuthenticationService;
import com.carddemo.service.CredentialDigestService;
import com.carddemo.service.MessageCatalogService;
import com.carddemo.service.NavigationService;
import com.carddemo.service.SignOnAttemptGovernor;
import com.carddemo.service.SignOnStateService;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.util.ApiRoutePaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.tracing.prometheus.PrometheusExemplarsAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
 * Shared machinery for the two specifications that establish <em>which value</em> the sign-on abuse
 * governor counts a caller by.
 *
 * <h2>The property under test, and why it needs a real container</h2>
 *
 * <p>The governor keeps two independent allowances. One is per identity, which catches a run of secrets
 * driven at a single identifier. The other is per <strong>caller address</strong>, and it is the only one
 * that can catch an enumeration sweep - a sweep never repeats an identifier, so the identity allowance
 * never accumulates. That makes the caller address a security-relevant input, and an allowance keyed on a
 * value the caller chooses is not an allowance at all.
 *
 * <p>The address the boundary attributes is the servlet request's own, but <em>what the servlet request
 * reports</em> is decided by the deployment's forwarded-header setting, several layers below any code this
 * module writes. A framework-level rewriting filter replaces it from {@code X-Forwarded-For} or
 * {@code Forwarded} <em>unconditionally</em>; the container's trusted-proxy-aware valve replaces it only
 * when the immediate peer is one the deployment named. The difference is invisible in the controller, in a
 * unit test, and in a mock servlet environment - all three see whatever they are handed. It is visible only
 * over a real connection to a real container, which is what these two specifications open.
 *
 * <h2>How the two subclasses divide the property</h2>
 *
 * <p>Both reach the boundary over the loopback interface, so the immediate peer is always the same address.
 * What differs is the allow-list each one configures, and therefore whether that peer is entitled to speak
 * for a client:
 * <ul>
 *   <li>{@code SignOnSourceAttributionUntrustedProxyIT} names an allow-list the peer does <strong>not</strong>
 *       match. Forwarded addresses must then be ignored, so rotating one cannot buy a fresh allowance.</li>
 *   <li>{@code SignOnSourceAttributionTrustedProxyIT} names an allow-list the peer <strong>does</strong>
 *       match. Forwarded addresses must then be honoured, so two genuinely different clients behind that
 *       proxy get two allowances - the behaviour a deployment behind a balancer depends on.</li>
 * </ul>
 *
 * <p>Neither subclass reads a counter or a meter. Each drives the published screen and reads the message it
 * answers with, because the governor's whole observable effect on a caller is that a further attempt is
 * refused <em>without</em> a credential read: an unknown identifier answers the not-found text while the
 * allowance holds and the cannot-verify text once it is spent. Using distinct identifiers throughout keeps
 * every identity allowance at one failure, so the source allowance is the only thing that can change the
 * answer.
 *
 * <p>The clock is the pinned instant, so a window neither expires nor depends on when the suite runs, and
 * an engaged refusal stays engaged for the whole method.
 *
 * <p>No credential is submitted: every identifier here is one no record carries, which is the not-found
 * arm, and the value sent in the secret field is a fixed non-credential literal. The legacy cleartext value
 * appears nowhere in these files.
 *
 * <p>Provenance: the governor has no legacy antecedent - the estate's screen was reached from a terminal
 * network and counted nothing - and it is a documented addition rather than a translated behaviour. Legacy
 * estate read as read-only reference at checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19; no legacy source text is reproduced.
 *
 * @since 1.0.0
 */
abstract class AbstractSignOnSourceAttributionIT extends AbstractPostgresIT {

    /** How many failures inside the window exhaust one subject's allowance in these specifications. */
    protected static final int ALLOWANCE = 3;

    /** Property naming that allowance, so the configured value and the cases cannot disagree. */
    protected static final String ALLOWANCE_PROPERTY =
            SignOnAttemptGovernor.MAX_FAILURES_PROPERTY + "=" + ALLOWANCE;

    /** The header a proxy names its client in, and the one an untrusted caller would try to choose by. */
    protected static final String FORWARDED_FOR = "X-Forwarded-For";

    /** The keyed read reported no such record: the answer while the allowance holds. */
    protected static final String USER_NOT_FOUND_MESSAGE = "User not found. Try again ...";

    /** The answer once an allowance is spent, given without reading or verifying any credential. */
    protected static final String UNABLE_TO_VERIFY_MESSAGE = "Unable to verify the User ...";

    /** Attention key every submission here presses; the program dispatches on it before anything else. */
    private static final String ENTER_KEY = "ENTER";

    /**
     * The value sent in the secret field.
     *
     * <p>It is not a credential of anything and is never compared with one: every identifier below is
     * absent from the credential store, so the not-found arm is reached before any comparison. It exists
     * because the field is part of the published request, and it is eight characters because the field the
     * sign-on map declares is eight wide and the boundary validator holds submissions to that width.
     */
    private static final String NOT_A_CREDENTIAL = "notacred";

    /** Reads served bodies. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The port the boundary opened. */
    @LocalServerPort
    private int port;

    /** The client every case drives the boundary through. */
    @Autowired
    private TestRestTemplate http;

    /** The governor under test, read only to confirm the configured allowance these cases assume. */
    @Autowired
    private SignOnAttemptGovernor governor;

    /** Creates the specification. */
    protected AbstractSignOnSourceAttributionIT() {
        super();
    }

    /**
     * Confirms the allowance the cases are written against is the one in force.
     *
     * <p>Asserted rather than assumed, because every case below counts attempts against it: an allowance
     * the property did not actually apply would make a passing case meaningless.
     */
    protected final void assertConfiguredAllowance() {
        assertThat(this.governor.isEnabled())
                .as("the governor must be enabled, or nothing here is under test")
                .isTrue();
        assertThat(this.governor.getMaxFailures())
                .as("the cases count attempts against this allowance")
                .isEqualTo(ALLOWANCE);
    }

    /**
     * Submits one sign-on for an identifier no record carries, optionally naming a forwarded client.
     *
     * @param  userId       the identifier to submit; must be one the credential store does not hold
     * @param  forwardedFor the address to claim in the forwarded header, or {@code null} to send none
     * @return the screen message the turn answered with, never {@code null}
     * @throws IOException if the reply cannot be parsed
     */
    protected final String signOnMessage(final String userId, final String forwardedFor)
            throws IOException {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (forwardedFor != null) {
            headers.add(FORWARDED_FOR, forwardedFor);
        }
        final String body = "{\"userId\":\"" + userId + "\",\"password\":\"" + NOT_A_CREDENTIAL
                + "\",\"keyAction\":\"" + ENTER_KEY + "\"}";

        final ResponseEntity<String> answer = this.http.exchange(
                "http://localhost:" + this.port + ApiRoutePaths.SIGN_ON_PATH, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(answer.getStatusCode())
                .as("every outcome the legacy screen could compose is answered 200, a refusal included:"
                        + " the outcome is read from the body and never from the status")
                .isEqualTo(HttpStatus.OK);
        assertThat(answer.getHeaders().get(HttpHeaders.AUTHORIZATION))
                .as("a refused or rejected turn earns no session")
                .isNull();
        return JSON.readTree(answer.getBody()).path("message").asText();
    }

    /**
     * The sign-on surface under test: the shipped controller, service, governor and filter chain.
     *
     * <p>Assembled explicitly rather than by scanning. Nothing about the governor is stubbed - the subject
     * of both specifications is which value reaches it, so replacing it would remove the subject.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import({AuthController.class, ModuleErrorController.class, SignOnContractAdapter.class,
        GlobalExceptionHandler.class, JsonRefusalBodyRenderer.class, AuthenticationService.class,
        SignOnAttemptGovernor.class, NavigationService.class, MessageCatalogService.class,
        CredentialDigestService.class, SignOnStateService.class, SecurityConfig.class,
        JwtTokenProvider.class, WebMvcConfig.class})
    @EnableConfigurationProperties(JwtProperties.class)
    @EnableJpaRepositories(basePackageClasses = UserSecurityRepository.class)
    @EntityScan(basePackageClasses = UserSecurity.class)
    static class SourceAttributionContext {

        /** Creates the configuration. */
        SourceAttributionContext() {
            // Intentionally empty: this slice contributes beans, not state.
        }

        /**
         * The pinned clock the governor measures every window and refusal against.
         *
         * @return the shared fixed clock
         */
        @Bean
        Clock fixedClock() {
            return FIXED_CLOCK;
        }
    }
}
