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

import java.time.LocalDateTime;
import org.springframework.batch.core.BatchStatus;
import org.springframework.context.ApplicationEvent;

/**
 * Parameter-free snapshot of one completed Spring Batch execution.
 *
 * <p>The event deliberately does not carry {@code JobParameters}, an execution context, a failure
 * exception or an exit description. Those objects can contain account identifiers, staging keys,
 * dates supplied by a caller, provider diagnostics and rendered stack traces. The SNS producer needs
 * none of them: a completion notification is an operational outcome, not a second copy of the batch
 * repository.
 */
public final class JobCompletionEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    private final String jobName;

    private final Long jobInstanceId;

    private final Long jobExecutionId;

    private final BatchStatus status;

    private final String exitCode;

    private final int stepsExecuted;

    private final LocalDateTime startedAt;

    private final LocalDateTime endedAt;

    /**
     * Creates an immutable completion snapshot.
     *
     * @param jobName       module-owned job name, or a stable unnamed marker
     * @param jobInstanceId framework job-instance identifier, possibly {@code null}
     * @param jobExecutionId framework execution identifier, possibly {@code null}
     * @param status        terminal framework status, possibly {@code null}
     * @param exitCode      terminal exit code, possibly {@code null}
     * @param stepsExecuted number of step executions recorded by the framework
     * @param startedAt     framework start time, possibly {@code null}
     * @param endedAt       framework end time, possibly {@code null}
     */
    public JobCompletionEvent(final String jobName, final Long jobInstanceId,
            final Long jobExecutionId, final BatchStatus status, final String exitCode,
            final int stepsExecuted, final LocalDateTime startedAt, final LocalDateTime endedAt) {
        super(JobCompletionEvent.class);
        if (stepsExecuted < 0) {
            throw new IllegalArgumentException(
                    "stepsExecuted must not be negative but was " + stepsExecuted);
        }
        this.jobName = jobName;
        this.jobInstanceId = jobInstanceId;
        this.jobExecutionId = jobExecutionId;
        this.status = status;
        this.exitCode = exitCode;
        this.stepsExecuted = stepsExecuted;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
    }

    public String jobName() {
        return this.jobName;
    }

    public Long jobInstanceId() {
        return this.jobInstanceId;
    }

    public Long jobExecutionId() {
        return this.jobExecutionId;
    }

    public BatchStatus status() {
        return this.status;
    }

    public String exitCode() {
        return this.exitCode;
    }

    public int stepsExecuted() {
        return this.stepsExecuted;
    }

    public LocalDateTime startedAt() {
        return this.startedAt;
    }

    public LocalDateTime endedAt() {
        return this.endedAt;
    }
}
