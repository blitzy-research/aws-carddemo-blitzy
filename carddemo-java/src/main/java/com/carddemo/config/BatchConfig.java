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
package com.carddemo.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.carddemo.batch.step.StagedGenerationStore;
import com.carddemo.service.JobCompletionEvent;
import com.carddemo.service.JobCompletionEventPublisher;
import com.carddemo.util.SanitisedObservation;

/**
 * Batch <em>infrastructure</em> for the migrated job tier: the small set of collaborators every job
 * configuration in {@code com.carddemo.batch} shares, and nothing else. It defines no job, no step, no
 * reader, no writer and no processor &mdash; each of the nine job configurations owns those, and
 * {@code com.carddemo.batch.step.AbstractCobolStep} owns the open / read-loop / status-check / close /
 * abend skeleton the batch programs share.
 *
 * <h2>Why there are nine job configurations and not seventy-nine</h2>
 *
 * <p>An executed census over all twenty-nine job members in {@code app/jcl} (twenty-eight lower-case and
 * one upper-case extension) and both cataloged procedures in {@code app/proc} finds <strong>seventy-nine
 * job steps that name a program to run</strong>. Only <strong>nine</strong> of them name an application
 * program. The other seventy are utility work: fifty-two name {@code IDCAMS} (dataset define, delete and
 * unload), eight name {@code SDSF} (spool inspection), five name {@code SORT} (external ordering), three
 * name {@code IEFBR14} (the null program, used for allocation and deletion), one names {@code IEBGENER}
 * (in-stream copy) and one names {@code DFHCSDUP} (the online resource-definition utility).
 *
 * <p>Those seventy do not become steps in any form. Dataset definition and unload are absorbed by the
 * four Flyway migrations and by the Compose stack; external ordering becomes a comparator or a query
 * predicate inside the one job that needs it; spool inspection, the null program and the resource
 * utility have no runtime equivalent at all once the indexed files are replaced. <strong>No step in this
 * module runs a utility</strong>, and none may be added that does.
 *
 * <p>{@code docs/traceability-matrix.md} carries the step-by-step inventory: which member and line each of
 * the nine application steps comes from, and which job configuration it becomes. Three further steps reach
 * the cataloged unload procedure rather than a program directly, at {@code app/jcl/PRTCATBL.jcl:29},
 * {@code app/jcl/TRANBKP.jcl:23} and {@code app/jcl/TRANREPT.jcl:23}; the procedure itself, at
 * {@code app/proc/REPROC.prc:21}, is one of the fifty-two utility steps counted above. Three members
 * &mdash; {@code app/jcl/CLOSEFIL.jcl}, {@code app/jcl/OPENFIL.jcl} and {@code app/jcl/CBADMCDJ.jcl}
 * &mdash; only toggle online file availability and drive the resource-definition utility; they are
 * <strong>intentionally not migrated</strong>, which is a recorded decision rather than an omission.
 *
 * <h2>All four step gates are the one strict form: every earlier step must have returned zero</h2>
 *
 * <p>The migration plan states that all four condition-code step gates in the estate demand a prior
 * return code of exactly zero, and that is the frozen contract this module implements. Six
 * condition-code occurrences exist, of which only four are step gates: three on STEP020, STEP030 and
 * STEP040 of {@code app/jcl/CREASTMT.JCL} at lines 56, 66 and 79, and one on STEP10 of
 * {@code app/jcl/TRANBKP.jcl:51}. The remaining two occurrences, at {@code app/jcl/TRANREPT.jcl:47} and
 * {@code app/proc/TRANREPT.prc:45}, select records inside an ordering step and are not step gates at all.
 *
 * <p>{@link ConditionCodeGate} therefore carries <strong>one</strong> constant and not two, and a second
 * ceiling must never be introduced beside it. A looser ceiling anywhere would run a guarded step the
 * plan holds to zero.
 *
 * <p><strong>One measured divergence, recorded rather than implemented.</strong> The gate literal on
 * {@code app/jcl/TRANBKP.jcl:51} is not spelled the same way as the three statement-job literals, and
 * read on its own it would admit a prior warning. The plan is the frozen source of truth for this
 * migration and it defines all four gates as the strict form, so the strict form is what is
 * implemented here and the divergence is carried as a recorded decision instead. See
 * {@code docs/decision-log.md} entry DL-145. That observation is metadata about the estate; it is
 * deliberately not expressed as behaviour anywhere in this module.
 *
 * <h2>The category-balance report has no application step</h2>
 *
 * <p>{@code app/jcl/PRTCATBL.jcl} names no application program at all: its steps are a delete-and-define
 * step naming the null program at line 21, an unload step reaching the cataloged procedure at line 29, and
 * an ordering step at line 43 that carries <strong>its own</strong> symbol declarations at lines 47 to 50
 * over the transaction-category-balance layout &mdash; account identifier at offset 1 for eleven
 * zoned-decimal bytes, type code at offset 12 for two character bytes, category code at offset 14 for four
 * zoned-decimal bytes and the balance at offset 18 for eleven zoned-decimal bytes &mdash; a three-key
 * ascending order, and a reprojection at line 56 whose balance field carries an edit mask rendering nine
 * integer digits, a decimal point, two fractional digits and trailing filler.
 *
 * <p>{@code batch/CategoryBalanceReportJobConfig} therefore derives its ordering and its formatted output
 * from that member's own ordering specification rather than from an application program, and the estate
 * holds <strong>four</strong> distinct external ordering specifications, the fourth being this one.
 * Program CBACT03C is named by {@code app/jcl/READXREF.jcl} and by nothing else.
 *
 * <h2>What this class deliberately does not declare, and why</h2>
 *
 * <p><strong>No enabling annotation.</strong> Under this framework generation the batch
 * auto-configuration is guarded by a condition that backs off when the enabling annotation is present, so
 * adding it <em>switches off</em> the very infrastructure it appears to switch on and leaves the job
 * repository and its data source unwired. The application entry point carries only its own
 * application-level annotation for the same reason, and neither file may acquire an enabling annotation.
 *
 * <p><strong>No job repository, launcher, explorer, registry or operator.</strong> All five are already
 * published by the auto-configuration, together with the initialiser that registers every job bean into
 * the registry by name. Declaring any of them here would either duplicate a bean definition or shadow the
 * framework's own wiring, so {@code api/BatchJobController} injects them directly and this class stays out
 * of the way. The deprecated no-argument launcher and operator factory methods are never called.
 *
 * <p><strong>No meter registry and no observation post-processor.</strong> The observability
 * post-processor that hands the observation registry to every job and step is already published when the
 * management dependency is present, so the framework's own job-level and step-level timings reach the
 * metrics scrape endpoint without anything being added here. {@code config/ObservabilityConfig} owns the
 * registry and the trace export; this class must not register a second registry, and must not suppress
 * what is already wired. {@code batch/step/AbstractCobolStep} additionally times each translated program
 * on its own timer.
 *
 * <p><strong>Publishing a measurement is not registering a registry, and this class does publish one.</strong>
 * Both registries are <em>injected</em> and remain owned elsewhere; what is added is a single counter and a
 * single observation, both at the job-boundary listener and both for one reason. The framework stops its
 * job timer and its job span <em>before</em> the terminal listener callbacks, and this module's required
 * durable boundary runs inside one of them and can turn a COMPLETED status into a persisted FAILED one. No
 * amount of framework telemetry can reflect that, because it was all recorded a moment earlier. So the
 * listener counts the persisted verdict alongside the observed one, and observes the object-store work that
 * would otherwise appear in no trace at all. Neither replaces a framework measurement; both exist because a
 * framework measurement cannot answer the question.
 *
 * <p><strong>No logger configuration.</strong> {@code logback-spring.xml} owns appender selection and the
 * correlation fields, and the shared configuration document and its overlays own every verbosity level,
 * including the two batch categories and the migration-tool category whose output the Compose validation
 * reads. Nothing here may pin, raise or silence a level.
 *
 * <p><strong>No schema work.</strong> The framework provisions its own metadata tables under the prefix
 * the shared configuration declares; those tables are additional to, never instead of, the eleven
 * application tables that {@code db/migration/schema/V1__create_schema.sql} creates, and a table census
 * must exclude that prefix. {@code config/FlywayConfig} owns migration behaviour, the delivered migration
 * inventory stays at exactly five scripts, and none of them may define a metadata table.
 *
 * <p><strong>No tuning of any kind.</strong> No chunk size, commit interval, task executor, thread count,
 * skip limit, retry limit, time-out or pool setting appears here, in code or in a comment. No numeric
 * service level is documented anywhere in the migrated estate, so performance for this implementation is
 * <em>measured and recorded</em> rather than asserted, and the connection pool keeps its shipped defaults.
 *
 * <p><strong>No concurrency.</strong> The migrated batch tier is strictly sequential, record at a time.
 * Parallel or partitioned execution would reorder output that four fixed-width formats are compared byte
 * for byte, so neither is introduced.
 *
 * <p><strong>No orchestrator.</strong> The estate holds no master scheduler; the order in which jobs run
 * is an operational convention. The nine jobs are therefore never chained into a super-job, and no job
 * that "runs everything" exists. {@code batch/DailyTransactionReadJobConfig} in particular translates a
 * complete program that no job member, procedure or online resource definition invokes, so it is defined
 * and exercised by tests only and is wired into nothing that runs by default.
 *
 * <h2>Nothing fires when the context starts</h2>
 *
 * <p>A legacy batch job was submitted deliberately; bringing an application up never triggered one. The
 * shared configuration document disables launch-on-start, which is what keeps the framework's own
 * start-up runner out of the context entirely, and jobs are launched on demand through
 * {@code api/BatchJobController}. This class contributes <strong>no</strong> start-up runner, lifecycle
 * participant, initialising callback, event listener or scheduled trigger, and it declares no job name for
 * anything to resolve. Both beans it does publish are inert until a job configuration attaches them.
 *
 * <h2>Provenance</h2>
 *
 * <p>Migrated from the CardDemo mainframe estate at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy tree is cited, never transcribed: no
 * job-control, program, copybook or online-resource text appears in this module, and nothing here reads
 * that tree at run time.
 *
 * <p>Stateless and immutable: final, no instance fields and no mutable static state. The job-boundary
 * bean resolves the optional AWS collaborators supplied by its method parameters once, then captures
 * those immutable references in the listener it returns. Focused tests without AWS infrastructure still
 * construct the listener; a real job that registered a durable artifact cannot silently complete when
 * the artifact store is absent.
 */
