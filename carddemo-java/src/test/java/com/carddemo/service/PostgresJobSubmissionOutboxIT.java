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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.util.JclCardImageBuilder;
import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsOperations;
import io.awspring.cloud.sqs.operations.SqsSendOptions;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.messaging.support.GenericMessage;

/**
 * Proves persisted per-card queue delivery state against a real PostgreSQL server.
 *
 * <p>The decisive scenario is a partial submission followed by a different submission before the
 * first caller retries. A nonce and a JVM lock alone do not solve it: retrying only the missing cards
 * would append those cards after the intervening stream. Two independently constructed service,
 * coordinator and outbox instances are used here so the only state they share is PostgreSQL.
 */
@DisplayName("PostgreSQL job-submission outbox")
class PostgresJobSubmissionOutboxIT extends AbstractPostgresIT {

    private static final String QUEUE = "JOBS.fifo";

    private static final String MESSAGE_GROUP = "carddemo-job-submission";

    private static final String FIRST_SUBMISSION = "outbox-A";

    private static final String SECOND_SUBMISSION = "outbox-B";

    private static final int REFUSED_ORDINAL = 5;

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearOutbox() {
        this.jdbcTemplate = new JdbcTemplate(dataSource());
        this.jdbcTemplate.update("DELETE FROM job_submission_outbox");
    }

    @AfterEach
    void restoreOutbox() {
        this.jdbcTemplate.update("DELETE FROM job_submission_outbox");
    }

    @Test
    @DisplayName("resumes an older partial stream before a newer stream, then makes its retry a no-op")
    void resumesBeforeAnInterveningStream() {
        final DataSource dataSource = dataSource();
        final RecordingQueue queue = new RecordingQueue();
        final JobSubmissionService firstReplica = service(dataSource, queue.operations);
        final JobSubmissionService secondReplica = service(dataSource, queue.operations);
        final List<String> firstCards = streamFor(FIRST_SUBMISSION);
        final List<String> secondCards = streamFor(SECOND_SUBMISSION);

        final JobSubmissionService.SubmissionResult partial =
                firstReplica.submitJobStream(FIRST_SUBMISSION, firstCards);

        assertThat(partial.failed()).isTrue();
        assertThat(partial.cardsPublished()).isEqualTo(REFUSED_ORDINAL - 1);
        assertThat(nextOrdinal(FIRST_SUBMISSION)).isEqualTo(REFUSED_ORDINAL);

        final JobSubmissionService.SubmissionResult second =
                secondReplica.submitJobStream(SECOND_SUBMISSION, secondCards);

        assertThat(second.complete()).isTrue();
        assertThat(queue.arrived()).hasSize(2 * JclCardImageBuilder.CARD_COUNT);
        assertContiguous(queue.arrived(), FIRST_SUBMISSION, 0);
        assertContiguous(queue.arrived(), SECOND_SUBMISSION, JclCardImageBuilder.CARD_COUNT);

        final int sendsBeforeRetry = queue.arrived().size();
        final JobSubmissionService.SubmissionResult retry =
                firstReplica.submitJobStream(FIRST_SUBMISSION, firstCards);

        assertThat(retry.complete()).isTrue();
        assertThat(retry.cardsPublished()).isEqualTo(JclCardImageBuilder.CARD_COUNT);
        assertThat(queue.arrived()).hasSize(sendsBeforeRetry);
        assertThat(completedRowCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("refuses to reuse a persisted identity for different cards")
    void refusesIdentityReuseForDifferentWork() {
        final DataSource dataSource = dataSource();
        final RecordingQueue queue = new RecordingQueue(false);
        final JobSubmissionService service = service(dataSource, queue.operations);
        final List<String> original = streamFor(FIRST_SUBMISSION);
        final List<String> altered = new ArrayList<>(original);
        altered.set(2, pad(FIRST_SUBMISSION + "#changed"));

        assertThat(service.submitJobStream(FIRST_SUBMISSION, original).complete()).isTrue();
        final int sendsBeforeMisuse = queue.arrived().size();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.submitJobStream(FIRST_SUBMISSION, altered))
                .withMessageContaining("true retry");
        assertThat(queue.arrived()).hasSize(sendsBeforeMisuse);
    }

    private static JobSubmissionService service(
            final DataSource dataSource, final SqsOperations operations) {
        final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        final JobSubmissionCoordinator coordinator = new PostgresJobSubmissionCoordinator(
                jdbc, new DataSourceTransactionManager(dataSource));
        final JobSubmissionOutbox outbox = new PostgresJobSubmissionOutbox(jdbc);
        return new JobSubmissionService(operations, QUEUE, MESSAGE_GROUP, coordinator, outbox,
                ObservationRegistry.NOOP);
    }

    private static DataSource dataSource() {
        return new DriverManagerDataSource(jdbcUrl(), databaseUser(), databasePassword());
    }

    private int nextOrdinal(final String submissionId) {
        final Integer next = this.jdbcTemplate.queryForObject(
                "SELECT next_card_ordinal FROM job_submission_outbox "
                        + "WHERE submission_id = ?",
                Integer.class,
                submissionId);
        return next == null ? 0 : next.intValue();
    }

    private int completedRowCount() {
        final Integer count = this.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM job_submission_outbox "
                        + "WHERE completed",
                Integer.class);
        return count == null ? 0 : count.intValue();
    }

