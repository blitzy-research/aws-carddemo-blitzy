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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.awspring.cloud.sns.core.SnsOperations;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.ListTopicsRequest;
import software.amazon.awssdk.services.sns.model.ListTopicsResponse;
import software.amazon.awssdk.services.sns.model.Topic;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

/**
 * Requires that a production deployment pointed at a resource it does not own, at a bucket without
 * versioning, at a queue shaped the wrong way, or held by a credential that cannot write, does not start.
 *
 * <h2>Why the assertions are about refusal rather than about calls</h2>
 *
 * <p>Each case perturbs exactly one of the six properties the verifier establishes and requires the start
 * to be refused. That is the behaviour a deployment depends on: the alternative to refusing is a running
 * instance whose report requests answer normally while its job cards land in another account, or whose
 * jobs report that they ran and wrote nothing - both of which are discovered days later by somebody
 * wondering where the output went.
 *
 * <p>The happy path is asserted in the other direction and in two ways: it must not throw, and the probe
 * object it writes must be removed by version identifier rather than by an unqualified delete. An
 * unqualified delete against a versioned bucket writes a delete marker and leaves every version
 * fetchable, so a probe removed that way accumulates one copy per start - which is the difference between
 * a probe and an artefact.
 *
 * <p>Every collaborator is a double. Nothing here reaches a network, and no test in this class creates,
 * names or requires a real bucket, queue, topic or credential; the twelve-digit account values are
 * synthetic and authorise nothing.
 */
@DisplayName("Production resource trust: ownership, versioning, write capability, queue shape and topic")
final class AwsResourceTrustVerifierTest {

    /** The account a deployment under test declares it owns. */
    private static final String OWNED_ACCOUNT = "000000000000";

    /** A twelve-digit account that is not the declared one. */
    private static final String FOREIGN_ACCOUNT = "999999999999";

    /** Region every configured resource is resolved in. */
    private static final String REGION = "us-east-1";

    /** Staging bucket name. */
    private static final String BUCKET = "carddemo-batch-staging";

    /** Job queue name, first-in-first-out as the settings type requires. */
    private static final String QUEUE = "JOBS.fifo";

    /** Notification topic name. */
    private static final String TOPIC = "carddemo-job-notifications";

    /** Resolved queue URL the double answers with. */
    private static final String QUEUE_URL =
            "https://sqs." + REGION + ".amazonaws.com/" + OWNED_ACCOUNT + "/" + QUEUE;

    /** Version identifier the probe listing answers with. */
    private static final String PROBE_VERSION = "probe-version-1";

    /** Creates the specification. */
    AwsResourceTrustVerifierTest() {
        // Intentionally empty: each test assembles its own doubles.
    }

    @Nested
    @DisplayName("A deployment whose four resources are its own and writable")
    final class ATrustedDeployment {

        @Test
        @DisplayName("starts, and takes its write-capability probe back version by version rather than "
                + "leaving a delete marker behind")
        void startsAndRemovesItsProbeByVersion() {
            final Fixture fixture = Fixture.trusted();

            fixture.verifier().afterPropertiesSet();

            verify(fixture.objectStore()).putObject(any(PutObjectRequest.class),
                    any(software.amazon.awssdk.core.sync.RequestBody.class));
            assertThat(fixture.removedVersions())
                    .as("an unqualified delete against a versioned bucket writes a delete marker and "
                            + "leaves every version fetchable, so the probe would accumulate one copy "
                            + "per start instead of being taken back")
                    .containsExactly(PROBE_VERSION);
            verify(fixture.notifications()).topicExists(ownedTopicArn());
        }

        @Test
        @DisplayName("asks the object store to enforce the declared owner on every bucket call, so "
                + "ownership is the provider's answer rather than this process's opinion")
        void asksTheObjectStoreToEnforceTheOwner() {
            final Fixture fixture = Fixture.trusted();

            fixture.verifier().afterPropertiesSet();

            assertThat(fixture.headBucketRequests())
                    .singleElement()
                    .satisfies(request -> {
                        assertThat(request.bucket()).isEqualTo(BUCKET);
                        assertThat(request.expectedBucketOwner()).isEqualTo(OWNED_ACCOUNT);
                    });
            assertThat(fixture.versioningRequests())
                    .allSatisfy(request ->
                            assertThat(request.expectedBucketOwner()).isEqualTo(OWNED_ACCOUNT));
        }
    }