@Configuration(proxyBeanMethods = false)
public final class BatchConfig {

    /**
     * The one logger this configuration and its two member types write through.
     *
     * <p>Resolved from this class, so it sits under the application base category that the shipped
     * logging configuration declares and that the local overlay raises. The two batch package categories
     * that configuration also declares are deliberately <em>not</em> borrowed by name: this class is not
     * in either package, and naming one in a string here would create a second place where that package
     * is spelled out, which is exactly the drift the logging configuration is pinned against.</p>
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(BatchConfig.class);

    /** Placeholder written in a diagnostic when a job execution carries no instance to name. */
    private static final String UNNAMED_JOB = "(unnamed)";

    /**
     * A clean completion, and the ceiling every one of the four step gates demands.
     *
     * <p>Cited from the gates on STEP020, STEP030 and STEP040 of {@code app/jcl/CREASTMT.JCL} at lines 56,
     * 66 and 79 and on STEP10 of {@code app/jcl/TRANBKP.jcl:51}. A legacy contract value, not a tuning
     * figure.</p>
     *
     * <p>Declared here rather than on {@link ConditionCodeGate}, which is where it belongs semantically,
     * for one language reason: an enum constant's argument may not name a field declared later in the same
     * class body, and the enum constants must come first.</p>
     */
    private static final int RETURN_CODE_NORMAL = 0;

    /**
     * A remark, and the code a step contributes when it completed carrying an exit code of its own.
     *
     * <p>This is <strong>not</strong> a ceiling: no gate admits it. It is the value
     * {@link ConditionCodeGate#decide} attributes to a step that completed while saying something other
     * than a clean completion, so that such a step is refused by the one strict ceiling rather than being
     * read as clean. A legacy contract value, not a tuning figure.</p>
     */
    private static final int RETURN_CODE_WARNING = 4;

    /**
     * A failure, above the ceiling {@link ConditionCodeGate} holds, so the gate refuses.
     *
     * <p>The value the batch programs' own status normalisation produced for an error, cited at
     * {@code app/cbl/CBACT01C.cbl:L90} through {@code app/cbl/CBACT01C.cbl:L114} and already carried by
     * {@code batch/step/AbstractCobolStep} for the same reason. A legacy contract value, not a tuning
     * figure.</p>
     */
    private static final int RETURN_CODE_ERROR = 12;

    /**
     * The most decimal digits a completion code occupies, used only to bound a parse.
     *
     * <p>Reading an unbounded run of digits would overflow, and no completion code is longer than this, so
     * a longer run is not one. A parse bound, not a tuning figure.</p>
     */
    private static final int MAX_RETURN_CODE_DIGITS = 4;

    /**
     * Declared explicitly so that the absence of collaborators is visible and deliberate: batch
     * infrastructure is the layer the nine job configurations are built against, so it can depend on
     * nothing that is itself built against a job.
     */
    public BatchConfig() {
    }

    /**
     * The counter that carries the job verdict a reader can trust, published after publication.
     *
     * <h2>Why the framework's own job telemetry cannot carry it</h2>
     *
     * <p>The framework stops its job observation and its job timer <em>before</em> it calls the terminal
     * listener callbacks. This module's required durable boundary runs inside one of those callbacks, and
     * it can change a status of COMPLETED into a persisted status of FAILED. The framework's timer and
     * span have already been recorded by then, so they report the status the job had a moment earlier and
     * nothing in either of them ever changes. The ordering belongs to the framework and is not
     * configurable, so the only remedy available to this module is to publish its own measurement after
     * the verdict is final - which is what this counter is.
     *
     * <p>It is deliberately a counter rather than a second timer. Elapsed time is already measured, once,
     * correctly, by the framework; duplicating it would invite two answers to one question. What is
     * missing is not a duration but a <em>verdict</em>, so what is published is one count per terminal
     * job carrying the verdict that was persisted.
     *
     * <p>See {@code docs/decision-log.md} entry DL-305.
     */
    static final String TERMINAL_VERDICT_METER_NAME = "carddemo.batch.job.terminal";

    /** Description of {@link #TERMINAL_VERDICT_METER_NAME}, stated once. */
    private static final String TERMINAL_VERDICT_METER_DESCRIPTION =
            "Terminal outcomes of migrated CardDemo batch jobs, counted after the durable artifact"
                    + " boundary has been completed and the verdict is final";

    /**
     * Tag naming the job a terminal count belongs to.
     *
     * <p><strong>Deliberately not {@code job}.</strong> Prometheus stamps its own {@code job} label onto
     * every series it collects, naming the scrape target rather than the application dimension. Two
     * labels of one name cannot coexist, so the exporter's value is renamed to {@code exported_job} on
     * collection and every query grouping by {@code job} collapses to the single scrape target -
     * silently, with the panel still rendering. Recorded as {@code DL-338}.
     *
     * <p>Spelled in the camel case this module's other multi-word tag keys use - {@link #TAG_JOB_EXECUTION_ID}
     * is declared on the same meters - so one meter does not carry two naming conventions.
     */
    static final String TAG_JOB = "batchJob";

    /**
     * Tag carrying the status that was <em>persisted</em>, which is the authoritative one.
     */
    static final String TAG_PERSISTED_STATUS = "status";

