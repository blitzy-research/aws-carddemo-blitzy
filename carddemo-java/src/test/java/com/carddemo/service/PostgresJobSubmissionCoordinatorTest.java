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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.slf4j.LoggerFactory;

import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

/**
 * Verifies what the deployment-wide submission guard decides, without a database.
 *
 * <p>That two sessions cannot hold the same advisory key at once is the database's behaviour and cannot be
 * demonstrated by a mock; it is asserted against a real server in
 * {@code PostgresJobSubmissionCoordinatorIT}. What is asserted here is everything this class decides: the
 * statement it issues, the keys it binds, the bound it places on waiting, and - the property this suite
 * exists for - which side of the queue's own effect a failure fell on.
 *
 * <h2>Why the last of those needs its own tests</h2>
 *
 * <p>The guard's transaction commits <em>after</em> the guarded work returns, so an exception reaching the
 * caller may have originated either before the seventeen cards were published or after. The two cases
 * demand opposite answers: before, nothing reached the queue and reporting zero cards is the truth; after,
 * the messages exist, a job stream will run from them, and reporting zero cards invites a resubmission that
 * runs the job twice. Nothing in the exception distinguishes them, so the coordinator has to remember, and
 * these tests drive the failure at each side of that line.
 */
@DisplayName("PostgresJobSubmissionCoordinator: bounded waiting and a verdict that matches the queue")
class PostgresJobSubmissionCoordinatorTest {

    private static final String SUBMISSION = "report-request-1";

    private static final int CANONICAL_CARDS = 17;

    private JdbcTemplate jdbcTemplate;
    private PlatformTransactionManager transactionManager;
    private TransactionStatus transactionStatus;
    private Connection connection;
    private PreparedStatement acquisition;
    private List<Integer> boundKeys;
    private PostgresJobSubmissionCoordinator coordinator;

    private Logger coordinatorLogger;
    private ListAppender<ILoggingEvent> logRecorder;
    private Level originalLevel;

