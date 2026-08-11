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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.carddemo.exception.ValidationException;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.stereotype.Service;

/**
 * The batch operations the module offers on demand: start one of the nine registered jobs by its stable
 * name, and report one execution of one of them out of the framework's own metadata.
 *
 * <p><strong>Why these operations live in the service layer.</strong> Launching a job is an operation
 * and not a transport concern. Holding it here restores the module's ordinary dependency direction - the
 * control surface in {@code com.carddemo.api} depends on this service, and nothing depends on the batch
 * tier from outside it - which is what the layering states and what a guard test now enforces. The
 * arrangement it replaces had the control surface importing nine job configuration classes directly,
 * which reached across a boundary the layering does not open.
 *
 * <p><strong>The inventory is closed, and closed is the security property.</strong>
 * {@link BatchJobCatalog#LAUNCHABLE_JOB_NAMES} holds nine names and {@link #launchable(String)} is the
 * gate over them. A name outside those nine reaches no framework call at all. Without the gate the
 * launch path would be "start whatever the caller names", and the framework's registry holds whatever it
 * was given; the closed list is what makes this a fixed set of nine operations rather than an arbitrary
 * execution facility. Nothing here resolves a bean name, a class name, an expression or a caller-selected
 * type, and nothing here reads a job configuration method: the nine names are read from the catalogue and
 * the framework's own registry does the resolving.
 *
 * <p><strong>A launch is deliberately repeatable, and this service adds nothing of its own to make it
 * so.</strong> A job identity is its name plus its identifying parameters. This service hands the launch
 * port exactly the name and exactly the parameter values it was given and <strong>appends nothing</strong>
 * - no timestamp, no unique identifier, no random value, no run counter, no current time - so nothing a
 * caller sent and nothing this layer invented distinguishes one request from the next. The identifying
 * value that does distinguish them is minted one layer down, by the shared parameter incrementer that every
 * job configuration attaches, and is applied inside {@code batch/BatchLaunchCoordinator}; a caller can
 * neither supply it nor influence it, because the only parameter names accepted are the ones the addressed
 * job declares.
 *
 * <p>So the same job submitted twice with the same parameters starts a second, distinct instance rather
 * than being answered out of the framework's metadata. That is the faithful reading of the estate and not a
 * gap: a job member resubmitted with an identical parameter set simply ran again - the posting job on the
 * same processing date, the accrual run with the same run date, the backup after a failed cycle - and
 * refusing the second submission would be a behavioural regression presented as an idempotency guarantee.
 * What <em>is</em> refused is an overlapping run: the coordinator holds a per-job lock and declines while
 * an execution of that job is active, which is the property the operational surface actually needs. This
 * paragraph replaces one that claimed the opposite; see {@code docs/decision-log.md} entry DL-310.
 *
 * <p><strong>Parameter values are passed through byte for byte.</strong> Two of them make that
 * non-negotiable: the interest run's ten-character parameter also becomes the literal leading characters
 * of every transaction identifier that run synthesises, and the report range is read as a fixed-width
 * layout, so a trimmed, a padded or a reformatted value would change output that is compared byte for
 * byte. Which parameters a job accepts, and what each must look like, is owned by the job configurations
 * and their validators - the validators run inside the framework's launch - and none of their rules is
 * restated here. A second copy of a rule is a second answer waiting to disagree with the first.
 *
 * <p><strong>A refusal reaches the caller as a closed reason code, never as a framework object.</strong>
 * The launch port answers a declined launch with its own refusal carrying one of three reasons - an
 * execution of that job is already active, the generated instance already exists, or the job's own
 * validator rejected the parameters - and an unregistered name arrives as the framework's own
 * {@link NoSuchJobException}. This service passes both on without adding a message of its own: the text an
 * operator reads is owned by the boundary that publishes the error contract, and a second copy of it here
 * would be a second copy to disagree with the first. What is deliberately not propagated is the framework's
 * exception chain, because it carries parameter values, query text and resource locations that this
 * boundary does not disclose.
 *
 * <p><strong>What a status report may carry.</strong> Four values: the execution identifier, the stable
 * job name, the framework's batch status and its exit code. The framework's exit <em>description</em> is
 * never read, because it carries a rendered stack trace; no failure chain, no parameter map, no step
 * detail, no query text, no resource location and no framework domain object crosses this boundary. An
 * execution the metadata does not hold, an execution carrying no instance to name, and an execution whose
 * job is outside the closed inventory are all answered the same way - {@code null} - so a caller cannot
 * tell them apart and cannot use the difference to discover what else the framework has run.
 *
 * <p><strong>Nothing starts when the context starts.</strong> This service contributes no runner,
 * lifecycle participant, initialising callback, event listener or scheduled trigger, and names no job for
 * anything to resolve, so a job runs only when one of these operations is called. The repository,
 * launcher, registry, operator, transaction manager, metadata tables and data source all arrive from the
 * framework's auto-configuration.
 *
 * <p>Stateless and immutable: every field is final and there is no mutable static state. The parameter
 * carrier the framework's launch signature requires is built inside the method that launches, is handed
 * straight to the framework and is never retained, published or shared, so instances are safe for
 * unsynchronised concurrent use.
 *
 * @since 1.0.0
 */
