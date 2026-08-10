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

import java.sql.PreparedStatement;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL-backed deployment-wide serialization of the seventeen-card submission stream.
 *
 * <p>A transaction-scoped advisory lock is used because every application replica already shares the
 * configured PostgreSQL database and the lock needs no table, row, migration or cleanup job. The two
 * integer lock keys form a namespace owned by this module; they identify the {@code CARD/JOBS}
 * boundary and are never derived from a request. A prepared statement supplies them as parameters, so
 * no SQL text is assembled from data.
 *
 * <p>The lock is acquired inside a {@link TransactionDefinition#PROPAGATION_REQUIRES_NEW new
 * transaction}. PostgreSQL releases a transaction-scoped advisory lock on both commit and rollback,
 * which means an application crash, an unexpected exception or a normal return all release it without
 * a second unlock statement. The new transaction also prevents an unrelated caller transaction from
 * extending the lock beyond the card stream.
 *
 * <h2>Waiting for the guard is bounded</h2>
 *
 * <p>Acquisition blocks, because a submission already in flight means "wait your turn" rather than
 * "publish out of order". It does <strong>not</strong> block indefinitely. The wait is bounded with
 * {@link PreparedStatement#setQueryTimeout(int)}, which cancels the waiting statement rather than
 * writing any {@code SET} text into SQL, so the unsafe-code audit's raw-SQL count stays at zero by
 * construction. The caller of this coordinator is an online request thread holding a pooled database
 * connection while it waits; an unbounded wait therefore consumes two of the deployment's scarcest
 * resources for as long as one stuck holder chooses to exist, and one stuck holder is enough to
 * exhaust both. A bounded wait converts that into a refused submission.
 *
 * <p>An expired wait is <strong>not fatal and reverses nothing</strong>. It raises
 * {@link JobSubmissionCoordinator.CoordinationFailure}, which the calling service already translates
 * into its non-fatal outcome carrying zero cards published - the truthful report, because an acquisition
 * that expired never entered the stream. That is the migrated form of {@code ERROROPTION(IGNORE)} at
 * {@code [app/csd/CARDDEMO.CSD:TDQUEUE(JOBS)]}: a write the queue would not accept is reported to the
 * operator and abandoned, never retried and never escalated.
 *
 * <h2>Releasing the guard is not part of the submission's truth</h2>
 *
 * <p>The lock's transaction and the submission's external effect end at different moments, and the
 * later one cannot revise the earlier one. Once the guarded work has published cards to the queue,
 * those messages exist: the queue has them, a job stream will run from them, and no database operation
 * can take them back. A failure of the commit that follows - the commit whose only effect is to release
 * the advisory lock, since this coordinator persists no business data - is therefore an operational
 * fault about the guard and never evidence about the submission.
 *
 * <p>So the outcome of a completed submission is captured before the transaction is committed, and a
 * commit or release failure that follows it is reported as an operational alert while the captured
 * outcome is returned unchanged. The alternative, which this class previously did, reports zero cards
 * published for a stream that is queued and about to run - the worst available answer, because an
 * operator told nothing was submitted resubmits, and the job then runs twice.
 *
 * <p>Nothing is leaked by declining to fail: a transaction whose commit failed is aborted by the
 * server, and a connection returned to the pool in that state is reset or discarded. Either releases
 * the transaction-scoped advisory lock the transaction held.
 *
 * <p>This coordinator persists no business data. It provides the cross-replica exclusion needed to
 * preserve the legacy queue's append ordering while leaving schema ownership with Flyway.
 *
 * <p>See {@code docs/decision-log.md} entry DL-304.
 */
@Component
public final class PostgresJobSubmissionCoordinator implements JobSubmissionCoordinator {

    /** Parameterized acquisition of one transaction-scoped, two-part PostgreSQL advisory lock. */
    static final String ACQUIRE_LOCK_SQL = "SELECT pg_advisory_xact_lock(?, ?)";

    /** ASCII {@code CARD}, the module namespace of the advisory lock. */
    static final int LOCK_NAMESPACE = 0x43415244;

    /** ASCII {@code JOBS}, the legacy queue resource guarded by the lock. */
    static final int LOCK_RESOURCE = 0x4A4F4253;

    /**
     * Longest this coordinator will wait for the deployment-wide guard, in seconds.
     *
     * <p>Chosen against the work the guard actually covers rather than as a round number. A holder
     * publishes at most seventeen cards, each bounded by the queue client's own ten-second per-call
     * budget, and an ordinary send completes in milliseconds - so a submission arriving while another is
     * in flight normally waits for a few milliseconds. Fifteen seconds accommodates the worst
     * <em>legitimate</em> holder, one whose stream contains a single send that consumes its entire
     * ten-second budget before the remainder completes, without accommodating a holder that has stopped
     * making progress at all. Two such stalled sends in one stream exceed it, and at that point the queue
     * is unhealthy and refusing the submission is the correct answer rather than a regrettable one.
     *
     * <p>It is a ceiling on waiting, not a target: nothing is tuned to approach it, and reaching it
     * always means something is wrong.
     */
    static final int ACQUISITION_TIMEOUT_SECONDS = 15;

    /** Reports guard acquisition and release faults; the submission itself logs through its service. */
    private static final Logger LOGGER =
            LoggerFactory.getLogger(PostgresJobSubmissionCoordinator.class);

    /** Executes the parameterized advisory-lock statement on the transaction-bound connection. */
    private final JdbcTemplate jdbcTemplate;

    /** Opens the short, independent transaction whose lifetime is the lock lifetime. */
    private final TransactionTemplate transactionTemplate;

    /**
     * @param jdbcTemplate       access to the transaction-bound PostgreSQL connection
     * @param transactionManager transaction manager shared by every replica
     */
    public PostgresJobSubmissionCoordinator(final JdbcTemplate jdbcTemplate,
            final PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public JobSubmissionService.SubmissionResult serialize(
            final Supplier<JobSubmissionService.SubmissionResult> submission) {
        Objects.requireNonNull(submission, "submission must not be null");
        // Two facts about how far the guarded work got, read only when something failed. They exist
        // because the transaction commits AFTER the callback returns, so the exception a caller sees can
        // originate either side of the point at which the queue already holds the cards.
        final AtomicBoolean guardAcquired = new AtomicBoolean();
        final AtomicReference<JobSubmissionService.SubmissionResult> completed =
                new AtomicReference<>();
        try {
            final JobSubmissionService.SubmissionResult result =
                    this.transactionTemplate.execute(status -> {
                        acquireAdvisoryLock();
                        guardAcquired.set(true);
                        final JobSubmissionService.SubmissionResult outcome = submission.get();
                        // Captured here, before the commit that this method's own try block covers. From
                        // this line the queue holds whatever the stream published and no database
                        // operation can revise that.
                        completed.set(Objects.requireNonNull(outcome,
                                "the serialized job-submission work must return an outcome"));
                        return outcome;
                    });
            return Objects.requireNonNull(result,
                    "the serialized job-submission work must return an outcome");
        } catch (final DataAccessException | TransactionException failure) {
            final JobSubmissionService.SubmissionResult alreadySubmitted = completed.get();
            if (alreadySubmitted != null) {
                return reportReleaseFailureAfterSubmission(alreadySubmitted, failure);
            }
            if (!guardAcquired.get()) {
                LOGGER.warn("The deployment-wide job-submission guard was not acquired within its"
                                + " bounded wait of {}s, so this card stream was not submitted and"
                                + " nothing reached the queue. failureType={}",
                        Integer.valueOf(ACQUISITION_TIMEOUT_SECONDS),
                        failure.getClass().getSimpleName());
            }
            throw new CoordinationFailure(
                    "the deployment-wide job-submission guard could not serialize the card stream",
                    failure);
        }
    }

    /**
     * Reports a guard release that failed <em>after</em> the card stream had already been published.
     *
     * <p>The submitted outcome is returned unchanged. It describes messages that exist on the queue, and
     * a database fault occurring afterwards is not evidence about them. Converting this into a failure
     * would tell an operator that nothing was submitted while a job stream is queued and about to run,
     * and the natural response to that report - resubmit - runs the job twice.
     *
     * <p>Logged at error level because it needs attention: the guard's transaction did not end the way it
     * was meant to, which is a database or connectivity fault worth investigating even though this
     * submission is unaffected. The record names counts and a failure type only, so no caller-supplied
     * value reaches it.
     *
     * @param  submitted the outcome the completed submission produced
     * @param  failure   the commit or release fault that followed it
     * @return the submitted outcome, unchanged
     */
    private static JobSubmissionService.SubmissionResult reportReleaseFailureAfterSubmission(
            final JobSubmissionService.SubmissionResult submitted, final RuntimeException failure) {
        LOGGER.error("OPERATIONAL ALERT: the deployment-wide job-submission guard could not be released"
                        + " cleanly, but the card stream had already been published and stands."
                        + " cardsPublished={} cardsRequested={} releaseFailureType={}",
                Integer.valueOf(submitted.cardsPublished()),
                Integer.valueOf(submitted.cardsRequested()),
                failure.getClass().getSimpleName());
        return submitted;
    }

    /**
     * Acquires the fixed transaction-scoped lock on the connection bound to the current transaction.
     *
     * <p>The wait is bounded by {@link PreparedStatement#setQueryTimeout(int)} rather than by a written
     * {@code SET} statement, so no value - not even one of this class's own constants - is concatenated
     * into SQL text. An expired wait cancels this statement, which raises, which the caller translates
     * into a refused submission that published nothing.
     */
    private void acquireAdvisoryLock() {
        this.jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(ACQUIRE_LOCK_SQL)) {
                statement.setQueryTimeout(ACQUISITION_TIMEOUT_SECONDS);
                statement.setInt(1, LOCK_NAMESPACE);
                statement.setInt(2, LOCK_RESOURCE);
                statement.execute();
            }
            return null;
        });
    }
}
