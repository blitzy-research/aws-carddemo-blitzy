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

import com.carddemo.util.AwsResourceNamingRules;
import com.carddemo.util.AwsResourcePolicyRules;
import com.carddemo.util.AwsResourcePolicyRules.PolicyPosture;
import com.carddemo.util.FailureDiagnostics;
import io.awspring.cloud.sns.core.SnsOperations;
import io.awspring.cloud.sns.core.TopicArnResolver;
import io.awspring.cloud.sns.core.TopicsListingTopicArnResolver;
import io.awspring.cloud.sqs.QueueAttributesResolver;
import io.awspring.cloud.sqs.listener.QueueAttributes;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetBucketEncryptionRequest;
import software.amazon.awssdk.services.s3.model.GetBucketEncryptionResponse;
import software.amazon.awssdk.services.s3.model.GetBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.GetPublicAccessBlockRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.PublicAccessBlockConfiguration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionRule;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.GetTopicAttributesRequest;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Establishes, before a production deployment serves anything, that the four cloud resources it is
 * pointed at are its own, are shaped the way its two contracts require, and can actually be written to.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>Three checks already stand between a production deployment and the wrong resource, and all three
 * judge <em>configuration</em>. {@link AwsProperties} refuses a malformed name, a bucket outside the
 * naming rules and a queue that is not a first-in-first-out queue.
 * {@link ProductionConfigurationValidator} refuses a missing required variable, an endpoint redirection
 * through any of its eight channels, and a queue destination that is not one this deployment could have
 * meant. {@link AwsConfig} then aims the three clients at the configured region and at nothing else.
 *
 * <p>What none of them establishes is anything about the resources themselves. A perfectly configured
 * deployment can be pointed at a bucket in another account, at a queue that somebody re-created without
 * first-in-first-out ordering, at a topic that exists but is not this deployment's, or at all three with
 * a credential that can read them and not write them - and every one of those states passes every check
 * above. Two properties of this module make the consequences silent rather than obvious.
 *
 * <ul>
 *   <li><strong>Both outbound channels are defined to tolerate a failure.</strong> The queue reproduces
 *       {@code ERROROPTION(IGNORE)}: a refused publish is logged and reported through a return value,
 *       never raised. The notification channel is non-fatal by construction. So a credential that cannot
 *       write produces report requests that answer normally and jobs that never run, and completion
 *       notices nobody receives - discovered by an operator wondering where the output went, days later.
 *   <li><strong>Reachability is not ownership.</strong> A readiness probe that asks "does this bucket
 *       exist" is answered YES by a bucket in an account this deployment does not own, and the
 *       deployment then writes statements, reports and rejected records - carrying account identifiers,
 *       card numbers and monetary balances - into it, reporting UP throughout.
 * </ul>
 *
 * <h2>What is verified, and how each property is established</h2>
 *
 * <table class="striped">
 *   <caption>The eleven properties this class establishes and the call that establishes each</caption>
 *   <tr><th>Property</th><th>Established by</th><th>Why it is load-bearing</th></tr>
 *   <tr>
 *     <td>the staging bucket is owned by the expected account</td>
 *     <td>{@code HeadBucket} carrying the expected owner</td>
 *     <td>the service itself compares the bucket's owning account and refuses the call when it differs,
 *         so ownership is enforced by the provider rather than asserted by this process</td>
 *   </tr>
 *   <tr>
 *     <td>the bucket carries object versioning</td>
 *     <td>{@code GetBucketVersioning}, status must be enabled</td>
 *     <td>versioning is what carries the retained-generation semantics of the legacy output datasets;
 *         without it the generation depths this module enforces retain nothing</td>
 *   </tr>
 *   <tr>
 *     <td>the bucket blocks every form of public access</td>
 *     <td>{@code GetPublicAccessBlock}, all four controls set</td>
 *     <td>the four controls are what stop a later access-control list or bucket policy from opening the
 *         bucket at all; without them a single mistaken grant makes statements, reports and rejected
 *         records - carrying account identifiers, card numbers and balances - world-readable, and
 *         nothing in this deployment would report differently</td>
 *   </tr>
 *   <tr>
 *     <td>the bucket's own policy opens it to nobody and refuses plain transport</td>
 *     <td>{@code GetBucketPolicy}, judged by {@link AwsResourcePolicyRules}</td>
 *     <td>the public-access controls bound what a policy may say; the policy is what actually says it,
 *         and it is also the only place a deployment can require that its data never crosses the
 *         network in clear text</td>
 *   </tr>
 *   <tr>
 *     <td>the bucket encrypts what is written to it by default</td>
 *     <td>{@code GetBucketEncryption}, an algorithm must be configured</td>
 *     <td>every object this module writes carries account identifiers, card numbers or monetary
 *         balances, and none of the writers names an algorithm per object - so the bucket's default is
 *         the whole of the at-rest protection</td>
 *   </tr>
 *   <tr>
 *     <td>the credential can write and remove an object</td>
 *     <td>one zero-length probe object written and then purged of every version</td>
 *     <td>the only property here that cannot be read; a read-only credential passes every other check
 *         and fails at the first real output</td>
 *   </tr>
 *   <tr>
 *     <td>the job queue is owned by the expected account</td>
 *     <td>the resolved queue identifier's account segment</td>
 *     <td>the queue receives the eighty-column job-control cards of every submission</td>
 *   </tr>
 *   <tr>
 *     <td>the job queue is first-in-first-out with content-based deduplication switched off</td>
 *     <td>the queue's own attributes</td>
 *     <td>append order survives only within a message group on a first-in-first-out queue, and
 *         content-based deduplication would silently discard the comment and delimiter cards, several of
 *         which are byte-identical, shortening the job stream to something a reader would accept</td>
 *   </tr>
 *   <tr>
 *     <td>the job queue's own policy opens it to nobody and refuses plain transport</td>
 *     <td>the queue's {@code Policy} attribute, judged by {@link AwsResourcePolicyRules}</td>
 *     <td>a queue open to every principal accepts eighty-column job-control cards from anyone who can
 *         name it, and a scheduler cannot tell those from this deployment's own</td>
 *   </tr>
 *   <tr>
 *     <td>the notification topic exists and is owned by the expected account</td>
 *     <td>listing the topics, then reading the resolved topic's attributes</td>
 *     <td>a topic in another account receives this deployment's job-completion notices</td>
 *   </tr>
 *   <tr>
 *     <td>the notification topic's own policy opens it to nobody and refuses plain transport</td>
 *     <td>the topic's {@code Policy} attribute, judged by {@link AwsResourcePolicyRules}</td>
 *     <td>a topic open to every principal can be published to by anyone, so a completion notice for a
 *         job that never ran is indistinguishable from one for a job that did</td>
 *   </tr>
 * </table>
 *
 * <h2>Why the resource policies are judged here and provisioned elsewhere</h2>
 *
 * <p>Ownership, versioning, queue shape and write capability were verified from the beginning; the five
 * policy properties above were not, and their absence was the gap. Verification and provisioning are
 * deliberately separate: the posture is created by whatever provisions the account - the local
 * validation stack's bootstrap hook creates it for the emulator, and a production deployment's
 * infrastructure definition creates it there - and it is <em>required</em> here, at the one point every
 * deployment passes through. A check that also provisioned would turn a misconfigured account into a
 * silently corrected one, which is the same failure the queue-resolution strategy below refuses.
 *
 * <p>The two rules each policy is held to are stated once, in {@link AwsResourcePolicyRules}, and are
 * applied identically to the bucket, the queue and the topic. Nothing about a document reaches this
 * class: it receives a verdict, composes a refusal from it, and never sees a principal, an action or a
 * resource identifier that a refusal could then publish. Recorded as {@code docs/decision-log.md}
 * DL-346.
 *
 * <h2>Why an unproven property stops the start rather than reporting DOWN</h2>
 *
 * <p>Reporting DOWN would leave the process running and its state ambiguous: an operator cannot tell a
 * resource that is momentarily unreachable from one that belongs to another account, and the second is
 * not a transient condition. Refusing to start is unambiguous, it happens at deployment time where
 * somebody is watching, and it is the posture
 * {@link ProductionConfigurationValidator} already takes for the same class of fault - a deployment that
 * cannot address its own account must not start. The readiness indicators on
 * {@link AwsResourceHealthConfig} keep their own, narrower job: they answer whether a resource is
 * reachable now, which is a question about the moment rather than about the deployment.
 *
 * <h2>Why the write probe is the shape it is</h2>
 *
 * <p>It is one zero-length object under a reserved key that no job, reader, retention pass or generation
 * allocation can see: the allocation lists a base's own prefix and the staging store composes
 * {@code batch/jobs/...}, and this key is beneath neither. It is written once per process, at start-up,
 * and then removed <em>version by version</em> - an unqualified delete against a versioned bucket writes
 * a delete marker and leaves every version fetchable, which would make the probe an accumulating
 * artefact rather than a probe. Removing it also proves the second half of the capability that matters,
 * because a credential that can create an object and not remove one cannot maintain the generation
 * depths this module enforces.
 *
 * <p>It creates no <em>resource</em>: no bucket, queue or topic is created here or anywhere else in this
 * module. An object is what this module writes as its ordinary function, and the probe writes one of
 * those, in a place nothing reads, and takes it back.
 *
 * <h2>Why the caller's own identity is not asked for</h2>
 *
 * <p>The obvious way to establish which account a deployment is running as is to ask the token service.
 * That is deliberately not done, and the reason is that it would be weaker as well as costlier. The
 * expected-owner comparison above is performed <strong>by the object store, on the call itself</strong>,
 * so it holds for the call that is actually made rather than for a separate question asked once at
 * start-up; and the queue and topic identifiers are compared against the same declared account, so a
 * resource in another account is refused whatever the caller's own identity turns out to be. Asking the
 * token service would also add a dependency the migration plan's dependency inventory does not carry,
 * for an assertion the three checks here already make. Recorded as {@code docs/decision-log.md} DL-302.
 *
 * <h2>What this class does not prove, stated because omitting it would read as proven</h2>
 *
 * <p>Publishing to the queue and to the topic is <strong>not</strong> proven. Proving it would require
 * publishing, and a message on the job queue is an eighty-column job-control card that a reader would
 * act on, while a message on the topic is a completion notice for a job that did not run. Both would be
 * indistinguishable from real traffic. The capability evidence this class logs therefore names what was
 * proven and, explicitly, what was not, so that a reader of the evidence is not left to assume the
 * stronger claim.
 *
 * <p>The five policy properties are <strong>structural</strong> claims about each document, not the
 * outcome of evaluating it. No access is simulated and no effective permission is computed: this class
 * establishes that no statement opens the resource to every principal unconditionally and that one
 * statement closes it to plain transport, which are the two properties a document either has or lacks.
 * An identity policy attached elsewhere, a permission boundary, a service control policy or an
 * organisation-level rule can each narrow or widen what a caller may actually do, and none of them is
 * visible from a resource's own document. Those belong to whatever provisions the account; what belongs
 * here is that the deployment refuses to run against a resource whose own document is open or permissive,
 * and that is what is claimed.
 *
 * <p>Stateless after construction and immutable: every field is final, the verification runs once, and
 * nothing is cached for a later caller.
 *
 * @since 1.0.0
 */
