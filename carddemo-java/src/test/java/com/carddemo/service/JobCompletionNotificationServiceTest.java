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
package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.awspring.cloud.sns.core.SnsNotification;
import io.awspring.cloud.sns.core.SnsOperations;
import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;

import com.carddemo.util.SanitisedObservation;

/**
 * Unit contract for the parameter-free SNS job-completion producer.
 */
@DisplayName("JobCompletionNotificationService - bounded SNS completion fan-out")
class JobCompletionNotificationServiceTest {

    private static final String TOPIC = "carddemo-job-notifications";

    private static final String JOB = "postTransactionJob";

    private static final Long INSTANCE_ID = 11L;

    private static final Long EXECUTION_ID = 22L;

    /**
     * A zone deliberately not UTC and not the build machine's, so a conversion that ignored the zone or
     * substituted the module's UTC clock would produce a different image and be caught.
     */
    private static final ZoneId RECORDING_ZONE = ZoneId.of("America/New_York");

    /** The framework's wall-clock start reading, as {@code LocalDateTime.now()} would have left it. */
    private static final LocalDateTime STARTED_WALL_CLOCK = LocalDateTime.of(2022, 7, 19, 10, 11, 12);

    /** The framework's wall-clock end reading. */
    private static final LocalDateTime ENDED_WALL_CLOCK = LocalDateTime.of(2022, 7, 19, 10, 12, 13);

    private static final Instant STARTED = STARTED_WALL_CLOCK.atZone(RECORDING_ZONE).toInstant();

    private static final Instant ENDED = ENDED_WALL_CLOCK.atZone(RECORDING_ZONE).toInstant();

    private final List<Observation.Context> observed = new ArrayList<>();

    private SnsOperations operations;

    private JobCompletionNotificationService service;

    /**
     * The very registry the service holds, kept so a test can ask what is current on a worker thread.
     *
     * <p>Needed because "the context was carried" is only observable from inside the work: it is a
     * question about what the executing thread sees, not about what the recorder collected afterwards.
     */
    private ObservationRegistry registryUnderTest;

