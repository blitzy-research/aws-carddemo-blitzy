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
package com.carddemo.batch;

import com.carddemo.service.BatchJobCatalog;
import com.carddemo.service.BatchLaunchGateway;
import com.carddemo.service.BatchLaunchGateway.LaunchRejectedException;
import com.carddemo.service.BatchLaunchGateway.RejectionReason;
import com.carddemo.util.FailureDiagnostics;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

/**
 * Serializes one on-demand launch per job and mints its identifying run number on the server.
 *
 * <p>The transaction-scoped PostgreSQL advisory lock closes the race between checking the framework's
 * metadata and starting the next instance across every application replica. Once the lock is held, the
 * framework's own metadata is the authority for whether an execution is active. The lock is released by
 * the database when the short coordination transaction ends; the execution itself is not run inside that
 * transaction.
 *
 * <p>The shared incrementer attached to every job remains the one authority for the identifying
 * {@value #SERVER_RUN_ID_PARAMETER} value. Caller parameters are overlaid onto a fresh parameter set, so
 * an unknown value retained by an execution created before this boundary was hardened cannot leak into a
 * new launch.
 *
 * <h2>Reserve on the caller's thread, run on a worker</h2>
 *
 * <p>{@link #start(Job, Map)} used to call {@code Job.execute} inline, which meant the whole job ran on
 * whatever thread asked for it - in production, a servlet request thread. Three things followed. A job
 * that reads a hundred-thousand-record dataset held a request thread for its entire duration, so a
 * handful of launches could exhaust the container's pool and take unrelated endpoints down with them. The
 * execution identifier the caller needs in order to ask after progress was not returned until the job had
 * already finished, which left the status endpoint able to report only outcomes that were no longer news.
 * And any client-side or proxy read timeout shorter than the job turned a successful run into an apparent
 * failure, because the response could not arrive before the work did.
 *
 * <p>The split is therefore: <strong>reserve synchronously, run asynchronously</strong>. Reservation -
 * the advisory lock, the active-execution check, the incrementer and the framework's metadata rows - stays
 * on the caller's thread, because that is what produces the answer the caller is waiting for and the
 * refusal it must be told about at once. Only {@code Job.execute} moves to a worker. Nothing about the
 * guard is relaxed by the move: the launch is still serialized per job by the same transaction-scoped
 * lock, the identifier is still minted by the same shared incrementer, and the same closed refusal
 * vocabulary still reaches the caller before the response is written.
 *
 * <p>The worker pool is bounded and its bound is derived rather than chosen:
 * {@value #MAX_CONCURRENT_LAUNCHES} threads, one per job in the closed launchable inventory, because the
 * reservation admits at most one active execution per job name and so no more than that many executions
 * can ever be in flight. The queue is bounded to the same figure, which under that guarantee is
 * unreachable rather than merely generous. A refusal is nevertheless handled rather than assumed away: the
 * reservation is marked failed so no execution is left recorded as started that will never run, and the
 * caller receives the same "not now" refusal a busy lock produces.
 */
@Component
public final class BatchLaunchCoordinator implements BatchLaunchGateway, AutoCloseable {

    /** The identifying key produced by the shared {@code RunIdIncrementer}. */
    public static final String SERVER_RUN_ID_PARAMETER = "run.id";

    /** Namespace separating these advisory locks from every unrelated database lock user. */
    static final String LOCK_NAMESPACE = "carddemo.batch.launch:";

    /** Parameterized PostgreSQL lock acquisition; no caller value is concatenated into SQL. */
    static final String TRY_LOCK_SQL =
            "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))";

    /**
     * How many times one reservation is attempted before the launch is refused.
     *
     * <p>Two: the first attempt, and one retry for a store conflict the store itself reports as worth
     * retrying. A third attempt would only lengthen the window in which a caller waits for an answer that
     * a second conflict has already made clear.
     */
    static final int RESERVATION_ATTEMPTS = 2;

    /**
     * How many reserved executions may run at once: one per job in the closed launchable inventory.
     *
     * <p>Derived from the guard rather than tuned. The reservation refuses a launch while an execution of
     * the same job is active, so at most one execution per job name can be in flight and the inventory's
     * size is the exact ceiling. A larger figure would buy capacity the guard forbids anyone to use; a
     * smaller one would make a launch of the last job wait behind the others for no reason. It is a
     * consequence of the inventory, so it changes only when the inventory does.
     */
    static final int MAX_CONCURRENT_LAUNCHES = BatchJobCatalog.LAUNCHABLE_JOB_NAMES.size();