@Service
public class BatchJobLaunchService {

    /** Maximum printable value length admitted to framework metadata. */
    private static final int MAX_PARAMETER_VALUE_LENGTH = 256;

    /** Fixed refusal text that never echoes a caller-supplied parameter name or value. */
    private static final String UNKNOWN_PARAMETER_MESSAGE =
            "The request supplied a parameter this job does not declare.";

    /** Fixed refusal text for a value outside the safe textual carrier. */
    private static final String INVALID_PARAMETER_VALUE_MESSAGE =
            "A job parameter value contains characters or a length this surface does not accept.";

    /** Registry a stable job name is resolved through; supplied by the framework. */
    private final JobRegistry jobRegistry;

    /** Guarded launch port implemented by the batch tier. */
    private final BatchLaunchGateway batchLaunchGateway;

    /** Metadata reader an execution is reported from; supplied by the framework. */
    private final JobExplorer jobExplorer;

    /**
     * Takes the three framework collaborators and refuses a missing one, so the bean cannot exist half
     * wired.
     *
     * <p>Three and not four. The framework's {@code JobOperator} is deliberately not taken: it serves a
     * repeat and a resume operation the delivered surface does not offer - the control surface maps exactly
     * the launch and the status read, and its integration contract asserts that a repeat address and a
     * resume address both answer as absent. A collaborator held for an operation nobody can reach is
     * a dependency the bean does not have, so it is not taken.
     *
     * @param jobRegistry the registry a stable job name is resolved through
     * @param batchLaunchGateway the guarded server-identity launch boundary
     * @param jobExplorer the metadata reader an execution is reported from
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public BatchJobLaunchService(final JobRegistry jobRegistry,
                                final BatchLaunchGateway batchLaunchGateway,
                                final JobExplorer jobExplorer) {
        this.jobRegistry = Objects.requireNonNull(jobRegistry, "jobRegistry must not be null");
        this.batchLaunchGateway = Objects.requireNonNull(
                batchLaunchGateway, "batchLaunchGateway must not be null");
        this.jobExplorer = Objects.requireNonNull(jobExplorer, "jobExplorer must not be null");
    }

    /**
     * Returns the closed inventory of jobs this module will start.
     *
     * <p>The catalogue's own immutable set, returned as it stands rather than copied, because it cannot
     * be modified by a caller and copying it on every read would allocate for nothing.
     *
     * @return the nine stable job names, never {@code null} and never modifiable
     */
    public Set<String> launchableJobNames() {
        return BatchJobCatalog.LAUNCHABLE_JOB_NAMES;
    }

    /**
     * Reports whether a name is one of the nine jobs this module will start.
     *
     * @param jobName the name to test, which may be {@code null}
     * @return {@code true} only when the name is in the closed inventory
     */
    public boolean launchable(final String jobName) {
        return BatchJobCatalog.launchable(jobName);
    }

    /**
     * Starts one job and answers the execution identifier the framework assigned, without waiting for the
     * job to run.
     *
     * <p>Nothing is added to the parameters here. The one identifying value that makes each launch a
     * distinct instance is minted below this method by the launch port, so the same name with the same
     * values deliberately starts a second run rather than being answered out of the framework's metadata -
     * which is what a resubmitted job member did on the estate. An <em>overlapping</em> run is what the
     * port refuses. Which parameters the job accepts, and what each must contain, is decided by the job's
     * own validator during the launch and is not restated here.
     *
     * @param stableJobName the allow-listed name to resolve through the registry
     * @param jobParameters the parameters to launch with; every value is passed through unchanged, and
     *                      {@code null} or an empty map launches the job with no parameters at all
     * @return the execution identifier the framework assigned
     * @throws com.carddemo.service.BatchLaunchGateway.LaunchRejectedException if an execution of that job
     *                      is already active, if the generated instance already exists, or if the job's own
     *                      validator rejected the parameters
     * @throws ValidationException if a supplied parameter name is not one the addressed job reads
     * @throws NoSuchJobException  if the framework holds no job under that name
     */
    public long launch(final String stableJobName, final Map<String, String> jobParameters)
            throws NoSuchJobException {
        final Job registered = this.jobRegistry.getJob(stableJobName);
        return this.batchLaunchGateway.start(
                registered, acceptedParametersFrom(stableJobName, jobParameters));
    }