    /**
     * Tag carrying the status the framework's own telemetry recorded, so a disagreement is queryable.
     *
     * <p>Its whole purpose is that the two values can differ. A series where this reads COMPLETED while
     * {@link #TAG_PERSISTED_STATUS} reads FAILED is exactly the case the framework's timer reports as a
     * success and the repository records as a failure, and having both on one meter makes that case a
     * query rather than an investigation. Both values come from a small closed enumeration, so the pair
     * adds no meaningful cardinality.</p>
     */
    static final String TAG_OBSERVED_STATUS = "observedStatus";

    /** Tag naming what the durable boundary did, so a verdict can be attributed to it or exonerated. */
    static final String TAG_PUBLICATION = "publication";

    /** Publication tag value for a job that registered no durable artifact. */
    static final String PUBLICATION_NOT_REQUIRED = "NOT_REQUIRED";

    /** Publication tag value for a job whose registered artifacts were all published. */
    static final String PUBLICATION_PUBLISHED = "PUBLISHED";

    /** Publication tag value for a job whose registered artifacts could not be published. */
    static final String PUBLICATION_FAILED = "FAILED";

    /** Publication tag value for a job that did not complete, so publication was skipped. */
    static final String PUBLICATION_SKIPPED = "SKIPPED";

    /**
     * Observation name covering the durable artifact boundary that runs after the job's own span closed.
     *
     * <p>The object-store work this callback performs was previously untraced in both directions: the
     * framework's job span was already closed, and nothing here opened one of its own, so uploads, alias
     * advances and retention deletions appeared in no trace at all. This observation makes that work
     * visible and gives the store's own per-operation observations a parent.
     *
     * <p><strong>It cannot be a child of the job's span, and that is a framework ordering rather than an
     * omission.</strong> The job observation is stopped before this callback is entered, so there is no
     * open span to descend from. What is carried instead is identity: the job name as a low-cardinality
     * tag and the execution identifier as a high-cardinality one, which is what lets a reader join this
     * work to the job execution it belongs to.
     */
    static final String PUBLICATION_OBSERVATION_NAME = "carddemo.batch.job.publication";

    /** Tag carrying the execution a publication belongs to, so it can be joined to the job's own record. */
    static final String TAG_JOB_EXECUTION_ID = "jobExecutionId";

    /** Tag carrying how many registered artifacts the publication covered. */
    static final String TAG_ARTIFACT_COUNT = "artifacts";

    /**
     * The one job-boundary diagnostic every job configuration attaches, announcing a job's start and its
     * terminal outcome.
     *
     * <p>The framework does not apply a listener bean to a job on its own, which is deliberate here: each
     * of the nine job configurations attaches this listener explicitly on its own builder, so a job
     * carrying no announcement is visible in that job's own source rather than hidden in a global
     * default.</p>
     *
     * <p><strong>What it reproduces.</strong> The migrated estate had two diagnostic layers and this
     * listener is the outer one. The job entry system announced that a job had begun and, at the end,
     * reported the completion code; the detail of what a program did came from console statements inside
     * the program. That division is preserved exactly: this listener reports the boundary and the terminal
     * status, and {@code batch/step/AbstractCobolStep} reports each translated program's own start, record
     * count, end and abend path.</p>
     *
     * <p><strong>What it will not write, and why.</strong> No throwable is ever handed to the logging
     * framework and the framework's exit <em>description</em> is never emitted, because that description
     * carries a rendered stack trace. Job parameter <em>keys</em> are reported and their
     * <strong>values are not</strong>: a parameter map is open to any future job, the log stream leaves
     * the process, and the values remain readable in the framework's own execution-parameter table, which
     * is reached through the database rather than through the log pipeline. This is the same allow-list
     * posture the shipped logging configuration takes with the diagnostic context.</p>
     *
     * <p><strong>Durable artifacts are finalised before the terminal outcome is announced.</strong>
     * Jobs register only atomically completed, execution-scoped files. On a clean job completion the
     * listener publishes them through {@link StagedGenerationStore}. A publication failure upgrades the
     * job to failed and is persisted by the framework after this callback; a job can therefore never
     * report a clean completion while its required external file drop is missing.
     *
     * <p><strong>Every terminal outcome is offered as a {@link JobCompletionEvent}.</strong> The event
     * is a parameter-free allow-list rather than a free-form log line. Operational fan-out is handled
     * by the event listener, whose send failure cannot rewrite the already-established batch verdict.
     * The event publisher remains optional so a focused unit context that exercises only condition-code
     * infrastructure does not need application-event or AWS wiring.
     *
     * <p><strong>A job that does not complete discards what it allocated.</strong> Publication is skipped
     * for such a job, so the local generations its steps sealed name nothing durable and any working file
     * they left names nothing at all. Both are removed here, by this execution's own generation token
     * only, which is the abnormal disposition of the legacy allocation and keeps a failed run from leaving
     * a dead artefact in the staging root. See {@code docs/decision-log.md} entry DL-211.
     *
     * <p><strong>It publishes one measurement of its own, and only one.</strong> The framework's job timer
     * and job span are stopped before this callback runs, so neither can carry a verdict this callback is
     * still able to change. {@link #TERMINAL_VERDICT_METER_NAME} therefore records the verdict that was
     * persisted, alongside the one the framework recorded, so the two can be compared rather than
     * confused. That is not a second registry and not a second timer: the meter registry is injected, the
     * observation registry is injected, and both remain owned by {@code config/ObservabilityConfig}.
     *
     * @param generationStores optional durable generation store
     * @param completionPublishers optional terminal application-event publisher
     * @param meterRegistries optional meter registry the terminal verdict is counted on
     * @param observationRegistries optional observation registry the durable boundary is observed into
     * @param stagingDirectory the shared local staging root swept after a job that did not complete
     * @return the shared job-boundary listener, never {@code null}
     */
    @Bean
    public JobExecutionListener batchJobBoundaryListener(
            final ObjectProvider<StagedGenerationStore> generationStores,
            final ObjectProvider<JobCompletionEventPublisher> completionPublishers,
            final ObjectProvider<MeterRegistry> meterRegistries,
            final ObjectProvider<ObservationRegistry> observationRegistries,
            @Value("${" + StagedGenerationStore.SHARED_STAGING_DIRECTORY_PROPERTY
                    + ":${java.io.tmpdir}}") final String stagingDirectory) {
        Objects.requireNonNull(generationStores, "generationStores");
        Objects.requireNonNull(completionPublishers, "completionPublishers");
        Objects.requireNonNull(meterRegistries, "meterRegistries");
        Objects.requireNonNull(observationRegistries, "observationRegistries");
        Objects.requireNonNull(stagingDirectory,
                StagedGenerationStore.SHARED_STAGING_DIRECTORY_PROPERTY);
        return new JobBoundaryListener(generationStores.getIfAvailable(),
                completionPublishers.getIfAvailable(), meterRegistries.getIfAvailable(),
                observationRegistries.getIfAvailable(ObservationRegistry::create),
                Path.of(stagingDirectory));
    }