    /**
     * How long an idle worker is kept before it retires, in seconds.
     *
     * <p>The JDK's own figure for a cached pool. It is a thread-lifetime housekeeping value and not a
     * performance setting: with a core size of zero and core timeout allowed, it means an instance that
     * launches nothing holds no thread at all, and a burst of launches does not leave nine threads parked
     * for the life of the process.
     */
    static final long WORKER_KEEP_ALIVE_SECONDS = 60L;

    /**
     * The exit description recorded on a reservation that could not be handed to a worker.
     *
     * <p>Authored here rather than taken from a framework message, because it is written to the job
     * repository and read by an operator.
     */
    static final String DISPATCH_REFUSED_EXIT_DESCRIPTION =
            "The reserved execution was never started because the batch launch workers could not accept"
                    + " it; no step of this execution ran.";

    /** Logger for reservation conflicts, which are operational events rather than caller errors. */
    private static final Logger LOG = LoggerFactory.getLogger(BatchLaunchCoordinator.class);

    private final JobRepository jobRepository;

    private final JobExplorer jobExplorer;

    private final JdbcOperations jdbcOperations;

    /**
     * The bounded pool reserved executions are run on.
     *
     * <p>Created here rather than injected, because its bound is part of this class's contract rather than
     * a deployment choice: a caller free to supply an unbounded executor could reintroduce exactly the
     * unbounded concurrency this bound exists to prevent. The threads are <strong>not</strong> daemon
     * threads - a batch job that has begun writing should finish rather than be cut off when the context
     * closes - and with a core size of zero and core timeout allowed, none exists until a job is
     * dispatched and none survives idleness.
     */
    private final ThreadPoolExecutor launchWorkers;

    /** Serial number for worker thread names, so a stack trace names which worker it was on. */
    private final AtomicLong workerNumber = new AtomicLong();

    /**
     * Creates the coordinator over framework metadata and the application transaction boundary.
     *
     * @param jobRepository framework metadata writer used to reserve the next execution
     * @param jobExplorer framework metadata reader used to advance the server run identifier
     * @param jdbcOperations configured PostgreSQL access used for the short advisory-lock transaction
     */
    public BatchLaunchCoordinator(
            final JobRepository jobRepository,
            final JobExplorer jobExplorer,
            final JdbcOperations jdbcOperations) {
        this.jobRepository = Objects.requireNonNull(
                jobRepository, "jobRepository must not be null");
        this.jobExplorer = Objects.requireNonNull(jobExplorer, "jobExplorer must not be null");
        this.jdbcOperations = Objects.requireNonNull(
                jdbcOperations, "jdbcOperations must not be null");
        this.launchWorkers = new ThreadPoolExecutor(
                0, MAX_CONCURRENT_LAUNCHES,
                WORKER_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_CONCURRENT_LAUNCHES),
                runnable -> new Thread(runnable,
                        "batch-launch-" + this.workerNumber.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy());
        // A core of zero with a maximum above it needs the pool to create a worker on demand and to let it
        // retire; without this the pool would hold no thread and queue everything behind nothing.
        this.launchWorkers.allowCoreThreadTimeOut(true);
    }

    /**
     * Reserves one execution of a registered job, hands it to a worker, and answers its identifier without
     * waiting for the job to run.
     *
     * <p>The reservation happens on the calling thread, so every refusal this method can raise still
     * reaches the caller before it returns and the identifier it answers with is already a row in the
     * framework's metadata that the status surface can read. What the caller no longer waits for is the
     * job itself: when this returns, the execution is reserved and dispatched, and it is normally still
     * running. A caller that needs the outcome asks the status surface for it, which is what the
     * identifier is for.
     *
     * @param job registered job to launch
     * @param callerParameters allow-listed caller parameters, with values preserved byte for byte
     * @return the framework execution identifier, valid the moment this returns
     * @throws LaunchRejectedException when the lock is busy, an execution is active, the generated
     *                                 instance already exists, job validation rejects the parameters, or
     *                                 the bounded workers cannot accept the reservation
     * @throws IllegalStateException when the registered job has no usable incrementer
     */
    @Override
    public long start(final Job job, final Map<String, String> callerParameters) {
        final Job registeredJob = Objects.requireNonNull(job, "job must not be null");
        final Map<String, String> parameters =
                Map.copyOf(Objects.requireNonNull(callerParameters,
                        "callerParameters must not be null"));
        final JobExecution reserved = reserveWithDatabaseLock(registeredJob, parameters);
        final long executionId = Objects.requireNonNull(
                reserved.getId(), "job repository returned an execution without an identifier")
                .longValue();
        dispatch(registeredJob, reserved, executionId);
        return executionId;
    }

