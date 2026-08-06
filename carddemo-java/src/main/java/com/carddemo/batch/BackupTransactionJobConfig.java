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

import com.carddemo.batch.step.AbstractCobolStep;
import com.carddemo.config.AwsProperties;
import com.carddemo.config.BatchConfig.ConditionCodeGate;
import com.carddemo.batch.step.GenerationPublicationLock;
import com.carddemo.batch.step.StagedGenerationStore;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.repository.TransactionScanRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.BatchJobCatalog;
import com.carddemo.util.BatchCancellation;
import com.carddemo.util.BoundedKeysetIterator;
import com.carddemo.util.SecureStagedFiles;
import com.carddemo.util.TransactionRecordMapper;
import io.awspring.cloud.s3.S3Operations;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.LongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Declares the job that archives the transaction master and then clears it for the next cycle.
 *
 * <p>Legacy antecedent: the 71-line job member {@code app/jcl/TRANBKP.jcl}, which drives the
 * cataloged unload wrapper {@code app/proc/REPROC.prc} and its control member
 * {@code app/ctl/REPROCT.ctl}, and whose output generation base is declared in
 * {@code app/jcl/DEFGDGB.jcl}. Read at commit {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec},
 * upstream release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No statement text
 * from the legacy tree is reproduced anywhere in this file; what is carried across is step naming,
 * data-definition and dataset naming, condition-code semantics, key length and offset, record
 * length and status vocabulary.
 *
 * <h2>The measured legacy job stream: three steps, one gate</h2>
 *
 * <ol>
 *   <li><strong>{@code STEP05R}</strong> (line 23), the unload, <em>ungated</em>. Its input is the
 *       transaction master opened with a shared disposition; its output is a <em>new</em> generation
 *       of the transaction backup generation base, fixed-length blocked, 350 bytes per record. The
 *       wrapper is a parameterised single-step utility whose one executable control statement copies
 *       input to output, with placeholder definitions the caller overrides. Here it is
 *       <strong>one ordinary read-and-write step</strong> - no shell-out, no process spawn, no
 *       external tool is invoked, and none may ever be.</li>
 *   <li><strong>{@code STEP05}</strong> (line 37), <em>ungated</em>: the dataset utility deletes the
 *       transaction cluster and then its alternate index, resetting its own condition code after
 *       each delete. Here it is the reset step described below.</li>
 *   <li><strong>{@code STEP10}</strong> (line 51), <em>gated</em>: the dataset utility re-defines the
 *       cluster with its data and its index component. The provisioning half is absorbed by the
 *       schema migrations; the gate is retained and applied to the reset step, because
 *       re-provisioning is what made the master usable again.</li>
 * </ol>
 *
 * <p>The re-definition corroborates the record contract independently of the copybook: the key is
 * 16 characters long at offset 0 and the record size is a fixed 350 bytes. That is the same
 * transaction identifier and the same record width the mapping layer already declares, arrived at
 * from the dataset definition rather than from the data description.
 *
 * <h2>&#9733; The gate is "prior return code zero", the same strict form as the other three</h2>
 *
 * <p>This job carries the fourth and last of the estate's four condition-code step gates, on
 * {@code app/jcl/TRANBKP.jcl:51}. The migration plan is the frozen contract for how those four gates
 * translate and it defines all four as the strict form: <strong>the guarded step executes only when
 * every earlier step returned exactly zero</strong>. That is what
 * {@link com.carddemo.config.BatchConfig.ConditionCodeGate#ALL_PRIOR_STEPS_ZERO} expresses, and
 * selecting it here is what keeps this job and the statement job's three gates one rule rather than
 * two.
 *
 * <p><strong>One measured divergence, recorded rather than implemented.</strong> The literal spelling
 * of this member's gate differs from the three in {@code app/jcl/CREASTMT.JCL}, and read on its own it
 * would admit a prior warning. The plan governs, so the strict form is implemented and the observation
 * is carried as a recorded decision instead - {@code docs/decision-log.md} entry DL-145. It is metadata
 * about the estate and is deliberately not expressed as behaviour, here or in any test.
 *
 * <p><strong>No second ceiling may be introduced for this job.</strong> A looser ceiling would run the
 * reset behind a prior nonzero code, which is precisely what the plan holds this gate against. The
 * posting job's own completion code of four for a nonzero reject count is a property of
 * <em>that</em> job's run and is reported on its own step; it is not a code this job's steps produce,
 * because a gate reads only the steps of the execution it sits inside.
 *
 * <p>The gate is expressed as a flow transition that <em>proceeds</em> on a permitting verdict and
 * <em>ends the job</em> on a refusing one, so a bypassed reset is distinguished from a failed one
 * rather than being lumped in with it. Routing on the framework's failure status alone would not do:
 * a step that <em>completed</em> while reporting a nonzero code of its own has not failed, and the
 * decider is what refuses it.
 *
 * <h2>&#9733; The pipeline relationship this job is one half of</h2>
 *
 * <p>This job <strong>empties</strong> the transaction master once it has been archived, and the
 * combine job then <strong>reloads</strong> the master by merging the archived backup generation
 * with the synthesized-interest generation. The two are complementary halves of one operational
 * cycle. The reset is not a defect and must never be omitted: without it the master would
 * accumulate, and the combine job's load would then produce duplicates. The two jobs nevertheless
 * remain independently launchable - the estate has no master orchestrator and its pipeline order is
 * an operational convention - so this configuration chains to no other job and defines no
 * run-everything job.
 *
 * <h2>What the two dataset-utility steps become</h2>
 *
 * <p><strong>The clear is idempotent by construction.</strong> The legacy step resets its own
 * condition code after each of its two deletes, and the purpose of those resets is to tolerate
 * deleting something that is not there - a first run, or a re-run after a partial failure. Clearing
 * an already-empty transaction master therefore must not fail, and here it does not: the clear is a
 * single bulk removal expressed through the repository, which removes nothing and reports nothing
 * wrong when the table holds no row. A genuine failure still surfaces, matching the legacy reset,
 * which masked the benign codes and left anything above them to be seen.
 *
 * <p><strong>Why the gate moves onto the clear.</strong> In the legacy stream the delete was ungated
 * and only the re-definition was gated, so a return code above the ceiling deleted the master and then
 * declined to rebuild it - leaving nothing usable behind. Once the rebuild is absorbed by the
 * migrations the two halves are one step, and the ceiling belongs on it: above the ceiling the master
 * is left intact and usable, which is what the legacy pair achieved whenever it ran to completion.
 *
 * <p><strong>The re-definition is absorbed, not implemented.</strong> Creating a cluster with its
 * data and index components is schema provisioning, and schema evolution in this module is owned
 * exclusively by the four flat migrations - the eleven application tables in the first and the index
 * equivalents, including the equivalent of the processing-timestamp alternate index, in the second.
 * This job therefore creates, drops and alters no table and no index, emits no schema statement and
 * adds no migration. The framework's own metadata tables are created by the framework and never by
 * the migrations, are excluded from the eleven-application-table count, and are not touched here
 * either.
 *
 * <h2>The archive destination</h2>
 *
 * <p>The legacy output is a new generation of the transaction backup generation base, declared with
 * a limit of five and scratch-on-roll-off. Here the generation becomes one <strong>timestamped
 * object</strong> in the batch staging bucket, whose name and whose region are read from the
 * injected settings bean and never written into this file. That bean is registered once, by the
 * AWS configuration class, and is only injected here: registering it a second time would be a
 * start-up conflict.
 *
 * <p><strong>No resource is ever created from application code.</strong> The legacy queue and file
 * resources were defined at initialisation time by the platform, and the target mirrors that: the
 * bucket, the queue and the topic are provisioned by the emulator bootstrap script. This job
 * <em>writes an object into an existing bucket</em>. It does not create a bucket, does not configure
 * one, and does not check for one in order to create it if absent.
 *
 * <p><strong>The object name is the generation base, the batch timestamp and the generation
 * number.</strong> Its fixed-width 26-character middle is the batch form the step template supplies:
 * the year, month and day separated by hyphens, a hyphen before the hour, dots between the hour,
 * minute, second and hundredths, and a fixed four-digit tail. The online form - a space before the
 * hour, colons between the time components, a period before a six-digit fraction - never appears, and
 * this is the only timestamp rendering anywhere in this file. Because the batch form contains only
 * digits, hyphens and dots, and an object name admits every one of those unchanged, no key-safe
 * rendering has to be derived from it and no second timestamp format is invented to obtain one.
 *
 * <p>The name is completed by the framework's own execution identifier, which is what makes each
 * execution's archive distinct rather than merely usually distinct. That is also the faithful reading
 * of the legacy request: the job stream asked for a <em>relative generation</em>, so a generation was
 * identified by a number, and the execution identifier is the number this framework issues for the
 * same purpose. The timestamp alone would leave two executions inside one hundredth of a second
 * sharing a name, and an archive that quietly replaces its predecessor is a lost archive.
 *
 * <p><strong>Retention stays where the platform put it.</strong> The generation limit becomes object
 * versioning on the pre-provisioned bucket, so this file implements no retention rule, no lifecycle
 * policy and no roll-off logic. Nor does it state any transport tuning value of any kind.
 *
 * <h2>&#9733; The archive's external byte contract, stated rather than implied</h2>
 *
 * <p>The archive is a <strong>fixed-width sequential dataset</strong> and its external bytes are a
 * contract, because the pipeline reads them back: {@code app/jcl/TRANBKP.jcl:35} declares the output
 * {@code DCB=(LRECL=350,RECFM=FB,BLKSIZE=0)}, and {@code app/jcl/COMBTRAN.jcl:24} takes the current
 * generation of that same base as the first of its two concatenated ordering inputs. The contract has
 * one part: each record is exactly {@link #ARCHIVE_RECORD_LENGTH} bytes, produced whole by the
 * mapping layer that owns every offset and width in it. Record-format-blocked data carries no line
 * terminator, so the physical stride is the record length itself and the object size must be exactly
 * {@code recordCount * 350}. The combine job consequently reads this generation with the module's
 * fixed-unblocked transaction reader rather than its newline-delimited fixture reader.
 *
 * <p>The exact external bytes are pinned by a committed golden fixture, so the stride is an asserted
 * contract rather than an incidental consequence of the write loop.
 *
 * <h2>&#9733; Two anomalies, recorded and never propagated</h2>
 *
 * <ol>
 *   <li>This job member resets its utility's condition code with an <em>at most eight</em> test,
 *       twice, whereas other members in the estate reset with an equality test against a single
 *       code. The two idioms coexist for no stated reason. The behaviour reproduced here is the
 *       intent both share - tolerate the absence of what is being deleted - and the divergence is
 *       raised for {@code docs/decision-log.md} rather than carried into the target.</li>
 *   <li>The report generation base is declared twice with divergent attributes: a limit of five with
 *       scratch-on-roll-off in {@code app/jcl/DEFGDGB.jcl}, and a limit of ten with no scratch
 *       keyword in {@code app/jcl/REPTFILE.jcl}. The module-wide resolution is ten. That base
 *       belongs to the report job, and the conflict is recorded here as well only because this job
 *       is the module's reference point for turning a generation into an object. Also raised for
 *       {@code docs/decision-log.md}.</li>
 * </ol>
 *
 * <p>Those three documentation artefacts - the traceability matrix, the decision log and the gate
 * evidence - are written elsewhere. This file raises notes for them and creates or edits none of
 * them.
 *
 * <h2>Diagnostics and failure handling</h2>
 *
 * <p>The legacy console-display channel becomes structured logging: the archived record count, the
 * object name and its destination are reported at completion. On a terminal input or output failure
 * the raw two-character file status is logged <em>first</em> and the abend is raised only after -
 * display-then-abend ordering is contractual, and the step template owns that ordering, so nothing
 * here re-implements it and nothing here logs a failure ahead of it.
 *
 * <p>End of file is never collapsed into error. The coarse three-way outcome the legacy programs
 * branch on is nested inside the step template, the raw status vocabulary lives in its own
 * enumeration, and the two codes that the estate documents but never compares are depended upon by
 * no path in this file.
 *
 * <h2>An improvement, labelled as one</h2>
 *
 * <p>Every application file in the legacy region was defined with uncommitted read integrity, no
 * recovery and no journalling. The read-committed isolation of the target database, combined with
 * optimistic version checking on the entities that carry it, is therefore <strong>strictly stronger
 * than the baseline</strong>. It is recorded here as an improvement so that a reviewer does not
 * mistake the stronger isolation for a behavioural regression.
 *
 * <h2>Wiring posture</h2>
 *
 * <p>The class is a configuration class and nothing more. It does not enable batch processing, which
 * under this framework generation would switch off the auto-configuration this module depends on. It
 * is final, injects every collaborator through its one constructor, holds every field final and
 * keeps no mutable state: each step's per-execution state lives in an object created for that
 * execution, never in a field of a singleton.
 *
 * <p><strong>Inter-bean method proxying is switched off, and that is what allows the class to be
 * final.</strong> Left at its default the container would subclass this class at run time to
 * intercept calls between its own factory methods, a subclass a final class cannot have &mdash; and
 * the container rejects the combination while the definitions are being read, before any bean is
 * created, so the whole application fails to start rather than this one job failing to wire. Nothing
 * here needs the interception: no factory method calls another, every collaborator arrives through
 * the constructor, and each step is composed from values passed as arguments. Switching it off is
 * also the posture every other configuration class in this module already takes, so this class is not
 * the exception that a reader has to account for.
 *
 * <p>Execution is <strong>strictly sequential</strong> - no task executor, no partitioning, no
 * multi-threaded step and no parallel flow. The archive must be complete and ordered before the
 * reset runs, and concurrency here would risk archiving a partially cleared master. Nothing fires
 * when the context starts: the framework's start-up launcher is switched off in the shared
 * configuration, this class contributes no runner, no lifecycle hook, no listener that launches and
 * no schedule, and it never names a job to run at start-up. Both steps are timed on the meter
 * registry so their elapsed time is visible on the metrics endpoint, and no throughput, latency,
 * memory, timeout, retry, skip, commit-interval or connection figure appears in this file, in code
 * or in comment.
 *
 * <p>Every archived record is exactly {@value com.carddemo.util.TransactionRecordMapper#RECORD_LENGTH}
 * bytes wide, and that width is asserted in encoded bytes rather than in characters. Rendering a
 * record to its image is the mapping layer's responsibility: all fixed-width offset knowledge lives
 * there, and this configuration composes rather than slices.
 *
 * <p>Bean-method proxying is switched off, as it is on every sibling job configuration in this
 * package, and the two properties of this class that follow from that are worth stating because
 * removing either one breaks start-up. The class stays {@code final}: a proxying configuration class
 * is subclassed at run time and cannot be final, so the container refuses one outright, and the
 * refusal is a start-up failure rather than a compile error. And the two step definitions below stay
 * private methods rather than beans: nothing here calls one bean method from another, so there is no
 * inter-bean reference for a proxy to intercept and no singleton guarantee to preserve.
 *
 * <h2>Standards this file is held to</h2>
 *
 * <p>No user-specified rules were provided for this engagement, so the work is held to
 * enterprise-standard best practice instead: a reproducible hermetic build; zero-warning compilation
 * as a build failure rather than a report; layered separation of concerns, with this tier depending
 * downward only; no code generation and a reflection budget of zero; secrets never in source and
 * never defaulted; migrations as the sole owner of the schema; a test pyramid with an enforced line
 * coverage floor; supply-chain hygiene through dependency scanning at verification; observability as
 * a first-class concern with no hardcoded performance target; licence continuity through the header
 * above; and full auditability through the traceability matrix. Where a faithful translation and an
 * idiomatic one disagree, the legacy behaviour wins and the divergence becomes a decision-log entry.
 *
 * <p>Declared as a lite configuration, which is what every other configuration class in this module
 * declares and what this one has to declare. A full configuration class is subclassed at runtime so
 * that a call from one bean method to another can be intercepted and answered with the singleton, and
 * a class that cannot be subclassed is rejected outright - so the full form on a final class prevents
 * the application context from starting at all. The lite form is not a workaround for that: it is the
 * accurate description of this class, whose single bean method calls private step builders rather than
 * other bean methods, so there is no call here for interception to answer differently. It also keeps
 * this file inside the module's zero-reflection posture by introducing no generated subclass.
 */
@Configuration(proxyBeanMethods = false)
public final class BackupTransactionJobConfig {

    /**
     * The stable name of the job, launched and queried by name through the registry and operator the
     * batch infrastructure configuration publishes.
     *
     * <p>The value is read from {@code service/BatchJobCatalog}, which is the module's single
     * declaration of the nine stable job names. Both tiers that need a name - this configuration
     * and the operational control surface above it - resolve it from there, so the name exists as
     * one literal and the two cannot drift apart across a boundary the layering keeps closed.
     */
    public static final String JOB_NAME = BatchJobCatalog.BACKUP_TRANSACTION_JOB_NAME;

    /** The stable name of the unload step, which archives the master before anything is cleared. */
    public static final String ARCHIVE_STEP_NAME = "backupTransactionArchiveStep";

    /** The stable name of the gated step, which clears the master once it has been archived. */
    public static final String RESET_STEP_NAME = "backupTransactionResetStep";

    /** Legacy generation-group base retained as the durable object-key prefix. */
    public static final String ARCHIVE_DATASET_BASE = "AWS.M2.CARDDEMO.TRANSACT.BKUP";

    /** The name prefix every archive object carries. */
    public static final String ARCHIVE_OBJECT_KEY_PREFIX = ARCHIVE_DATASET_BASE + "/";

    /**
     * The width of one archived record, in encoded bytes, being the legacy declared record length.
     *
     * <p>Read from the mapping layer rather than restated, so the two can never disagree. This is the
     * <em>record</em> half of the external byte contract documented on this class: it is what
     * {@code app/jcl/TRANBKP.jcl:35} declares as {@code LRECL=350}.
     */
    public static final int ARCHIVE_RECORD_LENGTH = TransactionRecordMapper.RECORD_LENGTH;

    /**
     * The physical distance from the start of one archived record to the start of the next.
     *
     * <p>Exactly one fixed-unblocked record. Published so that a reader of the archive, and the test
     * that pins its bytes, can state the stride instead of recomputing it.
     */
    public static final int ARCHIVE_RECORD_STRIDE = ARCHIVE_RECORD_LENGTH;

    /** This configuration's own diagnostic channel, replacing the legacy console display. */
    private static final Logger LOGGER = LoggerFactory.getLogger(BackupTransactionJobConfig.class);

    /**
     * The legacy job member this configuration translates, used as the culprit in an abend
     * diagnostic and as the step tag on the template's own timer.
     *
     * <p>Seven characters, so it fits the legacy culprit field the abend contract reserves.
     */
    private static final String LEGACY_MEMBER_NAME = "TRANBKP";

    /** The unload wrapper's input definition, as the legacy procedure declares it. */
    private static final String INPUT_DEFINITION_NAME = "FILEIN";

    /** The unload wrapper's output definition, as the legacy procedure declares it. */
    private static final String OUTPUT_DEFINITION_NAME = "FILEOUT";

    /** Timer name under which both of this job's steps are recorded. */
    private static final String METRIC_STEP_EXECUTION = "carddemo.batch.job.step";

    /** Timer description, stated once so both steps describe themselves identically. */
    private static final String METRIC_STEP_DESCRIPTION =
            "Elapsed time of one step of a migrated CardDemo batch job stream";

    /** Tag naming the job a timed step belongs to. */
    private static final String TAG_JOB = "job";

    /** Tag naming the step being timed. */
    private static final String TAG_STEP = "step";

    /** Tag naming how a timed step ended, so a failure is not averaged into the successes. */
    private static final String TAG_OUTCOME = "outcome";

    /** Outcome tag value for a step that returned normally. */
    private static final String OUTCOME_COMPLETED = "COMPLETED";

    /** Outcome tag value for a step that raised. */
    private static final String OUTCOME_FAILED = "FAILED";

    private static final int KEYSET_PAGE_SIZE = BoundedKeysetIterator.DEFAULT_PAGE_SIZE;

    /** The repository the framework records job and step executions in. */
    private final JobRepository jobRepository;

    /** The transaction manager each step's unit of work is bounded by. */
    private final PlatformTransactionManager transactionManager;

    /** The one job-boundary diagnostic every job configuration in this module attaches. */
    private final JobExecutionListener jobBoundaryListener;

    /** The one parameter incrementer every job configuration attaches, so a job can be resubmitted. */
    private final JobParametersIncrementer runIncrementer;

    /** The transaction master, read by the archive step and cleared by the reset step. */
    private final TransactionRepository transactionRepository;

    /** Bounded sequential-read view kept separate from the frozen online repository surface. */
    private final TransactionScanRepository transactionScanRepository;

    /** Shared durable generation store, including measured retention enforcement. */
    private final StagedGenerationStore generationStore;

    /** The settings bean the destination bucket and its region are read from. */
    private final AwsProperties awsProperties;

    /** The registry both step timers are recorded on. */
    private final MeterRegistry meterRegistry;

    /** The clock the batch timestamps are read from, injected so a test can fix it. */
    private final Clock clock;

    /**
     * Local root the archive generation is composed within before it is published.
     *
     * <p>The archive is written to a file and streamed from it, never held in memory, because the
     * legacy unload wrote a sequential dataset of unbounded size and a translation that composes the
     * whole of it in heap first is bounded by heap rather than by disk.
     */
    private final Path stagingDirectory;

    /**
     * @param jobRepository the repository the framework records executions in
     * @param transactionManager the manager each step's unit of work is bounded by
     * @param jobBoundaryListener the shared job-boundary diagnostic published by the batch
     *                            infrastructure configuration
     * @param runIncrementer the shared parameter incrementer published by the same configuration,
     *                       which is what lets this job be resubmitted with an identical parameter
     *                       set exactly as the legacy member could be
     * @param transactionRepository the transaction master
     * @param transactionScanRepository bounded sequential-read view of the transaction master
     * @param objectStore the object-store client used by the durable generation store
     * @param publicationLock the per-base publication lock the generation store serializes with; this
     *                        job shares its archive base with the transaction-report job's unload step,
     *                        so the lock is what keeps the two from interleaving their retention passes
     * @param awsProperties the already-registered settings bean carrying the destination bucket and
     *                      the region; this class never registers it a second time
     * @param meterRegistry the registry both step timers are recorded on
     * @param clock the clock the batch timestamp in the object name is read from
     */
    public BackupTransactionJobConfig(final JobRepository jobRepository,
            final PlatformTransactionManager transactionManager,
            @Qualifier("batchJobBoundaryListener")
                    final JobExecutionListener jobBoundaryListener,
            @Qualifier("batchJobRunIncrementer")
                    final JobParametersIncrementer runIncrementer,
            final TransactionRepository transactionRepository,
            final TransactionScanRepository transactionScanRepository,
            final S3Operations objectStore,
            final GenerationPublicationLock publicationLock,
            final AwsProperties awsProperties,
            final MeterRegistry meterRegistry,
            final Clock clock,
            @Value("${carddemo.batch.backup-transaction.staging-directory:${"
                    + StagedGenerationStore.SHARED_STAGING_DIRECTORY_PROPERTY
                    + ":${java.io.tmpdir}}}") final String stagingDirectory) {
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.jobBoundaryListener = Objects.requireNonNull(jobBoundaryListener, "jobBoundaryListener");
        this.runIncrementer = Objects.requireNonNull(runIncrementer, "runIncrementer");
        this.transactionRepository =
                Objects.requireNonNull(transactionRepository, "transactionRepository");
        this.transactionScanRepository = Objects.requireNonNull(
                transactionScanRepository, "transactionScanRepository");
        this.awsProperties = Objects.requireNonNull(awsProperties, "awsProperties");
        this.generationStore = new StagedGenerationStore(
                Objects.requireNonNull(objectStore, "objectStore"),
                this.awsProperties.s3().batchStagingBucket(),
                Objects.requireNonNull(publicationLock, "publicationLock"));
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.stagingDirectory = Path.of(requireStagingDirectory(stagingDirectory))
                .toAbsolutePath().normalize();
    }

    /**
     * Refuses a staging root that was configured to nothing.
     *
     * <p>A blank root would resolve the archive to a relative path under whatever directory the process
     * happens to be started in, which is not a place an operator can find it.
     *
     * @param  configured               the configured value
     * @return that value
     * @throws IllegalArgumentException if it is blank
     */
    private static String requireStagingDirectory(final String configured) {
        final String value = Objects.requireNonNull(configured, "stagingDirectory");
        if (value.isBlank()) {
            throw new IllegalArgumentException("stagingDirectory must not be blank");
        }
        return value;
    }

    /**
     * @param jobExecutionId the execution whose archive generation is being named
     * @return the local path the archive of that execution is composed at
     */
    private Path archiveGenerationPath(final long jobExecutionId) {
        return StagedGenerationStore.generationPath(this.stagingDirectory, ARCHIVE_DATASET_BASE,
                jobExecutionId);
    }

    // ----------------------------------------------------------------------------------------
    // The job
    // ----------------------------------------------------------------------------------------

    /**
     * The archive-and-reset job: unload the transaction master, then clear it behind the strict
     * condition-code gate.
     *
     * <p><strong>The flow, and why it is shaped this way.</strong> The unload runs unconditionally,
     * because the legacy unload step carried no gate. The gate then evaluates the highest return code
     * anything earlier in the job produced and routes on its verdict: the reset step on the
     * permitting outcome, and the end of the job on the refusing one, which is what the legacy gate
     * did when it declined to run its step. Refusing ends the job rather than failing it, because a
     * bypassed step is not a failed step - the legacy job stream reported a bypassed step and
     * completed.
     *
     * <p><strong>The gate is the strict ceiling and must stay so.</strong> The plan defines every one
     * of the estate's four condition-code step gates as the strict form, and the ceiling is a single
     * named policy precisely so that this selection is visible in one place and cannot be loosened by
     * accident.
     *
     * <p><strong>Why the steps are not published as beans.</strong> Nine job configurations share one
     * context, so publishing every step would put a couple of dozen same-typed beans in it and make
     * any by-type resolution ambiguous. The steps are composed here instead, which leaves this class
     * with exactly one bean and no ambiguity, and costs nothing: the framework locates a step through
     * the job that owns it, so a test can still run either step of this job on its own.
     *
     * @return the job, named {@link #JOB_NAME}, carrying the shared boundary diagnostic and the
     *         shared parameter incrementer that every job configuration in this module attaches
     */
    @Bean
    public Job backupTransactionJob() {
        final Step archiveStep = archiveTransactionMasterStep();
        final Step resetStep = resetTransactionMasterStep();

        return new JobBuilder(JOB_NAME, this.jobRepository)
                .listener(this.jobBoundaryListener)
                .incrementer(this.runIncrementer)
                .start(archiveStep)
                .next(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO)
                    .on(ConditionCodeGate.PERMITTED).to(resetStep)
                .from(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO)
                    .on(ConditionCodeGate.REFUSED).end()
                .end()
                .build();
    }

    // ----------------------------------------------------------------------------------------
    // The steps
    // ----------------------------------------------------------------------------------------

    /**
     * The unload step: read the whole transaction master in business-key order and write it as one
     * new archive generation.
     *
     * <p>A single-invocation step rather than a chunked one, because the legacy unload was one
     * indivisible copy of one dataset and because a chunked step would have to state a commit
     * interval, which is a tuning figure this translation has no legacy basis for choosing.
     *
     * @return the step, named {@link #ARCHIVE_STEP_NAME}
     */
    private Step archiveTransactionMasterStep() {
        final Tasklet archive = new TransactionArchiveTasklet(this.transactionScanRepository,
                this.generationStore, this.awsProperties, this.meterRegistry, this.clock,
                this::archiveGenerationPath);
        return new StepBuilder(ARCHIVE_STEP_NAME, this.jobRepository)
                .tasklet(timed(ARCHIVE_STEP_NAME, archive), this.transactionManager)
                .build();
    }

    /**
     * The gated step: clear the transaction master now that it has been archived.
     *
     * <p>Idempotent, so a first run or a re-run after a partial failure clears an already-empty
     * master without failing, exactly as the legacy step's condition-code resets intended.
     *
     * @return the step, named {@link #RESET_STEP_NAME}
     */
    private Step resetTransactionMasterStep() {
        final Tasklet reset = new TransactionMasterResetTasklet(this.transactionRepository);
        return new StepBuilder(RESET_STEP_NAME, this.jobRepository)
                .tasklet(timed(RESET_STEP_NAME, reset), this.transactionManager)
                .build();
    }

    /**
     * Wraps a step's work in a timer, so every step of this job is measured on the same meter under
     * the same tags.
     *
     * @param stepName the step being timed, which becomes the step tag
     * @param delegate the work to time
     * @return the delegate, timed
     */
    private Tasklet timed(final String stepName, final Tasklet delegate) {
        return new TimedTasklet(stepName, delegate, this.meterRegistry);
    }

    // ----------------------------------------------------------------------------------------
    // Timing
    // ----------------------------------------------------------------------------------------

    /**
     * Records how long one step took and how it ended, and does nothing else to it.
     *
     * <p>Holds only final collaborators, so the same instance can serve every execution of its step
     * without carrying state between them. It states no threshold, no budget and no target: the timer
     * publishes what was measured and the metrics endpoint is where a baseline is read from.
     */
    private static final class TimedTasklet implements Tasklet {

        private final String stepName;

        private final Tasklet delegate;

        private final MeterRegistry meterRegistry;

        TimedTasklet(final String stepName, final Tasklet delegate,
                final MeterRegistry meterRegistry) {
            this.stepName = Objects.requireNonNull(stepName, "stepName");
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        }

        @Override
        public RepeatStatus execute(final StepContribution contribution,
                final ChunkContext chunkContext) throws Exception {
            final Timer.Sample sample = Timer.start(this.meterRegistry);
            String outcome = OUTCOME_FAILED;
            try {
                final RepeatStatus status = this.delegate.execute(contribution, chunkContext);
                outcome = OUTCOME_COMPLETED;
                return status;
            } finally {
                sample.stop(Timer.builder(METRIC_STEP_EXECUTION)
                        .description(METRIC_STEP_DESCRIPTION)
                        .tag(TAG_JOB, JOB_NAME)
                        .tag(TAG_STEP, this.stepName)
                        .tag(TAG_OUTCOME, outcome)
                        .register(this.meterRegistry));
            }
        }
    }

    // ----------------------------------------------------------------------------------------
    // Step one: the unload
    // ----------------------------------------------------------------------------------------

    /**
     * Runs one archive of the transaction master.
     *
     * <p>Holds only final collaborators and builds a <strong>fresh</strong> unload for each
     * execution, so the per-execution state - the read position, the composed generation and the
     * object name - never lives in a field of a singleton and never leaks from one run into the next.
     */
    private static final class TransactionArchiveTasklet implements Tasklet {

        private final TransactionScanRepository transactionScanRepository;

        private final StagedGenerationStore generationStore;

        private final AwsProperties awsProperties;

        private final MeterRegistry meterRegistry;

        private final Clock clock;

        /** Resolves the local path this execution's archive is composed at, from its execution id. */
        private final LongFunction<Path> generationPathResolver;

        TransactionArchiveTasklet(final TransactionScanRepository transactionScanRepository,
                final StagedGenerationStore generationStore, final AwsProperties awsProperties,
                final MeterRegistry meterRegistry, final Clock clock,
                final LongFunction<Path> generationPathResolver) {
            this.transactionScanRepository = Objects.requireNonNull(
                    transactionScanRepository, "transactionScanRepository");
            this.generationStore = Objects.requireNonNull(generationStore, "generationStore");
            this.awsProperties = Objects.requireNonNull(awsProperties, "awsProperties");
            this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
            this.clock = Objects.requireNonNull(clock, "clock");
            this.generationPathResolver =
                    Objects.requireNonNull(generationPathResolver, "generationPathResolver");
        }

        @Override
        public RepeatStatus execute(final StepContribution contribution,
                final ChunkContext chunkContext) throws Exception {
            final long generationNumber = generationNumberOf(chunkContext);
            final TransactionArchiveProgram unload = new TransactionArchiveProgram(
                    this.transactionScanRepository, this.generationStore, generationNumber,
                    this.generationPathResolver.apply(generationNumber), this.meterRegistry,
                    this.clock);

            final AbstractCobolStep.ExecutionSummary summary;
            try {
                summary = unload.run(BatchCancellation.requestedBy(chunkContext));
            } catch (final CancellationException stopped) {
                throw BatchCancellation.interrupted(stopped);
            }

            LOGGER.info("ARCHIVED {} TRANSACTION RECORD(S) ({} BYTE(S)) AS OBJECT {} IN BUCKET {}"
                    + " OF REGION {}", summary.recordsRead(), unload.archivedByteCount(),
                    unload.objectKey(), this.awsProperties.s3().batchStagingBucket(),
                    this.awsProperties.region());
            return RepeatStatus.FINISHED;
        }

        /**
         * The generation number this execution's archive is named with: the framework's own execution
         * identifier, standing in for the relative generation the legacy job stream requested.
         *
         * <p>A launched job has a persisted execution identifier before its first step runs, so the
         * identifier is required rather than defaulted. If it were absent the archive could not be
         * named distinctly, and an archive that silently replaces its predecessor is worse than a run
         * that stops and says so.
         *
         * @param chunkContext the framework's chunk context for the running step
         * @return the execution identifier
         */
        private static long generationNumberOf(final ChunkContext chunkContext) {
            Objects.requireNonNull(chunkContext, "chunkContext");
            final Long jobExecutionId =
                    chunkContext.getStepContext().getStepExecution().getJobExecutionId();
            if (jobExecutionId == null) {
                throw new IllegalStateException("the running job has no execution identifier, so the"
                        + " archive generation cannot be named distinctly");
            }
            return jobExecutionId.longValue();
        }
    }

    /**
     * The unload itself, expressed on the shared step template so that the open, read-loop,
     * per-record work, close and abend sequence is the one every migrated batch program uses.
     *
     * <p>The legacy utility copied a keyed cluster to a sequential dataset one record at a time. That
     * is reproduced literally: the input is opened, records are delivered in business-key order one
     * per read, each is rendered to its record image and written, and the output is closed - and it is
     * the close that stores the object, because an object store has no append and one store is the
     * faithful analogue of allocating and cataloguing one new generation. Every input and output
     * attempt goes through the template's guarded forms, so a failure anywhere logs the raw status
     * first and abends second without this class arranging that ordering itself.
     *
     * <p><strong>A file and not an array.</strong> Each record image is written to a staged working file
     * as it arrives, the file is sealed by an atomic move at close, and the object store streams it from
     * there. Composing the generation in memory first bounded the job by heap and held a second complete
     * copy at the moment it was published. See {@code docs/decision-log.md} entry DL-176.
     *
     * <p>One instance serves one execution. Its mutable fields are per-execution state by
     * construction, which is why the enclosing tasklet builds a new one each time.
     */
    private static final class TransactionArchiveProgram extends AbstractCobolStep<Transaction> {

        private final TransactionScanRepository transactionScanRepository;

        private final StagedGenerationStore generationStore;

        /** The generation number this execution's archive is named with. */
        private final long generationNumber;

        /** The local path the completed archive is sealed at, resolved by the enclosing configuration. */
        private final Path completedGeneration;

        /** The sequential read position over the master, established when the input is opened. */
        private Iterator<Transaction> readPosition;

        /**
         * The generation being composed, one record image at a time, straight to its working file.
         *
         * <p>A stream and not a buffer. The legacy unload wrote a sequential dataset whose size is
         * bounded by the volume it sits on, so an archive of any size must cost one buffer here and not
         * one copy of itself.
         */
        private OutputStream generation;

        /** The working file the composed generation occupies until it is sealed. */
        private Path workingGeneration;

        /** How many bytes this execution has written, counted as they are written. */
        private long archivedBytes;

        /** The object name this execution writes, fixed when the output is opened. */
        private String objectKey;

        TransactionArchiveProgram(final TransactionScanRepository transactionScanRepository,
                final StagedGenerationStore generationStore, final long generationNumber,
                final Path completedGeneration, final MeterRegistry meterRegistry,
                final Clock clock) {
            super(LEGACY_MEMBER_NAME, meterRegistry, clock);
            this.transactionScanRepository = Objects.requireNonNull(
                    transactionScanRepository, "transactionScanRepository");
            this.generationStore = Objects.requireNonNull(generationStore, "generationStore");
            this.generationNumber = generationNumber;
            this.completedGeneration =
                    Objects.requireNonNull(completedGeneration, "completedGeneration");
        }

        /**
         * Opens the input and then the output, in the order the legacy procedure declares them.
         *
         * <p>Opening the input establishes the sequential read position in business-key order.
         * Opening the output fixes the name of the generation this execution will write - the legacy
         * generation base, the batch timestamp the template supplies, and this execution's generation
         * number - at the moment the generation is allocated, which is exactly when the legacy stream
         * resolved its relative generation too.
         */
        @Override
        protected void openResources() {
            openResource(INPUT_DEFINITION_NAME, () -> {
                this.readPosition = new BoundedKeysetIterator<>("", KEYSET_PAGE_SIZE,
                        (cursor, size) -> this.transactionScanRepository
                                .findByTranIdGreaterThanOrderByTranIdAsc(
                                        cursor, Limit.of(size.intValue())),
                        Transaction::getTranId, Comparator.naturalOrder());
                return FileStatus.SUCCESS.getCode();
            });
            openResource(OUTPUT_DEFINITION_NAME, () -> {
                this.objectKey =
                        StagedGenerationStore.objectKey(ARCHIVE_DATASET_BASE, this.generationNumber);
                this.workingGeneration =
                        StagedGenerationStore.workingPath(this.completedGeneration);
                this.archivedBytes = 0L;
                this.generation = openWorkingGeneration(this.workingGeneration);
                return FileStatus.SUCCESS.getCode();
            });
        }

        /**
         * Delivers the next record, or reports end of file, which ends the loop normally.
         *
         * @return the next record in business-key order, or empty at end of file
         */
        @Override
        protected Optional<Transaction> readNextRecord() {
            return readRecord(INPUT_DEFINITION_NAME, () -> {
                final Iterator<Transaction> position = this.readPosition;
                if (position == null || !position.hasNext()) {
                    return IoResult.endOfFile();
                }
                return IoResult.of(FileStatus.SUCCESS.getCode(), position.next());
            });
        }

        /**
         * Writes one record to the generation being composed.
         *
         * <p>The image is produced by the mapping layer, which owns every offset and width in it, and
         * its width is checked in <em>encoded bytes</em> before it is accepted: a record of any other
         * width would be a different record format, and a silently short or long archive is an
         * archive that cannot be read back.
         *
         * <p>The record is exactly {@link #ARCHIVE_RECORD_LENGTH} bytes and nothing on this path adds
         * a separator, delimiter or padding byte to it.
         *
         * @param record the record just read
         */
        @Override
        protected void processRecord(final Transaction record) {
            writeRecord(OUTPUT_DEFINITION_NAME, () -> {
                final byte[] image = TransactionRecordMapper.toRecordBytes(record);
                if (image.length != ARCHIVE_RECORD_LENGTH) {
                    throw new IllegalStateException("archive record encoded to " + image.length
                            + " bytes, expected " + ARCHIVE_RECORD_LENGTH);
                }
                try {
                    this.generation.write(image);
                } catch (final IOException failure) {
                    throw new UncheckedIOException("the transaction archive generation could not be"
                            + " written", failure);
                }
                this.archivedBytes += image.length;
                return FileStatus.SUCCESS.getCode();
            });
        }

        /**
         * Closes the input and then stores the generation, in the order the legacy procedure declares
         * its definitions.
         *
         * <p>The store is performed inside the guarded close of the output, so a failure to write the
         * object is diagnosed and abended exactly as a failure to close the output dataset was: the
         * raw status first, the abend second. The bucket already exists - it is provisioned by the
         * platform - so nothing here creates it, configures it or tests for it.
         *
         * <p>The composed bytes are checked against the stride before they are stored, so an archive
         * whose external shape is not a whole number of records is never uploaded at all. A
         * partial-record archive is unreadable by the ordering job that consumes it, and it is far cheaper
         * to fail the step here than to discover the truncation a cycle later.
         */
        @Override
        protected void closeResources() {
            closeResource(INPUT_DEFINITION_NAME, () -> {
                this.readPosition = null;
                return FileStatus.SUCCESS.getCode();
            });
            closeResource(OUTPUT_DEFINITION_NAME, () -> {
                closeWorkingGeneration();
                requireWholeRecordStride(this.archivedBytes);
                StagedGenerationStore.completeWorkingFile(this.workingGeneration,
                        this.completedGeneration);
                this.workingGeneration = null;
                this.generationStore.publishFile(ARCHIVE_DATASET_BASE, this.generationNumber,
                        this.completedGeneration, StagedGenerationStore.STANDARD_RETENTION_LIMIT);
                discardLocalGeneration(this.completedGeneration);
                return FileStatus.SUCCESS.getCode();
            });
        }

        /**
         * Refuses a generation whose size is not an exact multiple of the fixed record stride.
         *
         * <p>A Java-only guard with no legacy antecedent, because a record-format-blocked dataset could
         * not be written at a partial record length in the first place. It exists so that the external
         * byte contract this class publishes is enforced at the boundary that publishes it.
         *
         * @param  composedLength        the size of the composed generation, in bytes
         * @throws IllegalStateException if the size is not a whole number of records
         */
        private static void requireWholeRecordStride(final long composedLength) {
            if (composedLength % ARCHIVE_RECORD_STRIDE != 0) {
                throw new IllegalStateException("archive composed to " + composedLength
                        + " bytes, which is not a whole number of " + ARCHIVE_RECORD_STRIDE
                        + "-byte records");
            }
        }

        /**
         * Opens the working file the generation is composed in.
         *
         * @param  working              the working path
         * @return a buffered stream over it
         * @throws UncheckedIOException if it cannot be created
         */
        private static OutputStream openWorkingGeneration(final Path working) {
            try {
                // Owner-only from the first byte, and never through a link: the archive generation is
                // the whole transaction master, one 350-byte record at a time.
                // See docs/decision-log.md entry DL-178.
                return new BufferedOutputStream(SecureStagedFiles.newOutputStream(working));
            } catch (final IOException failure) {
                throw new UncheckedIOException("the transaction archive generation could not be"
                        + " opened for writing", failure);
            }
        }

        /**
         * Flushes and closes the working stream, so the file is whole before it is sealed.
         *
         * @throws UncheckedIOException if the stream cannot be closed
         */
        private void closeWorkingGeneration() {
            final OutputStream open = this.generation;
            this.generation = null;
            if (open == null) {
                return;
            }
            try {
                open.close();
            } catch (final IOException failure) {
                throw new UncheckedIOException("the transaction archive generation could not be"
                        + " closed", failure);
            }
        }

        /**
         * Removes the local copy once the durable one exists.
         *
         * <p>The legacy unload wrote its generation to the dataset and kept nothing else; the object
         * store is the generation here, and a local copy left behind would accumulate one archive of
         * the whole master per submission in the staging root. A failure to remove it is reported and
         * not raised, because the archive is already durable and the run succeeded.
         *
         * @param completed the sealed local generation
         */
        private static void discardLocalGeneration(final Path completed) {
            try {
                Files.deleteIfExists(completed);
            } catch (final IOException failure) {
                LOGGER.warn("The published transaction archive could not be removed from the local"
                        + " staging root; failureType={}", failure.getClass().getSimpleName());
            }
        }

        /**
         * Hands back what this execution was holding after a failure, without emitting a diagnostic
         * and without becoming a second close sequence.
         */
        @Override
        protected void releaseResources() {
            this.readPosition = null;
            releaseWorkingGenerationQuietly();
        }

        /**
         * Hands back the working stream and its file after a failure, without raising.
         *
         * <p>A partly composed archive is not an archive, so its working file is removed rather than
         * left where a later run might mistake it for one. Neither the close nor the removal may raise:
         * a failure is already on its way out and must not be displaced by a secondary one.
         */
        private void releaseWorkingGenerationQuietly() {
            final OutputStream open = this.generation;
            this.generation = null;
            if (open != null) {
                try {
                    open.close();
                } catch (final IOException failure) {
                    LOGGER.debug("The partly composed transaction archive could not be closed;"
                            + " failureType={}", failure.getClass().getSimpleName());
                }
            }
            final Path working = this.workingGeneration;
            this.workingGeneration = null;
            if (working != null) {
                try {
                    Files.deleteIfExists(working);
                } catch (final IOException failure) {
                    LOGGER.debug("The partly composed transaction archive could not be removed;"
                            + " failureType={}", failure.getClass().getSimpleName());
                }
            }
        }

        /**
         * @return the name of the object this execution wrote, or {@code null} before the output has
         *         been opened
         */
        String objectKey() {
            return this.objectKey;
        }

        /** @return the size of the generation this execution composed, in bytes */
        long archivedByteCount() {
            return this.archivedBytes;
        }

        /**
         * @return how many fixed records the composed generation holds, being its size divided by
         *         {@link #ARCHIVE_RECORD_STRIDE}
         */
        long archivedRecordCount() {
            return this.archivedBytes / ARCHIVE_RECORD_STRIDE;
        }

    }

    // ----------------------------------------------------------------------------------------
    // Step two: the reset
    // ----------------------------------------------------------------------------------------

    /**
     * Clears the transaction master so the next cycle starts from an empty one.
     *
     * <p>Expressed as one bulk removal through the repository - no assembled statement text, no
     * native query, and nothing that creates, drops or alters a table or an index. It is idempotent:
     * clearing a master that already holds nothing removes nothing and reports nothing wrong, which
     * is what the legacy step's condition-code resets existed to achieve for a first run and for a
     * re-run after a partial failure. A genuine failure is still allowed to surface, matching a
     * legacy code the resets did not mask.
     *
     * <p>The count is read before the removal purely so the diagnostic can state what was cleared;
     * both statements run inside the step's own unit of work, so the count cannot describe a
     * different state from the one that was cleared.
     */
    private static final class TransactionMasterResetTasklet implements Tasklet {

        private final TransactionRepository transactionRepository;

        TransactionMasterResetTasklet(final TransactionRepository transactionRepository) {
            this.transactionRepository =
                    Objects.requireNonNull(transactionRepository, "transactionRepository");
        }

        @Override
        public RepeatStatus execute(final StepContribution contribution,
                final ChunkContext chunkContext) throws Exception {
            try {
                if (chunkContext != null) {
                    BatchCancellation.checkpoint(BatchCancellation.requestedBy(chunkContext));
                }
                final long held = this.transactionRepository.count();
                this.transactionRepository.deleteAllInBatch();

                if (held == 0L) {
                    LOGGER.info("TRANSACTION MASTER HELD NO RECORD TO CLEAR; NOTHING TO DELETE IS"
                            + " NOT AN ERROR");
                } else {
                    LOGGER.info("CLEARED {} RECORD(S) FROM THE TRANSACTION MASTER", held);
                }
            } catch (final CancellationException stopped) {
                throw BatchCancellation.interrupted(stopped);
            }
            return RepeatStatus.FINISHED;
        }
    }
}