    /**
     * The one parameter incrementer every job configuration attaches, so a job can be resubmitted.
     *
     * <p>A job member on the mainframe could be submitted again with an identical parameter set and would
     * simply run again. The framework refuses a second instance of a job whose identifying parameters have
     * already been used, so without an incrementer a second submission of, say, the posting job on the
     * same processing date would be rejected rather than run. Attaching this incrementer is what makes that
     * repeat expressible at all.</p>
     *
     * <p><strong>Who applies it, and what that means for a repeated request.</strong> The framework itself
     * advances it only through the operator's next-instance call; a plain start takes the parameters it is
     * given and advances nothing. This module therefore does not leave a plain start to the framework:
     * {@code batch/BatchLaunchCoordinator} mints the identifying value from this same incrementer on the
     * server, inside the advisory-locked coordination transaction, so an HTTP launch through
     * {@code api/BatchJobController} carries a fresh run identity and the same request submitted twice
     * starts two distinct instances rather than being answered out of the framework's metadata. That is
     * deliberate: the on-demand surface has to be able to run a job a second time - a repeat timing run,
     * or a rerun after the inputs were restaged - and the mainframe member it replaces could be submitted
     * again with an identical parameter set.
     *
     * <p>What a caller still cannot do is choose that value. The identifying parameter is minted by the
     * server from framework metadata and is not in the closed set of names
     * {@code api/BatchJobController} accepts, so a caller can neither set it nor collide with it. The
     * separate next-instance operation remains the way to repeat a run <em>without restating its
     * parameters</em>, which is a different thing from being the only way to repeat one at all.</p>
     *
     * <p>The framework's own run-identifier incrementer is used rather than a bespoke one: it contributes a
     * single monotonic identifying parameter and nothing else, so it cannot collide with the parameter keys
     * {@code batch/JobParameterValidators} owns and cannot change how any validator reads them.</p>
     *
     * @return the shared parameter incrementer, never {@code null}
     */
    @Bean
    public JobParametersIncrementer batchJobRunIncrementer() {
        return new RunIdIncrementer();
    }

    /**
     * The one condition-code step gate the estate uses, as a flow decider a job configuration places
     * between two steps.
     *
     * <h2>Why there is one constant and not two</h2>
     *
     * <p>{@link #ALL_PRIOR_STEPS_ZERO} admits nothing above zero: the guarded step runs only if the
     * highest return code any earlier step produced is exactly zero. All four step gates in the estate
     * are that one form &mdash; the three on STEP020, STEP030 and STEP040 of
     * {@code app/jcl/CREASTMT.JCL}, at lines 56, 66 and 79, and the one on STEP10 of
     * {@code app/jcl/TRANBKP.jcl:51} &mdash; so one constant serves all four and a second ceiling would
     * describe a gate that does not exist. The migration plan is the frozen contract on this point and it
     * states the strict form for all four; the divergent spelling of the fourth literal is recorded in
     * {@code docs/decision-log.md} entry DL-145 rather than implemented.</p>
     *
     * <p><strong>A looser ceiling must never be introduced.</strong> Admitting a warning anywhere would
     * run a guarded step the plan holds to zero, which is a behavioural change no test could then
     * detect, because the tests would have been written against the looser rule. The two other
     * condition-code occurrences in the estate, at {@code app/jcl/TRANREPT.jcl:47} and
     * {@code app/proc/TRANREPT.prc:45}, select records inside an ordering step and are <em>not</em> step
     * gates; nothing here models them.
     *
     * <h2>How a job configuration uses it</h2>
     *
     * <p>The constant is placed in the flow where the guarded step would otherwise follow directly, and
     * the two outcomes are routed on the names {@link #PERMITTED} and {@link #REFUSED} &mdash; the guarded
     * step on the first, and the end of the flow on the second, which is what the legacy gate did when it
     * refused. Because the constant is a singleton and holds no state, it serves every job that needs a
     * gate.</p>
     *
     * <h2>How a return code is determined</h2>
     *
     * <p>The framework records an exit status per step rather than a numeric completion code, so
     * {@link #decide} derives one from every step execution the job has accumulated and gates on the
     * highest, exactly as the legacy gate compared against the highest code so far. The derivation, in
     * order:</p>
     *
     * <ol>
     *   <li>A step that has not ended contributes nothing, because no completion code exists for it yet.
     *       In a strictly sequential tier the only such step is the one whose completion invoked this
     *       decider.</li>
     *   <li>A step that ended unsuccessfully, or was stopped, contributes
     *       {@link BatchConfig#RETURN_CODE_ERROR} &mdash; above the ceiling, so the gate refuses.</li>
     *   <li>A step that completed carrying one of the framework's own exit codes, or carrying none at
     *       all, contributes {@link BatchConfig#RETURN_CODE_NORMAL}. A step that reported nothing
     *       reported nothing wrong, so it must not be read as though it had.</li>
     *   <li>A step that completed carrying any other exit code contributes
     *       {@link BatchConfig#RETURN_CODE_WARNING}, which is above the ceiling and therefore refused.
     *       This is what makes the strict rule complete rather than partial: a step that completed while
     *       reporting a remark of its own is refused just as a numeric code above zero is, so a remark
     *       cannot slip a guarded step past a gate that a bare framework-status transition would have
     *       admitted.</li>
     * </ol>
     *
     * <p><strong>Why the status is read first, and why that ordering is the correction.</strong> The two
     * channels can disagree, and the framework lets them: a step's status and its exit code are set
     * independently, so a failed step can carry an exit code that reads as a completion code, and an exit
     * status can be overwritten by a listener or an exception handler while the status stands. Reading the
     * exit code first therefore let a numeric code speak for a step that had failed, and let the
     * framework's own failure and stop codes - which are non-numeric and were previously grouped with the
     * codes that mean "nothing to report" - contribute a clean zero. Either one admits a guarded step the
     * legacy stream would have skipped, which is a behavioural regression rather than tolerance. The rule
     * is now that only a completed outcome may contribute a normal or a declared code; an unsuccessful one
     * contributes the error code whatever else it carries.</p>
     */
    public enum ConditionCodeGate implements JobExecutionDecider {

        /**
         * Run the guarded step only if every earlier step reported exactly zero.
         *
         * <p>The gate on STEP020, STEP030 and STEP040 of {@code app/jcl/CREASTMT.JCL}, at lines 56, 66
         * and 79, and on STEP10 of {@code app/jcl/TRANBKP.jcl:51}. All four step gates in the estate are
         * this one form; see {@code docs/decision-log.md} entry DL-145.</p>
         */
        ALL_PRIOR_STEPS_ZERO(RETURN_CODE_NORMAL);

        /**
         * The outcome name a job configuration routes the guarded step from.
         *
         * <p>Deliberately not one of the framework's own status names: routing on a framework name would
         * make a gate verdict indistinguishable from an ordinary step outcome in the same flow.</p>
         */
        public static final String PERMITTED = "CONDITION_CODE_PERMITTED";

        /** The outcome name a job configuration routes the end of the flow from. */
        public static final String REFUSED = "CONDITION_CODE_REFUSED";

        /** Shared immutable verdict for the permitting outcome. */
        private static final FlowExecutionStatus PERMITTED_STATUS = new FlowExecutionStatus(PERMITTED);

        /** Shared immutable verdict for the refusing outcome. */
        private static final FlowExecutionStatus REFUSED_STATUS = new FlowExecutionStatus(REFUSED);

        /**
         * The framework's own <strong>non-terminal</strong> exit codes, which carry no completion code of
         * their own.
         *
         * <p>Held as an immutable set so that a step completing with one of them is read as a clean
         * completion, while a step completing with anything else is read as having something to say.</p>
         *
         * <p><strong>The framework's failure, stop and unknown codes are deliberately absent.</strong> They
         * were once members, which meant a step carrying one of them could be read as having nothing to
         * report. That is only reachable when the status and the exit code disagree - and when they
         * disagree, the code that names a failure is the one that must not be treated as silence. A step
         * whose status is a clean completion while its exit code says failed, stopped or unknown therefore
         * contributes a warning, which the strict gate refuses and the tolerant gate admits, rather than a
         * zero that every gate admits.</p>
         */
        private static final Set<String> FRAMEWORK_EXIT_CODES = Set.of(
                ExitStatus.COMPLETED.getExitCode(),
                ExitStatus.EXECUTING.getExitCode(),
                ExitStatus.NOOP.getExitCode());

        /**
         * The one terminal status a step may carry and still contribute a normal or a declared code.
         *
         * <p>A completion, and nothing else. The framework's remaining terminal statuses - stopped, failed,
         * abandoned and unknown - are each either unsuccessful or indeterminate, and the estate's gates
         * existed precisely to stop a downstream step after one of those. The running statuses never reach
         * this comparison: they are answered before it, because a step that has not ended has no completion
         * code yet.</p>
         */
        private static final BatchStatus CLEANLY_ENDED_STATUS = BatchStatus.COMPLETED;