    /**
     * Hands one reserved execution to the bounded workers, or fails the reservation if they cannot take
     * it.
     *
     * <p>A reservation that is never dispatched must not be left behind as a started execution: the
     * metadata row already exists, the status surface would report it as running forever, and the
     * per-job guard would refuse every later launch of the same job because it would see that row as an
     * active execution. So the refusal path marks it failed, with an authored exit description, before
     * telling the caller.
     *
     * <p>The caller is told {@link RejectionReason#ACTIVE_EXECUTION} - the vocabulary's "not now",
     * already the answer for a busy lock and for a repeated store conflict. A distinct capacity reason
     * was considered and not added: it would widen a published closed vocabulary, and the transport
     * contract that renders it, for a state the per-job guard makes unreachable, since the workers are
     * sized to the inventory the guard admits.
     *
     * @param job         the registered job to run
     * @param reserved    the reserved execution
     * @param executionId the reserved execution's identifier
     * @throws LaunchRejectedException if the workers refuse the reservation
     */
    private void dispatch(final Job job, final JobExecution reserved, final long executionId) {
        final String jobName = job.getName();
        try {
            this.launchWorkers.execute(() -> run(job, reserved, jobName, executionId));
        } catch (final RejectedExecutionException refused) {
            failUndispatchedReservation(reserved, jobName, executionId, refused);
            throw new LaunchRejectedException(RejectionReason.ACTIVE_EXECUTION, refused);
        }
        LOG.info("Batch launch dispatched: job={} jobExecutionId={} activeLaunches={} capacity={}",
                jobName, Long.valueOf(executionId),
                Integer.valueOf(this.launchWorkers.getActiveCount()),
                Integer.valueOf(MAX_CONCURRENT_LAUNCHES));
    }

    /**
     * Runs one reserved execution on a worker thread.
     *
     * <p>Nothing is translated and nothing is rethrown. The framework records a step or job failure in the
     * execution itself, which is where the status surface reads it from, so there is no caller left to
     * raise anything to. A failure that escapes {@code Job.execute} entirely would otherwise reach the
     * thread's default handler and be reported without either identifier, so it is logged here with both
     * and with a bounded failure chain, and the execution is marked failed if the framework left it
     * running.
     *
     * @param job         the registered job
     * @param reserved    the reserved execution
     * @param jobName     the job's registered name, for diagnostics
     * @param executionId the execution identifier, for diagnostics
     */
    private void run(final Job job, final JobExecution reserved, final String jobName,
            final long executionId) {
        try {
            job.execute(reserved);
        } catch (final RuntimeException escaped) {
            LOG.error("Batch launch failed outside the framework's own reporting: job={}"
                            + " jobExecutionId={} failureChain={}", jobName,
                    Long.valueOf(executionId), FailureDiagnostics.failureChainOf(escaped));
            markFailed(reserved, jobName, executionId,
                    "The execution ended on a failure that escaped the job itself.");
        }
    }

    /**
     * Marks a reservation the workers refused as a failed execution.
     *
     * @param reserved    the reserved execution
     * @param jobName     the job's registered name, for diagnostics
     * @param executionId the execution identifier, for diagnostics
     * @param refused     the refusal, reported as a bounded failure chain
     */
    private void failUndispatchedReservation(final JobExecution reserved, final String jobName,
            final long executionId, final RejectedExecutionException refused) {

        LOG.warn("Batch launch refused by the bounded workers, so the reservation is failed rather than"
                        + " left started: job={} jobExecutionId={} capacity={} failureChain={}",
                jobName, Long.valueOf(executionId), Integer.valueOf(MAX_CONCURRENT_LAUNCHES),
                FailureDiagnostics.failureChainOf(refused));
        markFailed(reserved, jobName, executionId, DISPATCH_REFUSED_EXIT_DESCRIPTION);
    }