@Component
@Profile(ProductionConfigurationValidator.PRODUCTION_PROFILE)
public final class AwsResourceTrustVerifier implements InitializingBean {

    /**
     * Key the twelve-digit account this deployment declares it owns is bound from.
     *
     * <p>Owned by this class rather than by {@link AwsProperties}, because nothing else reads it and the
     * settings record binds the four resource <em>names</em>. It is a required production variable with
     * no fallback: a defaulted account would be an account somebody else owns, and comparing against it
     * would refuse every correct deployment while admitting exactly one wrong one.</p>
     */
    public static final String EXPECTED_ACCOUNT_ID_PROPERTY = "carddemo.aws.expected-account-id";

    /**
     * Key of the object the write-capability probe creates and then removes.
     *
     * <p>Beneath neither of the two prefixes this module reads - a generation allocation lists its own
     * logical base and the staging store composes {@code batch/jobs/...} - so no job, reader, retention
     * pass or allocation can see it even in the window before it is removed.</p>
     */
    static final String WRITE_CAPABILITY_PROBE_KEY =
            "_carddemo/trust-verification/write-capability-probe";

    /**
     * Action namespace the staging bucket's policy must deny in full over plain transport.
     *
     * <p>No bucket identifier carries a service segment - an object-store resource identifier omits both
     * the region and the account - so unlike the two below this name is used only as the namespace the
     * transport denial has to cover.</p>
     */
    static final String OBJECT_STORE_SERVICE = "s3";

