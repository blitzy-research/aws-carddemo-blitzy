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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.api.BatchJobController;
import com.carddemo.api.GlobalExceptionHandler;
import com.carddemo.api.JsonRefusalBodyRenderer;
import com.carddemo.api.ModuleErrorController;
import com.carddemo.domain.enums.UserType;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.CredentialDigestService;
import com.carddemo.service.SignOnStateService;
import com.carddemo.support.InMemoryCredentialMaster;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Asserts what the request-authorization boundary actually answers, by exercising the real filter chain
 * and reading real response statuses.
 *
 * <p><strong>Every assertion below is a response status or a response body, never a reading of the
 * configuration that produced it.</strong> That distinction is the point: a test that inspected the rules
 * would agree with a chain that had been assembled in the wrong order, and rule order is precisely what
 * decides whether a management endpoint added to a profile's exposure list becomes anonymous. The chain is
 * obtained from a real context and driven through real requests, so the order is exercised rather than
 * described. The probe endpoints exist only to give each rule a reachable target: without a handler, a
 * permitted path could only be shown to be "not refused", which cannot be told apart from a path that is
 * missing, whereas with one a permitted path answers {@code 200} and the distinction is unambiguous.</p>
 *
 * <p>No real credential appears in this class, and no value any profile ships is restated here. Every value
 * handed to the hasher below is generated in the test, as is every piece of signing material any slice
 * needs.</p>
 *
 * <p><strong>Subject.</strong> The class under test is {@link SecurityConfig}, the module's whole
 * route-to-role table and its only decision about what may be reached without a credential. What this file
 * owns is that table, the credential hasher, the session and cross-site posture, the reachability of the two
 * management endpoints orchestration depends on, and the ownership of the bearer-token settings. Everything
 * else that touches sign-on is owned elsewhere and is deliberately not restated here - see the ownership
 * paragraph below.</p>
 *
 * <p><strong>The table derives from eighteen transaction definitions, counted rather than assumed.</strong>
 * {@code app/csd/CARDDEMO.CSD} registers eighteen {@code DEFINE TRANSACTION} entries, each naming exactly
 * one program, alongside seventeen mapsets, eighteen program definitions and one transient-data queue. The
 * eighteen identifiers are written out literally in {@link RouteToRoleTable} as an independent oracle: no
 * expected value in this file is obtained by asking the class under test what it holds, so a nineteenth
 * invented entry, a missing entry, or an entry quietly promoted to the administrative set all fail. A
 * re-verification of that census must match {@code DEFINE X(} rather than an anchored {@code ^DEFINE},
 * because the file indents every definition by one column and an anchored search silently returns none.</p>
 *
 * <p><strong>The transaction manager enforced nothing, so the gate this file asserts is the
 * application-level one.</strong> All eighteen definition blocks end {@code RESSEC(NO) CMDSEC(NO)} -
 * resource security and command security both disabled - so the estate's administrative gate rested entirely
 * on the two menu catalogues ({@code app/cpy/COMEN02Y.cpy}, {@code app/cpy/COADM02Y.cpy}) and the
 * one-character user-type test at {@code app/cbl/COSGN00C.cbl} L230, whose alternative at L235 is
 * unconditional. What this file asserts is a faithful reproduction of that application-level gate, and the
 * framework <em>enforcing</em> it is a strict improvement over the legacy posture rather than a behavioural
 * change. The same reading applies to the data layer, where every application file was defined
 * {@code READINTEG(UNCOMMITTED) RECOVERY(NONE) JOURNAL(NO)} with correctness resting on record locking plus
 * before-and-after image comparison, so read-committed isolation plus an optimistic version column is also
 * strictly stronger. Both improvements are recorded in {@code docs/decision-log.md} so that a reviewer does
 * not mistake a stronger posture for a regression.</p>
 *
 * <p><strong>Anomaly register entry 3: one definition binds a program that has no source member.</strong>
 * The developer transaction {@code CDV1} names a program definition for which no member exists anywhere in
 * {@code app/cbl}, which was established by looking rather than assumed. The table keeps the entry, because
 * an inventory that quietly drops its own anomaly cannot be audited, and this file asserts both halves of
 * the consequence: the entry is present, and no type is declared for that program anywhere in the module.
 * The references the program name has in production sources document the anomaly rather than implementing
 * it.</p>
 *
 * <p><strong>The credential hasher is the migration's one documented parity exception.</strong> The sign-on
 * record is exactly eighty bytes - identifier eight, first name twenty, last name twenty, credential eight,
 * user type one, filler twenty-three at {@code app/cpy/CSUSR01Y.cpy} L17-L23, corroborated by the key length
 * and record width in {@code app/jcl/DUSRSECJ.jcl} - and the sign-on program compares the stored credential
 * against the submission as equal text at {@code app/cbl/COSGN00C.cbl} L223. Reproducing that comparison
 * would be faithful and would breach the requirement that no credential be hardcoded, so the value is hashed
 * and the stored column widened from eight characters to sixty, which is the single intentional width change
 * in the eleven-entity schema. The column itself is asserted by the migration and repository tiers, not here.
 * The provisioning job seeds ten identities, five administrative and five standard; those identifiers, names
 * and types are non-secret metadata, their rows belong to {@code FlywayConfigTest}, and the shared cleartext
 * credential they carry appears nowhere in this file, in any name, message or comment.</p>
 *
 * <p><strong>Statelessness is what the estate's conversational re-arm becomes.</strong> The legacy tier
 * carried a one-hundred-and-sixty-byte communication area across pseudo-conversational turns
 * ({@code app/cpy/COCOM01Y.cpy} L19, textually included by all seventeen online programs) and re-armed it on
 * return - nineteen re-arms across those seventeen programs, plus twenty-five program-to-program transfers.
 * The replacement is a stateless bearer token plus a navigation transfer object the client echoes back, with
 * no server-side forwarding, so a server session would reintroduce precisely the state the migration
 * removed. Cross-site request protection is disabled as a consequence of that, not as a relaxation of it:
 * the attack needs ambient authority, and a credential a caller must place in a header deliberately is not
 * ambient. Both claims are asserted below by driving requests rather than by reading configuration.</p>
 *
 * <p><strong>The management base path stays {@code /actuator} and the security rules follow it.</strong> The
 * image health check in {@code carddemo-java/Dockerfile}, the Compose dependency condition and
 * {@code carddemo-java/config/prometheus/prometheus.yml} all resolve that path literally, so a rule that
 * blocked it would break container orchestration and metric collection together. This file asserts that the
 * two endpoints those consumers depend on are reachable and that neither requires an administrative
 * authority; which endpoints a profile <em>publishes</em> is asserted by {@code ObservabilityConfigTest},
 * as is production's withholding of the environment, bean, configuration-property, heap-dump, thread-dump,
 * logger and request-mapping endpoints and its restriction of health detail to an authorised principal.</p>
 *
 * <p><strong>The bearer-token settings have exactly one registering home, and it is the class under
 * test.</strong> {@link SettingsOwnership} proves it by difference: the settings bean is present in a slice
 * that includes {@link SecurityConfig} and absent from an otherwise identical slice that does not.
 * {@code AwsProperties} is registered by {@code AwsConfig} and must not appear here. Production resolves
 * every secret from the environment with no fallback - the token signing secret, the database address, user
 * and password, and the AWS region and access pair - so a missing value stops start-up instead of binding a
 * placeholder; the mechanical sweep of that file for defaulted placeholders is owned once, by
 * {@code JwtPropertiesTest}, and is deliberately not repeated here. No default value from any profile is
 * written into this file in any form.</p>
 *
 * <p><strong>Four behaviours of the sign-on program are contractual and are recorded here as metadata
 * only.</strong> They are implemented in the authentication service, and what this file asserts is that
 * nothing installed on the chain stands in front of them: no framework authentication-failure handler that
 * would rewrite an outcome, no default message source, no redirect. (1) Blank-field reporting is ordered
 * rather than aggregated - the blank-identifier condition at {@code app/cbl/COSGN00C.cbl} L120 precedes the
 * blank-credential condition at L125, and a submission with both blank reports only the first. (2) Both
 * submitted values are folded to upper case unconditionally at L132-L136 through a fixed twenty-six
 * character substitution rather than a language-sensitive fold, so the subject a token carries is the folded
 * eight-character identifier. (3) The entitlement split at L230-L240 has an unconditional alternative and no
 * third branch, so user-type resolution is total and never fails on a code the estate does not declare.
 * (4) The outcomes are asymmetric - a credential that does not match at L242 does not raise the general
 * error flag, while an unknown user at L248 and an unclassified failure at L253 both do. The seven message
 * texts those paths emit belong to the message catalogue service and are asserted in its own test and in the
 * authentication service's; not one of them is reproduced here.</p>
 *
 * <p><strong>No thirteenth type is introduced into this package.</strong> There is no top-level
 * authentication filter, no route registry, no route-constant holder and no user-lookup implementation: the
 * request filter is a private nested type of the class under test, route values belong to the navigation
 * service, and user loading belongs to the authentication service over its repository.
 * {@link NoAdditionalConfigTypes} asserts that beside the behavioural proof that a nested filter is
 * installed and working.</p>
 *
 * <p><strong>Tier and mechanism.</strong> This file runs in the unit tier, which takes {@code *Test} and
 * excludes {@code *IT}, so it starts no container, creates no data source and contacts no database; the
 * chain is obtained from a {@code WebApplicationContextRunner} and driven with a standalone client. Both of
 * the preferred mechanisms are available - the table is directly addressable as published immutable
 * structure, and the chain is constructible in this tier - so <strong>nothing is migrated to the
 * integration tier</strong>. Sign-on over a real dispatcher, and the routing outcome for each user type,
 * belong to the {@code api} tier's own tests, where a real dispatcher and a real database belong.
 * Per-entry census detail for the table - each entry's bound program, its definition line, the four and
 * eight character widths, immutability and identifier resolution - is asserted once in
 * {@code SecurityConfigRouteTableTest}; this file asserts the partition contract the chain's rules are
 * actually derived from, and the two are complementary rather than duplicates.</p>
 *
 * <p><strong>Standards.</strong> The project's rules document states that no user rules were provided, which
 * was confirmed by reading it in full; that absence lowers nothing, and the work is held instead to the
 * enterprise standards the plan substitutes. No latency, throughput, capacity or availability figure is
 * asserted anywhere in this file, and none is implied: the hashing cost factor is a resistance parameter,
 * not a service level, and it is never timed.</p>
 */
@DisplayName("Request authorization: what the filter chain actually answers, asserted by real status")
class SecurityConfigTest {

    /** Source of every generated value in this class. Seeded by the platform, never by a fixture. */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Width of the signing material a slice generates for itself.
     *
     * <p>Chosen so the encoded form clears the fixed signature algorithm's key-length floor with room to
     * spare. It is a property of that algorithm, not a tuning figure and not a service level.</p>
     */
    private static final int SIGNING_MATERIAL_BYTES = 48;

    /** Width of a throwaway value handed to the hasher. */
    private static final int THROWAWAY_VALUE_BYTES = 12;

    /**
     * Signing material for the chain under test.
     *
     * <p>Generated at class initialization rather than written down. Nothing about this file needs a
     * particular value, and a fixture that wrote one would put a signing-secret literal into version
     * control - which is exactly the shape of the finding this module exists to avoid, whether or not the
     * value was ever real. Nothing logs it, nothing asserts it, and it does not outlive the test run.</p>
     */
    private static final String SECRET = freshSigningMaterial();

    /**
     * Signing material this chain trusts nothing signed with, used to mint a token from elsewhere.
     *
     * <p>Generated for the same reason, and separately, so a token signed with it cannot verify against the
     * chain's own material.</p>
     */
    private static final String FOREIGN_SECRET = freshSigningMaterial();

    /** Issuer the chain's provider claims and requires. */
    private static final String ISSUER = "carddemo-java";

    /** Base path the probe management endpoints are published beneath. */
    private static final String MANAGEMENT_BASE = "/actuator";

    /**
     * The operator credential these slices configure, which is a fixture and not a secret of anything.
     *
     * <p>Long and obviously inert on purpose: it is compared byte for byte, so a short value would still
     * pass and would read as though brevity were acceptable for the real thing.
     */
    private static final String OPERATOR_TOKEN =
            "unit-test-operator-credential-not-a-real-credential-0123456789";

    /** Address the probe interface description is published at. */
    private static final String API_DOCS = "/v3/api-docs";

    /** An ordinary protected business route, beneath no special prefix. */
    private static final String ORDINARY_ROUTE = "/api/accounts/00000000001";

    /**
     * Response header refusing content-type sniffing.
     *
     * <p>Named here rather than at each assertion because the framework's own {@code HttpHeaders} does not
     * publish a constant for it, and a header name spelled at four call sites is a header name that can be
     * misspelled at one of them - which would make the assertion pass by reading nothing.
     */
    private static final String CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options";

    /** Response header stating the framing policy, likewise unpublished by {@code HttpHeaders}. */
    private static final String FRAME_OPTIONS_HEADER = "X-Frame-Options";

    /** Response header stating the transport-security policy, likewise unpublished. */
    private static final String TRANSPORT_SECURITY_HEADER = "Strict-Transport-Security";

    /** An administrative route, beneath the administrative prefix. */
    private static final String ADMIN_ROUTE = SecurityConfig.ADMIN_PATH_PREFIX + "/users";

    /**
     * A batch control route, beneath the batch control path and deliberately NOT beneath the
     * administrative prefix.
     *
     * <p>That placement is what these assertions turn on: batch control was job submission in the estate
     * rather than one of the eighteen registered transactions, so it carries the administrative entitlement
     * under its own rule instead of by sitting under a prefix that stands for five transactions.</p>
     */
    private static final String BATCH_CONTROL_ROUTE = "/api/batch/jobs/postTransactionJob/launch";

    /** A protected route that commits its answer before failing authorization. */
    private static final String COMMITTED_ROUTE = "/api/accounts/commit-then-deny";

    /**
     * The batch-control launch operation, addressed exactly as the controller maps it.
     *
     * <p>Assembled from the published constants rather than written out, so a moved mapping moves this
     * assertion with it instead of leaving the assertion probing an address nothing claims.
     */
    private static final String BATCH_LAUNCH_ROUTE =
            BatchJobController.BATCH_JOBS_PATH + "/postTransactionJob/launch";

    /** The batch-control status operation, addressed the same way. */
    private static final String BATCH_STATUS_ROUTE =
            BatchJobController.BATCH_JOBS_PATH + "/executions/4271";

    /**
     * The administrative operator the fixture credential master holds.
     *
     * <p>Two identifiers rather than one, because the record now decides what a token may claim: a token
     * naming the administrative role is only established while the record it was minted from carries the
     * administrative code, so a single record could not serve both roles. Eight characters, the fixed width
     * of the record's key.</p>
     */
    private static final String ADMIN_USER_ID = "TESTADM1";

    /** The standard-user operator the fixture credential master holds, at the same fixed width. */
    private static final String STANDARD_USER_ID = "TESTUSR1";

    /**
     * The credential master the chain reads when it decides whether a presented token still names its
     * record.
     *
     * <p>Shared and re-seeded before each test rather than built per context, so that a test can change
     * the record between two requests through the same chain - which is the only way to observe that a
     * demotion, a deletion or a credential reset takes effect at once.</p>
     */
    private static final InMemoryCredentialMaster CREDENTIAL_MASTER = new InMemoryCredentialMaster();

    /** Instant the fixed clock reports, so token windows are exact. */
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    /** The framework's own login address, which this module must not publish a page at. */
    private static final String FRAMEWORK_LOGIN_PATH = "/login";

    /** Credential scheme a browser prompt would name, and which no answer here may name. */
    private static final String BROWSER_PROMPT_SCHEME = "Basic";

    /**
     * The eighteen transaction identifiers {@code app/csd/CARDDEMO.CSD} registers, in ascending order.
     *
     * <p>Written out literally, which is the point: this is an independent oracle, so nothing below asks the
     * table what it contains in order to decide what it ought to contain.</p>
     */
    private static final Set<String> REGISTERED_TRANSACTION_IDS = Set.of(
            "CA00", "CAUP", "CAVW", "CB00", "CC00", "CCDL", "CCLI", "CCUP", "CDV1",
            "CM00", "CR00", "CT00", "CT01", "CT02", "CU00", "CU01", "CU02", "CU03");

    /**
     * The five identifiers an administrative authority is required for: the administrative menu and the four
     * sign-on-record maintenance transactions.
     */
    private static final Set<String> ADMINISTRATIVE_TRANSACTION_IDS =
            Set.of("CA00", "CU00", "CU01", "CU02", "CU03");

    /** The one identifier reachable without a credential, because it is the one that issues them. */
    private static final Set<String> ANONYMOUS_TRANSACTION_IDS = Set.of("CC00");

    /** The remaining twelve identifiers, reachable by any caller that has established an identity. */
    private static final Set<String> ORDINARY_TRANSACTION_IDS = Set.of(
            "CAUP", "CAVW", "CB00", "CCDL", "CCLI", "CCUP", "CDV1",
            "CM00", "CR00", "CT00", "CT01", "CT02");

    /** How many transaction definitions the resource definition file registers. */
    private static final int REGISTERED_COUNT = 18;

    /** How many of those require an administrative authority. */
    private static final int ADMINISTRATIVE_COUNT = 5;

    /** How many are reachable without a credential. */
    private static final int ANONYMOUS_COUNT = 1;

    /** How many are reachable by any caller that has established an identity. */
    private static final int ORDINARY_COUNT = 12;

    /** The identifier whose definition binds a program with no source member - anomaly register entry 3. */
    private static final String DANGLING_TRANSACTION_ID = "CDV1";

    /** The program definition that has no source member anywhere in {@code app/cbl}. */
    private static final String DANGLING_PROGRAM_NAME = "COCRDSEC";

    /** Root of the module's production sources, relative to the directory a unit test runs in. */
    private static final Path PRODUCTION_SOURCE_ROOT = Path.of("src", "main", "java");

    /** The package this file mirrors, as a path beneath {@link #PRODUCTION_SOURCE_ROOT}. */
    private static final Path CONFIGURATION_PACKAGE =
            PRODUCTION_SOURCE_ROOT.resolve(Path.of("com", "carddemo", "config"));

    /** Source file of the settings record whose registering home is asserted below. */
    private static final Path TOKEN_SETTINGS_SOURCE =
            CONFIGURATION_PACKAGE.resolve("JwtProperties.java");

    /** Cost factor the stored digests must be produced at, read from the digest rather than from a field. */
    private static final int EXPECTED_HASHING_COST = 12;

    /** Cost factor as it appears in a digest, which states it as two characters. */
    private static final String EXPECTED_HASHING_COST_FIELD = "12";

    /** Length of the digest the hasher produces, in characters. */
    private static final int DIGEST_LENGTH = 60;

    /** Number of dollar-delimited fields a digest carries, the first of which is empty. */
    private static final int DIGEST_FIELD_COUNT = 4;

    /** Index of the cost field once a digest is split on its delimiter. */
    private static final int DIGEST_COST_FIELD_INDEX = 2;

    /**
     * Variant markers a digest may begin with.
     *
     * <p>All three are accepted rather than one pinned, because the marker records which implementation
     * produced the digest and carries no security meaning; pinning one would fail on a library that had
     * changed its default without anything about the module having changed.</p>
     */
    private static final Set<String> ACCEPTED_DIGEST_PREFIXES = Set.of("$2a$", "$2b$", "$2y$");

    /** Marker a delegating hasher would use for a value stored as cleartext. */
    private static final String CLEARTEXT_MARKER = "{noop}";

    /** Splits a digest into its dollar-delimited fields. */
    private static final Pattern DIGEST_FIELD_DELIMITER = Pattern.compile("\\$");

    /** Matches a type declaration whose name begins with the dangling program's name, in any case. */
    private static final Pattern DANGLING_TYPE_DECLARATION = Pattern.compile(
            "\\b(class|interface|enum|record)\\s+" + DANGLING_PROGRAM_NAME,
            Pattern.CASE_INSENSITIVE);

    /** Clears any identity a request established, so no test can inherit another's. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Re-seeds the two fixture operators, so no test inherits another's record state. */
    @BeforeEach
    void seedCredentialMaster() {
        CREDENTIAL_MASTER.reset()
                .with(ADMIN_USER_ID, UserType.ADMIN.getCode())
                .with(STANDARD_USER_ID, UserType.USER.getCode());
    }

    /**
     * Produces signing material for a slice that needs some.
     *
     * <p>Generated rather than written down, so no signing-secret literal enters version control. The
     * encoded form is URL-safe and unpadded, which keeps it usable as a configuration value.</p>
     *
     * @return freshly generated material, comfortably longer than the signature algorithm's key floor
     */
    private static String freshSigningMaterial() {
        final byte[] material = new byte[SIGNING_MATERIAL_BYTES];
        RANDOM.nextBytes(material);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    /**
     * Produces a value to hand to the hasher.
     *
     * <p>Generated for each use, and prefixed so that anything which ever printed one is recognisable as a
     * fixture. No value any provisioning record carries is used, needed or named.</p>
     *
     * @return a throwaway value that is not a credential
     */
    private static String throwawayValue() {
        final byte[] material = new byte[THROWAWAY_VALUE_BYTES];
        RANDOM.nextBytes(material);
        return "TEST-" + Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    /**
     * The identifiers the table classifies under one entitlement.
     *
     * @param gating the entitlement to select
     * @return those identifiers, as a set so that set equality can be asserted against a literal
     */
    private static Set<String> identifiersGated(final SecurityConfig.Gating gating) {
        return Set.copyOf(SecurityConfig.TransactionRoute.withGating(gating).stream()
                .map(SecurityConfig.TransactionRoute::getTransactionId)
                .toList());
    }

    /** @return every identifier the table registers, as a set */
    private static Set<String> registeredIdentifiers() {
        return Set.copyOf(SecurityConfig.TransactionRoute.registeredTransactions().stream()
                .map(SecurityConfig.TransactionRoute::getTransactionId)
                .toList());
    }

    /**
     * Every production source file in the module.
     *
     * @return the file paths, ordered arbitrarily
     * @throws IOException if the source tree cannot be read
     */
    private static List<Path> productionSources() throws IOException {
        try (Stream<Path> tree = Files.walk(PRODUCTION_SOURCE_ROOT)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList();
        }
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
         * Stands in for the readiness group that includes the required AWS resources.
         *
         * @return a fixed body
         */
        @GetMapping(MANAGEMENT_BASE + "/health/readiness")
        String readiness() {
            return "readiness";
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
         * Stands in for a submission to the sign-on route.
         *
         * <p>A state-changing method is what a cross-site request forgery defence would intercept, so the
         * posture can only be asserted against a route that accepts one.</p>
         *
         * @return a fixed body
         */
        @PostMapping(SecurityConfig.SIGN_ON_PATH)
        String signOnSubmission() {
            return "sign-on-submission";
        }

        /**
         * Stands in for a submission to an ordinary protected route.
         *
         * @return a fixed body
         */
        @PostMapping(ORDINARY_ROUTE)
        String ordinarySubmission() {
            return "ordinary-submission";
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
         * Stands in for the batch-control launch operation.
         *
         * <p>A state-changing method, because launching a job is one, and the operation whose reachability
         * by an ordinary signed-on caller was the defect this nest asserts is closed.
         *
         * @return a fixed body
         */
        @PostMapping(BATCH_LAUNCH_ROUTE)
        String batchLaunch() {
            return "batch-launch";
        }

        /**
         * Stands in for the batch-control status operation.
         *
         * @return a fixed body
         */
        @GetMapping(BATCH_STATUS_ROUTE)
        String batchStatus() {
            return "batch-status";
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
        return runner(requireHttps, publishDocs, anonymousScrape, MANAGEMENT_BASE);
    }

    /**
     * Builds a runner carrying the chain, the token provider and the probe handlers, beneath a stated
     * management base path.
     *
     * <p>The base path is a parameter rather than a constant because the rules are derived from it: a chain
     * that had narrowed it to a literal would keep permitting the old address and start refusing the
     * configured one, and only a slice that moves it can tell the two apart.</p>
     *
     * @param requireHttps     whether the chain must require a secure channel
     * @param publishDocs      whether the running profile publishes the interface description
     * @param anonymousScrape  whether the running profile opens the metrics scrape endpoint to a collector
     *                         presenting no credential
     * @param managementBase   base path the management endpoints are published beneath
     * @return the configured runner
     */
    private static WebApplicationContextRunner runner(final boolean requireHttps,
            final boolean publishDocs, final boolean anonymousScrape, final String managementBase) {
        return new WebApplicationContextRunner()
                // The two security auto-configurations are present deliberately. They are what would
                // supply a generated user and a default permit-nothing-named chain if this module
                // supplied neither, so including them is the only way an assertion that they stood down
                // can mean anything. Without them, "no generated user exists" would be true because
                // nothing had offered one.
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class))
                .withUserConfiguration(SecurityConfig.class, JwtTokenProvider.class,
                        SignOnStateService.class, JsonRefusalBodyRenderer.class,
                        WebMvcConfig.class, FixedClockConfig.class)
                // WebMvcConfig's body-limit registration now counts a refusal, so this slice needs a
                // registry. Supplied because the runner registers no metrics auto-configuration,
                // whereas the running application always has one.
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(UserSecurityRepository.class, CREDENTIAL_MASTER::repository)
                .withPropertyValues(
                        JwtProperties.PREFIX + ".secret=" + SECRET,
                        JwtProperties.PREFIX + ".issuer=" + ISSUER,
                        JwtProperties.PREFIX + ".expiration=PT30M",
                        WebMvcConfig.CORS_ALLOWED_ORIGINS_PROPERTY + "=",
                        WebMvcConfig.MAX_REQUEST_BODY_SIZE_PROPERTY + "=64KB",
                        "carddemo.security.require-https=" + requireHttps,
                        "carddemo.security.anonymous-metrics-scrape=" + anonymousScrape,
                        SecurityConfig.MANAGEMENT_TOKEN_PROPERTY + "=" + OPERATOR_TOKEN,
                        "springdoc.api-docs.enabled=" + publishDocs,
                        "springdoc.api-docs.path=" + API_DOCS,
                        "management.endpoints.web.base-path=" + managementBase);
    }

    /**
     * A runner with NO operator credential configured, which is the fail-closed posture.
     *
     * <p>Distinct from every other runner because it is the only one that can tell the difference between
     * "the management surface requires an operator authority" and "the management surface requires an
     * operator authority that happens to be configured here". With no credential configured no identity
     * can hold the authority, so everything beyond the three anonymous probe paths must refuse - including
     * a caller presenting the value that would otherwise have worked.
     *
     * @return the configured runner
     */
    private static WebApplicationContextRunner withoutOperatorIdentity() {
        return runner(false, false, false)
                .withPropertyValues(SecurityConfig.MANAGEMENT_TOKEN_PROPERTY + "=");
    }

    /**
     * A runner carrying one specific machine credential, so a weak value's effect on startup can be read.
     *
     * @param  token the credential to configure
     * @return the configured runner
     */
    private static WebApplicationContextRunner withOperatorCredential(final String token) {
        return runner(false, false, false)
                .withPropertyValues(SecurityConfig.MANAGEMENT_TOKEN_PROPERTY + "=" + token);
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
     * Reads the cost factor a digest declares, out of the digest itself.
     *
     * <p>Read from the produced value rather than from any encoder's configuration, so that an encoder
     * built at one strength and hashing at another would be caught.</p>
     *
     * @param digest a BCrypt digest
     * @return the cost factor it declares
     */
    private static int costFactorOf(final String digest) {
        final String[] fields = DIGEST_FIELD_DELIMITER.split(digest);
        assertThat(fields)
                .as("a digest carries an empty leading field, a variant, a cost and the remainder")
                .hasSize(DIGEST_FIELD_COUNT);
        return Integer.parseInt(fields[DIGEST_COST_FIELD_INDEX]);
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
     * Builds a client that drives the context's real chain with an identity supplied by the caller rather
     * than minted from a token.
     *
     * <p><strong>Why a second client exists.</strong> The chain's own bearer filter can only ever establish
     * one of the two authorities a sign-on record can carry, because it derives the authority from the
     * record's user type and the estate declares exactly two. That makes it impossible to ask the one
     * question the authorization rule exists to answer - what the chain does with an identity carrying
     * something else - through a token, and the question is not hypothetical: this process already mints a
     * third authority for the management surface. This client presents the identity directly, which is
     * exactly the condition the rule defends against and the only way to observe the rule's answer rather
     * than the filter's.
     *
     * @param context a started context carrying the chain
     * @return a client whose requests pass through the chain and may carry a supplied identity
     */
    private static MockMvc identityClientFor(final AssertableWebApplicationContext context) {
        final Filter chain = context.getBean("springSecurityFilterChain", Filter.class);
        return MockMvcBuilders.standaloneSetup(new ProbeEndpoints())
                .apply(SecurityMockMvcConfigurers.springSecurity(chain))
                .build();
    }

    /**
     * An established identity carrying exactly the authorities named, and nothing else.
     *
     * @param authorities the authority values the identity holds; none at all is a legitimate case, and it
     *                    is the one bare {@code authenticated()} used to admit
     * @return an authenticated principal for the request post-processor
     */
    private static Authentication identityCarrying(final String... authorities) {
        return new TestingAuthenticationToken(STANDARD_USER_ID, null,
                Stream.of(authorities).map(SimpleGrantedAuthority::new).toList());
    }

    /**
     * Mints a token for the given user type using the context's own provider.
     *
     * @param context a started context
     * @param type    user type the token should carry
     * @return the credential value to present
     */
    private static String tokenFor(final AssertableWebApplicationContext context, final UserType type) {
        return context.getBean(JwtTokenProvider.class)
                .issue(identifierFor(type), type, type.getCode());
    }

    /**
     * Names the fixture operator whose record carries a given type.
     *
     * <p>The provider refuses to mint a role its record does not carry, so a token for a type has to be
     * minted for the identifier holding that type. Choosing here rather than at each call site keeps every
     * existing assertion reading as "a token for this type".</p>
     *
     * @param type the type the token should carry
     * @return the identifier of the fixture operator holding that type
     */
    private static String identifierFor(final UserType type) {
        return type.isAdmin() ? ADMIN_USER_ID : STANDARD_USER_ID;
    }

    /**
     * Mints a token that verifies against the chain's own signing material but carries a user-type code the
     * estate does not declare, or none at all.
     *
     * <p>The module's own provider cannot produce this, because it accepts only a declared user type - which
     * is why the token is assembled here instead. Everything else about it is exactly what the provider
     * would write: the same signature algorithm, the same secret, the issuer the verifier requires, and a
     * window around the same fixed instant the chain's clock reports. So the only reason the boundary can
     * refuse it is the claim under test.</p>
     *
     * @param roleCode the user-type code to carry, or {@code null} to omit the claim entirely
     * @return the compact serialized token
     */
    private static String tokenCarryingRole(final String roleCode) {
        final OctetSequenceKey signingKey =
                new OctetSequenceKey.Builder(SECRET.getBytes(StandardCharsets.UTF_8)).build();
        final JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(STANDARD_USER_ID)
                .issuedAt(NOW)
                .expiresAt(NOW.plus(Duration.ofMinutes(30)));
        if (roleCode != null) {
            claims.claim(JwtTokenProvider.ROLE_CLAIM, roleCode);
        }
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)))
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }

    @Nested
    @DisplayName("The surfaces a sibling file resolves literally")
    class OperationalSurfaces {

        @ParameterizedTest(name = "GET {0}")
        @ValueSource(strings = {
            MANAGEMENT_BASE + "/health",
            MANAGEMENT_BASE + "/health/liveness",
            MANAGEMENT_BASE + "/health/readiness" })
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
        @DisplayName("still answer the metrics scrape endpoint for a collector presenting the OPERATOR "
                + "credential where the profile does not open it, so closing anonymity does not "
                + "unpublish the endpoint and the performance gate keeps its data source")
        void answerTheScrapeEndpointForACredentialedCollector() throws Exception {
            closedScrape().run(context -> clientFor(context)
                    .perform(get(MANAGEMENT_BASE + "/prometheus").header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + OPERATOR_TOKEN))
                    .andExpect(result -> {
                        assertThat(result.getResponse().getStatus())
                                .as("a production collector authenticates with its scoped credential and "
                                        + "collects; that is the whole reason the endpoint stays in the "
                                        + "exposure list")
                                .isEqualTo(200);
                        assertThat(result.getResponse().getContentAsString()).isEqualTo("prometheus");
                    }));
        }

        @Test
        @DisplayName("refuse a collector presenting a credential that is close to the operator one, "
                + "because the comparison is byte for byte and not a prefix match")
        void refuseANearMissOperatorCredential() throws Exception {
            closedScrape().run(context -> clientFor(context)
                    .perform(get(MANAGEMENT_BASE + "/prometheus").header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + OPERATOR_TOKEN.substring(0, OPERATOR_TOKEN.length() - 1)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .isEqualTo(401)));
        }

        @ParameterizedTest(name = "GET {0} is refused when no operator identity is configured")
        @ValueSource(strings = {MANAGEMENT_BASE + "/prometheus", MANAGEMENT_BASE + "/metrics",
            MANAGEMENT_BASE + "/info"})
        @DisplayName("refuse the operational surface entirely when NO operator credential is configured, "
                + "so an unset setting is the fail-closed case rather than the permissive one")
        void refuseTheOperationalSurfaceWithoutAnOperatorIdentity(final String path) throws Exception {
            withoutOperatorIdentity().run(context -> {
                clientFor(context).perform(get(path))
                        .andExpect(result -> assertThat(result.getResponse().getStatus())
                                .as("a missing credential setting must never widen anything")
                                .isEqualTo(401));
                clientFor(context)
                        .perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + OPERATOR_TOKEN))
                        .andExpect(result -> assertThat(result.getResponse().getStatus())
                                .as("with nothing configured there is no credential to present, so even "
                                        + "the value that would otherwise work must be refused")
                                .isEqualTo(401));
            });
        }

        @Test
        @DisplayName("keep the probe paths anonymous even with no operator identity configured, so a "
                + "deployment that has not issued a collector credential still reports healthy")
        void keepTheProbesAnonymousWithoutAnOperatorIdentity() throws Exception {
            withoutOperatorIdentity().run(context -> clientFor(context)
                    .perform(get(MANAGEMENT_BASE + "/health"))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200)));
        }

        @Test
        @DisplayName("refuse an empty presented credential where no operator identity is configured, "
                + "because two empty values must not compare equal into an open surface")
        void refuseAnEmptyPresentedCredential() throws Exception {
            withoutOperatorIdentity().run(context -> clientFor(context)
                    .perform(get(MANAGEMENT_BASE + "/prometheus")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer "))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("an unconfigured credential matches nothing, INCLUDING an empty one")
                            .isEqualTo(401)));
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
    @DisplayName("The container's error dispatch, which the rules must not answer a second time")
    class TheContainerErrorDispatch {

        /**
         * Marks a request as the container's internal error dispatch rather than a client's request.
         *
         * @return a post-processor setting the dispatch type the container sets
         */
        private static org.springframework.test.web.servlet.request.RequestPostProcessor errorDispatch() {
            return request -> {
                request.setDispatcherType(DispatcherType.ERROR);
                return request;
            };
        }

        @Test
        @DisplayName("is permitted, so the status the framework already decided survives instead of being "
                + "overwritten with 401 - which is the whole of the reported defect")
        void theErrorDispatchIsPermitted() throws Exception {
            plainTransport().run(context -> {
                final MockMvc client = clientFor(context);

                // The dispatch carries no credential, and cannot: the bearer filter is a
                // once-per-request filter, so it does not run on an error dispatch and no authentication
                // is established. Before the permit the closing catch-all answered 401 here, and that 401
                // replaced the 405, 415, 406 or 404 the framework had already decided - which is exactly
                // what the QA report observed.
                client.perform(get(ORDINARY_ROUTE).with(errorDispatch()))
                        .andExpect(result -> {
                            assertThat(result.getResponse().getStatus())
                                    .as("the dispatch reaches a handler rather than being refused")
                                    .isEqualTo(200);
                            assertThat(result.getResponse().getContentAsString()).isEqualTo("ordinary");
                        });

                // The same path, the same absent credential, as an ordinary client request: still refused.
                // Asserting both in one slice is what shows the permit is keyed on the dispatch type and
                // not on a path, so it opens nothing a client can address.
                client.perform(get(ORDINARY_ROUTE))
                        .andExpect(result -> assertThat(result.getResponse().getStatus())
                                .as("an ordinary request dispatch is unaffected by the permit")
                                .isEqualTo(401));
            });
        }

        @Test
        @DisplayName("is permitted ahead of the administrative rules too, which is what makes the permit's "
                + "position first rather than merely present")
        void theErrorDispatchPrecedesTheEntitlementRules() throws Exception {
            // An error dispatch that happens to carry an administrative path must still be answered, or a
            // wrong method on an administrative route would report an entitlement failure instead of the
            // method refusal. A permit written after the entitlement rules would fail this while passing
            // the test above.
            plainTransport().run(context -> clientFor(context)
                    .perform(get(ADMIN_ROUTE).with(errorDispatch()))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200)));
        }

        @Test
        @DisplayName("does not make the error path itself reachable, because a client's request to it is an "
                + "ordinary dispatch and falls to the closing catch-all")
        void theErrorPathStaysAuthenticatedForAClient() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(ModuleErrorController.ERROR_PATH_DEFAULT))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("the permit is on the dispatch, so a client addressing the path gains "
                                    + "nothing from it")
                            .isEqualTo(401)));
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

        @Test
        @DisplayName("admit the batch control surface to an administrator's token, because starting a job "
                + "was job submission and only whoever held submission rights could do it")
        void admitBatchControlToAnAdministrator() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(post(BATCH_CONTROL_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenFor(context, UserType.ADMIN)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200)));
        }

        @Test
        @DisplayName("REFUSE the batch control surface to a standard user's token, which the closing "
                + "catch-all alone would have admitted - so this proves the rule of its own is reached")
        void refuseBatchControlToAStandardUser() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(post(BATCH_CONTROL_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenFor(context, UserType.USER)))
                    .andExpect(result -> {
                        assertThat(result.getResponse().getStatus())
                                .as("an ordinary cardholder identity must not be able to re-run posting, "
                                        + "accrual or statement generation over the whole estate")
                                .isEqualTo(403);
                        assertThat(result.getResponse().getContentAsString())
                                .doesNotContain("batch-control");
                    }));
        }

        @Test
        @DisplayName("refuse the batch control surface presenting no credential at all as unauthorized, "
                + "not forbidden")
        void refuseBatchControlWithoutACredential() throws Exception {
            plainTransport().run(context -> clientFor(context).perform(post(BATCH_CONTROL_ROUTE))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401)));
        }

        @Test
        @DisplayName("gate the batch control surface by a rule of its own rather than by the "
                + "administrative prefix, so the eighteen-transaction census stays honest")
        void gateBatchControlByARuleOfItsOwn() {
            assertThat(SecurityConfig.BATCH_CONTROL_PATH_PATTERN)
                    .as("folding this path under the administrative prefix would report batch control as "
                            + "a nineteenth registered transaction")
                    .doesNotStartWith(SecurityConfig.ADMIN_PATH_PREFIX)
                    .isEqualTo("/api/batch/jobs/**");
            assertThat(BATCH_CONTROL_ROUTE).startsWith("/api/batch/jobs/");
        }
    }

    @Nested
    @DisplayName("The business surface is an allow-list of the two sign-on authorities, not merely a "
            + "check that some identity exists")
    class BusinessSurfaceAllowList {

        @Test
        @DisplayName("names the API root as the region it governs, so a business route added later is "
                + "inside the allow-list from the moment it exists")
        void namesTheApiRootAsTheRegionItGoverns() {
            assertThat(SecurityConfig.API_PATH_PREFIX).isEqualTo("/api");
            assertThat(ORDINARY_ROUTE).startsWith(SecurityConfig.API_PATH_PREFIX + "/");
            assertThat(ADMIN_ROUTE).startsWith(SecurityConfig.API_PATH_PREFIX + "/");
            assertThat(BATCH_CONTROL_ROUTE).startsWith(SecurityConfig.API_PATH_PREFIX + "/");
            assertThat(SecurityConfig.SIGN_ON_PATH).startsWith(SecurityConfig.API_PATH_PREFIX + "/");
        }

        @Test
        @DisplayName("states the ordinary entitlement as a rule of its own rather than leaving it to the "
                + "closing catch-all, which is the change that closes the over-grant")
        void statesTheOrdinaryEntitlementAsARuleOfItsOwn() {
            assertThat(SecurityConfig.Gating.AUTHENTICATED.enforcementPattern())
                    .as("an entitlement with no pattern is an entitlement enforced by whatever the "
                            + "closing rule happens to say, which was bare authentication")
                    .isEqualTo(SecurityConfig.API_PATH_PREFIX + "/**");
            assertThat(Stream.of(SecurityConfig.Gating.values())
                    .filter(gating -> gating.enforcementPattern().isBlank()))
                    .as("every entitlement now names the rule that enforces it")
                    .isEmpty();
        }

        @Test
        @DisplayName("admits an ordinary route to an identity carrying the standard authority")
        void admitsAnOrdinaryRouteToTheStandardAuthority() throws Exception {
            plainTransport().run(context -> identityClientFor(context)
                    .perform(get(ORDINARY_ROUTE)
                            .with(authentication(identityCarrying(JwtTokenProvider.USER_AUTHORITY))))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200)));
        }

        @Test
        @DisplayName("admits an ordinary route to an identity carrying the administrative authority, "
                + "because the estate's administrative branch reaches every ordinary transaction too")
        void admitsAnOrdinaryRouteToTheAdministrativeAuthority() throws Exception {
            plainTransport().run(context -> identityClientFor(context)
                    .perform(get(ORDINARY_ROUTE)
                            .with(authentication(identityCarrying(JwtTokenProvider.ADMIN_AUTHORITY))))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200)));
        }

        @Test
        @DisplayName("REFUSES an ordinary route to an identity carrying the operator authority, which "
                + "bare authentication admitted - this is the finding, stated as a response")
        void refusesAnOrdinaryRouteToTheOperatorAuthority() throws Exception {
            plainTransport().run(context -> identityClientFor(context)
                    .perform(get(ORDINARY_ROUTE)
                            .with(authentication(
                                    identityCarrying(SecurityConfig.MANAGEMENT_AUTHORITY))))
                    .andExpect(result -> {
                        assertThat(result.getResponse().getStatus())
                                .as("an identity that is not a sign-on record has no business reading an "
                                        + "account; it is refused here by the rule and not merely by the "
                                        + "accident that the management chain is matched first")
                                .isEqualTo(403);
                        assertThat(result.getResponse().getContentAsString())
                                .as("the handler must not have run")
                                .doesNotContain("ordinary");
                    }));
        }

        @Test
        @DisplayName("REFUSES an ordinary route to an identity carrying no authority at all, which is the "
                + "weakest identity bare authentication accepted")
        void refusesAnOrdinaryRouteToAnIdentityCarryingNoAuthority() throws Exception {
            plainTransport().run(context -> identityClientFor(context)
                    .perform(get(ORDINARY_ROUTE).with(authentication(identityCarrying())))
                    .andExpect(result -> {
                        assertThat(result.getResponse().getStatus()).isEqualTo(403);
                        assertThat(result.getResponse().getContentAsString()).doesNotContain("ordinary");
                    }));
        }

        @Test
        @DisplayName("refuses a submission to an ordinary route on the same terms, so the rule is not a "
                + "read-only gate")
        void refusesASubmissionOnTheSameTerms() throws Exception {
            plainTransport().run(context -> identityClientFor(context)
                    .perform(post(ORDINARY_ROUTE)
                            .with(authentication(
                                    identityCarrying(SecurityConfig.MANAGEMENT_AUTHORITY))))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(403)));
        }

        @Test
        @DisplayName("does not shadow the anonymous permit, because it is registered after it - a caller "
                + "with no identity at all still reaches the route that issues one")
        void doesNotShadowTheAnonymousPermit() throws Exception {
            plainTransport().run(context -> identityClientFor(context)
                    .perform(get(SecurityConfig.SIGN_ON_PATH))
                    .andExpect(result -> {
                        assertThat(result.getResponse().getStatus()).isEqualTo(200);
                        assertThat(result.getResponse().getContentAsString()).isEqualTo("sign-on");
                    }));
        }

        @Test
        @DisplayName("does not widen the administrative prefix, because that rule is registered ahead of "
                + "it and decides first")
        void doesNotWidenTheAdministrativePrefix() throws Exception {
            plainTransport().run(context -> identityClientFor(context)
                    .perform(get(ADMIN_ROUTE)
                            .with(authentication(identityCarrying(JwtTokenProvider.USER_AUTHORITY))))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(403)));
        }

        @Test
        @DisplayName("does not widen the batch-control surface, for the same ordering reason")
        void doesNotWidenTheBatchControlSurface() throws Exception {
            plainTransport().run(context -> identityClientFor(context)
                    .perform(post(BATCH_CONTROL_ROUTE)
                            .with(authentication(identityCarrying(JwtTokenProvider.USER_AUTHORITY))))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(403)));
        }

        @Test
        @DisplayName("still refuses an ordinary route presenting no identity at all as unauthorized "
                + "rather than forbidden, so the allow-list did not collapse the two refusals")
        void stillDistinguishesAnAbsentIdentityFromAnUnentitledOne() throws Exception {
            plainTransport().run(context -> identityClientFor(context).perform(get(ORDINARY_ROUTE))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401)));
        }
    }

    @Nested
    @DisplayName("The batch-control surface, which is not a transaction and is gated on its own")
    class BatchControlRoutes {

        @Test
        @DisplayName("admit the launch operation to an administrator's token, because an operator is who "
                + "submitted a job")
        void admitTheLaunchOperationToAnAdministrator() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(post(BATCH_LAUNCH_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenFor(context, UserType.ADMIN)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200)));
        }

        @Test
        @DisplayName("REFUSE the launch operation to a standard user's token, because the backup job's "
                + "second step clears the transaction master and no transaction ever exposed that")
        void refuseTheLaunchOperationToAStandardUser() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(post(BATCH_LAUNCH_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenFor(context, UserType.USER)))
                    .andExpect(result -> {
                        assertThat(result.getResponse().getStatus())
                                .as("a principal exists and is not entitled")
                                .isEqualTo(403);
                        // Were the rule absent, this same token would satisfy the closing catch-all and
                        // the handler would answer 200 with this body - so the body assertion is what
                        // proves the gate is reached rather than merely written.
                        assertThat(result.getResponse().getContentAsString())
                                .doesNotContain("batch-launch");
                    }));
        }

        @Test
        @DisplayName("refuse the launch operation presenting no credential at all as unauthorized, not "
                + "forbidden")
        void refuseTheLaunchOperationWithoutACredential() throws Exception {
            plainTransport().run(context -> clientFor(context).perform(post(BATCH_LAUNCH_ROUTE))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401)));
        }

        @Test
        @DisplayName("admit the status operation to an administrator's token, so both halves of the "
                + "surface answer the same caller")
        void admitTheStatusOperationToAnAdministrator() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(BATCH_STATUS_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenFor(context, UserType.ADMIN)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200)));
        }

        @Test
        @DisplayName("REFUSE the status operation to a standard user's token, because what has run and "
                + "how it ended is the same operational detail as starting it")
        void refuseTheStatusOperationToAStandardUser() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(BATCH_STATUS_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenFor(context, UserType.USER)))
                    .andExpect(result -> {
                        assertThat(result.getResponse().getStatus()).isEqualTo(403);
                        assertThat(result.getResponse().getContentAsString())
                                .doesNotContain("batch-status");
                    }));
        }

        @Test
        @DisplayName("refuse the status operation presenting no credential at all as unauthorized")
        void refuseTheStatusOperationWithoutACredential() throws Exception {
            plainTransport().run(context -> clientFor(context).perform(get(BATCH_STATUS_ROUTE))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401)));
        }

        @Test
        @DisplayName("gate the whole prefix and not the two operations, so an operation added beneath it "
                + "is administrator-only from the moment it exists")
        void gateTheWholePrefixRatherThanTheTwoOperations() {
            assertThat(BatchJobController.BATCH_JOBS_PATH)
                    .as("a mapping outside the gated prefix would answer under the closing catch-all")
                    .startsWith(SecurityConfig.BATCH_CONTROL_PATH_PREFIX);
            assertThat(SecurityConfig.BATCH_CONTROL_PATH_PREFIX)
                    .isEqualTo(BatchJobController.BATCH_CONTROL_PATH_PREFIX);
        }

        @Test
        @DisplayName("keep the batch-control surface out of the route-to-role table, because that table "
                + "is the census of the eighteen legacy transaction definitions")
        void keepTheSurfaceOutOfTheRouteToRoleTable() {
            assertThat(SecurityConfig.TransactionRoute.registeredTransactions())
                    .as("a nineteenth row for a surface with no legacy counterpart would make the "
                            + "census untrue, which is why the rule is stated separately")
                    .hasSize(18);
            assertThat(SecurityConfig.TransactionRoute
                            .enforcementPatternsFor(SecurityConfig.Gating.ADMINISTRATIVE))
                    .allSatisfy(pattern -> assertThat(pattern)
                            .doesNotContain(SecurityConfig.BATCH_CONTROL_PATH_PREFIX));
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
                        Clock.fixed(NOW.minus(Duration.ofHours(2)), ZoneOffset.UTC),
                        new SignOnStateService(CREDENTIAL_MASTER.repository()));

                clientFor(context)
                        .perform(get(ORDINARY_ROUTE).header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + past.issue(ADMIN_USER_ID, UserType.ADMIN, UserType.ADMIN.getCode())))
                        .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
            });
        }

        @Test
        @DisplayName("is refused when signed with foreign material, so a token minted elsewhere carries "
                + "no authority here")
        void isRefusedWhenSignedElsewhere() throws Exception {
            plainTransport().run(context -> {
                final JwtTokenProvider foreign = new JwtTokenProvider(
                        new JwtProperties(FOREIGN_SECRET, ISSUER, Duration.ofMinutes(30)),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        new SignOnStateService(CREDENTIAL_MASTER.repository()));

                clientFor(context)
                        .perform(get(ADMIN_ROUTE).header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + foreign.issue(ADMIN_USER_ID, UserType.ADMIN, UserType.ADMIN.getCode())))
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

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"X", "a", "u", "1", " ", "AA"})
        @DisplayName("establishes no identity, and does not fail, when it verifies but carries a user type "
                + "this estate does not declare - the sign-on program tolerates an unexpected code, so "
                + "neither may the boundary")
        void establishesNoIdentityForAnUndeclaredUserType(final String undeclaredCode) throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(ORDINARY_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenCarryingRole(undeclaredCode)))
                    // Refused because no identity was established, not because anything raised: an
                    // unauthorized answer rather than a server error is the whole point of the assertion.
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401)));
        }

        @Test
        @DisplayName("establishes no identity, and does not fail, when it verifies but carries no user "
                + "type at all")
        void establishesNoIdentityWhenTheRoleClaimIsAbsent() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(ORDINARY_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenCarryingRole(null)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401)));
        }

        @Test
        @DisplayName("establishes no identity when it verifies and carries a declared user type but no "
                + "record fingerprint, so a token minted before that claim existed cannot bypass the "
                + "currency check by omitting it")
        void establishesNoIdentityWhenTheFingerprintIsAbsent() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    // Assembled with the chain's own signing material, the issuer it requires and a window
                    // around its own clock, and carrying the type the seeded record genuinely holds - so
                    // the only thing wrong with it is the absent fingerprint.
                    .perform(get(ORDINARY_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenCarryingRole(UserType.USER.getCode())))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401)));
        }
    }

    /**
     * A session that has stopped describing its record, which the chain must stop admitting.
     *
     * <p><strong>What these assertions are for.</strong> The legacy system had nothing to revoke: every
     * terminal turn re-entered a transaction that read the credential master again, so a record changed
     * between two turns was simply read again on the next one. A bearer token is the opposite - it stays
     * true to its signature after it has stopped being true about the record it describes - so a chain that
     * trusted the signature alone left an administrator's demotion, an operator's deletion and a credential
     * reset with no effect at all until the token already in the operator's hands expired.</p>
     *
     * <p>Each assertion below is written as the pair that makes it discriminate: the same token, through the
     * same chain, before and after one administrative change. A test that only showed the refusal would be
     * satisfied by a chain that refused everything, and a test that only showed the acceptance would be
     * satisfied by the defect. The change is made against the credential master the chain actually reads,
     * which is why the evidence is a real response status and not a stubbed return.</p>
     *
     * <p>Every refusal here is {@code 401} rather than {@code 403}, and the distinction is the contract: a
     * token that no longer names its record establishes <em>no identity at all</em>, so the request reaches
     * the rules as an anonymous one. A {@code 403} would mean an identity had been established and then
     * found insufficient, which is a different statement about what the boundary decided.</p>
     */
    @Nested
    @DisplayName("A session that no longer describes its record")
    class RevokedSessions {

        @Test
        @DisplayName("is admitted while the record still stands, which is the control every refusal below "
                + "is measured against")
        void isAdmittedWhileTheRecordStillStands() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(ADMIN_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenFor(context, UserType.ADMIN)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200)));
        }

        @Test
        @DisplayName("is refused once the operator is demoted, so an administrative entitlement does not "
                + "outlive the record that granted it")
        void isRefusedOnceTheOperatorIsDemoted() throws Exception {
            plainTransport().run(context -> {
                final String credential = "Bearer " + tokenFor(context, UserType.ADMIN);

                CREDENTIAL_MASTER.withUserType(ADMIN_USER_ID, UserType.USER.getCode());

                clientFor(context)
                        .perform(get(ADMIN_ROUTE).header(HttpHeaders.AUTHORIZATION, credential))
                        .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
            });
        }

        @Test
        @DisplayName("is refused on an ordinary route too once the operator is demoted, so the demotion "
                + "is not merely a narrowing of what the same session may reach")
        void isRefusedOnAnOrdinaryRouteOnceDemoted() throws Exception {
            plainTransport().run(context -> {
                final String credential = "Bearer " + tokenFor(context, UserType.ADMIN);

                CREDENTIAL_MASTER.withUserType(ADMIN_USER_ID, UserType.USER.getCode());

                clientFor(context)
                        .perform(get(ORDINARY_ROUTE).header(HttpHeaders.AUTHORIZATION, credential))
                        .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
            });
        }

        @Test
        @DisplayName("is refused once the operator is deleted, so a removed sign-on record removes the "
                + "session with it")
        void isRefusedOnceTheOperatorIsDeleted() throws Exception {
            plainTransport().run(context -> {
                final String credential = "Bearer " + tokenFor(context, UserType.USER);

                CREDENTIAL_MASTER.without(STANDARD_USER_ID);

                clientFor(context)
                        .perform(get(ORDINARY_ROUTE).header(HttpHeaders.AUTHORIZATION, credential))
                        .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
            });
        }

        @Test
        @DisplayName("is refused once the credential is set, so setting a credential ends the sessions "
                + "issued against the previous one")
        void isRefusedOnceTheCredentialIsSet() throws Exception {
            plainTransport().run(context -> {
                final String credential = "Bearer " + tokenFor(context, UserType.USER);

                CREDENTIAL_MASTER.withResetCredential(STANDARD_USER_ID);

                clientFor(context)
                        .perform(get(ORDINARY_ROUTE).header(HttpHeaders.AUTHORIZATION, credential))
                        .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
            });
        }

        @Test
        @DisplayName("is still admitted after a change that is not a security fact, so correcting a family "
                + "name does not end anybody's session")
        void isStillAdmittedAfterANonSecurityChange() throws Exception {
            plainTransport().run(context -> {
                final String credential = "Bearer " + tokenFor(context, UserType.USER);

                CREDENTIAL_MASTER.findById(STANDARD_USER_ID).orElseThrow().setSecUsrFname("Corrected");

                clientFor(context)
                        .perform(get(ORDINARY_ROUTE).header(HttpHeaders.AUTHORIZATION, credential))
                        .andExpect(result -> assertThat(result.getResponse().getStatus())
                                .as("covering the name fields would end a session because somebody fixed "
                                        + "a spelling")
                                .isEqualTo(200));
            });
        }

        @Test
        @DisplayName("is refused when the credential master cannot be reached, failing closed rather than "
                + "leaving sessions standing while revocation is not working")
        void isRefusedWhenTheCredentialMasterCannotBeReached() throws Exception {
            plainTransport().run(context -> {
                final String credential = "Bearer " + tokenFor(context, UserType.USER);

                CREDENTIAL_MASTER.failLookupsWith(
                        new IllegalStateException("credential master unreachable"));

                clientFor(context)
                        .perform(get(ORDINARY_ROUTE).header(HttpHeaders.AUTHORIZATION, credential))
                        .andExpect(result -> assertThat(result.getResponse().getStatus())
                                .as("a refusal rather than a server error: the boundary absorbs the "
                                        + "failure and establishes nothing")
                                .isEqualTo(401));
            });
        }

        @Test
        @DisplayName("leaves the anonymous surfaces reachable, because the record is consulted only for a "
                + "request that actually presents a credential")
        void leavesTheAnonymousSurfacesReachable() throws Exception {
            plainTransport().run(context -> {
                CREDENTIAL_MASTER.failLookupsWith(
                        new IllegalStateException("credential master unreachable"));

                clientFor(context).perform(get(MANAGEMENT_BASE + "/health"))
                        .andExpect(result -> assertThat(result.getResponse().getStatus())
                                .as("the health probe presents no credential, so nothing reads a record "
                                        + "on its behalf")
                                .isEqualTo(200));
            });
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
    @DisplayName("The response headers the chain writes, pinned because nothing else states them")
    class ResponseHeaders {

        /**
         * The headers are the framework's defaults, and that is exactly why they need assertions.
         *
         * <p>This configuration calls no {@code headers(...)} customizer, so every value below arrives
         * from the framework rather than from a line in this module that a reviewer could read. An edit
         * that disabled the writers, narrowed the transport-security directive, or relaxed the frame
         * policy would therefore change the delivered posture without changing anything a reader would
         * notice. These tests are the statement of the posture: they read real responses, so they fail if
         * a header stops being written for any reason - a disabled customizer, a framework default that
         * moves, or a route that leaves the chain by a path that skips the writers.
         */
        ResponseHeaders() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("refuses content-type sniffing, so a response body cannot be re-interpreted as a "
                + "document type the endpoint did not declare")
        void refusesContentTypeSniffing() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(ORDINARY_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenFor(context, UserType.USER)))
                    .andExpect(result -> assertThat(result.getResponse()
                            .getHeader(CONTENT_TYPE_OPTIONS_HEADER)).isEqualTo("nosniff")));
        }

        @Test
        @DisplayName("denies framing, so no other document can embed a response of this surface")
        void deniesFraming() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(ORDINARY_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenFor(context, UserType.USER)))
                    .andExpect(result -> assertThat(result.getResponse().getHeader(FRAME_OPTIONS_HEADER))
                            .isEqualTo("DENY")));
        }

        @Test
        @DisplayName("forbids caching of an authenticated response, so an account body cannot be replayed "
                + "from an intermediary or from a shared browser cache")
        void forbidsCachingOfAnAuthenticatedResponse() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(ORDINARY_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenFor(context, UserType.USER)))
                    .andExpect(result -> {
                        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                                .contains("no-store")
                                .contains("no-cache");
                        assertThat(result.getResponse().getHeader(HttpHeaders.PRAGMA))
                                .isEqualTo("no-cache");
                        assertThat(result.getResponse().getHeader(HttpHeaders.EXPIRES)).isEqualTo("0");
                    }));
        }

        @Test
        @DisplayName("requires transport security for a year including subdomains once a request arrives "
                + "over a secure channel, which is when the directive can be honoured")
        void requiresTransportSecurityOnASecureRequest() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(ORDINARY_ROUTE).secure(true)
                            .header(HttpHeaders.AUTHORIZATION,
                                    "Bearer " + tokenFor(context, UserType.USER)))
                    .andExpect(result -> assertThat(result.getResponse()
                            .getHeader(TRANSPORT_SECURITY_HEADER))
                            .as("a shorter window or a policy excluding subdomains would leave a first "
                                    + "request, or a sibling host, reachable over plain transport")
                            .contains("max-age=31536000")
                            .contains("includeSubDomains")));
        }

        @Test
        @DisplayName("omits the transport-security directive on a plain request, because a client that "
                + "reached this response over plain transport has no secure origin to pin it to - the "
                + "module's own answer to plain transport is the redirect asserted above")
        void omitsTransportSecurityOnAPlainRequest() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(ORDINARY_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenFor(context, UserType.USER)))
                    .andExpect(result -> assertThat(result.getResponse()
                            .getHeader(TRANSPORT_SECURITY_HEADER)).isNull()));
        }

        @Test
        @DisplayName("writes the same headers on a refusal, which is the path a regression is most likely "
                + "to skip because no handler runs on it")
        void writesTheSameHeadersOnARefusal() throws Exception {
            plainTransport().run(context -> clientFor(context).perform(get(ORDINARY_ROUTE))
                    .andExpect(result -> {
                        assertThat(result.getResponse().getStatus()).isEqualTo(401);
                        assertThat(result.getResponse().getHeader(CONTENT_TYPE_OPTIONS_HEADER))
                                .isEqualTo("nosniff");
                        assertThat(result.getResponse().getHeader(FRAME_OPTIONS_HEADER)).isEqualTo("DENY");
                        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                                .contains("no-store");
                    }));
        }

        @Test
        @DisplayName("writes them on the anonymous route too, so the one surface reachable without a "
                + "credential is not the one surface without a posture")
        void writesThemOnTheAnonymousRoute() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(SecurityConfig.SIGN_ON_PATH))
                    .andExpect(result -> {
                        assertThat(result.getResponse().getStatus()).isEqualTo(200);
                        assertThat(result.getResponse().getHeader(CONTENT_TYPE_OPTIONS_HEADER))
                                .isEqualTo("nosniff");
                        assertThat(result.getResponse().getHeader(FRAME_OPTIONS_HEADER)).isEqualTo("DENY");
                    }));
        }

        @Test
        @DisplayName("writes them on the management chain as well, which is a separate chain and would "
                + "otherwise be a separate posture nobody had stated")
        void writesThemOnTheManagementChain() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(MANAGEMENT_BASE + "/health"))
                    .andExpect(result -> {
                        assertThat(result.getResponse().getStatus()).isEqualTo(200);
                        assertThat(result.getResponse().getHeader(CONTENT_TYPE_OPTIONS_HEADER))
                                .isEqualTo("nosniff");
                        assertThat(result.getResponse().getHeader(FRAME_OPTIONS_HEADER)).isEqualTo("DENY");
                    }));
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
        @DisplayName("publishes exactly the module's own two chains, so the framework's default chain has "
                + "stood down rather than sitting alongside them")
        void publishesExactlyTheModulesOwnChains() {
            plainTransport().run(context -> assertThat(context.getBeansOfType(SecurityFilterChain.class))
                    .as("the management surface is decided by its own chain because it needs a different "
                            + "kind of identity from an application route; a third chain here would be "
                            + "the framework's default sitting alongside them")
                    .containsOnlyKeys("managementSecurityFilterChain", "securityFilterChain"));
        }

        @Test
        @DisplayName("consults the management chain before the application chain, which is what makes "
                + "the management rules decide the management paths at all")
        void consultsTheManagementChainFirst() {
            plainTransport().run(context -> {
                final List<String> declared =
                        new ArrayList<>(context.getBeansOfType(SecurityFilterChain.class).keySet());

                assertThat(declared)
                        .as("the application chain carries no request matcher, so it matches everything; "
                                + "whichever chain is consulted first decides the management paths")
                        .containsExactly("managementSecurityFilterChain", "securityFilterChain");
            });
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
                final String candidate = throwawayValue();
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
                            SignOnStateService.class, FixedClockConfig.class)
                    // Contributed so that the only thing this slice is missing is the signing secret;
                    // without it the context would fail for a second reason and the assertion below would
                    // no longer be about the secret at all.
                    // WebMvcConfig's body-limit registration now counts a refusal, so this slice needs a
                    // registry. Supplied because the runner registers no metrics auto-configuration,
                    // whereas the running application always has one.
                    .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                    .withBean(UserSecurityRepository.class, CREDENTIAL_MASTER::repository)
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

    /**
     * The inventory the authorization rules are derived from, asserted as a partition.
     *
     * <p>Every expected value here is a literal written in this file. That is the whole discipline: an
     * expectation obtained by asking the table what it holds would agree with the table however the table
     * had been changed, and the changes that matter - a nineteenth route appearing, a route promoted into
     * the administrative set, the sign-on exemption spreading to a second route - are exactly the ones such
     * an expectation cannot see.</p>
     *
     * <p>The three entitlements must partition the eighteen registered definitions: one reachable
     * anonymously, twelve reachable by any established identity, five requiring an administrative
     * authority. The arithmetic is asserted as three sizes, a disjointness check and a coverage check
     * rather than left implicit, because a route moved from one entitlement to another leaves every count
     * except two unchanged.</p>
     */
    @Nested
    @DisplayName("The route-to-role table: eighteen registered transactions in three entitlements")
    class RouteToRoleTable {

        @Test
        @DisplayName("registers exactly the eighteen identifiers the resource definitions define, so "
                + "neither a dropped entry nor a nineteenth invented one can pass")
        void registersExactlyTheEighteenDefinedIdentifiers() {
            assertThat(registeredIdentifiers())
                    .as("nothing missing and nothing extra, against a literal expectation")
                    .isEqualTo(REGISTERED_TRANSACTION_IDS)
                    .hasSize(REGISTERED_COUNT);
        }

        @Test
        @DisplayName("requires an administrative authority for exactly five identifiers - the "
                + "administrative menu and the four sign-on-record maintenance transactions")
        void requiresAnAdministrativeAuthorityForExactlyFive() {
            assertThat(identifiersGated(SecurityConfig.Gating.ADMINISTRATIVE))
                    .as("gating a sixth refuses a caller the estate admits; ungating one admits a caller "
                            + "it refuses - so this is set equality, never containment")
                    .isEqualTo(ADMINISTRATIVE_TRANSACTION_IDS)
                    .hasSize(ADMINISTRATIVE_COUNT);
        }

        @Test
        @DisplayName("exempts one identifier and only one, because a route that issues credentials cannot "
                + "require the credential it issues")
        void exemptsOnlyTheTransactionThatIssuesCredentials() {
            assertThat(identifiersGated(SecurityConfig.Gating.ANONYMOUS))
                    .isEqualTo(ANONYMOUS_TRANSACTION_IDS)
                    .hasSize(ANONYMOUS_COUNT)
                    .containsExactly("CC00");
        }

        @Test
        @DisplayName("leaves the remaining twelve identifiers to the ordinary business rule, which admits "
                + "either sign-on authority by name and no other identity")
        void leavesTheRemainingTwelveToTheOrdinaryBusinessRule() {
            assertThat(identifiersGated(SecurityConfig.Gating.AUTHENTICATED))
                    .isEqualTo(ORDINARY_TRANSACTION_IDS)
                    .hasSize(ORDINARY_COUNT);
        }

        @Test
        @DisplayName("partitions the eighteen with no overlap and no remainder, so a route silently "
                + "promoted or declassified fails rather than balancing out")
        void partitionsTheEighteenWithNoOverlapAndNoRemainder() {
            final Set<String> anonymous = identifiersGated(SecurityConfig.Gating.ANONYMOUS);
            final Set<String> ordinary = identifiersGated(SecurityConfig.Gating.AUTHENTICATED);
            final Set<String> administrative = identifiersGated(SecurityConfig.Gating.ADMINISTRATIVE);

            assertThat(anonymous).hasSize(ANONYMOUS_COUNT);
            assertThat(ordinary).hasSize(ORDINARY_COUNT);
            assertThat(administrative).hasSize(ADMINISTRATIVE_COUNT);

            assertThat(anonymous)
                    .as("a route cannot be both anonymous and gated")
                    .doesNotContainAnyElementsOf(ordinary)
                    .doesNotContainAnyElementsOf(administrative);
            assertThat(ordinary)
                    .as("a route cannot be both ordinary and administrative")
                    .doesNotContainAnyElementsOf(administrative);

            assertThat(ANONYMOUS_COUNT + ORDINARY_COUNT + ADMINISTRATIVE_COUNT)
                    .as("the three entitlements account for every registered definition")
                    .isEqualTo(REGISTERED_COUNT);
            assertThat(Stream.of(anonymous, ordinary, administrative)
                    .flatMap(Set::stream)
                    .toList())
                    .as("their union is the whole inventory, so no entitlement is left unclassified")
                    .containsExactlyInAnyOrderElementsOf(REGISTERED_TRANSACTION_IDS);
        }

        @Test
        @DisplayName("keeps the definition whose bound program has no source member, and keeps it out of "
                + "the administrative set, so the anomaly is auditable and widens nothing")
        void keepsTheDanglingDefinitionWithoutWideningAnything() {
            assertThat(registeredIdentifiers())
                    .as("an inventory that drops its own anomaly cannot be reconciled")
                    .contains(DANGLING_TRANSACTION_ID);
            assertThat(identifiersGated(SecurityConfig.Gating.ADMINISTRATIVE))
                    .doesNotContain(DANGLING_TRANSACTION_ID);
            assertThat(SecurityConfig.TransactionRoute.registeredTransactions().stream()
                    .filter(route -> !route.isBoundProgramImplemented())
                    .map(SecurityConfig.TransactionRoute::getTransactionId)
                    .toList())
                    .as("exactly one definition is marked as having no implemented program")
                    .containsExactly(DANGLING_TRANSACTION_ID);
        }

        @Test
        @DisplayName("declares no type for the program that has no source member, so the anomaly is "
                + "recorded as inventory rather than implemented as code")
        void declaresNoTypeForTheProgramWithNoSourceMember() throws IOException {
            final List<Path> sources = productionSources();

            assertThat(sources)
                    .as("no production source file may be named after that program")
                    .noneMatch(path -> path.getFileName().toString().regionMatches(true, 0,
                            DANGLING_PROGRAM_NAME, 0, DANGLING_PROGRAM_NAME.length()));

            for (final Path source : sources) {
                assertThat(DANGLING_TYPE_DECLARATION.matcher(Files.readString(source)).find())
                        .as("no type of any kind may be declared for that program, in %s", source)
                        .isFalse();
            }
        }
    }

    /**
     * The credential hasher, which is the migration's one documented parity exception.
     *
     * <p>The cost factor is read out of a digest the hasher actually produced rather than out of a field,
     * because the value that matters is the one written into stored credentials. Nothing here times a
     * hashing operation: the factor is a resistance parameter, and timing it would invent a service level
     * that no requirement states.</p>
     *
     * <p>Every value handed to the hasher is generated in the test. No value from the legacy provisioning
     * records is used or named, here or anywhere in this file.</p>
     */
    @Nested
    @DisplayName("The credential hasher")
    class CredentialHashing {

        @Test
        @DisplayName("is the module's single hashing policy and is the hashing algorithm the stored digests "
                + "were produced by")
        void isTheSingleHashingPolicy() {
            plainTransport().run(context -> {
                assertThat(context).hasSingleBean(PasswordEncoder.class);

                final PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
                final String candidate = throwawayValue();

                assertThat(encoder.encode(candidate))
                        .as("a hasher that returned its input would be a no-op hasher")
                        .isNotEqualTo(candidate);
                assertThat(encoder)
                        .as("the type is the secondary guard; the behaviour above is the assertion")
                        .isInstanceOf(BCryptPasswordEncoder.class);
            });
        }

        @Test
        @DisplayName("produces every digest at the declared cost factor, read from the digest itself")
        void producesEveryDigestAtTheDeclaredCost() {
            plainTransport().run(context -> {
                final String digest = context.getBean(PasswordEncoder.class).encode(throwawayValue());
                final String[] fields = DIGEST_FIELD_DELIMITER.split(digest);

                assertThat(fields)
                        .as("a digest carries an empty leading field, a variant, a cost and the remainder")
                        .hasSize(DIGEST_FIELD_COUNT);
                assertThat(fields[DIGEST_COST_FIELD_INDEX])
                        .as("the cost the stored credentials were produced at")
                        .isEqualTo(EXPECTED_HASHING_COST_FIELD);
                assertThat(Integer.parseInt(fields[DIGEST_COST_FIELD_INDEX]))
                        .isEqualTo(EXPECTED_HASHING_COST);
            });
        }

        @Test
        @DisplayName("hashes at the same strength as the digest service, which is the module's single "
                + "hashing policy rather than two encoders that happen to agree")
        void hashesAtTheSameStrengthAsTheDigestService() {
            plainTransport().run(context -> {
                final String fromTheBean =
                        context.getBean(PasswordEncoder.class).encode(throwawayValue());
                final String fromTheDigestService = new CredentialDigestService().encode(throwawayValue());

                assertThat(costFactorOf(fromTheBean))
                        .as("two live encoders at two strengths is not a verification failure - a digest "
                                + "carries its own cost - it is a silent policy split in which the weaker "
                                + "becomes the module's real strength")
                        .isEqualTo(costFactorOf(fromTheDigestService));
                assertThat(costFactorOf(fromTheBean))
                        .as("and the strength both read is the one the digest service publishes")
                        .isEqualTo(CredentialDigestService.HASHING_STRENGTH)
                        .isEqualTo(EXPECTED_HASHING_COST);
            });
        }

        @Test
        @DisplayName("takes its strength from the digest service's published constant, so the expectation "
                + "in this file and the policy in the module cannot drift apart")
        void takesItsStrengthFromThePublishedConstant() {
            assertThat(CredentialDigestService.HASHING_STRENGTH)
                    .as("raising the strength is a one-line change in the service; this assertion is what "
                            + "makes a change here deliberate rather than accidental")
                    .isEqualTo(EXPECTED_HASHING_COST);
        }

        @Test
        @DisplayName("produces a digest of the declared width, marked with a recognised variant")
        void producesADigestOfTheDeclaredWidth() {
            plainTransport().run(context -> {
                final String digest = context.getBean(PasswordEncoder.class).encode(throwawayValue());

                assertThat(digest)
                        .as("the width the stored column was widened to hold")
                        .hasSize(DIGEST_LENGTH);
                assertThat(ACCEPTED_DIGEST_PREFIXES)
                        .as("the variant marker records which implementation produced the digest, so all "
                                + "three are accepted rather than one pinned")
                        .anySatisfy(prefix -> assertThat(digest).startsWith(prefix));
            });
        }

        @Test
        @DisplayName("verifies the value it hashed and refuses any other, which is the whole of what "
                + "replaced the estate's direct text comparison")
        void verifiesTheValueItHashedAndRefusesAnyOther() {
            plainTransport().run(context -> {
                final PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
                final String candidate = throwawayValue();
                final String other = throwawayValue();
                final String digest = encoder.encode(candidate);

                assertThat(encoder.matches(candidate, digest)).isTrue();
                assertThat(encoder.matches(other, digest)).isFalse();
            });
        }

        @Test
        @DisplayName("salts every digest, so two hashes of one value differ and both still verify")
        void saltsEveryDigest() {
            plainTransport().run(context -> {
                final PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
                final String candidate = throwawayValue();
                final String first = encoder.encode(candidate);
                final String second = encoder.encode(candidate);

                assertThat(first)
                        .as("equal digests for one value would mean an unsalted hash")
                        .isNotEqualTo(second);
                assertThat(encoder.matches(candidate, first)).isTrue();
                assertThat(encoder.matches(candidate, second)).isTrue();
            });
        }

        @Test
        @DisplayName("accepts nothing stored as cleartext, so there is no legacy comparison path left and "
                + "no delegating fallback that would take one")
        void acceptsNothingStoredAsCleartext() {
            plainTransport().run(context -> {
                final PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
                final String candidate = throwawayValue();

                assertThat(encoder.matches(candidate, candidate))
                        .as("the estate compared stored and submitted values as equal text; this must not")
                        .isFalse();
                assertThat(encoder.matches(candidate, CLEARTEXT_MARKER + candidate))
                        .as("a delegating hasher with a cleartext branch would accept this")
                        .isFalse();
            });
        }
    }

    /**
     * Submissions, and the absence of anything a browser would log in through.
     *
     * <p>Cross-site request protection is disabled, and the only honest way to assert that is to make a
     * state-changing request without a forgery token and read the answer. The three assertions together say
     * what disabling it did and did not do: an anonymous submission is accepted, an authenticated one is
     * accepted, and an unauthenticated submission to a protected route is still refused - so the defence
     * that was removed was the one made redundant by having no ambient authority, not the one that decides
     * reachability.</p>
     *
     * <p>There is no browser interface in this migration, so there is no login page and no credential
     * prompt. A refusal is a refusal rather than a redirect to somewhere a person could type into.</p>
     */
    @Nested
    @DisplayName("Submissions, and the absence of a login surface")
    class SubmissionsAndLoginSurface {

        @Test
        @DisplayName("accepts an anonymous submission carrying no forgery token, because the route that "
                + "issues credentials has to be reachable by a caller that holds none")
        void acceptsAnAnonymousSubmissionWithNoForgeryToken() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(post(SecurityConfig.SIGN_ON_PATH))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("403 here would mean a forgery defence was still intercepting writes")
                            .isEqualTo(200)));
        }

        @Test
        @DisplayName("accepts an authenticated submission carrying no forgery token, since the credential "
                + "is a header the caller set deliberately and never an ambient cookie")
        void acceptsAnAuthenticatedSubmissionWithNoForgeryToken() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(post(ORDINARY_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenFor(context, UserType.USER)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200)));
        }

        @Test
        @DisplayName("still refuses an unauthenticated submission to a protected route, so what was "
                + "removed was the redundant defence and not the one that decides reachability")
        void stillRefusesAnUnauthenticatedSubmission() throws Exception {
            plainTransport().run(context -> clientFor(context).perform(post(ORDINARY_ROUTE))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401)));
        }

        @Test
        @DisplayName("the shipped empty origin list rejects a browser preflight before authentication")
        void theShippedOriginPolicyRejectsEveryPreflight() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(options(ORDINARY_ROUTE)
                            .header(HttpHeaders.ORIGIN, "https://client.example")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(403)));
        }

        @Test
        @DisplayName("an exact configured origin receives its preflight without presenting a bearer token")
        void anExactConfiguredOriginReceivesItsPreflight() throws Exception {
            plainTransport()
                    .withPropertyValues(WebMvcConfig.CORS_ALLOWED_ORIGINS_PROPERTY
                            + "=https://client.example")
                    .run(context -> clientFor(context)
                            .perform(options(ORDINARY_ROUTE)
                                    .header(HttpHeaders.ORIGIN, "https://client.example")
                                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                            .andExpect(result -> {
                                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                                assertThat(result.getResponse().getHeader(
                                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                                        .isEqualTo("https://client.example");
                            }));
        }

        @ParameterizedTest(name = "GET {0}")
        @ValueSource(strings = {FRAMEWORK_LOGIN_PATH, FRAMEWORK_LOGIN_PATH + "?error"})
        @DisplayName("publishes no login page at the framework's own address, because there is no browser "
                + "interface and presenting a token is the only way to become authenticated")
        void publishesNoLoginPage(final String path) throws Exception {
            plainTransport().run(context -> clientFor(context).perform(get(path))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("200 would mean a page is served there and 302 would mean a redirect to "
                                    + "one; the closing rule must simply refuse it")
                            .isEqualTo(401)));
        }

        @Test
        @DisplayName("refuses without redirecting anywhere, so no caller is ever sent to a page to type "
                + "credentials into")
        void refusesWithoutRedirecting() throws Exception {
            plainTransport().run(context -> clientFor(context).perform(get(ORDINARY_ROUTE))
                    .andExpect(result -> {
                        assertThat(result.getResponse().getStatus()).isEqualTo(401);
                        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                                .as("a refusal carries no onward address")
                                .isNull();
                    }));
        }

        @Test
        @DisplayName("names no browser credential scheme in its challenge, so no client is invited to "
                + "prompt a person for one")
        void namesNoBrowserCredentialScheme() throws Exception {
            plainTransport().run(context -> clientFor(context).perform(get(ORDINARY_ROUTE))
                    .andExpect(result -> assertThat(
                            result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                            .as("a challenge naming the browser prompt scheme would produce a dialog")
                            .doesNotContain(BROWSER_PROMPT_SCHEME)));
        }
    }

    /**
     * Reachability of the two management endpoints that orchestration and metric collection depend on.
     *
     * <p>Which endpoints a profile publishes is asserted elsewhere. What is asserted here is narrower and
     * is the part a security rule could break: that the health probe and the scrape endpoint are reachable
     * without an administrative authority, and that the rules address the configured base path rather than
     * a literal one.</p>
     */
    @Nested
    @DisplayName("The management surface orchestration depends on")
    class ManagementSurfaceReachability {

        @ParameterizedTest(name = "GET {0} with an ordinary sign-on token")
        @ValueSource(strings = {MANAGEMENT_BASE + "/prometheus", MANAGEMENT_BASE + "/metrics",
            MANAGEMENT_BASE + "/info"})
        @DisplayName("REFUSES an ordinary sign-on identity on the operational surface, which is the "
                + "least-privilege rule the previous bare authenticated() gate did not express")
        void refusesAnOrdinarySignOnIdentity(final String path) throws Exception {
            closedScrape().run(context -> clientFor(context)
                    .perform(get(path).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenFor(context, UserType.USER)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("per-endpoint latency, per-batch-step record counts, pool saturation and "
                                    + "JVM internals are not anything a cardholder's session needs")
                            .isEqualTo(401)));
        }

        @ParameterizedTest(name = "GET {0} with an ADMINISTRATIVE sign-on token")
        @ValueSource(strings = {MANAGEMENT_BASE + "/prometheus", MANAGEMENT_BASE + "/metrics"})
        @DisplayName("REFUSES an administrative sign-on identity too, because the operator authority is "
                + "a different kind of identity rather than a higher rank of the existing two")
        void refusesAnAdministrativeSignOnIdentity(final String path) throws Exception {
            closedScrape().run(context -> clientFor(context)
                    .perform(get(path).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenFor(context, UserType.ADMIN)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("promoting every administrator to operator would have been the other way "
                                    + "to close this, and it would have granted far more than asked")
                            .isEqualTo(401)));
        }

        @ParameterizedTest(name = "GET {0} needs no administrative authority")
        @ValueSource(strings = {MANAGEMENT_BASE + "/health", MANAGEMENT_BASE + "/health/liveness",
            MANAGEMENT_BASE + "/health/readiness"})
        @DisplayName("keeps the aggregate probe and both probe groups anonymous, because the container "
                + "probe presents no credential and their bodies are a status word")
        void keepsTheThreeProbePathsAnonymous(final String path) throws Exception {
            closedScrape().run(context -> clientFor(context).perform(get(path))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("401 here would make the image report a healthy process unhealthy")
                            .isEqualTo(200)));
        }

        @Test
        @DisplayName("no longer permits the health subtree wholesale, so a per-component path naming the "
                + "database product and the validation query is not anonymous")
        void doesNotPermitTheHealthSubtreeWholesale() throws Exception {
            closedScrape().run(context -> clientFor(context)
                    .perform(get(MANAGEMENT_BASE + "/health/db"))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("the previous rule permitted /actuator/health/** - every present and "
                                    + "future sub-path - so a path added by a framework upgrade would "
                                    + "have arrived anonymous")
                            .isEqualTo(401)));
        }

        @Test
        @DisplayName("addresses the configured base path rather than a literal one, so moving the base "
                + "path moves the health permit with it instead of leaving it behind")
        void addressesTheConfiguredBasePath() throws Exception {
            final String movedBase = "/ops";

            runner(false, false, false, movedBase).run(context -> {
                clientFor(context).perform(get(movedBase + "/health"))
                        .andExpect(result -> assertThat(result.getResponse().getStatus())
                                .as("404 means the chain allowed it through to a path no probe handler "
                                        + "claims; 401 would mean the permit had not moved")
                                .isEqualTo(404));
                clientFor(context).perform(get(MANAGEMENT_BASE + "/health"))
                        .andExpect(result -> assertThat(result.getResponse().getStatus())
                                .as("the old address is no longer a management address, so the closing "
                                        + "rule refuses it")
                                .isEqualTo(401));
            });
        }
    }

    /**
     * The strength the machine credential must have before the context will start with it.
     *
     * <p>This credential is the whole of the authentication on the management surface and that surface
     * has no attempt allowance, so its width is the only thing standing between a caller and health,
     * metrics and environment detail. It previously needed only to be non-blank.</p>
     */
    @Nested
    @DisplayName("The machine credential's strength is a startup condition")
    class MachineCredentialStrength {

        /** Creates the slice. */
        MachineCredentialStrength() {
            // Intentionally empty.
        }

        @ParameterizedTest(name = "a credential of {0} characters fails startup")
        @ValueSource(ints = {1, 2, 8, 16, 31})
        @DisplayName("A CREDENTIAL TOO SHORT TO BE UNGUESSABLE FAILS STARTUP: it used to be accepted, "
                + "leaving a surface that looked authenticated and was not")
        void aShortCredentialFailsStartup(final int width) {
            withOperatorCredential(distinctCharacters(width)).run(context -> assertThat(context)
                    .as("a context that started would publish the surface behind a guessable value")
                    .hasFailed());
        }

        @Test
        @DisplayName("a credential of exactly the minimum width starts, so the bound refuses only what "
                + "is below it")
        void aCredentialOfExactlyTheMinimumWidthStarts() {
            withOperatorCredential(distinctCharacters(SecurityConfig.MANAGEMENT_TOKEN_MIN_LENGTH))
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        @DisplayName("a credential past the upper bound fails startup, because past a few hundred "
                + "characters the value is a paste in the wrong setting rather than a secret")
        void anOverLongCredentialFailsStartup() {
            withOperatorCredential("a" + distinctCharacters(
                    SecurityConfig.MANAGEMENT_TOKEN_MAX_LENGTH))
                    .run(context -> assertThat(context).hasFailed());
        }

        @Test
        @DisplayName("a credential of the required width made of one repeated character fails startup, "
                + "because width alone is not unguessability")
        void aRepeatedCharacterCredentialFailsStartup() {
            withOperatorCredential("a".repeat(SecurityConfig.MANAGEMENT_TOKEN_MIN_LENGTH))
                    .run(context -> assertThat(context).hasFailed());
        }

        @Test
        @DisplayName("a credential carrying a space or a control character fails startup, so the value "
                + "presented in a header is the value configured")
        void aCredentialCarryingWhitespaceFailsStartup() {
            final String padded = distinctCharacters(SecurityConfig.MANAGEMENT_TOKEN_MIN_LENGTH - 1);
            withOperatorCredential(padded.substring(0, 8) + " " + padded.substring(8))
                    .run(context -> assertThat(context).hasFailed());
        }

        @Test
        @DisplayName("NO credential configured still starts, because empty is the fail-closed state and "
                + "refusing to start would refuse the safe case")
        void anUnconfiguredCredentialStillStarts() {
            withoutOperatorIdentity().run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        @DisplayName("the credential the rest of this class configures satisfies the rule, so no other "
                + "assertion here depends on a value the boundary would refuse")
        void theFixtureCredentialSatisfiesTheRule() {
            assertThat(OPERATOR_TOKEN.length())
                    .isGreaterThanOrEqualTo(SecurityConfig.MANAGEMENT_TOKEN_MIN_LENGTH)
                    .isLessThanOrEqualTo(SecurityConfig.MANAGEMENT_TOKEN_MAX_LENGTH);
            assertThat(OPERATOR_TOKEN.chars().distinct().count())
                    .isGreaterThanOrEqualTo(SecurityConfig.MANAGEMENT_TOKEN_MIN_DISTINCT_CHARACTERS);
        }

        /**
         * Builds a credential of the requested width whose characters vary, so a width assertion is not
         * also a distinctness assertion.
         *
         * @param  width how many characters to produce
         * @return a visible-ASCII value of that width
         */
        private String distinctCharacters(final int width) {
            final StringBuilder value = new StringBuilder(width);
            for (int index = 0; index < width; index++) {
                value.append((char) ('a' + (index % 26)));
            }
            return value.toString();
        }
    }

    /**
     * Where the bearer-token settings are registered, proved by difference rather than by annotation.
     *
     * <p>A settings object bound by two configurations is bound twice and can be changed in one place
     * without the other noticing, so the ownership matters as much as the binding. The three slices below
     * differ by exactly one participant: the environment alone yields no settings bean, adding the consumer
     * fails because there is nothing to inject, and adding the class under test yields one bound bean.</p>
     */
    @Nested
    @DisplayName("Ownership of the bearer-token settings")
    class SettingsOwnership {

        /**
         * Builds a runner carrying the token settings as configuration but no participant that enables
         * them.
         *
         * @return the configured runner
         */
        private WebApplicationContextRunner settingsWithoutAnOwner() {
            return new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                            SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class))
                    // The record reader and its repository are present so that the one participant these
                    // slices differ by is the class under test, and the one dependency the token provider
                    // cannot satisfy here is the settings record.
                    .withUserConfiguration(SignOnStateService.class, FixedClockConfig.class)
                    // WebMvcConfig's body-limit registration now counts a refusal, so this slice needs a
                    // registry. Supplied because the runner registers no metrics auto-configuration,
                    // whereas the running application always has one.
                    .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                    .withBean(UserSecurityRepository.class, CREDENTIAL_MASTER::repository)
                    .withPropertyValues(
                            JwtProperties.PREFIX + ".secret=" + SECRET,
                            JwtProperties.PREFIX + ".issuer=" + ISSUER,
                            JwtProperties.PREFIX + ".expiration=PT30M");
        }

        @Test
        @DisplayName("surfaces one bound settings bean, so a slice carrying only this configuration can "
                + "read the issuer and the lifetime the profile declared")
        void surfacesOneBoundSettingsBean() {
            plainTransport().run(context -> {
                assertThat(context).hasSingleBean(JwtProperties.class);

                final JwtProperties settings = context.getBean(JwtProperties.class);

                assertThat(settings.issuer()).isEqualTo(ISSUER);
                assertThat(settings.expiration()).isEqualTo(Duration.ofMinutes(30));
                assertThat(settings.hasSecret())
                        .as("the value itself is never asserted, only that one was bound")
                        .isTrue();
            });
        }

        @Test
        @DisplayName("is what registers them: the same configuration values yield no settings bean when "
                + "this class is absent, which is what makes the assertion above about ownership")
        void isWhatRegistersThem() {
            settingsWithoutAnOwner().run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context)
                        .as("configuration keys alone never produce a settings bean")
                        .doesNotHaveBean(JwtProperties.class);
            });
        }

        @Test
        @DisplayName("is what the consumer depends on: the token provider cannot be constructed in that "
                + "same slice, so nothing else quietly registers the settings for it")
        void isWhatTheConsumerDependsOn() {
            settingsWithoutAnOwner()
                    .withUserConfiguration(JwtTokenProvider.class)
                    .run(context -> assertThat(context)
                            .as("with no participant enabling the settings there is nothing to inject")
                            .hasFailed());
        }

        @Test
        @DisplayName("registers no other settings object, since the cloud settings belong to the cloud "
                + "configuration and binding them twice is the drift this ownership prevents")
        void registersNoOtherSettingsObject() {
            plainTransport().run(context -> assertThat(context).doesNotHaveBean(AwsProperties.class));
        }

        @Test
        @DisplayName("binds the settings record without a stereotype on it, so the record is registered "
                + "in one place rather than discovered in two")
        void bindsTheSettingsRecordWithoutAStereotypeOnIt() throws IOException {
            plainTransport().run(context -> assertThat(context).hasSingleBean(JwtProperties.class));

            final MergedAnnotations declared =
                    MergedAnnotations.from(JwtProperties.class, SearchStrategy.TYPE_HIERARCHY);

            assertThat(declared.isPresent(Component.class))
                    .as("a stereotype would register the record a second time")
                    .isFalse();
            assertThat(declared.isPresent(Configuration.class)).isFalse();
            assertThat(declared.isPresent(ConfigurationPropertiesScan.class)).isFalse();
            assertThat(Files.readString(TOKEN_SETTINGS_SOURCE))
                    .as("constructor binding is inferred for a single-constructor record in this "
                            + "framework generation, and stating it is a deprecation - which this build "
                            + "treats as a failure")
                    .doesNotContain("@ConstructorBinding");
        }
    }

    /**
     * The types this package does not contain.
     *
     * <p>The request filter is a private nested type of the class under test, which is why no top-level
     * filter type exists here. Route values belong to the navigation service, and reading a sign-on record
     * belongs to the service layer - the authentication service over its repository for a sign-on, and the
     * sign-on state service over the same repository for the currency check the filter makes - which is why
     * no route holder and no user-lookup implementation exist here either. The boundary consults a record;
     * it does not implement the consulting. Each absence is asserted beside the behaviour that would
     * otherwise need one, so the pair says both that the capability works and that it is not a separate
     * type.</p>
     */
    @Nested
    @DisplayName("The types this package does not contain")
    class NoAdditionalConfigTypes {

        /**
         * Asserts that this package declares no top-level type of the given name.
         *
         * @param simpleName the type name that must not exist here
         */
        private void assertNotATopLevelType(final String simpleName) {
            assertThat(Files.exists(CONFIGURATION_PACKAGE.resolve(simpleName + ".java")))
                    .as("%s must not be a top-level type in this package", simpleName)
                    .isFalse();
        }

        @Test
        @DisplayName("holds no top-level authentication filter, while a token still authenticates - so "
                + "the filter that does the work is nested inside the configuration")
        void holdsNoTopLevelAuthenticationFilter() throws Exception {
            plainTransport().run(context -> clientFor(context)
                    .perform(get(ORDINARY_ROUTE).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokenFor(context, UserType.ADMIN)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("a nested filter is still a working filter")
                            .isEqualTo(200)));

            assertNotATopLevelType("JwtAuthenticationFilter");
        }

        @Test
        @DisplayName("holds no route holder, because the table above is the inventory and the navigation "
                + "service is where route values live")
        void holdsNoRouteHolder() {
            assertThat(SecurityConfig.TransactionRoute.registeredTransactions())
                    .as("the inventory is published by the configuration itself")
                    .hasSize(REGISTERED_COUNT);

            assertNotATopLevelType("RouteRegistry");
            assertNotATopLevelType("Routes");
            assertNotATopLevelType("SecurityRoutes");
        }

        @Test
        @DisplayName("holds no user-lookup implementation, and publishes no user-lookup bean either, "
                + "because loading a sign-on record belongs to the authentication service")
        void holdsNoUserLookupImplementation() {
            plainTransport().run(context -> assertThat(context)
                    .as("a user-lookup bean here would also restore the generated user")
                    .doesNotHaveBean(UserDetailsService.class));

            assertNotATopLevelType("UserDetailsServiceImpl");
        }
    }
    /**
     * What a presented management credential now leaves behind.
     *
     * <h2>Why this group exists</h2>
     *
     * <p>Review found the operator credential accepted on the strength of being non-blank, compared without
     * limit, and - the part this group asserts - <strong>refused in total silence</strong>. No meter moved
     * and nothing was logged, so a deployment being probed for its management credential was
     * indistinguishable from a deployment nobody had touched. The credential reaches every metrics
     * endpoint and every exposition scrape, so that silence was the difference between noticing an attempt
     * and never knowing one happened.
     *
     * <p>Three outcomes are counted, on three series with no caller-derived tag: a credential that matched,
     * one that did not, and one presented at a surface with no identity configured at all. A sustained run
     * of refusals additionally raises a warning once per interval, so probing is visible without one record
     * per attempt.
     *
     * <p><strong>What is deliberately NOT asserted here, because it deliberately does not happen:</strong>
     * no lockout. A threshold that closed the surface would let any unauthenticated caller take this
     * deployment's metrics away from its collector, with one shared secret and nothing to fall back to. The
     * last test in this group states that as a property rather than leaving it as an absence. See
     * {@code docs/decision-log.md} entry DL-312.
     */
    @Nested
    @DisplayName("the management credential's outcome is recorded")
    class TheManagementCredentialOutcome {

        /** A credential of the right shape that is not the configured one. */
        private static final String WRONG_CREDENTIAL =
                "unit-test-wrong-operator-credential-9876543210-abcdefghij";

        /**
         * Reads one outcome's count out of the context's registry.
         *
         * @param  context a started context
         * @param  outcome the outcome tag value
         * @return how many times that outcome was counted, zero when the series does not exist
         */
        private double counted(final AssertableWebApplicationContext context, final String outcome) {
            final Counter counter = context.getBean(MeterRegistry.class)
                    .find(SecurityConfig.MANAGEMENT_AUTHENTICATION_METER)
                    .tag(SecurityConfig.MANAGEMENT_OUTCOME_TAG, outcome)
                    .counter();
            return counter == null ? 0.0d : counter.count();
        }

        @Test
        @DisplayName("a wrong credential is counted as refused, which is the record that did not exist")
        void aWrongCredentialIsCountedAsRefused() throws Exception {
            closedScrape().run(context -> {
                clientFor(context)
                        .perform(get(MANAGEMENT_BASE + "/prometheus")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + WRONG_CREDENTIAL))
                        .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));

                assertThat(counted(context, SecurityConfig.MANAGEMENT_OUTCOME_REFUSED))
                        .as("a refusal must be visible to a collector, not only to the caller who caused it")
                        .isEqualTo(1.0d);
                assertThat(counted(context, SecurityConfig.MANAGEMENT_OUTCOME_ESTABLISHED)).isZero();
            });
        }

        @Test
        @DisplayName("the correct credential is counted as established, so a rate of refusals can be read "
                + "against a rate of successful scrapes rather than in isolation")
        void theCorrectCredentialIsCountedAsEstablished() throws Exception {
            closedScrape().run(context -> {
                clientFor(context)
                        .perform(get(MANAGEMENT_BASE + "/prometheus")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + OPERATOR_TOKEN))
                        .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));

                assertThat(counted(context, SecurityConfig.MANAGEMENT_OUTCOME_ESTABLISHED))
                        .isEqualTo(1.0d);
                assertThat(counted(context, SecurityConfig.MANAGEMENT_OUTCOME_REFUSED)).isZero();
            });
        }

        @Test
        @DisplayName("a credential presented where no operator identity is configured is counted as "
                + "unconfigured, which is a different fault from a wrong credential and needs a different "
                + "answer")
        void aPresentationAgainstNoIdentityIsCountedSeparately() throws Exception {
            withoutOperatorIdentity().run(context -> {
                clientFor(context)
                        .perform(get(MANAGEMENT_BASE + "/prometheus")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + OPERATOR_TOKEN))
                        .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));

                assertThat(counted(context, SecurityConfig.MANAGEMENT_OUTCOME_UNCONFIGURED))
                        .as("a collector presenting a credential to a surface that issued none is a "
                                + "deployment fault; a wrong credential is an access attempt")
                        .isEqualTo(1.0d);
                assertThat(counted(context, SecurityConfig.MANAGEMENT_OUTCOME_REFUSED)).isZero();
            });
        }

        @Test
        @DisplayName("an anonymous probe against a surface with no identity configured is counted as "
                + "nothing at all, because that is the ordinary traffic and counting it would bury the "
                + "presentations that mean something")
        void anAnonymousProbeIsNotCounted() throws Exception {
            withoutOperatorIdentity().run(context -> {
                clientFor(context)
                        .perform(get(MANAGEMENT_BASE + "/health"))
                        .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));

                assertThat(counted(context, SecurityConfig.MANAGEMENT_OUTCOME_UNCONFIGURED)).isZero();
                assertThat(counted(context, SecurityConfig.MANAGEMENT_OUTCOME_REFUSED)).isZero();
            });
        }

        @Test
        @DisplayName("a sustained run of refusals raises one warning per interval - not one per attempt, "
                + "which would let a caller choose this deployment's log volume")
        void aSustainedRunRaisesOneWarningPerInterval() throws Exception {
            final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            final Logger securityLogger = loggerContext.getLogger(SecurityConfig.class);
            final ListAppender<ILoggingEvent> capture = new ListAppender<>();
            capture.setContext(loggerContext);
            capture.start();
            securityLogger.addAppender(capture);
            try {
                closedScrape().run(context -> {
                    final int attempts = SecurityConfig.MANAGEMENT_REFUSAL_REPORT_INTERVAL * 2;
                    for (int attempt = 0; attempt < attempts; attempt++) {
                        clientFor(context).perform(get(MANAGEMENT_BASE + "/prometheus")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + WRONG_CREDENTIAL));
                    }

                    assertThat(counted(context, SecurityConfig.MANAGEMENT_OUTCOME_REFUSED))
                            .as("every attempt is counted; only the reporting is bounded")
                            .isEqualTo((double) attempts);
                    final List<ILoggingEvent> warnings = capture.list.stream()
                            .filter(event -> event.getLevel() == Level.WARN)
                            .filter(event -> event.getFormattedMessage()
                                    .contains("Management credential refused"))
                            .toList();
                    assertThat(warnings)
                            .as("twice the interval, so exactly two reports")
                            .hasSize(2);
                    assertThat(warnings.get(0).getFormattedMessage())
                            .as("the report names the count and nothing a caller supplied")
                            .contains(String.valueOf(SecurityConfig.MANAGEMENT_REFUSAL_REPORT_INTERVAL))
                            .doesNotContain(WRONG_CREDENTIAL)
                            .doesNotContain("Bearer");
                });
            } finally {
                securityLogger.detachAppender(capture);
                capture.stop();
            }
        }

        @Test
        @DisplayName("a successful presentation restarts the run, so the report describes a current attempt "
                + "and not the sum of every refusal since start-up")
        void aSuccessfulPresentationRestartsTheRun() throws Exception {
            final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            final Logger securityLogger = loggerContext.getLogger(SecurityConfig.class);
            final ListAppender<ILoggingEvent> capture = new ListAppender<>();
            capture.setContext(loggerContext);
            capture.start();
            securityLogger.addAppender(capture);
            try {
                closedScrape().run(context -> {
                    // One short of the interval, so nothing has been reported yet.
                    for (int attempt = 0;
                            attempt < SecurityConfig.MANAGEMENT_REFUSAL_REPORT_INTERVAL - 1; attempt++) {
                        clientFor(context).perform(get(MANAGEMENT_BASE + "/prometheus")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + WRONG_CREDENTIAL));
                    }
                    clientFor(context).perform(get(MANAGEMENT_BASE + "/prometheus")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + OPERATOR_TOKEN));
                    clientFor(context).perform(get(MANAGEMENT_BASE + "/prometheus")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + WRONG_CREDENTIAL));

                    assertThat(capture.list.stream()
                            .filter(event -> event.getLevel() == Level.WARN)
                            .filter(event -> event.getFormattedMessage()
                                    .contains("Management credential refused"))
                            .toList())
                            .as("without the restart the tenth refusal overall would report a run of ten, "
                                    + "which is a sentence about history rather than about an attempt")
                            .isEmpty();
                    assertThat(counted(context, SecurityConfig.MANAGEMENT_OUTCOME_REFUSED))
                            .as("the meter still counts every refusal; only the run is restarted")
                            .isEqualTo(SecurityConfig.MANAGEMENT_REFUSAL_REPORT_INTERVAL);
                });
            } finally {
                securityLogger.detachAppender(capture);
                capture.stop();
            }
        }

        @Test
        @DisplayName("the surface stays open to the correct credential after a run of refusals, because a "
                + "lockout would hand any unauthenticated caller this deployment's own metrics")
        void theSurfaceStaysOpenAfterARunOfRefusals() throws Exception {
            closedScrape().run(context -> {
                for (int attempt = 0; attempt < SecurityConfig.MANAGEMENT_REFUSAL_REPORT_INTERVAL * 3;
                        attempt++) {
                    clientFor(context).perform(get(MANAGEMENT_BASE + "/prometheus")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + WRONG_CREDENTIAL));
                }

                clientFor(context)
                        .perform(get(MANAGEMENT_BASE + "/prometheus")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + OPERATOR_TOKEN))
                        .andExpect(result -> assertThat(result.getResponse().getStatus())
                                .as("thirty wrong presentations must not cost the collector its scrape")
                                .isEqualTo(200));

                assertThat(counted(context, SecurityConfig.MANAGEMENT_OUTCOME_ESTABLISHED))
                        .isEqualTo(1.0d);
            });
        }
    }
}