    /**
     * Records a reserved execution as failed and ended, so no execution is left recorded as running that
     * nothing is going to advance.
     *
     * <p>Best effort by design: this runs when something has already gone wrong, and a store that cannot
     * take the update must not replace that diagnosis with one about the update. The failure to record is
     * logged and absorbed.
     *
     * @param reserved        the reserved execution
     * @param jobName         the job's registered name, for diagnostics
     * @param executionId     the execution identifier, for diagnostics
     * @param exitDescription the authored description to record on the exit status
     */
    private void markFailed(final JobExecution reserved, final String jobName, final long executionId,
            final String exitDescription) {
        try {
            reserved.setStatus(BatchStatus.FAILED);
            reserved.setExitStatus(ExitStatus.FAILED.addExitDescription(exitDescription));
            if (reserved.getEndTime() == null) {
                reserved.setEndTime(LocalDateTime.now());
            }
            this.jobRepository.update(reserved);
        } catch (final RuntimeException notRecorded) {
            LOG.error("The failed state of a batch execution could not be recorded: job={}"
                            + " jobExecutionId={} failureChain={}", jobName,
                    Long.valueOf(executionId), FailureDiagnostics.failureChainOf(notRecorded));
        }
    }

    /**
     * Stops accepting new launches, leaving those already dispatched to finish.
     *
     * <p>Called by the container as this bean's inferred destroy method. Nothing is cancelled and nothing
     * is waited for: a job that has begun writing should finish rather than be cut off, and the workers
     * are not daemon threads, so the process stays alive until they are done without the context close
     * having to block on them. What closing does change is that a launch arriving afterwards is refused by
     * the workers and its reservation is failed rather than left recorded as started.
     */
    @Override
    public void close() {
        this.launchWorkers.shutdown();
        final int queuedButNotStarted = this.launchWorkers.getQueue().size();
        LOG.info("Batch launch workers stopped accepting launches: active={} queued={}",
                Integer.valueOf(this.launchWorkers.getActiveCount()),
                Integer.valueOf(queuedButNotStarted));
    }

    /**
     * Reserves one execution behind the database lock, retrying once if the store reports a transient
     * concurrency failure and refusing the launch as an active execution if it reports one again.
     *
     * <p><strong>Why a retry belongs here.</strong> The framework creates its instance and execution rows
     * in its own transaction at serializable isolation. Two launches that arrive together can therefore
     * be cancelled by the store as a serialization pivot even though neither did anything wrong, and the
     * store says so itself - PostgreSQL's own hint on that error is that the transaction might succeed if
     * retried. One retry is enough: the reservation is short, the advisory lock is released by the
     * rollback before the retry begins, and the competing reservation has finished by then.
     *
     * <p><strong>Why the second failure is a refusal and not an internal error.</strong> A second
     * serialization failure on a reservation this short means another launch of the same job is genuinely
     * in flight, which is precisely {@link RejectionReason#ACTIVE_EXECUTION} - the same outcome the
     * caller receives when the advisory lock is busy or the framework reports an execution already
     * running. Surfacing the JDBC exception instead published a driver-level message as if the service
     * had malfunctioned, when the correct answer to the caller is "not now". A failure that is not
     * transient is still an internal error and is still reported as one. See
     * {@code docs/decision-log.md} entry DL-218.
     *
     * @param  job              the registered job to reserve an execution for
     * @param  callerParameters the allow-listed caller parameters
     * @return the reserved execution, never {@code null}
     * @throws LaunchRejectedException if the launch is refused, including a repeated transient conflict
     * @throws IllegalStateException   if the guard fails for a reason retrying cannot resolve
     */
    private JobExecution reserveWithDatabaseLock(
            final Job job, final Map<String, String> callerParameters) {
        final String jobName = Objects.requireNonNull(job.getName(), "job name must not be null");
        TransientDataAccessException lastTransientFailure = null;
        for (int attempt = 1; attempt <= RESERVATION_ATTEMPTS; attempt++) {
            try {
                final JobExecution reserved = this.jdbcOperations.execute(
                        (ConnectionCallback<JobExecution>) connection ->
                                reserveOnConnection(
                                        connection, job, callerParameters, jobName));
                return Objects.requireNonNull(reserved,
                        "JDBC callback returned no reserved execution");
            } catch (final TransientDataAccessException conflict) {
                lastTransientFailure = conflict;
                LOG.info("Launch reservation for job {} met a transient store conflict on attempt {} of"
                                + " {}; conflict={}", jobName, Integer.valueOf(attempt),
                        Integer.valueOf(RESERVATION_ATTEMPTS), conflict.getClass().getSimpleName());
            } catch (final DataAccessException failure) {
                throw new IllegalStateException(
                        "the database-backed batch launch guard could not be completed", failure);
            }
        }
        throw new LaunchRejectedException(RejectionReason.ACTIVE_EXECUTION, lastTransientFailure);
    }