    private static List<String> streamFor(final String submissionId) {
        final List<String> cards = new ArrayList<>(JclCardImageBuilder.CARD_COUNT);
        for (int ordinal = 1; ordinal < JclCardImageBuilder.CARD_COUNT; ordinal++) {
            cards.add(pad(submissionId + "#" + ordinal));
        }
        cards.add(pad(JclCardImageBuilder.EOF_SENTINEL_CARD));
        return List.copyOf(cards);
    }

    private static String pad(final String value) {
        return value + " ".repeat(JclCardImageBuilder.CARD_IMAGE_WIDTH - value.length());
    }

    private static void assertContiguous(
            final List<String> arrived, final String submissionId, final int expectedStart) {
        final List<Integer> positions = new ArrayList<>();
        for (int index = 0; index < arrived.size(); index++) {
            if (arrived.get(index).contains(submissionId)) {
                positions.add(Integer.valueOf(index));
            }
        }
        assertThat(positions).hasSize(JclCardImageBuilder.CARD_COUNT - 1);
        for (int index = 0; index < positions.size(); index++) {
            assertThat(positions.get(index).intValue()).isEqualTo(expectedStart + index);
        }
        assertThat(arrived.get(expectedStart + JclCardImageBuilder.CARD_COUNT - 1).stripTrailing())
                .isEqualTo(JclCardImageBuilder.EOF_SENTINEL_CARD);
    }

    private static <T> Consumer<SqsSendOptions<T>> anyConfigurer() {
        return org.mockito.ArgumentMatchers.any();
    }

    private static final class RecordingQueue {
        private final SqsOperations operations = mock(SqsOperations.class);

        private final List<String> received = Collections.synchronizedList(new ArrayList<>());

        private final AtomicBoolean refuseFirstAttempt;

        RecordingQueue() {
            this(true);
        }

        RecordingQueue(final boolean refuseFirstAttempt) {
            this.refuseFirstAttempt = new AtomicBoolean(refuseFirstAttempt);
            when(this.operations.send(
                    PostgresJobSubmissionOutboxIT.<String>anyConfigurer()))
                    .thenAnswer(invocation -> send(invocation.getArgument(0)));
        }

        private SendResult<String> send(final Consumer<SqsSendOptions<String>> configurer) {
            final CapturedSend<String> captured = new CapturedSend<>();
            configurer.accept(captured);
            if (captured.payload.contains(FIRST_SUBMISSION + "#" + REFUSED_ORDINAL)
                    && this.refuseFirstAttempt.compareAndSet(true, false)) {
                throw new IllegalStateException("injected queue refusal");
            }
            this.received.add(captured.payload);
            return new SendResult<>(UUID.randomUUID(), QUEUE,
                    new GenericMessage<>(captured.payload), Map.of());
        }

        private List<String> arrived() {
            synchronized (this.received) {
                return List.copyOf(this.received);
            }
        }
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