        /** The ceiling this constant admits, inclusive. */
        private final int highestToleratedReturnCode;

        ConditionCodeGate(final int highestToleratedReturnCode) {
            this.highestToleratedReturnCode = highestToleratedReturnCode;
        }

        /**
         * The highest earlier return code this gate admits, inclusive.
         *
         * @return zero, the ceiling every step gate in the estate demands
         */
        public int highestToleratedReturnCode() {
            return this.highestToleratedReturnCode;
        }

        /**
         * Whether this gate admits a given highest-so-far return code.
         *
         * <p>Separated from {@link #decide} so the ceiling can be asserted without assembling a job
         * execution, and so a job configuration can ask the question directly.</p>
         *
         * @param highestPriorReturnCode the highest return code any earlier step produced, never negative
         * @return {@code true} when the guarded step runs
         * @throws IllegalArgumentException if the supplied code is negative, which no completion code is
         */
        public boolean permits(final int highestPriorReturnCode) {
            if (highestPriorReturnCode < RETURN_CODE_NORMAL) {
                throw new IllegalArgumentException(
                        "a return code is never negative: " + highestPriorReturnCode);
            }
            return highestPriorReturnCode <= this.highestToleratedReturnCode;
        }

        /**
         * Evaluates the gate against everything the job has done so far.
         *
         * <p>The verdict is logged at every evaluation, because a step that silently did not run is the
         * hardest kind of batch outcome to explain after the fact, and the job entry system it replaces
         * said so on the job's own output.</p>
         *
         * @param jobExecution the job whose accumulated step outcomes are gated on, never {@code null}
         * @param stepExecution the step whose completion reached this gate; may be {@code null} when the
         *                      gate is the first thing in the flow, and is not read either way because the
         *                      gate compares against the highest code so far rather than the latest one
         * @return {@link #PERMITTED_STATUS} or {@link #REFUSED_STATUS}, never {@code null}
         * @throws NullPointerException if {@code jobExecution} is {@code null}
         */
        @Override
        public FlowExecutionStatus decide(final JobExecution jobExecution,
                final StepExecution stepExecution) {
            Objects.requireNonNull(jobExecution, "jobExecution");

            final int highest = highestReturnCodeSoFar(jobExecution);
            final boolean permitted = permits(highest);
            if (permitted) {
                LOGGER.info("CONDITION CODE GATE {} PERMITS THE NEXT STEP OF JOB {}"
                        + " - highestPriorReturnCode={} highestTolerated={}",
                        name(), jobNameOf(jobExecution), highest, this.highestToleratedReturnCode);
            } else {
                LOGGER.warn("CONDITION CODE GATE {} REFUSES THE NEXT STEP OF JOB {}; THE STEP WAS NOT"
                        + " EXECUTED - highestPriorReturnCode={} highestTolerated={}",
                        name(), jobNameOf(jobExecution), highest, this.highestToleratedReturnCode);
            }
            return permitted ? PERMITTED_STATUS : REFUSED_STATUS;
        }

        /**
         * One placement of this gate in one flow, distinct from every other placement.
         *
         * <p><strong>Why a flow that gates more than one step needs this.</strong> The framework's flow
         * builder keeps the decision states it creates in a map keyed by the object handed to it, and it
         * creates a state only when that key is absent. A constant of this enumeration is a singleton, so
         * placing it at three points of one flow finds the key present twice and yields <em>one</em>
         * decision state carrying all three pairs of outgoing transitions. Every gate in that flow then
         * routes wherever the first registered pair pointed, which sends the flow back to the step it had
         * just run instead of onward to the next one - a loop that runs a step twice and abends the second
         * time, having already consumed the input the first pass staged. A flow that places a gate exactly
         * once, as the backup job's does, is unaffected and may use the constant directly.
         *
         * <p>Each call answers a new placement that evaluates this constant's rule and nothing else, so
         * every gate of a flow asks the identical question - the highest return code so far against this
         * constant's ceiling - while occupying its own state. Two placements are never equal, which is the
         * property the keying depends on: a value-equal placement would collapse back into one key and
         * restore the defect.
         *
         * @return a decider that answers exactly as this constant does, never {@code null} and never equal
         *         to another placement
         */
        public JobExecutionDecider atOnePlacement() {
            return new GatePlacement(this);
        }

        /**
         * One placement of a condition-code gate at one point of one flow.
         *
         * <p>Deliberately a class rather than a record, and deliberately without an equality of its own.
         * The flow builder keys its states by this object, so identity equality is the whole purpose:
         * component-wise equality - which a record would generate - would make two placements of the same
         * gate equal, collapse them into a single key and reinstate the shared-state defect that
         * {@link #atOnePlacement()} exists to prevent.
         *
         * <p>It holds no state beyond the rule it delegates to and reads nothing else, so every placement
         * of one gate reaches the identical verdict on the identical execution.
         */
        private static final class GatePlacement implements JobExecutionDecider {

            /** The gate whose rule this placement evaluates; never {@code null}. */
            private final ConditionCodeGate rule;

            /**
             * @param rule the gate this placement stands in for
             */
            GatePlacement(final ConditionCodeGate rule) {
                this.rule = Objects.requireNonNull(rule, "rule");
            }

            /**
             * Evaluates the gate's rule, which is the only thing this placement does.
             *
             * @param jobExecution the job whose accumulated step outcomes are gated on
             * @param stepExecution the step whose completion reached this placement, possibly {@code null}
             * @return the gate's verdict, never {@code null}
             */
            @Override
            public FlowExecutionStatus decide(final JobExecution jobExecution,
                    final StepExecution stepExecution) {
                return this.rule.decide(jobExecution, stepExecution);
            }

            /**
             * Names the rule rather than this object, so a flow diagnostic reads as the gate it is.
             *
             * @return the gate's constant name followed by the word placement
             */
            @Override
            public String toString() {
                return this.rule.name() + " placement";
            }
        }

        /**
         * The highest return code any step of this job has produced.
         *
         * @param jobExecution the job to scan
         * @return the highest contributed code, never below {@link BatchConfig#RETURN_CODE_NORMAL}
         */
        private static int highestReturnCodeSoFar(final JobExecution jobExecution) {
            int highest = RETURN_CODE_NORMAL;
            for (final StepExecution stepExecution : jobExecution.getStepExecutions()) {
                if (stepExecution == null) {
                    continue;
                }
                final int contributed = returnCodeOf(stepExecution);
                if (contributed > highest) {
                    highest = contributed;
                }
            }
            return highest;
        }

        /**
         * The return code one step execution contributes, by the order documented on this enum.
         *
         * @param stepExecution the step to read
         * @return the contributed code, never negative
         */
        private static int returnCodeOf(final StepExecution stepExecution) {
            final BatchStatus status = stepExecution.getStatus();
            if (status == null || status.isRunning()) {
                return RETURN_CODE_NORMAL;
            }
            // The terminal status is decisive and is read FIRST. A failed, stopped, abandoned or unknown
            // step contributes the error code whatever its exit code happens to say, because the two are
            // set independently and a numeric code must never speak for a step that did not complete.
            if (status != CLEANLY_ENDED_STATUS) {
                return RETURN_CODE_ERROR;
            }

            final ExitStatus exitStatus = stepExecution.getExitStatus();
            final String exitCode = exitStatus == null ? null : exitStatus.getExitCode();

            final OptionalInt declared = declaredReturnCode(exitCode);
            if (declared.isPresent()) {
                return declared.getAsInt();
            }
            if (exitCode == null || exitCode.isBlank() || FRAMEWORK_EXIT_CODES.contains(exitCode)) {
                return RETURN_CODE_NORMAL;
            }
            return RETURN_CODE_WARNING;
        }

