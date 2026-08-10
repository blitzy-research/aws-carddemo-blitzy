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

import com.carddemo.support.AbstractLocalStackIT;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Executes the real AWS bootstrap hook against a live emulator and verifies the resources it actually
 * produces.
 *
 * <h2>What this test guards, and why reading the script is not enough</h2>
 *
 * <p>Its companion, {@code LocalStackBootstrapContractTest}, reads
 * {@code localstack/init/01-create-aws-resources.sh} as text and holds it to a contract: the resource
 * names, the queue's two behavioural attributes, the bucket's versioning, the idempotency guards, and
 * the agreement between the seven files that name these resources. That is a real check, but it can
 * only prove the script <em>declares</em> the right thing. A script can declare a correct attribute
 * pair and still fail - a flag the service rejects, a guard whose condition is inverted, an ordering
 * that applies versioning to a bucket that does not exist yet - and every one of those failures is
 * invisible to a reader.
 *
 * <p>This test closes that gap by running the file itself. No copy of it is embedded here and no part
 * of it is reimplemented: the file on disk is transferred into the container and invoked, so what runs
 * is what a developer's local stack runs.
 *
 * <h2>Why the resources are verified through the SDK and not through the emulator's own tool</h2>
 *
 * <p>The script provisions with the command-line tool shipped inside the emulator, and it reads its own
 * work back with that same tool - which is good practice for a provisioning script but is not
 * independent evidence. This test therefore verifies from the JVM, through the same AWS SDK clients the
 * application itself uses. If the tool and the SDK disagreed about what was provisioned, that
 * disagreement would matter far more than either reading alone, and only a check from the second of the
 * two can see it.
 *
 * <h2>Why the hook is invoked explicitly rather than left to fire on start-up</h2>
 *
 * <p>In a local stack this file is a ready hook: the stack definition mounts its directory at the
 * emulator's ready path and the emulator runs it once the edge port is serving. Reproducing that here
 * would make the test race the emulator's readiness sequence, and a flaky provisioning check is worse
 * than none. The file is therefore placed at a neutral path and invoked directly, which is
 * deterministic and additionally lets the exit status and the whole of the script's output be asserted.
 * The mount that makes it a hook is a static fact about the stack definition, and its companion pins
 * it there.
 *
 * <h2>Why idempotency is exercised by running it twice</h2>
 *
 * <p>A ready hook re-runs on every container start and the emulator's hook runner raises if it exits
 * non-zero, so "already there" has to be a normal outcome rather than a failure. The only way to
 * demonstrate that is to run the script a second time against the state the first run produced. The
 * second run must succeed, must report each guarded resource as already present rather than recreating
 * it, and must leave exactly one of each resource behind.
 *
 * <h2>How the evidence is obtained, and what that does and does not prove</h2>
 *
 * <p>Every expected value here is written out by hand: the three resource names, the region, both
 * queue attribute values, the versioning status and each of the script's reported lines. None is read
 * from the script and compared against itself. What this proves is that this exact file, run against
 * this exact emulator version, produces a first-in-first-out queue with content-based deduplication
 * off, a versioned bucket and a topic, and that running it again changes nothing. What it does not
 * prove is that a real cloud account would behave identically; the emulator is the environment every
 * validation gate is defined against, and no gate requires a real account.
 *
 * <p>Why this executing tier exists alongside {@link LocalStackBootstrapContractTest}, and why neither
 * tier's evidence subsumes the other's, is reasoned in {@code docs/decision-log.md} DL-114. The
 * deduplication attribute read back here is the resource-side half of DL-043 and the queue's name is
 * DL-045.
 *
 * <h2>Why this tier is bounded, and by figures it does not own</h2>
 *
 * <p>Everything here crosses a process boundary: a container is started, a shell script is executed inside
 * it, and three cloud clients call it over a socket. Every one of those can stall rather than fail, and a
 * stalled build reports nothing at all. Two of the methods below carried their own budget and the rest,
 * including the lifecycle method that runs the hook, carried none - so a wedged emulator hung the build at
 * the first unbounded call, and the clients themselves had no whole-call or per-attempt budget to fall back
 * on.
 *
 * <p>Both bounds now come from {@link com.carddemo.support.AbstractLocalStackIT}: its class-level figure is
 * applied here as a class-level {@code @Timeout} and again on the lifecycle method, which a class-level
 * annotation does not cover, and its bounded client configuration is applied to all three clients. The
 * figures are referenced rather than restated so that this tier and the shared tier cannot drift apart. This
 * class deliberately does not extend that base - it starts an emulator of its own, with the provisioning
 * hook copied in before start - which is exactly why the base's two levers are public.
 *
 * <p><strong>Provenance.</strong> Checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("AWS bootstrap, executed: the hook runs, provisions three resources, and reruns cleanly")