    @BeforeEach
    void buildCoordinatorAndAttachLogRecorder() throws SQLException {
        this.jdbcTemplate = mock(JdbcTemplate.class);
        this.transactionManager = mock(PlatformTransactionManager.class);
        this.transactionStatus = mock(TransactionStatus.class);
        this.connection = mock(Connection.class);
        this.acquisition = mock(PreparedStatement.class);
        this.boundKeys = new ArrayList<>();

        when(this.transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(this.transactionStatus);
        when(this.connection.prepareStatement(
                PostgresJobSubmissionCoordinator.ACQUIRE_LOCK_SQL))
                .thenReturn(this.acquisition);
        org.mockito.Mockito.doAnswer(invocation -> {
            this.boundKeys.add(invocation.getArgument(1, Integer.class));
            return null;
        }).when(this.acquisition).setInt(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
        runCallbackOnTheMockConnection();

        this.coordinator = new PostgresJobSubmissionCoordinator(this.jdbcTemplate,
                this.transactionManager);

        this.coordinatorLogger =
                (Logger) LoggerFactory.getLogger(PostgresJobSubmissionCoordinator.class);
        this.originalLevel = this.coordinatorLogger.getLevel();
        this.logRecorder = new ListAppender<>();
        this.logRecorder.setContext(this.coordinatorLogger.getLoggerContext());
        this.logRecorder.start();
        this.coordinatorLogger.addAppender(this.logRecorder);
        this.coordinatorLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void detachLogRecorder() {
        this.coordinatorLogger.detachAppender(this.logRecorder);
        this.logRecorder.stop();
        this.coordinatorLogger.setLevel(this.originalLevel);
    }

    /**
     * Makes the mocked template run the production callback against the mocked connection.
     *
     * <p>The callback is extracted through a parameterized accessor rather than by naming the raw class
     * literal: a raw {@code ConnectionCallback} would make the generic {@code execute} invocation
     * unchecked, and an unchecked operation is a build failure here, so the type is carried rather than
     * suppressed.</p>
     */
    private void runCallbackOnTheMockConnection() {
        when(this.jdbcTemplate.execute(anyConnectionCallback()))
                .thenAnswer(invocation -> {
                    final ConnectionCallback<Void> callback = invocation.getArgument(0);
                    return callback.doInConnection(this.connection);
                });
    }

    /**
     * A matcher for the coordinating callback that carries its type parameter.
     *
     * @return a matcher accepting any {@code ConnectionCallback<Void>}
     */
    private static ConnectionCallback<Void> anyConnectionCallback() {
        return org.mockito.ArgumentMatchers.any();
    }

    /**
     * The outcome of a stream that published every card it holds.
     *
     * @return a successful seventeen-card outcome
     */
    private static JobSubmissionService.SubmissionResult wholeStreamPublished() {
        return new JobSubmissionService.SubmissionResult(SUBMISSION, CANONICAL_CARDS,
                CANONICAL_CARDS, false, "");
    }

    /** Every message the recorder captured at or above the named level, in order. */
    private List<String> recordsAt(final Level level) {
        final List<String> messages = new ArrayList<>();
        for (final ILoggingEvent event : this.logRecorder.list) {
            if (event.getLevel().equals(level)) {
                messages.add(event.getFormattedMessage());
            }
        }
        return messages;
    }

    @Nested
    @DisplayName("the guard itself")
    class TheGuard {

        @Test
        @DisplayName("acquires the module's own two-part key with a parameterized statement")
        void bindsTheModuleKeysAsParameters() {
            coordinator.serialize(PostgresJobSubmissionCoordinatorTest::wholeStreamPublished);

            assertThat(boundKeys)
                    .as("the namespace and the resource are bound as parameters, in that order, so no "
                            + "value is ever concatenated into SQL text")
                    .containsExactly(Integer.valueOf(PostgresJobSubmissionCoordinator.LOCK_NAMESPACE),
                            Integer.valueOf(PostgresJobSubmissionCoordinator.LOCK_RESOURCE));
            assertThat(PostgresJobSubmissionCoordinator.ACQUIRE_LOCK_SQL)
                    .as("the statement is a compile-time constant whose only variables are parameters")
                    .isEqualTo("SELECT pg_advisory_xact_lock(?, ?)");
        }

        @Test
        @DisplayName("bounds the wait, so one stuck holder cannot pin a request thread indefinitely")
        void boundsTheWaitForTheGuard() throws SQLException {
            coordinator.serialize(PostgresJobSubmissionCoordinatorTest::wholeStreamPublished);

            // The bound is applied to the waiting statement itself. Applying it any other way would mean
            // writing a SET statement, which is text this module refuses to assemble.
            verify(acquisition).setQueryTimeout(
                    PostgresJobSubmissionCoordinator.ACQUISITION_TIMEOUT_SECONDS);
            assertThat(PostgresJobSubmissionCoordinator.ACQUISITION_TIMEOUT_SECONDS)
                    .as("a bound of zero or less is no bound at all: the driver reads zero as unlimited")
                    .isPositive();
        }

        @Test
        @DisplayName("runs in its own transaction, so no caller can extend the guard past the stream")
        void theGuardRunsInItsOwnTransaction() {
            coordinator.serialize(PostgresJobSubmissionCoordinatorTest::wholeStreamPublished);

            final org.mockito.ArgumentCaptor<TransactionDefinition> definition =
                    org.mockito.ArgumentCaptor.forClass(TransactionDefinition.class);
            verify(transactionManager).getTransaction(definition.capture());
            assertThat(definition.getValue().getPropagationBehavior())
                    .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }

        @Test
        @DisplayName("returns the outcome the guarded work produced")
        void returnsTheGuardedOutcome() {
            final JobSubmissionService.SubmissionResult result =
                    coordinator.serialize(PostgresJobSubmissionCoordinatorTest::wholeStreamPublished);

            assertThat(result.cardsPublished()).isEqualTo(CANONICAL_CARDS);
            assertThat(result.failed()).isFalse();
            verify(transactionManager).commit(transactionStatus);
        }

        @Test
        @DisplayName("refuses a null submission rather than opening a transaction for nothing")
        void aNullSubmissionIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> coordinator.serialize(null));

            verify(transactionManager, never()).getTransaction(any(TransactionDefinition.class));
        }
    }

    @Nested
    @DisplayName("a guard that was never acquired: nothing reached the queue")
    class BeforeTheQueueWasTouched {

        @Test
        @DisplayName("an expired wait publishes nothing and is reported as a coordination failure")
        void anExpiredWaitPublishesNothing() {
            final AtomicInteger submissionsRun = new AtomicInteger();
            // What a cancelled advisory-lock wait surfaces as once the template has translated it.
            doThrow(new QueryTimeoutException("canceling statement due to statement timeout"))
                    .when(jdbcTemplate).execute(anyConnectionCallback());

            assertThatExceptionOfType(JobSubmissionCoordinator.CoordinationFailure.class)
                    .isThrownBy(() -> coordinator.serialize(() -> {
                        submissionsRun.incrementAndGet();
                        return wholeStreamPublished();
                    }))
                    .withMessageContaining("could not serialize")
                    .withCauseInstanceOf(QueryTimeoutException.class);

            assertThat(submissionsRun)
                    .as("the stream never begins, which is what makes the caller's zero-cards report "
                            + "truthful rather than merely safe")
                    .hasValue(0);
            assertThat(recordsAt(Level.WARN))
                    .as("the operator is told the guard was not acquired within its bounded wait, so a "
                            + "refusal is distinguishable from a stream that failed midway")
                    .anySatisfy(message -> assertThat(message)
                            .contains("was not acquired within its bounded wait of "
                                    + PostgresJobSubmissionCoordinator.ACQUISITION_TIMEOUT_SECONDS + "s")
                            .contains("nothing reached the queue")
                            .contains("QueryTimeoutException"));
            assertThat(recordsAt(Level.ERROR))
                    .as("nothing was published, so there is no operational alert to raise")
                    .isEmpty();
        }

        @Test
        @DisplayName("a transaction that cannot be begun is a coordination failure")
        void aTransactionThatCannotBeBegunIsACoordinationFailure() {
            final AtomicInteger submissionsRun = new AtomicInteger();
            doThrow(new CannotCreateTransactionException("no connection"))
                    .when(transactionManager).getTransaction(any(TransactionDefinition.class));

            assertThatExceptionOfType(JobSubmissionCoordinator.CoordinationFailure.class)
                    .isThrownBy(() -> coordinator.serialize(() -> {
                        submissionsRun.incrementAndGet();
                        return wholeStreamPublished();
                    }))
                    .withCauseInstanceOf(CannotCreateTransactionException.class);

            assertThat(submissionsRun).hasValue(0);
        }

        @Test
        @DisplayName("an unreachable database during acquisition is a coordination failure")
        void anUnreachableDatabaseIsACoordinationFailure() {
            doThrow(new CannotGetJdbcConnectionException("no connection"))
                    .when(jdbcTemplate).execute(anyConnectionCallback());

            assertThatExceptionOfType(JobSubmissionCoordinator.CoordinationFailure.class)
                    .isThrownBy(() -> coordinator.serialize(
                            PostgresJobSubmissionCoordinatorTest::wholeStreamPublished))
                    .withCauseInstanceOf(CannotGetJdbcConnectionException.class);
        }

        @Test
        @DisplayName("a failure raised by the stream itself propagates unchanged, not as a guard failure")
        void aStreamFailurePropagatesUnchanged() {
            final IllegalStateException streamFailure =
                    new IllegalStateException("the card image could not be encoded");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> coordinator.serialize(() -> {
                        throw streamFailure;
                    }))
                    .isSameAs(streamFailure);

            // Wrapping it would tell the caller the guard failed, which would be a second untruth on top
            // of the first: the guard was held, and the stream is what could not run.
            verify(transactionManager).rollback(transactionStatus);
            verify(transactionManager, never()).commit(transactionStatus);
        }

        @Test
        @DisplayName("a stream that returns nothing is refused rather than reported as an outcome")
        void aStreamThatReturnsNothingIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> coordinator.serialize(() -> null));
        }
    }

    @Nested
    @DisplayName("a guard that could not be released: the queue already has the cards")
    class AfterTheQueueWasTouched {

        @Test
        @DisplayName("a release failure returns what was published instead of claiming nothing was")
        void aReleaseFailureReportsWhatWasPublished() {
            final AtomicInteger submissionsRun = new AtomicInteger();
            // // The commit runs after the callback returns, so this is the failure a naive coordinator reports
            // // as a submission that never happened.
            doThrow(new TransactionSystemException("the transaction could not be committed"))
                    .when(transactionManager).commit(transactionStatus);

            final JobSubmissionService.SubmissionResult result =
                    coordinator.serialize(() -> {
                        submissionsRun.incrementAndGet();
                        return wholeStreamPublished();
                    });

            assertThat(submissionsRun).hasValue(1);
            assertThat(result.cardsPublished())
                    .as("the messages exist on the queue, so the count that describes them is the only "
                            + "honest answer; reporting zero would invite a resubmission and run the "
                            + "job twice")
                    .isEqualTo(CANONICAL_CARDS);
            assertThat(result.failed()).isFalse();
            assertThat(result.submissionId()).isEqualTo(SUBMISSION);
        }

        @Test
        @DisplayName("the release failure is raised as an operational alert naming counts, not content")
        void theReleaseFailureIsAnOperationalAlert() {
            doThrow(new TransactionSystemException("the transaction could not be committed"))
                    .when(transactionManager).commit(transactionStatus);

            coordinator.serialize(PostgresJobSubmissionCoordinatorTest::wholeStreamPublished);

            assertThat(recordsAt(Level.ERROR))
                    .as("silence would leave a real database fault unreported; the alert says the "
                            + "submission stands so nobody resubmits on the strength of it")
                    .singleElement()
                    .satisfies(message -> assertThat(message)
                            .contains("OPERATIONAL ALERT")
                            .contains("could not be released")
                            .contains("had already been published and stands")
                            .contains("cardsPublished=" + CANONICAL_CARDS)
                            .contains("TransactionSystemException")
                            .doesNotContain(SUBMISSION));
            assertThat(recordsAt(Level.WARN))
                    .as("the guard WAS acquired, so the not-acquired diagnostic must not also fire")
                    .isEmpty();
        }

        @Test
        @DisplayName("a stream that stopped early keeps its own partial count through a release failure")
        void aPartialStreamKeepsItsOwnCount() {
            final JobSubmissionService.SubmissionResult stoppedEarly =
                    new JobSubmissionService.SubmissionResult(SUBMISSION, CANONICAL_CARDS, 5, true,
                            "Unable to Write TDQ (JOBS)...");
            doThrow(new TransactionSystemException("the transaction could not be committed"))
                    .when(transactionManager).commit(transactionStatus);

            final JobSubmissionService.SubmissionResult result =
                    coordinator.serialize(() -> stoppedEarly);

            // Neither improved nor worsened. Five cards are on the queue and twelve are not, and the
            // release fault changes neither number.
            assertThat(result).isSameAs(stoppedEarly);
            assertThat(result.cardsPublished()).isEqualTo(5);
            assertThat(result.failed()).isTrue();
        }
    }
}