    /**
     * Service segment a job-queue resource identifier must carry, and the action namespace its policy
     * must deny in full over plain transport.
     */
    static final String QUEUE_SERVICE = "sqs";

    /**
     * Service segment a notification-topic resource identifier must carry, and the action namespace its
     * policy must deny in full over plain transport.
     */
    static final String TOPIC_SERVICE = "sns";

    /**
     * How long the one asynchronous resolution here is awaited for.
     *
     * <p>The queue client's own call budget already bounds the request, so this is the second bound
     * rather than the only one: it bounds the <em>wait</em>, which is what start-up is holding, and it is
     * comfortably above the client's total budget so that an expiry here means the future itself never
     * completed rather than that the call was slow.</p>
     */
    static final long RESOLUTION_DEADLINE_SECONDS = 30L;

    /** Logger. Receives no credential, no account identifier and no resource identifier. */
    private static final Logger LOG = LoggerFactory.getLogger(AwsResourceTrustVerifier.class);

    /** Attribute value the queue must report for first-in-first-out ordering. */
    private static final String ATTRIBUTE_TRUE = "true";

    /** Attribute value the queue must report for content-based deduplication. */
    private static final String ATTRIBUTE_FALSE = "false";

    /**
     * Name of the topic attribute carrying the topic's own policy.
     *
     * <p>A literal because the notification service reports its attributes as a plain map of names to
     * values, unlike the queue service, which enumerates them.</p>
     */
    private static final String TOPIC_POLICY_ATTRIBUTE = "Policy";

    /** Object store the bucket properties are established through. */
    private final S3Client objectStore;

    /** Queue client the queue's identifier and attributes are read through. */
    private final SqsAsyncClient queueClient;

    /** Notification client the topic is resolved through by listing. */
    private final SnsClient notificationClient;

    /** Notification facade the resolved topic is confirmed through. */
    private final SnsOperations notifications;

    /** The four configured resource names and the region they are resolved in. */
    private final AwsProperties awsProperties;

    /** The twelve-digit account this deployment declares it owns. */
    private final String expectedAccountId;

    /**
     * Takes the three clients, the notification facade, the resource inventory and the declared account.
     *
     * @param objectStore        auto-configured object-store client
     * @param queueClient        auto-configured queue client
     * @param notificationClient auto-configured notification client, used only for listing
     * @param notifications      auto-configured notification facade
     * @param awsProperties      the validated resource inventory and region
     * @param expectedAccountId  the twelve-digit account this deployment declares it owns
     * @throws NullPointerException     if any collaborator is {@code null}
     * @throws IllegalArgumentException if the declared account is not twelve digits
     */
    public AwsResourceTrustVerifier(final S3Client objectStore, final SqsAsyncClient queueClient,
            final SnsClient notificationClient, final SnsOperations notifications,
            final AwsProperties awsProperties,
            @Value("${" + EXPECTED_ACCOUNT_ID_PROPERTY + "}") final String expectedAccountId) {
        this.objectStore = Objects.requireNonNull(objectStore, "objectStore must not be null");
        this.queueClient = Objects.requireNonNull(queueClient, "queueClient must not be null");
        this.notificationClient =
                Objects.requireNonNull(notificationClient, "notificationClient must not be null");
        this.notifications = Objects.requireNonNull(notifications, "notifications must not be null");
        this.awsProperties = Objects.requireNonNull(awsProperties, "awsProperties must not be null");
        this.expectedAccountId = AwsResourceNamingRules.requireAccountIdentifier(
                Objects.requireNonNull(expectedAccountId, "expectedAccountId must not be null").strip(),
                EXPECTED_ACCOUNT_ID_PROPERTY);
    }

