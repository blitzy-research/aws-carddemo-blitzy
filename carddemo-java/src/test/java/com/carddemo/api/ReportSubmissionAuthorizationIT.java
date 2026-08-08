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

import com.carddemo.config.AwsConfig;
import com.carddemo.config.JwtProperties;
import com.carddemo.config.JwtTokenProvider;
import com.carddemo.config.SecurityConfig;
import com.carddemo.config.WebMvcConfig;
import com.carddemo.domain.enums.UserType;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.DateValidationService;
import com.carddemo.service.JobSubmissionCoordinator;
import com.carddemo.service.JobSubmissionService;
import com.carddemo.service.MessageCatalogService;
import com.carddemo.service.NavigationService;
import com.carddemo.service.ReportRequestService;
import com.carddemo.service.SignOnStateService;
import com.carddemo.support.AbstractLocalStackIT;
import com.carddemo.support.InMemoryCredentialMaster;
import io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.CredentialsProviderAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.RegionProviderAutoConfiguration;
import io.awspring.cloud.autoconfigure.sqs.SqsAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Gate 5 executed over the <em>whole</em> boundary: the real security chain, a real credential, the real
 * report transaction and the real queue.
 *
 * <h2>Why this class exists alongside {@link AwsIntegrationIT}</h2>
 *
 * <p>{@code AwsIntegrationIT} proves the card contract - seventeen images, eighty characters each, the four
 * substituted slots, the terminating sentinel, the message group, the ordering - and it proves it against a
 * real queue. It drives the controller through a standalone servlet harness and hands it a
 * {@code Principal} directly, which is the right shape for that subject: the cards a submission carries do
 * not depend on who asked for them, so wiring a filter chain there would add machinery to a specification
 * about bytes.
 *
 * <p>It cannot, however, prove anything about <strong>who</strong> may submit or about whose namespace a
 * submission lands in, and those are security properties rather than contract properties. A handed-in
 * principal is an assumption: it asserts that the boundary uses the identity it is given, not that the
 * delivered route establishes one, not that a caller presenting nothing is refused before the queue is
 * touched, and not that two different callers are kept apart. Every one of those questions needs the chain
 * that mints and verifies a credential to actually run.
 *
 * <h2>What is therefore asserted here, and why each assertion needs the real chain</h2>
 * <ul>
 *   <li><strong>A submission with no credential publishes nothing.</strong> Asserted by draining the queue
 *       afterwards and finding it empty, because a refusal that still enqueued would be a refusal in the
 *       response and a submission in the estate.</li>
 *   <li><strong>A credential the chain verifies reaches the transaction and its subject scopes the
 *       submission.</strong> The token is minted by the shipped provider from a seeded record, so the
 *       identity under test is the one a signed-on caller actually holds rather than one this file
 *       invented.</li>
 *   <li><strong>Two different operators presenting the same retry token produce two complete streams.</strong>
 *       This is the defect the operator component of the submission identity closes, and it is only
 *       observable when two <em>real</em> identities are established: with a handed-in principal both
 *       submissions carry the same subject and the queue collapses the second, which is precisely the
 *       behaviour that used to be shipped.</li>
 *   <li><strong>The same operator repeating a token does not double its stream.</strong> The other half of
 *       the same property: the identity is a pure function of the range, the token and the operator, so a
 *       retry reissues the earlier identifiers and the queue collapses them.</li>
 *   <li><strong>An echoed navigation identity is ignored.</strong> A caller writes another operator's
 *       identifier and the administrative type into the record it echoes back; the submission still lands
 *       in the token subject's namespace. Over a standalone harness this cannot be tested at all, because
 *       there is no established identity for the echoed one to be preferred over.</li>
 * </ul>
 *
 * <h2>What is deliberately not re-asserted</h2>
 *
 * <p>The card images, their widths, the slot substitutions, the sentinel, the message group and the
 * attributes. Those belong to {@code AwsIntegrationIT} and duplicating them here would produce two places
 * to change when the contract changes. This class counts cards and compares namespaces; it does not read
 * inside a card except to confirm that a complete stream arrived.
 *
 * <h2>Provenance</h2>
 *
 * <p>{@code app/cbl/CORPT00C.cbl} lines 462 to 535 and {@code app/csd/CARDDEMO.CSD} lines 499 to 505, read
 * as read-only reference. The legacy queue had no notion of identity, so the namespace separation asserted
 * here has no legacy counterpart and is recorded as a target-only property in
 * {@code docs/decision-log.md}; no legacy source line is transcribed.
 */
