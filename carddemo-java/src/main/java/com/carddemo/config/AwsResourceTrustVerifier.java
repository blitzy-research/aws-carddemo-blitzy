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
import io.awspring.cloud.sns.core.SnsOperations;
import io.awspring.cloud.sns.core.TopicArnResolver;
import io.awspring.cloud.sns.core.TopicsListingTopicArnResolver;
import io.awspring.cloud.sqs.QueueAttributesResolver;
import io.awspring.cloud.sqs.listener.QueueAttributes;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sns.SnsClient;
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
 *   <caption>The six properties this class establishes and the call that establishes each</caption>
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
 *     <td>the notification topic exists and is owned by the expected account</td>
 *     <td>listing the topics, then reading the resolved topic's attributes</td>
 *     <td>a topic in another account receives this deployment's job-completion notices</td>
 *   </tr>
 * </table>
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

    /** Service segment a job-queue resource identifier must carry. */
    static final String QUEUE_SERVICE = "sqs";

    /** Service segment a notification-topic resource identifier must carry. */
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
     * @throws IllegalStateException if any of the six properties cannot be established
     */
    @Override
    public void afterPropertiesSet() {
        verifyResourceTrust();
    }

    /**
     * Establishes all six properties and publishes the capability evidence.
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
                        + " on and a completion notice for a job that did not run. See"
                        + " docs/gate-evidence.md and docs/decision-log.md DL-302",
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

        verifyWriteCapability(bucket, proven);
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
                        QueueAttributeName.CONTENT_BASED_DEDUPLICATION))
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
    }

    /**
     * Composes the refusal that stops the start.
     *
     * <p>The message states the property that could not be established and why it matters. It never
     * carries the account, the resource identifier, the bucket owner reported by the service or any part
     * of a credential - a deployer already has the values they configured, and a log record naming a
     * resource in another account would put that resource's identity into this deployment's log. The
     * cause is chained, so a diagnosing reader has the provider's own exception without this message
     * repeating its text.</p>
     *
     * @param  property what could not be established
     * @param  cause    the provider failure, or {@code null} when the refusal is a comparison
     * @return the exception for the caller to throw
     */
    private static IllegalStateException refusal(final String property, final Throwable cause) {
        final String message = "This production deployment must not start: " + property
                + ". Verified by " + AwsResourceTrustVerifier.class.getSimpleName()
                + " before any bean that publishes; see docs/decision-log.md DL-302.";
        return cause == null
                ? new IllegalStateException(message)
                : new IllegalStateException(message, cause);
    }
}
