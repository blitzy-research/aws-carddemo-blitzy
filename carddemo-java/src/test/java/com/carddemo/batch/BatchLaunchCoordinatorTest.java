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

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.ObservationView;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.carddemo.service.BatchJobCatalog;
import com.carddemo.service.BatchLaunchGateway.LaunchRejectedException;
import com.carddemo.service.BatchLaunchGateway.RejectionReason;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies server-minted identity, the database-serialized one-active-execution boundary, and the three
 * properties of the asynchronous launch: that nominally independent jobs run CONCURRENTLY, that a dispatched
 * execution is a CHILD of the launch's observation, and that stopping is a BOUNDED drain which fails the
 * reservations it discards.
 *
 * <p>See {@code docs/decision-log.md} entry DL-337.
 */
@DisplayName("batch launch coordinator: DB lock, active guard, server run identity")
final class BatchLaunchCoordinatorTest {

    private static final String JOB_NAME = "probeJob";

    private static final long EXECUTION_ID = 41L;

    /**
     * How long a verification waits for the launch worker before it gives up.
     *
     * <p>The job no longer runs on the thread that asked for it, so a verification of the execution has to
     * allow the worker to be scheduled. Generous rather than tight: it is the point at which the test
     * concludes the dispatch never happened, not a latency the machine is asked to meet.
     */
    private static final long WORKER_TIMEOUT_MILLIS = 10_000L;

    /**
     * Slack added to the reservation's published retry budget when the elapsed time of a refusal is
     * asserted.
     *
     * <p>The property under test is that the budget is <em>closed</em> - four spaced waits and then a
     * refusal, rather than a loop that keeps trying - so the ceiling has to hold on a shared machine where a
     * sleeping thread may be scheduled late. Generous for that reason, and deliberately not a latency
     * requirement: no figure in this module asserts one.
     */
    private static final long SCHEDULING_ALLOWANCE_MILLIS = 2_000L;

    /**
     * How long a deliberately blocked execution holds its worker, in milliseconds.
     *
     * <p>Longer than {@link #WORKER_TIMEOUT_MILLIS} on purpose, and the gap is what makes a concurrency
     * assertion a measurement. If a blocked job released at the same moment an assertion gave up waiting,
     * a serial pool would sometimes let the second job start just inside the window and the test would
     * pass against the very topology it exists to refuse. A job that holds for three times the assertion
     * window cannot be mistaken for one that finished in time.
     *
     * <p>Nothing waits this long in practice: every test that blocks a job either releases it in a
     * {@code finally} or relies on the drain interrupting it, and the interruption is handled.
     */
    private static final long BLOCKED_JOB_HOLD_MILLIS = 30_000L;

    private JobRepository jobRepository;

    private JobExplorer jobExplorer;

    private javax.sql.DataSource dataSource;

    private Connection connection;

    private PreparedStatement lockStatement;

    private ResultSet lockResult;

    private BatchLaunchCoordinator coordinator;

    private ObservationRegistry observations;

    /** Reservations stubbed by {@link #blockingJob}, so a test can name the one it wants to assert on. */
    private final List<StubbedLaunch> executionsByJob = new CopyOnWriteArrayList<>();

    /** Next distinct execution identifier handed to a stubbed reservation. */
    private long nextExecutionId = 100L;

    @BeforeEach
    void setUp() throws Exception {
        this.jobRepository = mock(JobRepository.class);
        this.jobExplorer = mock(JobExplorer.class);
        this.dataSource = mock(javax.sql.DataSource.class);
        this.connection = mock(Connection.class);
        this.lockStatement = mock(PreparedStatement.class);
        this.lockResult = mock(ResultSet.class);
        when(this.dataSource.getConnection()).thenReturn(this.connection);
        when(this.connection.prepareStatement(BatchLaunchCoordinator.TRY_LOCK_SQL))
                .thenReturn(this.lockStatement);
        when(this.lockStatement.executeQuery()).thenReturn(this.lockResult);
        when(this.lockResult.next()).thenReturn(Boolean.TRUE);
        when(this.lockResult.getBoolean(1)).thenReturn(Boolean.TRUE);
        this.observations = ObservationRegistry.create();
        // A HANDLER IS REQUIRED FOR THIS TO MEASURE ANYTHING. A registry with no handler answers every
        // start with the shared no-op observation, so "the worker sees the same observation as the
        // caller" would hold whether anything was propagated or not - two references to one singleton.
        // With a handler registered the registry mints real observations and the parent edge is real.
        this.observations.observationConfig().observationHandler(context -> true);
        this.coordinator = new BatchLaunchCoordinator(
                this.jobRepository,
                this.jobExplorer,
                new JdbcTemplate(this.dataSource),
                this.observations);
    }

    @AfterEach
    void stopAcceptingLaunches() {
        this.coordinator.close();
    }

