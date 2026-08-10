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
import com.carddemo.util.ObservationPropagation;
import io.micrometer.observation.ObservationRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
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
import org.springframework.context.SmartLifecycle;
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
 *
 * <h2>The pool is sized core-equals-maximum, and that is the difference between concurrent and serial</h2>
 *
 * <p><strong>A core size of zero made this pool serial.</strong> {@code ThreadPoolExecutor.execute}
 * creates a worker only while the live worker count is below the <em>core</em> size; once it is at or above
 * core it <em>queues</em>, and it grows towards the maximum only when the queue <em>refuses</em> a task. So
 * with a core of zero and a nine-slot queue, the first launch created one worker and the next eight were
 * queued behind it - the second job did not begin until the first had finished. Nine jobs that share no
 * data, no table and no output ran one after another, and the "activeLaunches" line this class logs
 * reported one every time.
 *
 * <p>Core is therefore {@value #MAX_CONCURRENT_LAUNCHES} as well, so a launch creates its own worker up to
 * the derived ceiling and nominally independent jobs actually run at the same time.
 * {@code allowCoreThreadTimeOut(true)} is retained, which is what keeps that sizing free of cost: an
 * instance that launches nothing still holds <strong>no</strong> thread, and a burst does not leave nine
 * threads parked for the life of the process. The bounded queue stays, and under this sizing it is reached
 * only when all {@value #MAX_CONCURRENT_LAUNCHES} workers are busy - which the per-job reservation makes
 * unreachable - so it is a backstop rather than a path.
 *
 * <h2>The worker runs inside the caller's observation</h2>
 *
 * <p>An observation, and so a trace context, is thread-bound, and handing work to a plain executor severs
 * it. Without propagation the job execution became a <em>detached root</em>: a trace of its own, with no
 * edge back to the request that launched it, so an operator holding the trace of a launch could not follow
 * it into the work it caused - which is the one question a launch trace exists to answer. The submitting
 * observation is therefore captured on the caller's thread by
 * {@link ObservationPropagation#inCurrentObservation} and reopened around the work on the worker, so the
 * execution is a child of the launch. Nothing about the pool changes to achieve it: a context-propagating
 * executor would have altered the queue, ceiling and rejection semantics above as a side effect of fixing
 * tracing.
 *
 * <h2>Shutdown is a bounded managed drain, not a bare stop</h2>
 *
 * <p>Stopping is a {@link SmartLifecycle} concern rather than a destruction concern, because destruction
 * runs after the collaborators this class needs in order to finish tidily. The phase is
 * {@value #SHUTDOWN_PHASE}, which is below both of the framework's own web-server lifecycle phases -
 * measured at {@code Integer.MAX_VALUE - 1024} for graceful request shutdown and
 * {@code Integer.MAX_VALUE - 2048} for the container stop against Spring Boot 3.5.16 - and stopping runs in
 * <em>descending</em> phase order, so by the time the drain begins the server has already stopped accepting
 * the requests that would launch anything new. It is far above the ordinary phase of a non-smart lifecycle
 * bean, and singleton destruction happens later still, so the job repository is available throughout.
 *
 * <p>The drain has three steps and each is bounded. New launches stop being accepted; work already
 * dispatched is waited for, for at most {@value #DRAIN_TIMEOUT_SECONDS} seconds; and if the window expires
 * the pool is stopped forcibly, which discards whatever was queued and interrupts what was running. The
 * discarded reservations definitively never ran, so each is marked failed with an authored exit
 * description - without that <em>terminal cleanup</em> the metadata would keep a row recorded as started
 * that nothing will ever advance, the status surface would report it running for ever, and the per-job
 * guard would refuse every future launch of that job because it would read that row as an active
 * execution. {@link #close()} delegates to the same drain so a container that destroys without stopping,
 * and a caller that closes twice, both behave identically.
 *
 * <p>See {@code docs/decision-log.md} entries DL-217 for the asynchronous launch this refines, DL-305 for
 * the propagation utility, and DL-337 for the pool sizing, the propagation and the managed drain recorded
 * together.
 */
@Component
public final class BatchLaunchCoordinator
        implements BatchLaunchGateway, SmartLifecycle, AutoCloseable {

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
     * performance setting: paired with {@code allowCoreThreadTimeOut(true)} it applies to <em>every</em>
     * worker including the core ones, so an instance that launches nothing holds no thread at all and a
     * burst of launches does not leave {@value #MAX_CONCURRENT_LAUNCHES} threads parked for the life of the
     * process. That pairing is what makes a core size equal to the maximum free of standing cost.
     */
    static final long WORKER_KEEP_ALIVE_SECONDS = 60L;

    /**
     * How long a managed stop waits for dispatched executions to finish, in seconds.
     *
     * <p>Bounded on purpose, and this is the figure the bound is set to rather than a tuning knob. A stop
     * that waited indefinitely would let one wedged job hold a deployment open for ever, which is the
     * failure an operator experiences as "it will not shut down"; a stop that waited for nothing would cut
     * off a job midway through writing whenever the process was asked to stop. Thirty seconds is long
     * enough for a chunk to commit and a step to close its resources, and short enough that a wedged
     * execution is escalated rather than waited on.
     */
    static final long DRAIN_TIMEOUT_SECONDS = 30L;

    /**
     * How long a forcible stop waits after interrupting, in seconds.
     *
     * <p>Only reached when the drain window above has already expired. It exists so that the interruption
     * has a chance to take effect before this class reports what is still running: a report written
     * immediately after the interrupt would name threads that were about to finish anyway.
     */
    static final long TERMINATION_GRACE_SECONDS = 5L;

    /**
     * The lifecycle phase this coordinator stops at.
     *
     * <p>Stopping runs in <strong>descending</strong> phase order. This value is below the framework's own
     * web-server phases - measured against Spring Boot 3.5.16 as {@code Integer.MAX_VALUE - 1024} for
     * graceful request shutdown and {@code Integer.MAX_VALUE - 2048} for the container stop - so the server
     * has stopped accepting the requests that launch jobs before the drain begins. Draining first would
     * leave a window in which a request could reserve and dispatch an execution into a pool that had just
     * been shut down, whose reservation would then be failed for a capacity reason it did not have.
     */
    static final int SHUTDOWN_PHASE = Integer.MAX_VALUE - 4096;

    /**
     * The exit description recorded on a reservation that could not be handed to a worker.
     *
     * <p>Authored here rather than taken from a framework message, because it is written to the job
     * repository and read by an operator.
     */
    static final String DISPATCH_REFUSED_EXIT_DESCRIPTION =
            "The reserved execution was never started because the batch launch workers could not accept"
                    + " it; no step of this execution ran.";

    /**
     * The exit description recorded on a reservation discarded by a forcible stop.
     *
     * <p>Distinct from {@link #DISPATCH_REFUSED_EXIT_DESCRIPTION} because the two are different operational
     * events with different remedies: a refusal means the workers were saturated, and this means the
     * application was stopping. An operator reading the repository afterwards needs to be able to tell them
     * apart, and a shared sentence would hide the distinction the repository is the only record of.
     */
    static final String SHUTDOWN_DISCARDED_EXIT_DESCRIPTION =
            "The reserved execution was never started because the application stopped before a batch"
                    + " launch worker took it; no step of this execution ran.";

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
     * closes - and with core timeout allowed, none exists until a job is dispatched and none survives
     * idleness.
     */
    private final ThreadPoolExecutor launchWorkers;

    /**
     * The registry the submitting thread's observation is read from.
     *
     * <p>Injected rather than resolved statically, so a context with tracing disabled supplies the
     * framework's no-op registry and this class behaves identically with nothing to carry.
     */
    private final ObservationRegistry observationRegistry;

    /** Serial number for worker thread names, so a stack trace names which worker it was on. */
    private final AtomicLong workerNumber = new AtomicLong();

    /**
     * Whether this coordinator is accepting launches, as {@link SmartLifecycle} reads it.
     *
     * <p>Held separately from the pool's own {@code isShutdown} because the two answer different questions:
     * the pool is shut down once a drain has begun, while this records whether the lifecycle has been
     * stopped at all - which is what makes {@link #stop()} and {@link #close()} idempotent without either
     * having to inspect the pool's internal state.
     */
    private volatile boolean accepting = true;

    /**
     * Creates the coordinator over framework metadata and the application transaction boundary.
     *
     * @param jobRepository framework metadata writer used to reserve the next execution
     * @param jobExplorer framework metadata reader used to advance the server run identifier
     * @param jdbcOperations configured PostgreSQL access used for the short advisory-lock transaction
     * @param observationRegistry registry the submitting thread's observation is carried from, so a
     *                            dispatched execution is a child of the launch rather than a detached root
     */
    public BatchLaunchCoordinator(
            final JobRepository jobRepository,
            final JobExplorer jobExplorer,
            final JdbcOperations jdbcOperations,
            final ObservationRegistry observationRegistry) {
        this.jobRepository = Objects.requireNonNull(
                jobRepository, "jobRepository must not be null");
        this.jobExplorer = Objects.requireNonNull(jobExplorer, "jobExplorer must not be null");
        this.jdbcOperations = Objects.requireNonNull(
                jdbcOperations, "jdbcOperations must not be null");
        this.observationRegistry = Objects.requireNonNull(
                observationRegistry, "observationRegistry must not be null");
        this.launchWorkers = new ThreadPoolExecutor(
                MAX_CONCURRENT_LAUNCHES, MAX_CONCURRENT_LAUNCHES,
                WORKER_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_CONCURRENT_LAUNCHES),
                runnable -> new Thread(runnable,
                        "batch-launch-" + this.workerNumber.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy());
        // CORE EQUALS MAXIMUM, AND THE TIMEOUT IS WHAT MAKES THAT FREE. execute() creates a worker only
        // while the live count is below CORE; at or above core it queues, and it grows towards the maximum
        // only once the queue REFUSES. A core of zero therefore ran one launch at a time and queued the
        // rest behind it. With core at the ceiling every launch gets its own worker, and with core timeout
        // allowed an idle instance still holds no thread at all.
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
        // Captured HERE, on the caller's thread, because by the time a worker runs the task this thread has
        // moved on and its observation is current nowhere. Wrapping the work rather than the executor keeps
        // the queue, ceiling and rejection semantics documented above exactly as they are.
        final Runnable observed = ObservationPropagation.inCurrentObservation(
                this.observationRegistry, () -> run(job, reserved, jobName, executionId));
        try {
            this.launchWorkers.execute(
                    new ReservedLaunch(reserved, jobName, executionId, observed));
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
     * Reports the phase this coordinator stops at, which is below the framework's own web-server phases.
     *
     * @return {@value #SHUTDOWN_PHASE}
     */
    @Override
    public int getPhase() {
        return SHUTDOWN_PHASE;
    }

    /**
     * Reports whether launches are still being accepted.
     *
     * <p>Answers the lifecycle's question and not the pool's: it is {@code false} from the moment a stop
     * begins, which is what lets a container call {@link #stop()} and then {@link #close()} without the
     * drain running twice.
     *
     * @return {@code true} until a stop has begun
     */
    @Override
    public boolean isRunning() {
        return this.accepting;
    }

    /**
     * Starts accepting launches, which is the state this bean is constructed in.
     *
     * <p>Present because the lifecycle contract requires it, and deliberately not a way to resume after a
     * stop: a stopped pool cannot be restarted, and pretending otherwise would let a caller believe a
     * launch would be accepted when the workers would refuse it. A start after a stop is therefore refused
     * rather than silently ignored.
     *
     * @throws IllegalStateException when called after a stop, because the workers cannot be restarted
     */
    @Override
    public void start() {
        if (!this.accepting) {
            throw new IllegalStateException("batch launch workers cannot be restarted once stopped;"
                    + " a new application context is required");
        }
    }

    /**
     * Stops accepting launches and drains what is already dispatched, within a bounded window.
     *
     * <p>Delegates to {@link #drainWithin(Duration, Duration)} with the published windows. Idempotent: a
     * second call finds the lifecycle already stopped and returns without touching the pool again.
     */
    @Override
    public void stop() {
        drainWithin(Duration.ofSeconds(DRAIN_TIMEOUT_SECONDS),
                Duration.ofSeconds(TERMINATION_GRACE_SECONDS));
    }

    /**
     * Runs the same drain as {@link #stop()}, so a container that destroys without stopping still drains.
     *
     * <p>Kept alongside the lifecycle rather than replaced by it. The lifecycle is what runs at the right
     * moment in a managed context; this is what a test, or a container that only calls the inferred destroy
     * method, gets. Both reach one routine, so there is one drain and one set of semantics to reason about.
     */
    @Override
    public void close() {
        stop();
    }

    /**
     * Stops accepting launches and drains the workers within the given windows.
     *
     * <p>Three bounded steps:
     *
     * <ol>
     *   <li><strong>Stop accepting.</strong> {@code shutdown()} lets everything already dispatched -
     *       running and queued alike - proceed, and refuses anything new.</li>
     *   <li><strong>Wait, for at most {@code drainWindow}.</strong> A job that has begun writing gets the
     *       chance to commit its chunk and close its resources.</li>
     *   <li><strong>Escalate, once.</strong> If the window expires, {@code shutdownNow()} discards what is
     *       still queued and interrupts what is still running. The discarded tasks are the ones that
     *       definitively never ran, and each carries its own reservation, so each is marked failed - the
     *       terminal cleanup without which the metadata would keep an execution recorded as started that
     *       nothing will advance, and the per-job guard would refuse every future launch of that job. What
     *       was interrupted is left to the framework's own failure recording, which owns those rows and is
     *       writing to them concurrently; this method reports what is still running rather than racing
     *       it.</li>
     * </ol>
     *
     * <p>Package-visible with explicit windows so the bounded behaviour can be <em>measured</em> with a
     * short window instead of by making a test wait {@value #DRAIN_TIMEOUT_SECONDS} seconds. Production
     * always enters through {@link #stop()} and always uses the published figures.
     *
     * @param drainWindow  how long to wait for dispatched executions to finish; must not be {@code null}
     * @param graceWindow  how long to wait after interrupting; must not be {@code null}
     */
    void drainWithin(final Duration drainWindow, final Duration graceWindow) {
        Objects.requireNonNull(drainWindow, "drainWindow must not be null");
        Objects.requireNonNull(graceWindow, "graceWindow must not be null");
        if (!this.accepting) {
            return;
        }
        this.accepting = false;

        this.launchWorkers.shutdown();
        LOG.info("Batch launch workers stopped accepting launches, draining: active={} queued={}"
                        + " drainMillis={}",
                Integer.valueOf(this.launchWorkers.getActiveCount()),
                Integer.valueOf(this.launchWorkers.getQueue().size()),
                Long.valueOf(drainWindow.toMillis()));

        if (awaitTermination(drainWindow)) {
            LOG.info("Batch launch workers drained cleanly; every dispatched execution finished");
            return;
        }

        final List<Runnable> discarded = this.launchWorkers.shutdownNow();
        LOG.warn("Batch launch workers did not drain within {}ms, so the remainder is being stopped:"
                        + " interrupted={} discarded={}",
                Long.valueOf(drainWindow.toMillis()),
                Integer.valueOf(this.launchWorkers.getActiveCount()),
                Integer.valueOf(discarded.size()));
        failDiscardedReservations(discarded);

        if (!awaitTermination(graceWindow) && this.launchWorkers.getActiveCount() > 0) {
            LOG.error("Batch launch workers are still running {}ms after being interrupted; their"
                            + " executions remain as the framework last recorded them",
                    Long.valueOf(graceWindow.toMillis()));
        }
    }

    /**
     * Waits for the pool to terminate, treating an interruption as a failure to drain.
     *
     * <p>The interrupt flag is restored rather than swallowed, because the thread being interrupted here is
     * the container's shutdown thread and whatever asked it to stop is entitled to see that it was asked.
     *
     * @param  window how long to wait
     * @return {@code true} when the pool terminated inside the window
     */
    private boolean awaitTermination(final Duration window) {
        try {
            return this.launchWorkers.awaitTermination(window.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return this.launchWorkers.isTerminated();
        }
    }

    /**
     * Marks every reservation a forcible stop discarded as a failed execution.
     *
     * <p>Normally there is nothing to do, and that is the point of doing it anyway: with the pool sized to
     * the launchable inventory and the reservation admitting one execution per job, the queue is
     * unreachable, so this list is empty on every ordinary stop. It is the path that keeps the metadata
     * honest in the case the sizing argument is wrong.
     *
     * @param discarded the tasks the pool returned as never commenced
     */
    private void failDiscardedReservations(final List<Runnable> discarded) {
        for (final Runnable task : discarded) {
            if (task instanceof final ReservedLaunch launch) {
                LOG.warn("A reserved execution was discarded by shutdown, so it is failed rather than"
                                + " left started: job={} jobExecutionId={}",
                        launch.jobName(), Long.valueOf(launch.executionId()));
                markFailed(launch.reserved(), launch.jobName(), launch.executionId(),
                        SHUTDOWN_DISCARDED_EXIT_DESCRIPTION);
            }
        }
    }

    /**
     * One dispatched launch, carrying the reservation it belongs to.
     *
     * <p>A plain lambda would have been enough to run the work, and it is deliberately not used: the pool
     * hands back the tasks a forcible stop discarded, and a lambda hands back nothing identifiable. Carrying
     * the reservation and its identifiers on the task is what lets the drain above name the executions that
     * never ran and fail them, rather than leaving rows recorded as started.
     *
     * @param reserved    the reserved execution this task would have run
     * @param jobName     the job's registered name, for diagnostics
     * @param executionId the reserved execution's identifier, for diagnostics
     * @param work        the work to run, already wrapped in the submitting observation
     */
    private record ReservedLaunch(
            JobExecution reserved, String jobName, long executionId, Runnable work)
            implements Runnable {

        @Override
        public void run() {
            this.work.run();
        }
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
