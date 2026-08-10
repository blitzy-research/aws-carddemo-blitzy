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

import io.awspring.cloud.autoconfigure.s3.S3ClientCustomizer;
import io.awspring.cloud.autoconfigure.sns.SnsClientCustomizer;
import io.awspring.cloud.autoconfigure.sqs.SqsAsyncClientCustomizer;
import io.awspring.cloud.sns.core.CachingTopicArnResolver;
import io.awspring.cloud.sns.core.TopicArnResolver;
import io.awspring.cloud.sns.core.TopicsListingTopicArnResolver;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.DefaultRetryStrategy;
import software.amazon.awssdk.retries.api.BackoffStrategy;
import software.amazon.awssdk.retries.api.RetryStrategy;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * Configures the object-store, queue and notification clients that stand in for the legacy estate's
 * batch datasets and for its single online-to-batch bridge, and registers the settings those three
 * clients are aimed by.
 *
 * <h2>The one bridge this class exists to carry</h2>
 *
 * <p>{@code app/csd/CARDDEMO.CSD} defines sixty-four CICS resources and exactly one transient-data
 * queue, at lines 499 to 505. That queue is named {@code JOBS}, sits in the {@code CARDDEMO} group, is
 * described there as submitting jobs from the online region, and is the whole of the path from an
 * online request to a batch job: one program writes to it and nothing else in the estate does. Five of
 * its attributes are behavioural rather than decorative, and each one has a first-in-first-out
 * realisation that has to survive the migration intact.
 *
 * <table class="striped">
 *   <caption>The queue definition's five load-bearing attributes and where each is honoured</caption>
 *   <tr><th>Attribute</th><th>Realisation</th><th>Enforced by</th></tr>
 *   <tr>
 *     <td>{@code RECORDSIZE(80)} with {@code RECORDFORMAT(FIXED)}</td>
 *     <td>one card is one message, every body exactly eighty characters, space padded, never trimmed</td>
 *     <td>the publisher, which measures every card's encoded width before it publishes</td>
 *   </tr>
 *   <tr>
 *     <td>{@code DISPOSITION(MOD)}</td>
 *     <td>append order preserved, which a first-in-first-out queue guarantees only within one message
 *         group, so one stable group identifier carries a whole submission</td>
 *     <td>the publisher, from {@link AwsProperties.Sqs#messageGroupId()}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code ERROROPTION(IGNORE)}</td>
 *     <td>a refused publish is logged and reported through a return value, never rethrown at the
 *         caller and never retried</td>
 *     <td>the publisher's structure, and the single-attempt strategy installed below</td>
 *   </tr>
 *   <tr>
 *     <td>{@code OPENTIME(INITIAL)}</td>
 *     <td>the resource exists before the first write, so nothing is created on demand</td>
 *     <td>this class, by building no resource; the emulator bootstrap script locally and
 *         infrastructure in a deployment provision them</td>
 *   </tr>
 *   <tr>
 *     <td>{@code TYPEFILE(OUTPUT)}</td>
 *     <td>publish only; this module consumes nothing from the job queue</td>
 *     <td>this class, by registering no listener and no consumer</td>
 *   </tr>
 * </table>
 *
 * <h2>Why the attempt count is a parity statement and not a tuning choice</h2>
 *
 * <p>The legacy write site is one paragraph, at {@code app/cbl/CORPT00C.cbl} lines 515 to 535. It
 * writes one card with one transient-data-queue write, taking its length from the single
 * eighty-character transmit buffer declared at line 79, and inspects that write's response
 * immediately. A normal response continues; any other response records diagnostics, raises the
 * program's error flag, sets the operator-visible failure message that names the queue, repositions the
 * cursor and re-sends the screen. There is no abend, no rollback, and - decisively for this class - no
 * second attempt.
 *
 * <p>The messaging client this module publishes through does not behave that way by default. Its
 * standard strategy treats a refused transport as transient and reissues the request, so one logical
 * card write can become several attempts on the wire. That is a behavioural difference rather than a
 * preference, and it is observable in two ways that matter. A queue that accepted a card on a later
 * attempt would turn a failure the legacy program reports into a success it never had, changing which
 * cards reach the queue and therefore whether the submitted job stream is complete. And a retried
 * publish that eventually succeeded would deliver its card after the publisher had already been told
 * the write failed, which the append-ordered contract cannot absorb.
 *
 * <p>{@link #singleAttemptSqsClientCustomizer()} removes the retry so that one card write is one
 * attempt, matching the legacy write exactly. Removing a retry is the <em>absence</em> of a figure
 * rather than the choice of one, which is what keeps that part a parity statement.
 *
 * <h2>Why every call is nevertheless bounded, and why a bound is not a performance target</h2>
 *
 * <p>An attempt count of one says how many times a call is made. It says nothing about how long one
 * call may take, and the transport's own inactivity time-outs do not close that gap: a peer that
 * answers a byte at a time, or accepts a connection and then answers nothing further while keeping the
 * socket alive, is never inactive and is never abandoned. Left unbounded that is not a slow call, it is
 * a lost thread, and each of the three clients loses a different one. A queue publish runs on the
 * report-request thread inside the deployment-wide submission guard, so a hung publish holds a request
 * thread <em>and</em> a database transaction holding the advisory lock that every other replica's
 * submission waits on. An object-store call runs inside the generation publication lock, so a hung
 * upload pins one connection and blocks every later publication of that base. A notification runs on
 * the single-slot notifier, so a hung publish stops every subsequent job-completion notice.
 *
 * <p>Each client is therefore given an explicit total budget and an explicit per-attempt budget, and
 * the values are <strong>ceilings, not targets</strong>. They encode no expectation about latency,
 * they are not derived from any measurement, they must not be read as a service level and they must not
 * be "tuned" towards one - {@code docs/gate-evidence.md} Gate 3 is where measured figures live, and it
 * records that no legacy baseline exists to compare against. What the ceilings do encode is the answer
 * to one question per client: how long may this call occupy the resource it holds before the caller is
 * told it failed? A budget expiring is reported exactly as a refusal is, so on the queue path the
 * legacy behaviour is preserved rather than altered - the operator learns the write did not happen,
 * which is what the writing program's own error path did, and which is what an unbounded wait denies
 * them.
 *
 * <p><strong>Waiting for a connection is bounded by the same two figures, and deliberately not by a
 * third.</strong> Connection acquisition and any wait in the pending-acquire queue happen <em>inside</em>
 * one attempt - the per-attempt budget is enforced around the whole attempt, transport included - so an
 * attempt that spends its entire budget waiting for a connection is abandoned exactly as one that spends
 * it waiting for bytes, and the total budget then bounds the sum of every attempt plus its backoff. The
 * alternative would be an acquisition time-out on the transport itself, which cannot be set without
 * building or replacing the HTTP client, and replacing it would take over the transport the cloud
 * integration owns - the same objection that keeps this class customizing rather than publishing clients
 * of its own. Two figures per client that provably bound acquisition are preferable to a third that
 * arrives with a transport this module would then have to own.
 *
 * <p>The legacy estate bounded the same calls, and did so outside the program: an online transaction
 * ran under the region's transaction time-out and a batch step under the job's own limit, so no legacy
 * write could wait for ever either. Reproducing a bound is therefore closer to the source than omitting
 * one; what the source does not supply is the figure, which is why the figures here are stated as
 * ceilings with their reasoning rather than as parity claims.
 *
 * <h2>Why each retry strategy is pinned rather than left ambient</h2>
 *
 * <p>An unpinned strategy is not "the default", it is whichever mode the host environment resolves -
 * the {@code AWS_RETRY_MODE} variable, the matching system property and the shared configuration file
 * all select one, and none of them is visible to this application. Two of the three clients cannot
 * tolerate that. The notification client publishes a job-completion notice, and a retried publish that
 * the service had already accepted delivers the notice twice, so a subscriber counting completions
 * counts one job as two; it is therefore pinned to a single attempt, which is the same shape as the
 * queue and for a related reason. The object-store client performs operations that are safe to repeat -
 * a listing is read-only, a versioned delete names its version, and an upload writes a key this module
 * allocated one past the highest generation, so a repeat overwrites only its own bytes - so it is
 * pinned to a small, fixed, <em>deterministic</em> schedule: a fixed delay without jitter, so two runs
 * of the same failure behave identically and a test can assert the schedule rather than sample it.
 *
 * <h2>Why a failed publish stops the rest of the submission</h2>
 *
 * <p>The emit loop at {@code app/cbl/CORPT00C.cbl} lines 498 to 508 walks the card table upward from
 * the first entry, moves each line into the transmit buffer, and stops on three conditions: the table
 * bound, an end-of-stream card, or the program's error flag. Two consequences follow and both are
 * contract.
 *
 * <p>The first is that <strong>the end-of-file sentinel card is transmitted</strong>. The loop
 * evaluates the end-of-stream test and sets its flag <em>before</em> performing the write, not instead
 * of it, so the sentinel is written and the loop terminates on the following iteration. A submission is
 * therefore seventeen messages and not sixteen, and acceptance drains a real queue to confirm it.
 *
 * <p>The second is that the error flag is one of the loop's own exit conditions, so a refused write
 * ends the submission where it stood. The faithful reading of {@code ERROROPTION(IGNORE)} is therefore
 * narrower than the attribute name suggests: log the failure, do not throw to the caller, do not retry,
 * and do not publish the remaining cards. Both behaviours belong to
 * {@code com.carddemo.service.JobSubmissionService}; this class only makes the single-attempt part of
 * them true of the transport.
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * <p><strong>It builds no client.</strong> The object-store, queue and notification clients stay
 * auto-configured by the cloud integration's starters, and the three beans below are customizers the
 * integration collects and applies to each client builder after it has finished configuring it.
 * Publishing clients of this class's own would take over credential resolution and the object store's
 * addressing style from the {@code spring.cloud.aws} settings that every profile document declares,
 * leaving two sources of truth for one setting - and for the endpoint that is actively dangerous,
 * because an absent override does not fail closed but resolves the region's real public endpoint.
 * Customizing instead of replacing keeps one source of truth and changes only what this module has a
 * position on.
 *
 * <p><strong>It creates no resource.</strong> No bucket, queue or topic is created at start-up or on
 * first publication. Provisioning is outside this application. The shared baseline correspondingly
 * sets the queue-not-found strategy to refuse an unresolvable queue rather than create one, and
 * {@link #preProvisionedTopicArnResolver(SnsClient)} replaces the notification template's
 * create-on-resolution default with a listing resolver that can resolve only an existing topic.
 *
 * <p><strong>It registers no queue template.</strong> The auto-configured one already carries the
 * message conversion and observation wiring the integration installs, and the publisher supplies the
 * two per-message properties that matter - the stable message group identifier, and an explicit
 * deduplication identifier derived from each card's ordinal position. That second one is the subtlest
 * hazard in this whole bridge: several of the seventeen cards are comment or delimiter cards with
 * byte-identical bodies, so content-based deduplication would silently discard the duplicates and
 * shorten the job stream to something the reader would accept and the scheduler would misread. The
 * emulator bootstrap script therefore creates the queue with content-based deduplication switched off,
 * and the publisher supplies its own per-card identifier, so the hazard is closed twice over. Nothing
 * here may re-enable it.
 *
 * <p><strong>It reads no credential.</strong> Credential resolution follows the software development
 * kit's own provider chain, so a deployment supplies its credentials to the environment and this class
 * never sees, binds or logs one. No credential appears in this file, in its log lines, or in any
 * message it produces.
 *
 * <h2>Where the three clients are aimed, and by what</h2>
 *
 * <p>{@link AwsProperties#region()} and {@link AwsProperties#endpointOverride()} are the two settings
 * that decide where the four resource names are resolved, and both are bound in this module's own
 * namespace. The customizers below apply them, which is what makes them load bearing rather than
 * merely validated: a key that is bound while nothing consumes it advertises an adjustability that does
 * not exist.
 *
 * <p>The region is applied unconditionally, because every client must resolve its endpoints somewhere
 * and every profile derives the integration's own region setting from this same key, so the two cannot
 * name different regions. The endpoint redirection is applied <strong>only when one is configured</strong>.
 * That conditional is genuine rather than cosmetic: a deployment declares no redirection, so the
 * decision is taken once, from {@link AwsProperties#endpointOverrideUri()}, rather than three times at
 * three builders with one of them getting it wrong. No emulator address is written into this file, and
 * no redirection is defaulted to a non-empty value.
 *
 * <p>A production deployment cannot reach the redirecting branch at all:
 * {@link ProductionConfigurationValidator} refuses the module's endpoint key and the integration's four
 * under that profile, on the reasoning that a well-formed redirection is still a redirection and only
 * the deployment knows whether its destination is its own.
 *
 * <h2>Why this class also owns the settings type</h2>
 *
 * <p>{@link AwsProperties} is self-annotated only: it carries no stereotype and nothing scans for it,
 * so it becomes a bean exactly where a configuration class enables it. This class is that single owner,
 * and the enabling annotation below is the whole of the registration - {@link SecurityConfig} owns the
 * signing settings by the same rule. One owner is deliberate rather than incidental: two registrations
 * of one settings type are two bean definitions of it, and the second is discovered as a context
 * failure rather than as a duplicate.
 *
 * <p>Registration is what turns that type's validation into a start-up gate rather than a decoration.
 * Unregistered, it binds nowhere, its presence constraints never run, and its refusal of a queue name
 * that omits the first-in-first-out suffix is never reached - so the single easiest misconfiguration in
 * this namespace would surface at the first submission, where an operator sees a failed report request
 * instead of a failed deployment. Registered here, a missing or blanked bucket, queue, message group,
 * topic or region, and a well-formed queue name that is merely not a first-in-first-out name, all stop
 * the application before it serves a request.
 *
 * <p>The publisher itself does not receive this object. The layering forbids it - a service may depend
 * on the repository, domain, utility and exception layers, and this configuration layer is not among
 * them - so {@code com.carddemo.service.JobSubmissionService} binds the two queue key paths it needs
 * directly and names them through its own constants. The two sets of constants are held to each other
 * by a test that fails the build if they ever diverge, so the arrangement is a checked invariant rather
 * than a convention.
 *
 * <p>The reasoning for one write being one attempt is recorded in {@code docs/decision-log.md} DL-095;
 * the call budgets and the pinned retry strategies, including why each figure is a ceiling rather than a
 * target and why the object store is the one client that repeats an operation, are DL-301;
 * the choice to aim the three clients from this module's own namespace by customizing them rather than
 * publishing clients of this class's own is DL-130; and the naming of the four resources is DL-092.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AwsProperties.class)
public class AwsConfig {

    /**
     * Logger. Never receives a credential, and never receives the configured endpoint redirection
     * itself: a diagnostic states which key decided something and what it decided, not the value, per
     * {@code docs/decision-log.md} DL-041.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AwsConfig.class);

    /** Names the object-store client in diagnostics, so one message identifies which client it describes. */
    private static final String OBJECT_STORE_CLIENT = "object-store";

    /** Names the queue client in diagnostics. */
    private static final String QUEUE_CLIENT = "queue";

    /** Names the notification client in diagnostics. */
    private static final String NOTIFICATION_CLIENT = "notification";

    /*
     * THE NINE FIGURES BELOW ARE PACKAGE-PRIVATE ON PURPOSE. Each one is a ceiling this class chose,
     * and a ceiling that only the class can see is a ceiling nothing can hold it to: a test able to
     * read the applied configuration but not the intended figure can assert only that SOMETHING was
     * set, which is satisfied by a wrong value as readily as by the right one. Published to the
     * package, the same test asserts the exact figure reached the client, so a change to any of them is
     * a change a reader sees rather than a change that silently widens a bound. They carry no secret
     * and no address; each is a duration or a count.
     */

    /**
     * Total budget for one object-store call, from the first attempt to the last.
     *
     * <p>The longest object-store call this module makes is the upload of one completed batch
     * generation, streamed from a local file, so the ceiling has to admit a whole statement, report or
     * archive generation rather than a small request. It is a ceiling on how long the generation
     * publication lock may be held by a single unresponsive call, and nothing else: see the class
     * comment for why that is not a latency expectation.</p>
     */
    static final Duration OBJECT_STORE_CALL_BUDGET = Duration.ofMinutes(2L);

    /** Per-attempt budget for one object-store call, so one stalled attempt cannot consume the total. */
    static final Duration OBJECT_STORE_ATTEMPT_BUDGET = Duration.ofSeconds(45L);

    /**
     * Total budget for one card publish.
     *
     * <p>Deliberately short. One card is eighty bytes, the publish holds a request thread and the
     * database transaction carrying the deployment-wide submission lock, and a submission is seventeen
     * of them in sequence - so the ceiling bounds the whole submission to a duration an operator waiting
     * on the report screen can be answered within, and bounds the lock every other replica queues
     * behind.</p>
     */
    static final Duration QUEUE_CALL_BUDGET = Duration.ofSeconds(10L);

    /**
     * Per-attempt budget for one card publish.
     *
     * <p>Stated even though the queue makes exactly one attempt. The two settings bound different
     * things - the total bounds the operation, the per-attempt bounds one request on the wire - and
     * leaving the second unset would make the single-attempt strategy the only thing standing between a
     * drip-feeding peer and an unbounded wait.</p>
     */
    static final Duration QUEUE_ATTEMPT_BUDGET = Duration.ofSeconds(8L);

    /** Total budget for one job-completion notification, which carries a bounded payload of its own. */
    static final Duration NOTIFICATION_CALL_BUDGET = Duration.ofSeconds(10L);

    /** Per-attempt budget for one job-completion notification. */
    static final Duration NOTIFICATION_ATTEMPT_BUDGET = Duration.ofSeconds(8L);

    /** Attempts an object-store operation is repeated for, counting the first. */
    static final int OBJECT_STORE_MAX_ATTEMPTS = 3;

    /** Fixed, jitter-free delay between object-store attempts, so the schedule is reproducible. */
    static final Duration OBJECT_STORE_RETRY_DELAY = Duration.ofMillis(200L);

    /** Fixed, jitter-free delay applied when the object store reports throttling. */
    static final Duration OBJECT_STORE_THROTTLE_DELAY = Duration.ofMillis(500L);

    /**
     * The bound settings the three customizers below aim their clients by.
     *
     * <p>Held rather than re-read because the region and the endpoint redirection have to be applied
     * identically to all three builders, and because the type has already refused an unusable
     * redirection by the time it is injected.</p>
     */
    private final AwsProperties awsProperties;

    /**
     * Creates the configuration from the bound settings.
     *
     * <p>Constructor injection rather than a field or a setter, so the settings are present before any
     * customizer can be produced and the field can be final. Nothing else is injected: no client, no
     * template and no credential provider, because this class configures the clients the starters build
     * rather than building any itself.</p>
     *
     * @param awsProperties the bound resource names, region and optional endpoint redirection; must not
     *                      be {@code null}
     * @throws NullPointerException if {@code awsProperties} is {@code null}
     */
    public AwsConfig(final AwsProperties awsProperties) {
        this.awsProperties = Objects.requireNonNull(awsProperties, "awsProperties must not be null");
        LOG.info("AWS bridge settings bound: region={}, staging bucket={}, job queue={},"
                        + " message group={}, notification topic={}, endpoint redirection configured={}",
                awsProperties.region(),
                awsProperties.s3().batchStagingBucket(),
                awsProperties.sqs().jobQueue(),
                awsProperties.sqs().messageGroupId(),
                awsProperties.sns().jobNotificationTopic(),
                awsProperties.hasEndpointOverride());
    }

    /**
     * Aims the object-store client at the region and, when one is configured, at the emulator.
     *
     * <p>The object store carries the staged batch input and output that replaced the legacy sequential
     * datasets and generation-data-group bases. Nothing about the bucket itself is set here: the bucket
     * is not created, its addressing style stays whatever the integration's own settings declare, and
     * the key an object is written under is composed by the job that writes it.</p>
     *
     * <p>It is the one client of the three that repeats a failed operation, because it is the one whose
     * operations can be repeated safely: a listing changes nothing, a delete names the exact version it
     * removes, and an upload writes a key this module allocated one past the highest generation the base
     * holds, so a second attempt can only overwrite the bytes the first attempt wrote. The schedule is
     * pinned rather than left to the host, and it is jitter-free so that it is reproducible.</p>
     *
     * @return a customizer that applies the region, the endpoint redirection when one is configured, the
     *         two call budgets and the pinned deterministic retry schedule to the object-store client
     *         builder; never {@code null}
     */
    @Bean
    public S3ClientCustomizer batchStagingS3ClientCustomizer() {
        return builder -> aimClient(builder, OBJECT_STORE_CLIENT, OBJECT_STORE_CALL_BUDGET,
                OBJECT_STORE_ATTEMPT_BUDGET, deterministicObjectStoreRetryStrategy());
    }

    /**
     * The fixed, jitter-free retry schedule the object-store client is pinned to.
     *
     * <p>Built here rather than held as a constant because a strategy carries a circuit breaker with
     * state of its own, and one instance per client builder keeps that state where the client is. The
     * schedule itself is entirely determined by the three constants above: a fixed delay without jitter
     * for a retryable failure, a longer fixed delay without jitter for a throttled one, and a small
     * attempt ceiling counting the first attempt. Nothing here samples a clock or a random source, so
     * two runs of the same failure produce the same schedule and a test can assert it.</p>
     *
     * @return a standard strategy with a pinned attempt ceiling and pinned jitter-free backoff
     */
    private static RetryStrategy deterministicObjectStoreRetryStrategy() {
        return DefaultRetryStrategy.standardStrategyBuilder()
                .maxAttempts(OBJECT_STORE_MAX_ATTEMPTS)
                .backoffStrategy(BackoffStrategy.fixedDelayWithoutJitter(OBJECT_STORE_RETRY_DELAY))
                .throttlingBackoffStrategy(
                        BackoffStrategy.fixedDelayWithoutJitter(OBJECT_STORE_THROTTLE_DELAY))
                .build();
    }

    /**
     * Aims the queue client at the region and, when one is configured, at the emulator, and reduces it
     * to the single publish attempt the legacy queue write made.
     *
     * <p>The single attempt is the parity statement, developed in the class comment: one legacy
     * transient-data-queue write was one write, and a queue that accepted a card on a later attempt would
     * turn a failure the legacy program reported into a success it never had.</p>
     *
     * <p>The two budgets alongside it are not a parity statement and are not a target. They bound how
     * long one card publish may hold the report-request thread and the database transaction carrying the
     * deployment-wide submission lock before the caller is told the write failed - which is the outcome
     * the legacy error path produced, and which an unbounded wait denies the operator. See the class
     * comment for why a single attempt does not bound a call, and {@link #aimClient} for how all three
     * settings are written onto the builder without discarding what the integration established.</p>
     *
     * @return a customizer that applies the region, the endpoint redirection when one is configured, the
     *         two call budgets and the single-attempt strategy to the queue client builder; never
     *         {@code null}
     */
    @Bean
    public SqsAsyncClientCustomizer singleAttemptSqsClientCustomizer() {
        return builder -> aimClient(builder, QUEUE_CLIENT, QUEUE_CALL_BUDGET, QUEUE_ATTEMPT_BUDGET,
                DefaultRetryStrategy.doNotRetry());
    }

    /**
     * Aims the notification client at the region and, when one is configured, at the emulator.
     *
     * <p>The notification topic has no legacy resource behind it - an operator learned a job's outcome
     * from the job's own output - so nothing here reproduces a legacy attribute. The topic is not
     * created, and no subscription is registered: this module publishes job-completion and operational
     * messages and consumes none.</p>
     *
     * <p>It is pinned to one attempt, and that is a correctness decision rather than a parity one. A
     * completion notice records that a named job execution ended with a named status; a retry of a
     * publish the service had already accepted delivers the same notice twice, and a subscriber has no
     * way to tell the second copy from a second run. The channel is defined non-fatal end to end - the
     * job's verdict is already final and a lost notice loses a notice - so declining to retry trades a
     * duplicate for a loss in the direction the channel is already documented to trade it.</p>
     *
     * @return a customizer that applies the region, the endpoint redirection when one is configured, the
     *         call budgets and the single-attempt strategy to the notification client builder; never
     *         {@code null}
     */
    @Bean
    public SnsClientCustomizer jobNotificationSnsClientCustomizer() {
        return builder -> aimClient(builder, NOTIFICATION_CLIENT, NOTIFICATION_CALL_BUDGET,
                NOTIFICATION_ATTEMPT_BUDGET, DefaultRetryStrategy.doNotRetry());
    }

    /**
     * Resolves only pre-provisioned notification topics.
     *
     * <p>The notification template's library default resolves a topic name through an idempotent create
     * call. That is convenient and violates this module's infrastructure boundary: a misspelled name
     * would create dead infrastructure and let publication appear successful. Listing the topics and
     * refusing an absent name preserves the contract that LocalStack bootstrap or deployment
     * infrastructure owns topic creation. The cache avoids repeating the list operation after one
     * topic has been resolved and creates no application-owned resource.
     *
     * @param snsClient auto-configured notification client
     * @return resolver that finds existing topics and never creates one
     */
    @Bean
    public TopicArnResolver preProvisionedTopicArnResolver(final SnsClient snsClient) {
        return new CachingTopicArnResolver(
                new TopicsListingTopicArnResolver(
                        Objects.requireNonNull(snsClient, "snsClient must not be null")));
    }

    /**
     * Applies the region unconditionally and the endpoint redirection only when one is configured.
     *
     * <p>One helper serves all three clients so that the decision "is a redirection configured" is
     * taken once from {@link AwsProperties#endpointOverrideUri()} rather than three times at three
     * builders, where getting it wrong once means one client silently addressing a real account. The
     * absent case is not a fault and is not defaulted: a client with no redirection resolves the
     * region's own endpoint, which is exactly what a deployment wants and what a production profile
     * enforces.</p>
     *
     * <h2>What the absent case does and does not assert</h2>
     *
     * <p>Not calling {@code endpointOverride(...)} means <strong>this application applies no
     * redirection</strong>. It does not, on its own, mean the client is unredirected: the SDK resolves
     * an endpoint from a chain of its own - {@code AWS_ENDPOINT_URL} and its per-service variants, the
     * matching {@code aws.endpointUrl*} system properties, and {@code endpoint_url} in the shared
     * configuration file - and no Spring property source participates in that chain, so
     * {@link AwsProperties#endpointOverrideUri()} cannot observe it.
     *
     * <p>The diagnostic below therefore states only what this class controls. Closing the SDK's own
     * chain is a deployment-posture question rather than a builder question, and it is enforced where
     * postures are enforced: {@link ProductionConfigurationValidator} refuses to start the production
     * profile when any arm of that chain supplies a redirection. An earlier revision of this method
     * logged that the client was "resolving the endpoint of region ..." with no such enforcement behind
     * it, which asserted a posture the process was not holding.
     *
     * <h2>Why the budgets and the strategy are applied here too</h2>
     *
     * <p>All three clients need bounding and all three need a pinned strategy, and applying them in one
     * place is what makes the three sets of figures comparable and the omission of one impossible.
     * Each client still supplies its <em>own</em> figures, because the three calls hold different
     * resources for different lengths of time; what is shared is the mechanism, not the value.</p>
     *
     * <p>The override configuration is extended rather than replaced. The convenience form that accepts
     * a consumer of a fresh configuration builder would look equivalent and is not: it constructs a new
     * configuration from nothing and then replaces the existing one wholesale, discarding the client
     * identification and the observation wiring the integration has already established while still
     * satisfying an assertion about the settings this method writes. Copying the current configuration
     * and adding to the copy preserves everything the integration set and changes only the three
     * properties named here. Because the customizer runs after the integration has finished, this is the
     * last writer of each of those three, so the result does not depend on customizer ordering.</p>
     *
     * @param builder        the client builder the integration has finished configuring
     * @param clientName     the client's name for diagnostics
     * @param callBudget     ceiling on one whole call, attempts included
     * @param attemptBudget  ceiling on one attempt of that call
     * @param retryStrategy  the pinned strategy, so the attempt count does not come from the host
     * @see ProductionConfigurationValidator#validateNativeSdkEndpointChannels
     */
    private void aimClient(final AwsClientBuilder<?, ?> builder, final String clientName,
            final Duration callBudget, final Duration attemptBudget,
            final RetryStrategy retryStrategy) {
        builder.region(Region.of(this.awsProperties.region()));
        builder.overrideConfiguration(builder.overrideConfiguration()
                .toBuilder()
                .apiCallTimeout(callBudget)
                .apiCallAttemptTimeout(attemptBudget)
                .retryStrategy(retryStrategy)
                .build());
        LOG.debug("{} client bounded: callBudget={} attemptBudget={} retryStrategy={}",
                clientName, callBudget, attemptBudget, retryStrategy.getClass().getSimpleName());
        this.awsProperties.endpointOverrideUri().ifPresentOrElse(
                redirection -> {
                    builder.endpointOverride(redirection);
                    LOG.debug("{} client redirected by {} away from the endpoint region {} resolves",
                            clientName, AwsProperties.ENDPOINT_OVERRIDE_PROPERTY,
                            this.awsProperties.region());
                },
                () -> LOG.debug("{} client left at region {} by this application; no {} configured."
                                + " The SDK's own endpoint chain is not visible here and is refused"
                                + " for the production profile by ProductionConfigurationValidator",
                        clientName, this.awsProperties.region(),
                        AwsProperties.ENDPOINT_OVERRIDE_PROPERTY));
    }
}
