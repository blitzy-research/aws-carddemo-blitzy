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
import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.domain.enums.UserType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The module's HTTP request-authorization boundary: one explicit filter chain, least privilege by
 * default, and every anonymous surface named individually.
 *
 * <p>The legacy estate decided reachability in the transaction manager's resource definitions: a
 * terminal user reached a transaction because a definition bound that transaction to a program, and the
 * sign-on program routed an administrator to the administrative menu and everyone else to the main menu
 * on the strength of a one-character user-type field. There is no equivalent outside this class once the
 * estate is served over HTTP, so this class is the whole of the route-to-role table and the only place
 * that decides what may be reached without a credential.
 *
 * <p><strong>Least privilege is the default, and it is structural rather than stated.</strong> The last
 * rule is {@code anyRequest().authenticated()}, so a route that no rule below mentions requires a
 * credential. A new endpoint is therefore protected the moment it exists and becomes reachable only when
 * someone deliberately adds a rule for it. The opposite arrangement - a permissive default with denials
 * listed - fails silently every time a route is added and forgotten.
 *
 * <p><strong>At most four kinds of surface are anonymous, two of them unconditionally and two only where
 * a profile opens them, and each is anonymous for a reason that can be checked against a sibling
 * file.</strong>
 * <ul>
 *   <li>The health probe, because {@code carddemo-java/Dockerfile} names it as the image
 *       {@code HEALTHCHECK} and Compose services wait on it. A container that cannot answer its own
 *       health check never becomes ready, so requiring a credential here would break orchestration
 *       rather than protect anything: the aggregate body is a status word, and the shared baseline
 *       closes component detail so that it stays one.</li>
 *   <li>The metrics scrape endpoint, <strong>and only where the running profile opens it.</strong> The
 *       permit is conditional on {@code carddemo.security.anonymous-metrics-scrape}, which the shared
 *       baseline and production leave closed and only the local and test overlays open - the same two
 *       overlays that relax transport, and for the same reason: their collector is
 *       {@code config/prometheus/prometheus.yml} running in the Compose stack on the same machine.
 *       Production leaves it closed because the exposition is not a status word. It carries per-endpoint
 *       request counts and latency distributions, per-step batch record counts, data-source pool
 *       saturation and JVM internals, which between them let an unauthenticated network client profile
 *       transaction volume, infer business activity and time an attack against a deployment it has no
 *       credential for. The endpoint remains PUBLISHED in production - a collector that presents a
 *       bearer token still collects, so the performance gate is unaffected - and what closes is
 *       collection by a client that presents nothing.</li>
 *   <li>The interface description, <strong>and only where the running profile publishes it.</strong> The
 *       permit is conditional on the same switch that decides whether the document is served at all, so
 *       a profile that does not publish it does not have an anonymous rule for it either. The shared
 *       baseline and production leave it unpublished; the local overlay publishes it.</li>
 *   <li>The sign-on route, because it is the route that issues tokens. A token-issuing route that
 *       required a token could never be reached, and no other route could ever be reached either. It is
 *       named by {@link #SIGN_ON_PATH} so that the exemption is a single reviewable rule rather than a
 *       pattern that happens to match.</li>
 * </ul>
 *
 * <p><strong>Every other management endpoint requires a credential, including the ones only the local
 * overlay publishes.</strong> That overlay adds the environment, configuration-property, bean, migration,
 * request-mapping and logger endpoints, which between them describe the configuration, the wiring and
 * the schema history of the running system. The rule authenticating the management base path is placed
 * after the two permits and before the catch-all, so widening the published set in a profile can never
 * widen the anonymous set: a newly published endpoint is authenticated by that rule the moment it
 * appears, without this class being edited. There is no blanket permit for the management base path
 * anywhere.
 *
 * <p><strong>The framework's generated-user path is removed rather than left dormant.</strong> Declaring
 * an {@link AuthenticationManager} bean is what withdraws the auto-configured in-memory user, and with
 * it the generated password that would otherwise be written to the log at start-up. Form login and HTTP
 * basic are both disabled explicitly, so there is no interactive login page, no browser credential
 * prompt, and no second way to become authenticated besides presenting a token.
 *
 * <p><strong>A refusal reads identically whether the filter chain or the dispatch decided it.</strong>
 * An authorization failure inside the dispatch is answered by
 * {@link GlobalExceptionHandler}; one decided here happens before any dispatch, so no exception handler
 * is consulted and the response would otherwise be the container's default error page. The entry point
 * and access-denied handler below therefore render the module's own
 * {@link ErrorResponse} and reuse
 * {@link GlobalExceptionHandler#AUTHENTICATION_REQUIRED_MESSAGE} and
 * {@link GlobalExceptionHandler#ACCESS_DENIED_MESSAGE} rather than literals of their own, so the two
 * boundaries cannot drift into disagreeing about the same condition.
 *
 * <p><strong>Cross-site request forgery protection is disabled, and that is a consequence of
 * statelessness rather than a relaxation of it.</strong> The attack it defends against requires the
 * browser to attach a credential to a request the user did not intend, which requires the credential to
 * be ambient - a session cookie. This module establishes no session, issues no cookie, and authenticates
 * only a token that a caller must place in a request header deliberately. A cross-site request carries no
 * such header, so there is no ambient authority for it to borrow. Session creation is set to stateless so
 * that this stays true rather than being true only by accident.
 *
 * <p><strong>Transport security is a rule here, not a comment.</strong> When
 * {@code carddemo.security.require-https} is set, every request must arrive over a secure channel and an
 * insecure one is redirected rather than served. The shared baseline and production set it; the local and
 * test overlays clear it, because both address containers over the loopback interface of one machine.
 * That setting had no consumer before this class existed, which is precisely why it is bound here.
 *
 * <p>No latency, time-out, capacity or pool figure appears anywhere in this class. The one numeric
 * parameter is the password-hashing cost, which is a resistance factor rather than a performance target.
 *
 * <p>The rule ordering, the deliberate choice of path matcher, the withdrawal of the generated user, the
 * cross-site posture and the transport rule are each reasoned once in {@code docs/decision-log.md}
 * DL-096 and DL-098, including the guard that was removed after being proved unreachable. The signing
 * primitive this chain verifies with is reasoned in DL-097, and the agreement between the routes
 * permitted here and the security scheme the published interface description advertises is DL-099.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    /**
     * The one route reachable without a credential because it is the route that issues them.
     *
     * <p>It is the REST form of the legacy sign-on transaction, the entry point of both the
     * administrative and the ordinary navigation flow. Published as a constant so that the controller
     * which serves it and the rule which exempts it name one authority: a controller mapped to some
     * other path would be authenticated by the catch-all rule and would fail closed, which is the safe
     * direction for that mistake to fail in.</p>
     */
    public static final String SIGN_ON_PATH = "/api/auth/signon";

    /**
     * Path prefix beneath which every administrator-only route lives.
     *
     * <p>The legacy resource definitions bind eighteen transactions to programs, and five of those are
     * administrative: the administrative menu, and the four user-maintenance transactions that list, add,
     * update and delete a sign-on record. Rather than restate five route patterns that no controller has
     * yet claimed, this module gives the administrative surface one prefix and gates the prefix, so a
     * route added beneath it is administrator-only from the moment it exists. A route placed outside it
     * is still authenticated by the catch-all rule, so the failure mode of forgetting the prefix is a
     * route that admits any signed-on user rather than one that admits anybody - and the prefix is
     * published here as the single authority a controller binds against.</p>
     */
    public static final String ADMIN_PATH_PREFIX = "/api/admin";

    /**
     * Cost factor for password hashing.
     *
     * <p>The legacy sign-on record held an eight-character cleartext password that the sign-on program
     * compared directly. This module stores a hash and compares through the encoder below, which is the
     * one documented place where the no-hardcoded-credentials requirement outranks byte-for-byte
     * faithfulness to the estate. The factor is a deliberate resistance parameter - each increment
     * doubles the work of both a legitimate verification and an attacker's guess - and it is above the
     * library's default because the value it protects is a credential. It is not a latency or throughput
     * figure and asserts no service level.</p>
     */
    private static final int PASSWORD_HASHING_STRENGTH = 12;

    /** Credential scheme name a challenge names, and the prefix a presented credential carries. */
    private static final String BEARER_SCHEME = "Bearer";

    /** Prefix a bearer credential carries in the authorization header, including its trailing space. */
    private static final String BEARER_PREFIX = BEARER_SCHEME + " ";

    /** Logger. Never receives a token, a credential, or a rejected value. */
    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

    /** Verifies presented tokens and reads the user type they carry. */
    private final JwtTokenProvider tokenProvider;

    /** Renders a refusal body in the module's own error shape. */
    private final ObjectMapper objectMapper;

    /** Whether every request must arrive over a secure channel. */
    private final boolean requireHttps;

    /** Whether the running profile publishes the interface description. */
    private final boolean apiDocsPublished;

    /** Whether the running profile lets a collector scrape the metrics endpoint without a credential. */
    private final boolean anonymousMetricsScrape;

    /** Address the interface description is published at, when it is published. */
    private final String apiDocsPath;

    /** Base path every management endpoint is published beneath. */
    private final String managementBasePath;

    /** Path the request-dispatching servlet is mapped at, which every other path here is relative to. */
    private final String servletPath;

    /**
     * Binds the settings that decide reachability, so that each has exactly one home.
     *
     * <p>Every path below is bound rather than written twice. The management base path and the
     * interface-description address are declared in configuration and resolved literally by sibling
     * files, so restating either here would create a second home that could disagree with the first;
     * binding them means a path that moves in configuration moves in the authorization rules with it.</p>
     *
     * @param tokenProvider      verifier for presented bearer tokens
     * @param objectMapper       renderer for the refusal body, so a filter-boundary refusal is shaped
     *                           exactly like a dispatch-boundary one
     * @param requireHttps       whether to require a secure channel; set by the shared baseline and
     *                           production, cleared by the local and test overlays
     * @param apiDocsPublished   whether the running profile serves the interface description, which is
     *                           the same switch that decides whether it may be fetched anonymously
     * @param anonymousMetricsScrape whether the running profile lets the metrics endpoint be scraped
     *                           without a credential; bound with NO default so that a profile which
     *                           says nothing cannot silently open it, and set only by the two overlays
     *                           whose collector runs on the same machine
     * @param apiDocsPath        address the interface description is served from
     * @param managementBasePath base path the management endpoints are served beneath
     * @param servletPath        path the dispatching servlet is mapped at; every rule below is expressed
     *                           relative to it, exactly as a request mapping is
     */
    public SecurityConfig(
            final JwtTokenProvider tokenProvider,
            final ObjectMapper objectMapper,
            @Value("${carddemo.security.require-https}") final boolean requireHttps,
            @Value("${springdoc.api-docs.enabled:false}") final boolean apiDocsPublished,
            @Value("${carddemo.security.anonymous-metrics-scrape}")
                    final boolean anonymousMetricsScrape,
            @Value("${springdoc.api-docs.path:/v3/api-docs}") final String apiDocsPath,
            @Value("${management.endpoints.web.base-path:/actuator}") final String managementBasePath,
            @Value("${spring.mvc.servlet.path:/}") final String servletPath) {
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.requireHttps = requireHttps;
        this.apiDocsPublished = apiDocsPublished;
        this.anonymousMetricsScrape = anonymousMetricsScrape;
        this.apiDocsPath = Objects.requireNonNull(apiDocsPath, "apiDocsPath must not be null");
        this.managementBasePath =
                Objects.requireNonNull(managementBasePath, "managementBasePath must not be null");
        this.servletPath = Objects.requireNonNull(servletPath, "servletPath must not be null");
    }

    /**
     * Builds one authorization rule's request matcher.
     *
     * <p>The matcher is chosen deliberately rather than defaulted. Path patterns are compared with the
     * same parser this framework generation uses to map a request to a handler, which is what makes a
     * rule and a request mapping unable to disagree: a request the administrative rule does not match is
     * a request the administrative handler does not receive. The alternative available here resolves each
     * rule through the dispatcher's own handler registry, which reaches the same conclusion but only in a
     * context where that registry has been published - so the boundary's behaviour would depend on
     * surrounding configuration, and could not be exercised on its own. Deciding it here keeps the rules a
     * property of this chain, which is why they can be asserted by issuing requests and reading answers.</p>
     *
     * <p>Patterns are expressed relative to the dispatching servlet, as a request mapping is. When that
     * servlet is mapped beneath a prefix, the prefix is applied to every rule rather than to some of them;
     * the default mapping is the root, for which no prefix applies. Without this, moving the servlet would
     * leave the rules addressing paths no request ever carries, and the administrative gate would stop
     * matching the administrative routes while the closing catch-all quietly kept refusing anonymous
     * callers - a weakening with no visible symptom.</p>
     *
     * @param pattern path pattern, relative to the dispatching servlet
     * @return a matcher for that pattern
     */
    private PathPatternRequestMatcher matcher(final String pattern) {
        final String prefix = this.servletPath.strip();
        if (prefix.isEmpty() || "/".equals(prefix)) {
            return PathPatternRequestMatcher.withDefaults().matcher(pattern);
        }
        final String trimmed = prefix.endsWith("/")
                ? prefix.substring(0, prefix.length() - 1)
                : prefix;
        return PathPatternRequestMatcher.withDefaults().basePath(trimmed).matcher(pattern);
    }

    /**
     * Builds the single filter chain that decides every request.
     *
     * <p>Rule order is the contract. The permits come first and name individual surfaces; the rule
     * authenticating the management base path comes next, so it catches every management endpoint the two
     * permits did not name, including any a profile publishes later; the administrative prefix follows;
     * and the catch-all closes the chain. Reordering these changes behaviour, so the order is asserted by
     * tests that exercise real responses rather than inspect the configuration.</p>
     *
     * @param http the chain builder supplied by the framework
     * @return the configured chain
     * @throws Exception if the framework rejects the configuration
     */
    @Bean
    public SecurityFilterChain securityFilterChain(final HttpSecurity http) throws Exception {
        final String healthPath = this.managementBasePath + "/health";
        final String healthSubPaths = healthPath + "/**";
        final String prometheusPath = this.managementBasePath + "/prometheus";
        final String managementSubPaths = this.managementBasePath + "/**";

        http
                // A cross-site request cannot borrow authority that is never ambient: no session, no
                // cookie, and a credential the caller must set as a header deliberately.
                .csrf(AbstractHttpConfigurer::disable)
                // No interactive login page and no browser credential prompt. Presenting a token is the
                // only way to become authenticated.
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                // Nothing to end: there is no session to invalidate and no cookie to clear, so a logout
                // route would answer without doing anything and would invite the belief that it did.
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers(matcher(healthPath), matcher(healthSubPaths)).permitAll();
                    if (this.anonymousMetricsScrape) {
                        // Only where the collector runs on the same machine as the application. When
                        // this is not set - the shared baseline and production - no permit is added and
                        // the scrape path falls through to the management rule below, which
                        // authenticates it. The endpoint stays PUBLISHED either way, so a collector
                        // that presents a bearer token still collects; what changes is whether one that
                        // presents nothing does.
                        requests.requestMatchers(matcher(prometheusPath)).permitAll();
                    }
                    if (this.apiDocsPublished) {
                        requests.requestMatchers(matcher(this.apiDocsPath),
                                matcher(this.apiDocsPath + "/**")).permitAll();
                    }
                    requests.requestMatchers(matcher(SIGN_ON_PATH)).permitAll();
                    // Everything else under the management base path, named or not, needs a credential.
                    requests.requestMatchers(matcher(managementSubPaths)).authenticated();
                    requests.requestMatchers(matcher(ADMIN_PATH_PREFIX + "/**"))
                            .hasAuthority(JwtTokenProvider.ADMIN_AUTHORITY);
                    requests.anyRequest().authenticated();
                })
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthorizedEntryPoint())
                        .accessDeniedHandler(forbiddenHandler()))
                .addFilterBefore(
                        new BearerTokenAuthenticationFilter(this.tokenProvider),
                        UsernamePasswordAuthenticationFilter.class);

        if (this.requireHttps) {
            // Every insecure request is redirected rather than served. This is the supported form of the
            // rule: the older channel-security configurer expresses the same thing and is deprecated in
            // this framework generation, and a deprecated call is a build failure here rather than a
            // warning to be carried. Left at its default matcher deliberately, which is every request -
            // narrowing it would exempt some route from transport security without saying which.
            http.redirectToHttps(Customizer.withDefaults());
        }

        LOG.info("HTTP security configured: anonymous surfaces are the health probe{}{}, and the sign-on "
                        + "route; every other route requires a bearer token, the metrics scrape endpoint "
                        + "included unless named here; secure channel required: {}",
                this.anonymousMetricsScrape ? ", the metrics scrape endpoint" : "",
                this.apiDocsPublished ? ", the interface description" : "",
                this.requireHttps);
        return http.build();
    }

    /**
     * Withdraws the framework's auto-configured in-memory user and the password it would log.
     *
     * <p>The auto-configuration that creates that user stands down as soon as an authentication manager is
     * present, which is the whole reason this bean exists. What it does when invoked is refuse, and that
     * refusal is accurate rather than a placeholder: this module has no username-and-password
     * authentication path in the delivered surface. Authentication happens by presenting a token, which
     * the filter below establishes directly without consulting a manager, so nothing in the configured
     * chain can reach this bean. Should some future path present a credential to it, refusing is the
     * correct answer until a credential-verifying provider is registered - the alternative, a manager that
     * accepted something, is how an accidental authentication happens.</p>
     *
     * @return an authentication manager that supports no authentication type
     */
    @Bean
    public AuthenticationManager authenticationManager() {
        return authentication -> {
            throw new ProviderNotFoundException(
                    "No credential-verifying authentication provider is registered; this module "
                            + "authenticates by bearer token only");
        };
    }

    /**
     * The password hasher every stored credential is written and compared through.
     *
     * <p>Published here, in the composition root, so that the module has one hashing policy rather than a
     * per-component choice. The legacy cleartext comparison has no counterpart: nothing in this module
     * stores or compares a cleartext credential, and no value from the legacy sign-on records is restated
     * anywhere in it.</p>
     *
     * @return a hasher at {@link #PASSWORD_HASHING_STRENGTH}
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(PASSWORD_HASHING_STRENGTH);
    }

    /**
     * Answers a request that established no credential.
     *
     * <p>It renders the module's own error shape, names the credential scheme it expects so that the
     * response is actionable without describing the rule that refused, and reuses the shared message
     * constant so that this answer and the dispatch-boundary answer to the same condition are one
     * string.</p>
     *
     * @return the entry point installed on the chain
     */
    private AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, authenticationException) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, BEARER_SCHEME);
            writeRefusal(response, HttpStatus.UNAUTHORIZED,
                    GlobalExceptionHandler.AUTHENTICATION_REQUIRED_MESSAGE);
        };
    }

    /**
     * Answers a request whose established credential does not entitle it.
     *
     * <p>The distinction from the entry point is the one the estate drew: a principal exists and is not
     * entitled, which is the sign-on program routing a non-administrator away from the administrative
     * menu rather than refusing the sign-on. No challenge header is sent, because presenting a different
     * credential is not what the caller should do.</p>
     *
     * @return the access-denied handler installed on the chain
     */
    private AccessDeniedHandler forbiddenHandler() {
        return (request, response, accessDeniedException) ->
                writeRefusal(response, HttpStatus.FORBIDDEN,
                        GlobalExceptionHandler.ACCESS_DENIED_MESSAGE);
    }

    /**
     * Writes a refusal in the module's error shape.
     *
     * <p>The response is guaranteed to be uncommitted here, and that guarantee is the framework's rather
     * than this method's. Both handlers above are invoked only by the chain's exception-translation filter,
     * which checks for a committed response first and raises instead of calling them - so a refusal is
     * never appended to an answer already on the wire. A guard here would restate that check in a place it
     * can never be reached from, which would read as though the case were possible and could never be
     * shown to work; the boundary test asserts the framework's behaviour directly instead.</p>
     *
     * @param response the response to write
     * @param status   the refusal status
     * @param message  the summary, which names neither the rule that refused nor the entitlement that
     *                 would have satisfied it
     * @throws IOException if the body cannot be written
     */
    private void writeRefusal(final HttpServletResponse response, final HttpStatus status,
            final String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        this.objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(message));
    }

    /**
     * Establishes an identity from a bearer credential, and never refuses on its own.
     *
     * <p>It runs once per request, ahead of the position an interactive login filter would occupy. When a
     * bearer credential is present and verifies, the identity it names is established for the remainder of
     * the request; in every other case - absent, malformed, wrongly signed, expired, issued elsewhere, or
     * carrying a user type this estate does not define - the request simply continues unauthenticated and
     * the authorization rules decide it. That division is deliberate: this filter's job is to establish
     * what it can prove, and refusing is the chain's job, so there is exactly one place a refusal is
     * shaped.</p>
     *
     * <p>A fresh security context is created rather than the current one mutated, so nothing is carried
     * between requests by a context that outlived one. Nothing here logs the credential, any part of it,
     * or the reason it failed to verify.</p>
     */
    private static final class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

        /** Verifier for the presented credential. */
        private final JwtTokenProvider tokenProvider;

        /**
         * Creates the filter.
         *
         * @param tokenProvider verifier for presented bearer tokens
         */
        private BearerTokenAuthenticationFilter(final JwtTokenProvider tokenProvider) {
            this.tokenProvider = tokenProvider;
        }

        /**
         * Establishes an identity when the request carries a verifiable bearer credential.
         *
         * @param request     the request
         * @param response    the response
         * @param filterChain the remainder of the chain, always continued
         * @throws ServletException if the remainder of the chain fails
         * @throws IOException      if the remainder of the chain fails
         */
        @Override
        protected void doFilterInternal(final HttpServletRequest request,
                final HttpServletResponse response, final FilterChain filterChain)
                throws ServletException, IOException {
            final String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null
                    && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
                final String presented = header.substring(BEARER_PREFIX.length()).trim();
                this.tokenProvider.verify(presented).ifPresent(verified -> {
                    final UserType userType = this.tokenProvider.userTypeOf(verified).orElse(null);
                    if (userType != null) {
                        establish(verified.getSubject(), userType);
                    }
                });
            }
            filterChain.doFilter(request, response);
        }

        /**
         * Establishes the identity a verified credential names, in a context of its own.
         *
         * <p>The credential itself is deliberately not retained as the authentication's credentials: a
         * token held in the security context is a credential that anything rendering that context could
         * publish, and nothing downstream needs it.</p>
         *
         * @param subject  identifier the credential names
         * @param userType user type the credential carries, which decides the granted authority
         */
        private static void establish(final String subject, final UserType userType) {
            final PreAuthenticatedAuthenticationToken authentication =
                    new PreAuthenticatedAuthenticationToken(subject, null,
                            List.of(new SimpleGrantedAuthority(JwtTokenProvider.authorityOf(userType))));
            final SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        }
    }
}
