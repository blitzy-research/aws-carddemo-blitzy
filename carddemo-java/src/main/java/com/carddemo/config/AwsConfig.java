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
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.DefaultRetryStrategy;
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
 * rather than the choice of one, which is what keeps it a parity statement: no attempt count, no
 * backoff interval, no time-out, no pool size and no queue capacity is set anywhere in this class.
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
     * @return a customizer that applies the region, and the endpoint redirection when one is
     *         configured, to the object-store client builder; never {@code null}
     */
    @Bean
    public S3ClientCustomizer batchStagingS3ClientCustomizer() {
        return builder -> aimClient(builder, OBJECT_STORE_CLIENT);
    }

    /**
     * Aims the queue client at the region and, when one is configured, at the emulator, and reduces it
     * to the single publish attempt the legacy queue write made.
     *
     * <p>The strategy is installed by copying the override configuration the integration has already
     * applied to the builder and adding the no-retry strategy to that copy. The convenience form that
     * accepts a consumer of a fresh configuration builder would look equivalent and is not: it
     * constructs a new configuration from nothing and then replaces the existing one wholesale,
     * discarding the client identification the messaging library sets for its own telemetry while still
     * satisfying an attempt-count assertion. Extending the current configuration preserves everything
     * the library established and changes only the attempt count.</p>
     *
     * <p>The customizer is applied after the integration has finished configuring the builder, so it is
     * the last writer of every property it touches; and every property it touches is set to one
     * definite value, so the result does not depend on the order customizers happen to run in.</p>
     *
     * @return a customizer that applies the region, the endpoint redirection when one is configured,
     *         and the single-attempt strategy to the queue client builder; never {@code null}
     */
    @Bean
    public SqsAsyncClientCustomizer singleAttemptSqsClientCustomizer() {
        return builder -> {
            aimClient(builder, QUEUE_CLIENT);
            builder.overrideConfiguration(builder.overrideConfiguration()
                    .toBuilder()
                    .retryStrategy(DefaultRetryStrategy.doNotRetry())
                    .build());
        };
    }

    /**
     * Aims the notification client at the region and, when one is configured, at the emulator.
     *
     * <p>The notification topic has no legacy resource behind it - an operator learned a job's outcome
     * from the job's own output - so nothing here reproduces a legacy attribute. The topic is not
     * created, and no subscription is registered: this module publishes job-completion and operational
     * messages and consumes none.</p>
     *
     * @return a customizer that applies the region, and the endpoint redirection when one is
     *         configured, to the notification client builder; never {@code null}
     */
    @Bean
    public SnsClientCustomizer jobNotificationSnsClientCustomizer() {
        return builder -> aimClient(builder, NOTIFICATION_CLIENT);
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
     * @param builder    the client builder the integration has finished configuring
     * @param clientName the client's name for diagnostics
     */
    private void aimClient(final AwsClientBuilder<?, ?> builder, final String clientName) {
        builder.region(Region.of(this.awsProperties.region()));
        this.awsProperties.endpointOverrideUri().ifPresentOrElse(
                redirection -> {
                    builder.endpointOverride(redirection);
                    LOG.debug("{} client redirected by {} away from the endpoint region {} resolves",
                            clientName, AwsProperties.ENDPOINT_OVERRIDE_PROPERTY,
                            this.awsProperties.region());
                },
                () -> LOG.debug("{} client resolving the endpoint of region {}; no {} configured",
                        clientName, this.awsProperties.region(),
                        AwsProperties.ENDPOINT_OVERRIDE_PROPERTY));
    }
}