    /**
     * Runs the verification as this bean is initialised, so it precedes anything that would publish.
     *
     * @throws IllegalStateException if any of the eleven properties cannot be established
     */
    @Override
    public void afterPropertiesSet() {
        verifyResourceTrust();
    }

    /**
     * Establishes all eleven properties and publishes the capability evidence.
     *
     * <p>Every property is established before the evidence is logged, so an evidence record exists only
     * for a deployment in which all of them held. A refusal names the resource and the property, and
     * never the account, the identifier or the credential.</p>
     *
     * @throws IllegalStateException if any property cannot be established
     */
    void verifyResourceTrust() {
        final List<String> proven = new ArrayList<>();
        verifyObjectStore(proven);
        verifyJobQueue(proven);
        verifyNotificationTopic(proven);
        LOG.info("AWS resource trust established for the production profile."
                        + " Proven capabilities: {}."
                        + " NOT proven, and deliberately so: sqs:SendMessage and sns:Publish, because"
                        + " proving them would emit an eighty-column job-control card a reader would act"
                        + " on and a completion notice for a job that did not run; and no effective"
                        + " permission is computed anywhere - the three policy claims above are"
                        + " structural properties of each attached document, not the outcome of"
                        + " evaluating it against an identity. See docs/gate-evidence.md and"
                        + " docs/decision-log.md DL-302 and DL-346",
                proven);
    }

    /**
     * Establishes that the staging bucket is owned by the declared account, carries versioning, and can
     * be written to and cleaned up by this credential.
     *
     * @param proven collects the capability names that were exercised, in the order they were exercised
     */
    private void verifyObjectStore(final List<String> proven) {
        final String bucket = this.awsProperties.s3().batchStagingBucket();
        try {
            this.objectStore.headBucket(HeadBucketRequest.builder()
                    .bucket(bucket)
                    .expectedBucketOwner(this.expectedAccountId)
                    .build());
        } catch (final RuntimeException refused) {
            throw refusal("the batch staging bucket is not present, or is not owned by the account "
                    + EXPECTED_ACCOUNT_ID_PROPERTY + " declares this deployment owns. The object store"
                    + " itself compares the owning account on the call, so this is the provider's answer"
                    + " and not this process's opinion", refused);
        }
        proven.add("s3:HeadBucket (owner verified by the service)");

        final GetBucketVersioningResponse versioning;
        try {
            versioning = this.objectStore.getBucketVersioning(GetBucketVersioningRequest.builder()
                    .bucket(bucket)
                    .expectedBucketOwner(this.expectedAccountId)
                    .build());
        } catch (final RuntimeException refused) {
            throw refusal("the batch staging bucket's versioning state could not be read, so the"
                    + " retained-generation semantics the legacy output datasets carried cannot be"
                    + " established", refused);
        }
        if (versioning.status() != BucketVersioningStatus.ENABLED) {
            throw refusal("the batch staging bucket does not carry enabled object versioning. Object"
                    + " versioning is what carries the retained-generation depths of the legacy output"
                    + " datasets, so without it every generation this module publishes replaces the one"
                    + " before it and the retention this module enforces retains nothing", null);
        }
        proven.add("s3:GetBucketVersioning (enabled)");

        // The three posture checks precede the write probe deliberately. The probe writes an object,
        // and a bucket that is world-readable or that accepts plain transport is a bucket this
        // deployment must not write to at all - not even a zero-length object under a reserved key.
        verifyPublicAccessPosture(bucket, proven);
        verifyBucketPolicy(bucket, proven);
        verifyDefaultEncryption(bucket, proven);

        verifyWriteCapability(bucket, proven);
    }

    /**
     * Establishes that all four of the bucket's public-access controls are set.
     *
     * <p>All four are required rather than any subset, because each closes a different door: two govern
     * access-control lists, on the bucket and on the objects within it, and two govern policies, refusing
     * a public one and disregarding one already attached. Three of four leaves the fourth door open, and
     * which door is open is not something a later reader of this deployment could tell.</p>
     *
     * @param bucket the staging bucket
     * @param proven collects the capability names that were exercised
     */
    private void verifyPublicAccessPosture(final String bucket, final List<String> proven) {
        final PublicAccessBlockConfiguration configuration;
        try {
            configuration = this.objectStore.getPublicAccessBlock(GetPublicAccessBlockRequest.builder()
                            .bucket(bucket)
                            .expectedBucketOwner(this.expectedAccountId)
                            .build())
                    .publicAccessBlockConfiguration();
        } catch (final RuntimeException refused) {
            throw refusal("the batch staging bucket's public-access controls could not be read. A"
                    + " bucket with no public-access-block configuration at all answers this call with a"
                    + " failure rather than with four unset values, so an absent configuration is"
                    + " reported here and is not distinguished from an unreadable one: neither"
                    + " establishes that public access is blocked", refused);
        }
        if (configuration == null
                || !Boolean.TRUE.equals(configuration.blockPublicAcls())
                || !Boolean.TRUE.equals(configuration.ignorePublicAcls())
                || !Boolean.TRUE.equals(configuration.blockPublicPolicy())
                || !Boolean.TRUE.equals(configuration.restrictPublicBuckets())) {
            throw refusal("the batch staging bucket does not block every form of public access. All four"
                    + " controls - BlockPublicAcls, IgnorePublicAcls, BlockPublicPolicy and"
                    + " RestrictPublicBuckets - are required, because each closes a different route to"
                    + " the statements, reports and rejected records this module writes there, and three"
                    + " of four leaves one route open", null);
        }
        proven.add("s3:GetPublicAccessBlock (all four controls set)");
    }