        /**
         * Reads an exit code that is written as a completion code.
         *
         * <p>Only ASCII digits are accepted, and the digits are counted before they are read. Accepting
         * whatever the platform considers a digit would let a value outside ASCII be read as a completion
         * code, and reading an unbounded run of digits would overflow; a completion code occupies at most
         * {@link BatchConfig#MAX_RETURN_CODE_DIGITS} decimal digits, so anything longer is not one.</p>
         *
         * @param exitCode the exit code to read, possibly {@code null}
         * @return the completion code it states, or empty when it states none
         */
        private static OptionalInt declaredReturnCode(final String exitCode) {
            if (exitCode == null || exitCode.isEmpty()
                    || exitCode.length() > MAX_RETURN_CODE_DIGITS) {
                return OptionalInt.empty();
            }
            for (int position = 0; position < exitCode.length(); position++) {
                final char character = exitCode.charAt(position);
                if (character < '0' || character > '9') {
                    return OptionalInt.empty();
                }
            }
            return OptionalInt.of(Integer.parseInt(exitCode));
        }
    }

    /**
     * The job name to write into a diagnostic, or a placeholder when there is none.
     *
     * <p>A job execution normally carries the instance it belongs to, and that instance names the job. One
     * assembled directly rather than through the repository need not, so the placeholder keeps a
     * diagnostic readable instead of letting it fail on the value it was written to explain.</p>
     *
     * @param jobExecution the execution to name, never {@code null}
     * @return the job name, or {@link #UNNAMED_JOB} when the execution carries no instance
     */
    private static String jobNameOf(final JobExecution jobExecution) {
        final JobInstance jobInstance = jobExecution.getJobInstance();
        if (jobInstance == null) {
            return UNNAMED_JOB;
        }
        final String jobName = jobInstance.getJobName();
        return jobName == null || jobName.isEmpty() ? UNNAMED_JOB : jobName;
    }

    /**
     * The job parameter keys of an execution, in a stable order and without their values.
     *
     * <p>Sorted so two runs of the same job produce the same diagnostic, which is what makes one log line
     * comparable with another. The values are withheld deliberately; see the job-boundary listener
     * bean for why.</p>
     *
     * @param jobExecution the execution to read, never {@code null}
     * @return the keys in ascending order, empty when the job takes no parameter
     */
    private static Set<String> parameterKeysOf(final JobExecution jobExecution) {
        final JobParameters parameters = jobExecution.getJobParameters();
        if (parameters == null) {
            return Set.of();
        }
        return new TreeSet<>(parameters.getParameters().keySet());
    }

    /**
     * The names of the steps of an execution that did not complete cleanly, in encounter order.
     *
     * <p>Named rather than counted, because on a job with several steps the identity of the one that
     * failed is the whole diagnostic. Only step names and framework status names are reported, both of
     * which are authored in this module or by the framework and neither of which can carry data.</p>
     *
     * @param jobExecution the execution to scan, never {@code null}
     * @return the unsuccessful step names, empty when every step completed
     */
    private static List<String> unsuccessfulStepNamesOf(final JobExecution jobExecution) {
        final List<String> unsuccessful = new ArrayList<>();
        for (final StepExecution stepExecution : jobExecution.getStepExecutions()) {
            if (stepExecution == null) {
                continue;
            }
            final BatchStatus status = stepExecution.getStatus();
            if (status != null && status != BatchStatus.COMPLETED && !status.isRunning()) {
                unsuccessful.add(stepExecution.getStepName() + '=' + status);
            }
        }
        return unsuccessful;
    }

    /**
     * The shared job-boundary diagnostic, durable publisher and terminal notifier.
     *
     * <p>Its collaborators are immutable and every callback derives state only from the supplied job
     * execution, so one instance serves every job and no concurrent run can observe another's progress.
     * It is reached only through the interface it implements, so a job configuration cannot come to
     * depend on anything but the contract.</p>
     *
     * <p>Both callbacks reject a {@code null} execution rather than logging around it. A {@code null} there
     * is a defect in the caller, not a runtime condition, and the framework contains a listener failure and
     * reports it, so failing loudly costs nothing and hiding it would cost the diagnostic.</p>
     */
    private static final class JobBoundaryListener implements JobExecutionListener {

        /** Durable artifact publisher, absent only in a focused context with no AWS infrastructure. */
        private final StagedGenerationStore generationStore;

        /** Terminal event publisher, absent only in a focused context without component scanning. */
        private final JobCompletionEventPublisher completionPublisher;

        /** Local staging root swept after a job that did not complete. */
        private final Path stagingDirectory;

        /**
         * The registry the authoritative terminal verdict is counted on, or {@code null}.
         *
         * <p>Optional for the same reason the two AWS collaborators are: a focused unit context that
         * exercises only condition-code infrastructure registers no metrics wiring, and a diagnostic
         * listener must not be the reason such a context cannot start. When it is absent the verdict is
         * still logged; what is lost is only the series.
         */
        private final MeterRegistry meterRegistry;

        /** The registry the durable boundary is observed into; never {@code null}, no-op when unwired. */
        private final ObservationRegistry observationRegistry;

        /**
         * @param generationStore durable artifact publisher, or {@code null}
         * @param completionPublisher terminal event publisher, or {@code null}
         * @param meterRegistry registry for the terminal verdict count, or {@code null}
         * @param observationRegistry registry the durable boundary is observed into, never {@code null}
         * @param stagingDirectory local staging root, never {@code null}
         */
        private JobBoundaryListener(final StagedGenerationStore generationStore,
                final JobCompletionEventPublisher completionPublisher,
                final MeterRegistry meterRegistry,
                final ObservationRegistry observationRegistry,
                final Path stagingDirectory) {
            this.generationStore = generationStore;
            this.completionPublisher = completionPublisher;
            this.meterRegistry = meterRegistry;
            this.observationRegistry =
                    Objects.requireNonNull(observationRegistry, "observationRegistry");
            this.stagingDirectory = Objects.requireNonNull(stagingDirectory, "stagingDirectory");
        }

        /**
         * Announces that a job has begun, naming it and the parameter keys it was given.
         *
         * @param jobExecution the execution about to run, never {@code null}
         * @throws NullPointerException if {@code jobExecution} is {@code null}
         */
        @Override
        public void beforeJob(final JobExecution jobExecution) {
            Objects.requireNonNull(jobExecution, "jobExecution");
            LOGGER.info("START OF JOB {} - jobInstanceId={} jobExecutionId={} parameterKeys={}",
                    jobNameOf(jobExecution), jobExecution.getJobId(), jobExecution.getId(),
                    parameterKeysOf(jobExecution));
        }

