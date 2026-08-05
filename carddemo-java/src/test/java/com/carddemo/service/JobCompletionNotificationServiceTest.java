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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