    @Test
    @DisplayName("a launch reserves metadata under the advisory lock and executes after reservation")
    void launchReservesAndExecutesWithServerIdentity() throws Exception {
        final Job job = launchableJob();
        final JobExecution reserved = reservedExecution();
        when(this.jobExplorer.findRunningJobExecutions(JOB_NAME)).thenReturn(Set.of());
        when(this.jobRepository.createJobExecution(eq(JOB_NAME), any(JobParameters.class)))
                .thenReturn(reserved);

        final long executionId = this.coordinator.start(
                job, Map.of("fixedWidth", " 2022-07-19 "));

        final ArgumentCaptor<JobParameters> parameters =
                ArgumentCaptor.forClass(JobParameters.class);
        verify(this.lockStatement).setString(
                1, BatchLaunchCoordinator.LOCK_NAMESPACE + JOB_NAME);
        verify(this.connection).setAutoCommit(false);
        verify(this.connection).commit();
        verify(this.jobRepository).createJobExecution(eq(JOB_NAME), parameters.capture());
        verify(job, timeout(WORKER_TIMEOUT_MILLIS)).execute(reserved);
        assertThat(executionId).isEqualTo(EXECUTION_ID);
        assertThat(parameters.getValue().getString("fixedWidth")).isEqualTo(" 2022-07-19 ");
        assertThat(parameters.getValue().getLong(
                BatchLaunchCoordinator.SERVER_RUN_ID_PARAMETER)).isEqualTo(1L);
    }

    @Test
    @DisplayName("the shared incrementer advances the last committed run for an exact rerun")
    void exactRerunReceivesTheNextServerIdentity() throws Exception {
        final Job job = launchableJob();
        final JobInstance previousInstance = new JobInstance(7L, JOB_NAME);
        final JobExecution previousExecution = mock(JobExecution.class);
        when(previousExecution.getJobParameters()).thenReturn(new JobParametersBuilder()
                .addLong(BatchLaunchCoordinator.SERVER_RUN_ID_PARAMETER, 7L)
                .addString("staleParameter", "must-not-survive")
                .toJobParameters());
        when(this.jobExplorer.getLastJobInstance(JOB_NAME)).thenReturn(previousInstance);
        when(this.jobExplorer.getLastJobExecution(previousInstance)).thenReturn(previousExecution);
        when(this.jobExplorer.findRunningJobExecutions(JOB_NAME)).thenReturn(Set.of());
        final JobExecution reserved = reservedExecution();
        when(this.jobRepository.createJobExecution(eq(JOB_NAME), any(JobParameters.class)))
                .thenReturn(reserved);

        this.coordinator.start(job, Map.of("currentParameter", "same-value"));

        final ArgumentCaptor<JobParameters> parameters =
                ArgumentCaptor.forClass(JobParameters.class);
        verify(this.jobRepository).createJobExecution(eq(JOB_NAME), parameters.capture());
        assertThat(parameters.getValue().getLong(
                BatchLaunchCoordinator.SERVER_RUN_ID_PARAMETER)).isEqualTo(8L);
        assertThat(parameters.getValue().getString("currentParameter")).isEqualTo("same-value");
        assertThat(parameters.getValue().getParameters()).doesNotContainKey("staleParameter");
    }

    @Test
    @DisplayName("a busy advisory lock refuses before metadata is read or written")
    void busyLockIsRefused() throws Exception {
        when(this.lockResult.getBoolean(1)).thenReturn(Boolean.FALSE);

        assertRejected(RejectionReason.ACTIVE_EXECUTION,
                () -> this.coordinator.start(launchableJob(), Map.of()));

        verifyNoInteractions(this.jobExplorer);
        verifyNoInteractions(this.jobRepository);
        verify(this.connection).rollback();
    }

    @Test
    @DisplayName("a transient store conflict on the reservation is retried and the retry stands")
    void aTransientConflictIsRetried() throws Exception {
        final Job job = launchableJob();
        final JobExecution reserved = reservedExecution();
        when(this.jobExplorer.findRunningJobExecutions(JOB_NAME)).thenReturn(Set.of());
        // The framework creates its rows in its own serializable transaction, so a conflict surfaces from
        // this call and not from the advisory lock. The first attempt is cancelled as a pivot; the second
        // succeeds, which is what PostgreSQL's own hint on that error says will happen (DL-218).
        when(this.jobRepository.createJobExecution(eq(JOB_NAME), any(JobParameters.class)))
                .thenThrow(new CannotAcquireLockException("could not serialize access"))
                .thenReturn(reserved);

        final long executionId = this.coordinator.start(job, Map.of());

        assertThat(executionId).isEqualTo(EXECUTION_ID);
        verify(this.jobRepository, times(2))
                .createJobExecution(eq(JOB_NAME), any(JobParameters.class));
        verify(this.connection).rollback();
        verify(this.connection).commit();
        verify(job, timeout(WORKER_TIMEOUT_MILLIS)).execute(reserved);
    }