        /**
         * Reports the terminal outcome of a job at one of three levels.
         *
         * <p>An unsuccessful terminal status is an error and names the steps responsible; a status that is
         * neither successful nor unsuccessful &mdash; a job that was stopped &mdash; is a warning, because
         * work remains undone without anything having gone wrong; a clean completion is informational. The
         * exit <em>code</em> is reported and the exit <em>description</em> never is, because the framework
         * renders a stack trace into that description.</p>
         *
         * @param jobExecution the execution that has ended, never {@code null}
         * @throws NullPointerException if {@code jobExecution} is {@code null}
         */
        @Override
        public void afterJob(final JobExecution jobExecution) {
            Objects.requireNonNull(jobExecution, "jobExecution");

            // Read BEFORE the durable boundary runs, because this is the status the framework's own job
            // timer and job span have already recorded: both are stopped before this callback is entered.
            // Publication can still change it, and the point of keeping both values is that the change
            // is then visible on one meter instead of being a disagreement between two systems.
            final BatchStatus observedStatus = jobExecution.getStatus();

            final String publicationOutcome = publishDurableArtifacts(jobExecution);

            final String jobName = jobNameOf(jobExecution);
            final BatchStatus status = jobExecution.getStatus();
            final ExitStatus exitStatus = jobExecution.getExitStatus();
            final String exitCode = exitStatus == null ? null : exitStatus.getExitCode();
            final int stepsExecuted = jobExecution.getStepExecutions().size();

            if (status != null && status.isUnsuccessful()) {
                LOGGER.error("JOB {} TERMINATED ABNORMALLY - jobInstanceId={} jobExecutionId={} status={}"
                        + " exitCode={} stepsExecuted={} unsuccessfulSteps={} failureCount={}",
                        jobName, jobExecution.getJobId(), jobExecution.getId(), status, exitCode,
                        stepsExecuted, unsuccessfulStepNamesOf(jobExecution),
                        jobExecution.getAllFailureExceptions().size());
            } else if (status != BatchStatus.COMPLETED) {
                LOGGER.warn("JOB {} ENDED WITHOUT COMPLETING - jobInstanceId={} jobExecutionId={} status={}"
                        + " exitCode={} stepsExecuted={}",
                        jobName, jobExecution.getJobId(), jobExecution.getId(), status, exitCode,
                        stepsExecuted);
            } else {
                LOGGER.info("END OF JOB {} - jobInstanceId={} jobExecutionId={} status={} exitCode={}"
                        + " stepsExecuted={}",
                        jobName, jobExecution.getJobId(), jobExecution.getId(), status, exitCode,
                        stepsExecuted);
            }
            countTerminalVerdict(jobName, observedStatus, status, publicationOutcome);
            publishTerminalEvent(jobExecution, jobName, status, exitCode, stepsExecuted);
        }

        /**
         * Counts one terminal job outcome, carrying the verdict that was persisted.
         *
         * <p>This is the module's answer to a framework ordering it cannot change: the job timer and the
         * job span are stopped before this callback runs, and the durable boundary inside it can turn a
         * status of COMPLETED into a persisted status of FAILED. Standard batch telemetry therefore
         * reports the earlier value forever. Publishing the later one here, next to the earlier one, makes
         * the two comparable - a series carrying an observed COMPLETED and a persisted FAILED is precisely
         * the case that used to be invisible, and it is now a query.
         *
         * <p>Non-fatal by construction. The verdict is already persisted and already logged; a metrics
         * registry that refuses a meter must not be able to change a job's outcome, so a failure here is
         * reported at debug level and the verdict stands. When no registry is wired at all - the focused
         * unit context - nothing is published and nothing is lost but the series.
         *
         * @param jobName          the job that ended
         * @param observedStatus   the status the framework's own telemetry recorded, possibly {@code null}
         * @param persistedStatus  the status that was persisted after publication, possibly {@code null}
         * @param publicationOutcome what the durable boundary did
         */
        private void countTerminalVerdict(final String jobName, final BatchStatus observedStatus,
                final BatchStatus persistedStatus, final String publicationOutcome) {
            if (this.meterRegistry == null) {
                return;
            }
            try {
                Counter.builder(TERMINAL_VERDICT_METER_NAME)
                        .description(TERMINAL_VERDICT_METER_DESCRIPTION)
                        .tag(TAG_JOB, jobName)
                        .tag(TAG_PERSISTED_STATUS, nameOf(persistedStatus))
                        .tag(TAG_OBSERVED_STATUS, nameOf(observedStatus))
                        .tag(TAG_PUBLICATION, publicationOutcome)
                        .register(this.meterRegistry)
                        .increment();
            } catch (final RuntimeException meterFailure) {
                LOGGER.debug("Could not publish the terminal verdict count for job {};"
                                + " failureType={}", jobName,
                        meterFailure.getClass().getSimpleName());
            }
        }

        /**
         * Names a status for a tag value, without ever producing a null tag.
         *
         * @param  status the status, possibly {@code null}
         * @return its name, or the framework's own unknown constant when there is none
         */
        private static String nameOf(final BatchStatus status) {
            return status == null ? BatchStatus.UNKNOWN.name() : status.name();
        }

        /**
         * Publishes every file a clean execution registered, upgrading the execution to failed if its
         * required durable boundary cannot be completed &mdash; and discarding the local generations that
         * verdict orphans, whichever way the boundary failed.
         *
         * <p><strong>A failure here means nothing was published.</strong> The store treats its uploads and
         * its fixed-name alias replacements as one compensated unit, so a failure anywhere in that unit
         * deletes every object it uploaded and puts every alias it advanced back. The verdict this method
         * writes is therefore honest in both directions: a FAILED job left no durable object and no local
         * view naming a generation that was rolled back, and a COMPLETED job published all of them.</p>
         *
         * <h2>Three ways to end FAILED, and all three discard</h2>
         *
         * <p>The local discard used to be reached from one of them only: the arm taken when the execution
         * arrived here already non-COMPLETED. The other two arms &mdash; a completed job whose registered
         * artifacts cannot be published because no store is available, and a publication that threw &mdash;
         * both set the status to FAILED <em>after</em> that branch had been passed, and returned. A job that
         * ends FAILED by either of those routes is in exactly the state the discard exists for: its
         * completed local generations name nothing durable, because nothing was published, and they are
         * still readable on disk and still resolvable by name to any component that asks the store for the
         * current local generation of their base. One dead artifact per failed run, indistinguishable from
         * real output. All three arms therefore discard, and the two failure arms do it through
         * {@link #failPublicationAndDiscardLocalArtifacts(JobExecution, RuntimeException)}. See
         * {@code docs/decision-log.md} entry DL-290.</p>
         *
         * <p>The converse is equally deliberate. Enforcing generation retention happens after the store's
         * commit point and cannot raise, because deleting a rolled-off object is irreversible and so cannot
         * participate in any compensation. A retention problem leaves a base temporarily over depth, which
         * the next successful publication of that base corrects; it is not a reason to fail a job whose
         * artifacts are durable and visible.</p>
         *
         * @param  jobExecution terminal job execution
         * @return what the boundary did, as one of the four publication tag values, so the terminal
         *         verdict count can attribute a failed job to this boundary or exonerate it
         */
        private String publishDurableArtifacts(final JobExecution jobExecution) {
            if (jobExecution.getStatus() != BatchStatus.COMPLETED) {
                // Nothing was published, so nothing local names anything durable. Discard this
                // execution's own allocations rather than leaving one dead artefact per failed run.
                //
                // "Its own" is exact: the store deletes only the paths THIS execution registered on its
                // own context, plus the one working sibling it names for each of them, and re-checks each
                // path is still a regular file this process owns before removing it. It does not sweep
                // the root and it does not match on a name, which an earlier revision did - by
                // predictable substring, so a file that merely contained this execution's token was
                // removed whether or not the store had ever allocated it.
                StagedGenerationStore.discardLocalArtifactsOf(jobExecution, this.stagingDirectory);
                return PUBLICATION_SKIPPED;
            }
            final int artifactCount =
                    StagedGenerationStore.registeredArtifactCount(jobExecution);
            if (artifactCount == 0) {
                return PUBLICATION_NOT_REQUIRED;
            }
            if (this.generationStore == null) {
                failPublicationAndDiscardLocalArtifacts(jobExecution, new IllegalStateException(
                        "a completed job registered durable artifacts but no generation store is"
                                + " available"));
                return PUBLICATION_FAILED;
            }
            // Observed, because until it was the object-store work here appeared in no trace at all: the
            // framework's job span is stopped before this callback is entered, and nothing opened one of
            // its own. This observation cannot be a CHILD of that span - it is already closed, which is a
            // framework ordering rather than an omission - so it carries the job's identity instead, which
            // is what lets a reader join it to the execution it belongs to. The store's own per-operation
            // observations descend from this one.
            final Observation publication =
                    Observation.createNotStarted(PUBLICATION_OBSERVATION_NAME, this.observationRegistry)
                            .lowCardinalityKeyValue(TAG_JOB, jobNameOf(jobExecution))
                            .highCardinalityKeyValue(TAG_JOB_EXECUTION_ID,
                                    String.valueOf(jobExecution.getId()))
                            .lowCardinalityKeyValue(TAG_ARTIFACT_COUNT,
                                    String.valueOf(artifactCount));
            // SanitisedObservation opens the scope, records a failure on the span and stops the
            // observation, which is the same shape every other outbound call in this module is observed
            // with - and it records the bounded type chain this module composed rather than the raw
            // object-store failure, whose message and stack trace the tracing bridge would export. The
            // observation's own observe() recorded the latter, so a publication failure published the
            // bucket, the key and the endpoint to the collector while the verdict below stayed safe; see
            // decision log DL-341. The failure is then converted into the job's verdict below exactly as
            // before; it is never rethrown from this callback, so a trace shows the boundary that failed
            // AND the job still ends with a verdict.
            final Runnable publish = () -> this.generationStore.publishRegistered(jobExecution);
            try {
                SanitisedObservation.observeRunnable(publication, publish);
                return PUBLICATION_PUBLISHED;
            } catch (final RuntimeException failure) {
                failPublicationAndDiscardLocalArtifacts(jobExecution, failure);
                return PUBLICATION_FAILED;
            }
        }

