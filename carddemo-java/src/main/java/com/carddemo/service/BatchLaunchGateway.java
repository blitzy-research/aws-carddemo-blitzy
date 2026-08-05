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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
     * Starts one registered job after allocating server identity and refusing overlap.
     *
     * @param job registered job to start
     * @param callerParameters allow-listed caller-owned parameters
     * @return the framework execution identifier
     * @throws LaunchRejectedException when the guarded launch cannot be reserved
     */
    long start(Job job, Map<String, String> callerParameters);

    /** Closed refusal reasons the transport boundary may translate without exposing framework text. */
    enum RejectionReason {
        ACTIVE_EXECUTION,
        INSTANCE_ALREADY_EXISTS,
        INVALID_PARAMETERS
    }

    /** Safe launch refusal carrying only a closed reason code. */
    class LaunchRejectedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final RejectionReason rejectionReason;

        /**
         * Creates a refusal.
         *
         * @param reason closed refusal reason
         * @param cause internal cause, never rendered at the transport boundary
         */
        protected LaunchRejectedException(
                final RejectionReason reason, final Throwable cause) {
            super(Objects.requireNonNull(reason, "reason must not be null").name(), cause);
            this.rejectionReason = reason;
        }

        /**
         * Returns the transport-safe refusal reason.
         *
         * @return closed refusal reason
         */
        public final RejectionReason rejectionReason() {
            return this.rejectionReason;
        }
    }
}