    @Test
    @DisplayName("the retry budget is spent in full before a conflict is refused, because two attempts "
            + "fell inside one contention window and refused two launches in three")
    void theWholeRetryBudgetIsSpentBeforeRefusing() throws Exception {
        final Job job = launchableJob();
        final JobExecution reserved = reservedExecution();
        when(this.jobExplorer.findRunningJobExecutions(JOB_NAME)).thenReturn(Set.of());
        // Four conflicts and then success: a launch that would have been refused outright under the former
        // two-attempt budget now completes, which is the whole point of the wider one (DL-364).
        when(this.jobRepository.createJobExecution(eq(JOB_NAME), any(JobParameters.class)))
                .thenThrow(new CannotAcquireLockException("could not serialize access"))
                .thenThrow(new CannotAcquireLockException("could not serialize access"))
                .thenThrow(new CannotAcquireLockException("could not serialize access"))
                .thenThrow(new CannotAcquireLockException("could not serialize access"))
                .thenReturn(reserved);

        final long executionId = this.coordinator.start(job, Map.of());

        assertThat(executionId).isEqualTo(EXECUTION_ID);
        assertThat(BatchLaunchCoordinator.RESERVATION_ATTEMPTS)
                .as("the budget has to leave room for four retries for the case above to be reachable")
                .isEqualTo(5);
        verify(this.jobRepository, times(BatchLaunchCoordinator.RESERVATION_ATTEMPTS))
                .createJobExecution(eq(JOB_NAME), any(JobParameters.class));
        verify(job, timeout(WORKER_TIMEOUT_MILLIS)).execute(reserved);
    }

    @Test
    @DisplayName("a conflict that outlasts the budget is refused as a RETRYABLE STORE CONFLICT and never "
            + "as an active execution, because no execution of the job is running")
    void aConflictThatOutlastsTheBudgetIsRefusedAsTransient() throws Exception {
        when(this.jobExplorer.findRunningJobExecutions(JOB_NAME)).thenReturn(Set.of());
        when(this.jobRepository.createJobExecution(eq(JOB_NAME), any(JobParameters.class)))
                .thenThrow(new CannotAcquireLockException("could not serialize access"));

        // The measured defect this pins: concurrent launches of DIFFERENT jobs contend on the framework's
        // shared metadata tables, and answering ACTIVE_EXECUTION told the caller a run existed when the
        // metadata held no non-terminal row for the job at all (DL-364).
        assertRejected(RejectionReason.TRANSIENT_STORE_CONFLICT,
                () -> this.coordinator.start(launchableJob(), Map.of()));

        verify(this.jobRepository, times(BatchLaunchCoordinator.RESERVATION_ATTEMPTS))
                .createJobExecution(eq(JOB_NAME), any(JobParameters.class));
    }

    @Test
    @DisplayName("the retry budget is bounded in time as well as in count, so a refused caller waits a "
            + "published maximum rather than an unbounded one")
    void theRetryBudgetIsBoundedInTime() throws Exception {
        when(this.jobExplorer.findRunningJobExecutions(JOB_NAME)).thenReturn(Set.of());
        when(this.jobRepository.createJobExecution(eq(JOB_NAME), any(JobParameters.class)))
                .thenThrow(new CannotAcquireLockException("could not serialize access"));
        // Doubling from the base for each retry, plus jitter of at most the base on each: both figures are
        // properties of the two published constants and are computed from them rather than restated.
        final long base = BatchLaunchCoordinator.RESERVATION_RETRY_BASE_BACKOFF_MILLIS;
        final int retries = BatchLaunchCoordinator.RESERVATION_ATTEMPTS - 1;
        long deterministicWait = 0L;
        for (int retry = 1; retry <= retries; retry++) {
            deterministicWait = deterministicWait + base * (1L << (retry - 1));
        }
        final long jitterCeiling = base * retries;

        final long startedAt = System.nanoTime();
        assertRejected(RejectionReason.TRANSIENT_STORE_CONFLICT,
                () -> this.coordinator.start(launchableJob(), Map.of()));
        final long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertThat(deterministicWait)
                .as("four waits of 20, 40, 80 and 160 milliseconds")
                .isEqualTo(300L);
        assertThat(elapsedMillis)
                .as("the retries are genuinely spaced, because an immediate retry re-enters the window the "
                        + "conflict came from - which is how two attempts came to be one attempt in effect")
                .isGreaterThanOrEqualTo(deterministicWait);
        assertThat(elapsedMillis)
                .as("and the budget is closed rather than open: a refusal arrives within the published "
                        + "waits plus their jitter, allowing for scheduling on a loaded machine. The "
                        + "property under test is boundedness, not latency, so the allowance is generous "
                        + "on purpose")
                .isLessThanOrEqualTo(deterministicWait + jitterCeiling + SCHEDULING_ALLOWANCE_MILLIS);
    }