        /**
         * Writes the failed verdict, then discards the local generations that verdict has just orphaned.
         *
         * <p><strong>The order is the contract, in both directions.</strong> The verdict is written first
         * because the discard's own diagnostics describe an execution that did not complete, and because
         * the failure log names the artifact count, which the discard leaves in place but which a reader
         * expects to see against the failure rather than after it. The discard runs second, and it runs
         * before this method returns &mdash; which is what puts it before the terminal event, since
         * {@link #afterJob(JobExecution)} emits that only once artifact publication has been attempted. An
         * operator or a downstream subscriber therefore never observes a FAILED terminal event for a job
         * whose local generations are still sitting in the staging root.
         *
         * <p><strong>The publication failure is the one that survives.</strong> The discard is
         * best-effort by construction: {@code discardLocalArtifactsOf} re-checks each registered path,
         * reports rather than raises on a file it cannot remove, and returns a count. The
         * {@code catch} here covers the remaining possibility &mdash; that reading the registry itself
         * fails &mdash; because a cleanup problem must not replace the reason the publication failed, which
         * is the only diagnostic an operator can act on. A cleanup that could not run is reported at
         * warning level and the verdict already written stands.
         *
         * <p>The registry is deliberately <em>not</em> cleared. The store clears it only when a publication
         * commits, so a failed job keeps its registrations, and the artifact count remains readable to
         * anything that inspects the failed execution afterwards.
         *
         * @param jobExecution the completed execution whose durable boundary could not be completed
         * @param cause        the publication failure, preserved as the job's own failure exception
         */
        private void failPublicationAndDiscardLocalArtifacts(final JobExecution jobExecution,
                final RuntimeException cause) {

            markArtifactPublicationFailed(jobExecution, cause);
            try {
                final int discarded = StagedGenerationStore.discardLocalArtifactsOf(
                        jobExecution, this.stagingDirectory);
                if (discarded > 0) {
                    LOGGER.info("Discarded {} local file(s) of jobExecutionId={} after its durable"
                                    + " artifact publication failed; nothing was published, so nothing"
                                    + " local named a durable generation",
                            Integer.valueOf(discarded), jobExecution.getId());
                }
            } catch (final RuntimeException cleanupFailure) {
                LOGGER.warn("The local artifacts of jobExecutionId={} could not be discarded after its"
                                + " durable artifact publication failed; they remain in the staging root"
                                + " and name nothing durable. cleanupFailureType={}",
                        jobExecution.getId(), cleanupFailure.getClass().getSimpleName());
            }
        }

        /**
         * Converts a durable-publication failure into the job's persisted terminal verdict.
         *
         * @param jobExecution execution whose artifact could not be published
         * @param cause publication failure
         */
        private static void markArtifactPublicationFailed(final JobExecution jobExecution,
                final RuntimeException cause) {
            final IllegalStateException failure = new IllegalStateException(
                    "durable batch artifact publication failed", cause);
            jobExecution.setStatus(BatchStatus.FAILED);
            jobExecution.setExitStatus(ExitStatus.FAILED.addExitDescription(
                    "durable batch artifact publication failed"));
            jobExecution.addFailureException(failure);
            LOGGER.error("JOB {} COULD NOT PUBLISH {} REGISTERED ARTIFACT(S) -"
                            + " jobExecutionId={} failureType={}",
                    jobNameOf(jobExecution),
                    StagedGenerationStore.registeredArtifactCount(jobExecution),
                    jobExecution.getId(), cause.getClass().getSimpleName());
        }

        /**
         * Offers one allow-listed terminal snapshot to the application event boundary.
         *
         * <p>Notification is explicitly non-fatal. The batch verdict is already final, the durable file
         * boundary has already been attempted, and changing the verdict because operational fan-out
         * failed would make a correctly completed job appear not to have run. The warning names only
         * authored identifiers and the exception type; it never emits the exception message.</p>
         *
         * <p><strong>Non-fatal, and also non-blocking.</strong> Absorbing the failure was only half of
         * what "non-fatal" has to mean. Spring's event boundary is synchronous, so this call used to run
         * the outbound publish inside this very callback: the verdict was decided, the artifacts were
         * published, and a topic that accepted a connection and then stopped answering still held the
         * job from finishing. The failures were swallowed exactly as this note said; the job waited for
         * them to be swallowed, which it did not say.
         *
         * <p>{@code JobCompletionNotificationService} now hands the snapshot to a bounded worker and
         * returns, so this callback is released immediately and the network wait happens off the job's
         * thread. The {@code catch} below is kept rather than removed: this method must not depend on
         * which side of that boundary a failure comes from, and a listener registered here later must
         * not be able to fail a completed job either.
         *
         * <p>The resulting contract, stated once and here because this is where the verdict is finally
         * observable: a notification that is dropped, shed, refused or timed out leaves the job's status
         * and exit code exactly as the framework recorded them.</p>
         *
         * @param jobExecution terminal job execution
         * @param jobName registered job name
         * @param status terminal status after durable publication
         * @param exitCode terminal exit code after durable publication
         * @param stepsExecuted number of step executions
         */
        private void publishTerminalEvent(final JobExecution jobExecution,
                final String jobName, final BatchStatus status, final String exitCode,
                final int stepsExecuted) {
            if (this.completionPublisher == null) {
                LOGGER.debug("No job-completion event publisher is available for job {}"
                        + " execution {}", jobName, jobExecution.getId());
                return;
            }

            // The framework's two boundaries are wall-clock readings taken with LocalDateTime.now(),
            // so they carry no offset and mean nothing on their own. The zone that produced them is this
            // JVM's default, and the factory below is where it is attached, once. It is emphatically NOT
            // the module's Clock bean, which is pinned to UTC so that emitted timestamp images stay
            // stable - using that zone would silently shift every notification wherever a deployment is
            // not itself running in UTC. See docs/decision-log.md entry DL-306.
            final JobCompletionEvent event = JobCompletionEvent.ofFrameworkExecution(
                    jobName,
                    jobExecution.getJobId(),
                    jobExecution.getId(),
                    status,
                    exitCode,
                    stepsExecuted,
                    jobExecution.getStartTime(),
                    jobExecution.getEndTime(),
                    ZoneId.systemDefault());
            try {
                this.completionPublisher.publishCompletion(event);
                LOGGER.info("Published terminal completion event for job {} execution {} with status {}",
                        jobName, jobExecution.getId(),
                        status == null ? BatchStatus.UNKNOWN : status);
            } catch (final RuntimeException failure) {
                LOGGER.warn("Could not publish terminal completion event for job {} execution {};"
                                + " failureType={}",
                        jobName, jobExecution.getId(), failure.getClass().getSimpleName());
            }
        }
    }
}
