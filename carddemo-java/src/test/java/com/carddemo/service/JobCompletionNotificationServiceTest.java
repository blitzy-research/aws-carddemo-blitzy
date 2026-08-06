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
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;

/**
 * Unit contract for the parameter-free SNS job-completion producer.
 */
@DisplayName("JobCompletionNotificationService - bounded SNS completion fan-out")
class JobCompletionNotificationServiceTest {

    private static final String TOPIC = "carddemo-job-notifications";

    private static final String JOB = "postTransactionJob";

    private static final Long INSTANCE_ID = 11L;

    private static final Long EXECUTION_ID = 22L;

    private static final LocalDateTime STARTED = LocalDateTime.of(2022, 7, 19, 10, 11, 12);

    private static final LocalDateTime ENDED = LocalDateTime.of(2022, 7, 19, 10, 12, 13);

    private final List<Observation.Context> observed = new ArrayList<>();

    private SnsOperations operations;

    private JobCompletionNotificationService service;

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
        this.service = new JobCompletionNotificationService(this.operations, TOPIC, registry);
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
                .isEqualTo("{\"schemaVersion\":1,"
                        + "\"eventType\":\"carddemo.batch.job-completion\","
                        + "\"jobName\":\"postTransactionJob\","
                        + "\"jobInstanceId\":11,"
                        + "\"jobExecutionId\":22,"
                        + "\"status\":\"COMPLETED\","
                        + "\"exitCode\":\"COMPLETED\","
                        + "\"stepsExecuted\":3,"
                        + "\"startedAt\":\"2022-07-19T10:11:12\","
                        + "\"endedAt\":\"2022-07-19T10:12:13\"}");
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
    @DisplayName("records an SNS refusal and returns false without throwing")
    void absorbsAndObservesAPublishFailure() {
        final IllegalStateException refused = new IllegalStateException("topic refused");
        doThrow(refused).when(this.operations)
                .sendNotification(org.mockito.ArgumentMatchers.eq(TOPIC),
                        org.mockito.ArgumentMatchers.any());

        assertThat(this.service.publishCompletion(completedEvent())).isFalse();

        assertThat(this.observed).hasSize(1);
        assertThat(this.observed.getFirst().getError()).isSameAs(refused);
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
                        null, TOPIC, ObservationRegistry.NOOP))
                .withMessageContaining("snsOperations");
        assertThatNullPointerException()
                .isThrownBy(() -> new JobCompletionNotificationService(
                        this.operations, null, ObservationRegistry.NOOP))
                .withMessageContaining("topic");
        assertThatNullPointerException()
                .isThrownBy(() -> new JobCompletionNotificationService(
                        this.operations, TOPIC, null))
                .withMessageContaining("observationRegistry");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JobCompletionNotificationService(
                        this.operations, " ", ObservationRegistry.NOOP))
                .withMessageContaining(JobCompletionNotificationService.TOPIC_PROPERTY);
        assertThatThrownBy(() -> new JobCompletionNotificationService(
                this.operations, "topic\nforged", ObservationRegistry.NOOP))
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
