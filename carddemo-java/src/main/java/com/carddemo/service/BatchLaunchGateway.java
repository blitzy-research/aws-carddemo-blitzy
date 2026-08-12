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

import java.util.Map;
import java.util.Objects;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;

/**
 * Service-owned port for one guarded on-demand batch launch.
 *
 * <p>The batch tier implements this contract, so the service and API layers can request a launch
 * without importing a batch implementation type. Caller parameters contain only the per-job names
 * admitted by {@link BatchJobCatalog}; server-owned identity and active-run coordination remain
 * behind this port.
 */
public interface BatchLaunchGateway {

    /**
     * Adapts a framework launcher, primarily for narrow tests and alternate compositions.
     *
     * <p>The production composition supplies {@code BatchLaunchCoordinator} directly. This adapter
     * retains the service port's closed refusal vocabulary while preserving the framework launcher's
     * typed string-parameter contract.
     *
     * @param jobLauncher framework launcher to adapt
     * @return guarded-launch port over that launcher
     */
    static BatchLaunchGateway from(final JobLauncher jobLauncher) {
        final JobLauncher launcher =
                Objects.requireNonNull(jobLauncher, "jobLauncher must not be null");
        return (job, callerParameters) -> {
            final JobParametersBuilder parameters = new JobParametersBuilder();
            Objects.requireNonNull(callerParameters, "callerParameters must not be null")
                    .forEach((name, value) -> parameters.addString(name, value, true));
            try {
                return Objects.requireNonNull(
                        launcher.run(job, parameters.toJobParameters()).getId(),
                        "job launcher returned an execution without an identifier");
            } catch (final JobExecutionAlreadyRunningException running) {
                throw new LaunchRejectedException(RejectionReason.ACTIVE_EXECUTION, running);
            } catch (final JobInstanceAlreadyCompleteException | JobRestartException duplicate) {
                throw new LaunchRejectedException(
                        RejectionReason.INSTANCE_ALREADY_EXISTS, duplicate);
            } catch (final JobParametersInvalidException invalid) {
                throw new LaunchRejectedException(RejectionReason.INVALID_PARAMETERS, invalid);
            }
        };
    }

    /**
     * Starts one registered job after allocating server identity and refusing overlap, and answers the
     * identifier of the execution it reserved.
     *
     * <p>Every refusal is raised before this returns, because reserving is what decides them. Whether the
     * job has <em>finished</em> when this returns is the implementation's business and callers must not
     * assume it has: the production implementation reserves synchronously and runs the job on a bounded
     * worker, so the returned identifier is normally that of an execution still in progress, and the
     * identifier is the handle for asking after its outcome. The adapter below inherits whatever timing
     * the framework launcher it wraps has been configured with.
     *
     * @param job registered job to start
     * @param callerParameters allow-listed caller-owned parameters
     * @return the framework execution identifier, valid the moment this returns
     * @throws LaunchRejectedException when the guarded launch cannot be reserved
     */
    long start(Job job, Map<String, String> callerParameters);

    /**
     * Closed refusal reasons the transport boundary may translate without exposing framework text.
     *
     * <p>Three of the four are caller-addressable states: an overlapping run of the same job, a run
     * identity that could not be advanced, and parameters the job's own validator refused. The fourth,
     * {@link #TRANSIENT_STORE_CONFLICT}, is not about the caller at all - it says the reservation itself
     * could not be committed because the metadata store cancelled it as a serialization conflict, and that
     * the same request may well succeed as submitted. It is declared separately from
     * {@link #ACTIVE_EXECUTION} because those two answers are true of different situations and lead an
     * operator to different actions: waiting for a run that exists, versus submitting the same request
     * again. Reporting one as the other told operators a job was running when none was (decision log
     * DL-364, refining DL-218).
     */
    enum RejectionReason {
        /** An execution of the same job is genuinely active, or the per-job launch guard is held. */
        ACTIVE_EXECUTION,

        /** The generated run identity already exists, so no new instance could be created for it. */
        INSTANCE_ALREADY_EXISTS,

        /** The job's own parameter validator refused the parameters supplied. */
        INVALID_PARAMETERS,

        /**
         * The reservation met a store-level serialization conflict that outlasted the bounded retries.
         *
         * <p>Retryable by nature: nothing about the request is wrong and no execution of the job is
         * running. Concurrent launches of <em>different</em> jobs contend on the framework's shared
         * metadata tables, which it writes at serializable isolation, so the store may cancel one of them
         * as a pivot - the condition PostgreSQL itself reports with the hint that a retry may succeed.
         */
        TRANSIENT_STORE_CONFLICT
    }

    /**
     * Safe launch refusal carrying only a closed reason code.
     *
     * <p>This is the only refusal type in the module and {@link RejectionReason} is the only refusal
     * vocabulary, declared exactly once. A second enum of the same constants with a subclass converting
     * between them by {@code Enum.valueOf(other.name())} would be a name-matched bridge that compiles
     * either way and fails at run time the first time one side gains a constant the other lacks. The
     * class is final and the vocabulary has one home so that bridge cannot be introduced.
     */
    final class LaunchRejectedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final RejectionReason rejectionReason;

        /**
         * Creates a refusal.
         *
         * <p>Public because the launch coordinator that raises refusals lives in another package and
         * must construct this type rather than a package-local stand-in for it.
         *
         * @param reason closed refusal reason
         * @param cause internal cause, never rendered at the transport boundary, {@code null} when the
         *     refusal has no internal cause
         */
        public LaunchRejectedException(
                final RejectionReason reason, final Throwable cause) {
            super(Objects.requireNonNull(reason, "reason must not be null").name(), cause);
            this.rejectionReason = reason;
        }

        /**
         * Returns the transport-safe refusal reason.
         *
         * @return closed refusal reason
         */
        public RejectionReason rejectionReason() {
            return this.rejectionReason;
        }
    }
}