    @Nested
    @DisplayName("A deployment that must not start")
    final class ARefusedDeployment {

        @Test
        @DisplayName("because the staging bucket is not present or is owned by another account")
        void becauseTheBucketIsNotOwned() {
            final Fixture fixture = Fixture.trusted();
            fixture.headBucketFailure(S3Exception.builder().message("access denied").build());

            assertRefused(fixture, "not owned by the account");
        }

        @Test
        @DisplayName("because the staging bucket does not carry enabled object versioning, which is "
                + "what carries the retained-generation semantics of the legacy output datasets")
        void becauseVersioningIsNotEnabled() {
            final Fixture fixture = Fixture.trusted();
            fixture.versioningStatus(BucketVersioningStatus.SUSPENDED);

            assertRefused(fixture, "does not carry enabled object versioning");
        }

        @Test
        @DisplayName("because the versioning state cannot be read at all")
        void becauseVersioningCannotBeRead() {
            final Fixture fixture = Fixture.trusted();
            fixture.versioningFailure(S3Exception.builder().message("access denied").build());

            assertRefused(fixture, "versioning state could not be read");
        }

        @Test
        @DisplayName("because the credential cannot create an object, which is the one property no "
                + "read can establish and the one a read-only credential fails at silently")
        void becauseTheCredentialCannotWrite() {
            final Fixture fixture = Fixture.trusted();
            fixture.putObjectFailure(S3Exception.builder().message("access denied").build());

            assertRefused(fixture, "cannot create an object");
        }

        @Test
        @DisplayName("because the credential can create an object and cannot remove one, which would "
                + "leave the generation depths this module enforces unmaintainable")
        void becauseTheCredentialCannotRemove() {
            final Fixture fixture = Fixture.trusted();
            fixture.deleteObjectFailure(S3Exception.builder().message("access denied").build());

            assertRefused(fixture, "cannot remove one");
        }

        @Test
        @DisplayName("because the bucket answered a listing without the object it had just accepted")
        void becauseTheProbeWasNotFoundAfterBeingWritten() {
            final Fixture fixture = Fixture.trusted();
            fixture.probeListingIsEmpty();

            assertRefused(fixture, "found absent");
        }

        @Test
        @DisplayName("because the job queue it resolved is owned by another account, and the queue "
                + "receives the job-control cards of every submission")
        void becauseTheQueueIsOwnedByAnotherAccount() {
            final Fixture fixture = Fixture.trusted();
            fixture.queueArn(arn("sqs", FOREIGN_ACCOUNT, QUEUE));

            assertRefused(fixture, "not one it owns");
        }

        @Test
        @DisplayName("because the job queue reports no resource identifier at all")
        void becauseTheQueueReportsNoIdentifier() {
            final Fixture fixture = Fixture.trusted();
            fixture.queueArn("");

            assertRefused(fixture, "reported no resource identifier");
        }

        @Test
        @DisplayName("because the job queue is not first-in-first-out, so append order survives nothing")
        void becauseTheQueueIsNotFirstInFirstOut() {
            final Fixture fixture = Fixture.trusted();
            fixture.queueAttribute(QueueAttributeName.FIFO_QUEUE, "false");

            assertRefused(fixture, "FifoQueue");
        }