@SpringBootTest(classes = ReportSubmissionAuthorizationIT.SecuredReportBridgeContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("Gate 5 executed :: who may submit a report, and whose namespace it lands in")
class ReportSubmissionAuthorizationIT extends AbstractLocalStackIT {

    /** Cards one complete submission transmits, the end-of-stream card included. */
    private static final int CARD_COUNT = 17;

    /** The route the report-request transaction is published at. */
    private static final String REPORT_REQUEST_ROUTE = "/api/reports/request";

    /** Header the effective logical-request token travels on. */
    private static final String RETRY_TOKEN_HEADER = "Idempotency-Key";

    /** Presentation prefix a bearer credential is offered under. */
    private static final String BEARER_PREFIX = "Bearer ";

    /** First fixture operator, an administrator. */
    private static final String FIRST_OPERATOR = "TESTADM1";

    /** Second fixture operator, an ordinary signed-on identity, because the route admits either type. */
    private static final String SECOND_OPERATOR = "TESTUSR1";

    /**
     * A value that makes every token this class mints unique to this run.
     *
     * <p><strong>This is load-bearing and not hygiene.</strong> The queue deduplicates by identifier over a
     * five-minute window, and emptying the queue between tests removes the <em>messages</em> without
     * clearing that window. Two tests presenting the same token for the same period as the same operator
     * would therefore be one logical submission as far as the queue is concerned, and the second test
     * would drain nothing and report a defect that does not exist. Each test names its own token, and the
     * nonce keeps those names distinct from a previous run's as well.
     */
    private static final String RUN_NONCE =
            Long.toHexString(new SecureRandom().nextLong() & Long.MAX_VALUE);

    /** Signing material, generated so no secret literal enters version control. */
    private static final String SIGNING_SECRET = generatedSecret();

    /** Issuer the verifier requires. */
    private static final String ISSUER = "carddemo-report-submission-it";

    /** The instant every collaborator reads, so a derived period means one thing on every run. */
    private static final Clock PINNED_CLOCK =
            Clock.fixed(Instant.parse("2020-04-01T09:15:00Z"), ZoneOffset.UTC);

    /** The credential master the seeded records live in. */
    private static final InMemoryCredentialMaster CREDENTIAL_MASTER = new InMemoryCredentialMaster();

    /** The context, so the chain bean can be resolved by its published name. */
    @Autowired
    private ApplicationContext context;

    /** The boundary under test, wired to this run's emulator. */
    @Autowired
    private ReportController reportController;

    /** The shared failure handler, so a refusal is rendered as the module renders it. */
    @Autowired
    private GlobalExceptionHandler globalExceptionHandler;

    /** The shipped provider, which is what mints a credential a signed-on caller would hold. */
    @Autowired
    private JwtTokenProvider tokenProvider;

    /**
     * Publishes the credential settings the chain and the provider read.
     *
     * <p>Separate from the emulator registration the base class performs, which the framework permits and
     * which keeps each concern's settings beside the concern that needs them.
     *
     * @param registry the registry the framework supplies
     */
    @DynamicPropertySource
    static void registerSecuritySettings(final DynamicPropertyRegistry registry) {
        registry.add(JwtProperties.PREFIX + ".secret", () -> SIGNING_SECRET);
        registry.add(JwtProperties.PREFIX + ".issuer", () -> ISSUER);
        registry.add(JwtProperties.PREFIX + ".expiration", () -> "PT30M");
        registry.add(WebMvcConfig.CORS_ALLOWED_ORIGINS_PROPERTY, () -> "");
        registry.add(WebMvcConfig.MAX_REQUEST_BODY_SIZE_PROPERTY, () -> "64KB");
        registry.add("carddemo.security.require-https", () -> "false");
        registry.add("carddemo.security.anonymous-metrics-scrape", () -> "false");
        registry.add(SecurityConfig.MANAGEMENT_TOKEN_PROPERTY, () -> "");
        registry.add("springdoc.api-docs.enabled", () -> "false");
    }

    /** Seeds the two fixture operators, so neither test inherits the other's record state. */
    @BeforeEach
    void seedOperators() {
        CREDENTIAL_MASTER.reset()
                .with(FIRST_OPERATOR, UserType.ADMIN.getCode())
                .with(SECOND_OPERATOR, UserType.USER.getCode());
    }

    /**
     * Empties the shared queue whatever the outcome, because a message left behind is a message the next
     * specification drains and cannot explain.
     */
    @AfterEach
    void emptyTheQueue() {
        resetJobSubmissionQueue();
    }

    /**
     * A logical-request token unique to one test and to this run.
     *
     * <p>A caller chooses a token that means something to itself - a period name, a run label - so two
     * operators arriving at the same one is the ordinary case rather than an adversarial one, and that is
     * the case the namespace assertions drive. What must not happen is two <em>tests</em> arriving at the
     * same one; see {@link #RUN_NONCE}.
     *
     * @param  label what the test is about, so a token in a diagnostic says which test minted it
     * @return the token
     */
    private static String tokenFor(final String label) {
        return label + "-" + RUN_NONCE;
    }

    /**
     * Generates signing material of the length the provider requires.
     *
     * @return a URL-safe unpadded encoding of thirty-two random bytes
     */
    private static String generatedSecret() {
        final byte[] material = new byte[32];
        new SecureRandom().nextBytes(material);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    /**
     * The servlet harness over the boundary, with this context's real security chain in front of it.
     *
     * <p>The chain is the bean the framework built from the shipped configuration, resolved by the name it
     * publishes rather than by type, so what runs ahead of the controller is the delivered bearer filter,
     * the delivered authorization rules, the delivered entry point and the delivered access-denied
     * handler. That is the difference between this class and its sibling: nothing here is handed an
     * identity, and every identity is established by verifying a credential.
     *
     * @return the harness
     */
    private MockMvc securedBoundary() {
        return MockMvcBuilders.standaloneSetup(this.reportController)
                .setControllerAdvice(this.globalExceptionHandler)
                .addFilters(this.context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    /**
     * A credential for one seeded operator, minted by the shipped provider.
     *
     * @param  userId the seeded operator
     * @param  userType the type that record carries
     * @return the authorization header value, presentation prefix included
     */
    private String credentialFor(final String userId, final UserType userType) {
        return BEARER_PREFIX + this.tokenProvider.issue(userId, userType, userType.getCode());
    }

    /**
     * Posts one confirmed operator-range request, optionally presenting a credential and a retry token.
     *
     * @param  credential the authorization header value, or {@code null} to present none
     * @param  retryToken the logical-request token, or {@code null} to send none
     * @param  echoedUserId an identifier to write into the echoed navigation record, or {@code null}
     * @param  echoedUserType a user-type code to write into the echoed record, or {@code null}
     * @return the completed result, so status and body can both be read
     * @throws Exception if the request cannot be dispatched
     */
    private MvcResult submit(final String credential, final String retryToken,
            final String echoedUserId, final String echoedUserType) throws Exception {
        var request = post(REPORT_REQUEST_ROUTE)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content(confirmedRangeRequest(echoedUserId, echoedUserType));
        if (credential != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, credential);
        }
        if (retryToken != null) {
            request = request.header(RETRY_TOKEN_HEADER, retryToken);
        }
        return securedBoundary().perform(request).andReturn();
    }

    /**
     * The transmitted screen: the operator-range marker, the six date parts and the confirmation.
     *
     * @param  echoedUserId an identifier to write into the echoed navigation record, or {@code null}
     * @param  echoedUserType a user-type code to write into the echoed record, or {@code null}
     * @return the request body
     */
    private static String confirmedRangeRequest(final String echoedUserId,
            final String echoedUserType) {
        final StringBuilder echoed = new StringBuilder("{\"fromTransactionId\":\"CR00\","
                + "\"programContext\":\"REENTER\"");
        if (echoedUserId != null) {
            echoed.append(",\"userId\":\"").append(echoedUserId).append('"');
        }
        if (echoedUserType != null) {
            echoed.append(",\"userType\":\"").append(echoedUserType).append('"');
        }
        echoed.append('}');
        return "{\"customSelection\":\"S\","
                + "\"startMonth\":\"03\",\"startDay\":\"01\",\"startYear\":\"2020\","
                + "\"endMonth\":\"03\",\"endDay\":\"31\",\"endYear\":\"2020\","
                + "\"confirm\":\"Y\",\"keyAction\":\"ENTER\","
                + "\"navigationContext\":" + echoed + "}";
    }

    /**
     * Drains the queue, stopping at a bound above one complete stream.
     *
     * <p>The bound is deliberately higher than a stream so that a doubled stream is <em>observable</em>
     * rather than truncated to the expected count: asking for exactly seventeen would report seventeen
     * whether the queue held seventeen or thirty-four.
     *
     * @param  bound how many messages to stop after
     * @return the delivered bodies, in delivery order
     */
    private static List<String> drainedBodies(final int bound) {
        final List<Message> messages = drainQueue(jobSubmissionQueueUrl(), bound);
        final List<String> bodies = new ArrayList<>(messages.size());
        for (final Message message : messages) {
            bodies.add(message.body());
        }
        return bodies;
    }

    @Nested
    @DisplayName("a submission requires a credential the chain verifies")
    class TheCredentialRequirement {

        /** Creates the nested specification. */
        TheCredentialRequirement() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("a request presenting no credential is refused as unauthorized and publishes nothing, "
                + "so a refusal is not a refusal in the reply and a submission in the estate")
        void aRequestWithNoCredentialPublishesNothing() throws Exception {
            final MvcResult refused = submit(null, tokenFor("no-credential"), null, null);

            assertThat(refused.getResponse().getStatus())
                    .as("no identity was established, so there is no entitlement to have been found "
                            + "wanting - exactly unauthorized")
                    .isEqualTo(401);
            assertThat(drainedBodies(1))
                    .as("and the queue is untouched, which a reply-only assertion could never establish")
                    .isEmpty();
        }

        @Test
        @DisplayName("a request presenting an unverifiable credential is refused the same way and "
                + "likewise publishes nothing")
        void aRequestWithAnUnverifiableCredentialPublishesNothing() throws Exception {
            final MvcResult refused = submit(BEARER_PREFIX + "not-a-credential-this-chain-would-mint",
                    tokenFor("unverifiable-credential"), null, null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(401);
            assertThat(drainedBodies(1)).isEmpty();
        }

        @Test
        @DisplayName("a request presenting a credential the chain verifies is served and publishes one "
                + "complete stream")
        void aVerifiedCredentialPublishesOneCompleteStream() throws Exception {
            final MvcResult served = submit(credentialFor(FIRST_OPERATOR, UserType.ADMIN),
                    tokenFor("verified-credential"), null, null);

            assertThat(served.getResponse().getStatus())
                    .as("every outcome of this transaction is a screen the legacy program composed and "
                            + "sent, so the turn completes")
                    .isEqualTo(200);
            assertThat(drainedBodies(CARD_COUNT + 1))
                    .as("one complete stream and not one card more")
                    .hasSize(CARD_COUNT);
        }
    }

    @Nested
    @DisplayName("one operator's retry token cannot reach another operator's submission")
    class TheSubmissionNamespace {

        /** Creates the nested specification. */
        TheSubmissionNamespace() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("two operators presenting the SAME retry token for the same period each publish a "
                + "complete stream, because the authenticated operator scopes the deduplication namespace")
        void twoOperatorsSharingATokenEachPublishAStream() throws Exception {
            // THE DEFECT THIS ASSERTS IS CLOSED. With the operator left out of the submission identity,
            // the identity was a function of the dates and the token alone - so the second operator's
            // seventeen cards were collapsed as duplicates of the first's and the second operator was told
            // its request had been submitted, having published nothing at all. Two REAL identities are
            // what make this observable: handed-in principals would both carry the same subject.
            final String shared = tokenFor("two-operators-one-token");

            assertThat(submit(credentialFor(FIRST_OPERATOR, UserType.ADMIN), shared,
                    null, null).getResponse().getStatus()).isEqualTo(200);
            assertThat(submit(credentialFor(SECOND_OPERATOR, UserType.USER), shared,
                    null, null).getResponse().getStatus()).isEqualTo(200);

            assertThat(drainedBodies(2 * CARD_COUNT + 1))
                    .as("two complete streams: neither operator suppressed the other")
                    .hasSize(2 * CARD_COUNT);
        }

        @Test
        @DisplayName("the SAME operator repeating a retry token for the same period does not double its "
                + "stream, because the identity is a pure function of the range, the token and the operator")
        void oneOperatorRepeatingATokenDoesNotDoubleItsStream() throws Exception {
            final String credential = credentialFor(FIRST_OPERATOR, UserType.ADMIN);
            final String repeated = tokenFor("one-operator-repeating");

            assertThat(submit(credential, repeated, null, null)
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(submit(credential, repeated, null, null)
                    .getResponse().getStatus()).isEqualTo(200);

            assertThat(drainedBodies(2 * CARD_COUNT + 1))
                    .as("an interrupted request resubmitted under the same token completes its stream "
                            + "rather than doubling behind itself")
                    .hasSize(CARD_COUNT);
        }

        @Test
        @DisplayName("the same operator submitting the same period WITHOUT a token publishes a second "
                + "stream, because a fresh logical request is what the legacy queue did unconditionally")
        void thesameOperatorWithoutATokenPublishesASecondStream() throws Exception {
            final String credential = credentialFor(FIRST_OPERATOR, UserType.ADMIN);

            assertThat(submit(credential, null, null, null).getResponse().getStatus()).isEqualTo(200);
            assertThat(submit(credential, null, null, null).getResponse().getStatus()).isEqualTo(200);

            assertThat(drainedBodies(2 * CARD_COUNT + 1))
                    .as("the estate appended a second request for the same period and ran the job again; "
                            + "that remains reachable, and it is the absence of a token that reaches it")
                    .hasSize(2 * CARD_COUNT);
        }
    }

    @Nested
    @DisplayName("the namespace comes from the established identity and never from the echoed record")
    class TheEchoedIdentityIsIgnored {

        /** Creates the nested specification. */
        TheEchoedIdentityIsIgnored() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("a caller that writes another operator's identifier into the echoed record still "
                + "publishes in its own namespace, so it can neither borrow nor suppress that operator")
        void anEchoedIdentifierNeitherBorrowsNorSuppresses() throws Exception {
            // The first operator submits under its own identity. The second then submits the same period
            // under the same token while claiming, in the record it echoes back, to be the first operator
            // and to hold the administrative type. Were the echoed record consulted, the two identities
            // would coincide and the queue would collapse the second stream - so the second stream
            // arriving is the assertion that the echoed value decided nothing.
            final String shared = tokenFor("echoed-identifier");

            assertThat(submit(credentialFor(FIRST_OPERATOR, UserType.ADMIN), shared,
                    null, null).getResponse().getStatus()).isEqualTo(200);
            assertThat(submit(credentialFor(SECOND_OPERATOR, UserType.USER), shared,
                    FIRST_OPERATOR, UserType.ADMIN.getCode()).getResponse().getStatus()).isEqualTo(200);

            assertThat(drainedBodies(2 * CARD_COUNT + 1))
                    .as("two complete streams: the echoed identifier did not move the second submission "
                            + "into the first operator's namespace")
                    .hasSize(2 * CARD_COUNT);
        }

        @Test
        @DisplayName("and an echoed identifier does not stand in for an absent credential, so it cannot "
                + "become an identity of its own")
        void anEchoedIdentifierIsNotACredential() throws Exception {
            final MvcResult refused = submit(null, tokenFor("echoed-not-a-credential"), FIRST_OPERATOR,
                    UserType.ADMIN.getCode());

            assertThat(refused.getResponse().getStatus())
                    .as("a client-supplied identifier is not a credential, however well formed it is")
                    .isEqualTo(401);
            assertThat(drainedBodies(1)).isEmpty();
        }
    }

    /**
     * The report graph, this run's emulator, and the shipped security chain in front of it.
     *
     * <p>The report half is the same set of collaborators {@code AwsIntegrationIT} wires, for the same
     * reasons its own account gives. What is added is the security half - the chain, the token provider,
     * the sign-on state reader, the refusal renderer and the boundary configuration - plus the credential
     * repository the provider reads a record through. Nothing is stubbed on the security path: this class
     * exists precisely to exercise it.
     *
     * <p>The two auto-configurations the security half needs are the Jackson one, because a refusal body
     * is rendered as JSON, and the four AWS ones the queue path needs. No application auto-configuration
     * is imported, so this specification still owns no data source, no migration and no persistence unit -
     * the credential repository is the fixture's in-memory view.
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({CredentialsProviderAutoConfiguration.class,
            RegionProviderAutoConfiguration.class, AwsAutoConfiguration.class,
            SqsAutoConfiguration.class, JacksonAutoConfiguration.class})
    @Import({AwsConfig.class, ReportController.class, ReportContractAdapter.class,
            ConversationStateAdapter.class, GlobalExceptionHandler.class, ReportRequestService.class,
            JobSubmissionService.class, DateValidationService.class, MessageCatalogService.class,
            NavigationService.class, SecurityConfig.class, JwtTokenProvider.class,
            SignOnStateService.class, JsonRefusalBodyRenderer.class, WebMvcConfig.class})
    static class SecuredReportBridgeContext {

        /** Creates the configuration. */
        SecuredReportBridgeContext() {
            // Intentionally empty: this slice contributes beans, not state.
        }

        /**
         * The pinned clock every collaborator in the graph reads, the token provider included.
         *
         * @return the shared fixed clock
         */
        @Bean
        Clock fixedClock() {
            return PINNED_CLOCK;
        }

        /**
         * The credential records the provider reads, as the fixture's in-memory view.
         *
         * @return the repository view
         */
        @Bean
        UserSecurityRepository userSecurityRepository() {
            return CREDENTIAL_MASTER.repository();
        }

        /**
         * The registry the boundary's turn timer is registered against.
         *
         * @return an in-memory registry
         */
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        /**
         * The registry the outbound publish observation is recorded against.
         *
         * @return a no-op registry, because this specification asserts messages and not observations
         */
        @Bean
        ObservationRegistry observationRegistry() {
            return ObservationRegistry.NOOP;
        }

        /**
         * The serialization boundary one card stream is published inside.
         *
         * @return an in-process boundary that runs the work and returns its outcome
         */
        @Bean
        JobSubmissionCoordinator submissionCoordinator() {
            return submission -> submission.get();
        }

        /**
         * The notification client, taken from the emulator this run started.
         *
         * @return the shared client bound to the running emulator
         */
        @Bean
        SnsClient notificationClient() {
            return snsClient();
        }
    }
}