    private JobExecution reserveOnConnection(
            final Connection connection,
            final Job job,
            final Map<String, String> callerParameters,
            final String jobName) throws SQLException {
        connection.setAutoCommit(false);
        try {
            if (!tryAcquire(connection, LOCK_NAMESPACE + jobName)) {
                throw new LaunchRejectedException(RejectionReason.ACTIVE_EXECUTION, null);
            }
            final JobExecution reserved = reserveExecution(job, callerParameters);
            connection.commit();
            return reserved;
        } catch (final SQLException failure) {
            rollback(connection, failure);
            throw failure;
        } catch (final RuntimeException failure) {
            rollback(connection, failure);
            throw failure;
        }
    }

    private JobExecution reserveExecution(
            final Job job, final Map<String, String> callerParameters) {
        final String jobName = job.getName();
        if (!this.jobExplorer.findRunningJobExecutions(jobName).isEmpty()) {
            throw new LaunchRejectedException(RejectionReason.ACTIVE_EXECUTION, null);
        }

        final JobParameters launchParameters = nextLaunchParameters(job, callerParameters);
        try {
            Objects.requireNonNull(
                    job.getJobParametersValidator(),
                    "registered batch job has no parameter validator")
                    .validate(launchParameters);
            return this.jobRepository.createJobExecution(jobName, launchParameters);
        } catch (final JobExecutionAlreadyRunningException runningExecution) {
            throw new LaunchRejectedException(RejectionReason.ACTIVE_EXECUTION, runningExecution);
        } catch (final JobInstanceAlreadyCompleteException duplicate) {
            throw new LaunchRejectedException(RejectionReason.INSTANCE_ALREADY_EXISTS, duplicate);
        } catch (final JobRestartException restartRejected) {
            throw new LaunchRejectedException(
                    RejectionReason.INSTANCE_ALREADY_EXISTS, restartRejected);
        } catch (final JobParametersInvalidException invalid) {
            throw new LaunchRejectedException(RejectionReason.INVALID_PARAMETERS, invalid);
        }
    }

    private static boolean tryAcquire(final Connection connection, final String lockName)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TRY_LOCK_SQL)) {
            statement.setString(1, lockName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static void rollback(final Connection connection, final Throwable primary) {
        try {
            connection.rollback();
        } catch (final SQLException rollbackFailure) {
            primary.addSuppressed(rollbackFailure);
        }
    }

    private JobParameters nextLaunchParameters(
            final Job job, final Map<String, String> callerParameters) {
        final JobParametersIncrementer incrementer = job.getJobParametersIncrementer();
        if (incrementer == null) {
            throw new IllegalStateException(
                    "registered batch job has no server-side parameter incrementer");
        }

        final JobParameters previous = previousParameters(job.getName());
        final JobParameters advanced = Objects.requireNonNull(
                incrementer.getNext(previous), "job incrementer returned no parameters");
        final Long runId = advanced.getLong(SERVER_RUN_ID_PARAMETER);
        if (runId == null || runId.longValue() < 1L) {
            throw new IllegalStateException(
                    "job incrementer did not produce a positive server run identifier");
        }

        final JobParametersBuilder next = new JobParametersBuilder();
        callerParameters.forEach((name, value) -> next.addString(name, value, true));
        next.addLong(SERVER_RUN_ID_PARAMETER, runId, true);
        return next.toJobParameters();
    }

    private JobParameters previousParameters(final String jobName) {
        final JobInstance previousInstance = this.jobExplorer.getLastJobInstance(jobName);
        if (previousInstance == null) {
            return new JobParameters();
        }
        final JobExecution previousExecution =
                this.jobExplorer.getLastJobExecution(previousInstance);
        return previousExecution == null
                ? new JobParameters()
                : previousExecution.getJobParameters();
    }
}
