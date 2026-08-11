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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.exception.JobSubmissionException;
import com.carddemo.util.JclCardImageBuilder;
import com.carddemo.util.SanitisedObservation;
import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsOperations;
import io.awspring.cloud.sqs.operations.SqsSendOptions;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.GenericMessage;

/**
 * Proves that the explicit queue-publish observation reaches the actual send call and records its
 * failure rather than merely timing the surrounding report request.
 */
@DisplayName("JobSubmissionService - outbound SQS observation")
class JobSubmissionServiceObservationTest {

    private static final String QUEUE = "JOBS.fifo";

    private static final String MESSAGE_GROUP = "carddemo-job-submission";

    private static final String SUBMISSION = "observation-submission";

    private final List<Observation.Context> observed = new ArrayList<>();

    private SqsOperations operations;

    private JobSubmissionService service;

    @BeforeEach
    void bindARecordingRegistry() {
        this.operations = mock(SqsOperations.class);
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
        final JobSubmissionCoordinator coordinator = submission -> submission.get();
        this.service = new JobSubmissionService(
                this.operations, QUEUE, MESSAGE_GROUP, coordinator, registry);
    }

    @Test
    @DisplayName("uses fixed low-cardinality tags and carries boundary detail only on the span")
    void recordsTheFixedTagContract() {
        when(this.operations.send(JobSubmissionServiceObservationTest.<String>anyConfigurer()))
                .thenAnswer(invocation -> accepted(invocation.getArgument(0)));

        final JobSubmissionService.SubmissionResult result =
                this.service.submitJobStream(SUBMISSION, List.of(card()));

        assertThat(result.complete()).isTrue();
        assertThat(this.observed).hasSize(1);
        final Observation.Context context = this.observed.getFirst();
        assertThat(context.getName()).isEqualTo(JobSubmissionService.PUBLISH_OBSERVATION_NAME);
        assertThat(lowTag(context, JobSubmissionService.TAG_SYSTEM))
                .isEqualTo(JobSubmissionService.SYSTEM_SQS);
        assertThat(lowTag(context, JobSubmissionService.TAG_OPERATION))
                .isEqualTo(JobSubmissionService.OPERATION_SEND);
        assertThat(highTag(context, JobSubmissionService.TAG_QUEUE)).isEqualTo(QUEUE);
        assertThat(highTag(context, JobSubmissionService.TAG_SUBMISSION)).isEqualTo(SUBMISSION);
        assertThat(highTag(context, JobSubmissionService.TAG_CARD_ORDINAL)).isEqualTo("1");
        assertThat(context.getError()).isNull();
    }

    @Test
    @DisplayName("records a sanitised classification of a send failure on the outbound observation before "
            + "returning it non-fatally")
    void recordsTheBoundaryFailure() {
        final IllegalStateException refused =
                new IllegalStateException("refused queue=" + QUEUE + " endpoint=sqs.internal.example");
        when(this.operations.send(JobSubmissionServiceObservationTest.<String>anyConfigurer()))
                .thenThrow(refused);

        final JobSubmissionService.SubmissionResult result =
                this.service.submitJobStream(SUBMISSION, List.of(card()));

        assertThat(result.failed()).isTrue();
        assertThat(this.observed).hasSize(1);
        final Throwable recorded = this.observed.getFirst().getError();
        assertThat(recorded)
                .as("the span must still show that the boundary failed")
                .isNotNull()
                .as("and must not show the queue client's own failure, whose message the exporter "
                        + "publishes verbatim")
                .isNotSameAs(refused)
                .isInstanceOf(SanitisedObservation.SanitisedBoundaryFailure.class);
        assertThat(recorded.getMessage())
                .doesNotContain("sqs.internal.example")
                .contains(SanitisedObservation.FAILURE_CHAIN_LABEL + "IllegalStateException");
        assertThat(recorded.getCause()).isNull();
    }

    @Test
    @DisplayName("returns a shared-coordination failure through the legacy non-fatal bridge contract")
    void returnsCoordinationFailureNonFatally() {
        final IllegalStateException databaseFailure = new IllegalStateException("database refused");
        final JobSubmissionCoordinator coordinator = submission -> {
            throw new JobSubmissionCoordinator.CoordinationFailure(
                    "Unable to serialize the job-submission stream", databaseFailure);
        };
        final JobSubmissionService guardedService = new JobSubmissionService(
                this.operations, QUEUE, MESSAGE_GROUP, coordinator, ObservationRegistry.NOOP);

        final JobSubmissionService.SubmissionResult result =
                guardedService.submitJobStream(SUBMISSION, List.of(card()));

        assertThat(result.cardsRequested()).isOne();
        assertThat(result.cardsPublished()).isZero();
        assertThat(result.failed()).isTrue();
        assertThat(result.failureMessage()).isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
        assertThat(result.submissionId()).isEqualTo(SUBMISSION);
        verifyNoInteractions(this.operations);
    }

    private static String card() {
        return JclCardImageBuilder.build("2022-01-01", "2022-01-31").getFirst();
    }

    private static SendResult<String> accepted(final Consumer<SqsSendOptions<String>> configurer) {
        final CapturedSend<String> captured = new CapturedSend<>();
        configurer.accept(captured);
        return new SendResult<>(UUID.randomUUID(), QUEUE,
                new GenericMessage<>(captured.payload), Map.of());
    }

    private static <T> Consumer<SqsSendOptions<T>> anyConfigurer() {
        return org.mockito.ArgumentMatchers.any();
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

    private static final class CapturedSend<T> implements SqsSendOptions<T> {
        private T payload;

        @Override
        public SqsSendOptions<T> queue(final String value) {
            return this;
        }

        @Override
        public SqsSendOptions<T> payload(final T value) {
            this.payload = value;
            return this;
        }

        @Override
        public SqsSendOptions<T> header(final String name, final Object value) {
            return this;
        }

        @Override
        public SqsSendOptions<T> headers(final Map<String, Object> values) {
            return this;
        }

        @Override
        public SqsSendOptions<T> delaySeconds(final Integer value) {
            return this;
        }

        @Override
        public SqsSendOptions<T> messageGroupId(final String value) {
            return this;
        }

        @Override
        public SqsSendOptions<T> messageDeduplicationId(final String ignoredValue) {
            return this;
        }
    }
}