    /**
     * Reports one execution of one allow-listed job out of the framework's own metadata.
     *
     * @param executionId the execution identifier a launch answered with
     * @return the four reportable values, or {@code null} when no such execution is held, when it
     *         carries no instance to name, or when it belongs to a job outside the closed inventory
     */
    public JobExecutionReport readExecution(final long executionId) {
        final JobExecution execution = this.jobExplorer.getJobExecution(executionId);
        if (execution == null) {
            return null;
        }
        final JobInstance instance = execution.getJobInstance();
        if (instance == null) {
            return null;
        }
        final String jobName = instance.getJobName();
        if (!BatchJobCatalog.launchable(jobName)) {
            return null;
        }
        return new JobExecutionReport(executionId, jobName, statusNameOf(execution),
                exitCodeOf(execution));
    }

    /**
     * Copies the supplied parameters into the typed carrier the framework's launch signature takes.
     *
     * <p>Values are copied exactly as they arrived. Nothing is trimmed, padded, upper-cased, parsed or
     * reformatted, because two of the parameters this module's jobs declare are fixed-width and one of
     * them becomes the leading characters of synthesised identifiers. Nothing is added here either; the
     * only addition anywhere on the path is the gateway's identifying run number.
     *
     * <p>The carrier is created here, handed straight to the framework by the only caller and never
     * retained, published or shared, so it is not shared mutable state. Every accepted value is installed
     * explicitly as an identifying string parameter: a caller therefore cannot select a Java type or opt a
     * value out of the job identity through the untyped {@code Properties} grammar.
     *
     * @param jobParameters the supplied parameters, which may be {@code null} or empty
     * @return a freshly created carrier holding the supplied names and values verbatim
     */
    private static Map<String, String> acceptedParametersFrom(final String stableJobName,
            final Map<String, String> jobParameters) {
        if (jobParameters == null) {
            return Map.of();
        }
        final Set<String> accepted = BatchJobCatalog.parameterNamesFor(stableJobName)
                .orElseThrow(() -> new ValidationException(UNKNOWN_PARAMETER_MESSAGE));
        final Map<String, String> launchParameters = new LinkedHashMap<>();
        for (final Map.Entry<String, String> supplied : jobParameters.entrySet()) {
            final String name = supplied.getKey();
            final String value = supplied.getValue();
            if (name == null || !accepted.contains(name)) {
                throw new ValidationException(UNKNOWN_PARAMETER_MESSAGE);
            }
            if (!acceptableParameterValue(value)) {
                throw new ValidationException(INVALID_PARAMETER_VALUE_MESSAGE);
            }
            launchParameters.put(name, value);
        }
        return Map.copyOf(launchParameters);
    }

    private static boolean acceptableParameterValue(final String value) {
        if (value == null || value.length() > MAX_PARAMETER_VALUE_LENGTH
                || value.indexOf(',') >= 0) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character < 0x20 || character > 0x7e) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads an execution's batch status by name.
     *
     * @param execution the execution being reported, never {@code null}
     * @return the status name, or the framework's own unknown status when none is recorded
     */
    private static String statusNameOf(final JobExecution execution) {
        final BatchStatus status = execution.getStatus();
        return (status == null) ? BatchStatus.UNKNOWN.name() : status.name();
    }

    /**
     * Reads an execution's exit code.
     *
     * <p>The exit <em>code</em> only. The accompanying description is never read: the framework writes a
     * rendered stack trace into it on a failed run, so carrying it would publish internals through a
     * value that looks like a status.
     *
     * @param execution the execution being reported, never {@code null}
     * @return the exit code, or the framework's own unknown code when none is recorded
     */
    private static String exitCodeOf(final JobExecution execution) {
        final ExitStatus exitStatus = execution.getExitStatus();
        if (exitStatus == null || exitStatus.getExitCode() == null) {
            return ExitStatus.UNKNOWN.getExitCode();
        }
        return exitStatus.getExitCode();
    }

    /**
     * The four values one batch execution is reportable by.
     *
     * <p>Scalars only, and deliberately no framework object: an immutable carrier of four values cannot
     * be walked back into the metadata it was read from, whereas a framework execution object exposes
     * its parameter set, its step executions and its exit description.
     *
     * @param executionId the identifier the framework assigned the execution
     * @param jobName the stable job name, always one of the nine in the closed inventory
     * @param status the framework's batch status by name, never {@code null}
     * @param exitCode the framework's exit code, never {@code null}
     * @since 1.0.0
     */
    public record JobExecutionReport(long executionId, String jobName, String status,
            String exitCode) {
    }
}
