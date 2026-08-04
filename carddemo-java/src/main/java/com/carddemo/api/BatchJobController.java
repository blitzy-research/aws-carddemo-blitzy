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
package com.carddemo.api;

import com.carddemo.batch.BackupTransactionJobConfig;
import com.carddemo.batch.CategoryBalanceReportJobConfig;
import com.carddemo.batch.CombineTransactionsJobConfig;
import com.carddemo.batch.CreateStatementJobConfig;
import com.carddemo.batch.DailyTransactionReadJobConfig;
import com.carddemo.batch.FileProbeJobConfig;
import com.carddemo.batch.InterestCalculationJobConfig;
import com.carddemo.batch.PostTransactionJobConfig;
import com.carddemo.batch.TransactionReportJobConfig;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobInstanceAlreadyExistsException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * On-demand launch and status surface for the nine registered batch jobs, and the whole of the
 * operational control the migrated batch tier needs.
 *
 * <p><strong>What this class does.</strong> Two things. It starts one allow-listed job by its stable
 * name, and it reports the outcome of one execution by its identifier. Both are a bind, a delegation to a
 * framework interface, and a projection of the answer onto a minimal body. There is no third operation.
 *
 * <h2>Why an operational surface exists at all, and why it is not a transaction</h2>
 *
 * <p>On the mainframe a batch job was started by submitting a job member, which is an act outside the
 * online system: the resource definition at {@code app/csd/CARDDEMO.CSD} registers eighteen transactions
 * and <strong>not one of them starts a job</strong>. So this controller translates no transaction, carries
 * no screen, echoes no navigation state and answers with no route for a client to drive. It is the
 * replacement for job submission, and job submission had no screen.
 *
 * <p>One consequence is worth stating because its absence would otherwise look like an omission: this
 * route is deliberately <em>not</em> registered in the route-to-role table that
 * {@code config/SecurityConfig} derives from those eighteen definitions. That table is an inventory of
 * legacy transactions, and adding an entry with no legacy counterpart would corrupt an audit whose value
 * is that it matches the resource definition exactly. Authentication is not weakened by staying out of it:
 * the security chain's closing rule requires an established identity for every route no earlier rule
 * names, and no earlier rule names this one, so both operations here demand a verified credential. Nor is
 * the path placed beneath the administrative prefix, because the five administratively gated transactions
 * are exactly {@code CA00} and {@code CU00} through {@code CU03}, and a sixth gate invented here would
 * widen an entitlement the estate defines precisely.
 *
 * <h2>The inventory is closed, and closed is the security property</h2>
 *
 * <p>{@link #LAUNCHABLE_JOB_NAMES} holds nine names, each read from the job configuration that publishes
 * it rather than spelled out again here, so a renamed job moves both ends together and cannot leave a
 * stale literal behind. A name outside those nine is answered as an absent resource and reaches no
 * framework call at all.
 *
 * <p>That gate is not decoration. Without it the launch path would be "start whatever the caller names",
 * and the registry holds whatever the framework was given; a closed list is what makes this endpoint a
 * fixed set of nine operations rather than an arbitrary execution facility. Nothing here resolves a bean
 * name, a class name, an expression or a caller-selected type, and nothing here reads a job configuration
 * method: the nine constants are read, and the framework's own registry does the resolving.
 *
 * <h2>The nine are independent, and one of them is an orphan</h2>
 *
 * <p>The estate holds <strong>no master scheduler and no orchestrator</strong>. The sequence in which jobs
 * were submitted was an operational convention held by whoever ran them, not a dependency encoded in a
 * job member, so each of the nine is launched on its own and this class chains nothing. There is
 * deliberately no run-everything operation, no pipeline operation, no next-job operation, no schedule and
 * no bulk launch; adding one would invent an ordering guarantee the estate never made.
 *
 * <p>{@link DailyTransactionReadJobConfig} needs its own sentence. It translates a complete program that
 * <em>no</em> job member, cataloged procedure or online resource definition invokes. It is registered and
 * it is launchable here by its stable name, which is how it stays exercisable rather than becoming dead
 * code; it is <strong>not</strong> part of any default sequence, it is never selected implicitly, and it is
 * never chained to another job. Its presence in the allow-list is a deliberate recorded decision, not a
 * promotion.
 *
 * <h2>A launch is idempotent, because the framework's metadata already decides that</h2>
 *
 * <p>A job identity is its name plus its identifying parameters. This class hands the framework exactly
 * the name and exactly the parameter values it was given and <strong>appends nothing</strong> - no
 * timestamp, no unique identifier, no random value, no run counter, no current time. So the same request
 * twice is the same job identity twice, and the framework answers the second one out of its own metadata
 * rather than starting a duplicate run. That refusal is translated into the module's error contract and is
 * never worked around, because manufacturing a distinguishing parameter is precisely how a
 * "launch once" instruction quietly becomes "launch again".
 *
 * <p>Parameter values are passed through byte for byte. Two of them make that non-negotiable: the interest
 * run's ten-character parameter also becomes the literal leading characters of every transaction
 * identifier that run synthesises, and the report range is read as a fixed-width layout, so a trimmed, a
 * padded or a reformatted value would change output that is compared byte for byte. Which parameters a
 * job accepts, and what each must look like, is owned by {@code batch/JobParameterValidators} and by the
 * job configurations themselves - the validators run inside the framework's launch, and none of their
 * rules is restated here. A second copy of a rule is a second answer waiting to disagree with the first.
 *
 * <h2>Nothing starts when the context starts</h2>
 *
 * <p>Launch-on-start is disabled by the shipped configuration document, which is what keeps the
 * framework's own start-up runner out of the context. This class contributes no runner, no lifecycle
 * participant, no initialising callback, no event listener and no scheduled trigger, and it names no job
 * for anything to resolve, so a job runs only when one of these two operations is called. It also
 * constructs no repository, no launcher, no registry, no operator, no transaction manager, no metadata
 * table and no data source: all of those arrive from the framework's auto-configuration, which is exactly
 * why {@code config/BatchConfig} declines to declare them.
 *
 * <h2>What the answers may and may not carry</h2>
 *
 * <p>Both bodies are immutable maps of scalars, and the status body carries four members: the execution
 * identifier, the stable job name, the framework's batch status and its exit code. Nothing else is
 * exposed. In particular the framework's exit <em>description</em> is never read, because it carries a
 * rendered stack trace; no failure chain, no parameter map, no step detail, no query text, no resource
 * location and no framework domain object reaches a caller. The same restraint governs the log: the
 * stable job name and the execution identifier are recorded, and no supplied value is.
 *
 * <p>Metrics follow it too. Each operation is timed, tagged by outcome and by job name, and the job tag
 * takes one of the nine allow-listed names or a single fixed placeholder - never a caller's value, never
 * an execution identifier, never a parameter, never an identity and never an exception text - so the
 * label set stays bounded however many requests arrive.
 *
 * <p>Provenance: the eighteen transaction and program definitions of {@code app/csd/CARDDEMO.CSD} and the
 * sign-on program {@code app/cbl/COSGN00C.cbl}, which between them establish that batch control is not a
 * transaction and that an identity carries one of two user types; read as read-only reference at commit
 * SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No resource definition, job control statement or
 * program text is transcribed.
 *
 * <p>Stateless and immutable: final class, final fields, no mutable static state. The parameter carrier
 * the framework's launch signature requires is built inside the method that launches, is handed straight
 * to the framework and is never retained, published or shared, so instances are safe for unsynchronised
 * concurrent use.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping(BatchJobController.BATCH_JOBS_PATH)
public final class BatchJobController {

    /**
     * The base address of the batch control surface.
     *
     * <p>Declared here, as every other route in this package is, so that the mapping and anything that
     * reasons about the route read one authority. It sits outside the administrative prefix on purpose -
     * see the class documentation - and it shadows neither the management base path nor the
     * interface-description path, both of which the security chain treats separately.
     */
    public static final String BATCH_JOBS_PATH = "/api/batch/jobs";

    /** The launch operation, addressed by the stable job name. */
    public static final String LAUNCH_SUBPATH = "/{jobName}/launch";

    /** The status operation, addressed by the execution identifier the framework assigned. */
    public static final String EXECUTION_SUBPATH = "/executions/{executionId}";

    /** Name of the path variable carrying the stable job name. */
    public static final String JOB_NAME_PATH_VARIABLE = "jobName";

    /** Name of the path variable carrying the execution identifier. */
    public static final String EXECUTION_ID_PATH_VARIABLE = "executionId";

    /** Body member naming the execution the framework assigned. */
    public static final String FIELD_EXECUTION_ID = "executionId";

    /** Body member naming the job, always one of {@link #LAUNCHABLE_JOB_NAMES}. */
    public static final String FIELD_JOB_NAME = "jobName";

    /** Body member carrying the framework's batch status by name. */
    public static final String FIELD_STATUS = "status";

    /** Body member carrying the framework's exit code. */
    public static final String FIELD_EXIT_CODE = "exitCode";

    /**
     * The closed set of jobs this surface will start, one entry per registered job configuration.
     *
     * <p>Every entry is the constant the owning configuration publishes, never a repeated literal, so the
     * allow-list cannot drift from the names the framework registered. The set is immutable and safe to
     * publish: a caller may read it - a test asserting the inventory is exactly these nine is the reason
     * it is visible - and no caller can add to it or take from it.
     *
     * <p>The eight operational jobs are independently launchable in any order the operator chooses.
     * {@link DailyTransactionReadJobConfig} is the ninth and is the recorded orphan described in the class
     * documentation: launchable by name, and part of no sequence.
     */
    public static final Set<String> LAUNCHABLE_JOB_NAMES = Set.of(
            PostTransactionJobConfig.JOB_NAME,
            InterestCalculationJobConfig.JOB_NAME,
            CombineTransactionsJobConfig.JOB_NAME,
            CreateStatementJobConfig.JOB_NAME,
            TransactionReportJobConfig.JOB_NAME,
            BackupTransactionJobConfig.JOB_NAME,
            CategoryBalanceReportJobConfig.JOB_NAME,
            FileProbeJobConfig.FILE_PROBE_JOB_NAME,
            DailyTransactionReadJobConfig.JOB_NAME);

    /** The one logger this class writes through, resolved from the class so it sits under the module. */
    private static final Logger LOG = LoggerFactory.getLogger(BatchJobController.class);

    /** Timer name for one launch request, following the module's batch metric naming. */
    private static final String METRIC_LAUNCH_REQUEST = "carddemo.batch.joblaunch.request";

    /** Timer name for one status request, following the module's batch metric naming. */
    private static final String METRIC_STATUS_REQUEST = "carddemo.batch.jobstatus.request";

    /** Tag naming the job an operation concerned, bounded to the allow-list plus one placeholder. */
    private static final String TAG_JOB = "job";

    /** Tag naming which outcome the operation reached. */
    private static final String TAG_OUTCOME = "outcome";

    /**
     * The job tag used when no allow-listed job can be named.
     *
     * <p>A fixed placeholder rather than the value that arrived. Tagging a metric with a path variable
     * would let a caller mint an unbounded number of time series by varying it, which is a denial of
     * service against the metrics store rather than a diagnostic.
     */
    private static final String JOB_TAG_UNRECOGNISED = "unrecognised";

    /** Outcome tag: the framework accepted the launch and assigned an execution. */
    private static final String OUTCOME_LAUNCHED = "launched";

    /** Outcome tag: the framework declined the launch on its own metadata or its own validation. */
    private static final String OUTCOME_REFUSED = "refused";

    /** Outcome tag: the addressed job or execution does not exist. */
    private static final String OUTCOME_ABSENT = "absent";

    /** Outcome tag: the addressed execution was found and reported. */
    private static final String OUTCOME_REPORTED = "reported";

    /** Record type reported when a launch names a job outside the closed inventory. */
    private static final String RECORD_TYPE_BATCH_JOB = "BatchJob";

    /** Record type reported when a status request names an execution that cannot be reported. */
    private static final String RECORD_TYPE_JOB_EXECUTION = "BatchJobExecution";

    /**
     * Operator text for a launch the framework refused because that job identity has already been used.
     *
     * <p>A fixed literal. The framework's own message for this condition renders the job name together
     * with the whole parameter set, so it is never propagated; this text says what happened and what to
     * change without echoing anything that was sent.
     */
    private static final String INSTANCE_ALREADY_EXISTS_MESSAGE =
            "This job has already been run with the parameters supplied. Vary a parameter to run it again.";

    /**
     * Operator text for a launch the job's own parameter validation refused.
     *
     * <p>A fixed literal, for the same reason: the framework's message can name the keys and values it
     * rejected. The rules themselves belong to the job configurations and their validators, and this text
     * deliberately does not restate any of them.
     */
    private static final String PARAMETERS_INVALID_MESSAGE =
            "The job parameters supplied were rejected by this job. Correct them and submit again.";

    /** Registry the stable job name is resolved through; supplied by the framework. */
    private final JobRegistry jobRegistry;

    /** Launcher the resolved job is started through; supplied by the framework. */
    private final JobOperator jobOperator;

    /** Metadata reader one execution is reported from; supplied by the framework. */
    private final JobExplorer jobExplorer;

    /** Registry both operation timers are registered against. */
    private final MeterRegistry meterRegistry;

    /**
     * Creates the controller over the batch infrastructure the framework publishes.
     *
     * <p>Constructor injection only, and every collaborator is an interface the framework's
     * auto-configuration already supplies as a single bean. None of the four is constructed, replaced or
     * decorated here.
     *
     * @param jobRegistry   resolves a stable job name to the registered job
     * @param jobOperator   starts a resolved job and reports the execution it assigned
     * @param jobExplorer   reads one execution out of the framework's own metadata
     * @param meterRegistry the metrics registry both operation timers are registered against
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public BatchJobController(final JobRegistry jobRegistry,
                              final JobOperator jobOperator,
                              final JobExplorer jobExplorer,
                              final MeterRegistry meterRegistry) {
        this.jobRegistry = Objects.requireNonNull(jobRegistry, "jobRegistry must not be null");
        this.jobOperator = Objects.requireNonNull(jobOperator, "jobOperator must not be null");
        this.jobExplorer = Objects.requireNonNull(jobExplorer, "jobExplorer must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    // ==================================================================================================
    // The two operations
    // ==================================================================================================

    /**
     * Starts one allow-listed job and answers with the execution the framework assigned.
     *
     * <p>The order of the four steps is the whole of the method and each step is load-bearing. The name is
     * checked against the closed inventory first, so an unrecognised name reaches no framework call and
     * writes no metadata. The recognised name is then resolved through the registry, and the resolved job's
     * own name - not the value that arrived - is what the launch is issued against, so the framework starts
     * exactly what the registry produced. The supplied parameters are copied verbatim into the carrier the
     * launch signature takes. The launch is then issued once, and its three declined outcomes are
     * translated into the module's error contract.
     *
     * <p>Nothing is added to the parameters, so the same name with the same values is the same job identity
     * and the framework answers a repeat out of its own metadata rather than starting a second run. Which
     * parameters this job accepts, and what each must contain, is decided by the job's own validator during
     * the launch and is not restated here.
     *
     * @param jobName       the stable job name, which must be one of {@link #LAUNCHABLE_JOB_NAMES}
     * @param jobParameters the parameters to launch with, bound from the request's query parameters; every
     *                      value is passed through unchanged, and an absent or empty map launches the job
     *                      with no parameters at all
     * @return {@code 200} carrying the execution identifier and the stable job name
     * @throws RecordNotFoundException if the name is outside the closed inventory
     * @throws ValidationException     if the framework declined the launch because that job identity has
     *                                 already been used, or because the job's own validator rejected the
     *                                 parameters
     * @throws IllegalStateException   if an allow-listed name is not registered, which is a wiring fault
     *                                 rather than a caller fault
     */
    @PostMapping(path = LAUNCH_SUBPATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Launch one batch job on demand",
            description = "Starts one of the nine registered jobs by its stable name. Nothing runs when "
                    + "the application starts, so this is the only way a job begins. The launch is "
                    + "idempotent: no run identifier, timestamp or unique value is added to the "
                    + "parameters, so submitting the same job with the same parameters twice is refused "
                    + "by the framework's own metadata rather than starting a duplicate run. Parameter "
                    + "values are passed through unchanged and are validated by the job itself. The jobs "
                    + "are independent - there is no run-everything operation and no ordering imposed "
                    + "here.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The job was started. Carries the execution identifier to ask after and "
                        + "the stable job name it was started under."),
        @ApiResponse(responseCode = "400",
                description = "The job refused the parameters supplied, or this job has already been run "
                        + "with them."),
        @ApiResponse(responseCode = "401",
                description = "No credential was presented, or the one presented did not verify."),
        @ApiResponse(responseCode = "404",
                description = "The name is not one of the nine jobs this surface will start.")})
    public ResponseEntity<Map<String, Object>> launchJob(
            @PathVariable(name = JOB_NAME_PATH_VARIABLE) final String jobName,
            @RequestParam final Map<String, String> jobParameters) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);

        if (!LAUNCHABLE_JOB_NAMES.contains(jobName)) {
            recordLaunch(sample, JOB_TAG_UNRECOGNISED, OUTCOME_ABSENT);
            LOG.warn("Batch job launch addressed a job outside the closed inventory");
            throw new RecordNotFoundException(RECORD_TYPE_BATCH_JOB, jobName);
        }

        final String stableJobName = registeredNameOf(jobName);
        final Long executionId;
        try {
            executionId = this.jobOperator.start(stableJobName, launchParametersFrom(jobParameters));
        } catch (final JobInstanceAlreadyExistsException alreadyRun) {
            recordLaunch(sample, stableJobName, OUTCOME_REFUSED);
            LOG.warn("Batch job launch refused: job={} reason=instance-already-exists", stableJobName);
            throw new ValidationException(INSTANCE_ALREADY_EXISTS_MESSAGE);
        } catch (final JobParametersInvalidException rejectedParameters) {
            recordLaunch(sample, stableJobName, OUTCOME_REFUSED);
            LOG.warn("Batch job launch refused: job={} reason=parameters-rejected", stableJobName);
            throw new ValidationException(PARAMETERS_INVALID_MESSAGE);
        } catch (final NoSuchJobException notRegistered) {
            recordLaunch(sample, stableJobName, OUTCOME_ABSENT);
            throw registrationFault(stableJobName, notRegistered);
        }

        final Map<String, Object> body = Map.of(
                FIELD_EXECUTION_ID, executionId,
                FIELD_JOB_NAME, stableJobName);

        recordLaunch(sample, stableJobName, OUTCOME_LAUNCHED);
        LOG.info("Batch job launched: job={} executionId={}", stableJobName, executionId);

        return ResponseEntity.ok(body);
    }

    /**
     * Reports one execution of one allow-listed job out of the framework's own metadata.
     *
     * <p>Four members and no more: the execution identifier, the stable job name, the batch status and the
     * exit code. The framework's exit description is deliberately not among them - it carries a rendered
     * stack trace - and neither is the parameter set, the step detail or any framework object.
     *
     * <p>An execution that the metadata does not hold, and an execution belonging to a job outside the
     * closed inventory, are both answered as an absent resource. The second case matters: without it this
     * operation would be a general reader of the framework's metadata rather than a status view over nine
     * jobs, and a caller could enumerate identifiers to discover what else has run.
     *
     * @param executionId the execution identifier the launch answered with
     * @return {@code 200} carrying the execution identifier, the stable job name, the batch status and the
     *         exit code
     * @throws RecordNotFoundException if no such execution is held, or it belongs to a job outside the
     *                                 closed inventory
     */
    @GetMapping(path = EXECUTION_SUBPATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Report one batch job execution",
            description = "Reads one execution of one of the nine registered jobs out of the framework's "
                    + "own metadata, which is the single source of truth for what ran. Answers the "
                    + "execution identifier, the stable job name, the batch status and the exit code, and "
                    + "nothing else: no exit description, no parameter set and no step detail, because "
                    + "those carry internal detail. An execution this surface does not own reads as "
                    + "absent.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The execution was found. Carries its identifier, the stable job name, the "
                        + "batch status and the exit code."),
        @ApiResponse(responseCode = "400",
                description = "The execution identifier was not a number."),
        @ApiResponse(responseCode = "401",
                description = "No credential was presented, or the one presented did not verify."),
        @ApiResponse(responseCode = "404",
                description = "No such execution is held, or it belongs to a job this surface does not "
                        + "own.")})
    public ResponseEntity<Map<String, Object>> readJobExecution(
            @PathVariable(name = EXECUTION_ID_PATH_VARIABLE) final long executionId) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);

        final JobExecution execution = this.jobExplorer.getJobExecution(executionId);
        final String stableJobName = ownedJobNameOf(execution);
        if (stableJobName == null) {
            recordStatus(sample, JOB_TAG_UNRECOGNISED, OUTCOME_ABSENT);
            LOG.debug("Batch job execution not reportable: executionId={}", executionId);
            throw new RecordNotFoundException(RECORD_TYPE_JOB_EXECUTION, Long.toString(executionId));
        }

        final Map<String, Object> body = Map.of(
                FIELD_EXECUTION_ID, executionId,
                FIELD_JOB_NAME, stableJobName,
                FIELD_STATUS, statusNameOf(execution),
                FIELD_EXIT_CODE, exitCodeOf(execution));

        recordStatus(sample, stableJobName, OUTCOME_REPORTED);
        LOG.debug("Batch job execution reported: job={} executionId={}", stableJobName, executionId);

        return ResponseEntity.ok(body);
    }

    // ==================================================================================================
    // Adapting an allow-listed request onto the framework's own calls
    // ==================================================================================================

    /**
     * Resolves an allow-listed name through the registry and answers the name the registry produced.
     *
     * <p>The registry's answer is used rather than the value that arrived, so the launch is issued against
     * the identity the framework holds. The two agree for every one of the nine - each job is built with the
     * constant its configuration publishes - and reading it back is what makes that agreement checked
     * rather than assumed.
     *
     * @param jobName an allow-listed stable job name
     * @return the name the registered job carries
     * @throws IllegalStateException if the name is allow-listed but not registered
     */
    private String registeredNameOf(final String jobName) {
        final Job registered;
        try {
            registered = this.jobRegistry.getJob(jobName);
        } catch (final NoSuchJobException notRegistered) {
            throw registrationFault(jobName, notRegistered);
        }
        return registered.getName();
    }

    /**
     * Copies the supplied parameters into the carrier the framework's launch signature takes.
     *
     * <p>Values are copied exactly as they arrived. Nothing is trimmed, padded, upper-cased, parsed or
     * reformatted, because two of the parameters this module's jobs declare are fixed-width and one of them
     * becomes the leading characters of synthesised identifiers. Nothing is added either, which is what
     * keeps a repeated launch the same job identity.
     *
     * <p>The carrier is created here, handed straight to the framework by the only caller and never
     * retained, published or shared, so it is not shared mutable state. An entry with no name or no value
     * is skipped rather than rejected: the carrier cannot hold one, and no job declares a parameter that
     * could arrive that way.
     *
     * @param jobParameters the bound request parameters, which may be {@code null} or empty
     * @return a freshly created carrier holding the supplied names and values verbatim
     */
    private static Properties launchParametersFrom(final Map<String, String> jobParameters) {
        final Properties launchParameters = new Properties();
        if (jobParameters == null) {
            return launchParameters;
        }
        for (final Map.Entry<String, String> supplied : jobParameters.entrySet()) {
            final String name = supplied.getKey();
            final String value = supplied.getValue();
            if (name != null && value != null) {
                launchParameters.setProperty(name, value);
            }
        }
        return launchParameters;
    }

    /**
     * Names the job an execution belongs to, but only when this surface owns that job.
     *
     * <p>Answers {@code null} for an execution the metadata does not hold, for one that carries no instance
     * to name, and for one whose job is outside the closed inventory. The caller turns all three into the
     * same absent-resource answer, so a caller cannot tell them apart and cannot use the difference to
     * discover what else the framework has run.
     *
     * @param execution the execution read from the metadata, which may be {@code null}
     * @return the stable job name when this surface owns it, otherwise {@code null}
     */
    private static String ownedJobNameOf(final JobExecution execution) {
        if (execution == null) {
            return null;
        }
        final JobInstance instance = execution.getJobInstance();
        if (instance == null) {
            return null;
        }
        final String jobName = instance.getJobName();
        return LAUNCHABLE_JOB_NAMES.contains(jobName) ? jobName : null;
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
     * rendered stack trace into it on a failed run, so returning it would publish internals through a field
     * that looks like a status.
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
     * Builds the failure for an allow-listed job the registry does not hold.
     *
     * <p>This is a wiring fault, not a caller fault: the name passed the closed inventory, so the job
     * configuration that publishes it failed to publish a job bean. It is deliberately not the module's
     * abend carrier, which reproduces a legacy terminal condition that this has no counterpart in; the
     * boundary's terminal handler already answers an unanticipated failure with the one frozen terminal
     * text and logs the failure type without its message, which is the correct treatment. The job name is
     * safe to name because it came from the closed inventory.
     *
     * @param jobName       the allow-listed name that did not resolve
     * @param notRegistered the framework's own report, chained so the cause survives in the log
     * @return the failure to raise
     */
    private static IllegalStateException registrationFault(final String jobName,
            final NoSuchJobException notRegistered) {
        LOG.error("Batch job is allow-listed but not registered: job={}", jobName);
        return new IllegalStateException("no job is registered under the name " + jobName, notRegistered);
    }

    // ==================================================================================================
    // Metrics
    // ==================================================================================================

    /**
     * Records the elapsed time of one launch request, tagged by job and outcome.
     *
     * @param sample  the timing sample started at the head of the request
     * @param jobTag  an allow-listed job name, or {@link #JOB_TAG_UNRECOGNISED}
     * @param outcome the outcome the request reached
     */
    private void recordLaunch(final Timer.Sample sample, final String jobTag, final String outcome) {
        sample.stop(Timer.builder(METRIC_LAUNCH_REQUEST)
                .description("Elapsed time of one CardDemo batch job launch request")
                .tag(TAG_JOB, jobTag)
                .tag(TAG_OUTCOME, outcome)
                .register(this.meterRegistry));
    }

    /**
     * Records the elapsed time of one status request, tagged by job and outcome.
     *
     * @param sample  the timing sample started at the head of the request
     * @param jobTag  an allow-listed job name, or {@link #JOB_TAG_UNRECOGNISED}
     * @param outcome the outcome the request reached
     */
    private void recordStatus(final Timer.Sample sample, final String jobTag, final String outcome) {
        sample.stop(Timer.builder(METRIC_STATUS_REQUEST)
                .description("Elapsed time of one CardDemo batch job status request")
                .tag(TAG_JOB, jobTag)
                .tag(TAG_OUTCOME, outcome)
                .register(this.meterRegistry));
    }
}