    /**
     * Establishes that the bucket's own policy opens it to nobody and refuses plain transport.
     *
     * <p>An absent policy is reported through the same refusal as an unreadable one, because the object
     * store answers a bucket with no policy with a failure rather than with an empty document, and
     * neither state establishes anything about who may reach the bucket.</p>
     *
     * @param bucket the staging bucket
     * @param proven collects the capability names that were exercised
     */
    private void verifyBucketPolicy(final String bucket, final List<String> proven) {
        final String document;
        try {
            document = this.objectStore.getBucketPolicy(GetBucketPolicyRequest.builder()
                            .bucket(bucket)
                            .expectedBucketOwner(this.expectedAccountId)
                            .build())
                    .policy();
        } catch (final RuntimeException refused) {
            throw refusal("the batch staging bucket's own policy could not be read, and a bucket with"
                    + " no policy attached answers this call with a failure rather than with an empty"
                    + " document - so neither an absent policy nor an unreadable one is distinguished"
                    + " here, because neither establishes who may reach the objects this module writes",
                    refused);
        }
        requirePolicyPosture(AwsResourcePolicyRules.postureOf(document, OBJECT_STORE_SERVICE),
                "batch staging bucket",
                "Every object written there carries account identifiers, card numbers or monetary"
                        + " balances, and the readiness probe reports the bucket UP whether or not"
                        + " anybody else can read it.");
        proven.add("s3:GetBucketPolicy (no open grant, plain transport denied)");
    }

    /**
     * Establishes that the bucket encrypts by default what this module writes to it.
     *
     * <p>The algorithm is not narrowed to one choice. A managed key and a customer-managed key are both
     * accepted, because the property that matters is that an object written without naming an algorithm -
     * which is how every writer in this module writes - is encrypted anyway.</p>
     *
     * @param bucket the staging bucket
     * @param proven collects the capability names that were exercised
     */
    private void verifyDefaultEncryption(final String bucket, final List<String> proven) {
        final GetBucketEncryptionResponse encryption;
        try {
            encryption = this.objectStore.getBucketEncryption(GetBucketEncryptionRequest.builder()
                    .bucket(bucket)
                    .expectedBucketOwner(this.expectedAccountId)
                    .build());
        } catch (final RuntimeException refused) {
            throw refusal("the batch staging bucket's default encryption could not be read, and a bucket"
                    + " with none configured answers this call with a failure rather than with an empty"
                    + " configuration - so neither is distinguished here, because neither establishes"
                    + " that an object written without naming an algorithm is encrypted", refused);
        }
        if (!carriesDefaultEncryption(encryption)) {
            throw refusal("the batch staging bucket configures no default server-side encryption"
                    + " algorithm. No writer in this module names an algorithm per object, so the"
                    + " bucket's default is the whole of the at-rest protection for statements, reports"
                    + " and rejected records", null);
        }
        proven.add("s3:GetBucketEncryption (a default algorithm is configured)");
    }

    /**
     * Decides whether an encryption configuration names an algorithm this deployment recognises.
     *
     * @param  encryption the configuration as the object store reported it
     * @return {@code true} when at least one rule applies a recognised algorithm by default
     */
    private static boolean carriesDefaultEncryption(final GetBucketEncryptionResponse encryption) {
        if (encryption.serverSideEncryptionConfiguration() == null) {
            return false;
        }
        for (final ServerSideEncryptionRule rule
                : encryption.serverSideEncryptionConfiguration().rules()) {
            if (rule.applyServerSideEncryptionByDefault() == null) {
                continue;
            }
            final ServerSideEncryption algorithm =
                    rule.applyServerSideEncryptionByDefault().sseAlgorithm();
            // An algorithm this software development kit does not recognise is treated as no algorithm:
            // the property being established is that the default is one this deployment can reason
            // about, and a name it cannot resolve is not one.
            if (algorithm != null && algorithm != ServerSideEncryption.UNKNOWN_TO_SDK_VERSION) {
                return true;
            }
        }
        return false;
    }

    /**
     * Establishes that this credential can create and remove an object in the staging bucket.
     *
     * <p>The probe is removed version by version rather than deleted, because an unqualified delete
     * against a versioned bucket writes a delete marker and leaves every version fetchable - which would
     * turn a probe into an artefact that accumulates one copy per start.</p>
     *
     * @param bucket the staging bucket
     * @param proven collects the capability names that were exercised
     */
    private void verifyWriteCapability(final String bucket, final List<String> proven) {
        try {
            this.objectStore.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(WRITE_CAPABILITY_PROBE_KEY)
                            .expectedBucketOwner(this.expectedAccountId)
                            .build(),
                    RequestBody.empty());
        } catch (final RuntimeException refused) {
            throw refusal("this deployment's credential cannot create an object in the batch staging"
                    + " bucket. A read-only credential satisfies every other check here and then fails"
                    + " at the first statement, report or rejected record a job produces - by which time"
                    + " the job has already reported that it ran", refused);
        }
        proven.add("s3:PutObject (probe object created)");