@Timeout(value = AbstractLocalStackIT.EXTERNAL_BOUNDARY_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
class LocalStackBootstrapIT {

    /**
     * The emulator image, pinned by tag <em>and</em> by content digest, exactly as the stack definition and
     * the continuous-integration workflow pin it. Its companion asserts that this reference and the stack
     * definition's agree, so a drift between them is a test failure rather than a silently different
     * environment - and the digest is what makes that agreement mean one image rather than one label.
     */
    private static final String EMULATOR_IMAGE = "localstack/localstack:4.14.0@sha256:"
            + "3ebc37595918b8accb852f8048fef2aff047d465167edd655528065b07bc364a";

    /**
     * The repository the pinned reference denotes, named for the container library's compatibility check.
     *
     * <p>Required by the digest rather than by the image: the library recognises the bare tagged reference
     * and treats the digest-bearing one as an unknown substitute, because it compares the whole reference
     * against the name it was written for.
     */
    private static final String EMULATOR_REPOSITORY = "localstack/localstack";

    /** The hook as it exists in the module. Nothing about it is copied or reimplemented here. */
    private static final String HOOK_ON_HOST = "localstack/init/01-create-aws-resources.sh";

    /**
     * Where the hook is placed inside the container. Deliberately not the emulator's ready directory:
     * placing it there would make the emulator run it on its own schedule, and this test would then
     * race that.
     */
    private static final String HOOK_IN_CONTAINER = "/tmp/carddemo-bootstrap.sh";

    /** The canonical object-store bucket. */
    private static final String BUCKET = "carddemo-batch-staging";

    /** The canonical queue. */
    private static final String QUEUE = "JOBS.fifo";

    /** The canonical notification topic. */
    private static final String TOPIC = "carddemo-job-notifications";

    /** The single stable message group the publisher carries per message. */
    private static final String MESSAGE_GROUP = "carddemo-job-submission";

    /** The canonical region. */
    private static final String REGION = "us-east-1";

    /** Every line the script prefixes, so the log can be found in a container's output. */
    private static final String LOG_PREFIX = "[carddemo-init]";

    /** How many resources the script provisions. */
    private static final int PROVISIONED_RESOURCE_COUNT = 3;

    /** The emulator every test in this class shares. */
    private static final LocalStackContainer LOCALSTACK = startEmulator();

    /** The outcome of the first, provisioning run. Captured once and asserted by several tests. */
    private static Container.ExecResult firstRun;

    /**
     * Starts the emulator with exactly the three services this module uses.
     *
     * <p>Started in a static initialiser so one emulator serves the whole class and the Testcontainers
     * resource reaper removes it when the JVM exits. The hook is transferred in before start, which is
     * the only point at which a file can be added to the image's filesystem declaratively.
     *
     * @return the started emulator
     */
    private static LocalStackContainer startEmulator() {
        final LocalStackContainer container =
                new LocalStackContainer(DockerImageName.parse(EMULATOR_IMAGE)
                        .asCompatibleSubstituteFor(EMULATOR_REPOSITORY))
                        .withServices(LocalStackContainer.Service.S3,
                                LocalStackContainer.Service.SQS,
                                LocalStackContainer.Service.SNS)
                        .withCopyFileToContainer(
                                MountableFile.forHostPath(HOOK_ON_HOST), HOOK_IN_CONTAINER);
        container.start();
        return container;
    }

    /**
     * Runs the hook once, before any assertion, and keeps its outcome.
     *
     * <p>Invoked through {@code bash} rather than relying on the transferred file's executable bit,
     * which also exercises the interpreter the script declares. A non-zero exit here is reported
     * immediately with the script's own output, because every later assertion would otherwise fail for
     * a reason the failure text would not explain.
     *
     * @throws IOException          if the container cannot be reached
     * @throws InterruptedException if the invocation is interrupted
     */
    @BeforeAll
    @Timeout(value = AbstractLocalStackIT.EXTERNAL_BOUNDARY_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    static void runTheHookOnce() throws IOException, InterruptedException {
        firstRun = LOCALSTACK.execInContainer("bash", HOOK_IN_CONTAINER);
        assertThat(firstRun.getExitCode())
                .as("the hook must succeed on a fresh emulator; stdout was:%n%s%nstderr was:%n%s",
                        firstRun.getStdout(), firstRun.getStderr())
                .isZero();
    }

    /**
     * Builds an object-store client bound to the running emulator.
     *
     * @return a client for the emulator
     */
    private static S3Client s3Client() {
        return S3Client.builder()
                .overrideConfiguration(AbstractLocalStackIT.boundedCallConfiguration())
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .forcePathStyle(true)
                .build();
    }

    /**
     * Builds a queue client bound to the running emulator.
     *
     * @return a client for the emulator
     */
    private static SqsClient sqsClient() {
        return SqsClient.builder()
                .overrideConfiguration(AbstractLocalStackIT.boundedCallConfiguration())
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
    }

    /**
     * Builds a notification client bound to the running emulator.
     *
     * @return a client for the emulator
     */
    private static SnsClient snsClient() {
        return SnsClient.builder()
                .overrideConfiguration(AbstractLocalStackIT.boundedCallConfiguration())
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
    }

    /**
     * Returns the queue's URL as the emulator reports it.
     *
     * @return the queue URL
     */
    private static String queueUrl() {
        try (SqsClient client = sqsClient()) {
            return client.getQueueUrl(request -> request.queueName(QUEUE)).queueUrl();
        }
    }

    @Nested
    @DisplayName("the first run")
    final class TheFirstRun {

        @Test
        @DisplayName("succeeds and reports each of the three resources becoming ready")
        void succeedsAndReportsEachResourceReady() {
            // The reported lines are part of the contract, not incidental output: the gate runbook
            // reads them back out of the container log, and they are the only instrumentation the
            // bootstrap step has. Each of the three resource lines says "verified" rather than
            // "ready" because the script reads the resource back out of the emulator and compares
            // what it finds before it prints the line. A line that appears is therefore evidence
            // that a comparison succeeded, not an announcement that a call was made.
            final String output = firstRun.getStdout();

            assertThat(firstRun.getExitCode()).isZero();
            assertThat(output)
                    .as("every line must carry the prefix that makes it findable in a container log")
                    .contains(LOG_PREFIX)
                    .contains(LOG_PREFIX + " bootstrap starting: region=" + REGION
                            + " bucket=" + BUCKET + " queue=" + QUEUE + " topic=" + TOPIC)
                    .contains("message group id " + MESSAGE_GROUP
                            + " is carried per message by the publisher, not provisioned")
                    .contains("queue " + QUEUE + " verified at")
                    .contains("bucket " + BUCKET + " object versioning verified: "
                            + BucketVersioningStatus.ENABLED.toString())
                    .contains("topic " + TOPIC + " verified at")
                    .contains("AWS resource bootstrap complete: " + PROVISIONED_RESOURCE_COUNT
                            + " of " + PROVISIONED_RESOURCE_COUNT
                            + " resources verified (queue, bucket, topic)");
            assertThat(firstRun.getStderr())
                    .as("a clean provisioning run writes nothing to the error stream")
                    .isEmpty();
        }

        @Test
        @DisplayName("creates the resources rather than finding them, because the emulator was fresh")
        void createsTheResourcesOnAFreshEmulator() {
            // This is what makes the idempotency test below meaningful. If the first run had reported
            // the resources as already present, the second run would prove nothing - both runs would
            // have taken the same branch, and the create branch would never have been exercised at
            // all.
            final String output = firstRun.getStdout();

            assertThat(output)
                    .as("a fresh emulator must take the create branch for both guarded resources")
                    .contains("created queue " + QUEUE)
                    .contains("created bucket " + BUCKET);
            assertThat(output)
                    .as("and must not report either as already present")
                    .doesNotContain("queue " + QUEUE + " already present")
                    .doesNotContain("bucket " + BUCKET + " already present");
        }

        @Test
        @DisplayName("reads its own work back, so the log carries the attributes it provisioned")
        void readsItsOwnWorkBack() {
            // The read-back line is the script's own evidence that ordering is guaranteed and no card
            // can be silently dropped. The script does not merely print what it read: it compares
            // each attribute against the value the queue is required to carry and exits non-zero on
            // a mismatch, so this line records a comparison that already succeeded rather than a
            // value the reader is left to judge. Both attributes are reported on one line, so the
            // line is found once and both values are asserted against it rather than against the
            // whole output - which is what stops a run that reported the two values against the
            // wrong attributes from passing.
            final String attributeLine = firstRun.getStdout().lines()
                    .filter(line -> line.contains("queue " + QUEUE + " attributes verified:"))
                    .findFirst()
                    .orElse("");

            assertThat(attributeLine)
                    .as("the script must report the attributes it read back")
                    .isNotEmpty()
                    .contains("FifoQueue=true")
                    .contains("ContentBasedDeduplication=false");
        }
    }

    @Nested
    @DisplayName("the queue the hook actually created")
    final class TheQueueTheHookCreated {

        @Test
        @DisplayName("exists under the canonical name, keeping the legacy resource name and the required suffix")
        void existsUnderTheCanonicalName() {
            assertThat(queueUrl())
                    .as("the queue must be reachable under the canonical name")
                    .endsWith("/" + QUEUE);
            assertThat(QUEUE)
                    .as("the legacy transient-data resource name is retained, plus only the suffix "
                            + "the queue service requires of a fifo queue")
                    .isEqualTo("JOBS.fifo");
        }

        @Test
        @DisplayName("is first-in-first-out with content-based deduplication off, read through the SDK")
        void isFifoWithContentBasedDeduplicationOff() {
            // The single most consequential property of the whole bootstrap, verified from the JVM
            // rather than from the tool that set it. Append order is part of the legacy contract, and
            // content-based deduplication would silently collapse the three identical comment
            // delimiters and the two identical in-stream delimiters of a submitted job image - so
            // seventeen published cards would arrive as fourteen.
            final Map<QueueAttributeName, String> attributes;
            try (SqsClient client = sqsClient()) {
                attributes = client.getQueueAttributes(request -> request
                                .queueUrl(queueUrl())
                                .attributeNames(QueueAttributeName.FIFO_QUEUE,
                                        QueueAttributeName.CONTENT_BASED_DEDUPLICATION))
                        .attributes();
            }

            assertThat(attributes)
                    .as("both behavioural attributes must be reported, and both must be as the "
                            + "legacy queue definition requires")
                    .containsEntry(QueueAttributeName.FIFO_QUEUE, "true")
                    .containsEntry(QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "false");
        }

        @Test
        @DisplayName("is the only queue the hook created")
        void isTheOnlyQueueTheHookCreated() {
            // A second queue would mean the bootstrap provisions something no overlay binds, which is
            // the same silent mismatch as a missing one.
            final List<String> queues;
            try (SqsClient client = sqsClient()) {
                queues = client.listQueues().queueUrls();
            }

            assertThat(queues)
                    .as("the bootstrap must leave exactly one queue behind")
                    .hasSize(1)
                    .allSatisfy(url -> assertThat(url).endsWith("/" + QUEUE));
        }
    }

    @Nested
    @DisplayName("the bucket the hook actually created")
    final class TheBucketTheHookCreated {

        @Test
        @DisplayName("exists under the canonical name and is the only bucket")
        void existsUnderTheCanonicalNameAndIsTheOnlyBucket() {
            final List<String> buckets;
            try (S3Client client = s3Client()) {
                buckets = client.listBuckets().buckets().stream()
                        .map(bucket -> bucket.name())
                        .toList();
            }

            assertThat(buckets)
                    .as("the bootstrap must leave exactly the one staging bucket behind")
                    .containsExactly(BUCKET);
        }

        @Test
        @DisplayName("has object versioning enabled, which is the generation-data-group replacement")
        void hasObjectVersioningEnabled() {
            // Versioning is what carries the retained-history semantics of the legacy generation data
            // groups, and it is the only setting the bucket needs: no retention rule, no encryption
            // configuration and no object lock is applied, because none of them carries those
            // semantics and none was configured in the estate.
            final BucketVersioningStatus status;
            try (S3Client client = s3Client()) {
                status = client.getBucketVersioning(request -> request.bucket(BUCKET)).status();
            }

            assertThat(status)
                    .as("object versioning must be enabled on the staging bucket")
                    .isEqualTo(BucketVersioningStatus.ENABLED);
        }

        @Test
        @DisplayName("holds no object, so no key prefix was given a placeholder")
        void holdsNoObject() {
            // Key prefixes are not resources. A marker object would be returned to any reader listing
            // that prefix and would gain a new version on every rerun of the hook, so its absence is
            // deliberate and is verified rather than assumed.
            final int objectCount;
            try (S3Client client = s3Client()) {
                objectCount = client.listObjectsV2(request -> request.bucket(BUCKET))
                        .contents().size();
            }

            assertThat(objectCount)
                    .as("the bootstrap must create no object of any kind in the staging bucket")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("the topic the hook actually created")
    final class TheTopicTheHookCreated {

        @Test
        @DisplayName("exists under the canonical name and is the only topic")
        void existsUnderTheCanonicalNameAndIsTheOnlyTopic() {
            final List<String> topicArns;
            try (SnsClient client = snsClient()) {
                topicArns = client.listTopics().topics().stream()
                        .map(topic -> topic.topicArn())
                        .toList();
            }

            assertThat(topicArns)
                    .as("the bootstrap must leave exactly one topic behind, under the canonical name")
                    .hasSize(1);
            assertThat(topicArns.get(0)).endsWith(":" + TOPIC);
        }

        @Test
        @DisplayName("has no subscription, because the bridge is publish-only")
        void hasNoSubscription() {
            // The legacy definition was output-only: nothing in this module reads from the topic, so
            // no consumer is attached and none should appear.
            final int subscriptionCount;
            try (SnsClient client = snsClient()) {
                subscriptionCount = client.listSubscriptions().subscriptions().size();
            }

            assertThat(subscriptionCount)
                    .as("nothing may be subscribed to the notification topic")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("running the hook a second time")
    final class RunningTheHookASecondTime {

        @Test
        @Timeout(value = AbstractLocalStackIT.EXTERNAL_BOUNDARY_TIMEOUT_SECONDS,
            unit = TimeUnit.SECONDS)
        @DisplayName("succeeds, reports each resource already present, and changes nothing")
        void succeedsReportsAlreadyPresentAndChangesNothing()
                throws IOException, InterruptedException {
            // The property a ready hook has to have, demonstrated rather than argued: the emulator runs
            // it on every container start and raises if it exits non-zero, so "already there" must be
            // a normal outcome. State is captured before and after and compared, so a run that
            // recreated a resource - losing its versioning, or replacing the queue - would fail even
            // if it exited zero and printed the right words. "Left alone" here means not recreated,
            // not unexamined: the rerun still reads both resources back and still compares what it
            // finds, which is why it reports them as already present and verified in the same run.
            final String queueUrlBefore = queueUrl();
            final BucketVersioningStatus versioningBefore;
            try (S3Client client = s3Client()) {
                versioningBefore =
                        client.getBucketVersioning(request -> request.bucket(BUCKET)).status();
            }

            final Container.ExecResult secondRun =
                    LOCALSTACK.execInContainer("bash", HOOK_IN_CONTAINER);

            assertThat(secondRun.getExitCode())
                    .as("a rerun must succeed; stdout was:%n%s%nstderr was:%n%s",
                            secondRun.getStdout(), secondRun.getStderr())
                    .isZero();
            assertThat(secondRun.getStdout())
                    .as("both guarded resources must be reported as already present and "
                            + "re-verified rather than recreated")
                    .contains("queue " + QUEUE + " already present - verifying its attributes")
                    .contains("bucket " + BUCKET
                            + " already present - verifying its object versioning")
                    .as("and the rerun must still report completion")
                    .contains("AWS resource bootstrap complete: " + PROVISIONED_RESOURCE_COUNT
                            + " of " + PROVISIONED_RESOURCE_COUNT
                            + " resources verified (queue, bucket, topic)");
            assertThat(secondRun.getStdout())
                    .as("a rerun must create nothing")
                    .doesNotContain("created queue")
                    .doesNotContain("created bucket");

            assertThat(queueUrl())
                    .as("the queue must be the same queue, not a replacement")
                    .isEqualTo(queueUrlBefore);
            try (S3Client client = s3Client()) {
                assertThat(client.getBucketVersioning(request -> request.bucket(BUCKET)).status())
                        .as("re-applying versioning must be a no-op, not a change")
                        .isEqualTo(versioningBefore)
                        .isEqualTo(BucketVersioningStatus.ENABLED);
            }
        }

        @Test
        @Timeout(value = AbstractLocalStackIT.EXTERNAL_BOUNDARY_TIMEOUT_SECONDS,
            unit = TimeUnit.SECONDS)
        @DisplayName("leaves exactly one of each resource, never a duplicate")
        void leavesExactlyOneOfEachResource() throws IOException, InterruptedException {
            // Runs the hook again and then counts. A duplicate would not necessarily fail any single
            // resource assertion above - the canonical name would still resolve - so the counts are
            // asserted after a repeat rather than only after the first run.
            final Container.ExecResult rerun = LOCALSTACK.execInContainer("bash", HOOK_IN_CONTAINER);
            assertThat(rerun.getExitCode()).isZero();

            try (SqsClient sqs = sqsClient(); S3Client s3 = s3Client(); SnsClient sns = snsClient()) {
                assertThat(sqs.listQueues().queueUrls())
                        .as("one queue, after repeated provisioning")
                        .hasSize(1);
                assertThat(s3.listBuckets().buckets())
                        .as("one bucket, after repeated provisioning")
                        .hasSize(1);
                assertThat(sns.listTopics().topics())
                        .as("one topic, after repeated provisioning: creating a topic by name is "
                                + "itself idempotent, which is why it needs no guard")
                        .hasSize(1);
            }
        }
    }
}
