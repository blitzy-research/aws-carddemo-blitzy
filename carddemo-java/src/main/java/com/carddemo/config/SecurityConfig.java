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

import com.carddemo.domain.enums.UserType;
import com.carddemo.service.CredentialDigestService;
import com.carddemo.util.ApiRoutePaths;
import com.carddemo.util.RefusalBodyRenderer;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
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
 * The module's HTTP request-authorization boundary: two explicit filter chains - one for the application
 * surface and one for the management surface - least privilege by default in both, and every anonymous
 * surface named individually.
 *
 * <p><strong>Why two chains rather than one.</strong> The application surface and the management surface
 * answer to different populations. A caller reaches the application surface by signing on, and the
 * strongest thing a sign-on token can say is that its holder is an administrator of <em>cardholder
 * data</em>. A caller reaches the management surface to collect telemetry about the running process, and
 * nothing in the estate makes a cardholder administrator an operator of the process. Expressing both in
 * one chain forced the operational surface to be described in the vocabulary of sign-on entitlements,
 * which is how it came to be readable by any signed-on caller. The two chains are ordered
 * {@code MANAGEMENT_CHAIN_ORDER} then {@code APPLICATION_CHAIN_ORDER}, so the management base path is
 * decided by {@code managementSecurityFilterChain} and never falls through to the application rules.
 *
 * <p>The legacy estate decided reachability in the transaction manager's resource definitions: a
 * terminal user reached a transaction because a definition bound that transaction to a program, and the
 * sign-on program routed an administrator to the administrative menu and everyone else to the main menu
 * on the strength of a one-character user-type field. There is no equivalent outside this class once the
 * estate is served over HTTP, so this class is the whole of the route-to-role table and the only place
 * that decides what may be reached without a credential.
 *
 * <h2>The route-to-role table</h2>
 *
 * <p>{@code app/csd/CARDDEMO.CSD} registers eighteen transaction definitions, each naming exactly one
 * program. {@link TransactionRoute} carries one entry per registered transaction - its four-character
 * transaction identifier, the eight-character program bound to it, the line its definition begins on, and
 * the entitlement this module requires of a caller who reaches it. The table is immutable, is not assembled
 * at run time, and the authorization rules below are <em>derived</em> from it rather than restating it, so
 * a reclassified entry changes what the chain answers instead of producing a comment that disagrees with
 * the rule beside it.
 *
 * <p><strong>Exactly five of the eighteen are administrative</strong> - the administrative menu and the
 * four transactions that list, add, update and delete a sign-on record. Every other transaction is
 * reachable by any signed-on caller, which is the estate's own arrangement: the sign-on program tests one
 * condition and sends an administrator to the administrative menu, and its alternative is unconditional.
 * Gating a sixth would refuse a caller the estate admits; leaving one of the five ungated would admit one
 * it refuses. Both are behavioural regressions, so the count is asserted rather than assumed.
 *
 * <p><strong>The developer transaction {@code CDV1} is bound to a program that has no source member, and
 * the table keeps it.</strong> Its definition at {@code app/csd/CARDDEMO.CSD} L388 names a program for
 * which no member exists in {@code app/cbl} - anomaly register entry 3 of {@code docs/decision-log.md} -
 * so it was never dispatchable on the mainframe either. No target class is generated for it and no route
 * constant names it, which is why the navigation vocabulary derives seventeen destinations from eighteen
 * definitions (DL-104). It still appears in the table, carrying the entitlement it would have required and
 * marked as having no implemented program, because an inventory that drops its own anomaly cannot be
 * reconciled against that seventeen.
 *
 * <h2>Least privilege, and the surfaces that are anonymous</h2>
 *
 * <p>The application chain's last rule is {@code anyRequest().authenticated()} and the management chain's
 * is {@code anyRequest().hasAuthority(}{@link #MANAGEMENT_AUTHORITY}{@code )} - or {@code denyAll()} where
 * no operator identity is configured - so a route no rule mentions requires a credential on either chain:
 * a new endpoint is protected the moment it exists and becomes reachable only when someone deliberately
 * adds a rule for it. Against those two defaults, at most five kinds of surface are anonymous - three
 * unconditionally and two only where a profile opens them:
 * <p><strong>That default is sufficient for a screen transaction and was not sufficient for the one
 * surface that is not one.</strong> The batch-control surface starts jobs, and a job is not a screen: one
 * of the nine clears the transaction master as its second step. Requiring only a credential there meant
 * any signed-on caller could invoke an irreversible operation that no legacy transaction exposed at all,
 * because job submission was a facility outside the online system rather than a transaction within it.
 * {@link #BATCH_CONTROL_PATH_PREFIX} therefore carries its own rule requiring the administrative
 * authority, stated explicitly beside the rules derived from the table rather than added to the table -
 * the table is the census of the eighteen definitions, and a nineteenth row for a surface with no legacy
 * counterpart would make that census untrue. The distinction to hold on to is that the table is an
 * inventory of what the estate defined, while the chain is the whole of what this module enforces, and
 * the chain is therefore allowed one rule the inventory has no row for.
 *
 * <p><strong>Authenticated is not sufficient for every surface, and batch control is the case that proves
 * it.</strong> The catch-all admits any caller holding a valid token, which is right for the twelve
 * ordinary transactions and wrong for a surface that starts jobs. Launching the posting, accrual,
 * consolidation, statement, report or backup job re-runs financial work across the whole estate, and in the
 * estate that was job submission - a submitted job stream, never a dispatchable transaction, reachable only
 * by whoever held submission rights. {@link #BATCH_CONTROL_PATH_PATTERN} therefore carries the
 * administrative authority under a rule of its own, kept out of the transaction table below because batch
 * control is not one of the eighteen definitions that table counts, and kept out from under
 * {@link #ADMIN_PATH_PREFIX} because that prefix exists to carry five administrative transactions and would
 * be the same misreport by a different route. One entitlement, two independently reviewable rules, and a
 * census that stays honest.
 *
 * <p><strong>At most five kinds of surface are anonymous, three of them unconditionally and two only where
 * a profile opens them, and each is anonymous for a reason that can be checked against a sibling
 * file.</strong> The first three are decided by {@code managementSecurityFilterChain} and the last two by
 * {@code securityFilterChain}; the split is stated per bullet because a reader tracing one permit needs to
 * know which chain to read.
 * <ul>
 *   <li><strong>Management chain.</strong> The aggregate health endpoint and the two probe groups -
 *       {@code /actuator/health}, {@code /actuator/health/liveness} and
 *       {@code /actuator/health/readiness} - because {@code carddemo-java/Dockerfile} names
 *       a probe group as the image {@code HEALTHCHECK} and Compose services wait on it. Requiring a
 *       credential would break orchestration rather than protect anything: each of the three bodies is a
 *       status word, and the shared baseline closes component detail so that it stays one. These are
 *       <em>three exact paths</em> and deliberately not the health subtree: the per-component paths beneath
 *       it name each contributor and the database product behind it, and they are not status words.</li>
 *   <li><strong>Management chain.</strong> The metrics scrape endpoint, <strong>and only where the running
 *       profile opens it.</strong> The
 *       permit is conditional on {@code carddemo.security.anonymous-metrics-scrape}, which the shared
 *       baseline and production leave closed and only the local and test overlays open - the same two
 *       overlays that relax transport, and for the same reason: their collector is
 *       {@code config/prometheus/prometheus.yml} running in the Compose stack on the same machine.
 *       Production leaves it closed because the exposition is not a status word: it carries per-endpoint
 *       request counts and latency distributions, per-step batch record counts, data-source pool
 *       saturation and JVM internals. The endpoint stays PUBLISHED in production - a collector presenting
 *       the operator credential described below still collects, so the performance gate is unaffected - and
 *       what closes is collection by a client that presents nothing.</li>
 *   <li><strong>Application chain.</strong> The interface description, <strong>and only where the running
 *       profile publishes it.</strong> The permit is conditional on the same switch that decides whether
 * the       document is served at all. The shared baseline and production leave it unpublished; the local
 *       overlay publishes it.</li>
 *   <li><strong>Application chain.</strong> The sign-on route, because it is the route that issues tokens:
 * a       token-issuing route that required a token could never be reached, and no other route could be
 *       reached either. It is named by {@link #SIGN_ON_PATH} so the exemption is a single reviewable rule
 *       rather than a pattern that happens to match.</li>
 * </ul>
 *
 * <h2>The management surface, and the third kind of identity it requires</h2>
 *
 * <p><strong>Every management endpoint other than the three probe paths and the conditional scrape permit
 * requires the {@link #MANAGEMENT_AUTHORITY} authority</strong> - the metrics endpoints, the exposition
 * endpoint where a profile has not opened it, the build description, the per-component health paths, and
 * the environment, configuration-property, bean, migration, request-mapping and logger endpoints that only
 * the local overlay publishes. The authority is carried by exactly one identity, established by
 * {@code ManagementTokenAuthenticationFilter} from {@value #MANAGEMENT_TOKEN_PROPERTY} and by nothing else.
 *
 * <p><strong>No sign-on token can carry it, and that is the point.</strong> The application chain's bearer
 * filter is deliberately not installed on the management chain, so on that chain a sign-on token is not a
 * credential at all - an ordinary cardholder and a cardholder administrator are refused identically, and
 * neither refusal depends on an authority comparison happening to come out the right way. The earlier
 * arrangement required only {@code authenticated()} here, which every signed-on cardholder satisfies; that
 * is the defect this split closes.
 *
 * <p><strong>Absent configuration is the closed state, not the open one.</strong> Where
 * {@value #MANAGEMENT_TOKEN_PROPERTY} resolves to blank, the chain's catch-all is {@code denyAll()} rather
 * than an authority rule no caller could satisfy - the same outcome, said in a way that cannot be widened
 * by a later edit. Production must therefore supply the credential, and
 * {@link ProductionConfigurationValidator} refuses the start if it is missing, so the surface can be
 * neither accidentally open nor accidentally unreachable. Ordering the permits before the catch-all means
 * widening the published endpoint set in a profile can never widen the anonymous set: a newly published
 * endpoint falls to the catch-all the moment it appears. There is no blanket permit for the management base
 * path anywhere.
 *
 * <p><strong>The framework's generated-user path is removed rather than left dormant.</strong> Declaring
 * an {@link AuthenticationManager} bean withdraws the auto-configured in-memory user, and with it the
 * generated password that would otherwise be written to the log at start-up. Form login and HTTP basic are
 * both disabled explicitly, so there is no interactive login page, no browser credential prompt, and no
 * second way to become authenticated besides presenting a token.
 *
 * <h2>Credential storage: the migration's one documented parity exception</h2>
 *
 * <p>The sign-on record holds a credential field eight characters wide - the fourth field of the
 * eighty-byte record at {@code app/cpy/CSUSR01Y.cpy} L17-L23 - and the sign-on program compares it against
 * the submission directly, as equal text, at {@code app/cbl/COSGN00C.cbl} L223. This module hashes instead,
 * through {@link #passwordEncoder()}, and the stored column is widened to hold a digest. That is the single
 * point in the migration where the requirement that no credential be hardcoded outranks byte-for-byte
 * faithfulness, because reproducing a cleartext comparison would satisfy parity and breach the constraint
 * in the same statement; it is recorded as a documented parity exception in {@code docs/decision-log.md}.
 * What this class guarantees is confined to what it controls: a credential is stored only as a digest,
 * verification is a digest comparison rather than an equality test on cleartext, and neither this chain nor
 * the encoder it publishes writes a submitted or stored credential to a log, a response or a refusal
 * message.
 *
 * <h2>Four sign-on behaviours no framework default may replace</h2>
 *
 * <p>Each is implemented in the authentication service; what matters here is that nothing installed on
 * this chain stands in front of it and answers first.
 * <ul>
 *   <li><em>Blank-field reporting is ordered, not aggregated.</em> The program evaluates the blank
 *       user-identifier condition at {@code app/cbl/COSGN00C.cbl} L120 before the blank-credential
 *       condition at L125, inside a construct that stops at the first condition that holds - so a
 *       submission with both fields blank reports the user-identifier message and only that one.
 *       Declarative constraint validation reports whatever it finds in whatever order it finds it, which
 *       is why the sign-on submission is screened by an ordered cascade in the service, and why nothing on
 *       this chain translates a constraint violation into an answer.</li>
 *   <li><em>Both submitted values are folded to upper case unconditionally.</em> The program folds the
 *       identifier and the credential at L132-L136, before the guard at L138 that skips verification when
 *       an error is already flagged - so the fold happens whether or not that flag is set. Verification
 *       therefore compares a folded submission against a digest produced from a folded value. The fold is
 *       a fixed twenty-six-character substitution rather than a language-sensitive one, matching the
 *       substitution tables the estate uses elsewhere.</li>
 *   <li><em>The entitlement split has an unconditional alternative.</em> Across L230-L240 the program
 *       tests one condition at L230 - the one-character user-type field holding the administrative code
 *       declared at {@code app/cpy/COCOM01Y.cpy} L27, beside the standard code at L28 - and its
 *       alternative at L235 is unconditional, with no third branch and no failure path for an undeclared
 *       code. {@link JwtTokenProvider#authorityOf(UserType)} is that mapping, and the filter below either
 *       establishes an identity or leaves the request unauthenticated for the rules to decide; neither
 *       ever fails on an unexpected code.</li>
 *   <li><em>A wrong credential is not the same outcome as an unknown user.</em> The program raises its
 *       general error flag for an unknown user at L248-L249 and for an unclassified failure at L253-L254,
 *       and deliberately does not raise it for a credential that does not match at L242. Three outcomes,
 *       not two. The refusal shaping installed below distinguishes a request that established no identity
 *       from one whose identity does not entitle it, and carries the module's own summaries, so no
 *       framework authentication-failure text reaches a caller and collapses the distinction.</li>
 * </ul>
 *
 * <p>The seven message texts those paths emit belong to the message catalogue service, which is their one
 * home; none is restated here.
 *
 * <p><strong>A refusal reads identically whether the filter chain or the dispatch decided it.</strong>
 * An authorization failure inside the dispatch is answered by the boundary's own failure handler; one
 * decided here happens before any dispatch, so no exception handler is consulted and the response would
 * otherwise be the container's default error page. The entry point and access-denied handler below
 * therefore render the module's own error contract through the injected
 * {@link RefusalBodyRenderer} - whose implementation the boundary supplies, because the shape is a
 * transport type the boundary owns - and reuse
 * {@link RefusalBodyRenderer#AUTHENTICATION_REQUIRED_MESSAGE} and
 * {@link RefusalBodyRenderer#ACCESS_DENIED_MESSAGE} rather than literals of their own, so the two
 * boundaries cannot drift into disagreeing about the same condition.
 *
 * <p>Cross-site request forgery protection is disabled as a consequence of statelessness rather than a
 * relaxation of it. That attack requires the credential to be ambient - a session cookie. This module
 * establishes no session, issues no cookie, and authenticates only a token a caller must place in a
 * request header deliberately, so a cross-site request has no ambient authority to borrow. Session
 * creation is set to stateless so that this stays true rather than being true by accident.
 *
 * <p>Statelessness is what the estate's conversational re-arm becomes. Each online program ended a turn by
 * returning with its own transaction identifier and its communication area attached - the sign-on program
 * at {@code app/cbl/COSGN00C.cbl} L98-L102 - so the next keystroke resumed with the previous turn's state
 * in hand. Here the two facts that area carried forward become claims in the token this chain verifies,
 * screen state becomes a transfer object the client echoes back, and the program-to-program transfers
 * become route values returned in response bodies by the navigation service. Nothing is held between
 * requests.
 *
 * <p><strong>Statelessness is not the same as trusting a claim forever, and the difference is a second
 * question this chain asks.</strong> Each of those terminal turns re-entered a transaction that read the
 * credential master again, so an entitlement could not go stale - there was nothing carried to go stale. A
 * signed claim is the opposite: it stays true to its signature after it has stopped being true about the
 * record it describes. So a verified token establishes an identity here only while it still names the
 * record it was minted from, which the token carries as a fingerprint and
 * {@code JwtTokenProvider.namesCurrentState} recomputes on every request that presents a credential. An
 * operator demoted, promoted, deleted, or given a new credential is refused on the next request rather
 * than at the end of the token's lifetime. Nothing is stored to achieve it - no revocation list, no
 * session table, no cache - so the chain stays stateless; what it does instead is read the record, which
 * is what the estate did on every single turn.
 *
 * <p><strong>Transport security is a rule here, not a comment.</strong> When
 * {@code carddemo.security.require-https} is set, every request must arrive over a secure channel and an
 * insecure one is redirected rather than served. The shared baseline and production set it; the local and
 * test overlays clear it, because both address containers over the loopback interface of one machine.
 * That setting had no consumer before this class existed, which is precisely why it is bound here.
 *
 * <p>No latency, time-out, capacity, heap, pool or availability figure appears anywhere in this class. Two
 * kinds of number do appear and neither is a service level: the password-hashing cost, which is a
 * resistance factor, and the line at which each transaction definition begins in the resource definition
 * file, which is a citation anchor over a tree held byte-identical.
 *
 * <p>The rule ordering, the choice of path matcher, the withdrawal of the generated user, the cross-site
 * posture and the transport rule are each reasoned in {@code docs/decision-log.md} DL-096 and DL-098; the
 * signing primitive this chain verifies with in DL-097; and the agreement between the routes permitted
 * here and the security scheme the published interface description advertises in DL-099.
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
     * exempts it name one authority: a controller mapped to some other path beneath the API root would be
     * refused by the chain's closing refusal rather than exempted, which is the safe direction for that
     * mistake to fail in.</p>
     *
     * <p>That authority is the neutral route contract in the base layer, read here rather than restated.
     * The direction is deliberate: neither the boundary nor this configuration may import the other, so
     * the declaring site is the one package both are permitted to depend on. This field remains published
     * because the security rules and their tests are written in terms of it.</p>
     */
    public static final String SIGN_ON_PATH = ApiRoutePaths.SIGN_ON_PATH;

    /**
     * The root of the business surface, over which one positive authority rule is installed.
     *
     * <p><strong>Why a rule over this root exists at all.</strong> The chain used to close with bare
     * {@code authenticated()} and nothing else, which asks only whether the caller established
     * <em>an</em> identity. That is not the question the estate asks. The estate declares exactly two user
     * types, and both of them are sign-on records; a caller holding any other authority is, by
     * construction, not a sign-on record and has no business reaching an account, a card, a transaction or
     * a statement. Bare {@code authenticated()} could not express that difference, so any authority minted
     * anywhere in this process would have inherited every ordinary business route by default -
     * {@link #MANAGEMENT_AUTHORITY} being the one that already exists to demonstrate the point. It is
     * refused on the management chain today only because that chain is matched first and this one never
     * sees it; the business surface's own answer was "yes" and had to be changed to a named allow-list.
     *
     * <p><strong>The rule is positive, not a denial of the known other authority.</strong> Naming the two
     * authorities that may pass means an authority added later is refused because nothing admitted it,
     * rather than admitted because nobody remembered to exclude it. That is the whole difference between a
     * deny-list and an allow-list, and it is the direction a security rule must fail in.
     *
     * <p><strong>What this root carries is a refusal, and the grants name addresses.</strong> A positive
     * rule written over this root and everything beneath it would admit either sign-on authority to every
     * address under it - including every address no controller serves. That leaves the dispatcher's
     * not-found answer as the only thing distinguishing a real route from an invented one, and it
     * pre-admits whatever is mapped beneath the root next. The chain grants the eleven ordinary addresses
     * of {@link ApiRoutePaths#ORDINARY_ROUTE_PATHS} one rule each and closes this root with
     * {@code denyAll}, so the grant is exactly as wide as the delivered surface and anything else beneath
     * the root is refused.
     *
     * <p>The root is therefore load-bearing in its own right: it is what turns
     * a forgotten classification into an unreachable route rather than an open one. It spans the sign-on
     * route, {@link #ADMIN_PATH_PREFIX} and {@link #BATCH_CONTROL_PATH_PREFIX} as well, which is harmless
     * because rule order decides - every narrower rule is registered ahead of the refusal, so the refusal
     * only ever answers what none of them claimed. Registering it the other way round would make the whole
     * surface unreachable, which is why the order is asserted by tests that exercise real responses.
     *
     * <p>Read from the neutral route contract in the base layer rather than restated, in the direction
     * {@link #SIGN_ON_PATH} establishes, and published so a test can exercise the rule against a real
     * response rather than inspect the configuration that declares it.
     */
    public static final String API_PATH_PREFIX = ApiRoutePaths.API_PATH_PREFIX;

    /**
     * Path prefix beneath which every administrator-only route lives.
     *
     * <p>The legacy resource definitions bind eighteen transactions to programs, and five of those are
     * administrative: the administrative menu, and the four user-maintenance transactions that list, add,
     * update and delete a sign-on record. Rather than restate five route patterns, this module gives the
     * administrative surface one prefix and gates the prefix, so a route added beneath it is
     * administrator-only from the moment it exists. A route placed outside it but still beneath the API
     * root is refused by the closing refusal, because it would be neither an administrative address nor one
     * of the eleven named ordinary ones - so the failure mode of forgetting the prefix is a route nobody
     * reaches rather than one every signed-on caller reaches. The prefix is published here as the single
     * authority a controller binds against.</p>
     *
     * <p>All five are claimed. {@code api.MenuController} maps the administrative menu at
     * {@code /api/admin/menu} and {@code api.AdminUserController} maps the four sign-on-record operations
     * beneath {@code /api/admin/users}, so every one of the five is inside this prefix and reached by the
     * one rule below. Because the prefix and the mapping are written in different files, their agreement is
     * asserted rather than assumed: {@code config.DeliveredRouteSecurityStateTest} classifies every
     * delivered route against this prefix and requires the set of gated legacy transactions to equal
     * exactly the five that {@code service.NavigationService} marks administrative - so an administrative
     * operation mapped elsewhere, or an ordinary one nested in here, fails the build rather than silently
     * changing who can reach it.</p>
     *
     * <p>All five administrative entries of {@link TransactionRoute} therefore name one enforcement
     * pattern, and the chain deduplicates them into the one rule below. That is the honest reading of the
     * arrangement: five transactions, one gate.</p>
     */
    public static final String ADMIN_PATH_PREFIX = ApiRoutePaths.ADMIN_PATH_PREFIX;

    /**
     * Path prefix beneath which the operational batch-control surface lives, gated to the administrative
     * authority.
     *
     * <p><strong>Why this is a second gate and not a nineteenth row of {@link TransactionRoute}.</strong>
     * That table is an inventory of the eighteen transaction definitions the legacy resource file
     * registers, and <em>not one of them starts a batch job</em> - a job was submitted from outside the
     * online system entirely. Adding a row for a surface with no legacy counterpart would corrupt an
     * audit whose entire value is that it matches the resource definition exactly. So the batch-control
     * surface is classified here, beside the table rather than inside it, as what it is: an operational
     * capability this migration had to invent because job submission had no screen.</p>
     *
     * <p><strong>Why administrative and not merely authenticated.</strong> Reaching this prefix starts a
     * job, and the jobs are not read-only. One of them clears the transaction master outright as its
     * second step, reproducing a legacy condition-code reset. On the mainframe the equivalent authority
     * was the right to submit a job - a facility an ordinary terminal operator did not have and which no
     * transaction exposed - so requiring the administrative authority here is the narrower reading of the
     * estate, not a widened one. Before the ordinary grant was narrowed to named addresses, the
     * alternative the chain applied here was a rule over the API root that admitted any signed-on caller -
     * the correct default for a screen transaction and the wrong one for an irreversible operation. Today
     * the alternative is the closing refusal, so an absent rule would make the surface unreachable rather
     * than open; the rule is what makes it reachable by an administrator, and naming the administrative
     * authority is what keeps it out of everybody else's reach.</p>
     *
     * <p>Gating the prefix rather than each operation means an operation added beneath it is
     * administrator-only from the moment it exists. The value is read from the controller that claims the
     * surface rather than written again here, exactly as {@link #SIGN_ON_PATH} is, so the rule and the
     * mapping cannot name different addresses.</p>
     */
    public static final String BATCH_CONTROL_PATH_PREFIX = ApiRoutePaths.BATCH_CONTROL_PATH_PREFIX;

    /** Compatibility name used by the published interface contract. */
    public static final String BATCH_PATH_PREFIX = BATCH_CONTROL_PATH_PREFIX;

    /**
     * Path prefix of the batch control surface, which is administrator-only.
     *
     * <p><strong>This surface is not a migrated transaction, and that is precisely why it needs a rule of
     * its own.</strong> The eighteen transaction definitions of {@code app/csd/CARDDEMO.CSD} bind no
     * transaction to batch control: on the estate a batch member was submitted through the job entry
     * system, which required an authority an ordinary terminal user did not hold and which the sign-on
     * record's user type does not describe. This module publishes that control as REST, so the entitlement
     * has to be stated here rather than read out of the resource definitions.</p>
     *
     * <p><strong>It is stated as administrator-only because of what the nine jobs do.</strong> They post
     * the day's transactions, accrue interest, replace the transaction master's contents, and - in the
     * backup job's second step - clear the master outright. Answering those to any caller that merely
     * holds a token would let an ordinary sign-on record trigger work the estate never let a terminal user
     * near. The rule therefore names {@link JwtTokenProvider#ADMIN_AUTHORITY}, the same authority the five
     * administrative transactions require, and it covers the launch operation and the execution-status
     * operation alike: the status body names jobs and executions, which is operational metadata about what
     * the installation is running.</p>
     *
     * <p>The value is {@code api/BatchJobController}'s own published constant, read here rather than
     * restated, in the direction {@link #SIGN_ON_PATH} establishes - configuration may depend on the
     * boundary, and the boundary may not depend on configuration. It deliberately does <em>not</em> sit
     * beneath {@link #ADMIN_PATH_PREFIX}: the administrative prefix stands for the five administrative
     * <em>transactions</em> of the estate, and folding an operational surface that reproduces no
     * transaction into that prefix would make the census of five unreadable.</p>
     */
    public static final String BATCH_OPERATIONS_PATH_PREFIX = ApiRoutePaths.BATCH_JOBS_PATH;

    /**
     * Pattern suffix matching a path and everything beneath it.
     *
     * <p>Named once so that a rule reads as a prefix plus its descendants rather than as a literal a
     * reader has to recognise.</p>
     */
    private static final String ANY_DESCENDANT = ApiRoutePaths.ANY_DESCENDANT;

    /**
     * The batch control surface and everything beneath it: administrator-only, by a rule of its own.
     *
     * <p><strong>Deliberately not a {@link TransactionRoute} entry, and deliberately not beneath
     * {@link #ADMIN_PATH_PREFIX}.</strong> That table is the census of the eighteen transaction definitions
     * the estate's resource definitions actually register, and starting a batch job is not one of them:
     * batch work reached the estate through job submission, never through a transaction, so inventing a
     * nineteenth entry would misreport the census that the same table's start-up log line publishes.
     * Folding the path under the administrative prefix would be the same misreport by a different route,
     * because that prefix exists to carry the five administrative transactions and its documentation says so.</p>
     *
     * <p>The entitlement is nevertheless identical - {@link JwtTokenProvider#ADMIN_AUTHORITY} - and for a
     * stronger reason than the user-maintenance transactions have. Launching the posting, accrual,
     * consolidation, statement, report or backup job re-runs financial work over the whole estate: it posts
     * balances, accrues interest and writes generation datasets. In the estate that was a submitted job,
     * which only whoever held submission rights could run. An ordinary signed-on cardholder identity must
     * therefore not reach it, and when the ordinary grant was a rule over the API root the absence of this
     * rule admitted exactly that - any caller holding any valid token. The ordinary grant now names eleven
     * addresses and this is not one of them, so this rule is what makes the surface reachable at all, and
     * naming the administrative authority is what keeps it reachable by nobody else.</p>
     *
     * <p>Read from the neutral route contract rather than restated, so the gate and the boundary that binds
     * the path cannot drift apart, and published so the rule is assertable by a test that exercises a real
     * response.</p>
     */
    public static final String BATCH_CONTROL_PATH_PATTERN =
            ApiRoutePaths.BATCH_JOBS_PATH + ApiRoutePaths.ANY_DESCENDANT;

    /**
     * The pattern the chain's closing refusal covers: everything beneath the API root.
     *
     * <p>Assembled from {@link #API_PATH_PREFIX} rather than written out, so the refusal cannot cover a
     * different root from the one the controllers bind beneath. It carries a refusal and not the ordinary
     * grant, because carrying a grant over a whole region is what makes that grant wider than the
     * delivered surface; see that constant for the reasoning, and
     */
    private static final String BUSINESS_SURFACE_PATTERN = API_PATH_PREFIX + ANY_DESCENDANT;

    /**
     * Cost factor for password hashing, read from the service that owns the policy rather than
     * declared here.
     *
     * <p>The legacy sign-on record held an eight-character cleartext password that the sign-on program
     * compared directly. This module stores a hash and compares through the encoder below, which is the
     * one documented place where the no-hardcoded-credentials requirement outranks byte-for-byte
     * faithfulness to the estate. The factor is a deliberate resistance parameter - each increment
     * doubles the work of both a legitimate verification and an attacker's guess - and it is above the
     * library's default because the value it protects is a credential. It is not a latency or throughput
     * figure and asserts no service level.</p>
     *
     * <p><strong>Why it is read and not written.</strong> The module has two live encoders: the bean
     * published below, which the administrative user-maintenance path writes digests through, and the
     * one inside {@link CredentialDigestService}, which is the declared authority for the stored form
     * of a credential. Two encoders at two strengths is not a verification failure - a digest carries
     * its own cost - it is a silent policy split in which the weaker of the two becomes the module's
     * real strength. Restating the number here is exactly how that split happens, so the number has one
     * home and this is a reference to it. The direction is the permitted one: configuration may depend
     * on a service, and a service may never depend on configuration, which is why the constant lives
     * in the service and not here.</p>
     */
    private static final int PASSWORD_HASHING_STRENGTH = CredentialDigestService.HASHING_STRENGTH;

    /**
     * Authority that reads the operational surface, held by a machine and by nothing else.
     *
     * <h2>Why a third authority exists</h2>
     *
     * <p>This module grants exactly two authorities from a sign-on, because the legacy estate declares
     * exactly two user types - and neither of them is an operator. Gating the management surface on bare
     * {@code authenticated()} would admit every signed-on cardholder, so any ordinary application user
     * could read per-endpoint request counts and latency distributions, per-batch-step record counts,
     * connection-pool saturation, and JVM heap, thread and garbage-collection internals. That set is
     * enough to profile transaction volume, infer business activity from the batch counts and time an
     * attack, and none of it is anything a cardholder's session needs.
     * <p>So the fix is not a stricter role on the existing tokens, it is a <em>different kind of
     * identity</em>. A collector is not a user of this application: it holds no account, signs on through
     * no screen, and appears nowhere in the legacy user records. Granting it one of the two authorities
     * derived from {@code SEC-USR-TYPE} would have meant either promoting every administrator to operator
     * or inventing a legacy user type that does not exist. This authority is minted from a machine
     * credential instead, is the only authority the management chain accepts, and is never carried by a
     * token issued at sign-on.
     *
     * <p>{@code application-prod.yml} already promised "a scoped credential" for the collector. This is
     * that credential's authority; before it existed the promise had nothing behind it.
     */
    public static final String MANAGEMENT_AUTHORITY = "ROLE_MONITORING";

    /**
     * Setting the machine credential for the management surface is supplied through.
     *
     * <h2>What this credential has to withstand, and therefore what it has to be</h2>
     *
     * <p>It is presented as a bearer token on any request beneath the management base path, and
     * {@code ManagementTokenAuthenticationFilter} below establishes {@link #MANAGEMENT_AUTHORITY} for a
     * request that carries the right bytes. There is no sign-on behind it, no account, no lockout, no
     * attempt counter and no second factor - by design, because a collector is not a user - so
     * <strong>the only thing protecting it is that it cannot be guessed</strong>. The constant-time
     * comparison in that filter stops a timing side channel from revealing the value one character at a
     * time; it does nothing at all about a value that is short enough to enumerate outright.
     *
     * <p>So production requires a minimum length. {@link ProductionConfigurationValidator} refuses to
     * start the production profile when this setting resolves to fewer than
     * {@value ProductionConfigurationValidator#MINIMUM_MANAGEMENT_TOKEN_LENGTH} characters after
     * stripping, alongside its refusal of an absent, unresolved or blank one. Before that rule, a
     * one-character token satisfied every check this module made and then fell to a few hundred
     * guesses - CWE-521, reachable in production with nothing else misconfigured.
     *
     * <p>Generate one, never choose one: {@code openssl rand -base64 24} produces a value at the floor
     * over a 64-symbol alphabet, which is what also satisfies
     * {@link ProductionConfigurationValidator#MANAGEMENT_TOKEN_MINIMUM_DISTINCT_CHARACTERS}. A hexadecimal
     * value of the same width is long enough and usually fails that second rule, because hexadecimal has
     * only sixteen symbols in total. A length rule is a proxy for unpredictability and not a measure of it
     * - thirty-two repetitions of one letter would satisfy it - which is why both the generator and its
     * alphabet are named here rather than left to be improvised.
     *
     * <p>No floor is imposed outside production. The local and test overlays carry a committed
     * development literal so the profiles work on a fresh clone, and the shared baseline binds this
     * setting with an EMPTY default, which is the fail-closed state: with nothing configured, no
     * identity can hold the authority and the surface admits nobody beyond the three anonymous probe
     * paths.
     */
    public static final String MANAGEMENT_TOKEN_PROPERTY = "carddemo.security.management.token";

    /**
     * Shortest machine credential the management surface will start with, in characters.
     *
     * <p>Thirty-two, because this credential is the whole of the authentication on a surface that
     * publishes health, metrics and environment detail, and because it is not rate limited: no chain in
     * this module counts attempts - the legacy estate has no attempt counter and the migration adds none
     * (docs/decision-log.md DL-352) - so what stands between a caller and this surface is the cost of
     * guessing the value. Nothing bounded it before: the value need only have been non-blank, so a
     * single character was accepted and startup reported nothing unusual, leaving a surface that looked
     * authenticated and was not.
     *
     * <p>Thirty-two visible characters is the width of a 128-bit value written in hexadecimal or of a
     * 192-bit value written in Base64, which puts exhaustive search out of reach without demanding a
     * particular encoding. Length is what is checked because length is what can be checked: a
     * configuration value cannot be inspected for randomness, so the requirement is stated as a width
     * an unguessable value comfortably satisfies and a typed one does not.
     */
    public static final int MANAGEMENT_TOKEN_MIN_LENGTH = 32;

    /**
     * Longest machine credential the management surface will start with, in characters.
     *
     * <p>An upper bound exists because the value is compared byte for byte on every request to the
     * surface a collector scrapes on a schedule, and because a credential of unbounded length is a
     * configuration mistake rather than a stronger credential - past a few hundred characters the value
     * is not a secret someone chose but a file, a certificate or a paste that landed in the wrong
     * setting. Five hundred and twelve is far above any generated token and far below anything that
     * would make the comparison measurable.
     */
    public static final int MANAGEMENT_TOKEN_MAX_LENGTH = 512;

    /**
     * Fewest distinct characters the machine credential must contain.
     *
     * <p>A width requirement alone is satisfied by thirty-two copies of one letter, which has the shape
     * of a strong credential and none of the substance. Any value drawn at random from a hexadecimal,
     * Base64 or alphanumeric alphabet carries far more distinct characters than this at thirty-two
     * characters wide, so the floor refuses the hand-written degenerate cases without constraining a
     * generated value. It is a screen against an obvious mistake, not a measure of entropy, and it is
     * documented as such so it is not mistaken for one.
     */
    public static final int MANAGEMENT_TOKEN_MIN_DISTINCT_CHARACTERS = 8;

    /** Lowest character code the machine credential may contain: the first visible ASCII character. */
    private static final char MANAGEMENT_TOKEN_LOWEST_CHARACTER = '!';

    /** Highest character code the machine credential may contain: the last visible ASCII character. */
    private static final char MANAGEMENT_TOKEN_HIGHEST_CHARACTER = '~';

    /**
     * How many bytes of generated material a production management credential must carry.
     *
     * <p>Stated here, on the class that consumes the credential, because that is where the module already
     * puts a credential's floor: {@code JwtTokenProvider} holds the signing key's floor beside the
     * algorithm that requires it. {@code ProductionConfigurationValidator} reads this constant rather than
     * restating the number, so the rule a deployment is held to and the rule this filter relies on cannot
     * drift apart.
     *
     * <p>The floor is not enforced here. A local or test profile runs with no credential at all - the
     * fail-closed state - and a floor applied in this constructor would make the empty case a start-up
     * failure in every profile. Production is where a supplied credential must be strong, and production is
     * where it is checked. See {@code docs/decision-log.md} entry DL-312.
     */
    public static final int MANAGEMENT_TOKEN_MINIMUM_BYTES = 32;

    /** Meter recording what happened to a presented management credential. */
    static final String MANAGEMENT_AUTHENTICATION_METER = "carddemo.management.authentication";

    /** Tag naming the outcome of a presentation. */
    static final String MANAGEMENT_OUTCOME_TAG = "outcome";

    /** Outcome tag value for a credential that matched. */
    static final String MANAGEMENT_OUTCOME_ESTABLISHED = "established";

    /** Outcome tag value for a presented credential that did not match. */
    static final String MANAGEMENT_OUTCOME_REFUSED = "refused";

    /** Outcome tag value for a presentation made when no operator identity is configured. */
    static final String MANAGEMENT_OUTCOME_UNCONFIGURED = "unconfigured";

    /**
     * How many consecutive refusals are absorbed before the sustained-failure warning is raised again.
     *
     * <p>A bound on the diagnostic, deliberately not a bound on the authentication; the class comment on
     * {@code ManagementTokenAuthenticationFilter} explains why that distinction is the right one here.
     */
    static final int MANAGEMENT_REFUSAL_REPORT_INTERVAL = 10;

    /** Name the management identity is recorded under, which is a role and not a person. */
    private static final String MANAGEMENT_PRINCIPAL = "carddemo-management-collector";

    /**
     * Precedence of the management chain, which must be consulted before the application chain.
     *
     * <p>Ordering is the mechanism, not a detail. The application chain carries no request matcher, so it
     * matches everything; whichever chain the framework consults first therefore decides the management
     * paths. Stating both orders explicitly - rather than relying on one being annotated and the other
     * defaulting to lowest precedence - makes the relationship readable in one place and impossible to
     * invert by adding an annotation later.
     */
    private static final int MANAGEMENT_CHAIN_ORDER = 1;

    /** Precedence of the application chain, which matches everything the management chain did not. */
    private static final int APPLICATION_CHAIN_ORDER = 2;

    /** Name of the liveness probe group, as the framework publishes it beneath the health endpoint. */
    private static final String LIVENESS_PROBE = "liveness";

    /** Name of the readiness probe group, as the framework publishes it beneath the health endpoint. */
    private static final String READINESS_PROBE = "readiness";

    /** Credential scheme name a challenge names, and the prefix a presented credential carries. */
    private static final String BEARER_SCHEME = "Bearer";

    /** Prefix a bearer credential carries in the authorization header, including its trailing space. */
    private static final String BEARER_PREFIX = BEARER_SCHEME + " ";

    /** Logger. Never receives a token, a credential, or a rejected value. */
    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

    /** Verifies presented tokens and reads the user type they carry. */
    private final JwtTokenProvider tokenProvider;

    /** Renders a refusal body in the module's own error shape. */
    private final RefusalBodyRenderer refusalBodyRenderer;

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
     * Machine credential the management surface accepts, or empty when no operator identity is configured.
     *
     * <p>Empty is a supported and <strong>fail-closed</strong> state rather than a gap: with no credential
     * configured there is no identity that can hold {@link #MANAGEMENT_AUTHORITY}, so the management
     * surface admits nobody beyond the three anonymous probe paths. That is the correct default for a
     * setting whose absence must never widen anything - the alternative, a shipped literal, would be a
     * credential in the repository, and the alternative to that, opening the surface when unset, would
     * make a missing setting the most permissive configuration.
     */
    private final String managementToken;

    /**
     * Binds the settings that decide reachability, so that each has exactly one home.
     *
     * <p>Every path below is bound rather than written twice. The management base path and the
     * interface-description address are declared in configuration and resolved literally by sibling
     * files, so restating either here would create a second home that could disagree with the first;
     * binding them means a path that moves in configuration moves in the authorization rules with it.</p>
     *
     * @param tokenProvider      verifier for presented bearer tokens
     * @param refusalBodyRenderer renderer for the refusal body, supplied by the boundary so a
     *                           filter-boundary refusal is shaped exactly like a dispatch-boundary one
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
     * @param managementToken    machine credential the management surface accepts, empty when no operator
     *                           identity is configured; bound with an EMPTY default rather than no default
     *                           because empty is the fail-closed state - it admits nobody beyond the three
     *                           anonymous probe paths - whereas a shipped literal would be a credential in
     *                           the repository and a missing setting must never be the permissive case. A
     *                           value that is present but too weak to be the surface's only
     *                           authentication fails startup; see
     *                           {@link #requireUsableManagementToken(String)}
     * @throws IllegalStateException if a machine credential is configured and is too weak
     */
    public SecurityConfig(
            final JwtTokenProvider tokenProvider,
            final RefusalBodyRenderer refusalBodyRenderer,
            @Value("${carddemo.security.require-https}") final boolean requireHttps,
            @Value("${springdoc.api-docs.enabled:false}") final boolean apiDocsPublished,
            @Value("${carddemo.security.anonymous-metrics-scrape}")
                    final boolean anonymousMetricsScrape,
            @Value("${springdoc.api-docs.path:/v3/api-docs}") final String apiDocsPath,
            @Value("${management.endpoints.web.base-path:/actuator}") final String managementBasePath,
            @Value("${spring.mvc.servlet.path:/}") final String servletPath,
            @Value("${" + MANAGEMENT_TOKEN_PROPERTY + ":}") final String managementToken) {
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider must not be null");
        this.refusalBodyRenderer =
                Objects.requireNonNull(refusalBodyRenderer, "refusalBodyRenderer must not be null");
        this.requireHttps = requireHttps;
        this.apiDocsPublished = apiDocsPublished;
        this.anonymousMetricsScrape = anonymousMetricsScrape;
        this.apiDocsPath = Objects.requireNonNull(apiDocsPath, "apiDocsPath must not be null");
        this.managementBasePath =
                Objects.requireNonNull(managementBasePath, "managementBasePath must not be null");
        this.servletPath = Objects.requireNonNull(servletPath, "servletPath must not be null");
        this.managementToken = requireUsableManagementToken(
                Objects.requireNonNull(managementToken, "managementToken must not be null").strip());
    }

    /**
     * Refuses a machine credential that is present but too weak to be the only authentication on the
     * management surface, failing the context rather than starting with it.
     *
     * <p>An empty value is returned unchanged. Empty is the configured fail-closed state: the chain's
     * catch-all becomes {@code denyAll()}, the filter matches nothing including an empty presented
     * credential, and only the three anonymous probe paths remain reachable. A deployment that has not
     * configured an operator identity is therefore not a deployment with a weak one, and refusing to
     * start would refuse the safe case.
     *
     * <p>A present value must be between {@value #MANAGEMENT_TOKEN_MIN_LENGTH} and
     * {@value #MANAGEMENT_TOKEN_MAX_LENGTH} characters, must be visible ASCII throughout, and must carry
     * at least {@value #MANAGEMENT_TOKEN_MIN_DISTINCT_CHARACTERS} distinct characters. Anything else
     * fails startup, which is the only moment at which a weak credential can still be fixed cheaply -
     * once the context is up, the surface is open and the weakness is only observable to whoever guesses
     * it first.
     *
     * <p><strong>The failure names the rule and never the value.</strong> A context failure is printed to
     * the console, written to the log and frequently forwarded to a collector, so a message quoting the
     * offending credential would publish it in three places at once.
     *
     * @param  token the stripped configured credential, possibly empty
     * @return the credential, unchanged, when it is usable
     * @throws IllegalStateException when a credential is configured and is too weak to rely on
     */
    private static String requireUsableManagementToken(final String token) {
        if (token.isEmpty()) {
            return token;
        }
        if (token.length() < MANAGEMENT_TOKEN_MIN_LENGTH
                || token.length() > MANAGEMENT_TOKEN_MAX_LENGTH) {
            throw new IllegalStateException(MANAGEMENT_TOKEN_PROPERTY + " must be between "
                    + MANAGEMENT_TOKEN_MIN_LENGTH + " and " + MANAGEMENT_TOKEN_MAX_LENGTH
                    + " characters; it is the only authentication on the management surface and that"
                    + " surface has no attempt allowance");
        }
        final Set<Character> distinct = new HashSet<>();
        for (int index = 0; index < token.length(); index++) {
            final char character = token.charAt(index);
            if (character < MANAGEMENT_TOKEN_LOWEST_CHARACTER
                    || character > MANAGEMENT_TOKEN_HIGHEST_CHARACTER) {
                throw new IllegalStateException(MANAGEMENT_TOKEN_PROPERTY + " must be visible ASCII"
                        + " throughout, with no space and no control character, so that the value"
                        + " presented in an Authorization header is the value configured");
            }
            distinct.add(Character.valueOf(character));
        }
        if (distinct.size() < MANAGEMENT_TOKEN_MIN_DISTINCT_CHARACTERS) {
            throw new IllegalStateException(MANAGEMENT_TOKEN_PROPERTY + " must carry at least "
                    + MANAGEMENT_TOKEN_MIN_DISTINCT_CHARACTERS + " distinct characters; a value of the"
                    + " required width made of one repeated character is not an unguessable value");
        }
        return token;
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
     * Builds the higher-precedence filter chain that decides every request beneath the management base
     * path, and nothing else.
     *
     * <p><strong>This chain exists because the management surface answers to a different population than
     * the application surface.</strong> Its predecessor was a pair of rules inside the application chain: a
     * blanket permit for the health subtree, then bare {@code authenticated()} for everything else beneath
     * the base path. The second of those is satisfied by every signed-on cardholder, which made
     * per-endpoint latency distributions, per-batch-step record counts, connection-pool saturation and JVM
     * internals readable by an ordinary application user. Neither of the two authorities a sign-on token
     * can carry is an operator authority - the estate declares exactly two user types and neither of them
     * operates the process - so the fix could not be a stricter rank of an existing role and had to be a
     * third kind of identity on a chain of its own.</p>
     *
     * <p><strong>Matching is the whole base path and its descendants, and the base path itself is included
     * deliberately</strong> - the endpoint index is served there and enumerates every published endpoint,
     * which is a management answer like any other. Because this bean is ordered ahead of the application
     * chain, a management request is decided here and never falls through to the application rules; there
     * is consequently no management rule in the application chain at all, and its absence is the fix rather
     * than an omission.</p>
     *
     * <p><strong>Rule order is the contract.</strong> Three exact permits come first - the aggregate health
     * endpoint and the two probe groups, named individually rather than as a subtree so the per-component
     * health paths stay closed; the conditional scrape permit follows, added only where the running profile
     * opens it; and the catch-all closes the chain. Reordering these changes behaviour, so the order is
     * asserted by tests that exercise real responses rather than inspect the configuration.</p>
     *
     * <p><strong>The catch-all is {@code denyAll()} where no operator identity is configured</strong> and
     * {@code hasAuthority(}{@link #MANAGEMENT_AUTHORITY}{@code )} where one is. Both refuse every caller
     * without the credential; stating the first as an outright denial rather than as an authority nobody
     * can hold means an absent credential cannot be widened into an open surface by a later edit.</p>
     *
     * <p><strong>The application chain's bearer filter is deliberately not installed here.</strong> On this
     * chain a sign-on token is not a credential at all, so an ordinary identity and an administrative one
     * are refused by the same route rather than by an authority comparison that has to come out correctly.
     * The only filter installed is {@link ManagementTokenAuthenticationFilter}, which mints
     * {@link #MANAGEMENT_AUTHORITY} from {@value #MANAGEMENT_TOKEN_PROPERTY} and from nothing else.</p>
     *
     * @param http the chain builder supplied by the framework
     * @param meterRegistry where the operator filter counts what happened to a presented credential.
     *                      Taken as a provider rather than as the registry itself so that a slice which
     *                      publishes no registry still builds this chain: the outcomes are then simply not
     *                      counted, and no other behaviour changes
     * @return the configured management chain
     * @throws Exception if the framework rejects the configuration
     */
    @Bean
    @Order(MANAGEMENT_CHAIN_ORDER)
    public SecurityFilterChain managementSecurityFilterChain(final HttpSecurity http,
            final ObjectProvider<MeterRegistry> meterRegistry) throws Exception {
        final String healthPath = this.managementBasePath + "/health";
        final String prometheusPath = this.managementBasePath + "/prometheus";
        final boolean operatorIdentityConfigured = !this.managementToken.isEmpty();

        http
                // Both the base path itself and everything beneath it, because the endpoint index is
                // served at the base path and is as much a management surface as any endpoint under it.
                // Expressed through the plural configurer because the singular form accepts one matcher.
                .securityMatchers(managed -> managed.requestMatchers(
                        matcher(this.managementBasePath),
                        matcher(this.managementBasePath + ANY_DESCENDANT)))
                // Same posture as the application chain, and stated rather than inherited: a chain
                // declares its own, and a management chain that silently picked up a session, a cookie or
                // a login form from somewhere else would be a management chain with ambient authority.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> {
                    // THREE EXACT PATHS, NOT A SUBTREE. The previous rule permitted /actuator/health/**,
                    // which is every present and future sub-path of the health endpoint - including the
                    // per-component paths, whose bodies name each health contributor, the database product
                    // and version and the validation query. Naming the three that are genuinely probes is
                    // what keeps a path added by a framework upgrade from arriving anonymous.
                    requests.requestMatchers(
                            matcher(healthPath),
                            matcher(healthPath + "/" + LIVENESS_PROBE),
                            matcher(healthPath + "/" + READINESS_PROBE)).permitAll();
                    if (this.anonymousMetricsScrape) {
                        // Only where the collector runs on the same machine as the application, which is
                        // the local and test overlays. The shared baseline and production leave it closed,
                        // and the scrape path then falls to the operator rule below.
                        requests.requestMatchers(matcher(prometheusPath)).permitAll();
                    }
                    if (operatorIdentityConfigured) {
                        // Everything else beneath the base path, named or not, needs the OPERATOR
                        // authority - not merely a credential. An ordinary sign-on token cannot satisfy
                        // this, and that is the whole point of the rule.
                        requests.anyRequest().hasAuthority(MANAGEMENT_AUTHORITY);
                    } else {
                        // Fail closed. With no operator credential configured, no identity can hold the
                        // authority above, so the honest rule is to admit nobody rather than to fall back
                        // on "any authenticated caller" - which is exactly the over-grant being removed.
                        requests.anyRequest().denyAll();
                    }
                })
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthorizedEntryPoint())
                        .accessDeniedHandler(forbiddenHandler()))
                // The application chain's bearer filter is deliberately NOT installed here. It would
                // establish an ordinary sign-on identity on this chain, which would then be refused by the
                // authority rule above - the right outcome by a confusing route. Installing only the
                // operator filter makes the surface's vocabulary explicit: on this chain, a sign-on token
                // is not a credential at all.
                .addFilterBefore(new ManagementTokenAuthenticationFilter(this.managementToken,
                                meterRegistry.getIfAvailable()),
                        UsernamePasswordAuthenticationFilter.class);

        if (this.requireHttps) {
            http.redirectToHttps(Customizer.withDefaults());
        }

        LOG.info("Management security configured: anonymous surfaces are {} and the two probe groups{};"
                        + " every other management path requires the {} authority; operator identity"
                        + " configured: {}; secure channel required: {}",
                healthPath,
                this.anonymousMetricsScrape ? ", plus the metrics scrape endpoint" : "",
                MANAGEMENT_AUTHORITY,
                operatorIdentityConfigured,
                this.requireHttps);
        return http.build();
    }

    /**
     * Builds the filter chain that decides every request outside the management base path.
     *
     * <p>Rule order is the contract. The error-dispatch permit comes first and names no surface at all -
     * it excuses the container's internal re-dispatch of an already-refused request, which carries no
     * authentication because the bearer filter does not run on it, and which a client cannot issue; the
     * permits follow and name individual surfaces; the administrative prefix follows; the batch-control
     * prefix follows that; the eleven ordinary addresses follow that, each requiring one of the two
     * sign-on authorities; a refusal then covers everything beneath the API root that none of those rules
     * claimed; and the catch-all closes the chain over what is left, which is everything outside the API
     * root. Reordering these changes behaviour, so the order is asserted by tests that exercise real
     * responses rather than inspect the configuration.</p>
     *
     * <p><strong>No management rule appears here, and its absence is deliberate.</strong> Every request
     * beneath the management base path is matched by {@link #managementSecurityFilterChain(HttpSecurity)},
     * which is ordered ahead of this one. Restating a management rule here would be dead code at best and,
     * if the two ever disagreed, a second answer to a question that must have exactly one.</p>
     *
     * <p><strong>All three business rules are read out of {@link TransactionRoute} rather than written
     * here.</strong> The anonymous permit exists because exactly one registered transaction is classified
     * anonymous, the administrative rule because five are classified administrative, and the eleven
     * ordinary grants because twelve are classified ordinary and eleven of them have a delivered address;
     * all three come from {@link TransactionRoute#enforcementPatternsFor(Gating)}. Reclassifying an entry
     * therefore changes what this chain answers, which is what keeps the table from becoming a description
     * of rules rather than their source.</p>
     *
     * <p><strong>Two rules are written here rather than read from the table, and that is the point of
     * them.</strong> {@link #BATCH_CONTROL_PATH_PATTERN} gates the batch control surface behind the same
     * administrative authority, because job submission was never a transaction in the estate and adding a
     * nineteenth table entry would misreport the census the table exists to publish. The rule sits between
     * the administrative loop and the ordinary loop: placed after, so it cannot shadow a narrower rule;
     * placed before, so no broader rule can admit an ordinary signed-on identity to it.</p>
     *
     * <p><strong>{@link #BUSINESS_SURFACE_PATTERN} then refuses everything beneath the API root that none
     * of those rules claimed.</strong> That refusal is what makes reading the ordinary grants out of the
     * table safe: a reclassified or forgotten ordinary address is refused rather than left to a rule that
     * accepts any established identity, so the edit fails closed. It also refuses the addresses this module
     * maps nothing to, which the framework would otherwise answer for a signed-on caller as a not-found
     * rather than as a refusal - authorization deciding nothing and the dispatcher deciding everything.
     * Only what lies OUTSIDE the root reaches the closing rule, where an established identity is the right
     * default because the container's own error path is reached that way. See {@link #API_PATH_PREFIX} for
     * the full reasoning.</p>
     *
     * @param http the chain builder supplied by the framework
     * @return the configured application chain
     * @throws Exception if the framework rejects the configuration
     */
    @Bean
    @Order(APPLICATION_CHAIN_ORDER)
    public SecurityFilterChain securityFilterChain(final HttpSecurity http) throws Exception {
        http
                // A cross-site request cannot borrow authority that is never ambient: no session, no
                // cookie, and a credential the caller must set as a header deliberately.
                .csrf(AbstractHttpConfigurer::disable)
                // WebMvcConfig supplies one exact-origin policy for /api/**. Enabling the security
                // integration processes permitted preflights before bearer authentication.
                .cors(Customizer.withDefaults())
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
                    // FIRST, and it decides nothing about what a client may reach. This permits the
                    // container's ERROR dispatch - the internal re-dispatch that carries an already
                    // refused request to the error path so a body can be written for it. A client cannot
                    // issue one: a request addressed to the error path is an ordinary REQUEST dispatch
                    // and is still answered by the closing catch-all below.
                    //
                    // Without it, the rules ran a second time over a request that no longer carried an
                    // authentication - the bearer filter is a once-per-request filter and does not run
                    // on an error dispatch - so the catch-all answered 401 and OVERWROTE the status the
                    // framework had already decided. A wrong method read as an authentication failure, an
                    // unreadable media type read as one, an unsatisfiable Accept header read as one, and
                    // an unknown path read as one, on the anonymous sign-on route as much as anywhere
                    // else. ModuleErrorController answers the dispatch once it is permitted.
                    requests.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                    // NO MANAGEMENT RULE APPEARS HERE ANY MORE, and its absence is the fix rather than an
                    // omission. This chain used to permit /actuator/health/** and then require bare
                    // authenticated() for every other management path - a rule every signed-on cardholder
                    // satisfies, which made per-endpoint latency, per-batch-step record counts, pool
                    // saturation and JVM internals readable by an ordinary application user. The whole
                    // management subtree is now matched by managementSecurityFilterChain above, which
                    // requires an operator authority no sign-on token can carry. Restating a management
                    // rule here would be dead code at best and, if the two ever disagreed, a second
                    // answer to a question that must have one.
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
                    // The batch control surface, gated ahead of the ordinary rules and the refusal that
                    // follows them. Both of its operations are covered - the launch and the execution
                    // status - and so is anything added beneath the prefix later. See
                    // BATCH_OPERATIONS_PATH_PREFIX for why this entitlement is stated here rather than read
                    // out of the resource definitions, and note that batch control is not one of the
                    // eighteen registered transactions and must not be counted as one: it gets a rule of
                    // its own, not a fabricated table row. This rule is what makes the surface reachable:
                    // the ordinary grants below name eleven addresses that do not include it, and the
                    // refusal after them covers everything else beneath the root - so without this rule a
                    // surface that re-runs posting, accrual and statement generation would be reachable by
                    // nobody, and with a weaker one it would be reachable by any signed-on caller.
                    //
                    // ONE rule, installed once. The descendant half of it must not be installed a second
                    // time further down under a second name with the identical authority. A duplicate that agrees is dead
                    // weight; a duplicate that stops agreeing is two answers to a question that must have one, and the
                    // reader has no way to know which one the chain applies. The descendant pattern is named from the
                    // published constant so there is one spelling of it as well as one rule.
                    requests.requestMatchers(matcher(BATCH_OPERATIONS_PATH_PREFIX),
                                    matcher(BATCH_CONTROL_PATH_PATTERN))
                            .hasAuthority(JwtTokenProvider.ADMIN_AUTHORITY);
                    // The table's five administrative transactions share one prefix, so they yield one
                    // rule. A sixth classified administrative would be gated by it; one declassified would
                    // fall to the ordinary loop below, which names eleven addresses that do not include it,
                    // and would then be refused by the closing refusal rather than admitted to any
                    // signed-on caller.
                    for (final String administrative
                            : TransactionRoute.enforcementPatternsFor(Gating.ADMINISTRATIVE)) {
                        requests.requestMatchers(matcher(administrative))
                                .hasAuthority(JwtTokenProvider.ADMIN_AUTHORITY);
                    }
                    // The ordinary business surface: ONE RULE PER DELIVERED ADDRESS, each admitting only
                    // the two authorities a sign-on token can carry.
                    //
                    // Two changes are folded into this loop and both matter. First, the authorities are
                    // named rather than an identity merely required: authenticated() asks whether an
                    // identity was established and not whose, and the estate declares exactly two user
                    // types which are both sign-on records - so an authority that is neither
                    // (MANAGEMENT_AUTHORITY exists in this very class to prove such an authority can be
                    // minted) would otherwise reach every account, card, transaction and statement route.
                    // Second, the addresses are enumerated rather than covered by a rule over the API
                    // root. A rule over the root granted an ordinary token every path beneath it,
                    // including the ones no controller serves, which left the router's not-found answer
                    // as the only thing separating a real route from an invented one - authorization
                    // deciding nothing while the dispatcher decided everything - and pre-admitted
                    // whatever was mapped beneath the root next.
                    //
                    // Read out of the route-to-role table, so a reclassified entry changes the delivered
                    // rules. That is safe here in a way it would not have been before, because the
                    // refusal below closes the root: a withdrawn grant makes its address unreachable
                    // instead of leaving it to a rule that accepts any identity.
                    for (final String ordinary
                            : TransactionRoute.enforcementPatternsFor(Gating.AUTHENTICATED)) {
                        requests.requestMatchers(matcher(ordinary))
                                .hasAnyAuthority(JwtTokenProvider.ADMIN_AUTHORITY,
                                        JwtTokenProvider.USER_AUTHORITY);
                    }
                    // EVERYTHING ELSE BENEATH THE API ROOT IS REFUSED OUTRIGHT, and this rule is what
                    // makes the enumeration above safe to write. An address beneath the root that no rule
                    // has claimed is not a business route: either nothing serves it, or something serves
                    // it that nobody has yet decided the entitlement of. Refusing both is the only answer
                    // that fails in the right direction, and it is why forgetting to classify a new route
                    // makes it unreachable rather than open.
                    //
                    // It is a refusal and not "authenticated", because requiring merely an identity here
                    // is what the enumeration above exists to stop: any signed-on caller would again be
                    // authorized for every unclaimed address under the root. The root and its descendants
                    // are both named, following the same convention as the batch rule above and the
                    // management chain's own matcher, so the bare root cannot slip past on a
                    // pattern-semantics detail.
                    //
                    // Placed after every narrower rule, so it shadows none of them - the sign-on permit,
                    // the two batch rules, the administrative loop and the ordinary loop all decide
                    // first.
                    requests.requestMatchers(matcher(API_PATH_PREFIX),
                                    matcher(BUSINESS_SURFACE_PATTERN))
                            .denyAll();
                    // The closing rule, and it answers only what is OUTSIDE the API root: the container's
                    // error path reached as an ordinary client request, and any address this module maps
                    // nothing to. Requiring an identity of those is the right default - it fails closed
                    // for an unmapped path without turning the framework's own 404 into a 403 - and it
                    // protects no business route, because every one of those is decided above.
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

        // Names only what THIS chain decides. The management surface has its own chain and its own
        // start-up line, and this message must not list the health probe or the scrape endpoint: they are
        // accurate only while this chain carries those rules, and become a second, stale description of them
        // the moment it does not.
        LOG.info("HTTP security configured: the anonymous surface is the sign-on route{}; {} named "
                        + "ordinary addresses require one of the two sign-on authorities {} or {} by name, "
                        + "rather than merely an established identity; the administrative prefix {} and the "
                        + "batch-control prefix {} require {} alone; every other address beneath {} is "
                        + "refused outright rather than admitted to a signed-on caller; anything outside {} "
                        + "requires an identity by the closing rule; the management base path {} is decided "
                        + "by managementSecurityFilterChain and not by this chain; secure channel "
                        + "required: {}",
                this.apiDocsPublished ? ", plus the interface description" : "",
                TransactionRoute.enforcementPatternsFor(Gating.AUTHENTICATED).size(),
                JwtTokenProvider.ADMIN_AUTHORITY,
                JwtTokenProvider.USER_AUTHORITY,
                ADMIN_PATH_PREFIX,
                BATCH_CONTROL_PATH_PREFIX,
                JwtTokenProvider.ADMIN_AUTHORITY,
                API_PATH_PREFIX,
                API_PATH_PREFIX,
                this.managementBasePath,
                this.requireHttps);
        // The census the rules were derived from, recorded once at start-up so that an operator reading a
        // log can reconcile the delivered gate against the resource definitions without reading this file.
        LOG.info("Route-to-role table: {} registered transaction definitions; administrative: {}, "
                        + "anonymous: {}, ordinary: {}, with no implemented bound program: {}; ordinary "
                        + "addresses granted individually: {}",
                TransactionRoute.registeredTransactions().size(),
                TransactionRoute.withGating(Gating.ADMINISTRATIVE).size(),
                TransactionRoute.withGating(Gating.ANONYMOUS).size(),
                TransactionRoute.withGating(Gating.AUTHENTICATED).size(),
                TransactionRoute.registeredTransactions().stream()
                        .filter(route -> !route.isBoundProgramImplemented())
                        .count(),
                TransactionRoute.enforcementPatternsFor(Gating.AUTHENTICATED).size());
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
     * <p>The strength this bean is built at is {@link CredentialDigestService#HASHING_STRENGTH}, read
     * through {@link #PASSWORD_HASHING_STRENGTH}. That service is the module's declared authority for the
     * stored form of a credential and holds the module's other encoder; both are built from that one
     * constant, so the two cannot be at different strengths and the module has one hashing policy in
     * fact and not only in intent.</p>
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
                    RefusalBodyRenderer.AUTHENTICATION_REQUIRED_MESSAGE);
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
                        RefusalBodyRenderer.ACCESS_DENIED_MESSAGE);
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
        response.getOutputStream()
                .write(this.refusalBodyRenderer.renderRefusal(message).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Entitlement this module requires of a caller who reaches a registered transaction's surface, and the
     * one pattern the chain installs to enforce it.
     *
     * <p>The patterns belong to the entitlement rather than to the individual transaction, because that is
     * how the surface is actually arranged. All five administrative transactions live beneath one prefix
     * and are gated by one rule, so a route added beneath that prefix is administrator-only from the moment
     * it exists rather than from the moment somebody remembers to add a rule for it - and naming a pattern
     * per administrative transaction would mean inventing five addresses that no request handler has
     * claimed, which is worse than no rule because it reads as though something were being enforced.</p>
     *
     * <p><strong>The ordinary entitlement is the one case where the addresses do exist and are claimed, so
     * it names them.</strong> Enforcing it by one rule over the API root would be broader than the
     * delivered surface: such a rule admits an address no controller serves and pre-admits whatever is
     * mapped beneath the root next. It carries the eleven ordinary addresses themselves, and the chain
     * closes with a refusal over the root, so breadth is not the price of not forgetting a route -
     * forgetting one makes it unreachable rather than open, and the delivered-surface oracle fails the
     * build when the two disagree.</p>
     *
     * <p>Several patterns per entitlement rather than one, therefore, and the chain installs each of them.
     * A single pattern was enough only while the ordinary entitlement was a region.</p>
     */
    public enum Gating {

        /**
         * Reachable without presenting a credential.
         *
         * <p>Enforced by one rule naming {@link SecurityConfig#SIGN_ON_PATH}. Exactly one registered
         * transaction carries this entitlement, and it is the sign-on transaction - the route that issues
         * credentials, which could never be reached if it required one.</p>
         */
        ANONYMOUS(List.of(SIGN_ON_PATH)),

        /**
         * Reachable by a caller carrying either sign-on authority, whichever user type that identity
         * carries, and by no other identity.
         *
         * <p>This is the entitlement the estate gives every non-administrative transaction, because the
         * sign-on program's alternative branch is unconditional - see {@code app/cbl/COSGN00C.cbl}
         * L235.</p>
         *
         * <p><strong>Enforced by one rule per ordinary address - the eleven of
         * {@link ApiRoutePaths#ORDINARY_ROUTE_PATHS} - each matching either
         * {@link JwtTokenProvider#ADMIN_AUTHORITY} or {@link JwtTokenProvider#USER_AUTHORITY}.</strong>
         * This entitlement first named no pattern at all and was answered by the chain's closing
         * {@code anyRequest().authenticated()} rule. That rule asks only whether an identity was
         * established, which is a weaker question than the estate asks: the estate declares two user types
         * and both are sign-on records, so an authority that is neither would have inherited every
         * ordinary business route by default. Naming the two authorities that may pass makes a third one
         * refused because nothing admitted it.</p>
         *
         * <p><strong>Then the pattern was the whole API root, and that was still wider than the surface it
         * protected.</strong> A rule over the root admits every address beneath it, including the ones no
         * controller serves: a caller holding an ordinary token was authorized for
         * {@code /api/anything-at-all} and separated from a real route only by the dispatcher's own
         * not-found answer, which is authorization deciding nothing and the router deciding everything. It
         * also pre-admitted whatever was mapped beneath the root next, before anybody had decided who
         * should reach it.</p>
         *
         * <p><strong>Why enumerating them is now the safe direction rather than the fragile one.</strong>
         * The objection to enumeration was that reclassifying the last ordinary transaction, or adding a
         * route and forgetting to list it, would withdraw the rule and let the closing catch-all widen the
         * surface. That objection is answered by the chain rather than argued with: after these eleven
         * rules the chain refuses everything else beneath the root outright, so a withdrawn or forgotten
         * grant produces a refusal and not an admission. The forgetting is caught before it ships as well
         * - {@code api.DeliveredApiSurfaceOracleTest} enumerates the router's own mappings, requires them
         * to equal an independently written literal table, and requires the ordinary entries of that table
         * to equal this list exactly.</p>
         */
        AUTHENTICATED(ApiRoutePaths.ORDINARY_ROUTE_PATHS),

        /**
         * Reachable only by a caller whose identity carries the administrative authority.
         *
         * <p>Enforced by one rule over {@link SecurityConfig#ADMIN_PATH_PREFIX} and everything beneath it,
         * matching {@link JwtTokenProvider#ADMIN_AUTHORITY}. Exactly five registered transactions carry
         * this entitlement.</p>
         */
        ADMINISTRATIVE(List.of(ADMIN_PATH_PREFIX + ANY_DESCENDANT));

        /**
         * Path patterns the chain installs for this entitlement. Never empty, and no element blank.
         *
         * <p>Every value is assembled from the published path constants above rather than written out, so
         * an enumeration constant here cannot drift from the address a controller binds or from the rule
         * the chain installs.</p>
         *
         * This field is plural because an entitlement enforced by several rules is the arrangement, and the
         * field is the arrangement's shape rather than a simplification a reader has to unpick. A single
         * {@code String} could not express it, and a {@code String} permitted to be empty - meaning "no
         * dedicated rule, answered by the closing catch-all" - could not express the ordinary entitlement
         * at all, because that entitlement carries the eleven ordinary addresses rather than the region
         * containing them.</p>
         */
        private final List<String> enforcementPatterns;

        /**
         * @param enforcementPatterns path patterns the chain installs for this entitlement; must name at
         *                            least one, because an entitlement without a rule of its own is
         *                            enforced by whatever the closing rules happen to say, and no element
         *                            may be blank, because a blank pattern is a rule that matches
         *                            everything or nothing depending on the framework's reading of it
         * @throws IllegalArgumentException if {@code enforcementPatterns} is empty or holds a blank
         *                                  element
         * @throws NullPointerException     if {@code enforcementPatterns} or any element is {@code null}
         */
        Gating(final List<String> enforcementPatterns) {
            final List<String> declared = List.copyOf(Objects.requireNonNull(enforcementPatterns,
                    "every entitlement must name the path patterns that enforce it"));
            if (declared.isEmpty()) {
                throw new IllegalArgumentException(
                        "every entitlement must name at least one path pattern that enforces it");
            }
            for (final String pattern : declared) {
                if (pattern.isBlank()) {
                    throw new IllegalArgumentException(
                            "an entitlement may not name a blank path pattern");
                }
            }
            this.enforcementPatterns = declared;
        }

        /**
         * The path patterns the chain installs for this entitlement.
         *
         * @return the patterns, in declaration order; never {@code null}, never empty, and never holding a
         *         blank element
         */
        public List<String> enforcementPatterns() {
            return this.enforcementPatterns;
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
         * contributes the patterns the entitlement names, and a pattern already contributed is not
         * contributed twice - so the five administrative entries yield one rule between them, and the
         * twelve ordinary entries yield the eleven ordinary addresses between them, which is the
         * arrangement rather than a simplification of it. An entitlement that no registered transaction
         * carries contributes nothing and therefore installs no rule, which is why reclassifying the
         * sign-on entry withdraws the anonymous permit rather than leaving it standing - and why a
         * withdrawn ordinary grant leaves its address to the chain's closing refusal over the API root
         * rather than to a rule that would admit any identity.</p>
         *
         * @param gating the entitlement whose rules are being installed
         * @return an unmodifiable list of the distinct patterns to install, in the order the entries
         *         contributing them declare them; empty only when no registered transaction carries the
         *         entitlement, since every entitlement names at least one pattern
         * @throws NullPointerException if {@code gating} is {@code null}, screened by
         *                              {@link #withGating(Gating)} rather than screened twice
         */
        public static List<String> enforcementPatternsFor(final Gating gating) {
            final List<String> patterns = new ArrayList<>();
            for (final TransactionRoute route : withGating(gating)) {
                for (final String pattern : route.gating.enforcementPatterns()) {
                    if (!patterns.contains(pattern)) {
                        patterns.add(pattern);
                    }
                }
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
     * bearer credential is present, verifies, <em>and still names the record it was minted from</em>, the
     * identity it names is established for the remainder of the request; in every other case - absent,
     * malformed, wrongly signed, expired, issued elsewhere, carrying a user type this estate does not
     * define, or naming a record that has since been deleted, demoted, promoted or had its credential
     * set - the request simply continues unauthenticated and the authorization rules decide it. That
     * division is deliberate: this filter's job is to establish what it can prove, and refusing is the
     * chain's job, so there is exactly one place a refusal is shaped.</p>
     *
     * <p><strong>Verifying and establishing are two questions, and both are asked.</strong> A verified
     * signature proves this module minted the token and that its window is open; it proves nothing about
     * whether the two facts inside it are still true. Trusting the signature alone is what left a
     * demotion, a deletion and a credential reset with no effect until the token expired, so
     * {@code JwtTokenProvider.namesCurrentState} is consulted before an identity is established and a
     * token that no longer describes its record establishes nothing. The cost is one keyed read of the
     * credential master per authenticated request, which is the honest price of revoking at once with no
     * session store to consult and nothing cached - and it is only paid when a bearer credential is
     * actually presented.</p>
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
                    // Both conditions, in this order: a token whose type cannot be read has an unknown
                    // entitlement, and a token that no longer names its record has a revoked one. Neither
                    // establishes anything, and the record is not read at all for the first.
                    if (userType != null && this.tokenProvider.namesCurrentState(verified)) {
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

    /**
     * Establishes the operator identity on the management chain, and only there.
     *
     * <h2>Why this is a separate filter from the sign-on one</h2>
     *
     * <p>The two credentials are different kinds of thing and are verified differently. A sign-on token is
     * a signed assertion about a user of the application, carrying a subject, a user type and a
     * fingerprint of the record it was minted from, and it is verified by checking the signature and
     * re-reading that record. An operator credential asserts nothing about anybody: it is a shared machine
     * secret a collector presents, and verifying it is a comparison. Running both filters on one chain
     * would let a sign-on token establish an identity on the management surface - refused a moment later
     * by the authority rule, but by a route that invites the belief that a cardholder's token is a
     * management credential of insufficient rank. It is not a management credential at all.
     *
     * <p>The comparison is {@link MessageDigest#isEqual(byte[], byte[])} rather than
     * {@link String#equals(Object)}. That is not decoration: string comparison returns at the first
     * differing character, so its duration reveals how much of a guessed prefix was correct, and a shared
     * secret compared that way is guessable one character at a time by an attacker who can time the
     * response. The framework method is the constant-time comparison for exactly this case.
     *
     * <p>Nothing about a presented credential is logged, retained on the authentication, or reflected in a
     * response - not on success, and not on failure. A failure simply establishes nothing, and the chain's
     * authorization rule then refuses the request, which is what produces the 401.
     *
     * <h2>What a refusal now leaves behind, and what it deliberately does not</h2>
     *
     * <p>Until review, a wrong credential left no trace at all: no meter moved and nothing was logged, so a
     * deployment being probed looked exactly like a deployment nobody was probing. Every presentation now
     * increments {@value #MANAGEMENT_AUTHENTICATION_METER} under an outcome of established, refused or
     * unconfigured - three series, no caller-derived tag - and a run of consecutive refusals raises a
     * warning once per {@value #MANAGEMENT_REFUSAL_REPORT_INTERVAL} so that sustained probing is visible
     * without a log line per attempt. The warning names the count and nothing else; it names no header, no
     * presented value, no path and no caller.
     *
     * <p><strong>The bound is on the diagnostic and not on the authentication, and that is a decision
     * rather than an omission.</strong> Locking the surface after a threshold of failures would let any
     * unauthenticated caller take this deployment's metrics, exposition and management endpoints away from
     * its collector by presenting wrong credentials - a denial of service with no recovery path, since
     * there is one shared secret and no second factor to fall back to. Keying a lockout by caller instead
     * would mean unbounded per-caller state driven by an unauthenticated header, which is the shape this
     * module has already refused elsewhere. What makes guessing infeasible here is the credential's own
     * strength, held to a floor of {@value #MANAGEMENT_TOKEN_MINIMUM_BYTES} bytes under the production
     * profile; what makes an attempt visible is the meter. See {@code docs/decision-log.md} entry DL-312.
     */
    private static final class ManagementTokenAuthenticationFilter extends OncePerRequestFilter {

        /** The configured operator credential; empty when no operator identity exists. */
        private final byte[] expected;

        /** Where an outcome is counted, or {@code null} where no registry is published. */
        private final MeterRegistry meterRegistry;

        /**
         * Consecutive refusals since the last established presentation.
         *
         * <p>One counter for the whole surface rather than one per caller: the credential is a single
         * shared secret, so a per-caller breakdown would be unbounded state keyed by an unauthenticated
         * header and would say nothing the total does not.
         */
        private final AtomicLong consecutiveRefusals = new AtomicLong();

        /**
         * Creates the filter over the configured credential.
         *
         * @param managementToken configured operator credential, possibly empty
         * @param meterRegistry   where outcomes are counted; absent in a slice that publishes no registry,
         *                        in which case the outcomes are simply not counted and nothing else changes
         */
        private ManagementTokenAuthenticationFilter(final String managementToken,
                final MeterRegistry meterRegistry) {
            this.expected = managementToken.getBytes(StandardCharsets.UTF_8);
            this.meterRegistry = meterRegistry;
        }

        /**
         * Counts one outcome, when there is somewhere to count it.
         *
         * @param outcome the outcome tag value
         */
        private void record(final String outcome) {
            if (this.meterRegistry != null) {
                this.meterRegistry.counter(MANAGEMENT_AUTHENTICATION_METER,
                        MANAGEMENT_OUTCOME_TAG, outcome).increment();
            }
        }

        /**
         * Records a refusal and reports a sustained run of them at a bounded rate.
         *
         * @param outcome whether the credential was wrong or no identity is configured at all
         */
        private void refuse(final String outcome) {
            record(outcome);
            final long consecutive = this.consecutiveRefusals.incrementAndGet();
            if (consecutive % MANAGEMENT_REFUSAL_REPORT_INTERVAL == 0) {
                LOG.warn("Management credential refused {} times consecutively; nothing about a presented"
                        + " credential is recorded, and the surface stays open to the correct one",
                        Long.valueOf(consecutive));
            }
        }

        /**
         * Establishes the operator identity when the request carries the configured credential.
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
            final boolean presentedCredential = header != null
                    && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length());
            // An unconfigured credential matches nothing, including an empty presented one. Checked here
            // rather than left to the comparison, because two empty arrays ARE equal and that would turn
            // the fail-closed state into a surface any caller could enter by presenting "Bearer ".
            if (this.expected.length == 0) {
                // Counted only when something was actually presented: a request carrying no credential at
                // all against a surface with no identity configured is the ordinary probe traffic, and
                // counting it would bury the presentations that mean something.
                if (presentedCredential) {
                    refuse(MANAGEMENT_OUTCOME_UNCONFIGURED);
                }
            } else if (presentedCredential) {
                final byte[] presented = header.substring(BEARER_PREFIX.length()).trim()
                        .getBytes(StandardCharsets.UTF_8);
                if (MessageDigest.isEqual(this.expected, presented)) {
                    establishOperator();
                    record(MANAGEMENT_OUTCOME_ESTABLISHED);
                    this.consecutiveRefusals.set(0L);
                } else {
                    refuse(MANAGEMENT_OUTCOME_REFUSED);
                }
            }
            filterChain.doFilter(request, response);
        }

        /** Establishes the operator identity in a context of its own, retaining no credential. */
        private static void establishOperator() {
            final PreAuthenticatedAuthenticationToken authentication =
                    new PreAuthenticatedAuthenticationToken(MANAGEMENT_PRINCIPAL, null,
                            List.of(new SimpleGrantedAuthority(MANAGEMENT_AUTHORITY)));
            final SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        }
    }
}
