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
package com.carddemo.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

/**
 * Shared base for every integration test that needs a real message-queue service rather than a
 * stand-in for one.
 *
 * <h2>What this provides</h2>
 * One LocalStack Community emulator with the queue and notification services enabled, one queue
 * client built against it, and the three queue operations an interface test needs: create a
 * first-in-first-out queue, drain it in delivery order, and empty it between tests. Notification
 * tests build their synchronous client from the protected endpoint, region and throwaway credential
 * accessors below. Nothing about the migrated application is configured here; a subclass wires
 * whatever component it is exercising against {@link #sqsAsyncClient()}.
 *
 * <h2>Why an emulator rather than a stand-in</h2>
 * The queue is an external interface contract, and the acceptance criterion for that contract is
 * that the ordered sequence of fixed-width records is read back <em>out of a queue</em>. Asserting
 * against the return value of a builder, or against a mock's recorded arguments, proves that the
 * caller intended to publish something; it does not prove the service accepted it, preserved its
 * order, or preserved its bytes. Only a real queue proves that, which is why this base exists and
 * why the interface test that uses it declares no stand-in for the successful path.
 *
 * <h2>Why the emulator is started in a static initialiser</h2>
 * For the same lifecycle reason recorded on {@link AbstractPostgresIT}: the JUnit integration behind
 * {@code @Container} runs its teardown once per test <em>class</em>, so a static container inherited
 * by several classes is stopped after the first of them. Starting once here, outside that lifecycle,
 * lets every subclass share one emulator, and the Testcontainers resource reaper removes it when the
 * JVM exits. This class carries neither {@code @Testcontainers} nor {@code @Container} so no
 * subclass can reintroduce per-class teardown by inheritance.
 *
 * <h2>Why the image tag is pinned, and pinned to this tag specifically</h2>
 * Tags on the 2026 line perform licence activation at start-up and exit rather than serve traffic
 * without an authorisation token, so a floating tag would make every queue test fail for a reason
 * unrelated to the code under test. This is the newest Community tag that starts token-free, and it
 * is the same tag {@code carddemo-java/docker-compose.yml} pins, so a local run and a
 * continuous-integration run exercise the same emulator.
 *
 * <h2>Why this emulator is not the one the local stack already runs</h2>
 * The container started here listens on an ephemeral port of its own and is reaped with the test
 * JVM. It deliberately does not reuse a long-running emulator on a fixed port, because a shared
 * emulator would let one test observe another's messages and would let a test purge a queue another
 * process depends on.
 *
 * <h2>How a subclass that boots a Spring context reaches the same emulator</h2>
 * {@link #registerAwsProperties(DynamicPropertyRegistry)} publishes this emulator's endpoint, region
 * and throwaway credentials as {@code spring.cloud.aws.*} properties, so a framework-created client
 * inside such a context addresses the container this class started rather than anything a file
 * declared.
 *
 * <p>An AWS endpoint is not like a database address, and the difference is why this registration
 * matters more than it looks. An absent datasource URL fails closed. An absent AWS endpoint does
 * <em>not</em>: the SDK falls back to the region's real public endpoint, so a client with no endpoint
 * set addresses a real account on whatever credentials it happens to find, and does so silently.
 * Both copies of {@code application-test.yml} therefore also declare the emulator endpoint
 * explicitly, as a floor - this method raises the value from that fixed floor to the ephemeral
 * address of the container actually running, and neither path can reach a real account.</p>
 *
 * <p>The queue tests in this module build their own client against {@link #sqsAsyncClient()} rather
 * than resolving one from a context, because a directly-built client is the cheapest way to assert a
 * transport contract. This registration is what makes a context-booting test correct by construction
 * instead of by remembering to wire it, and it is verified from both ends:
 * {@code LocalStackPropertyRegistrationIT} calls it with a recording registry and asserts that each key
 * resolves to the running emulator's own value, and {@code ContextInheritsContainerAddressesIT} boots a
 * real context and asserts that the endpoint it observes is this emulator's ephemeral one rather than
 * the fixed floor the profile documents declare - which is what demonstrates that a dynamic property
 * source really does outrank a property file. See {@code docs/decision-log.md} DL-104.</p>
 *
 * <p>Provenance: this support type has no legacy antecedent - the legacy estate carries no test
 * harness of any kind. It exists to serve tests of the queue that replaces
 * {@code TDQUEUE(JOBS)} as defined in {@code app/csd/CARDDEMO.CSD}, taken from checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
public abstract class AbstractLocalStackIT {

    /**
     * The pinned emulator image. Held as a constant so a subclass can assert against it rather than
     * restating the tag.
     */
    protected static final String LOCALSTACK_IMAGE = "localstack/localstack:4.14.0";

    /** Suffix the queue service requires on the name of a first-in-first-out queue. */
    protected static final String FIFO_SUFFIX = ".fifo";

    /**
     * The wildcard that asks the receive operation for every user message attribute.
     *
     * <p>A literal the service defines, and the only value that returns attributes a test did not name
     * in advance - which is what an assertion about the <em>set</em> of published attributes needs.
     */
    private static final String ALL_MESSAGE_ATTRIBUTES = "All";

    /** Largest batch the receive operation will return in one call. */
    private static final int RECEIVE_BATCH_SIZE = 10;

    /** Seconds a receive call waits for a message before returning empty. */
    private static final int RECEIVE_WAIT_SECONDS = 1;

    /** Upper bound on how long a drain will keep asking before giving up. */
    private static final Duration DRAIN_DEADLINE = Duration.ofSeconds(30);

    /** The one emulator every subclass shares. */
    private static final LocalStackContainer LOCALSTACK = startEmulator();

    /** The one client every subclass shares, bound to the emulator's ephemeral endpoint. */
    private static final SqsAsyncClient SQS_ASYNC_CLIENT = buildSqsAsyncClient();

    /** Synchronous queue client used by cross-service notification tests. */
    private static final SqsClient SQS_CLIENT = buildSqsClient();

    /** Object-store client used by durable batch-artifact integration tests. */
    private static final S3Client S3_CLIENT = buildS3Client();

    /** Notification client used by terminal job-event integration tests. */
    private static final SnsClient SNS_CLIENT = buildSnsClient();

    /**
     * Restricts construction to subclasses. A test class extends this type; nothing instantiates it
     * directly.
     */
    protected AbstractLocalStackIT() {
        // Intentionally empty: this base holds no per-instance state.
    }

    /**
     * Starts the emulator with the queue and notification services enabled.
     *
     * @return the started emulator
     */
    private static LocalStackContainer startEmulator() {
        final LocalStackContainer container =
                new LocalStackContainer(DockerImageName.parse(LOCALSTACK_IMAGE))
                        .withServices(LocalStackContainer.Service.S3,
                                LocalStackContainer.Service.SQS,
                                LocalStackContainer.Service.SNS);
        container.start();
        return container;
    }

    /**
     * Builds the client the tests publish and receive through.
     *
     * @return a client bound to the running emulator
     */
    private static SqsAsyncClient buildSqsAsyncClient() {
        return SqsAsyncClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
    }

    /** Builds the synchronous queue client used where a second service delivers into a queue. */
    private static SqsClient buildSqsClient() {
        return SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
    }

    /** Builds the object-store client used to verify uploaded bytes and retention. */
    private static S3Client buildS3Client() {
        return S3Client.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .forcePathStyle(true)
                .build();
    }

    /** Builds the notification client used to verify terminal job events. */
    private static SnsClient buildSnsClient() {
        return SnsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
    }

    /**
     * Publishes the running emulator's endpoint, region and credentials into the environment of any
     * subclass that boots a Spring context.
     *
     * <p>The global endpoint is registered and then restated for each of the three services. That
     * redundancy is the point: it means no client can fall through to a real AWS endpoint if the
     * global setting is ever dropped, or if one client's auto-configuration stops consulting it. The
     * failure being guarded against is silent success against the wrong target, not an error.</p>
     *
     * <p>The credentials published here are the emulator's own throwaway pair. They authenticate
     * nothing: the emulator accepts any non-empty pair and verifies neither. They are registered so
     * that a context never reaches the default credentials chain, which on a developer's machine or a
     * build agent could find real ones.</p>
     *
     * <p><strong>The module's own endpoint key is raised alongside the integration's four.</strong>
     * {@code carddemo.aws.endpoint-override} is the key {@code com.carddemo.config.AwsProperties} binds
     * and the form a consumer of that type reads, and both copies of the test profile floor it at the
     * emulator's fixed port. Raising only the {@code spring.cloud.aws} settings would leave a context
     * in which the clients address the container this JVM started while the settings type still reports
     * the floor - two answers to one question, and the wrong one belonging to whichever component asked
     * the type rather than the client.</p>
     *
     * @param registry the registry the Spring TestContext Framework supplies; must not be null
     */
    @DynamicPropertySource
    protected static void registerAwsProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.aws.region.static", AbstractLocalStackIT::emulatorRegion);
        registry.add("spring.cloud.aws.credentials.access-key", AbstractLocalStackIT::emulatorAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", AbstractLocalStackIT::emulatorSecretKey);
        registry.add("spring.cloud.aws.endpoint", AbstractLocalStackIT::emulatorEndpoint);
        registry.add("spring.cloud.aws.s3.endpoint", AbstractLocalStackIT::emulatorEndpoint);
        registry.add("spring.cloud.aws.sqs.endpoint", AbstractLocalStackIT::emulatorEndpoint);
        registry.add("spring.cloud.aws.sns.endpoint", AbstractLocalStackIT::emulatorEndpoint);
        registry.add("carddemo.aws.endpoint-override", AbstractLocalStackIT::emulatorEndpoint);
    }

    /**
     * Returns the shared client bound to the running emulator.
     *
     * @return the queue client
     */
    protected static SqsAsyncClient sqsAsyncClient() {
        return SQS_ASYNC_CLIENT;
    }

    /** @return the shared synchronous queue client */
    protected static SqsClient sqsClient() {
        return SQS_CLIENT;
    }

    /** @return the shared object-store client */
    protected static S3Client s3Client() {
        return S3_CLIENT;
    }

    /** @return the shared notification client */
    protected static SnsClient snsClient() {
        return SNS_CLIENT;
    }

    /**
     * Returns the emulator's endpoint, including its ephemeral port.
     *
     * @return the endpoint as text
     */
    protected static String emulatorEndpoint() {
        return LOCALSTACK.getEndpoint().toString();
    }

    /**
     * Returns the region the emulator reports.
     *
     * @return the region identifier
     */
    protected static String emulatorRegion() {
        return LOCALSTACK.getRegion();
    }

    /**
     * Returns the emulator's throwaway access key.
     *
     * <p>This is not a credential of anything: the emulator accepts any non-empty pair and verifies
     * neither value, and the pair never leaves the test JVM.</p>
     *
     * @return the access key the emulator reports
     */
    protected static String emulatorAccessKey() {
        return LOCALSTACK.getAccessKey();
    }

    /**
     * Returns the emulator's throwaway secret key.
     *
     * <p>See {@link #emulatorAccessKey()}: this authenticates nothing and never leaves the test
     * JVM.</p>
     *
     * @return the secret key the emulator reports
     */
    protected static String emulatorSecretKey() {
        return LOCALSTACK.getSecretKey();
    }

    /**
     * Creates a first-in-first-out queue, or returns the existing one if the name is already taken.
     *
     * <p>Content-based deduplication is switched off deliberately: the publisher supplies an explicit
     * deduplication identifier per message, and enabling the content-based alternative would let two
     * cards that happen to carry identical bytes collapse into one message.</p>
     *
     * @param queueName the queue name, which must end in the first-in-first-out suffix
     * @return the queue's URL
     */
    protected static String createFifoQueue(final String queueName) {
        final Map<QueueAttributeName, String> attributes = new LinkedHashMap<>();
        attributes.put(QueueAttributeName.FIFO_QUEUE, "true");
        attributes.put(QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "false");
        return SQS_ASYNC_CLIENT.createQueue(CreateQueueRequest.builder()
                        .queueName(queueName)
                        .attributes(attributes)
                        .build())
                .join()
                .queueUrl();
    }

    /**
     * Reads messages back in delivery order, deleting each as it is read so that the next messages of
     * the same group become visible.
     *
     * <p>Reading stops as soon as the expected count is reached, or as soon as the service reports
     * nothing further, or when the deadline expires - whichever happens first. Stopping on an empty
     * response rather than only on the expected count is what lets a caller assert that <em>fewer</em>
     * messages than requested were published.</p>
     *
     * <p>Both every system attribute and every user message attribute are requested, because the
     * service does not return either kind unless it is asked for them by name. Without the second
     * request a test asserting on a published message attribute would observe an empty map and could
     * not tell an attribute that was never set from one that was simply not fetched.</p>
     *
     * @param queueUrl              the queue to read
     * @param expectedMessageCount  how many messages to stop after; must not be negative
     * @return the messages read, in the order the service delivered them
     */
    protected static List<Message> drainQueue(final String queueUrl, final int expectedMessageCount) {
        if (expectedMessageCount < 0) {
            throw new IllegalArgumentException(
                    "expectedMessageCount must not be negative but was " + expectedMessageCount);
        }
        final List<Message> drained = new ArrayList<>(expectedMessageCount);
        final Instant deadline = Instant.now().plus(DRAIN_DEADLINE);
        while (drained.size() < expectedMessageCount && Instant.now().isBefore(deadline)) {
            final ReceiveMessageResponse response = SQS_ASYNC_CLIENT.receiveMessage(
                            ReceiveMessageRequest.builder()
                                    .queueUrl(queueUrl)
                                    .maxNumberOfMessages(RECEIVE_BATCH_SIZE)
                                    .waitTimeSeconds(RECEIVE_WAIT_SECONDS)
                                    .messageSystemAttributeNames(MessageSystemAttributeName.ALL)
                                    .messageAttributeNames(ALL_MESSAGE_ATTRIBUTES)
                                    .build())
                    .join();
            if (!response.hasMessages() || response.messages().isEmpty()) {
                break;
            }
            for (final Message message : response.messages()) {
                drained.add(message);
                SQS_ASYNC_CLIENT.deleteMessage(DeleteMessageRequest.builder()
                                .queueUrl(queueUrl)
                                .receiptHandle(message.receiptHandle())
                                .build())
                        .join();
            }
        }
        return List.copyOf(drained);
    }

    /**
     * Removes every message from a queue so the next test starts from an empty one.
     *
     * @param queueUrl the queue to empty
     */
    protected static void purgeQueue(final String queueUrl) {
        SQS_ASYNC_CLIENT.purgeQueue(PurgeQueueRequest.builder().queueUrl(queueUrl).build()).join();
    }

    /** Creates the bucket unless another test already created it. */
    protected static void createBucketIfAbsent(final String bucket) {
        try {
            S3_CLIENT.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (final BucketAlreadyExistsException | BucketAlreadyOwnedByYouException alreadyThere) {
            // The requested postcondition already holds.
        }
    }

    /** Stores one exact byte image. */
    protected static void putObject(final String bucket, final String key, final byte[] content) {
        S3_CLIENT.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(content));
    }

    /** Removes one object; S3 treats an absent key as success. */
    protected static void deleteObject(final String bucket, final String key) {
        S3_CLIENT.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    /** Reads one object back as exact bytes. */
    protected static byte[] objectBytes(final String bucket, final String key) {
        try (ResponseInputStream<GetObjectResponse> body = S3_CLIENT.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            return body.readAllBytes();
        } catch (final IOException failure) {
            throw new UncheckedIOException("the staged object " + key + " could not be read", failure);
        }
    }

    /** Reports whether one object exists without reading its body. */
    protected static boolean objectExists(final String bucket, final String key) {
        try {
            S3_CLIENT.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (final NoSuchKeyException absent) {
            return false;
        }
    }
}