    /** The very registry lost notifications are counted on, so a test can read the authoritative count. */
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void createService() {
        this.operations = mock(SnsOperations.class);
        final ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public void onStop(final Observation.Context context) {
                observed.add(context);
            }

            @Override
            public boolean supportsContext(final Observation.Context context) {
                return true;
            }
        });
        this.registryUnderTest = registry;
        this.meterRegistry = new SimpleMeterRegistry();
        this.service = new JobCompletionNotificationService(this.operations, TOPIC, registry,
                this.meterRegistry);
    }

    @Test
    @DisplayName("publishes the exact versioned payload and no job parameter")
    void publishesTheExactVersionedPayload() {
        final JobCompletionEvent event = completedEvent();
        final AtomicReference<String> destination = new AtomicReference<>();
        final AtomicReference<SnsNotification<?>> notification = new AtomicReference<>();
        doAnswer(invocation -> {
            destination.set(invocation.getArgument(0, String.class));
            final Object sent = invocation.getArgument(1);
            if (!(sent instanceof SnsNotification<?> snsNotification)) {
                throw new AssertionError("expected an SnsNotification but received " + sent);
            }
            notification.set(snsNotification);
            return null;
        }).when(this.operations).sendNotification(anyString(), any());

        assertThat(this.service.publishCompletion(event)).isTrue();

        assertThat(destination.get()).isEqualTo(TOPIC);
        assertThat(notification.get()).isNotNull();
        assertThat(notification.get().getSubject())
                .isEqualTo(JobCompletionNotificationService.SUBJECT);
        assertThat(notification.get().getPayload())
                .isEqualTo("{\"schemaVersion\":2,"
                        + "\"eventType\":\"carddemo.batch.job-completion\","
                        + "\"jobName\":\"postTransactionJob\","
                        + "\"jobInstanceId\":11,"
                        + "\"jobExecutionId\":22,"
                        + "\"status\":\"COMPLETED\","
                        + "\"exitCode\":\"COMPLETED\","
                        + "\"stepsExecuted\":3,"
                        // 10:11:12 in America/New_York on 19 July is 14:11:12 UTC. The offset is
                        // present in the image rather than implied by it, which is the whole change:
                        // a subscriber in another zone reads the same instant.
                        + "\"startedAt\":\"2022-07-19T14:11:12Z\","
                        + "\"endedAt\":\"2022-07-19T14:12:13Z\"}");
        assertThat(notification.get().getPayload().toString())
                .doesNotContain("jobParameters", "parameter", "executionContext", "failureException",
                        "exitDescription");
    }

    @Test
    @DisplayName("records fixed low-cardinality tags and boundary detail only on the span")
    void recordsTheObservationContract() {
        assertThat(this.service.publishCompletion(completedEvent())).isTrue();

        assertThat(this.observed).hasSize(1);
        final Observation.Context context = this.observed.getFirst();
        assertThat(context.getName())
                .isEqualTo(JobCompletionNotificationService.OBSERVATION_NAME);
        assertThat(lowTag(context, JobCompletionNotificationService.TAG_SYSTEM))
                .isEqualTo(JobCompletionNotificationService.SYSTEM_SNS);
        assertThat(lowTag(context, JobCompletionNotificationService.TAG_OPERATION))
                .isEqualTo(JobCompletionNotificationService.OPERATION_PUBLISH);
        assertThat(lowTag(context, JobCompletionNotificationService.TAG_EVENT_TYPE))
                .isEqualTo(JobCompletionNotificationService.EVENT_TYPE);
        assertThat(highTag(context, JobCompletionNotificationService.TAG_TOPIC)).isEqualTo(TOPIC);
        assertThat(highTag(context, JobCompletionNotificationService.TAG_JOB)).isEqualTo(JOB);
        assertThat(highTag(context, JobCompletionNotificationService.TAG_EXECUTION)).isEqualTo("22");
        assertThat(context.getError()).isNull();
    }

    @Test
    @DisplayName("records a sanitised classification of an SNS refusal and returns false without throwing")
    void absorbsAndObservesAPublishFailure() {
        final IllegalStateException refused =
                new IllegalStateException("topic refused arn:aws:sns:eu-west-1:123456789012:secret");
        doThrow(refused).when(this.operations)
                .sendNotification(org.mockito.ArgumentMatchers.eq(TOPIC),
                        org.mockito.ArgumentMatchers.any());

        assertThat(this.service.publishCompletion(completedEvent())).isFalse();

        assertThat(this.observed).hasSize(1);
        final Throwable recorded = this.observed.getFirst().getError();
        assertThat(recorded)
                .as("the refusal is still a failure on the span rather than a gap in the trace")
                .isNotNull()
                .as("but the provider's own failure is not what is recorded: the adjacent log publishes a "
                        + "bounded type chain, and the span may not undo that by publishing the object")
                .isNotSameAs(refused)
                .isInstanceOf(SanitisedObservation.SanitisedBoundaryFailure.class);
        assertThat(recorded.getMessage())
                .doesNotContain("123456789012")
                .contains(SanitisedObservation.FAILURE_CHAIN_LABEL + "IllegalStateException");
        assertThat(recorded.getCause()).isNull();
    }

    @Test
    @DisplayName("bounds and sanitizes free-form framework text before JSON or telemetry")
    void boundsAndSanitizesFrameworkText() {
        final String hostileJob = "job\r\n" + "x".repeat(180);
        final JobCompletionEvent event = new JobCompletionEvent(
                hostileJob,
                null,
                null,
                null,
                "FAILED\naccount=00000000001",
                0,
                null,
                null);

        final String payload = JobCompletionNotificationService.payload(event);

        assertThat(payload.getBytes(StandardCharsets.US_ASCII).length)
                .isLessThanOrEqualTo(JobCompletionNotificationService.MAX_PAYLOAD_BYTES);
        assertThat(payload)
                .doesNotContain("\r", "\n", "00000000001")
                .contains("\"jobName\":\"job__")
                .contains("\"jobInstanceId\":null")
                .contains("\"jobExecutionId\":null")
                .contains("\"status\":\"UNKNOWN\"")
                .contains("\"exitCode\":\"OTHER\"");
    }

    @Test
    @DisplayName("the event refuses a negative step count")
    void refusesANegativeStepCount() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JobCompletionEvent(
                        JOB, INSTANCE_ID, EXECUTION_ID, BatchStatus.FAILED, "FAILED", -1,
                        STARTED, ENDED))
                .withMessageContaining("stepsExecuted");
    }

    @Test
    @DisplayName("construction refuses an absent collaborator or unusable topic")
    void validatesConstruction() {
        assertThatNullPointerException()
                .isThrownBy(() -> new JobCompletionNotificationService(
                        null, TOPIC, ObservationRegistry.NOOP, new SimpleMeterRegistry()))
                .withMessageContaining("snsOperations");
        assertThatNullPointerException()
                .isThrownBy(() -> new JobCompletionNotificationService(
                        this.operations, null, ObservationRegistry.NOOP, new SimpleMeterRegistry()))
                .withMessageContaining("topic");
        assertThatNullPointerException()
                .isThrownBy(() -> new JobCompletionNotificationService(
                        this.operations, TOPIC, null, new SimpleMeterRegistry()))
                .withMessageContaining("observationRegistry");
        assertThatNullPointerException()
                .isThrownBy(() -> new JobCompletionNotificationService(
                        this.operations, TOPIC, ObservationRegistry.NOOP, null))
                .withMessageContaining("meterRegistry");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JobCompletionNotificationService(
                        this.operations, " ", ObservationRegistry.NOOP, new SimpleMeterRegistry()))
                .withMessageContaining(JobCompletionNotificationService.TOPIC_PROPERTY);
        assertThatThrownBy(() -> new JobCompletionNotificationService(
                this.operations, "topic\nforged", ObservationRegistry.NOOP, new SimpleMeterRegistry()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code point");
    }

    private static JobCompletionEvent completedEvent() {
        return new JobCompletionEvent(
                JOB,
                INSTANCE_ID,
                EXECUTION_ID,
                BatchStatus.COMPLETED,
                "COMPLETED",
                3,
                STARTED,
                ENDED);
    }

    @Nested
    @DisplayName("The listener path is non-blocking, so an optional channel cannot delay a finished job")
    class TheListenerPathDoesNotBlock {

        /**
         * Far longer than any reasonable finalization pause, so a blocking handoff is unmistakable.
         *
         * <p>The assertions below compare against a fraction of this rather than against an absolute
         * figure, so the test states "returned without waiting for the topic" rather than asserting a
         * latency the machine has to meet.
         */
        private static final long TOPIC_STALL_MILLIS = 5_000L;

        @AfterEach
        void closeNotifier() {
            service.close();
        }

        @Test
        @DisplayName("a topic that stalls does not hold the listener callback, which is the defect: the "
                + "publish used to run inside the batch afterJob callback")
        void aStalledTopicDoesNotHoldTheCallback() throws Exception {
            final CountDownLatch publishStarted = new CountDownLatch(1);
            final CountDownLatch releaseTopic = new CountDownLatch(1);
            doAnswer(invocation -> {
                publishStarted.countDown();
                releaseTopic.await(TOPIC_STALL_MILLIS, TimeUnit.MILLISECONDS);
                return null;
            }).when(operations).sendNotification(anyString(), any());

            final long startedAt = System.nanoTime();
            service.onApplicationEvent(completedEvent());
            final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertThat(publishStarted.await(TOPIC_STALL_MILLIS, TimeUnit.MILLISECONDS))
                    .as("the publish must still happen, just not on the caller's thread")
                    .isTrue();
            assertThat(elapsedMillis)
                    .as("the listener returned in %d ms while the topic was still stalled", elapsedMillis)
                    .isLessThan(TOPIC_STALL_MILLIS / 2);
            releaseTopic.countDown();
        }

        @Test
        @DisplayName("the hand-off carries the caller's observation, so the publish is a child rather "
                + "than a detached root")
        void theHandOffCarriesTheCallersObservation() throws Exception {
            // The gap this closes: the publish is fully observed, but it happens on a worker thread that
            // a plain executor hands over with nothing current - so the observation became a trace of one
            // span with no link to anything. The job's own span cannot be that parent, because the
            // framework stops it before the terminal callbacks; what is carried is the context enclosing
            // the launch, which is the strongest link still available.
            final CompletableFuture<Observation> onTheWorker = new CompletableFuture<>();
            doAnswer(invocation -> {
                onTheWorker.complete(registryUnderTest.getCurrentObservation());
                return null;
            }).when(operations).sendNotification(anyString(), any());
            final Observation launch =
                    Observation.createNotStarted("launch", registryUnderTest).start();

            final Observation.Scope scope = launch.openScope();
            try {
                service.onApplicationEvent(completedEvent());
            } finally {
                scope.close();
            }

            final Observation current = onTheWorker.get(5, TimeUnit.SECONDS);
            assertThat(current)
                    .as("something was current on the worker; a null here is the detached-root state")
                    .isNotNull();
            assertThat(current.getContext().getName())
                    .as("and the observation the publish opened is a descendant of the caller's, which is "
                            + "what makes the SNS span appear under the launch that caused it")
                    .isEqualTo(JobCompletionNotificationService.OBSERVATION_NAME);
            assertThat(current.getContext().getParentObservation())
                    .as("its parent is the caller's own observation, carried across the hand-off")
                    .isSameAs(launch);
            launch.stop();
        }

        @Test
        @DisplayName("a caller with no observation of its own still delivers, because an unobserved "
                + "caller is an ordinary case")
        void anUnobservedCallerStillDelivers() throws Exception {
            final CountDownLatch delivered = new CountDownLatch(1);
            doAnswer(invocation -> {
                delivered.countDown();
                return null;
            }).when(operations).sendNotification(anyString(), any());

            service.onApplicationEvent(completedEvent());

            assertThat(delivered.await(5L, TimeUnit.SECONDS)).isTrue();
        }

        @Test
        @DisplayName("a topic that refuses does not escape the listener, so a completed job's verdict "
                + "cannot be changed by operational fan-out")
        void aRefusingTopicDoesNotEscape() {
            doThrow(new IllegalStateException("topic refused"))
                    .when(operations).sendNotification(anyString(), any());

            assertThatCode(() -> service.onApplicationEvent(completedEvent()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the snapshot really is delivered off-thread, on the named daemon worker rather "
                + "than on the caller's thread")
        void theSnapshotIsDeliveredOnADaemonWorker() throws Exception {
            final CompletableFuture<Thread> publishingThread = new CompletableFuture<>();
            doAnswer(invocation -> {
                publishingThread.complete(Thread.currentThread());
                return null;
            }).when(operations).sendNotification(anyString(), any());

            service.onApplicationEvent(completedEvent());

            final Thread worker = publishingThread.get(10, TimeUnit.SECONDS);
            assertThat(worker).isNotSameAs(Thread.currentThread());
            assertThat(worker.isDaemon())
                    .as("a stuck publish must not keep the JVM alive at shutdown")
                    .isTrue();
            assertThat(worker.getName()).isEqualTo("job-completion-notifier");
        }

        @Test
        @DisplayName("the pending queue is bounded, so a topic that is slow for longer than the batch "
                + "tier produces work sheds notifications instead of accumulating them")
        void thePendingQueueIsBounded() throws Exception {
            final CountDownLatch releaseTopic = new CountDownLatch(1);
            doAnswer(invocation -> {
                releaseTopic.await(TOPIC_STALL_MILLIS, TimeUnit.MILLISECONDS);
                return null;
            }).when(operations).sendNotification(anyString(), any());

            final int offered =
                    JobCompletionNotificationService.PENDING_NOTIFICATION_CAPACITY * 3;
            for (int notification = 0; notification < offered; notification++) {
                service.onApplicationEvent(completedEvent());
            }

            assertThatCode(() -> service.onApplicationEvent(completedEvent()))
                    .as("shedding must not surface as a failure to the caller")
                    .doesNotThrowAnyException();
            releaseTopic.countDown();
            assertThat(JobCompletionNotificationService.PENDING_NOTIFICATION_CAPACITY)
                    .as("an unbounded queue would trade a delay for a leak")
                    .isPositive();
        }

        @Test
        @DisplayName("the direct call stays synchronous and keeps returning its outcome, because a "
                + "caller that invokes it has asked for the publish rather than the fan-out")
        void theDirectCallStaysSynchronous() {
            final CompletableFuture<Thread> publishingThread = new CompletableFuture<>();
            doAnswer(invocation -> {
                publishingThread.complete(Thread.currentThread());
                return null;
            }).when(operations).sendNotification(anyString(), any());

            final boolean accepted = service.publishCompletion(completedEvent());

            assertThat(accepted).isTrue();
            assertThat(publishingThread.getNow(null))
                    .as("the direct call must publish on the caller's own thread")
                    .isSameAs(Thread.currentThread());
        }

        @Test
        @DisplayName("a null snapshot is refused by name on the listener path too")
        void aNullSnapshotIsRefusedOnTheListenerPath() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.onApplicationEvent(null))
                    .withMessageContaining("event");
        }

        @Test
        @DisplayName("after close the notifier accepts nothing and still does not throw at its caller")
        void afterCloseNothingIsAcceptedAndNothingEscapes() {
            service.close();

            assertThatCode(() -> service.onApplicationEvent(completedEvent()))
                    .doesNotThrowAnyException();
            verifyNoInteractions(operations);
        }

        @Test
        @DisplayName("close is idempotent, because the container may call it after a manual close")
        void closeIsIdempotent() {
            assertThatCode(() -> {
                service.close();
                service.close();
            }).doesNotThrowAnyException();
        }
    }

    /**
     * Every notification this channel lost, counted from inside the losses themselves.
     *
     * <h2>Why a counter is the assertion rather than a log line</h2>
     *
     * <p>A channel allowed to drop messages owes an exact number, and the number it used to produce came
     * from a queue-depth sample read on the producing thread <em>before</em> the hand-off. That is a
     * different instant from the rejection and disagrees with it in both directions under concurrency, so
     * the number was not a count of losses at all - it was a guess about them, and it was wrong in the
     * two situations that matter: nothing lost while the worker drained, and something lost while another
     * producer filled the gap.
     *
     * <p>These tests therefore pin the boundary exactly. Filling the queue to capacity must lose
     * <em>nothing</em>, and the very next offer must lose exactly one. And a shed after close - the case
     * the pool's own policy answered by doing nothing whatsoever, with {@code execute} raising nothing
     * either - must be counted and named like any other.
     */
    @Nested
    @DisplayName("Every lost notification is counted, by the code that loses it")
    class LostNotificationsAreCounted {

        /** How long a test waits on a latch before treating the wait as a failure. */
        private static final long WAIT_MILLIS = 5_000L;

        /** Rejections deliberately provoked once the queue is full. */
        private static final int PROVOKED_REJECTIONS = 5;

        @Test
        @DisplayName("filling the queue to capacity loses nothing, and the next offer loses exactly one, "
                + "so the boundary is the rejection rather than a sample taken near it")
        void theBoundaryIsTheRejectionItself() throws Exception {
            final CountDownLatch publishing = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            doAnswer(invocation -> {
                publishing.countDown();
                release.await(WAIT_MILLIS, TimeUnit.MILLISECONDS);
                return null;
            }).when(operations).sendNotification(anyString(), any());

            // One snapshot occupies the single worker, which is then blocked, so nothing drains and the
            // accounting below is exact rather than probabilistic.
            service.onApplicationEvent(completedEvent());
            assertThat(publishing.await(WAIT_MILLIS, TimeUnit.MILLISECONDS))
                    .as("the worker must be holding the topic before the queue is filled")
                    .isTrue();

            for (int queued = 0;
                    queued < JobCompletionNotificationService.PENDING_NOTIFICATION_CAPACITY;
                    queued++) {
                service.onApplicationEvent(completedEvent());
            }
            assertThat(shed(JobCompletionNotificationService.REASON_QUEUE_FULL))
                    .as("a queue filled exactly to capacity has lost nothing; the discarded pre-hand-off "
                            + "sample would already be claiming otherwise")
                    .isZero();

            for (int rejected = 0; rejected < PROVOKED_REJECTIONS; rejected++) {
                service.onApplicationEvent(completedEvent());
            }

            assertThat(shed(JobCompletionNotificationService.REASON_QUEUE_FULL))
                    .as("one displaced snapshot per rejection, and not one more")
                    .isEqualTo(PROVOKED_REJECTIONS);
            release.countDown();
        }

        @Test
        @DisplayName("a shed after close is counted and named, which the pool's own policy answered by "
                + "doing nothing at all while execute raised nothing either")
        void aShedAfterCloseIsCounted() {
            service.close();

            assertThatCode(() -> service.onApplicationEvent(completedEvent()))
                    .as("a closed channel still must not surface anything to a finished job")
                    .doesNotThrowAnyException();

            assertThat(shed(JobCompletionNotificationService.REASON_NOTIFIER_CLOSED))
                    .as("this is the loss that previously left no trace anywhere: no meter, no log, and "
                            + "no exception for the caller to catch")
                    .isEqualTo(1.0d);
            verifyNoInteractions(operations);
        }

        @Test
        @DisplayName("notifications still pending when the grace period expires are counted as abandoned")
        void abandonedWorkIsCountedWhenTheGracePeriodExpires() throws Exception {
            final CountDownLatch publishing = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            doAnswer(invocation -> {
                publishing.countDown();
                release.await(WAIT_MILLIS, TimeUnit.MILLISECONDS);
                return null;
            }).when(operations).sendNotification(anyString(), any());
            service.onApplicationEvent(completedEvent());
            assertThat(publishing.await(WAIT_MILLIS, TimeUnit.MILLISECONDS)).isTrue();
            service.onApplicationEvent(completedEvent());
            service.onApplicationEvent(completedEvent());

            service.close();

            assertThat(shed(JobCompletionNotificationService.REASON_SHUTDOWN))
                    .as("two snapshots were still queued behind a stalled topic when the wait gave up")
                    .isEqualTo(2.0d);
            release.countDown();
        }

        @Test
        @DisplayName("an interrupted shutdown counts what it abandons too, which is the path most likely "
                + "to be holding a backlog and was the one reporting nothing")
        void abandonedWorkIsCountedWhenTheShutdownWaitIsInterrupted() throws Exception {
            final CountDownLatch publishing = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            doAnswer(invocation -> {
                publishing.countDown();
                release.await(WAIT_MILLIS, TimeUnit.MILLISECONDS);
                return null;
            }).when(operations).sendNotification(anyString(), any());
            service.onApplicationEvent(completedEvent());
            assertThat(publishing.await(WAIT_MILLIS, TimeUnit.MILLISECONDS)).isTrue();
            service.onApplicationEvent(completedEvent());

            // Interrupting the closing thread is exactly what a container tear-down under pressure does,
            // and it makes awaitTermination raise on entry rather than wait out its grace period.
            Thread.currentThread().interrupt();
            service.close();
            final boolean interruptWasPreserved = Thread.interrupted();

            assertThat(interruptWasPreserved)
                    .as("the interrupt is re-asserted for the caller rather than swallowed")
                    .isTrue();
            assertThat(shed(JobCompletionNotificationService.REASON_SHUTDOWN))
                    .as("the queued snapshot was abandoned, and an abandoned notification is a lost one "
                            + "whichever path abandoned it")
                    .isEqualTo(1.0d);
            release.countDown();
        }

        @Test
        @DisplayName("under many producers saturating the channel at once, every offered notification is "
                + "either delivered or counted lost - which is the invariant a sampled count could not "
                + "state at all")
        void nothingIsUnaccountedForUnderConcurrentSaturation() throws Exception {
            final CountDownLatch publishing = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final AtomicInteger delivered = new AtomicInteger();
            doAnswer(invocation -> {
                publishing.countDown();
                release.await(WAIT_MILLIS, TimeUnit.MILLISECONDS);
                delivered.incrementAndGet();
                return null;
            }).when(operations).sendNotification(anyString(), any());

            // Block the worker first, so every producer below races against a channel that is genuinely
            // saturated rather than one that happens to keep up.
            service.onApplicationEvent(completedEvent());
            assertThat(publishing.await(WAIT_MILLIS, TimeUnit.MILLISECONDS)).isTrue();

            final int producers = 8;
            final int perProducer = 40;
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch finished = new CountDownLatch(producers);
            final List<Thread> threads = new ArrayList<>();
            for (int producer = 0; producer < producers; producer++) {
                final Thread thread = new Thread(() -> {
                    try {
                        start.await(WAIT_MILLIS, TimeUnit.MILLISECONDS);
                        for (int offered = 0; offered < perProducer; offered++) {
                            service.onApplicationEvent(completedEvent());
                        }
                    } catch (final InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                }, "saturating-producer-" + producer);
                threads.add(thread);
                thread.start();
            }
            start.countDown();
            assertThat(finished.await(WAIT_MILLIS, TimeUnit.MILLISECONDS))
                    .as("no producer may block; the hand-off is the property under test")
                    .isTrue();
            for (final Thread thread : threads) {
                thread.join(WAIT_MILLIS);
            }

            release.countDown();
            // close() drains what it can inside the grace period and counts the remainder as abandoned,
            // so after it returns every offered snapshot has reached exactly one of the two outcomes.
            service.close();

            final int offered = 1 + producers * perProducer;
            assertThat(delivered.get() + (int) totalShed())
                    .as("offered = delivered + lost, with nothing unaccounted for on either side. A count "
                            + "sampled before the hand-off cannot satisfy this: it over-reports when the "
                            + "worker drains in the gap and under-reports when a peer fills it")
                    .isEqualTo(offered);
            assertThat(totalShed())
                    .as("the channel was genuinely saturated, so this test would prove nothing if it "
                            + "had happened to lose none")
                    .isPositive();
        }

        /**
         * @return how many notifications have been counted lost for any reason
         */
        private double totalShed() {
            return shed(JobCompletionNotificationService.REASON_QUEUE_FULL)
                    + shed(JobCompletionNotificationService.REASON_NOTIFIER_CLOSED)
                    + shed(JobCompletionNotificationService.REASON_SHUTDOWN);
        }

        /**
         * @param  reason the {@link JobCompletionNotificationService#TAG_REASON} value
         * @return how many notifications have been counted lost for that reason, zero when none
         */
        private double shed(final String reason) {
            final Counter counter = meterRegistry
                    .find(JobCompletionNotificationService.SHED_METER_NAME)
                    .tag(JobCompletionNotificationService.TAG_REASON, reason)
                    .counter();
            return counter == null ? 0.0d : counter.count();
        }
    }

    /**
     * The two boundaries are instants, so two instances in two zones can be put in order.
     *
     * <p>The defect this closes is invisible in a single-zone test, which is why the assertion below is a
     * comparison rather than a format check: one wall-clock reading taken in two zones is two different
     * moments, and the previous payload rendered both identically. A subscriber merging notices from two
     * regions therefore had no way to sequence them, and a notice either side of a daylight-saving
     * transition was ambiguous against its own neighbours in the same region.
     */
    @Nested
    @DisplayName("Completion boundaries are instants under a versioned schema")
    class BoundariesAreInstants {

        @Test
        @DisplayName("one wall-clock reading in two zones becomes two distinct instants, and two distinct "
                + "payloads, where it used to become one")
        void oneReadingInTwoZonesBecomesTwoInstants() {
            final JobCompletionEvent inNewYork = frameworkExecutionIn(ZoneId.of("America/New_York"));
            final JobCompletionEvent inTokyo = frameworkExecutionIn(ZoneId.of("Asia/Tokyo"));

            assertThat(inNewYork.startedAt())
                    .as("the same wall-clock reading in two zones is not the same moment")
                    .isNotEqualTo(inTokyo.startedAt());
            assertThat(JobCompletionNotificationService.payload(inNewYork))
                    .isNotEqualTo(JobCompletionNotificationService.payload(inTokyo));
        }

        @Test
        @DisplayName("the recording zone is applied, and an absent boundary stays absent rather than "
                + "becoming an epoch or a placeholder")
        void theRecordingZoneIsAppliedAndAbsenceIsPreserved() {
            final JobCompletionEvent recorded = frameworkExecutionIn(RECORDING_ZONE);

            assertThat(recorded.startedAt()).isEqualTo(STARTED);
            assertThat(recorded.endedAt()).isEqualTo(ENDED);

            final JobCompletionEvent unfinished = JobCompletionEvent.ofFrameworkExecution(
                    JOB, INSTANCE_ID, EXECUTION_ID, BatchStatus.STARTED, null, 0,
                    STARTED_WALL_CLOCK, null, RECORDING_ZONE);

            assertThat(unfinished.endedAt())
                    .as("the framework records no end time for an execution abandoned before it "
                            + "finished, and inventing one would report a time that never happened")
                    .isNull();
            assertThat(JobCompletionNotificationService.payload(unfinished))
                    .contains("\"endedAt\":null");
        }

        @Test
        @DisplayName("the factory refuses an absent recording zone, because a reading with no zone is "
                + "the very thing being removed")
        void theFactoryRefusesAnAbsentZone() {
            assertThatNullPointerException()
                    .isThrownBy(() -> JobCompletionEvent.ofFrameworkExecution(
                            JOB, INSTANCE_ID, EXECUTION_ID, BatchStatus.COMPLETED, "COMPLETED", 3,
                            STARTED_WALL_CLOCK, ENDED_WALL_CLOCK, null))
                    .withMessageContaining("recordingZone");
        }

        @Test
        @DisplayName("the schema version records the change, so a subscriber can tell the two meanings "
                + "of the timestamp fields apart")
        void theSchemaVersionRecordsTheChange() {
            assertThat(JobCompletionNotificationService.SCHEMA_VERSION)
                    .as("the timestamps changed meaning and not only format, which is what a version is "
                            + "for")
                    .isEqualTo(2);
            assertThat(JobCompletionNotificationService.payload(completedEvent()))
                    .contains("\"schemaVersion\":2")
                    .contains("Z\"");
        }

        /**
         * @param  zone the zone the framework's wall-clock readings are treated as taken in
         * @return a snapshot built the way the batch boundary builds one
         */
        private JobCompletionEvent frameworkExecutionIn(final ZoneId zone) {
            return JobCompletionEvent.ofFrameworkExecution(JOB, INSTANCE_ID, EXECUTION_ID,
                    BatchStatus.COMPLETED, "COMPLETED", 3, STARTED_WALL_CLOCK, ENDED_WALL_CLOCK, zone);
        }
    }

    private static String lowTag(final Observation.Context context, final String name) {
        for (final KeyValue keyValue : context.getLowCardinalityKeyValues()) {
            if (name.equals(keyValue.getKey())) {
                return keyValue.getValue();
            }
        }
        return null;
    }

    private static String highTag(final Observation.Context context, final String name) {
        for (final KeyValue keyValue : context.getHighCardinalityKeyValues()) {
            if (name.equals(keyValue.getKey())) {
                return keyValue.getValue();
            }
        }
        return null;
    }
}
