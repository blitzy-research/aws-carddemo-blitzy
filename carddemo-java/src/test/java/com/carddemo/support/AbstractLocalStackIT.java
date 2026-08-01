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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
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
 * One LocalStack Community emulator with the queue service enabled, one client built against it, and
 * the three queue operations an interface test needs: create a first-in-first-out queue, drain it in
 * delivery order, and empty it between tests. Nothing about the migrated application is configured
 * here; a subclass wires whatever component it is exercising against {@link #sqsAsyncClient()}.
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

    /**
     * Restricts construction to subclasses. A test class extends this type; nothing instantiates it
     * directly.
     */
    protected AbstractLocalStackIT() {
        // Intentionally empty: this base holds no per-instance state.
    }

    /**
     * Starts the emulator with the queue service enabled.
     *
     * @return the started emulator
     */
    private static LocalStackContainer startEmulator() {
        final LocalStackContainer container =
                new LocalStackContainer(DockerImageName.parse(LOCALSTACK_IMAGE))
                        .withServices(LocalStackContainer.Service.SQS);
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

    /**
     * Returns the shared client bound to the running emulator.
     *
     * @return the queue client
     */
    protected static SqsAsyncClient sqsAsyncClient() {
        return SQS_ASYNC_CLIENT;
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
}