        @Test
        @DisplayName("because content-based deduplication is switched on, which would discard the "
                + "byte-identical comment and delimiter cards and shorten the job stream")
        void becauseContentBasedDeduplicationIsOn() {
            final Fixture fixture = Fixture.trusted();
            fixture.queueAttribute(QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true");

            assertRefused(fixture, "ContentBasedDeduplication");
        }

        @Test
        @DisplayName("because the queue named by this deployment does not exist, and is deliberately "
                + "not created to make the check pass")
        void becauseTheQueueDoesNotExist() {
            final Fixture fixture = Fixture.trusted();
            fixture.queueDoesNotExist();

            assertRefused(fixture, "could not be resolved");
            verify(fixture.queueClient(), never())
                    .createQueue(ArgumentMatchers.<Consumer<CreateQueueRequest.Builder>>any());
        }

        @Test
        @DisplayName("because the notification topic is not among the topics it can list")
        void becauseTheTopicIsAbsent() {
            final Fixture fixture = Fixture.trusted();
            fixture.topicListingIsEmpty();

            assertRefused(fixture, "not found among the");
        }

        @Test
        @DisplayName("because the notification topic it resolved is owned by another account, which "
                + "would receive this deployment's job-completion notices")
        void becauseTheTopicIsOwnedByAnotherAccount() {
            final Fixture fixture = Fixture.trusted();
            fixture.topicArn(arn("sns", FOREIGN_ACCOUNT, TOPIC));

            assertRefused(fixture, "not one it owns");
        }

        @Test
        @DisplayName("because the resolved topic did not answer an attribute read of its own")
        void becauseTheTopicDidNotAnswerAnAttributeRead() {
            final Fixture fixture = Fixture.trusted();
            fixture.topicExists(false);

            assertRefused(fixture, "did not answer an attribute read");
        }

        /**
         * Requires the verification to refuse, and requires the refusal to explain itself.
         *
         * @param fixture  the perturbed fixture
         * @param fragment wording the refusal must carry
         */
        private void assertRefused(final Fixture fixture, final String fragment) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> fixture.verifier().afterPropertiesSet())
                    .withMessageContaining("must not start")
                    .withMessageContaining(fragment)
                    .as("a refusal that named the account, the identifier or the owner reported by the "
                            + "service would put another account's resource identity into this "
                            + "deployment's log")
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .doesNotContain(FOREIGN_ACCOUNT)
                            .doesNotContain(OWNED_ACCOUNT));
        }
    }

    @Nested
    @DisplayName("The declared account itself")
    final class TheDeclaredAccount {

        @Test
        @DisplayName("must be twelve digits, so a mistyped one is reported against the key that "
                + "carries it rather than as a resource that looks like somebody else's")
        void mustBeTwelveDigits() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Fixture.trusted().withDeclaredAccount("00000000000"))
                    .withMessageContaining(
                            AwsResourceTrustVerifier.EXPECTED_ACCOUNT_ID_PROPERTY);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Fixture.trusted().withDeclaredAccount("00000000000x"))
                    .withMessageContaining(
                            AwsResourceTrustVerifier.EXPECTED_ACCOUNT_ID_PROPERTY);
        }

        @Test
        @DisplayName("is accepted with surrounding whitespace, because an environment variable "
                + "routinely arrives with it")
        void isAcceptedWithSurroundingWhitespace() {
            final Fixture fixture = Fixture.trusted();

            assertThat(fixture.withDeclaredAccount("  " + OWNED_ACCOUNT + "  ")).isNotNull();
        }
    }

    /**
     * Composes the resource identifier of one owned or foreign resource.
     *
     * @param  service the service segment
     * @param  account the account segment
     * @param  name    the resource segment
     * @return the identifier
     */
    private static String arn(final String service, final String account, final String name) {
        return "arn:aws:" + service + ":" + REGION + ":" + account + ":" + name;
    }

    /**
     * @return the identifier of the topic the declared account owns
     */
    private static String ownedTopicArn() {
        return arn("sns", OWNED_ACCOUNT, TOPIC);
    }

    /**
     * The four doubles, wired for a trusted deployment and perturbable one property at a time.
     *
     * <p>Assembled as a mutable fixture rather than as a builder per case, because every case differs
     * from the trusted one in exactly one respect and stating only that respect is what makes each case
     * readable as the property it is about.</p>
     */
    private static final class Fixture {

        /** Object store double. */
        private final S3Client objectStore = mock(S3Client.class);

        /** Queue client double. */
        private final SqsAsyncClient queueClient = mock(SqsAsyncClient.class);

        /** Notification client double, used only for listing. */
        private final SnsClient notificationClient = mock(SnsClient.class);

        /** Notification facade double. */
        private final SnsOperations notifications = mock(SnsOperations.class);

        /** Head-bucket requests the verifier issued. */
        private final List<HeadBucketRequest> headBucketRequests = new ArrayList<>();

        /** Versioning requests the verifier issued. */
        private final List<GetBucketVersioningRequest> versioningRequests = new ArrayList<>();

        /** Version identifiers the verifier removed. */
        private final List<String> removedVersions = new ArrayList<>();

        /** The queue attributes the double answers with. */
        private final Map<QueueAttributeName, String> queueAttributes =
                new EnumMap<>(QueueAttributeName.class);

        /** Versioning status the double answers with. */
        private BucketVersioningStatus versioningStatus = BucketVersioningStatus.ENABLED;

        /** Whether the probe listing answers with the probe. */
        private boolean probeListed = true;

        /** Topics the listing answers with. */
        private List<Topic> topics = List.of(Topic.builder().topicArn(ownedTopicArn()).build());

        /** Whether the resolved topic answers an attribute read. */
        private boolean topicExists = true;

        /** Creates the fixture with no stubbing applied. */
        private Fixture() {
        }

        /**
         * @return a fixture in which all six properties hold
         */
        static Fixture trusted() {
            final Fixture fixture = new Fixture();
            fixture.queueAttributes.put(QueueAttributeName.QUEUE_ARN, arn("sqs", OWNED_ACCOUNT, QUEUE));
            fixture.queueAttributes.put(QueueAttributeName.FIFO_QUEUE, "true");
            fixture.queueAttributes.put(QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "false");
            fixture.stubObjectStore();
            fixture.stubQueue();
            fixture.stubNotifications();
            return fixture;
        }

        /** Applies the object-store stubbing the trusted path needs. */
        private void stubObjectStore() {
            when(this.objectStore.headBucket(any(HeadBucketRequest.class)))
                    .thenAnswer(invocation -> {
                        this.headBucketRequests.add(invocation.getArgument(0));
                        return HeadBucketResponse.builder().build();
                    });
            when(this.objectStore.getBucketVersioning(any(GetBucketVersioningRequest.class)))
                    .thenAnswer(invocation -> {
                        this.versioningRequests.add(invocation.getArgument(0));
                        return GetBucketVersioningResponse.builder()
                                .status(this.versioningStatus)
                                .build();
                    });
            when(this.objectStore.putObject(any(PutObjectRequest.class),
                    any(software.amazon.awssdk.core.sync.RequestBody.class)))
                    .thenReturn(PutObjectResponse.builder().build());
            when(this.objectStore.listObjectVersions(any(ListObjectVersionsRequest.class)))
                    .thenAnswer(invocation -> ListObjectVersionsResponse.builder()
                            .versions(this.probeListed
                                    ? List.of(ObjectVersion.builder()
                                            .key(AwsResourceTrustVerifier.WRITE_CAPABILITY_PROBE_KEY)
                                            .versionId(PROBE_VERSION)
                                            .build())
                                    : List.of())
                            .isTruncated(Boolean.FALSE)
                            .build());
            when(this.objectStore.deleteObject(any(DeleteObjectRequest.class)))
                    .thenAnswer(invocation -> {
                        this.removedVersions.add(
                                ((DeleteObjectRequest) invocation.getArgument(0)).versionId());
                        return DeleteObjectResponse.builder().build();
                    });
        }

        /** Applies the queue stubbing the trusted path needs. */
        private void stubQueue() {
            when(this.queueClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(
                            GetQueueUrlResponse.builder().queueUrl(QUEUE_URL).build()));
            when(this.queueClient.getQueueAttributes(
                    ArgumentMatchers.<Consumer<GetQueueAttributesRequest.Builder>>any()))
                    .thenAnswer(invocation -> CompletableFuture.completedFuture(
                            GetQueueAttributesResponse.builder()
                                    .attributes(Map.copyOf(this.queueAttributes))
                                    .build()));
        }

        /** Applies the notification stubbing the trusted path needs. */
        private void stubNotifications() {
            // The resolver reads the no-argument overload first and then pages with a request, so both
            // are stubbed: stubbing only the request form leaves the first call answering null.
            when(this.notificationClient.listTopics())
                    .thenAnswer(invocation ->
                            ListTopicsResponse.builder().topics(this.topics).build());
            when(this.notificationClient.listTopics(any(ListTopicsRequest.class)))
                    .thenAnswer(invocation ->
                            ListTopicsResponse.builder().topics(this.topics).build());
            when(this.notifications.topicExists(any(String.class)))
                    .thenAnswer(invocation -> this.topicExists);
        }

        /**
         * @return the verifier over these doubles, with the declared account this fixture owns
         */
        AwsResourceTrustVerifier verifier() {
            return withDeclaredAccount(OWNED_ACCOUNT);
        }

        /**
         * @param  declaredAccount the account to declare
         * @return the verifier over these doubles with that declared account
         */
        AwsResourceTrustVerifier withDeclaredAccount(final String declaredAccount) {
            return new AwsResourceTrustVerifier(this.objectStore, this.queueClient,
                    this.notificationClient, this.notifications, settings(), declaredAccount);
        }

        /**
         * @return the resource inventory the verifier reads
         */
        private static AwsProperties settings() {
            return new AwsProperties(REGION, null, new AwsProperties.S3(BUCKET),
                    new AwsProperties.Sqs(QUEUE, "carddemo-job-submission"),
                    new AwsProperties.Sns(TOPIC));
        }

        /** @return the object-store double */
        S3Client objectStore() {
            return this.objectStore;
        }

        /** @return the queue-client double */
        SqsAsyncClient queueClient() {
            return this.queueClient;
        }

        /** @return the notification-facade double */
        SnsOperations notifications() {
            return this.notifications;
        }

        /** @return the head-bucket requests the verifier issued */
        List<HeadBucketRequest> headBucketRequests() {
            return this.headBucketRequests;
        }

        /** @return the versioning requests the verifier issued */
        List<GetBucketVersioningRequest> versioningRequests() {
            return this.versioningRequests;
        }

        /** @return the version identifiers the verifier removed */
        List<String> removedVersions() {
            return this.removedVersions;
        }

        /** @param failure what the head-bucket call raises */
        void headBucketFailure(final RuntimeException failure) {
            when(this.objectStore.headBucket(any(HeadBucketRequest.class))).thenThrow(failure);
        }

        /** @param failure what the versioning read raises */
        void versioningFailure(final RuntimeException failure) {
            when(this.objectStore.getBucketVersioning(any(GetBucketVersioningRequest.class)))
                    .thenThrow(failure);
        }

        /** @param status the versioning status the bucket reports */
        void versioningStatus(final BucketVersioningStatus status) {
            this.versioningStatus = status;
        }

        /** @param failure what the probe write raises */
        void putObjectFailure(final RuntimeException failure) {
            when(this.objectStore.putObject(any(PutObjectRequest.class),
                    any(software.amazon.awssdk.core.sync.RequestBody.class))).thenThrow(failure);
        }

        /** @param failure what the probe removal raises */
        void deleteObjectFailure(final RuntimeException failure) {
            when(this.objectStore.deleteObject(any(DeleteObjectRequest.class))).thenThrow(failure);
        }

        /** Makes the probe listing answer without the object that was just written. */
        void probeListingIsEmpty() {
            this.probeListed = false;
        }

        /** @param arn the resource identifier the queue reports */
        void queueArn(final String arn) {
            this.queueAttributes.put(QueueAttributeName.QUEUE_ARN, arn);
        }

        /**
         * @param name  the attribute to change
         * @param value the value the queue reports for it
         */
        void queueAttribute(final QueueAttributeName name, final String value) {
            this.queueAttributes.put(name, value);
        }

        /** Makes the queue resolution report that the queue does not exist. */
        void queueDoesNotExist() {
            when(this.queueClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                    .thenReturn(CompletableFuture.failedFuture(
                            QueueDoesNotExistException.builder().message("absent").build()));
        }

        /** Makes the topic listing answer with no topics. */
        void topicListingIsEmpty() {
            this.topics = List.of();
        }

        /** @param arn the identifier of the single topic the listing answers with */
        void topicArn(final String arn) {
            this.topics = List.of(Topic.builder().topicArn(arn).build());
        }

        /** @param exists whether the resolved topic answers an attribute read */
        void topicExists(final boolean exists) {
            this.topicExists = exists;
        }
    }
}
