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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
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
 *
 * <h2>The two timestamps are instants, and that is a correctness property rather than a style choice</h2>
 *
 * <p>Spring Batch records both boundaries by calling {@code LocalDateTime.now()} with no argument, so
 * the values on a {@code JobExecution} are wall-clock readings in the default zone of whichever JVM ran
 * the job. Carrying them onward as {@code LocalDateTime} therefore carried a number with no meaning
 * attached: two replicas configured in different zones produce completion notices that cannot be put
 * in order, and a notice produced either side of a daylight-saving transition is ambiguous against its
 * own neighbours. The consumer of a completion notification is an operator or a subscriber trying to
 * establish what happened when, relative to something else, so the field has to be a point in time.
 *
 * <p>The conversion needs the zone that produced the reading, which is why it lives in
 * {@link #ofFrameworkExecution} rather than anywhere downstream: the producing JVM is the only party
 * that knows. Note in particular that the module's own {@code Clock} bean is <em>not</em> that zone -
 * it is deliberately fixed to UTC so emitted timestamp images are stable - so interpreting a framework
 * wall-clock reading against it would be wrong wherever a deployment is not itself running in UTC.
 *
 * <p>See {@code docs/decision-log.md} entry DL-306.
 */
public final class JobCompletionEvent extends ApplicationEvent {

    /**
     * Bumped from one because the two timestamp fields changed type incompatibly.
     *
     * <p>The event travels only between beans inside one JVM, so nothing serializes it in practice; the
     * declaration exists because the compiler requires one of a serializable class under
     * {@code -Xlint:all -Werror}, and it is advanced rather than left alone so that the declaration
     * stays an honest statement about the shape of the class.
     */
    private static final long serialVersionUID = 2L;

    private final String jobName;

    private final Long jobInstanceId;

    private final Long jobExecutionId;

    private final BatchStatus status;

    private final String exitCode;

    private final int stepsExecuted;

    private final Instant startedAt;

    private final Instant endedAt;

    /**
     * Creates an immutable completion snapshot from boundaries that are already instants.
     *
     * @param jobName       module-owned job name, or a stable unnamed marker
     * @param jobInstanceId framework job-instance identifier, possibly {@code null}
     * @param jobExecutionId framework execution identifier, possibly {@code null}
     * @param status        terminal framework status, possibly {@code null}
     * @param exitCode      terminal exit code, possibly {@code null}
     * @param stepsExecuted number of step executions recorded by the framework
     * @param startedAt     the instant the job started, possibly {@code null}
     * @param endedAt       the instant the job ended, possibly {@code null}
     */
    public JobCompletionEvent(final String jobName, final Long jobInstanceId,
            final Long jobExecutionId, final BatchStatus status, final String exitCode,
            final int stepsExecuted, final Instant startedAt, final Instant endedAt) {
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

    /**
     * Creates a snapshot from a framework execution's wall-clock boundaries and the zone that recorded
     * them.
     *
     * <p>This is the factory the batch boundary uses, and the only place the zone is applied. A caller
     * passes the zone rather than having it read from the ambient default so that the interpretation is
     * visible at the call site and can be exercised by a test without altering the JVM's default zone -
     * which is a process-wide mutation no test in a shared suite may perform.
     *
     * <p>A {@code null} boundary stays {@code null}. The framework leaves the end boundary unset on an
     * execution that was abandoned before it finished, and inventing an instant for it would report a
     * time that never happened.
     *
     * @param  jobName        module-owned job name, or a stable unnamed marker
     * @param  jobInstanceId  framework job-instance identifier, possibly {@code null}
     * @param  jobExecutionId framework execution identifier, possibly {@code null}
     * @param  status         terminal framework status, possibly {@code null}
     * @param  exitCode       terminal exit code, possibly {@code null}
     * @param  stepsExecuted  number of step executions recorded by the framework
     * @param  startedAt      the framework's wall-clock start reading, possibly {@code null}
     * @param  endedAt        the framework's wall-clock end reading, possibly {@code null}
     * @param  recordingZone  the zone the framework's readings were taken in; must not be {@code null}
     * @return the snapshot, never {@code null}
     * @throws NullPointerException     if {@code recordingZone} is {@code null}
     * @throws IllegalArgumentException if {@code stepsExecuted} is negative
     */
    public static JobCompletionEvent ofFrameworkExecution(final String jobName,
            final Long jobInstanceId, final Long jobExecutionId, final BatchStatus status,
            final String exitCode, final int stepsExecuted, final LocalDateTime startedAt,
            final LocalDateTime endedAt, final ZoneId recordingZone) {
        Objects.requireNonNull(recordingZone, "recordingZone must not be null");
        return new JobCompletionEvent(jobName, jobInstanceId, jobExecutionId, status, exitCode,
                stepsExecuted, instantOf(startedAt, recordingZone), instantOf(endedAt, recordingZone));
    }

    /**
     * @param  wallClock    a framework wall-clock reading, possibly {@code null}
     * @param  recordingZone the zone the reading was taken in
     * @return the corresponding instant, or {@code null} when the reading is absent
     */
    private static Instant instantOf(final LocalDateTime wallClock, final ZoneId recordingZone) {
        return wallClock == null ? null : wallClock.atZone(recordingZone).toInstant();
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

    /**
     * @return the instant the job started, or {@code null} when the framework recorded none
     */
    public Instant startedAt() {
        return this.startedAt;
    }

    /**
     * @return the instant the job ended, or {@code null} when the framework recorded none
     */
    public Instant endedAt() {
        return this.endedAt;
    }
}
