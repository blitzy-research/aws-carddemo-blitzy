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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
import software.amazon.awssdk.services.s3.model.GetBucketEncryptionRequest;
import software.amazon.awssdk.services.s3.model.GetBucketEncryptionResponse;
import software.amazon.awssdk.services.s3.model.GetBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.GetBucketPolicyResponse;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.GetPublicAccessBlockRequest;
import software.amazon.awssdk.services.s3.model.GetPublicAccessBlockResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.PublicAccessBlockConfiguration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionByDefault;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionConfiguration;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionRule;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.GetTopicAttributesRequest;
import software.amazon.awssdk.services.sns.model.GetTopicAttributesResponse;
import software.amazon.awssdk.services.sns.model.ListTopicsRequest;
import software.amazon.awssdk.services.sns.model.ListTopicsResponse;
import software.amazon.awssdk.services.sns.model.SnsException;
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

        @Test
        @DisplayName("establishes each of the five policy and access-posture properties, so a resource "
                + "that is the deployment's own but reachable by somebody else is still refused")
        void establishesTheAccessPostureOfEveryResource() {
            // Ownership, versioning, queue shape and write capability were verified from the
            // beginning; these five were the gap. Asserted as calls actually made, because a check
            // that is never reached is indistinguishable from one that always passes.
            final Fixture fixture = Fixture.trusted();

            fixture.verifier().afterPropertiesSet();

            verify(fixture.objectStore()).getPublicAccessBlock(
                    ArgumentMatchers.<GetPublicAccessBlockRequest>argThat(request ->
                            BUCKET.equals(request.bucket())
                                    && OWNED_ACCOUNT.equals(request.expectedBucketOwner())));
            verify(fixture.objectStore()).getBucketPolicy(
                    ArgumentMatchers.<GetBucketPolicyRequest>argThat(request ->
                            BUCKET.equals(request.bucket())
                                    && OWNED_ACCOUNT.equals(request.expectedBucketOwner())));
            verify(fixture.objectStore()).getBucketEncryption(
                    ArgumentMatchers.<GetBucketEncryptionRequest>argThat(request ->
                            BUCKET.equals(request.bucket())
                                    && OWNED_ACCOUNT.equals(request.expectedBucketOwner())));
            verify(fixture.notificationClient()).getTopicAttributes(
                    ArgumentMatchers.<GetTopicAttributesRequest>argThat(request ->
                            ownedTopicArn().equals(request.topicArn())));
            assertThat(fixture.resolvedQueueAttributeNames())
                    .as("the queue's own policy is read with the same attribute call as its shape, so "
                            + "establishing it costs no further request")
                    .contains(QueueAttributeName.POLICY);
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

    }

    @Nested
    @DisplayName("A deployment whose staging bucket is reachable by somebody else")
    final class ABucketReachableBySomebodyElse {

        @Test
        @DisplayName("must not start when the bucket's public-access controls cannot be read, which is "
                + "also how a bucket with no such configuration answers")
        void becauseThePublicAccessControlsCannotBeRead() {
            final Fixture fixture = Fixture.trusted();
            fixture.publicAccessBlockFailure(S3Exception.builder()
                    .message("NoSuchPublicAccessBlockConfiguration")
                    .build());

            assertRefused(fixture, "public-access controls could not be read");
        }

        @Test
        @DisplayName("must not start when the object store reports no controls at all")
        void becauseNoPublicAccessControlsAreReported() {
            final Fixture fixture = Fixture.trusted();
            fixture.publicAccessBlock(null);

            assertRefused(fixture, "does not block every form of public access");
        }

        @Test
        @DisplayName("must not start when any one of the four controls is unset, because each closes a "
                + "different route and three of four leaves one open")
        void becauseAnyOneOfTheFourControlsIsUnset() {
            // Asserted one control at a time rather than as a single perturbation, because the defect
            // this guards against is a posture that looks configured: a bucket with three of the four
            // set reads as hardened to anybody who does not count them.
            assertRefused(fixtureWithControls(false, true, true, true),
                    "does not block every form of public access");
            assertRefused(fixtureWithControls(true, false, true, true),
                    "does not block every form of public access");
            assertRefused(fixtureWithControls(true, true, false, true),
                    "does not block every form of public access");
            assertRefused(fixtureWithControls(true, true, true, false),
                    "does not block every form of public access");
        }

        @Test
        @DisplayName("must not start when a control is reported as neither set nor unset, which a "
                + "partially-written configuration does")
        void becauseAControlIsNotReportedAtAll() {
            final Fixture fixture = Fixture.trusted();
            fixture.publicAccessBlock(PublicAccessBlockConfiguration.builder()
                    .blockPublicAcls(true)
                    .ignorePublicAcls(true)
                    .blockPublicPolicy(true)
                    .build());

            assertRefused(fixture, "does not block every form of public access");
        }

        /**
         * @param  blockPublicAcls       whether public access-control lists are refused
         * @param  ignorePublicAcls      whether existing public access-control lists are disregarded
         * @param  blockPublicPolicy     whether a public policy is refused
         * @param  restrictPublicBuckets whether policy-granted public access is confined
         * @return a trusted fixture perturbed only in its public-access controls
         */
        private Fixture fixtureWithControls(final boolean blockPublicAcls,
                final boolean ignorePublicAcls, final boolean blockPublicPolicy,
                final boolean restrictPublicBuckets) {
            final Fixture fixture = Fixture.trusted();
            fixture.publicAccessBlock(publicAccessBlockWith(blockPublicAcls, ignorePublicAcls,
                    blockPublicPolicy, restrictPublicBuckets));
            return fixture;
        }
    }

    @Nested
    @DisplayName("A deployment whose resources carry no sound policy of their own")
    final class ResourcesWithoutASoundPolicy {

        @Test
        @DisplayName("must not start when the bucket's policy cannot be read, which is also how a "
                + "bucket with no policy answers")
        void becauseTheBucketPolicyCannotBeRead() {
            final Fixture fixture = Fixture.trusted();
            fixture.bucketPolicyFailure(
                    S3Exception.builder().message("NoSuchBucketPolicy").build());

            assertRefused(fixture, "own policy could not be read");
        }

        @Test
        @DisplayName("must not start when the bucket reports an empty policy")
        void becauseTheBucketPolicyIsEmpty() {
            final Fixture fixture = Fixture.trusted();
            fixture.bucketPolicy(null);

            assertRefused(fixture, "carries no resource policy of its own");
        }

        @Test
        @DisplayName("must not start when the bucket's policy is not a policy document")
        void becauseTheBucketPolicyIsUnreadable() {
            final Fixture fixture = Fixture.trusted();
            fixture.bucketPolicy("{\"Version\":\"2012-10-17\"}");

            assertRefused(fixture, "could not be read as a policy document");
        }

        @Test
        @DisplayName("must not start when the bucket's policy grants every principal, and the probe "
                + "object is never written to a bucket in that state")
        void becauseTheBucketPolicyGrantsEveryPrincipal() {
            final Fixture fixture = Fixture.trusted();
            fixture.bucketPolicy(openPolicy("s3"));

            assertRefused(fixture, "grants every principal");
            // The posture is established before the write-capability probe deliberately: a bucket
            // anybody can read is one this deployment must not write to at all, not even a
            // zero-length object under a reserved key.
            verify(fixture.objectStore(), never()).putObject(any(PutObjectRequest.class),
                    any(software.amazon.awssdk.core.sync.RequestBody.class));
        }

        @Test
        @DisplayName("must not start when the bucket's policy does not deny plain transport, so an "
                + "object carrying balances could cross the network in clear text")
        void becauseTheBucketPolicyPermitsPlainTransport() {
            final Fixture fixture = Fixture.trusted();
            fixture.bucketPolicy(policyPermittingPlainTransport("s3"));

            assertRefused(fixture, "does not deny every principal every action over an unencrypted");
        }

        @Test
        @DisplayName("must not start when the queue reports no policy, because nothing attached to the "
                + "queue then constrains who may put a job-control card on it")
        void becauseTheQueueCarriesNoPolicy() {
            final Fixture fixture = Fixture.trusted();
            fixture.queueAttributeAbsent(QueueAttributeName.POLICY);

            assertRefused(fixture, "job queue carries no resource policy of its own");
        }

        @Test
        @DisplayName("must not start when the queue's policy grants every principal")
        void becauseTheQueuePolicyGrantsEveryPrincipal() {
            final Fixture fixture = Fixture.trusted();
            fixture.queueAttribute(QueueAttributeName.POLICY, openPolicy("sqs"));

            assertRefused(fixture, "job queue's resource policy grants every principal");
        }

        @Test
        @DisplayName("must not start when the queue's policy does not deny plain transport")
        void becauseTheQueuePolicyPermitsPlainTransport() {
            final Fixture fixture = Fixture.trusted();
            fixture.queueAttribute(QueueAttributeName.POLICY,
                    policyPermittingPlainTransport("sqs"));

            assertRefused(fixture, "job queue's resource policy does not deny");
        }

        @Test
        @DisplayName("must not start when the topic's attributes cannot be read at all")
        void becauseTheTopicAttributesCannotBeRead() {
            final Fixture fixture = Fixture.trusted();
            fixture.topicAttributesFailure(
                    SnsException.builder().message("AuthorizationError").build());

            assertRefused(fixture, "attributes could not be read");
        }

        @Test
        @DisplayName("must not start when the topic reports no attributes, so its policy is not absent "
                + "but unknown - and an unknown policy is treated as an absent one")
        void becauseTheTopicReportsNoAttributes() {
            final Fixture fixture = Fixture.trusted();
            fixture.topicAttributesAreAbsent();

            assertRefused(fixture, "notification topic carries no resource policy of its own");
        }

        @Test
        @DisplayName("must not start when the topic carries only the default policy the service "
                + "attaches, because that default says nothing about transport")
        void becauseTheTopicCarriesOnlyTheServiceDefault() {
            // The finding this check exists to make. A topic created and left alone carries a policy
            // that names every principal and confines it to the owning account - which is not an open
            // grant and is accepted as such - and carries no transport denial at all.
            final Fixture fixture = Fixture.trusted();
            fixture.topicPolicy(String.format(Locale.ROOT, """
                    {"Version":"2008-10-17","Statement":[{"Sid":"__default_statement_ID",\
                    "Effect":"Allow","Principal":{"AWS":"*"},"Action":["SNS:Publish"],\
                    "Resource":"%s","Condition":{"StringEquals":{"AWS:SourceOwner":"%s"}}}]}\
                    """, ownedTopicArn(), OWNED_ACCOUNT));

            assertRefused(fixture, "notification topic's resource policy does not deny");
        }

        @Test
        @DisplayName("must not start when the topic's policy grants every principal")
        void becauseTheTopicPolicyGrantsEveryPrincipal() {
            final Fixture fixture = Fixture.trusted();
            fixture.topicPolicy(openPolicy("sns"));

            assertRefused(fixture, "notification topic's resource policy grants every principal");
        }

        @Test
        @DisplayName("must not start when the topic reports an empty policy")
        void becauseTheTopicPolicyIsEmpty() {
            final Fixture fixture = Fixture.trusted();
            fixture.topicPolicy(null);

            assertRefused(fixture, "notification topic carries no resource policy of its own");
        }
    }

    @Nested
    @DisplayName("A deployment whose staging bucket does not encrypt what it is given")
    final class ABucketThatDoesNotEncrypt {

        @Test
        @DisplayName("must not start when the default encryption cannot be read, which is also how a "
                + "bucket with none configured answers")
        void becauseTheEncryptionCannotBeRead() {
            final Fixture fixture = Fixture.trusted();
            fixture.encryptionFailure(S3Exception.builder()
                    .message("ServerSideEncryptionConfigurationNotFoundError")
                    .build());

            assertRefused(fixture, "default encryption could not be read");
        }

        @Test
        @DisplayName("must not start when no configuration is reported")
        void becauseNoConfigurationIsReported() {
            final Fixture fixture = Fixture.trusted();
            fixture.encryption(null);

            assertRefused(fixture, "configures no default server-side encryption algorithm");
        }

        @Test
        @DisplayName("must not start when a rule is present and applies nothing by default")
        void becauseARuleAppliesNothingByDefault() {
            final Fixture fixture = Fixture.trusted();
            fixture.encryption(ServerSideEncryptionConfiguration.builder()
                    .rules(ServerSideEncryptionRule.builder().bucketKeyEnabled(false).build())
                    .build());

            assertRefused(fixture, "configures no default server-side encryption algorithm");
        }

        @Test
        @DisplayName("must not start when the algorithm named is one this deployment cannot resolve")
        void becauseTheAlgorithmIsUnrecognised() {
            final Fixture fixture = Fixture.trusted();
            fixture.encryption(ServerSideEncryptionConfiguration.builder()
                    .rules(ServerSideEncryptionRule.builder()
                            .applyServerSideEncryptionByDefault(
                                    ServerSideEncryptionByDefault.builder()
                                            .sseAlgorithm("nothing-anybody-has-heard-of")
                                            .build())
                            .build())
                    .build());

            assertRefused(fixture, "configures no default server-side encryption algorithm");
        }

        @Test
        @DisplayName("starts when the bucket encrypts with a managed key instead, because the property "
                + "required is that an unnamed algorithm is still an algorithm")
        void startsWithAManagedKeyInstead() {
            final Fixture fixture = Fixture.trusted();
            fixture.encryption(defaultEncryption(ServerSideEncryption.AWS_KMS));

            fixture.verifier().afterPropertiesSet();

            verify(fixture.objectStore()).getBucketEncryption(any(GetBucketEncryptionRequest.class));
        }
    }

    @Nested
    @DisplayName("What a refusal publishes when a provider is the one that refused")
    final class WhatARefusalPublishes {

        /** A provider message shaped like the ones a software development kit actually composes. */
        private static final String PROVIDER_MESSAGE = "Access Denied (Service: S3, Status Code: 403,"
                + " Request ID: 8XZQ4EXAMPLE, Extended Request ID: aBcDeF, Bucket:"
                + " somebody-elses-bucket, Owner: 999999999999, Credential:"
                + " AKIAIOSFODNN7EXAMPLE/20260811/us-east-1/s3/aws4_request, Endpoint:"
                + " https://s3.us-east-1.amazonaws.com)";

        @Test
        @DisplayName("carries no cause, because start-up failure reporting renders every cause in full")
        void carriesNoCause() {
            final Fixture fixture = Fixture.trusted();
            fixture.headBucketFailure(S3Exception.builder().message(PROVIDER_MESSAGE).build());

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> fixture.verifier().afterPropertiesSet())
                    .satisfies(refusal -> assertThat(refusal.getCause()).isNull());
        }

        @Test
        @DisplayName("carries no fragment of the provider's own message: not the endpoint, the request "
                + "identifiers, the bucket, the owning account or the credential")
        void carriesNoFragmentOfTheProviderMessage() {
            final Fixture fixture = Fixture.trusted();
            fixture.headBucketFailure(S3Exception.builder().message(PROVIDER_MESSAGE).build());

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> fixture.verifier().afterPropertiesSet())
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .doesNotContain("Access Denied")
                            .doesNotContain("8XZQ4EXAMPLE")
                            .doesNotContain("aBcDeF")
                            .doesNotContain("somebody-elses-bucket")
                            .doesNotContain("999999999999")
                            .doesNotContain("AKIAIOSFODNN7EXAMPLE")
                            .doesNotContain("s3.us-east-1.amazonaws.com"));
        }

        @Test
        @DisplayName("does carry the failure's classification, so a diagnosing reader has the type "
                + "chain and the code location to correlate the provider's own record against")
        void carriesTheClassification() {
            final Fixture fixture = Fixture.trusted();
            fixture.headBucketFailure(S3Exception.builder().message(PROVIDER_MESSAGE).build());

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> fixture.verifier().afterPropertiesSet())
                    .withMessageContaining("classified")
                    .withMessageContaining("S3Exception");
        }

        @Test
        @DisplayName("says nothing about a classification when the refusal was a comparison this "
                + "deployment made rather than a failure a provider reported")
        void saysNothingAboutAClassificationWhenNothingFailed() {
            final Fixture fixture = Fixture.trusted();
            fixture.versioningStatus(BucketVersioningStatus.SUSPENDED);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> fixture.verifier().afterPropertiesSet())
                    .withMessageContaining("does not carry enabled object versioning")
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .doesNotContain("classified"));
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
     * Requires the verification to refuse, and requires the refusal to explain itself while publishing
     * nothing a provider handed it.
     *
     * <p>Shared by every refusal case in this class, which is what makes the two disclosure properties
     * below universal rather than asserted in the one place somebody remembered. A refusal carries no
     * cause of any kind: it is thrown from a bean's initialisation, so the framework's start-up failure
     * reporting renders whatever it carries, and a provider exception carries the endpoint, the request
     * identifiers, the resource and - on a signature failure - an access key identifier.</p>
     *
     * @param fixture  the perturbed fixture
     * @param fragment wording the refusal must carry
     */
    private static void assertRefused(final Fixture fixture, final String fragment) {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> fixture.verifier().afterPropertiesSet())
                .withMessageContaining("must not start")
                .withMessageContaining(fragment)
                .as("a refusal that named the account, the identifier or the owner reported by the "
                        + "service would put another account's resource identity into this "
                        + "deployment's log")
                .satisfies(refusal -> assertThat(refusal.getMessage())
                        .doesNotContain(FOREIGN_ACCOUNT)
                        .doesNotContain(OWNED_ACCOUNT))
                .as("a chained provider exception is rendered in full by start-up failure reporting, "
                        + "so the refusal carries a classification of the failure and never the "
                        + "failure itself")
                .satisfies(refusal -> assertThat(refusal.getCause()).isNull());
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

        /** Attribute names the queue resolution asked the queue service for. */
        private final List<QueueAttributeName> resolvedQueueAttributeNames = new ArrayList<>();

        /** The queue attributes the double answers with. */
        private final Map<QueueAttributeName, String> queueAttributes =
                new EnumMap<>(QueueAttributeName.class);

        /** Versioning status the double answers with. */
        private BucketVersioningStatus versioningStatus = BucketVersioningStatus.ENABLED;

        /** Public-access controls the bucket reports. */
        private PublicAccessBlockConfiguration publicAccessBlock = everyPublicAccessRouteBlocked();

        /** The bucket's own policy document, as the object store reports it. */
        private String bucketPolicy = soundPolicy("s3");

        /** Default encryption the bucket reports. */
        private ServerSideEncryptionConfiguration encryption =
                defaultEncryption(ServerSideEncryption.AES256);

        /** Attributes the resolved topic reports, the policy among them. */
        private Map<String, String> topicAttributes = new HashMap<>(
                Map.of("TopicArn", ownedTopicArn(), "Policy", soundPolicy("sns")));

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
            fixture.queueAttributes.put(QueueAttributeName.POLICY, soundPolicy("sqs"));
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
            when(this.objectStore.getPublicAccessBlock(any(GetPublicAccessBlockRequest.class)))
                    .thenAnswer(invocation -> GetPublicAccessBlockResponse.builder()
                            .publicAccessBlockConfiguration(this.publicAccessBlock)
                            .build());
            when(this.objectStore.getBucketPolicy(any(GetBucketPolicyRequest.class)))
                    .thenAnswer(invocation -> GetBucketPolicyResponse.builder()
                            .policy(this.bucketPolicy)
                            .build());
            when(this.objectStore.getBucketEncryption(any(GetBucketEncryptionRequest.class)))
                    .thenAnswer(invocation -> GetBucketEncryptionResponse.builder()
                            .serverSideEncryptionConfiguration(this.encryption)
                            .build());
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
                    .thenAnswer(invocation -> {
                        // The customiser is applied to a builder of this fixture's own so the
                        // attribute names the resolver asked for can be asserted: the queue's policy
                        // has to arrive with the same call as its shape, and a second call would be a
                        // second round trip at every start-up.
                        final Consumer<GetQueueAttributesRequest.Builder> customiser =
                                invocation.getArgument(0);
                        final GetQueueAttributesRequest.Builder request =
                                GetQueueAttributesRequest.builder();
                        customiser.accept(request);
                        this.resolvedQueueAttributeNames.addAll(request.build().attributeNames());
                        return CompletableFuture.completedFuture(
                                GetQueueAttributesResponse.builder()
                                        .attributes(Map.copyOf(this.queueAttributes))
                                        .build());
                    });
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
            when(this.notificationClient.getTopicAttributes(any(GetTopicAttributesRequest.class)))
                    .thenAnswer(invocation -> GetTopicAttributesResponse.builder()
                            .attributes(this.topicAttributes)
                            .build());
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

        /** @return the notification-client double */
        SnsClient notificationClient() {
            return this.notificationClient;
        }

        /** @return the notification-facade double */
        SnsOperations notifications() {
            return this.notifications;
        }

        /** @return the attribute names the queue resolution asked the queue service for */
        List<QueueAttributeName> resolvedQueueAttributeNames() {
            return this.resolvedQueueAttributeNames;
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

        /** @param configuration the public-access controls the bucket reports */
        void publicAccessBlock(final PublicAccessBlockConfiguration configuration) {
            this.publicAccessBlock = configuration;
        }

        /** @param failure what the public-access-control read raises */
        void publicAccessBlockFailure(final RuntimeException failure) {
            when(this.objectStore.getPublicAccessBlock(any(GetPublicAccessBlockRequest.class)))
                    .thenThrow(failure);
        }

        /** @param document the policy the bucket reports, or {@code null} for none */
        void bucketPolicy(final String document) {
            this.bucketPolicy = document;
        }

        /** @param failure what the bucket-policy read raises */
        void bucketPolicyFailure(final RuntimeException failure) {
            when(this.objectStore.getBucketPolicy(any(GetBucketPolicyRequest.class)))
                    .thenThrow(failure);
        }

        /** @param configuration the default encryption the bucket reports, or {@code null} for none */
        void encryption(final ServerSideEncryptionConfiguration configuration) {
            this.encryption = configuration;
        }

        /** @param failure what the encryption read raises */
        void encryptionFailure(final RuntimeException failure) {
            when(this.objectStore.getBucketEncryption(any(GetBucketEncryptionRequest.class)))
                    .thenThrow(failure);
        }

        /** @param name the attribute the queue stops reporting */
        void queueAttributeAbsent(final QueueAttributeName name) {
            this.queueAttributes.remove(name);
        }

        /** @param document the policy the topic reports, or {@code null} for none */
        void topicPolicy(final String document) {
            this.topicAttributes.remove("Policy");
            if (document != null) {
                this.topicAttributes.put("Policy", document);
            }
        }

        /** Makes the topic report no attributes at all, which a client may do. */
        void topicAttributesAreAbsent() {
            when(this.notificationClient.getTopicAttributes(any(GetTopicAttributesRequest.class)))
                    .thenAnswer(invocation -> GetTopicAttributesResponse.builder().build());
        }

        /** @param failure what the topic-attribute read raises */
        void topicAttributesFailure(final RuntimeException failure) {
            when(this.notificationClient.getTopicAttributes(any(GetTopicAttributesRequest.class)))
                    .thenThrow(failure);
        }
    }

    /**
     * Composes the policy shape the local bootstrap writes and a production account is required to
     * carry: one statement denying every principal every action of the service over plain transport.
     *
     * @param  namespace the service's action namespace
     * @return a sound document
     */
    private static String soundPolicy(final String namespace) {
        return String.format(Locale.ROOT, """
                {"Version":"2012-10-17","Statement":[{"Sid":"DenyInsecureTransport",\
                "Effect":"Deny","Principal":"*","Action":"%s:*","Resource":"*",\
                "Condition":{"Bool":{"aws:SecureTransport":"false"}}}]}\
                """, namespace);
    }

    /**
     * Composes a document that grants every principal with nothing confining the grant.
     *
     * @param  namespace the service's action namespace
     * @return an open document
     */
    private static String openPolicy(final String namespace) {
        return String.format(Locale.ROOT, """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*",\
                "Action":"%s:*","Resource":"*"}]}\
                """, namespace);
    }

    /**
     * Composes a document that says nothing about transport - the shape a resource carries when a
     * policy was attached for another purpose and nobody closed plain transport.
     *
     * @param  namespace the service's action namespace
     * @return a document that permits plain transport
     */
    private static String policyPermittingPlainTransport(final String namespace) {
        return String.format(Locale.ROOT, """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",\
                "Principal":{"AWS":"arn:aws:iam::000000000000:role/synthetic"},\
                "Action":"%s:*","Resource":"*"}]}\
                """, namespace);
    }

    /**
     * @return public-access controls with all four routes closed
     */
    private static PublicAccessBlockConfiguration everyPublicAccessRouteBlocked() {
        return publicAccessBlockWith(true, true, true, true);
    }

    /**
     * @param  blockPublicAcls       whether public access-control lists are refused
     * @param  ignorePublicAcls      whether public access-control lists already present are disregarded
     * @param  blockPublicPolicy     whether a public policy is refused
     * @param  restrictPublicBuckets whether public and cross-account access through a policy is confined
     * @return the configuration
     */
    private static PublicAccessBlockConfiguration publicAccessBlockWith(final boolean blockPublicAcls,
            final boolean ignorePublicAcls, final boolean blockPublicPolicy,
            final boolean restrictPublicBuckets) {
        return PublicAccessBlockConfiguration.builder()
                .blockPublicAcls(blockPublicAcls)
                .ignorePublicAcls(ignorePublicAcls)
                .blockPublicPolicy(blockPublicPolicy)
                .restrictPublicBuckets(restrictPublicBuckets)
                .build();
    }

    /**
     * @param  algorithm the algorithm applied to an object written without naming one
     * @return a configuration carrying one rule applying that algorithm
     */
    private static ServerSideEncryptionConfiguration defaultEncryption(
            final ServerSideEncryption algorithm) {
        return ServerSideEncryptionConfiguration.builder()
                .rules(ServerSideEncryptionRule.builder()
                        .applyServerSideEncryptionByDefault(ServerSideEncryptionByDefault.builder()
                                .sseAlgorithm(algorithm)
                                .build())
                        .build())
                .build();
    }
}
