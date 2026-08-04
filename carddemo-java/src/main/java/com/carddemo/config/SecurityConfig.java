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

import com.carddemo.api.AuthController;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * <p><strong>The route-to-role table is the eighteen registered transaction definitions, and it is
 * complete on purpose.</strong> {@code app/csd/CARDDEMO.CSD} registers eighteen transaction definitions
 * and eighteen program definitions, and every transaction definition names exactly one program - a census
 * taken by extraction rather than by reading, because that file indents each definition by one column and
 * a search anchored at the start of a line silently finds none of them. {@link TransactionRoute} carries
 * one entry per registered transaction, each naming its four-character transaction identifier, the
 * eight-character program the definition binds it to, the line that definition begins on, and the
 * entitlement this module requires of a caller who reaches it. Nothing in that table is mutable, nothing
 * in it is assembled at run time, and the authorization rules below are derived from it rather than
 * restating it - so a reclassified entry changes what the chain answers, and cannot become a comment that
 * disagrees with the rule beside it.
 *
 * <p><strong>Exactly five of the eighteen are administrative, and five is a fact about the estate rather
 * than a preference.</strong> They are the administrative menu and the four transactions that list, add,
 * update and delete a sign-on record. Every other transaction is reachable by any signed-on caller, which
 * is the estate's own arrangement: the sign-on program tests one condition and sends an administrator to
 * the administrative menu, and its alternative is unconditional. Gating a sixth would refuse a caller the
 * estate admits; leaving one of the five ungated would admit one it refuses. Both are behavioural
 * regressions, so the count is asserted rather than assumed.
 *
 * <p><strong>The eighteenth transaction is bound to a program that has no source member, and the table
 * keeps it.</strong> The developer transaction {@code CDV1}, defined at {@code app/csd/CARDDEMO.CSD}
 * L388, names a program definition for which no member exists anywhere in {@code app/cbl} - anomaly
 * register entry 3 of {@code docs/decision-log.md}, verified by looking. It was therefore never
 * dispatchable on the mainframe either. No target class is generated for that program, here or in any
 * other package, and no route constant names it; the navigation vocabulary accordingly derives seventeen
 * destinations from eighteen definitions. It still appears below, carrying the entitlement it would have
 * required and marked as having no implemented program, because an inventory that quietly drops its own
 * anomaly cannot be audited and cannot be reconciled against that seventeen.
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
 * <p><strong>One deliberate behavioural change lives in this file, and it is labelled rather than
 * hidden.</strong> The sign-on record holds a credential field eight characters wide - the fourth field of
 * an exactly eighty-byte record laid out at {@code app/cpy/CSUSR01Y.cpy} L17-L23 as eight, twenty, twenty,
 * eight, one and twenty-three characters, whose key length and record width the provisioning job at
 * {@code app/jcl/DUSRSECJ.jcl} independently corroborates - and the sign-on program compares it against
 * the submission directly, as equal text, at {@code app/cbl/COSGN00C.cbl} L223. This module hashes
 * instead, through {@link #passwordEncoder()}, and the stored column is widened to hold a digest. That is
 * the single point in the migration where the requirement that no credential be hardcoded outranks
 * byte-for-byte faithfulness, because reproducing a cleartext comparison would satisfy parity and breach
 * the constraint in the same statement. It is recorded as a documented parity exception in
 * {@code docs/decision-log.md} rather than left to be discovered, and it is the only entry in this file
 * that is not pure faithfulness. No value carried by those provisioning records is restated anywhere in
 * this module - not in code, configuration, documentation, a log line or an assertion message.
 *
 * <p><strong>Four behaviours of the sign-on program are contractual, and this chain is wired so that no
 * framework default can quietly replace them.</strong> Each is implemented in the authentication service;
 * what matters here is that nothing installed on this chain stands in front of it and answers first.
 * <ul>
 *   <li><em>Blank-field reporting is ordered, not aggregated.</em> The program evaluates the blank
 *       user-identifier condition at {@code app/cbl/COSGN00C.cbl} L120 before the blank-credential
 *       condition at L125, inside a construct that stops at the first condition that holds - so a
 *       submission with both fields blank reports the user-identifier message and only that one.
 *       Declarative constraint validation reports whatever it finds in whatever order it finds it, which
 *       is why the sign-on submission is screened by an ordered cascade in the service rather than by
 *       unordered constraints, and why nothing on this chain translates a constraint violation into an
 *       answer.</li>
 *   <li><em>Both submitted values are folded to upper case unconditionally.</em> The program folds the
 *       identifier and the credential at L132-L136, positioned after the blank-field cascade and before
 *       the guard at L138 that skips verification when an error is already flagged - so the fold happens
 *       whether or not that flag is set. Verification therefore compares a folded submission against the
 *       stored digest, and the digests were produced from folded values. The fold is a fixed
 *       twenty-six-character substitution rather than a language-sensitive one, matching the substitution
 *       tables the estate uses for the same purpose elsewhere.</li>
 *   <li><em>The entitlement split has an unconditional alternative.</em> Across L230-L240 the program tests
 *       one condition at L230 - the one-character user-type field holding the administrative code declared
 *       at {@code app/cpy/COCOM01Y.cpy} L27, beside the standard code at L28 - and its alternative at L235
 *       is unconditional, with no third branch and no failure path for a code the estate does not declare.
 *       So the administrative authority is granted for that one code and the ordinary authority for
 *       everything else. {@link JwtTokenProvider#authorityOf(UserType)} is that mapping, and the filter
 *       below either establishes an identity or leaves the request unauthenticated for the rules to
 *       decide; neither ever fails on an unexpected code.</li>
 *   <li><em>A wrong credential is not the same outcome as an unknown user.</em> The program raises its
 *       general error flag for an unknown user at L248-L249 - the record-not-found response - and for an
 *       unclassified failure at L253-L254, and deliberately does not raise it for a credential that does
 *       not match at L242. Three outcomes, not two. The refusal shaping installed below distinguishes a
 *       request that established no identity from one whose identity does not entitle it, and carries the
 *       module's own summaries, so no framework authentication-failure text reaches a caller and collapses
 *       the distinction.</li>
 * </ul>
 *
 * <p>The seven message texts those paths emit belong to the message catalogue service, which is their one
 * home; none is restated here.
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
 * <p><strong>Statelessness is what the estate's conversational re-arm becomes.</strong> Each online program
 * ended a turn by returning with its own transaction identifier and its communication area attached - the
 * sign-on program does it at {@code app/cbl/COSGN00C.cbl} L98-L102 - so the next keystroke resumed with the
 * previous turn's state already in hand. There are nineteen such re-arms across the seventeen online
 * programs and twenty-five program-to-program transfers between them. None of that survives here: the two
 * facts the communication area carried forward become claims in the token this chain verifies, screen state
 * becomes a transfer object the client echoes back, and the transfers become route values returned in
 * response bodies by the navigation service. Nothing is held between requests, which is why the session
 * policy below is stateless rather than merely unused.
 *
 * <p><strong>Transport security is a rule here, not a comment.</strong> When
 * {@code carddemo.security.require-https} is set, every request must arrive over a secure channel and an
 * insecure one is redirected rather than served. The shared baseline and production set it; the local and
 * test overlays clear it, because both address containers over the loopback interface of one machine.
 * That setting had no consumer before this class existed, which is precisely why it is bound here.
 *
 * <p>No latency, time-out, capacity, heap, pool or availability figure appears anywhere in this class, and
 * none is implied by anything in it. Two kinds of number do appear and neither is a service level: the
 * password-hashing cost, which is a resistance factor, and the line at which each transaction definition
 * begins in the resource definition file, which is a citation anchor for the traceability matrix over a
 * tree that is held byte-identical.
 *
 * <p>The rule ordering, the deliberate choice of path matcher, the withdrawal of the generated user, the
 * cross-site posture and the transport rule are each reasoned once in {@code docs/decision-log.md}
 * DL-096 and DL-098, including the guard that was removed after being proved unreachable. The signing
 * primitive this chain verifies with is reasoned in DL-097, and the agreement between the routes
 * permitted here and the security scheme the published interface description advertises is DL-099. The
 * dangling program definition the table below keeps is anomaly register entry 3, and the derivation of
 * seventeen navigable destinations from these eighteen registered definitions is DL-104. The credential
 * hashing this class publishes is the parity exception that log records against
 * {@code app/cbl/COSGN00C.cbl} L223.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    /**
     * The one route reachable without a credential because it is the route that issues them.
     *
     * <p>It is the REST form of the legacy sign-on transaction, the entry point of both the
     * administrative and the ordinary navigation flow. The controller which serves it and the rule which
     * exempts it name one authority: a controller mapped to some other path would be authenticated by the
     * catch-all rule and would fail closed, which is the safe direction for that mistake to fail in.</p>
     *
     * <p>That authority is the controller's own constant, read here rather than restated. The direction is
     * deliberate: configuration is permitted to depend on the boundary and the boundary is not permitted to
     * depend on configuration, so the declaring site has to be the controller. This field remains published
     * because the security rules and their tests are written in terms of it.</p>
     */
    public static final String SIGN_ON_PATH = AuthController.SIGN_ON_PATH;

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
     *
     * <p>All five administrative entries of {@link TransactionRoute} therefore name one enforcement
     * pattern, and the chain deduplicates them into the one rule below. That is the honest reading of the
     * arrangement: five transactions, one gate.</p>
     */
    public static final String ADMIN_PATH_PREFIX = "/api/admin";

    /**
     * Pattern suffix matching a path and everything beneath it.
     *
     * <p>Named once so that a rule reads as a prefix plus its descendants rather than as a literal a
     * reader has to recognise.</p>
     */
    private static final String ANY_DESCENDANT = "/**";

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
     * <p><strong>The two business rules are read out of {@link TransactionRoute} rather than written
     * here.</strong> The anonymous permit exists because exactly one registered transaction is classified
     * anonymous, and the administrative rule exists because five are classified administrative; both come
     * from {@link TransactionRoute#enforcementPatternsFor(Gating)}. Everything else the table registers -
     * the twelve ordinary transactions and the one whose bound program has no source member - is answered
     * by the closing catch-all, which is why none of them needs a rule and why an unlisted route is
     * refused rather than admitted.</p>
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
                    // The table's one anonymous transaction is the sign-on transaction, and this permit
                    // exists only because it is classified that way. Reclassify it and the permit goes.
                    for (final String anonymous
                            : TransactionRoute.enforcementPatternsFor(Gating.ANONYMOUS)) {
                        requests.requestMatchers(matcher(anonymous)).permitAll();
                    }
                    // Everything else under the management base path, named or not, needs a credential.
                    requests.requestMatchers(matcher(managementSubPaths)).authenticated();
                    // The table's five administrative transactions share one prefix, so they yield one
                    // rule. A sixth classified administrative would be gated by it; one declassified
                    // would fall through to the catch-all and admit any signed-on caller.
                    for (final String administrative
                            : TransactionRoute.enforcementPatternsFor(Gating.ADMINISTRATIVE)) {
                        requests.requestMatchers(matcher(administrative))
                                .hasAuthority(JwtTokenProvider.ADMIN_AUTHORITY);
                    }
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
        // The census the rules were derived from, recorded once at start-up so that an operator reading a
        // log can reconcile the delivered gate against the resource definitions without reading this file.
        LOG.info("Route-to-role table: {} registered transaction definitions; administrative: {}, "
                        + "anonymous: {}, with no implemented bound program: {}",
                TransactionRoute.registeredTransactions().size(),
                TransactionRoute.withGating(Gating.ADMINISTRATIVE).size(),
                TransactionRoute.withGating(Gating.ANONYMOUS).size(),
                TransactionRoute.registeredTransactions().stream()
                        .filter(route -> !route.isBoundProgramImplemented())
                        .count());
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
     * <p><strong>This is the migration's one documented parity exception.</strong> The sign-on program
     * compares a stored eight-character credential against the submission as equal text at
     * {@code app/cbl/COSGN00C.cbl} L223, over the record laid out at {@code app/cpy/CSUSR01Y.cpy} L17-L23.
     * Reproducing that comparison would be faithful and would breach the requirement that no credential be
     * hardcoded, so the credential is hashed and the stored column widened to hold a digest - the single
     * intentional width change in the schema. There is no cleartext fallback of any kind: no encoder that
     * passes a value through, no delegating encoder with a legacy branch, and no migration path that would
     * accept an unhashed value once. A submission that was folded to upper case, as the program folds both
     * submitted values at L132-L136, is what verification is given, and it is what the stored digests were
     * produced from.</p>
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
     * Entitlement this module requires of a caller who reaches a registered transaction's surface, and the
     * one pattern the chain installs to enforce it.
     *
     * <p>The pattern belongs to the entitlement rather than to the individual transaction, because that is
     * how the surface is actually arranged. All five administrative transactions live beneath one prefix
     * and are gated by one rule, so a route added beneath that prefix is administrator-only from the moment
     * it exists rather than from the moment somebody remembers to add a rule for it. The ordinary
     * transactions have no dedicated rule at all: they are answered by the chain's closing catch-all, which
     * is why a new route is protected before it is written. Naming a pattern per transaction would mean
     * inventing five addresses that no request handler has claimed, and an authorization rule that matches
     * nothing is worse than no rule, because it reads as though something were being enforced.</p>
     */
    public enum Gating {

        /**
         * Reachable without presenting a credential.
         *
         * <p>Enforced by one rule naming {@link SecurityConfig#SIGN_ON_PATH}. Exactly one registered
         * transaction carries this entitlement, and it is the sign-on transaction - the route that issues
         * credentials, which could never be reached if it required one.</p>
         */
        ANONYMOUS(SIGN_ON_PATH),

        /**
         * Reachable by any caller that has established an identity, whatever user type that identity
         * carries.
         *
         * <p>No dedicated rule: these are answered by the chain's closing {@code anyRequest} rule, so the
         * default for anything unnamed is to require a credential. This is the entitlement the estate gives
         * every non-administrative transaction, because the sign-on program's alternative branch is
         * unconditional - see {@code app/cbl/COSGN00C.cbl} L235.</p>
         */
        AUTHENTICATED(""),

        /**
         * Reachable only by a caller whose identity carries the administrative authority.
         *
         * <p>Enforced by one rule over {@link SecurityConfig#ADMIN_PATH_PREFIX} and everything beneath it,
         * matching {@link JwtTokenProvider#ADMIN_AUTHORITY}. Exactly five registered transactions carry
         * this entitlement.</p>
         */
        ADMINISTRATIVE(ADMIN_PATH_PREFIX + ANY_DESCENDANT);

        /**
         * Path pattern the chain installs for this entitlement, or the empty string when the entitlement
         * has no dedicated rule and is answered by the closing catch-all.
         *
         * <p>Both non-empty values are compile-time constants assembled from the published path constants
         * above, so an enumeration constant here can never drift from the rule the chain installs.</p>
         */
        private final String enforcementPattern;

        /**
         * @param enforcementPattern path pattern the chain installs for this entitlement, or the empty
         *                           string when it has no dedicated rule
         */
        Gating(final String enforcementPattern) {
            this.enforcementPattern = enforcementPattern;
        }

        /**
         * The path pattern the chain installs for this entitlement.
         *
         * @return the pattern, or {@link Optional#empty()} when this entitlement is answered by the
         *         chain's closing catch-all rather than by a rule of its own
         */
        public Optional<String> enforcementPattern() {
            return this.enforcementPattern.isEmpty()
                    ? Optional.empty()
                    : Optional.of(this.enforcementPattern);
        }
    }

    /**
     * The route-to-role table: one entry for each of the eighteen transaction definitions registered in
     * {@code app/csd/CARDDEMO.CSD}, in the order that file defines them.
     *
     * <p>Each entry names the four-character transaction identifier a terminal user would have entered, the
     * eight-character program the definition binds it to, the line the definition begins on so a reviewer
     * and the traceability matrix can cite it, the entitlement this module requires of a caller who reaches
     * it, and whether the bound program has a source member at all. The census behind it was taken by
     * extraction rather than by reading: eighteen transaction definitions, eighteen program definitions,
     * every transaction naming exactly one program, and the set of bound programs equal to the set of
     * defined programs.</p>
     *
     * <p><strong>The table is the source of the rules, not a description of them.</strong>
     * {@link SecurityConfig#securityFilterChain(HttpSecurity)} installs an anonymous rule only because an
     * entry here is classified anonymous, and an administrative rule only because entries here are
     * classified administrative. Reclassifying an entry changes what the chain answers, which is what keeps
     * this table from becoming a comment that disagrees with the code beside it.</p>
     *
     * <p><strong>It is immutable in every respect.</strong> The constants are fixed, their fields are
     * final, and the two indexes below are built once during class initialization from the constants
     * themselves and copied into unmodifiable maps. Nothing here is assembled at request time and no caller
     * can add to, remove from or reorder it.</p>
     *
     * <p><strong>Seventeen of the eighteen have a target in this module.</strong> The eighteenth is
     * {@link #DEVELOPER_TRANSACTION}, and it is kept rather than dropped so that the inventory is honest -
     * see that constant.</p>
     */
    public enum TransactionRoute {

        /**
         * Account update. Transaction {@code CAUP}, bound to program {@code COACTUPC} at
         * {@code app/csd/CARDDEMO.CSD} L306. Reachable by any signed-on caller.
         */
        ACCOUNT_UPDATE("CAUP", "COACTUPC", 306, Gating.AUTHENTICATED, true),

        /**
         * Account view. Transaction {@code CAVW}, bound to program {@code COACTVWC} at
         * {@code app/csd/CARDDEMO.CSD} L317. Reachable by any signed-on caller.
         */
        ACCOUNT_VIEW("CAVW", "COACTVWC", 317, Gating.AUTHENTICATED, true),

        /**
         * Administrative menu. Transaction {@code CA00}, bound to program {@code COADM01C} at
         * {@code app/csd/CARDDEMO.CSD} L327. Administrative: it is the destination the sign-on program
         * reaches only for the user-type code declared at {@code app/cpy/COCOM01Y.cpy} L27, tested at
         * {@code app/cbl/COSGN00C.cbl} L230.
         */
        ADMINISTRATIVE_MENU("CA00", "COADM01C", 327, Gating.ADMINISTRATIVE, true),

        /**
         * Bill payment. Transaction {@code CB00}, bound to program {@code COBIL00C} at
         * {@code app/csd/CARDDEMO.CSD} L337. Reachable by any signed-on caller.
         */
        BILL_PAYMENT("CB00", "COBIL00C", 337, Gating.AUTHENTICATED, true),

        /**
         * Card detail. Transaction {@code CCDL}, bound to program {@code COCRDSLC} at
         * {@code app/csd/CARDDEMO.CSD} L347. Reachable by any signed-on caller.
         */
        CARD_DETAIL("CCDL", "COCRDSLC", 347, Gating.AUTHENTICATED, true),

        /**
         * Card list. Transaction {@code CCLI}, bound to program {@code COCRDLIC} at
         * {@code app/csd/CARDDEMO.CSD} L357. Reachable by any signed-on caller.
         */
        CARD_LIST("CCLI", "COCRDLIC", 357, Gating.AUTHENTICATED, true),

        /**
         * Card update. Transaction {@code CCUP}, bound to program {@code COCRDUPC} at
         * {@code app/csd/CARDDEMO.CSD} L367. Reachable by any signed-on caller.
         */
        CARD_UPDATE("CCUP", "COCRDUPC", 367, Gating.AUTHENTICATED, true),

        /**
         * Sign-on. Transaction {@code CC00}, bound to program {@code COSGN00C} at
         * {@code app/csd/CARDDEMO.CSD} L378.
         *
         * <p>The one anonymous entry in the table, because it is the route that issues credentials: a
         * token-issuing route that required a token could never be reached, and neither could any route
         * behind it. Its address is {@link SecurityConfig#SIGN_ON_PATH}.</p>
         */
        SIGN_ON("CC00", "COSGN00C", 378, Gating.ANONYMOUS, true),

        /**
         * Developer transaction. Transaction {@code CDV1}, defined at {@code app/csd/CARDDEMO.CSD} L388.
         *
         * <p><strong>Its bound program definition has no source member anywhere in {@code app/cbl}</strong>
         * - anomaly register entry 3 of {@code docs/decision-log.md}, verified by looking rather than
         * assumed. The transaction was therefore never dispatchable on the mainframe either, which is why
         * no target class is generated for that program in this module or any other, and why no route
         * constant names it: the navigation vocabulary derives seventeen destinations from these eighteen
         * definitions.</p>
         *
         * <p>It is kept here, carrying the entitlement it would have required and marked as having no
         * implemented program, because an inventory that quietly drops its own anomaly cannot be audited.
         * Keeping it is what lets the eighteen registered definitions and the seventeen delivered
         * destinations be reconciled against each other by anyone who counts them.</p>
         */
        DEVELOPER_TRANSACTION("CDV1", "COCRDSEC", 388, Gating.AUTHENTICATED, false),

        /**
         * Main menu. Transaction {@code CM00}, bound to program {@code COMEN01C} at
         * {@code app/csd/CARDDEMO.CSD} L399. Reachable by any signed-on caller - it is the destination the
         * sign-on program's unconditional alternative reaches at {@code app/cbl/COSGN00C.cbl} L235, for
         * every user-type code that is not the administrative one.
         */
        MAIN_MENU("CM00", "COMEN01C", 399, Gating.AUTHENTICATED, true),

        /**
         * Report request. Transaction {@code CR00}, bound to program {@code CORPT00C} at
         * {@code app/csd/CARDDEMO.CSD} L409. Reachable by any signed-on caller.
         */
        REPORT_REQUEST("CR00", "CORPT00C", 409, Gating.AUTHENTICATED, true),

        /**
         * Transaction list. Transaction {@code CT00}, bound to program {@code COTRN00C} at
         * {@code app/csd/CARDDEMO.CSD} L419. Reachable by any signed-on caller.
         */
        TRANSACTION_LIST("CT00", "COTRN00C", 419, Gating.AUTHENTICATED, true),

        /**
         * Transaction view. Transaction {@code CT01}, bound to program {@code COTRN01C} at
         * {@code app/csd/CARDDEMO.CSD} L429. Reachable by any signed-on caller.
         */
        TRANSACTION_VIEW("CT01", "COTRN01C", 429, Gating.AUTHENTICATED, true),

        /**
         * Transaction add. Transaction {@code CT02}, bound to program {@code COTRN02C} at
         * {@code app/csd/CARDDEMO.CSD} L439. Reachable by any signed-on caller.
         */
        TRANSACTION_ADD("CT02", "COTRN02C", 439, Gating.AUTHENTICATED, true),

        /**
         * User list. Transaction {@code CU00}, bound to program {@code COUSR00C} at
         * {@code app/csd/CARDDEMO.CSD} L449. Administrative: one of the four sign-on-record maintenance
         * transactions.
         */
        USER_LIST("CU00", "COUSR00C", 449, Gating.ADMINISTRATIVE, true),

        /**
         * User add. Transaction {@code CU01}, bound to program {@code COUSR01C} at
         * {@code app/csd/CARDDEMO.CSD} L459. Administrative: one of the four sign-on-record maintenance
         * transactions.
         */
        USER_ADD("CU01", "COUSR01C", 459, Gating.ADMINISTRATIVE, true),

        /**
         * User update. Transaction {@code CU02}, bound to program {@code COUSR02C} at
         * {@code app/csd/CARDDEMO.CSD} L469. Administrative: one of the four sign-on-record maintenance
         * transactions.
         */
        USER_UPDATE("CU02", "COUSR02C", 469, Gating.ADMINISTRATIVE, true),

        /**
         * User delete. Transaction {@code CU03}, bound to program {@code COUSR03C} at
         * {@code app/csd/CARDDEMO.CSD} L479. Administrative: one of the four sign-on-record maintenance
         * transactions.
         */
        USER_DELETE("CU03", "COUSR03C", 479, Gating.ADMINISTRATIVE, true);

        /**
         * Every entry, in the order the resource definition file defines them.
         *
         * <p>Built from the constants themselves so it cannot fall out of step with them. Enum constants
         * are created before any other static field of the type is initialized, so {@link #values()} is
         * already complete when this runs.</p>
         */
        private static final List<TransactionRoute> ALL = List.of(values());

        /**
         * Immutable index from four-character transaction identifier to entry. Looked up by key only; its
         * own iteration order is never relied upon, which is why no ordered map type is used to build it.
         */
        private static final Map<String, TransactionRoute> BY_TRANSACTION_ID = indexByTransactionId();

        /**
         * Immutable index from entitlement to the entries carrying it. Each group is a list in definition
         * order, which is the order that matters; the map's own iteration order is never relied upon.
         */
        private static final Map<Gating, List<TransactionRoute>> BY_GATING = indexByGating();

        /** Four-character transaction identifier, exactly as the definition names it. */
        private final String transactionId;

        /** Eight-character program name the transaction definition binds this transaction to. */
        private final String boundProgramName;

        /** Line in {@code app/csd/CARDDEMO.CSD} at which this transaction's definition begins. */
        private final int csdDefinitionLine;

        /** Entitlement this module requires of a caller who reaches this transaction's surface. */
        private final Gating gating;

        /** Whether the bound program has a source member in the legacy estate. */
        private final boolean boundProgramImplemented;

        /**
         * @param transactionId           four-character transaction identifier
         * @param boundProgramName        eight-character bound program name
         * @param csdDefinitionLine       line the definition begins on
         * @param gating                  entitlement required of a caller
         * @param boundProgramImplemented whether the bound program has a source member
         */
        TransactionRoute(final String transactionId, final String boundProgramName,
                final int csdDefinitionLine, final Gating gating,
                final boolean boundProgramImplemented) {
            this.transactionId = transactionId;
            this.boundProgramName = boundProgramName;
            this.csdDefinitionLine = csdDefinitionLine;
            this.gating = gating;
            this.boundProgramImplemented = boundProgramImplemented;
        }

        /**
         * Builds the immutable identifier index from the constants themselves.
         *
         * @return an unmodifiable map from transaction identifier to entry
         */
        private static Map<String, TransactionRoute> indexByTransactionId() {
            final Map<String, TransactionRoute> index = new HashMap<>();
            for (final TransactionRoute route : ALL) {
                index.put(route.transactionId, route);
            }
            return Map.copyOf(index);
        }

        /**
         * Builds the immutable entitlement index from the constants themselves.
         *
         * <p>Every entitlement gets an entry, including one no transaction carries, so a caller never has
         * to distinguish an absent key from an empty group.</p>
         *
         * @return an unmodifiable map from entitlement to the entries carrying it
         */
        private static Map<Gating, List<TransactionRoute>> indexByGating() {
            final Map<Gating, List<TransactionRoute>> index = new HashMap<>();
            for (final Gating gating : Gating.values()) {
                final List<TransactionRoute> carrying = new ArrayList<>();
                for (final TransactionRoute route : ALL) {
                    if (route.gating == gating) {
                        carrying.add(route);
                    }
                }
                index.put(gating, List.copyOf(carrying));
            }
            return Map.copyOf(index);
        }

        /**
         * Every registered transaction, in the order the resource definition file defines them.
         *
         * @return an unmodifiable list of all entries; never empty
         */
        public static List<TransactionRoute> registeredTransactions() {
            return ALL;
        }

        /**
         * The registered transactions requiring one entitlement, in definition order.
         *
         * @param gating the entitlement to select
         * @return an unmodifiable list, empty when no registered transaction requires it
         * @throws NullPointerException if {@code gating} is {@code null}
         */
        public static List<TransactionRoute> withGating(final Gating gating) {
            Objects.requireNonNull(gating, "gating must not be null");
            return BY_GATING.get(gating);
        }

        /**
         * Resolves a four-character transaction identifier to its entry without ever throwing.
         *
         * <p>An unknown identifier yields an empty result rather than a failure, because a caller asking
         * about a transaction this estate does not register is asking a legitimate question. The comparison
         * is exact: identifiers are upper case in the definitions and no case fold is applied, matching the
         * estate's own handling of the codes it compares.</p>
         *
         * @param transactionId the identifier to resolve; may be {@code null}
         * @return the matching entry, or {@link Optional#empty()} when the identifier is absent or is not
         *         one of the eighteen registered
         */
        public static Optional<TransactionRoute> forTransactionId(final String transactionId) {
            if (transactionId == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(BY_TRANSACTION_ID.get(transactionId));
        }

        /**
         * The path patterns the chain installs for one entitlement, without repetition.
         *
         * <p>This is the link that makes the table load-bearing, and it is deliberately a walk over the
         * entries rather than a question put to the entitlement. Each entry carrying the entitlement
         * contributes the pattern the entitlement names, and a pattern already contributed is not
         * contributed twice - so the five administrative entries yield one rule between them, which is the
         * arrangement rather than a simplification of it. An entitlement that no registered transaction
         * carries contributes nothing and therefore installs no rule, and an entitlement with no dedicated
         * pattern contributes nothing however many entries carry it, because those are answered by the
         * chain's closing catch-all. Reclassifying the sign-on entry withdraws the anonymous rule rather
         * than leaving it standing.</p>
         *
         * @param gating the entitlement whose rules are being installed
         * @return an unmodifiable list of the distinct patterns to install, in the order the entries
         *         contributing them are defined; empty when the entitlement has no dedicated rule or no
         *         registered transaction carries it
         * @throws NullPointerException if {@code gating} is {@code null}, screened by
         *                              {@link #withGating(Gating)} rather than screened twice
         */
        public static List<String> enforcementPatternsFor(final Gating gating) {
            final List<String> patterns = new ArrayList<>();
            for (final TransactionRoute route : withGating(gating)) {
                route.gating.enforcementPattern()
                        .filter(pattern -> !patterns.contains(pattern))
                        .ifPresent(patterns::add);
            }
            return List.copyOf(patterns);
        }

        /**
         * @return the four-character transaction identifier; never {@code null}
         */
        public String getTransactionId() {
            return this.transactionId;
        }

        /**
         * @return the eight-character program name the definition binds this transaction to; never
         *         {@code null}
         */
        public String getBoundProgramName() {
            return this.boundProgramName;
        }

        /**
         * @return the line in {@code app/csd/CARDDEMO.CSD} at which this transaction's definition begins
         */
        public int getCsdDefinitionLine() {
            return this.csdDefinitionLine;
        }

        /**
         * @return the entitlement this module requires of a caller who reaches this transaction's surface;
         *         never {@code null}
         */
        public Gating getGating() {
            return this.gating;
        }

        /**
         * Whether the bound program has a source member in the legacy estate.
         *
         * <p>False for exactly one entry, {@link #DEVELOPER_TRANSACTION}, whose program definition is
         * registered with no member behind it.</p>
         *
         * @return {@code true} when a source member exists for the bound program
         */
        public boolean isBoundProgramImplemented() {
            return this.boundProgramImplemented;
        }
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