        final int removed = purgeProbe(bucket);
        proven.add("s3:ListObjectVersions and s3:DeleteObject with a version identifier ("
                + removed + " version(s) of the probe removed)");
    }

    /**
     * Removes every version and delete marker beneath the probe key, refusing the start if it cannot.
     *
     * @param  bucket the staging bucket
     * @return how many versions and delete markers were removed
     */
    private int purgeProbe(final String bucket) {
        int removed = 0;
        try {
            String keyMarker = null;
            String versionIdMarker = null;
            do {
                final ListObjectVersionsRequest.Builder request = ListObjectVersionsRequest.builder()
                        .bucket(bucket)
                        .prefix(WRITE_CAPABILITY_PROBE_KEY)
                        .expectedBucketOwner(this.expectedAccountId);
                if (keyMarker != null) {
                    request.keyMarker(keyMarker);
                }
                if (versionIdMarker != null) {
                    request.versionIdMarker(versionIdMarker);
                }
                final ListObjectVersionsResponse listing = this.objectStore
                        .listObjectVersions(request.build());
                for (final var version : listing.versions()) {
                    if (WRITE_CAPABILITY_PROBE_KEY.equals(version.key())) {
                        removed += removeVersion(bucket, version.versionId());
                    }
                }
                for (final var marker : listing.deleteMarkers()) {
                    if (WRITE_CAPABILITY_PROBE_KEY.equals(marker.key())) {
                        removed += removeVersion(bucket, marker.versionId());
                    }
                }
                keyMarker = Boolean.TRUE.equals(listing.isTruncated()) ? listing.nextKeyMarker() : null;
                versionIdMarker = keyMarker == null ? null : listing.nextVersionIdMarker();
            } while (keyMarker != null);
        } catch (final RuntimeException refused) {
            throw refusal("this deployment's credential can create an object in the batch staging"
                    + " bucket and cannot remove one. Removing an object is not optional here: the"
                    + " generation depths this module enforces are maintained by removing rolled-off"
                    + " generations, and a failed publication is compensated by removing what it"
                    + " uploaded", refused);
        }
        if (removed == 0) {
            throw refusal("the write-capability probe object was created and then found absent, so"
                    + " neither its removal nor its presence can be established. A bucket that answers"
                    + " a listing without the object it has just accepted is not a bucket this"
                    + " deployment can reason about", null);
        }
        return removed;
    }

    /**
     * Removes one exact version of the probe object.
     *
     * @param  bucket    the staging bucket
     * @param  versionId the version to remove
     * @return one, so the caller can accumulate the count
     */
    private int removeVersion(final String bucket, final String versionId) {
        this.objectStore.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(WRITE_CAPABILITY_PROBE_KEY)
                .versionId(versionId)
                .expectedBucketOwner(this.expectedAccountId)
                .build());
        return 1;
    }

    /**
     * Establishes that the job queue is this deployment's own, and is shaped the way the bridge requires.
     *
     * <p>The queue is resolved with the strategy that refuses an absent queue rather than creating one.
     * That is load-bearing rather than incidental: the alternative strategy would create a queue, which
     * would turn a misconfiguration into new infrastructure and let this very check pass.</p>
     *
     * @param proven collects the capability names that were exercised
     */
    private void verifyJobQueue(final List<String> proven) {
        final QueueAttributes attributes = resolveQueueAttributes();
        final String queueArn = attributes.getQueueAttribute(QueueAttributeName.QUEUE_ARN);
        if (queueArn == null || queueArn.isBlank()) {
            throw refusal("the job queue reported no resource identifier of its own, so the account"
                    + " that owns it cannot be established", null);
        }
        try {
            AwsResourceNamingRules.requireResourceOwnedByAccount(queueArn, QUEUE_SERVICE,
                    this.awsProperties.region(), this.expectedAccountId,
                    EXPECTED_ACCOUNT_ID_PROPERTY);
        } catch (final IllegalArgumentException refused) {
            throw refusal("the job queue this deployment resolved is not one it owns. " + "The queue"
                    + " receives the eighty-column job-control cards of every batch submission, and the"
                    + " bridge is defined to tolerate a refused publish, so a queue in another account"
                    + " would receive them while every request still answered normally", refused);
        }
        proven.add("sqs:GetQueueUrl and sqs:GetQueueAttributes (owner matched)");

        requireAttribute(attributes, QueueAttributeName.FIFO_QUEUE, ATTRIBUTE_TRUE,
                "append order survives only within a message group on a first-in-first-out queue, and"
                        + " the legacy queue's append disposition is reproduced by one stable group per"
                        + " submission");
        requireAttribute(attributes, QueueAttributeName.CONTENT_BASED_DEDUPLICATION, ATTRIBUTE_FALSE,
                "several of the seventeen cards are comment or delimiter cards with byte-identical"
                        + " bodies, so content-based deduplication would silently discard them and"
                        + " shorten the job stream to something a reader would accept and a scheduler"
                        + " would misread");
        proven.add("the queue is first-in-first-out with content-based deduplication switched off");

        // The queue's own policy arrives with the same attribute read as the two above, so establishing
        // it costs no further call. A queue reports no Policy attribute at all when none is attached,
        // which the rules below read as absent rather than as permissive.
        requirePolicyPosture(AwsResourcePolicyRules.postureOf(
                        attributes.getQueueAttribute(QueueAttributeName.POLICY), QUEUE_SERVICE),
                "job queue",
                "The queue is the single online-to-batch bridge, it receives the eighty-column"
                        + " job-control cards of every submission, and the publisher is defined to"
                        + " tolerate a refused write - so neither a card this deployment did not send nor"
                        + " one it failed to send would surface anywhere.");
        proven.add("the queue's own policy carries no open grant and denies plain transport");
    }

    /**
     * Reads the queue's identifier and attributes, bounded by a deadline of its own.
     *
     * @return the resolved queue attributes
     */
    private QueueAttributes resolveQueueAttributes() {
        final CompletableFuture<QueueAttributes> resolution = QueueAttributesResolver.builder()
                .queueName(this.awsProperties.sqs().jobQueue())
                .sqsAsyncClient(this.queueClient)
                .queueAttributeNames(List.of(QueueAttributeName.QUEUE_ARN,
                        QueueAttributeName.FIFO_QUEUE,
                        QueueAttributeName.CONTENT_BASED_DEDUPLICATION,
                        QueueAttributeName.POLICY))
                .queueNotFoundStrategy(QueueNotFoundStrategy.FAIL)
                .build()
                .resolveQueueAttributes();
        try {
            return resolution.get(RESOLUTION_DEADLINE_SECONDS, TimeUnit.SECONDS);
        } catch (final TimeoutException expired) {
            resolution.cancel(true);
            throw refusal("the job queue could not be resolved within the start-up deadline, so"
                    + " nothing about it can be established", expired);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            resolution.cancel(true);
            throw refusal("the job queue resolution was interrupted, so nothing about it can be"
                    + " established", interrupted);
        } catch (final ExecutionException failed) {
            throw refusal("the job queue named by this deployment could not be resolved. It is resolved"
                    + " with the strategy that refuses an absent queue rather than creating one, so an"
                    + " absent queue is reported here instead of becoming new infrastructure",
                    failed.getCause() == null ? failed : failed.getCause());
        }
    }

    /**
     * Requires one queue attribute to carry one value.
     *
     * @param attributes the resolved attributes
     * @param name       the attribute to read
     * @param expected   the value it must carry
     * @param because    why the value is load-bearing, carried into the refusal
     */
    private static void requireAttribute(final QueueAttributes attributes,
            final QueueAttributeName name, final String expected, final String because) {
        final String actual = attributes.getQueueAttribute(name);
        final String normalized = actual == null ? null : actual.strip().toLowerCase(Locale.ROOT);
        if (!expected.equals(normalized)) {
            throw refusal("the job queue's " + name + " attribute is not " + expected + ", and it has"
                    + " to be: " + because, null);
        }
    }

    /**
     * Establishes that the notification topic exists and is this deployment's own.
     *
     * <p>Resolved by listing rather than by the library's default resolver, which resolves a name through
     * an idempotent create call - convenient, and it would create the very topic whose absence this
     * check exists to report.</p>
     *
     * <p><strong>This is the mandatory stage of the notification contract, and it does not contradict the
     * readiness exclusion that accompanies it.</strong> The publication path is documented as best effort
     * and the {@code awsSns} health contributor is deliberately outside the readiness group, because a
     * notice announces a batch job that has <em>already</em> finished, so losing one prevents no work.
     * Neither statement is about the resource existing. This check settles that question once, before any
     * bean that could publish is created: a topic that cannot be resolved from a listing, is owned by
     * another account, or does not answer an attribute read stops the start-up. Best-effort <em>delivery</em>
     * to a destination this deployment owns is a different claim from an optional destination, and only the
     * first is made anywhere. Recorded as {@code DL-339}.
     *
     * @param proven collects the capability names that were exercised
     */
    private void verifyNotificationTopic(final List<String> proven) {
        final String topic = this.awsProperties.sns().jobNotificationTopic();
        final TopicArnResolver resolver = new TopicsListingTopicArnResolver(this.notificationClient);
        final String topicArn;
        try {
            topicArn = resolver.resolveTopicArn(topic).toString();
        } catch (final RuntimeException refused) {
            throw refusal("the notification topic named by this deployment was not found among the"
                    + " topics it can list, and it is deliberately not created on resolution", refused);
        }
        try {
            AwsResourceNamingRules.requireResourceOwnedByAccount(topicArn, TOPIC_SERVICE,
                    this.awsProperties.region(), this.expectedAccountId,
                    EXPECTED_ACCOUNT_ID_PROPERTY);
        } catch (final IllegalArgumentException refused) {
            throw refusal("the notification topic this deployment resolved is not one it owns, so"
                    + " another account would receive its job-completion notices", refused);
        }
        if (!this.notifications.topicExists(topicArn)) {
            throw refusal("the notification topic resolved by listing did not answer an attribute"
                    + " read, so its existence is established by the listing alone and not by the topic"
                    + " itself", null);
        }
        proven.add("sns:ListTopics and sns:GetTopicAttributes (owner matched)");

        verifyTopicPolicy(topicArn, proven);
    }

    /**
     * Establishes that the topic's own policy opens it to nobody and refuses plain transport.
     *
     * <p>Read through the client rather than through the facade, because the facade answers whether a
     * topic exists and does not surrender its attributes. The notification service attaches a default
     * policy to every topic it creates, and that default is <em>not</em> an open grant even though it
     * names every principal: its statements are confined by a condition on the owning account, which is
     * exactly the shape {@link AwsResourcePolicyRules} accepts. What it does not carry is a transport
     * denial, so a topic left with only its default policy is refused here - and that is the finding this
     * check exists to make.</p>
     *
     * @param topicArn the resolved topic identifier
     * @param proven   collects the capability names that were exercised
     */
    private void verifyTopicPolicy(final String topicArn, final List<String> proven) {
        final Map<String, String> attributes;
        try {
            attributes = this.notificationClient.getTopicAttributes(GetTopicAttributesRequest.builder()
                            .topicArn(topicArn)
                            .build())
                    .attributes();
        } catch (final RuntimeException refused) {
            throw refusal("the notification topic's attributes could not be read, so its own policy"
                    + " cannot be established", refused);
        }
        requirePolicyPosture(AwsResourcePolicyRules.postureOf(
                        attributes == null ? null : attributes.get(TOPIC_POLICY_ATTRIBUTE),
                        TOPIC_SERVICE),
                "notification topic",
                "A notice announces a batch job that has already finished, so the delivery path is"
                        + " deliberately best effort - which is precisely why nobody would notice a"
                        + " notice this deployment did not publish.");
        proven.add("the topic's own policy carries no open grant and denies plain transport");
    }

    /**
     * Turns one policy verdict into either nothing or the refusal that names the property it failed.
     *
     * <p>Written as a switch expression rather than as a chain of comparisons so that the compiler
     * enforces exhaustiveness: were a further verdict ever added to {@link PolicyPosture}, this method
     * would fail to compile rather than silently accept the state it names. That is the fail-closed
     * direction, and it is the direction that matters for a check whose job is to refuse.</p>
     *
     * @param  posture     what the document was found to be
     * @param  resource    the resource the document belongs to, named as a refusal would name it
     * @param  consequence what an open or permissive policy on that resource would mean, carried into
     *                     the refusal so a deployer reads the reason rather than the rule
     * @throws IllegalStateException if the posture is anything other than sound
     */
    private static void requirePolicyPosture(final PolicyPosture posture, final String resource,
            final String consequence) {
        final String unmetProperty = switch (posture) {
            case SOUND -> null;
            case ABSENT -> "the " + resource + " carries no resource policy of its own, so nothing"
                    + " attached to the resource constrains who may reach it";
            case UNREADABLE -> "the " + resource + "'s resource policy could not be read as a policy"
                    + " document, so no property of it can be established. The document is deliberately"
                    + " not reported here, because it names accounts, roles and resources";
            case PUBLICLY_GRANTED -> "the " + resource + "'s resource policy grants every principal,"
                    + " with no condition confining the grant";
            case INSECURE_TRANSPORT_PERMITTED -> "the " + resource + "'s resource policy does not deny"
                    + " every principal every action over an unencrypted transport. A statement denying"
                    + " every action of the service when "
                    + AwsResourcePolicyRules.SECURE_TRANSPORT_CONDITION_KEY + " is false is what closes"
                    + " it";
        };
        if (unmetProperty != null) {
            throw refusal(unmetProperty + ". " + consequence, null);
        }
    }

    /**
     * Composes the refusal that stops the start.
     *
     * <p>The message states the property that could not be established and why it matters. It never
     * carries the account, the resource identifier, the bucket owner reported by the service or any part
     * of a credential - a deployer already has the values they configured, and a log record naming a
     * resource in another account would put that resource's identity into this deployment's log.</p>
     *
     * <p><strong>The provider's own failure is classified rather than attached, and that is a correction
     * rather than a preference.</strong> A chained cause reads as free diagnosis, and it is not: this
     * exception is thrown from a bean's initialisation, so the framework's own start-up failure reporting
     * renders it and every cause beneath it into the deployment log, in full and verbatim. Those causes
     * are composed by the provider's software development kit, and in practice they carry the endpoint
     * that was called, the request and extended request identifiers, the resource name, the account the
     * bucket is owned by as the service reported it and, on a signature failure, the access key
     * identifier - every one of which this message is at pains not to publish. Chaining them therefore
     * discarded the whole point of composing the message carefully, and it did so at the one moment
     * whose output is most widely read.</p>
     *
     * <p>What replaces it is the part this module authored: the bounded chain of failure <em>type</em>
     * names and the bounded code location, both rendered by {@link FailureDiagnostics}, which reads no
     * message, no localised message, no suppressed throwable and no rendered frame. That is the same
     * classification every other boundary in this module publishes in place of a provider failure, so a
     * reader correlating this refusal with the provider's own logs has the type chain and the origin to
     * correlate on, and the provider's record still holds its own detail for whoever is entitled to read
     * it. Recorded as {@code docs/decision-log.md} DL-347.</p>
     *
     * @param  property what could not be established
     * @param  cause    the provider failure, or {@code null} when the refusal is a comparison
     * @return the exception for the caller to throw, carrying no cause of any kind
     */
    private static IllegalStateException refusal(final String property, final Throwable cause) {
        final String classification = cause == null
                ? ""
                : " The provider failure this was established from, classified: "
                        + FailureDiagnostics.failureChainOf(cause)
                        + " at " + FailureDiagnostics.failureOriginOf(cause)
                        + ". Its own message and stack are deliberately not reproduced here, because"
                        + " start-up failure reporting renders whatever this exception carries and a"
                        + " provider message carries the endpoint, the request identifiers and the"
                        + " resource.";
        return new IllegalStateException("This production deployment must not start: " + property
                + ". Verified by " + AwsResourceTrustVerifier.class.getSimpleName()
                + " before any bean that publishes; see docs/decision-log.md DL-302, DL-346 and"
                + " DL-347." + classification);
    }
}
