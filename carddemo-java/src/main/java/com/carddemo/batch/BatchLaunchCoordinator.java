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

import com.carddemo.service.BatchLaunchGateway;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;

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
 */
@Component
public final class BatchLaunchCoordinator implements BatchLaunchGateway {

    /** The identifying key produced by the shared {@code RunIdIncrementer}. */
    public static final String SERVER_RUN_ID_PARAMETER = "run.id";

    /** Namespace separating these advisory locks from every unrelated database lock user. */
    static final String LOCK_NAMESPACE = "carddemo.batch.launch:";

    /** Parameterized PostgreSQL lock acquisition; no caller value is concatenated into SQL. */
    static final String TRY_LOCK_SQL =
            "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))";

    private final JobRepository jobRepository;

    private final JobExplorer jobExplorer;

    private final JdbcOperations jdbcOperations;

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
    }

    /**
     * Starts one registered job after serializing its launch and rejecting an active execution.
     *
     * @param job registered job to launch
     * @param callerParameters allow-listed caller parameters, with values preserved byte for byte
     * @return the framework execution identifier
     * @throws LaunchRejectedException when the lock is busy, an execution is active, the generated
     *                                 instance already exists, or job validation rejects the parameters
     * @throws IllegalStateException when the registered job has no usable incrementer
     */
    @Override
    public long start(final Job job, final Map<String, String> callerParameters) {
        final Job registeredJob = Objects.requireNonNull(job, "job must not be null");
        final Map<String, String> parameters =
                Map.copyOf(Objects.requireNonNull(callerParameters,
                        "callerParameters must not be null"));
        final JobExecution reserved = reserveWithDatabaseLock(registeredJob, parameters);
        registeredJob.execute(reserved);
        return Objects.requireNonNull(
                reserved.getId(), "job repository returned an execution without an identifier");
    }

    private JobExecution reserveWithDatabaseLock(
            final Job job, final Map<String, String> callerParameters) {
        final String jobName = Objects.requireNonNull(job.getName(), "job name must not be null");
        try {
            final JobExecution reserved = this.jdbcOperations.execute(
                    (ConnectionCallback<JobExecution>) connection ->
                            reserveOnConnection(
                                    connection, job, callerParameters, jobName));
            return Objects.requireNonNull(reserved, "JDBC callback returned no reserved execution");
        } catch (final DataAccessException failure) {
            throw new IllegalStateException(
                    "the database-backed batch launch guard could not be completed", failure);
        }
    }

    private JobExecution reserveOnConnection(
            final Connection connection,
            final Job job,
            final Map<String, String> callerParameters,
            final String jobName) throws SQLException {
        connection.setAutoCommit(false);
        try {
            if (!tryAcquire(connection, LOCK_NAMESPACE + jobName)) {
                throw new LaunchRejectedException(RejectionReason.ACTIVE_EXECUTION);
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
            throw new LaunchRejectedException(RejectionReason.ACTIVE_EXECUTION);
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

    /** Stable reasons the HTTP boundary can translate without publishing framework messages. */
    public enum RejectionReason {
        ACTIVE_EXECUTION,
        INSTANCE_ALREADY_EXISTS,
        INVALID_PARAMETERS
    }

    /** A safe launch refusal carrying only a closed reason code. */
    public static final class LaunchRejectedException
            extends BatchLaunchGateway.LaunchRejectedException {

        private static final long serialVersionUID = 1L;

        private final RejectionReason reason;

        public LaunchRejectedException(final RejectionReason reason) {
            this(reason, null);
        }

        LaunchRejectedException(final RejectionReason reason, final Throwable cause) {
            super(BatchLaunchGateway.RejectionReason.valueOf(
                    Objects.requireNonNull(reason, "reason must not be null").name()), cause);
            this.reason = reason;
        }

        /**
         * Returns the closed reason code.
         *
         * @return refusal reason
         */
        public RejectionReason reason() {
            return this.reason;
        }
    }
}