    @Test
    @DisplayName("an interruption while waiting to retry refuses the launch, restores the interrupt and "
            + "does not go on retrying")
    void anInterruptionWhileWaitingRefusesAndPreservesTheFlag() throws Exception {
        when(this.jobExplorer.findRunningJobExecutions(JOB_NAME)).thenReturn(Set.of());
        when(this.jobRepository.createJobExecution(eq(JOB_NAME), any(JobParameters.class)))
                .thenThrow(new CannotAcquireLockException("could not serialize access"));
        // The flag is set before the call, so the first wait ends at once rather than after the spacing.
        Thread.currentThread().interrupt();
        try {
            assertRejected(RejectionReason.TRANSIENT_STORE_CONFLICT,
                    () -> this.coordinator.start(launchableJob(), Map.of()));

            assertThat(Thread.currentThread().isInterrupted())
                    .as("the caller's own cancellation must survive the refusal rather than be swallowed")
                    .isTrue();
            verify(this.jobRepository, times(1))
                    .createJobExecution(eq(JOB_NAME), any(JobParameters.class));
        } finally {
            // Cleared so the flag does not travel into the next specification on this thread.
            assertThat(Thread.interrupted()).isTrue();
        }
    }

    @Test
    @DisplayName("a failure retrying cannot resolve is still an internal error, so it is not hidden "
            + "behind a refusal")
    void aNonTransientFailureIsStillAnInternalError() throws Exception {
        when(this.jobExplorer.findRunningJobExecutions(JOB_NAME)).thenReturn(Set.of());
        when(this.jobRepository.createJobExecution(eq(JOB_NAME), any(JobParameters.class)))
                .thenThrow(new DataIntegrityViolationException("metadata schema is wrong"));

        assertThatThrownBy(() -> this.coordinator.start(launchableJob(), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batch launch guard");

        verify(this.jobRepository, times(1))
                .createJobExecution(eq(JOB_NAME), any(JobParameters.class));
    }

    @Test
    @DisplayName("a running execution in framework metadata refuses a second execution")
    void runningExecutionIsRefused() throws Exception {
        when(this.jobExplorer.findRunningJobExecutions(JOB_NAME))
                .thenReturn(Set.of(mock(JobExecution.class)));

        assertRejected(RejectionReason.ACTIVE_EXECUTION,
                () -> this.coordinator.start(launchableJob(), Map.of()));

        verify(this.jobRepository, never())
                .createJobExecution(any(), any(JobParameters.class));
    }

    @Test
    @DisplayName("validator and repository refusals become closed reason codes")
    void frameworkRefusalsBecomeClosedReasons() throws Exception {
        prepareAvailableLock();
        final Job invalid = launchableJob();
        when(invalid.getJobParametersValidator()).thenReturn(parameters -> {
            throw new JobParametersInvalidException("value and internals");
        });
        assertRejected(RejectionReason.INVALID_PARAMETERS,
                () -> this.coordinator.start(invalid, Map.of()));

        final Job duplicate = launchableJob();
        when(this.jobRepository.createJobExecution(eq(JOB_NAME), any(JobParameters.class)))
                .thenThrow(new JobInstanceAlreadyCompleteException("duplicate"));
        assertRejected(RejectionReason.INSTANCE_ALREADY_EXISTS,
                () -> this.coordinator.start(duplicate, Map.of()));

        final Job running = launchableJob();
        when(this.jobRepository.createJobExecution(eq(JOB_NAME), any(JobParameters.class)))
                .thenThrow(new JobExecutionAlreadyRunningException("running"));
        assertRejected(RejectionReason.ACTIVE_EXECUTION,
                () -> this.coordinator.start(running, Map.of()));

        final Job restart = launchableJob();
        when(this.jobRepository.createJobExecution(eq(JOB_NAME), any(JobParameters.class)))
                .thenThrow(new JobRestartException("restart"));
        assertRejected(RejectionReason.INSTANCE_ALREADY_EXISTS,
                () -> this.coordinator.start(restart, Map.of()));
    }

    @Test
    @DisplayName("a missing or defective incrementer fails as infrastructure, not caller input")
    void incrementerContractIsRequired() {
        prepareAvailableLock();
        final Job absent = launchableJob();
        when(absent.getJobParametersIncrementer()).thenReturn(null);
        assertThatIllegalStateException()
                .isThrownBy(() -> this.coordinator.start(absent, Map.of()))
                .withMessageContaining("no server-side parameter incrementer");

        final Job defective = launchableJob();
        when(defective.getJobParametersIncrementer()).thenReturn(parameters -> new JobParameters());
        assertThatIllegalStateException()
                .isThrownBy(() -> this.coordinator.start(defective, Map.of()))
                .withMessageContaining("positive server run identifier");
    }

    @Test
    @DisplayName("the launch returns while the job is still running, so a long job cannot hold the "
            + "thread that asked for it")
    void theLaunchReturnsWhileTheJobIsStillRunning() throws Exception {
        final Job job = launchableJob();
        final JobExecution reserved = reservedExecution();
        final CountDownLatch jobStarted = new CountDownLatch(1);
        final CountDownLatch releaseJob = new CountDownLatch(1);
        when(this.jobExplorer.findRunningJobExecutions(JOB_NAME)).thenReturn(Set.of());
        when(this.jobRepository.createJobExecution(eq(JOB_NAME), any(JobParameters.class)))
                .thenReturn(reserved);
        org.mockito.Mockito.doAnswer(invocation -> {
            jobStarted.countDown();
            releaseJob.await(WORKER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            return null;
        }).when(job).execute(reserved);

        final long executionId = this.coordinator.start(job, Map.of());

        // The launch answered while the job was still inside execute. Under the previous inline launch this
        // could not happen: start did not return until the job had finished, so a job that reads a large
        // dataset held the caller's thread - a servlet request thread in production - for its whole
        // duration, and the identifier a client needs in order to ask after progress arrived only once
        // there was no progress left to ask about.
        assertThat(jobStarted.await(WORKER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                .as("the job must still run, just not on the caller's thread")
                .isTrue();
        assertThat(executionId)
                .as("and the identifier is answered immediately, while the run is in flight")
                .isEqualTo(EXECUTION_ID);
        releaseJob.countDown();
    }

    @Test
    @DisplayName("the worker bound is one per launchable job, derived from the inventory the guard admits "
            + "rather than chosen")
    void theWorkerBoundIsOnePerLaunchableJob() {
        assertThat(BatchLaunchCoordinator.MAX_CONCURRENT_LAUNCHES)
                .isEqualTo(BatchJobCatalog.LAUNCHABLE_JOB_NAMES.size())
                .isEqualTo(9);
        assertThat(BatchLaunchCoordinator.WORKER_KEEP_ALIVE_SECONDS).isPositive();
    }

    @Test
    @DisplayName("a reservation the workers cannot accept is failed rather than left recorded as started, "
            + "and the caller is refused")
    void anUndispatchedReservationIsFailedAndRefused() throws Exception {
        final Job job = launchableJob();
        final JobExecution reserved = reservedExecution();
        when(this.jobExplorer.findRunningJobExecutions(JOB_NAME)).thenReturn(Set.of());
        when(this.jobRepository.createJobExecution(eq(JOB_NAME), any(JobParameters.class)))
                .thenReturn(reserved);
        // Closing is the reachable way to make the workers refuse: the pool is sized to the inventory the
        // per-job guard admits, so a full queue cannot occur while the guard holds.
        this.coordinator.close();

        assertRejected(RejectionReason.ACTIVE_EXECUTION, () -> this.coordinator.start(job, Map.of()));

        verify(job, never()).execute(reserved);
        // The metadata row already exists, so leaving it started would report a run that will never
        // advance and would make the guard refuse every later launch of the same job.
        verify(reserved).setStatus(BatchStatus.FAILED);
        final ArgumentCaptor<ExitStatus> exitStatus = ArgumentCaptor.forClass(ExitStatus.class);
        verify(reserved).setExitStatus(exitStatus.capture());
        assertThat(exitStatus.getValue().getExitCode()).isEqualTo(ExitStatus.FAILED.getExitCode());
        assertThat(exitStatus.getValue().getExitDescription())
                .isEqualTo(BatchLaunchCoordinator.DISPATCH_REFUSED_EXIT_DESCRIPTION);
        verify(this.jobRepository).update(reserved);
    }

    @Test
    @DisplayName("a store that cannot record the failed reservation does not replace the refusal with an "
            + "error about the recording")
    void aStoreThatCannotRecordTheFailureStillRefuses() throws Exception {
        final Job job = launchableJob();
        final JobExecution reserved = reservedExecution();
        when(this.jobExplorer.findRunningJobExecutions(JOB_NAME)).thenReturn(Set.of());
        when(this.jobRepository.createJobExecution(eq(JOB_NAME), any(JobParameters.class)))
                .thenReturn(reserved);
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("metadata unavailable"))
                .when(this.jobRepository).update(reserved);
        this.coordinator.close();

        assertRejected(RejectionReason.ACTIVE_EXECUTION, () -> this.coordinator.start(job, Map.of()));
    }

    @Test
    @DisplayName("close is idempotent, because the container may call it after a manual close")
    void closeIsIdempotent() {
        this.coordinator.close();
        this.coordinator.close();
    }

    @Test
    @DisplayName("constructor and operation reject absent collaborators")
    void absentCollaboratorsAreRejected() {
        assertThatNullPointerException().isThrownBy(() ->
                new BatchLaunchCoordinator(null, this.jobExplorer,
                        new JdbcTemplate(this.dataSource), this.observations));
        assertThatNullPointerException().isThrownBy(() ->
                new BatchLaunchCoordinator(this.jobRepository, null,
                        new JdbcTemplate(this.dataSource), this.observations));
        assertThatNullPointerException().isThrownBy(() ->
                new BatchLaunchCoordinator(this.jobRepository, this.jobExplorer, null,
                        this.observations));
        assertThatNullPointerException().isThrownBy(() ->
                new BatchLaunchCoordinator(this.jobRepository, this.jobExplorer,
                        new JdbcTemplate(this.dataSource), null));
        assertThatNullPointerException().isThrownBy(() ->
                this.coordinator.start(null, Map.of()));
        assertThatNullPointerException().isThrownBy(() ->
                this.coordinator.start(launchableJob(), null));
    }

    @Test
    @DisplayName("two launches of different jobs run AT THE SAME TIME, because nominally independent jobs "
            + "must not queue behind one another")
    void twoLaunchesOfDifferentJobsRunConcurrently() throws Exception {
        final CountDownLatch bothStarted = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        final Job first = blockingJob("concurrentJobOne", bothStarted, release);
        final Job second = blockingJob("concurrentJobTwo", bothStarted, release);

        this.coordinator.start(first, Map.of());
        this.coordinator.start(second, Map.of());

        try {
            assertThat(bothStarted.await(WORKER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                    .as("THIS IS THE DEFECT THIS PINS. ThreadPoolExecutor.execute creates a worker only "
                            + "while the live count is below the CORE size, and QUEUES once it is at or "
                            + "above it - it grows towards the maximum only when the queue REFUSES. With "
                            + "a core of zero and a nine-slot queue the second job could not begin until "
                            + "the first had finished, so nine jobs sharing no data ran one after "
                            + "another and the pool reported one active launch throughout. Both must be "
                            + "inside execute at the same moment for this to pass")
                    .isTrue();
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("every job in the launchable inventory can be in flight at once, so the derived bound is "
            + "capacity and not a label")
    void theWholeInventoryCanBeInFlightAtOnce() throws Exception {
        final int capacity = BatchLaunchCoordinator.MAX_CONCURRENT_LAUNCHES;
        final CountDownLatch allStarted = new CountDownLatch(capacity);
        final CountDownLatch release = new CountDownLatch(1);
        for (int index = 0; index < capacity; index++) {
            this.coordinator.start(blockingJob("inventoryJob" + index, allStarted, release), Map.of());
        }

        try {
            assertThat(allStarted.await(WORKER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                    .as("the bound is one worker per launchable job precisely so that the guard's own "
                            + "ceiling of one active execution per job is reachable; a pool that cannot "
                            + "hold the inventory makes the last job wait behind the others for nothing")
                    .isTrue();
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("the dispatched execution runs INSIDE the launch's observation, so the job is a child of "
            + "the launch rather than a detached root")
    void theDispatchedExecutionIsAChildOfTheLaunch() throws Exception {
        final Job job = launchableJob();
        final JobExecution reserved = reservedExecution();
        final CountDownLatch executed = new CountDownLatch(1);
        final AtomicReference<Observation> currentOnWorker = new AtomicReference<>();
        final AtomicReference<ObservationView> parentOfChild = new AtomicReference<>();
        when(this.jobExplorer.findRunningJobExecutions(JOB_NAME)).thenReturn(Set.of());
        when(this.jobRepository.createJobExecution(eq(JOB_NAME), any(JobParameters.class)))
                .thenReturn(reserved);
        org.mockito.Mockito.doAnswer(invocation -> {
            currentOnWorker.set(this.observations.getCurrentObservation());
            final Observation child = Observation.start("carddemo.test.child", this.observations);
            parentOfChild.set(child.getContext().getParentObservation());
            child.stop();
            executed.countDown();
            return null;
        }).when(job).execute(reserved);

        final Observation launch = Observation.start("carddemo.test.launch", this.observations);
        // Scope opened and closed explicitly rather than by a resource declaration: the scope is not
        // referenced in the body, and a declared-but-unused resource is a warning this build treats as an
        // error. The finally is what matters - the scope must close whether the launch returns or raises.
        final Observation.Scope launchScope = launch.openScope();
        try {
            this.coordinator.start(job, Map.of());
        } finally {
            launchScope.close();
        }
        launch.stop();

        assertThat(executed.await(WORKER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(currentOnWorker.get())
                .as("an observation is thread-bound, so handing the execution to a plain worker severs "
                        + "it unless the submitting observation is captured on the CALLER's thread and "
                        + "reopened on the worker. Without that the job's spans form a trace of their "
                        + "own with no edge back to the request that launched it")
                .isSameAs(launch);
        assertThat(parentOfChild.get())
                .as("and the edge is a real parent-child one: anything the execution observes names the "
                        + "launch as its parent")
                .isSameAs(launch);
    }

    @Test
    @DisplayName("a launch with nothing observed still runs, because a caller with no trace context is a "
            + "normal case rather than an error")
    void aLaunchWithNoObservationStillRuns() throws Exception {
        final Job job = launchableJob();
        final JobExecution reserved = reservedExecution();
        when(this.jobExplorer.findRunningJobExecutions(JOB_NAME)).thenReturn(Set.of());
        when(this.jobRepository.createJobExecution(eq(JOB_NAME), any(JobParameters.class)))
                .thenReturn(reserved);

        assertThat(this.observations.getCurrentObservation())
                .as("nothing is current, which is what a scheduled launch or a test looks like")
                .isNull();

        this.coordinator.start(job, Map.of());

        verify(job, timeout(WORKER_TIMEOUT_MILLIS)).execute(reserved);
    }

    @Test
    @DisplayName("a stop drains what is running within its window and reports a clean drain")
    void aStopDrainsWhatIsRunningWithinItsWindow() throws Exception {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Job job = blockingJob("drainableJob", started, release);
        this.coordinator.start(job, Map.of());
        assertThat(started.await(WORKER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)).isTrue();

        // Released before the drain begins, so the window is long enough and nothing is interrupted.
        release.countDown();
        this.coordinator.drainWithin(Duration.ofMillis(WORKER_TIMEOUT_MILLIS), Duration.ofMillis(200L));

        assertThat(this.coordinator.isRunning())
                .as("a drained coordinator is no longer accepting, which is what the lifecycle reports")
                .isFalse();
        verify(this.jobRepository, never()).update(any(JobExecution.class));
    }

    @Test
    @DisplayName("a stop against a WEDGED job is bounded, and the reservation that never started is "
            + "failed rather than left recorded as running")
    void aStopAgainstAWedgedJobIsBoundedAndFailsWhatNeverStarted() throws Exception {
        final int capacity = BatchLaunchCoordinator.MAX_CONCURRENT_LAUNCHES;
        final CountDownLatch allStarted = new CountDownLatch(capacity);
        final CountDownLatch neverReleased = new CountDownLatch(1);
        final List<Job> wedged = new ArrayList<>();
        for (int index = 0; index < capacity; index++) {
            final Job job = blockingJob("wedgedJob" + index, allStarted, neverReleased);
            wedged.add(job);
            this.coordinator.start(job, Map.of());
        }
        assertThat(allStarted.await(WORKER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                .as("every worker must be occupied before a further launch can reach the queue")
                .isTrue();

        // The tenth launch has no worker to take it, so it waits in the bounded queue - the one state in
        // which a reserved execution exists in the metadata and has not begun.
        final Job queued = blockingJob("queuedJob", new CountDownLatch(1), neverReleased);
        final JobExecution queuedReservation = executionOf(queued);
        this.coordinator.start(queued, Map.of());
        verify(queued, never()).execute(queuedReservation);

        final long before = System.nanoTime();
        this.coordinator.drainWithin(Duration.ofMillis(300L), Duration.ofMillis(300L));
        final Duration elapsed = Duration.ofNanos(System.nanoTime() - before);

        assertThat(elapsed)
                .as("the drain is BOUNDED. A stop that waited indefinitely would let one wedged job hold "
                        + "a deployment open for ever, which is the failure an operator experiences as "
                        + "'it will not shut down'")
                .isLessThan(Duration.ofMillis(WORKER_TIMEOUT_MILLIS));
        verify(queuedReservation).setStatus(BatchStatus.FAILED);
        final ArgumentCaptor<ExitStatus> exitStatus = ArgumentCaptor.forClass(ExitStatus.class);
        verify(queuedReservation).setExitStatus(exitStatus.capture());
        assertThat(exitStatus.getValue().getExitDescription())
                .as("TERMINAL CLEANUP. The metadata row already exists, so leaving it started would "
                        + "report a run that nothing will advance and would make the per-job guard refuse "
                        + "every future launch of that job. The description is the shutdown one and not "
                        + "the capacity one, because an operator reading the repository afterwards has "
                        + "only this sentence to tell the two events apart")
                .isEqualTo(BatchLaunchCoordinator.SHUTDOWN_DISCARDED_EXIT_DESCRIPTION);
        verify(this.jobRepository).update(queuedReservation);
        neverReleased.countDown();
        assertThat(wedged).hasSize(capacity);
    }

    @Test
    @DisplayName("a stop is idempotent and close reaches the same drain, so a container that stops and "
            + "then destroys drains once")
    void stopIsIdempotentAndCloseReachesTheSameDrain() {
        this.coordinator.stop();

        assertThat(this.coordinator.isRunning()).isFalse();
        // Neither of these may drain again: a second shutdownNow on a terminated pool is harmless, but a
        // second terminal cleanup would re-fail reservations that were already accounted for.
        this.coordinator.stop();
        this.coordinator.close();
        this.coordinator.close();
        assertThat(this.coordinator.isRunning()).isFalse();
    }

    @Test
    @DisplayName("the lifecycle phase is below the framework's own web-server phases, so the server has "
            + "stopped accepting requests before the drain begins")
    void theLifecyclePhaseIsBelowTheWebServerPhases() {
        assertThat(this.coordinator.getPhase())
                .as("stopping runs in DESCENDING phase order. Measured against Spring Boot 3.5.16, "
                        + "graceful request shutdown is Integer.MAX_VALUE - 1024 and the container stop "
                        + "is Integer.MAX_VALUE - 2048; draining above either would leave a window in "
                        + "which a request could dispatch into a pool that had just shut down")
                .isEqualTo(BatchLaunchCoordinator.SHUTDOWN_PHASE)
                .isLessThan(Integer.MAX_VALUE - 2048);
    }

    @Test
    @DisplayName("a restart after a stop is refused rather than silently accepted, because a stopped pool "
            + "cannot take work")
    void aRestartAfterAStopIsRefused() {
        this.coordinator.start();

        this.coordinator.stop();

        assertThatIllegalStateException()
                .isThrownBy(() -> this.coordinator.start())
                .withMessageContaining("cannot be restarted");
    }

    /**
     * Builds a launchable job whose execution blocks until released, with its reservation stubbed.
     *
     * <p>Each job carries its own name and its own reserved execution, because the per-job guard is what
     * makes concurrent launches legitimate: two launches of the SAME job would be refused, so a
     * concurrency claim can only be made across distinct names.
     *
     * @param  name    the registered job name, unique per job in a test
     * @param  started counted down as the execution begins
     * @param  release awaited by the execution before it returns
     * @return the stubbed job
     * @throws Exception if the framework's checked reservation signature requires it
     */
    private Job blockingJob(final String name, final CountDownLatch started,
            final CountDownLatch release) throws Exception {
        final Job job = mock(Job.class);
        when(job.getName()).thenReturn(name);
        when(job.getJobParametersIncrementer()).thenReturn(new RunIdIncrementer());
        when(job.getJobParametersValidator()).thenReturn(parameters -> { });
        final JobExecution reserved = mock(JobExecution.class);
        this.nextExecutionId += 1L;
        when(reserved.getId()).thenReturn(Long.valueOf(this.nextExecutionId));
        this.executionsByJob.add(new StubbedLaunch(job, reserved));
        when(this.jobExplorer.findRunningJobExecutions(name)).thenReturn(Set.of());
        when(this.jobRepository.createJobExecution(eq(name), any(JobParameters.class)))
                .thenReturn(reserved);
        org.mockito.Mockito.doAnswer(invocation -> {
            started.countDown();
            try {
                release.await(BLOCKED_JOB_HOLD_MILLIS, TimeUnit.MILLISECONDS);
            } catch (final InterruptedException interrupted) {
                // A forcible drain interrupts what is still running. Restoring the flag and returning is
                // what a well-behaved job does; letting the interruption escape would reach the mock as a
                // checked exception the job signature does not declare.
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(job).execute(reserved);
        return job;
    }

    /**
     * Returns the reserved execution stubbed for one job built by {@link #blockingJob}.
     *
     * @param  job the job to look up
     * @return its reserved execution
     */
    private JobExecution executionOf(final Job job) {
        return this.executionsByJob.stream()
                .filter(stubbed -> stubbed.job() == job)
                .map(StubbedLaunch::reserved)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no reservation was stubbed for " + job.getName()));
    }

    /** One stubbed job and the reservation its launch returns. */
    private record StubbedLaunch(Job job, JobExecution reserved) {
    }

    private void prepareAvailableLock() {
        try {
            when(this.lockResult.getBoolean(1)).thenReturn(Boolean.TRUE);
        } catch (final java.sql.SQLException impossibleForMock) {
            throw new AssertionError(impossibleForMock);
        }
        when(this.jobExplorer.findRunningJobExecutions(JOB_NAME)).thenReturn(Set.of());
    }

    private static Job launchableJob() {
        final Job job = mock(Job.class);
        when(job.getName()).thenReturn(JOB_NAME);
        when(job.getJobParametersIncrementer()).thenReturn(new RunIdIncrementer());
        when(job.getJobParametersValidator()).thenReturn(parameters -> { });
        return job;
    }

    private static JobExecution reservedExecution() {
        final JobExecution execution = mock(JobExecution.class);
        when(execution.getId()).thenReturn(EXECUTION_ID);
        return execution;
    }

    private static void assertRejected(
            final RejectionReason expected,
            final org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(LaunchRejectedException.class,
                        rejected -> assertThat(rejected.rejectionReason()).isEqualTo(expected));
    }
}
