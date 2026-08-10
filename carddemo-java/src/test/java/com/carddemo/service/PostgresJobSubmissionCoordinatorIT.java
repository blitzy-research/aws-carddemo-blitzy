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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.util.JclCardImageBuilder;
import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsOperations;
import io.awspring.cloud.sqs.operations.SqsSendOptions;
import io.micrometer.observation.ObservationRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.messaging.support.GenericMessage;

/**
 * Verifies the whole-stream guard against the real PostgreSQL advisory-lock implementation and two
 * independently constructed service instances, the shape used by separate application replicas.
 */
@DisplayName("PostgreSQL job-submission coordinator")
class PostgresJobSubmissionCoordinatorIT extends AbstractPostgresIT {

    private static final String QUEUE = "JOBS.fifo";

    private static final String MESSAGE_GROUP = "carddemo-job-submission";

    private static final String FIRST_SUBMISSION = "replica-A";

    private static final String SECOND_SUBMISSION = "replica-B";

    private static final long WAIT_SECONDS = 20L;

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("serializes complete streams across two coordinator instances sharing only PostgreSQL")
    void serializesAcrossReplicaInstances() throws Exception {
        final javax.sql.DataSource dataSource = dataSource();
        final JobSubmissionCoordinator firstCoordinator = coordinator(dataSource);
        final JobSubmissionCoordinator secondCoordinator = coordinator(dataSource);
        final RecordingQueue queue = new RecordingQueue();

        final JobSubmissionService firstService = new JobSubmissionService(
                queue.operations, QUEUE, MESSAGE_GROUP, firstCoordinator, ObservationRegistry.NOOP);
        final JobSubmissionService secondService = new JobSubmissionService(
                queue.operations, QUEUE, MESSAGE_GROUP, secondCoordinator, ObservationRegistry.NOOP);

        final ExecutorService replicas = Executors.newFixedThreadPool(2);
        try {
            final var first = replicas.submit(() -> firstService.submitJobStream(
                    FIRST_SUBMISSION, streamFor(FIRST_SUBMISSION)));
            assertThat(queue.firstSendEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("the first replica must hold the database lock while it is inside the stream")
                    .isTrue();

            final var second = replicas.submit(() -> secondService.submitJobStream(
                    SECOND_SUBMISSION, streamFor(SECOND_SUBMISSION)));

            assertThat(queue.secondSendEntered.await(300L, TimeUnit.MILLISECONDS))
                    .as("a second replica must not enter even its first send while the first holds the"
                            + " PostgreSQL advisory lock")
                    .isFalse();

            queue.releaseFirstSend.countDown();
            assertThat(first.get(WAIT_SECONDS, TimeUnit.SECONDS).complete()).isTrue();
            assertThat(second.get(WAIT_SECONDS, TimeUnit.SECONDS).complete()).isTrue();
        } finally {
            queue.releaseFirstSend.countDown();
            replicas.shutdownNow();
            assertThat(replicas.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        final List<String> arrived = queue.arrived();
        assertThat(arrived).hasSize(2 * JclCardImageBuilder.CARD_COUNT);
        assertContiguous(arrived, FIRST_SUBMISSION);
        assertContiguous(arrived, SECOND_SUBMISSION);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("a statement timeout really does cancel a wait for this lock, which is what bounds it")
    void aStatementTimeoutCancelsAWaitForTheGuard() throws Exception {
        // Establishes the one half of the bounded-wait property that no mock can: that the driver and
        // the server honour a statement timeout for THIS statement, so a wait for an advisory lock
        // already held is cancelled rather than continued. The other half - that the coordinator applies
        // the bound to exactly this statement, with its own value - is asserted in
        // PostgresJobSubmissionCoordinatorTest, because the value it applies is a decision and not a
        // mechanism. Together they cover it.
        //
        // A one-second bound is used rather than the coordinator's own, so this test spends a second
        // demonstrating a mechanism instead of spending the coordinator's whole allowance demonstrating
        // the same mechanism.
        final int oneSecond = 1;
        try (Connection holder = connect()) {
            holder.setAutoCommit(false);
            acquireGuardOn(holder, 0);

            try (Connection waiter = connect()) {
                waiter.setAutoCommit(false);
                final long startedAt = System.nanoTime();

                // An unbounded wait is what lets one stuck holder pin a request thread and a pooled
                // connection for as long as it chooses to exist.
                assertThatExceptionOfType(SQLException.class)
                        .isThrownBy(() -> acquireGuardOn(waiter, oneSecond))
                        .satisfies(cancelled -> assertThat(cancelled.getSQLState())
                                .as("57014 is query_canceled: the wait was cut short, rather than "
                                        + "refused for some unrelated reason that would let this test "
                                        + "pass by accident")
                                .isEqualTo("57014"));

                final long waitedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
                assertThat(waitedMillis)
                        .as("the wait ended near its own bound rather than near the holder's lifetime")
                        .isLessThan(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
                waiter.rollback();
            }
            holder.rollback();
        }
    }

    /**
     * Acquires the coordinator's own two-part guard on one connection, optionally bounded.
     *
     * @param connection    the connection to acquire on, inside its own transaction
     * @param timeoutSeconds the bound to apply, or zero for the driver's unbounded default
     * @throws SQLException if the statement fails or its wait is cancelled
     */
    private static void acquireGuardOn(final Connection connection, final int timeoutSeconds)
            throws SQLException {
        try (java.sql.PreparedStatement acquisition = connection.prepareStatement(
                PostgresJobSubmissionCoordinator.ACQUIRE_LOCK_SQL)) {
            acquisition.setQueryTimeout(timeoutSeconds);
            acquisition.setInt(1, PostgresJobSubmissionCoordinator.LOCK_NAMESPACE);
            acquisition.setInt(2, PostgresJobSubmissionCoordinator.LOCK_RESOURCE);
            acquisition.execute();
        }
    }

    private static javax.sql.DataSource dataSource() {
        return new DriverManagerDataSource(jdbcUrl(), databaseUser(), databasePassword());
    }

    private static JobSubmissionCoordinator coordinator(final javax.sql.DataSource dataSource) {
        return new PostgresJobSubmissionCoordinator(new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource));
    }

    private static List<String> streamFor(final String submissionId) {
        final List<String> cards = new ArrayList<>(JclCardImageBuilder.CARD_COUNT);
        for (int ordinal = 1; ordinal < JclCardImageBuilder.CARD_COUNT; ordinal++) {
            cards.add(pad(submissionId + "#" + ordinal));
        }
        cards.add(pad(JclCardImageBuilder.EOF_SENTINEL_CARD + submissionId));
        return List.copyOf(cards);
    }

    private static String pad(final String value) {
        return value + " ".repeat(JclCardImageBuilder.CARD_IMAGE_WIDTH - value.length());
    }

    private static void assertContiguous(final List<String> arrived, final String submissionId) {
        final List<Integer> positions = new ArrayList<>();
        for (int index = 0; index < arrived.size(); index++) {
            if (arrived.get(index).contains(submissionId)) {
                positions.add(index);
            }
        }
        assertThat(positions).hasSize(JclCardImageBuilder.CARD_COUNT);
        for (int index = 1; index < positions.size(); index++) {
            assertThat(positions.get(index))
                    .as("%s card %d must immediately follow its predecessor",
                            submissionId, index + 1)
                    .isEqualTo(positions.get(index - 1) + 1);
        }
    }

    private static <T> Consumer<SqsSendOptions<T>> anyConfigurer() {
        return org.mockito.ArgumentMatchers.any();
    }

    private static final class RecordingQueue {
        private final SqsOperations operations = mock(SqsOperations.class);

        private final List<String> received = Collections.synchronizedList(new ArrayList<>());

        private final AtomicBoolean first = new AtomicBoolean(true);

        private final CountDownLatch firstSendEntered = new CountDownLatch(1);

        private final CountDownLatch secondSendEntered = new CountDownLatch(1);

        private final CountDownLatch releaseFirstSend = new CountDownLatch(1);

        RecordingQueue() {
            when(this.operations.send(
                    PostgresJobSubmissionCoordinatorIT.<String>anyConfigurer()))
                    .thenAnswer(invocation -> send(invocation.getArgument(0)));
        }

        private SendResult<String> send(final Consumer<SqsSendOptions<String>> configurer)
                throws InterruptedException {
            final CapturedSend<String> captured = new CapturedSend<>();
            configurer.accept(captured);
            if (captured.payload.contains(SECOND_SUBMISSION)) {
                this.secondSendEntered.countDown();
            }
            if (this.first.compareAndSet(true, false)) {
                this.firstSendEntered.countDown();
                if (!this.releaseFirstSend.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the first send was never released");
                }
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
