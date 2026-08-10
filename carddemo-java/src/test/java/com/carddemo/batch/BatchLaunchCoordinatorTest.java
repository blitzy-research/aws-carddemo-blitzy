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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
 * Verifies server-minted identity and the database-serialized one-active-execution boundary.
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

    private JobRepository jobRepository;

    private JobExplorer jobExplorer;

    private javax.sql.DataSource dataSource;

    private Connection connection;

    private PreparedStatement lockStatement;

    private ResultSet lockResult;

    private BatchLaunchCoordinator coordinator;

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
        this.coordinator = new BatchLaunchCoordinator(
                this.jobRepository,
                this.jobExplorer,
                new JdbcTemplate(this.dataSource));
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
    @DisplayName("a transient store conflict on the reservation is retried once and the retry stands")
    void aTransientConflictIsRetriedOnce() throws Exception {
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
        verify(this.jobRepository, times(BatchLaunchCoordinator.RESERVATION_ATTEMPTS))
                .createJobExecution(eq(JOB_NAME), any(JobParameters.class));
        verify(this.connection).rollback();
        verify(this.connection).commit();
        verify(job, timeout(WORKER_TIMEOUT_MILLIS)).execute(reserved);
    }

    @Test
    @DisplayName("a repeated transient conflict is the active-execution refusal and never a JDBC error")
    void aRepeatedTransientConflictIsRefused() throws Exception {
        when(this.jobExplorer.findRunningJobExecutions(JOB_NAME)).thenReturn(Set.of());
        when(this.jobRepository.createJobExecution(eq(JOB_NAME), any(JobParameters.class)))
                .thenThrow(new CannotAcquireLockException("could not serialize access"));

        assertRejected(RejectionReason.ACTIVE_EXECUTION,
                () -> this.coordinator.start(launchableJob(), Map.of()));

        verify(this.jobRepository, times(BatchLaunchCoordinator.RESERVATION_ATTEMPTS))
                .createJobExecution(eq(JOB_NAME), any(JobParameters.class));
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
                new BatchLaunchCoordinator(
                        null, this.jobExplorer, new JdbcTemplate(this.dataSource)));
        assertThatNullPointerException().isThrownBy(() ->
                new BatchLaunchCoordinator(
                        this.jobRepository, null, new JdbcTemplate(this.dataSource)));
        assertThatNullPointerException().isThrownBy(() ->
                new BatchLaunchCoordinator(this.jobRepository, this.jobExplorer, null));
        assertThatNullPointerException().isThrownBy(() ->
                this.coordinator.start(null, Map.of()));
        assertThatNullPointerException().isThrownBy(() ->
                this.coordinator.start(launchableJob(), null));
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
