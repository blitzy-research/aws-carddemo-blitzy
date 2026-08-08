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

import com.carddemo.api.dto.BatchJobExecutionResponse;
import com.carddemo.api.dto.BatchJobLaunchRequest;
import com.carddemo.api.dto.BatchJobLaunchResponse;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.service.BatchJobCatalog;
import com.carddemo.service.BatchLaunchGateway.LaunchRejectedException;
import com.carddemo.service.BatchLaunchGateway.RejectionReason;
import com.carddemo.service.BatchJobLaunchService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * On-demand launch and status surface for the nine registered batch jobs, and the whole of the
 * operational control the migrated batch tier needs.
 *
 * <p><strong>What this class does.</strong> Two things. It starts one allow-listed job by its stable
 * name, and it reports the outcome of one execution by its identifier. Both are a bind, a delegation to
 * {@link BatchJobLaunchService}, and a projection of the answer onto a minimal body. There is no third
 * operation.
 *
 * <p><strong>Why the framework calls sit below this class rather than in it.</strong> Launching a job is
 * an operation, not a transport concern, so the registry, the operator and the metadata reader are named
 * by the service layer and this class names the service. That is the module's ordinary dependency
 * direction, and it is now the only one this class has: an earlier revision imported the nine job
 * configuration classes to read their stable names, which reached from the API tier into the batch tier
 * across a boundary the layering does not open. The nine names are declared once in
 * {@link BatchJobCatalog}, which both tiers are permitted to read, so nothing is repeated as a literal
 * and nothing depends upward. What stays here is what belongs to a transport boundary: the routes, the
 * body shape, the error contract, the metrics and the log.
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
 * is that it matches the resource definition exactly. Nor is the path placed beneath the administrative
 * prefix, because the five administratively gated transactions are exactly {@code CA00} and {@code CU00}
 * through {@code CU03}, and a sixth entry there would misreport an entitlement the estate defines
 * precisely.
 *
 * <h2>Both operations require the administrative authority</h2>
 *
 * <p>Staying out of the table does not mean staying out of the rules.
 * {@link #BATCH_CONTROL_PATH_PREFIX} is published for {@code config/SecurityConfig} to gate to the
 * administrative authority with a rule of its own, and {@link #BATCH_JOBS_PATH} is assembled from that
 * prefix so the rule reaches both operations. A caller presenting no credential is
 * answered {@code 401}; a caller presenting an ordinary signed-on credential is answered {@code 403}.
 *
 * <p><strong>Relying on the chain's closing rule was not sufficient, and the reason is specific rather
 * than precautionary.</strong> That rule requires only an established identity, which is the right
 * default for a screen transaction and the wrong one here: starting a job is not reading a screen. The
 * backup job's second step clears the transaction master outright, reproducing a legacy condition-code
 * reset, so a launch is irreversible in a way no transaction was. On the mainframe the corresponding
 * authority was the right to submit a job - a facility no transaction exposed and an ordinary terminal
 * operator did not hold - so requiring the administrative authority here is the closer reading of the
 * estate rather than an invented restriction. The status operation carries the same requirement because
 * what has run and how it ended is the same operational detail, and a surface whose two halves disagreed
 * about who may use it would be a surface nobody could reason about.
 *
 * <h2>The inventory is closed, and closed is the security property</h2>
 *
 * <p>{@link #LAUNCHABLE_JOB_NAMES} holds nine names, read from {@link BatchJobCatalog} rather than
 * spelled out again here, so a renamed job moves both ends together and cannot leave a stale literal
 * behind. A name outside those nine is answered as an absent resource and reaches no framework call at
 * all.
 *
 * <p>That gate is not decoration. Without it the launch path would be "start whatever the caller names",
 * and the registry holds whatever the framework was given; a closed list is what makes this endpoint a
 * fixed set of nine operations rather than an arbitrary execution facility. Nothing here resolves a bean
 * name, a class name, an expression or a caller-selected type, and nothing here reads a job configuration
 * method: the nine catalogued names are read, and the framework's own registry does the resolving.
 *
 * <h2>The nine are independent, and one of them is an orphan</h2>
 *
 * <p>The estate holds <strong>no master scheduler and no orchestrator</strong>. The sequence in which jobs
 * were submitted was an operational convention held by whoever ran them, not a dependency encoded in a
 * job member, so each of the nine is launched on its own and this class chains nothing. There is
 * deliberately no run-everything operation, no pipeline operation, no next-job operation, no schedule and
 * no bulk launch; adding one would invent an ordering guarantee the estate never made.
 *
 * <p>{@link BatchJobCatalog#DAILY_TRANSACTION_READ_JOB_NAME} needs its own sentence. It names the job
 * that translates a complete program that <em>no</em> job member, cataloged procedure or online resource
 * definition invokes. It is registered and it is launchable here by its stable name, which is how it
 * stays exercisable rather than becoming dead code; it is <strong>not</strong> part of any default
 * sequence, it is never selected implicitly, and it is never chained to another job. Its presence in the
 * allow-list is a deliberate recorded decision, not a promotion.
 *
 * <h2>A launch is repeatable, because submitting a job member twice ran it twice</h2>
 *
 * <p>A job identity is its name plus its identifying parameters, and the framework refuses a second
 * instance of an identity it has already recorded. On the estate that refusal has no counterpart: a member
 * could be submitted again with an identical parameter set and would simply run again - the posting job on
 * the same processing date, the interest run with the same run date, the backup after a failed cycle. Left
 * alone, this surface would have made the first submission of any parameter set the only one, which is a
 * behavioural regression rather than an idempotency guarantee.
 *
 * <p>So the launch <strong>advances the job to its next instance</strong>, and it does so through the job's
 * own {@link JobParametersIncrementer} - the one {@code config/BatchConfig} publishes and all nine
 * configurations attach - rather than through anything invented here. The incrementer is seeded from the
 * parameters of that job's most recent instance, which is what makes the identifying value it contributes
 * monotonic instead of resetting to its first value on every call. Only the keys the incrementer actually
 * contributed are added, only where the caller supplied none, so a caller's value is never displaced and
 * the previous run's parameters are never dragged into this one. A job carrying no incrementer is launched
 * with exactly what arrived.
 *
 * <p>The framework's refusal is therefore reachable only when a caller pins the incremented key itself,
 * and it is still translated into the module's error contract rather than worked around.
 *
 * <p>Parameter values are bound and handed on byte for byte. Two of them make that non-negotiable: the
 * interest run's ten-character parameter also becomes the literal leading characters of every transaction
 * identifier that run synthesises, and the report range is read as a fixed-width layout, so a trimmed, a
 * padded or a reformatted value would change output that is compared byte for byte. Nothing is trimmed,
 * padded, parsed or reformatted on the way through, and the carrier the framework's launch signature
 * takes is built inside the service rather than here. Which parameters a job accepts, and what each must
 * look like, is owned by {@code batch/JobParameterValidators} and by the job configurations themselves -
 * the validators run inside the framework's launch, and none of their rules is restated here. A second
 * copy of a rule is a second answer waiting to disagree with the first.
 *
 * <h2>Nothing starts when the context starts</h2>
 *
 * <p>Launch-on-start is disabled by the shipped configuration document, which is what keeps the
 * framework's own start-up runner out of the context. This class contributes no runner, no lifecycle
 * participant, no initialising callback, no event listener and no scheduled trigger, and it names no job
 * for anything to resolve, so a job runs only when one of these two operations is called. Neither this
 * class nor the service beneath it constructs a repository, a launcher, a registry, an operator, a
 * transaction manager, a metadata table or a data source: all of those arrive from the framework's
 * auto-configuration, which is exactly why {@code config/BatchConfig} declines to declare them.
 *
 * <h2>What the answers may and may not carry</h2>
 *
 * <p>Every body is an immutable map of scalars. A launch answers with the execution identifier and the
 * stable job name; the status body carries four members: the execution
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
 * <p>Stateless and immutable: final class, final fields, no mutable static state. The bound parameter map
 * is read once, handed to the service and never retained, published or shared, so instances are safe for
 * unsynchronised concurrent use.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping(BatchJobController.BATCH_JOBS_PATH)
public final class BatchJobController {

    /**
     * Path prefix the whole batch-control surface lives beneath, and the address the security chain gates
     * to the administrative authority.
     *
     * <p>Declared here rather than in the configuration because a route belongs to the controller that
     * claims it - the same arrangement the sign-on route uses, where the controller owns the literal and
     * {@code config/SecurityConfig} reads it. The rule and the mapping therefore cannot name different
     * addresses, and the direction that drift would fail in is the dangerous one: a mapping moved out from
     * under the gated prefix would still answer requests, but would answer them under the chain's closing
     * rule and therefore to any signed-on caller.
     *
     * <p>It sits outside the administrative prefix on purpose - see the class documentation - and it
     * shadows neither the management base path nor the interface-description path, both of which the
     * security chain treats separately.
     */
    public static final String BATCH_CONTROL_PATH_PREFIX = "/api/batch";

    /**
     * The base address of the batch control surface.
     *
     * <p>Assembled from {@link #BATCH_CONTROL_PATH_PREFIX} so that every operation this class maps is
     * beneath the prefix the chain gates, by construction rather than by inspection.
     */
    public static final String BATCH_JOBS_PATH = BATCH_CONTROL_PATH_PREFIX + "/jobs";

    /** The launch operation, addressed by the stable job name. */
    public static final String LAUNCH_SUBPATH = "/{jobName}/launch";

    /** The status operation, addressed by the execution identifier the framework assigned. */
    public static final String EXECUTION_SUBPATH = "/executions/{executionId}";

    /** Name of the path variable carrying the stable job name. */
    public static final String JOB_NAME_PATH_VARIABLE = "jobName";

    /** Name of the path variable carrying the execution identifier. */
    public static final String EXECUTION_ID_PATH_VARIABLE = "executionId";

    /**
     * The closed set of jobs this surface will start, one entry per registered job.
     *
     * <p>The catalogue's own set, read rather than rebuilt, so the allow-list cannot drift from the
     * names the framework registered: the nine job configurations take their registered names from the
     * same nine constants. The set is immutable and safe to publish - a caller may read it, and a test
     * asserting the inventory is exactly these nine is the reason it is visible - and no caller can add
     * to it or take from it.
     *
     * <p>The eight operational jobs are independently launchable in any order the operator chooses.
     * {@link BatchJobCatalog#DAILY_TRANSACTION_READ_JOB_NAME} is the ninth and is the recorded orphan
     * described in the class documentation: launchable by name, and part of no sequence.
     */
    public static final Set<String> LAUNCHABLE_JOB_NAMES = BatchJobCatalog.LAUNCHABLE_JOB_NAMES;

    /**
     * The parameter each allow-listed job will accept, and nothing else: one entry per job, holding the
     * exact names that job reads.
     *
     * <p><strong>This is a closed schema and it is the whole of what a caller may name.</strong> A
     * supplied name outside the addressed job's entry is refused before any framework call is made, so a
     * name this module's jobs do not read cannot reach the framework's parameter set, cannot become part
     * of a job identity, and cannot appear in the framework's own metadata or its launch diagnostic.
     * Without that closure the launch path accepted anything: an unrecognised name is an identifying
     * parameter by default, so a caller could vary one and mint a fresh instance of a job that had
     * deliberately already been run - turning "launch once" into "launch again" through a name the job
     * never reads.
     *
     * <p>Every name is read from the declaring authority rather than repeated here.
     * {@link JobParameterValidators} publishes the three the validators police, and
     * {@link CombineTransactionsJobConfig} publishes the two its own step resolves, so a renamed
     * parameter moves both ends together.
     *
     * <p><strong>Six of the nine jobs read no parameter at all, and their empty entries are deliberate
     * rather than missing.</strong> An empty entry means that job accepts none, so any supplied name is
     * refused; leaving a job out of this map entirely would be indistinguishable from an oversight, and
     * the launch method's lookup would then have to decide what an absent entry meant. The map is
     * immutable, is built once during class initialization, and is published so that a test can assert
     * the schema is exactly this.
     */
    public static final Map<String, Set<String>> ACCEPTED_JOB_PARAMETER_NAMES =
            BatchJobCatalog.parameterNamesByJob();

    /**
     * The greatest number of encoded characters a parameter value may carry.
     *
     * <p>Every value the nine jobs actually read is short: a ten-character date, a mode name, or a
     * generation name. The bound is set well above all of them and well below anything that could be used
     * to drive an unbounded value into the framework's metadata tables or its launch diagnostic. It is a
     * defensive ceiling and not a format rule - the formats belong to the jobs' own validators, which run
     * inside the launch and are deliberately not restated here.
     */
    public static final int MAX_PARAMETER_VALUE_LENGTH = 256;

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

    /** Outcome tag: the request named a parameter the addressed job does not declare. */
    private static final String OUTCOME_REJECTED = "rejected";

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
     *
     * <p>Reaching this outcome now means one specific thing: the caller pinned the very parameter the job's
     * incrementer would otherwise have advanced, so the identity could not move on. An ordinary repeat
     * launch does not reach it, because the incrementer supplies a fresh identifying value.
     */
    private static final String INSTANCE_ALREADY_EXISTS_MESSAGE =
            "A new run identity could not be allocated for this job. Submit the request again.";

    /** Operator text for an overlap refused by the database-backed active-execution guard. */
    private static final String ACTIVE_EXECUTION_MESSAGE =
            "This job already has an active execution. Wait for it to finish before launching another.";

    /**
     * Operator text for a launch the job's own parameter validation refused.
     *
     * <p>A fixed literal, for the same reason: the framework's message can name the keys and values it
     * rejected. The rules themselves belong to the job configurations and their validators, and this text
     * deliberately does not restate any of them.
     */
    private static final String PARAMETERS_INVALID_MESSAGE =
            "The job parameters supplied were rejected by this job. Correct them and submit again.";

    /** The service that owns the closed inventory and both batch operations. */
    private final BatchJobLaunchService batchJobLaunchService;

    /** Registry both operation timers are registered against. */
    private final MeterRegistry meterRegistry;

    /**
     * Creates the controller over the batch operations the service layer publishes.
     *
     * <p>Constructor injection only. The batch operation service holds the framework's registry,
     * operator and metadata reader, and neither it nor the metrics registry is constructed, replaced or
     * decorated here.
     *
     * @param batchJobLaunchService the closed inventory and the launch and status operations
     * @param meterRegistry         the metrics registry both operation timers are registered against
     * @throws NullPointerException if either collaborator is {@code null}
     */
    public BatchJobController(final BatchJobLaunchService batchJobLaunchService,
                              final MeterRegistry meterRegistry) {
        this.batchJobLaunchService = Objects.requireNonNull(batchJobLaunchService,
                "batchJobLaunchService must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    // ==================================================================================================
    // The two operations
    // ==================================================================================================

    /**
     * Starts one allow-listed job and answers with the execution the framework assigned.
     *
     * <p>The order of the five steps is the whole of the method and each step is load-bearing. The name is
     * checked against the closed inventory first, so an unrecognised name reaches no framework call and
     * writes no metadata. The recognised name is then resolved through the registry, and the resolved job -
     * not the value that arrived - is what the launch is issued against, so the framework starts exactly
     * what the registry produced. The supplied parameters are then screened against that job's own closed
     * schema and converted into typed parameters. The launch is issued once, and its declined outcomes are
     * translated into the module's error contract.
     *
     * <p><strong>The parameters reach the framework typed, and that is a security property rather than a
     * style.</strong> The framework also accepts an untyped carrier of text, in which a value may be
     * followed by a type name and an identifying flag; a caller who could reach that grammar would be
     * choosing which type the framework resolves and whether the parameter takes part in the job's
     * identity. This method never builds that carrier. It builds {@link JobParameters} directly, every
     * entry a string, every entry identifying, so the type is fixed here and the identifying flag is the
     * server's decision and not the caller's.
     *
     * <p><strong>Nothing is added to the parameters and nothing unknown is passed on.</strong> The same
     * name with the same values is the same job identity, so the framework answers a repeat out of its own
     * metadata rather than starting a second run - and because a name outside the job's schema is refused
     * rather than forwarded, a caller cannot manufacture a distinguishing parameter to defeat that. Which
     * values each accepted parameter must carry is still decided by the job's own validator during the
     * launch and is deliberately not restated here; this boundary decides only which names exist and that a
     * value is printable, comma-free single-byte text within {@link #MAX_PARAMETER_VALUE_LENGTH}.
     *
     * @param jobName       the stable job name, which must be one of {@link #LAUNCHABLE_JOB_NAMES}
     * @param jobParameters the parameters to launch with, bound from the request's query parameters; each
     *                      name must appear in {@link #ACCEPTED_JOB_PARAMETER_NAMES} for the addressed job
     *                      and each value is carried through unchanged once screened. An absent or empty
     *                      map launches the job with no parameters at all
     * @return {@code 200} carrying the execution identifier and the stable job name
     * @throws RecordNotFoundException if the name is outside the closed inventory
     * @throws ValidationException     if a supplied parameter name is not one the addressed job reads, if a
     *                                 value is refused on its shape, if the framework declined the launch
     *                                 because that job identity has already been used or is already
     *                                 running, or because the job's own validator rejected the parameters
     * @throws IllegalStateException   if an allow-listed name is not registered, which is a wiring fault
     *                                 rather than a caller fault
     */
    @PostMapping(path = LAUNCH_SUBPATH, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Launch one batch job on demand",
            description = "Starts one of the nine registered jobs from a closed typed parameter body. "
                    + "The batch-control surface requires administrative authority.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The job was started and the execution identifier is returned.")})
    public ResponseEntity<BatchJobLaunchResponse> launchTypedJob(
            @PathVariable(name = JOB_NAME_PATH_VARIABLE) final String jobName,
            @Valid @RequestBody(required = false) final BatchJobLaunchRequest launchRequest) {
        return launchJob(jobName, parametersOf(launchRequest));
    }

    @Operation(summary = "Launch one batch job on demand",
            description = "Starts one of the nine registered jobs by its stable name. Nothing runs when "
                    + "the application starts, so this is the only way a job begins. Each launch is "
                    + "serialized per job and its single identifying run parameter is minted on the "
                    + "server, so the same job submitted twice with the same parameters starts a second, "
                    + "distinct instance rather than being refused; a caller can neither supply that "
                    + "parameter nor influence it, because the only names accepted are the ones the "
                    + "addressed job declares. Parameter values are otherwise passed through unchanged "
                    + "and are validated by the job itself. The jobs are independent - there is no "
                    + "run-everything operation and no ordering imposed here. Requires the administrative "
                    + "authority: starting a job is an operational act no legacy transaction exposed, and "
                    + "one of the nine clears the transaction master.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The job was started. Carries the execution identifier to ask after and "
                        + "the stable job name it was started under."),
        @ApiResponse(responseCode = "400",
                description = "A parameter name is not one this job reads, a value was refused on its "
                        + "shape, the job refused the parameters supplied, or this job has already been "
                        + "run with them."),
        @ApiResponse(responseCode = "401",
                description = "No credential was presented, or the one presented did not verify."),
        @ApiResponse(responseCode = "403",
                description = "The credential presented verified but does not carry the administrative "
                        + "authority this surface requires."),
        @ApiResponse(responseCode = "404",
                description = "The name is not one of the nine jobs this surface will start.")})
    public ResponseEntity<BatchJobLaunchResponse> launchJob(
            @PathVariable(name = JOB_NAME_PATH_VARIABLE) final String jobName,
            @RequestParam final Map<String, String> jobParameters) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);

        if (!this.batchJobLaunchService.launchable(jobName)) {
            recordLaunch(sample, JOB_TAG_UNRECOGNISED, OUTCOME_ABSENT);
            LOG.warn("Batch job launch addressed a job outside the closed inventory");
            throw new RecordNotFoundException(RECORD_TYPE_BATCH_JOB, jobName);
        }

        final String stableJobName = jobName;

        final Long executionId;
        try {
            executionId = this.batchJobLaunchService.launch(stableJobName, jobParameters);
        } catch (final ValidationException refused) {
            recordLaunch(sample, stableJobName, OUTCOME_REFUSED);
            LOG.warn("Batch job launch refused: job={} reason=parameter-not-accepted", stableJobName);
            throw refused;
        } catch (final LaunchRejectedException rejected) {
            recordLaunch(sample, stableJobName, OUTCOME_REFUSED);
            LOG.warn("Batch job launch refused: job={} reason={}",
                    stableJobName, rejected.rejectionReason().name());
            throw new ValidationException(messageFor(rejected.rejectionReason()));
        } catch (final NoSuchJobException notRegistered) {
            recordLaunch(sample, stableJobName, OUTCOME_ABSENT);
            throw registrationFault(stableJobName, notRegistered);
        }

        recordLaunch(sample, stableJobName, OUTCOME_LAUNCHED);
        LOG.info("Batch job launched: job={} executionId={}", stableJobName, executionId);

        return ResponseEntity.ok(new BatchJobLaunchResponse(executionId, stableJobName));
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
            description = "Reports the stable job name, batch status and exit code for one execution.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The execution was found and its bounded status is returned.")})
    public ResponseEntity<BatchJobExecutionResponse> readTypedJobExecution(
            @PathVariable(name = EXECUTION_ID_PATH_VARIABLE) final long executionId) {
        return readJobExecution(executionId);
    }

    @Operation(summary = "Report one batch job execution",
            description = "Reads one execution of one of the nine registered jobs out of the framework's "
                    + "own metadata, which is the single source of truth for what ran. Answers the "
                    + "execution identifier, the stable job name, the batch status and the exit code, and "
                    + "nothing else: no exit description, no parameter set and no step detail, because "
                    + "those carry internal detail. An execution this surface does not own reads as "
                    + "absent. Requires the administrative authority, as the whole batch-control surface "
                    + "does: what has run and how it ended is operational detail an ordinary signed-on "
                    + "caller was never able to ask for.")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The execution was found. Carries its identifier, the stable job name, the "
                        + "batch status and the exit code."),
        @ApiResponse(responseCode = "400",
                description = "The execution identifier was not a number."),
        @ApiResponse(responseCode = "401",
                description = "No credential was presented, or the one presented did not verify."),
        @ApiResponse(responseCode = "403",
                description = "The credential presented verified but does not carry the administrative "
                        + "authority this surface requires."),
        @ApiResponse(responseCode = "404",
                description = "No such execution is held, or it belongs to a job this surface does not "
                        + "own.")})
    public ResponseEntity<BatchJobExecutionResponse> readJobExecution(
            @PathVariable(name = EXECUTION_ID_PATH_VARIABLE) final long executionId) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);

        final BatchJobLaunchService.JobExecutionReport report =
                this.batchJobLaunchService.readExecution(executionId);
        if (report == null) {
            recordStatus(sample, JOB_TAG_UNRECOGNISED, OUTCOME_ABSENT);
            LOG.debug("Batch job execution not reportable: executionId={}", executionId);
            throw new RecordNotFoundException(RECORD_TYPE_JOB_EXECUTION, Long.toString(executionId));
        }

        final String stableJobName = report.jobName();

        recordStatus(sample, stableJobName, OUTCOME_REPORTED);
        LOG.debug("Batch job execution reported: job={} executionId={}", stableJobName, executionId);

        return ResponseEntity.ok(new BatchJobExecutionResponse(
                executionId, stableJobName, report.status(), report.exitCode()));
    }

    /**
     * Projects the typed transport request onto the service's neutral name/value boundary.
     *
     * @param request typed request, or {@code null} for a launch with no parameters
     * @return only supplied parameter names and their unmodified values
     */
    private static Map<String, String> parametersOf(final BatchJobLaunchRequest request) {
        if (request == null) {
            return Map.of();
        }
        final Map<String, String> parameters = new HashMap<>();
        putIfPresent(parameters, "interestParmDate", request.interestParmDate());
        putIfPresent(parameters, "reportStartDate", request.reportStartDate());
        putIfPresent(parameters, "reportEndDate", request.reportEndDate());
        putIfPresent(parameters, "fileProbeMode", request.fileProbeMode());
        return Map.copyOf(parameters);
    }

    private static void putIfPresent(final Map<String, String> parameters,
                                     final String name,
                                     final String value) {
        if (value != null) {
            parameters.put(name, value);
        }
    }

    // ==================================================================================================
    // Adapting an allow-listed request onto the service's own calls
    // ==================================================================================================

    private static String messageFor(final RejectionReason reason) {
        return switch (Objects.requireNonNull(reason, "reason must not be null")) {
            case ACTIVE_EXECUTION -> ACTIVE_EXECUTION_MESSAGE;
            case INSTANCE_ALREADY_EXISTS -> INSTANCE_ALREADY_EXISTS_MESSAGE;
            case INVALID_PARAMETERS -> PARAMETERS_INVALID_MESSAGE;
        };
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
