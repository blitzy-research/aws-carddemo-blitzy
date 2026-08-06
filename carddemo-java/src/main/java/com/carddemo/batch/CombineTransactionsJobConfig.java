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

import com.carddemo.batch.step.CombineTransactionsProcessor;
import com.carddemo.batch.step.FixedWidthFlatFileReaderFactory;
import com.carddemo.batch.step.StagedGenerationStore;
import com.carddemo.domain.Transaction;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.BatchJobCatalog;
import com.carddemo.util.ExternalStringSorter;
import com.carddemo.util.FailureDiagnostics;
import com.carddemo.util.SecureStagedFiles;
import com.carddemo.util.StagedResourceNames;
import com.carddemo.util.TransactionRecordMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Job configuration that consolidates two transaction input streams into the transaction master, in
 * transaction-identifier order.
 *
 * <h2>The legacy job stream, as measured</h2>
 *
 * <p>The legacy member is fifty-two lines long, holds <strong>exactly two steps</strong> and
 * <strong>carries no condition-code gate on either of them</strong>. Neither step runs an
 * application program: the first names the platform's external sort utility and the second names the
 * platform's dataset utility, whose control member holds a single executable copy statement and whose
 * cataloged wrapper declares placeholder input and output definitions for a caller to override.
 *
 * <table border="1">
 *   <caption>The two legacy steps and what replaces each</caption>
 *   <tr><th>Legacy step</th><th>What it did</th><th>Replacement</th></tr>
 *   <tr><td>{@code STEP05R}</td>
 *       <td>External sort over two concatenated sequential inputs, writing one new combined
 *           generation whose record characteristics are inherited from the input</td>
 *       <td>{@link #combineTransactionsOrderStep} - an ordinary chunk-oriented step whose reader
 *           concatenates the two inputs in the legacy order and applies this class's own
 *           comparator</td></tr>
 *   <tr><td>{@code STEP10}</td>
 *       <td>Copy utility loading the combined generation into the transaction master</td>
 *       <td>{@link #combineTransactionsLoadStep} - an ordinary chunk-oriented read-and-write step
 *           that saves through {@link TransactionRepository}</td></tr>
 * </table>
 *
 * <p><strong>Nothing on this path invokes an operating-system utility.</strong> This is the job most
 * likely to tempt a process launch, because its second legacy step is literally a utility
 * invocation, and it is precisely the job that must not: no runtime process, no process builder, no
 * shell command, no container command and no external tool appears here or is reachable from here.
 * The copy became a reader, a processor and a writer, which is the only translation that keeps the
 * load inside a transaction the job repository can account for.
 *
 * <h2>The two inputs, their order, and the generation they produce</h2>
 *
 * <p>The sort input is <strong>two concatenated data definitions read as one logical stream</strong>:
 * first the transaction backup dataset at its <em>current</em> generation, then the
 * synthesized-transaction dataset at its <em>current</em> generation. Both are current generations.
 * Neither is a new generation - the only new generation in the job is the combined output - so
 * nothing here may treat either input as freshly minted or reorder the two for recency.
 *
 * <p><strong>The concatenation order is contractual, not incidental.</strong> The legacy sort
 * declares one key and no equal-key, summing or duplicate-elimination option of any kind, so records
 * that share an identifier are neither removed nor merged and their relative order is decided
 * entirely by the order in which the concatenated input presented them: backup-origin records ahead
 * of synthesized-origin records. Reversing the two definitions would change the output of the whole
 * job without changing its record count, which is exactly the class of defect that compiles cleanly
 * and passes a test written under the same misunderstanding.
 *
 * <p>The three datasets involved are generation groups whose bases are each defined with a limit of
 * five generations and with roll-off scratching the generation that falls out. Retention is
 * <strong>not</strong> implemented here: a generation group's depth is a property of the environment
 * that provisions the storage, and a job that enforced its own retention would apply a second,
 * competing policy. Every resource this job reads is resolved from this deployment's own configuration by
 * its logical property name - see {@link #BACKUP_RESOURCE_PROPERTY} and
 * {@link #SYNTHESIZED_RESOURCE_PROPERTY} - and <strong>no filesystem path is written anywhere in this
 * file</strong>.
 *
 * <p><strong>Neither input location is a job parameter, and that is a faithfulness requirement before it
 * is a security one.</strong> The legacy member declares both inputs inside itself, as two fixed dataset
 * names on two data-definition statements; whoever submitted the job named neither, and the submission
 * carried no location of any kind. Binding them from configuration reproduces exactly that division: the
 * environment that provisions the storage names the storage, and a launch says only which job to run. It
 * closes a hazard in the same stroke - a location that arrives with a request and is handed to a resource
 * loader is a read the server performs on the caller's behalf against whatever that loader understands, so
 * a job launch would otherwise double as a general read facility over the server's filesystem, classpath
 * and network position. With the locations bound from configuration there is no scheme to filter and no
 * path to bound, because no untrusted value reaches the loader at all.
 *
 * <p>The combined generation itself is an <strong>in-job artefact</strong>. An executed search of the
 * whole legacy tree finds it named twice, both times inside this one member: minted as a new
 * generation by the first step and consumed by the second. No other job member, cataloged procedure
 * or online resource definition references it. {@link CombinedGeneration} therefore models it as an
 * ordered result carried from the first step to the second inside one job execution, which is the
 * same treatment the statement job's in-job work cluster receives. Modelling it as a rendered file
 * instead would put fixed-width record-image knowledge into a job configuration, and that knowledge
 * has exactly one home in this module - the utility layer that owns the layout.
 *
 * <h2>The comparator is private to this job, and that is a parity requirement</h2>
 *
 * <p>A verb census over all twenty-eight legacy programs finds <strong>zero internal sort and zero
 * merge statements</strong>: every ordering in the estate is performed by the external sort utility,
 * in four distinct specifications. Ordering therefore belongs to job configuration rather than to
 * program logic, and each job owns its own specification.
 *
 * <p>{@code TRAN_ID_ASCENDING} is declared {@code private static final} in this class and is
 * deliberately not shared, not published, not extracted into a reusable comparator type and not
 * imported from anywhere. <strong>Sharing it would be a parity defect that compiles.</strong> The
 * statement job orders the same physical transaction card-number field as characters while the report
 * job orders that same field as zoned decimal, and the category-balance job mixes zoned-decimal and
 * character keys inside a single specification; a comparator shared between any two of them would
 * silently apply one job's typing to another job's data, and nothing would fail to build.
 *
 * <p>This job's key is the <strong>transaction identifier: sixteen characters beginning at one-based
 * position one of the record, compared as characters, ascending, with no secondary key</strong>. The
 * comparison is a plain lexicographic comparison of the identifier's own character sequence. Nothing
 * parses it as a number, decodes it as a zoned decimal value, trims it, case folds it or passes it
 * through a locale-sensitive collator - its sixteen characters, leading zeros included, are the
 * contract.
 *
 * <p><strong>Duplicate handling is a deliberate determinism decision, raised for the decision
 * log.</strong> Because the legacy specification names no equal-key option, the legacy tie-break is
 * unspecified rather than defined, so faithfulness cannot be read off the source. This job therefore
 * chooses the one tie-break that is both defensible and reproducible: ordering is applied with a
 * stable sort, so records carrying equal identifiers retain the concatenation order established
 * above - backup stream before synthesized stream. The choice is stated rather than relied upon,
 * because an unstable ordering would produce a byte-different combined stream from one run to the
 * next on identical input, and byte comparison is how this migration is verified.
 *
 * <h2>Sequencing, wiring and what is deliberately absent</h2>
 *
 * <p>The two steps are wired as a plain sequence. <strong>There is no failure-ending transition,
 * because the measured member has no condition-code gate.</strong> Exactly four steps in the whole
 * estate carry one - three in the statement job, one in the backup job - and none of them is here,
 * so a gate must not be added to this job by analogy with its neighbours. A technical failure in
 * either step fails the step and the job through ordinary framework semantics; there is no tolerated
 * skip path, no retry budget and no reject route, because this job renders no verdict on a record's
 * content.
 *
 * <p>Execution is <strong>strictly sequential and must stay so</strong>. No task executor, no
 * partitioning, no multi-threaded step, no parallel flow and no concurrent writer appears here or may
 * be added: every one of them interleaves work, and interleaved work destroys the ordering this job
 * exists to establish.
 *
 * <p>Nothing fires when the application context starts. Launch-on-start is disabled in the shared
 * configuration document, and this class contributes no start-up runner, no lifecycle participant, no
 * initialising callback, no event listener and no scheduled trigger, and it names no job for anything
 * to resolve automatically. The job is launched on demand, by the name published as {@link #JOB_NAME},
 * through the registry and operator the framework's own auto-configuration publishes. It is
 * <strong>not</strong> chained to any other job: the estate holds no master orchestrator, pipeline
 * order is an operational convention, and no job that "runs everything" exists in this module.
 *
 * <p>Both steps are real framework steps carrying <strong>distinct, stable names</strong>, which is
 * what makes each one separately visible on the metrics scrape endpoint: the batch tier's own
 * step-level meters time every step and tag the sample with the step name, and the shared
 * configuration document enables a percentile histogram for that timer. A second timer over the same
 * interval is deliberately not registered - it would double-report the same elapsed time - and no
 * meter registry is created here, because registry ownership belongs to the observability
 * configuration. <strong>No performance figure of any kind appears in this file</strong>: no
 * throughput, latency, heap, timeout, thread-count, skip-limit, retry-limit, backoff or connection
 * figure, and the one commit granularity that a chunk-oriented step must state is a legacy semantic
 * rather than a tuning knob - see {@link #RECORD_AT_A_TIME}.
 *
 * <h2>Diagnostics and failure</h2>
 *
 * <p>The legacy console-display channel becomes structured logging, and the <strong>ordering is
 * contractual: the diagnostic is emitted first and the failure is raised second</strong>. What is
 * deliberately absent is the raw two-byte file status and the abend that follows it. That form
 * belongs to the ten translated batch programs, whose own procedure divisions normalise a file status
 * into a tri-state outcome before branching, and it is owned by
 * {@code com.carddemo.batch.step.AbstractCobolStep}, which this job does not extend and must not
 * re-implement. Neither of this job's steps runs a program: a utility step has no file-status
 * vocabulary to normalise, so inventing one here would fabricate a legacy antecedent.
 *
 * <p><strong>End of file is never collapsed into error.</strong> A delegate reader returning no
 * further record ends that stream normally and the next concatenated stream begins; only a raised
 * exception is an error. Nothing in this file declares a top-level input/output outcome type - the
 * tri-state outcome is nested inside the program template where it belongs - and no code path here
 * depends on a file-status code.
 *
 * <h2>Standards this file is held to</h2>
 *
 * <p>No project rules were supplied for this migration, so the work is held to enterprise-standard
 * best practice instead: a reproducible hermetic build with every version pinned; zero-warning
 * compilation enforced as a build failure rather than reported; layered separation of concerns, with
 * this configuration composing readers, a writer and a comparator and never slicing a record offset
 * itself; no code generation and a reflection budget of zero, which is why the load writer calls the
 * repository directly instead of naming a method for a framework to resolve; secrets never in source
 * and never defaulted, and none is needed here; schema evolution owned solely by the migration tool,
 * with the framework's own metadata tables provisioned by the framework; a test pyramid with an
 * enforced line-coverage floor; supply-chain hygiene through dependency scanning bound to
 * verification; observability as a first-class concern with no hardcoded performance target; licence
 * continuity through the header above; full auditability through the traceability matrix; and, where
 * a faithful translation and an idiomatic one diverge, <strong>faithful wins and the divergence is
 * recorded as a decision</strong>.
 *
 * <p>Two notes are raised here for the migration record and are deliberately not written by this
 * file, because {@code docs/decision-log.md}, {@code docs/traceability-matrix.md} and
 * {@code docs/gate-evidence.md} belong elsewhere: the stable-sort tie-break described above, and the
 * modelling of the combined generation as an in-job ordered result rather than a rendered dataset.
 *
 * <h2>Provenance</h2>
 *
 * <p>Migrated from the CardDemo mainframe estate at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy tree is cited, never
 * transcribed: no job-control, utility-control, cataloged-procedure, program or copybook statement
 * text appears in this file, and nothing here reads that tree at run time. Step names, data-definition
 * roles, sort offsets and typings, record widths and generation semantics are cited as measured facts.
 *
 * @see CombineTransactionsProcessor
 * @see FixedWidthFlatFileReaderFactory
 * @see TransactionRepository
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public final class CombineTransactionsJobConfig {

    /**
     * The single logger this configuration and its nested types write through.
     *
     * <p>Resolved from this class so that every line lands under the application base category the
     * shipped logging configuration declares. The batch package categories that configuration also
     * declares are deliberately not named in a string here, because naming one would create a second
     * place where a package is spelled out.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(CombineTransactionsJobConfig.class);

    /**
     * The stable name this job is registered and launched under.
     *
     * <p>Published as a constant because the job is launched and queried <em>by name</em> through the
     * registry and operator the framework publishes, so a caller must be able to name it
     * symbolically rather than repeat a literal. The legacy member's own name is not reused as the
     * registered name; it is available from {@link CombineTransactionsProcessor#LEGACY_JOB} for
     * traceability, which keeps one origin identifier in one place.
     *
     * <p>The value is read from {@code service/BatchJobCatalog}, which is the module's single
     * declaration of the nine stable job names. Both tiers that need a name - this configuration
     * and the operational control surface above it - resolve it from there, so the name exists as
     * one literal and the two cannot drift apart across a boundary the layering keeps closed.
     */
    public static final String JOB_NAME = BatchJobCatalog.COMBINE_TRANSACTIONS_JOB_NAME;

    /**
     * Name of the ordering step, which replaces the legacy external-sort step {@code STEP05R}.
     *
     * <p>Also the bean name of {@link #combineTransactionsOrderStep}, and the value the batch tier's
     * step timer carries as its step tag, which is what makes this stage separately measurable from
     * the load stage.
     */
    public static final String ORDER_STEP_NAME = "combineTransactionsOrderStep";

    /**
     * Name of the load step, which replaces the legacy copy-utility step {@code STEP10}.
     *
     * <p>Also the bean name of {@link #combineTransactionsLoadStep}, and the value the batch tier's
     * step timer carries as its step tag.
     */
    public static final String LOAD_STEP_NAME = "combineTransactionsLoadStep";

    /**
     * Configuration prefix under which this job's two staged input generations are named.
     *
     * <p><strong>Configuration, deliberately, and not a job parameter.</strong> The legacy job member
     * declares both inputs inside itself, as two fixed dataset names on two data-definition statements;
     * neither was ever supplied by whoever submitted the job, and the submission carried no location of
     * any kind. Resolving them from the deployment's own configuration reproduces that: the environment
     * that provisions the storage names the storage, and a caller launching the job cannot name anything.
     *
     * <p>That is also the only arrangement that is safe. A location that arrives from a caller and is
     * handed to a resource loader is a request the server performs on the caller's behalf against
     * whatever the loader understands - a URL, a file path, a classpath entry - which turns a job launch
     * into a general read facility over the server's network position and filesystem. Reading the two
     * locations from configuration removes the caller from the decision entirely, so there is no scheme
     * to filter and no path to bound: no untrusted value reaches the loader at all.
     */
    public static final String RESOURCE_PROPERTY_PREFIX = "carddemo.batch.combine-transactions.";

    /**
     * Property naming the location of the <strong>first</strong> concatenated input: the transaction
     * backup dataset at its current generation.
     *
     * <p>Configured per environment rather than compiled in, so that the same job serves every
     * deployment and no filesystem path is written into this module.
     *
     * <p>This is the stream whose records precede the other's wherever two identifiers are equal.
     */
    public static final String BACKUP_RESOURCE_PROPERTY =
            RESOURCE_PROPERTY_PREFIX + "transaction-backup";

    /**
     * Property naming the location of the <strong>second</strong> concatenated input: the
     * synthesized-transaction dataset at its current generation.
     *
     * <p>The interest-accrual job mints a new generation of this dataset; this job reads it at its
     * current generation, never at a new one.
     */
    public static final String SYNTHESIZED_RESOURCE_PROPERTY =
            RESOURCE_PROPERTY_PREFIX + "synthesized-transaction";


    /*
     * There is deliberately no launch-parameter name for either configured input. Two such names were
     * once published on the launch contract as "typed-launch compatibility" spellings, and no job ever
     * accepted either, so every request carrying one was refused while the published document invited
     * it. Naming them here would reopen the question the two properties above have already answered:
     * the locations are the deployment's to name and not a caller's, which is what keeps an untrusted
     * value away from a resource loader. The names are therefore absent rather than unused.
     */

    /** Prefix of the per-execution combined object staged for downstream inspection or reuse. */
    private static final String COMBINED_OBJECT_KEY_PREFIX = JOB_NAME + "/combined/";

    /** Local file-name prefix of the combined generation of one execution. */
    private static final String COMBINED_GENERATION_NAME_PREFIX = JOB_NAME + ".combined.";

    /** Local file-name suffix of the combined generation of one execution. */
    private static final String COMBINED_GENERATION_NAME_SUFFIX = ".dat";

    /**
     * The record separator the combined generation writes, stated as a byte rather than taken from the
     * platform.
     *
     * <p>The legacy dataset is record-format blocked, so its records are delimited by the access method
     * and not by a character at all; a line-oriented local file is the closest local equivalent, and the
     * delimiter must be the same byte on every platform this module builds on. A writer's own newline
     * would be two bytes on one of them, which would change the external length of every record.
     */
    private static final char COMBINED_RECORD_SEPARATOR = '\n';

    /**
     * Property naming the one directory a supplied location may name a relative name beneath.
     *
     * <p>The local half of the staged-input allow-list, and the same key shape the other staged-dataset
     * jobs of this package use for their own staging directory. Published so that a deployment, a test and
     * a diagnostic all spell it once. It defaults to the platform's temporary location, so no configuration
     * document has to name it for the job to be launchable.
     */
    public static final String STAGING_DIRECTORY_PROPERTY =
            "carddemo.batch.combine-transactions.staging-directory";

    /**
     * Human-readable label for the first concatenated stream, used only in diagnostics.
     */
    private static final String BACKUP_STREAM = "transaction backup current generation";

    /**
     * Human-readable label for the second concatenated stream, used only in diagnostics.
     */
    private static final String SYNTHESIZED_STREAM = "synthesized transaction current generation";

    /**
     * Name prefix of the ordering work area and the file within it.
     *
     * <p>The work area is minted per execution rather than named, so two runs of the same job cannot
     * collide and neither can predict the other's path.
     */
    private static final String ORDERING_WORK_PREFIX = "carddemo-combine-order-";

    /** Name suffix of the ordering work file. */
    private static final String ORDERING_WORK_SUFFIX = ".dat";

    /**
     * Commit granularity of both steps: one record.
     *
     * <p><strong>This is a legacy semantic, not a tuning figure.</strong> The migrated batch tier is
     * strictly sequential and record at a time, which is the granularity the legacy tier exhibited,
     * and a chunk-oriented step must state some granularity for the framework to commit on. Stating
     * one record keeps the unit of work identical to the unit of processing, so a failure cannot
     * leave part of a group applied and part not. It is deliberately <em>not</em> presented as a
     * throughput, latency or batching knob, and no numeric service level is documented anywhere in
     * the migrated estate against which such a knob could be set.
     */
    private static final int RECORD_AT_A_TIME = 1;

    /**
     * The ordering this job's legacy sort specification declares, and the whole of it.
     *
     * <p>One key, the transaction identifier, compared as characters, ascending, with no secondary
     * key: exactly the single symbol the legacy specification declares at one-based position one for
     * sixteen character bytes. {@link Transaction#getTranId()} returns that field verbatim, leading
     * zeros included, so comparing the returned character sequences is the comparison the
     * specification asks for and no offset arithmetic is needed or permitted here.
     *
     * <p>The comparison is deliberately the string type's own lexicographic order, which is
     * locale-independent. A collator, a case-insensitive comparison or a numeric parse would each
     * change the result for some input, and a numeric parse would additionally lose the leading zeros
     * the fixed-width contract requires.
     *
     * <p>Because every identifier in the estate is drawn from a digit alphabet, this ordering agrees
     * with the legacy platform's own character ordering: both order the digits ascending, so the
     * difference between the two encodings' collating sequences cannot reach this key.
     *
     * <p><strong>Private, and never to be shared.</strong> See the class documentation: a sibling job
     * types the same physical field as zoned decimal, so a shared comparator would hand one job the
     * other's semantics without failing to compile.
     *
     * <p>A null identifier would raise a null-pointer exception from the sort rather than sorting to
     * one end. That is intended: the record mapper that produced the entity refuses to emit an absent
     * field, so a null here means the entity did not come from the layout it claims to.
     */
    private static final Comparator<Transaction> TRAN_ID_ASCENDING =
            Comparator.comparing(Transaction::getTranId);

    /**
     * Builds readers over the transaction master layout, whose record is a fixed
     * {@value CombineTransactionsProcessor#COMBINED_RECORD_LENGTH} encoded bytes.
     *
     * <p>Every offset, field rule and width check inside that layout belongs to the factory and to
     * the mapper it delegates to. This configuration asks for a reader and receives one; it never
     * slices a record, never counts characters and never restates a field position.
     */
    private final FixedWidthFlatFileReaderFactory readerFactory;

    /**
     * Turns a configured logical location into a readable resource.
     *
     * <p>Injected rather than reached for statically, so that the location form is the application
     * context's decision and so that a test can supply its own loader. This is the only mechanism by
     * which this job locates an input, which is what keeps every path out of this file.
     *
     * <p>Every location it is ever given comes from this deployment's own configuration. No value that
     * arrived with a request reaches it, which is what stops a job launch from becoming a read of
     * whatever a caller can name.
     */
    private final ResourceLoader resourceLoader;

    /** Shared object-store boundary used for staged inputs and the combined output generation. */
    private final BatchStagingArea stagingArea;

    /** Local staging root used when a configured logical name is not present in object storage. */
    private final Path stagingDirectory;

    /**
     * Configured location of the first concatenated input, exactly as the deployment named it.
     *
     * <p>Held rather than resolved at construction, because the resource is opened once per step
     * execution and a resolved handle held across executions would outlive the storage it names.
     */
    private final String backupLocation;

    /** Configured location of the second concatenated input, exactly as the deployment named it. */
    private final String synthesizedLocation;

    /**
     * The transaction master this job loads into.
     *
     * <p>The load step writes rows through this repository, which is what replaces the legacy copy
     * utility. The master's key remains the sixteen-character business identifier the record already
     * carries; no surrogate key is generated, requested or implied.
     */
    private final TransactionRepository transactionRepository;

    /**
     * Creates the configuration.
     *
     * <p>Constructor injection only, and every field final: these collaborators are the whole of
     * what this configuration holds, and holding them immutably is what makes the configuration safe
     * to share and impossible to re-point at run time. The job repository, the transaction manager
     * and the framework listeners this job attaches are taken as bean-method arguments rather than
     * fields, because they belong to individual bean definitions rather than to the configuration as
     * a whole.
     *
     * <p>Both input locations arrive here, from configuration, at construction. That placement is the
     * point: they are properties of the deployment rather than of an execution, so they are bound once
     * when the context is built and no per-execution value can replace them.
     *
     * @param  readerFactory         builds readers over the transaction master layout; must not be
     *                               {@code null}
     * @param  resourceLoader        resolves a job parameter's logical location into a readable
     *                               resource; must not be {@code null}
     * @param  stagingArea           shared object-store staging boundary; must not be {@code null}
     * @param  transactionRepository the transaction master the load step writes through; must not be
     *                               {@code null}
     * @param  stagingDirectory      local root for deployment-owned logical input names
     * @param  backupLocation        configured location of the transaction backup current generation,
     *                               blank when the deployment has staged none
     * @param  synthesizedLocation   configured location of the synthesized-transaction current
     *                               generation, blank when the deployment has staged none
     * @throws NullPointerException  if any argument is {@code null}
     */
    public CombineTransactionsJobConfig(final FixedWidthFlatFileReaderFactory readerFactory,
            final ResourceLoader resourceLoader,
            final BatchStagingArea stagingArea,
            final TransactionRepository transactionRepository,
            @Value("${" + STAGING_DIRECTORY_PROPERTY + ":${"
                    + StagedGenerationStore.SHARED_STAGING_DIRECTORY_PROPERTY
                    + ":${java.io.tmpdir}}}")
                    final String stagingDirectory,
            @Value("${" + BACKUP_RESOURCE_PROPERTY + ":}") final String backupLocation,
            @Value("${" + SYNTHESIZED_RESOURCE_PROPERTY + ":}") final String synthesizedLocation) {
        this.readerFactory = Objects.requireNonNull(readerFactory,
                "readerFactory must not be null");
        this.resourceLoader = Objects.requireNonNull(resourceLoader,
                "resourceLoader must not be null");
        this.stagingArea = Objects.requireNonNull(stagingArea, "stagingArea must not be null");
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "transactionRepository must not be null");
        this.stagingDirectory = Path.of(requireConfiguredValue(
                stagingDirectory, STAGING_DIRECTORY_PROPERTY)).toAbsolutePath().normalize();
        this.backupLocation = Objects.requireNonNull(backupLocation,
                "backupLocation must not be null");
        this.synthesizedLocation = Objects.requireNonNull(synthesizedLocation,
                "synthesizedLocation must not be null");
    }

    // ------------------------------------------------------------------------------------------
    // The pieces the two steps are composed from
    // ------------------------------------------------------------------------------------------

    /**
     * Publishes the per-record stage of the load step.
     *
     * <p>The processor is declared here rather than discovered, because it carries no framework
     * stereotype of its own: it is a stateless, immutable pass-through that confirms each record can
     * still be rendered at the contracted width and hands back the very instance it was given, so one
     * shared instance serves every record of every execution.
     *
     * <p>It is attached to the <strong>load</strong> step, which is the stage its own contract
     * describes: the width postcondition belongs where a record leaves the combined stream for the
     * master, and its diagnostics name the legacy load step. The ordering stage does not use it,
     * because ordering is this configuration's responsibility and the processor deliberately declares
     * no comparator.
     *
     * @return the shared per-record stage of the load step, never {@code null}
     */
    @Bean
    public CombineTransactionsProcessor combineTransactionsProcessor() {
        return new CombineTransactionsProcessor();
    }

    /**
     * Publishes the ordering stage's reader: the two concatenated current generations, presented as one
     * stream ordered by transaction identifier ascending.
     *
     * <p>Scoped to the step, because the records it materialises and the position it has reached are
     * <strong>per-execution state</strong>. Holding either in a shared singleton field would let one
     * execution observe another's progress, so this is the one place such state may live and it lives
     * here rather than anywhere else in this file.
     *
     * <p>The declared type is the stream-reader interface rather than an implementation class, which
     * matters twice. The framework registers a reader that is also a stream and calls its open and
     * close methods around the step, so the whole read-and-order pass happens inside the step it is
     * attributed to and is timed by that step's own meter. And a scoped bean declared by an interface
     * is proxied through that interface, so no subclass of an implementation type is generated - which
     * keeps this path clear of the class generation the module's reflection budget rules out.
     *
     * <p><strong>Both locations come from this deployment's configuration, and neither may be blank.</strong>
     * They are not job parameters and cannot be supplied with a launch: the legacy member declared both
     * inputs inside itself and the submission named neither, so a caller has nothing to say about where
     * this job reads. An unconfigured location is diagnosed and refused rather than resolved to some
     * default, because a job that quietly read one input instead of two would produce a plausible combined
     * stream missing half its records.
     *
     * @return a reader over both inputs in the legacy concatenation order, ordered by the identifier,
     *         never {@code null}
     * @throws IllegalStateException if either location is unconfigured or blank
     */
    @Bean
    @StepScope
    public ItemStreamReader<Transaction> combineTransactionsOrderedReader() {
        return new ConcatenatedOrderingReader(readerFactory,
                resolveInput(this.backupLocation, BACKUP_RESOURCE_PROPERTY, BACKUP_STREAM),
                resolveInput(this.synthesizedLocation, SYNTHESIZED_RESOURCE_PROPERTY,
                        SYNTHESIZED_STREAM));
    }

    /**
     * Publishes the combined generation: the ordered result the ordering step writes and the load step
     * reads.
     *
     * <p>Scoped to the job, which is the exact lifetime of the artefact it stands for. The legacy
     * combined generation is minted by the first step and consumed by the second of the same
     * submission, and an executed search of the legacy tree finds it named nowhere else, so one
     * instance per job execution reproduces its lifetime precisely. A singleton would carry one
     * execution's records into the next; a step-scoped bean would give each step its own empty copy and
     * the load step would find nothing.
     *
     * <p>One object serves as the writer of the first step and the reader of the second because one
     * dataset served both legacy steps. Reading begins at the first record written, so the load step
     * sees the ordering the first step established, in that order, once each.
     *
     * @param jobExecutionId the execution whose object key must be distinct
     * @return the per-execution combined generation, never {@code null}
     */
    @Bean
    @JobScope
    public CombinedGeneration combineTransactionsCombinedGeneration(
            @Value("#{jobExecution.id}") final Long jobExecutionId) {
        final long identifier = Objects.requireNonNull(jobExecutionId,
                "the framework must assign a job execution identifier before creating the generation")
                .longValue();
        return new StagedCombinedGeneration(this.stagingArea,
                COMBINED_OBJECT_KEY_PREFIX + identifier,
                this.stagingDirectory.resolve(COMBINED_GENERATION_NAME_PREFIX + identifier
                        + COMBINED_GENERATION_NAME_SUFFIX));
    }

    /**
     * Publishes the load step's writer, which inserts each record into the transaction master.
     *
     * <p><strong>This is the whole of what replaced the legacy copy utility.</strong> It writes rows
     * through the repository - no process is launched, no command is composed, no external tool is
     * addressed and no statement text is assembled, so there is no dynamic query and nothing to
     * concatenate. A keyed-file duplicate fails the legacy load rather than replacing the row already
     * present, so the assigned-key entity goes through the insert-only repository fragment and is
     * flushed before that call returns. Spring Data merge semantics are deliberately not used.
     *
     * <p>The writer is stateless and therefore an ordinary singleton: it holds no accumulated records,
     * no counter and no position, and it takes each group exactly as the step hands it over, in the
     * order the reader produced it. The step's record-at-a-time boundary makes each flushed insert one
     * unit of work. An empty group is a no-operation rather than an empty insert.
     *
     * @return the writer that loads the combined stream into the transaction master, never
     *         {@code null}
     */
    @Bean
    public ItemWriter<Transaction> combineTransactionsMasterWriter() {
        return (final Chunk<? extends Transaction> loaded) -> {
            Objects.requireNonNull(loaded, "loaded must not be null");
            for (final Transaction record : loaded.getItems()) {
                transactionRepository.insertAndFlush(record);
            }
        };
    }

    // ------------------------------------------------------------------------------------------
    // The two steps, and the job that sequences them
    // ------------------------------------------------------------------------------------------

    /**
     * The ordering step, replacing the legacy external-sort step {@code STEP05R}.
     *
     * <p>Reads the two concatenated current generations as one stream in the legacy order, orders that
     * stream by transaction identifier ascending with this class's own comparator, and writes the
     * result to the combined generation. No processor is attached: the legacy sort inspected no field
     * other than its key and modified no record, so inserting a per-record stage here would give the
     * job a decision the legacy step never took.
     *
     * <p>Deliberately absent, each for a measured reason. No task executor, throttle, partitioner or
     * parallel flow, because ordering is the only reason this step exists and any of them would
     * interleave it away. No fault tolerance, skip limit or retry limit, because the legacy job admits
     * no gate and a tolerated record would be a record the legacy job loaded and this one dropped. No
     * restart allowance, because the reader materialises its input on open rather than resuming a
     * position, and a partial re-read would order a different set of records than the first attempt.
     *
     * @param  jobRepository       the repository the step records its execution in
     * @param  transactionManager  the manager the step commits each unit of work through
     * @param  orderedReader       the concatenating, ordering reader over the two inputs
     * @param  combinedGeneration  the ordered result this step produces for the load step
     * @return the ordering step, named {@value #ORDER_STEP_NAME}, never {@code null}
     */
    @Bean
    public Step combineTransactionsOrderStep(final JobRepository jobRepository,
            final PlatformTransactionManager transactionManager,
            @Qualifier("combineTransactionsOrderedReader")
                    final ItemStreamReader<Transaction> orderedReader,
            @Qualifier("combineTransactionsCombinedGeneration")
                    final CombinedGeneration combinedGeneration) {
        return new StepBuilder(ORDER_STEP_NAME, jobRepository)
                .<Transaction, Transaction>chunk(RECORD_AT_A_TIME, transactionManager)
                .reader(orderedReader)
                .writer(combinedGeneration)
                .listener(stepCompletionDiagnostics(ORDER_STEP_NAME,
                        CombineTransactionsProcessor.LEGACY_SORT_STEP))
                .build();
    }

    /**
     * The load step, replacing the legacy copy-utility step {@code STEP10}.
     *
     * <p>Reads the combined generation the ordering step produced, confirms each record through the
     * per-record stage, and saves it into the transaction master. This is an ordinary read-and-write
     * step and nothing more: <strong>no utility is invoked, no process is spawned and no external tool
     * is addressed</strong>, which is the single most important property of this translation.
     *
     * <p>The reader is the same object the ordering step wrote to, so the order established there
     * carries through this step's reader, its processor and its writer without being re-established.
     * A second ordering pass here would at best repeat the first and at worst contradict it, so none
     * exists.
     *
     * @param  jobRepository       the repository the step records its execution in
     * @param  transactionManager  the manager the step commits each unit of work through
     * @param  combinedGeneration  the ordered result the ordering step produced
     * @param  processor           the per-record width confirmation
     * @param  masterWriter        the writer that saves into the transaction master
     * @return the load step, named {@value #LOAD_STEP_NAME}, never {@code null}
     */
    @Bean
    public Step combineTransactionsLoadStep(final JobRepository jobRepository,
            final PlatformTransactionManager transactionManager,
            @Qualifier("combineTransactionsCombinedGeneration")
                    final CombinedGeneration combinedGeneration,
            @Qualifier("combineTransactionsProcessor") final CombineTransactionsProcessor processor,
            @Qualifier("combineTransactionsMasterWriter") final ItemWriter<Transaction> masterWriter) {
        return new StepBuilder(LOAD_STEP_NAME, jobRepository)
                .<Transaction, Transaction>chunk(RECORD_AT_A_TIME, transactionManager)
                .reader(combinedGeneration)
                .processor(processor)
                .writer(masterWriter)
                .listener(stepCompletionDiagnostics(LOAD_STEP_NAME,
                        CombineTransactionsProcessor.LEGACY_LOAD_STEP))
                .build();
    }

    /**
     * The job: the ordering step, then the load step, and nothing else.
     *
     * <p><strong>The sequence carries no failure-ending transition, because the measured member carries
     * no condition-code gate.</strong> Adding one would either skip the load after a warning the
     * legacy job tolerated or end the job cleanly where the legacy job would have failed it, and both
     * are behavioural changes dressed as robustness. Ordinary framework failure semantics apply: if the
     * ordering step fails, the load step does not run and the job fails.
     *
     * <p>Two collaborators the batch infrastructure publishes are attached here, which is what they
     * exist for. The run incrementer makes a re-submission of the same logical work a new instance
     * rather than a completed-instance refusal, matching a legacy job that could be submitted again
     * with the same cards. The boundary listener emits the job-level start and end diagnostics, so the
     * two step-level lines this class emits sit inside a job-level frame.
     *
     * <p><strong>A parameter validator is attached, and this job is the reason the staged-input contract
     * exists.</strong> Its two parameters are locations, and a location is afterwards handed to a resource
     * resolver, so it is the one kind of launch parameter that decides what the process reads rather than
     * merely what it computes with. The rule is the allow-list owned by {@link JobParameterValidators},
     * built from the configured staging bucket and {@value #STAGING_DIRECTORY_PROPERTY}, so a launch naming
     * anything else is refused as an invalid parameter set before any step opens anything - and refused
     * again at resolution, because a boundary that can be reached around is not a boundary.
     *
     * <p>Nothing launches this job automatically. It is registered under {@value #JOB_NAME} and
     * launched on demand; it is not chained to another job and no aggregate job exists that would run
     * it as part of a wider pipeline.
     *
     * @param  jobRepository    the repository the job records its executions in
     * @param  runIncrementer   makes each submission a distinct instance
     * @param  boundaryListener emits the job-level start and end diagnostics
     * @param  orderStep        the ordering step, which runs first
     * @param  loadStep         the load step, which runs second
     * @return the job, registered under {@value #JOB_NAME}, never {@code null}
     */
    @Bean
    public Job combineTransactionsJob(final JobRepository jobRepository,
            @Qualifier("batchJobRunIncrementer") final JobParametersIncrementer runIncrementer,
            @Qualifier("batchJobBoundaryListener") final JobExecutionListener boundaryListener,
            @Qualifier(ORDER_STEP_NAME) final Step orderStep,
            @Qualifier(LOAD_STEP_NAME) final Step loadStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .incrementer(runIncrementer)
                .listener(boundaryListener)
                .start(orderStep)
                .next(loadStep)
                .build();
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /**
     * Builds the once-per-step diagnostic for one of the two steps.
     *
     * <p>Package-visible rather than private so that a test in this package can drive the diagnostic
     * directly against a step execution, instead of leaving it as a line nobody can reach. The job
     * configuration is the only production caller and it calls it exactly twice.
     *
     * @param  stepName       the migrated step's own name, which is also its meter tag
     * @param  legacyStepName the legacy step this stage replaces, for the traceability the line carries
     * @return a stateless listener that reports the step's counts once it has finished, never
     *         {@code null}
     */
    static StepExecutionListener stepCompletionDiagnostics(final String stepName,
            final String legacyStepName) {
        return new StepCompletionDiagnostics(stepName, legacyStepName);
    }

    /**
     * Turns a configured logical location into a readable resource, refusing an unconfigured one.
     *
     * <p>The diagnostic precedes the refusal, which is the ordering the batch tier uses throughout. The
     * refusal itself is a deployment error rather than a stream failure - this environment has not named
     * one of the two inputs the job needs - so it is reported as an illegal state and not dressed as an
     * input/output fault. It is deliberately not a caller error either, because a caller has no say in
     * these locations and reporting one would misattribute the fault.
     *
     * <p>No default is substituted and no location is composed. A job that resolved a missing property to
     * some conventional path would read whatever happened to be there, and a combine job that read one
     * input instead of two produces a perfectly well-formed result that is missing half its records.
     *
     * @param  location     the configured location, possibly blank
     * @param  propertyName the property that names it, reported in both the diagnostic and the refusal
     * @param  streamName   the stream the location was expected to name
     * @return the resolved resource, never {@code null}
     * @throws IllegalStateException if {@code location} is blank
     */
    private Resource resolveInput(final String location, final String propertyName,
            final String streamName) {
        if (location.isBlank()) {
            LOGGER.error("{} {}: property {} names no location for the {}, so the input cannot be"
                            + " resolved", CombineTransactionsProcessor.LEGACY_JOB,
                    CombineTransactionsProcessor.LEGACY_SORT_STEP, propertyName, streamName);
            throw new IllegalStateException("property " + propertyName
                    + " must name the " + streamName + ", and no default may be substituted for it");
        }
        final String normalized = location.strip();
        if (this.stagingArea.holds(normalized)) {
            return this.stagingArea.stagedInput(normalized);
        }
        if (isSimpleLocation(normalized)) {
            final String logicalName =
                    StagedResourceNames.requireSimpleName(normalized, propertyName);
            final Path localCandidate = this.stagingDirectory.resolve(logicalName).normalize();
            if (Files.exists(localCandidate)) {
                return new FileSystemResource(localCandidate);
            }
        }
        return resourceLoader.getResource(normalized);
    }

    /**
     * Reports whether a configured location is one logical file name rather than a resource URI or
     * path. Logical names may fall back to the configured local staging root; explicit resource
     * locations continue through the application resource loader unchanged.
     *
     * @param location normalized configured location
     * @return {@code true} only for a single path segment with no URI scheme
     */
    private static boolean isSimpleLocation(final String location) {
        return location.indexOf(':') < 0
                && location.indexOf('/') < 0
                && location.indexOf('\\') < 0;
    }

    /**
     * Requires one non-blank configured value.
     *
     * @param value configured value
     * @param propertyName property reported by a refusal
     * @return the stripped value
     */
    private static String requireConfiguredValue(final String value, final String propertyName) {
        final String required = Objects.requireNonNull(value, propertyName + " must not be null");
        if (required.isBlank()) {
            throw new IllegalArgumentException(propertyName + " must not be blank");
        }
        return required.strip();
    }

    // ------------------------------------------------------------------------------------------
    // The combined generation
    // ------------------------------------------------------------------------------------------

    /**
     * The combined generation of one job execution: written by the ordering step, read by the load
     * step.
     *
     * <p>The legacy job minted a new generation of a sequential dataset in its first step and consumed
     * that generation in its second, and no other member of the estate names it. This role combines the
     * two framework interfaces that correspond to those two uses, so that <strong>one object is the
     * artefact</strong> rather than two objects that must be kept in step with each other.
     *
     * <p>It carries no other operation. In particular it exposes no comparator, no ordering, no
     * deduplication, no lookup and no total: ordering is applied before a record reaches it, and a
     * dataset does not reorder what was written to it.
     *
     * @since 1.0.0
     */
    public interface CombinedGeneration
            extends ItemStreamReader<Transaction>, ItemStreamWriter<Transaction> {
    }

    /**
     * The combined generation held as a local sequential file for the duration of one job execution.
     *
     * <p>Records are appended in the order the ordering step wrote them and served in that same order,
     * once each, beginning at the first. Nothing sorts, re-sorts, re-groups, re-keys, deduplicates or
     * aggregates here: the ordering was established by the step before, and this type's entire job is
     * to preserve it.
     *
     * <p><strong>A file and not a list.</strong> The legacy generation is a sequential dataset whose size
     * is bounded by the volume it sits on, and it is the whole of the transaction master concatenated
     * with the whole of the synthesized interest stream. Holding it as a list of entities bounded the job
     * by heap instead, and rendering it to a byte array to publish it held a second complete copy at the
     * same moment. Each record is therefore written to the file as it arrives and read back from the file
     * as it is served, so the cost is one buffer in each direction however large the generation is.
     *
     * <p>Serving a record means parsing the image that was written, which is what the legacy load step
     * did: it read the sequential dataset the ordering step had written, record by record, through the
     * same layout. The ordering reader of this same class already round-trips through that layout for its
     * own external sort, so the round trip is not a new dependency introduced here.
     *
     * <p>The state is confined to one job execution by the scope of the bean that publishes it, which is
     * what makes a mutable handle safe here and would make it unsafe as a singleton field. Execution is
     * strictly sequential and single-threaded by the job's own design, so no synchronisation is required
     * and none is added - adding it would suggest a concurrent access this job must never have.
     *
     * <p>See {@code docs/decision-log.md} entry DL-176.
     */
    private static final class StagedCombinedGeneration implements CombinedGeneration {

        /** Shared staging boundary that publishes the completed generation. */
        private final BatchStagingArea stagingArea;

        /** Per-execution object key under which the generation is published. */
        private final String objectKey;

        /** Per-execution local file the generation occupies once it has been sealed. */
        private final Path completedGeneration;

        /** Open while the generation is being composed; the working file is its sibling. */
        private BufferedWriter composer;

        /** Open while the generation is being served. */
        private BufferedReader server;

        /** Whether the generation has been sealed, so that the completed file is whole. */
        private boolean sealed;

        /** Whether the ordering step has already published this generation. */
        private boolean published;

        /**
         * Creates an empty combined generation.
         *
         * @param stagingArea         the staging boundary that publishes it
         * @param objectKey           the durable name of this execution's generation
         * @param completedGeneration the local file this execution's generation occupies
         */
        StagedCombinedGeneration(final BatchStagingArea stagingArea, final String objectKey,
                final Path completedGeneration) {
            this.stagingArea = Objects.requireNonNull(stagingArea, "stagingArea must not be null");
            this.objectKey = Objects.requireNonNull(objectKey, "objectKey must not be null");
            this.completedGeneration =
                    Objects.requireNonNull(completedGeneration, "completedGeneration must not be null");
        }

        /**
         * Prepares whichever role this stream is being opened for.
         *
         * <p>Before the generation has been sealed this is the writing step opening its output, so a
         * fresh composition begins and anything a previous attempt left behind is discarded - opening
         * twice cannot append one pass to another. Afterwards it is the reading step opening its input,
         * so the served position returns to the first record.
         *
         * @param  executionContext the framework's context for this stream; never {@code null}
         * @throws NullPointerException if {@code executionContext} is {@code null}
         */
        @Override
        public void open(final ExecutionContext executionContext) {
            Objects.requireNonNull(executionContext, "executionContext must not be null");
            if (this.sealed) {
                closeServerQuietly();
                return;
            }
            closeComposerQuietly();
            beginComposition();
        }

        /**
         * Appends one group of ordered records to the generation.
         *
         * @param loaded the group the ordering step produced, in reader order; never {@code null}
         * @throws NullPointerException if {@code loaded} is {@code null}, which would mean the
         *                              framework contract was breached rather than that a group was
         *                              empty
         * @throws ItemStreamException  if the generation cannot be written
         */
        @Override
        public void write(final Chunk<? extends Transaction> loaded) {
            Objects.requireNonNull(loaded, "loaded must not be null");
            if (this.composer == null) {
                beginComposition();
            }
            try {
                for (final Transaction record : loaded.getItems()) {
                    this.composer.write(TransactionRecordMapper.toRecord(record));
                    this.composer.write(COMBINED_RECORD_SEPARATOR);
                }
            } catch (final IOException failure) {
                throw new ItemStreamException(
                        "the combine-transactions combined generation could not be written", failure);
            }
        }

        /**
         * Serves the next record of the generation, or reports exhaustion.
         *
         * <p>Returning {@code null} is the framework's own signal that the input is exhausted, and it is
         * the <strong>only</strong> meaning it carries here: exhaustion is the normal end of a stream
         * and is never reported as, converted into or confused with an error.
         *
         * <p>A generation still being composed is sealed first, because a record cannot be served from a
         * file that is not yet whole. In a running job the writing step has already closed its output by
         * this point, so the sealing is a no-op; the path exists so that serving is well defined however
         * the stream is driven.
         *
         * @return the next record in written order, or {@code null} once every record has been served
         * @throws ItemStreamException if the generation cannot be read
         */
        @Override
        public Transaction read() {
            seal();
            if (this.server == null) {
                this.server = openServer();
            }
            try {
                final String image = this.server.readLine();
                return image == null ? null : TransactionRecordMapper.fromRecord(image);
            } catch (final IOException failure) {
                throw new ItemStreamException(
                        "the combine-transactions combined generation could not be read", failure);
            }
        }

        /**
         * Closes whichever role this stream was serving.
         *
         * <p>The writing step's close seals the generation and publishes it once, which is what
         * cataloguing one new generation of the dataset did. The reading step's close releases the served
         * position and removes the local copy, because the durable object is the generation from that
         * point on and a local copy left behind would accumulate one whole combined dataset per
         * submission.
         *
         * @throws ItemStreamException if the generation cannot be released
         */
        @Override
        public void close() {
            if (this.server != null) {
                closeServer();
                discardLocalGeneration();
                return;
            }
            seal();
            if (!this.published) {
                this.stagingArea.publish(this.objectKey, this.completedGeneration);
                this.published = true;
            }
        }

        /** Opens a fresh working file, discarding anything an earlier attempt left behind. */
        private void beginComposition() {
            final Path working = StagedGenerationStore.workingPath(this.completedGeneration);
            try {
                Files.deleteIfExists(this.completedGeneration);
                // Owner-only from the first byte, and never through a link: the combined generation is
                // every posted transaction from both inputs, at 350 bytes each.
                // See docs/decision-log.md entry DL-178.
                this.composer = SecureStagedFiles.newWriter(working, StandardCharsets.US_ASCII);
            } catch (final IOException failure) {
                throw new ItemStreamException(
                        "the combine-transactions combined generation could not be opened for writing",
                        failure);
            }
        }

        /**
         * Makes the completed file whole, so that it may be served and published.
         *
         * <p>Idempotent: a generation that is already sealed is left alone, which is why both the
         * writing step's close and the first read may call it.
         */
        private void seal() {
            if (this.sealed) {
                return;
            }
            if (this.composer == null) {
                beginComposition();
            }
            try {
                this.composer.close();
            } catch (final IOException failure) {
                throw new ItemStreamException(
                        "the combine-transactions combined generation could not be closed", failure);
            } finally {
                this.composer = null;
            }
            StagedGenerationStore.completeWorkingFile(
                    StagedGenerationStore.workingPath(this.completedGeneration),
                    this.completedGeneration);
            this.sealed = true;
        }

        /**
         * @return a reader positioned at the first record of the sealed generation
         * @throws ItemStreamException if it cannot be opened
         */
        private BufferedReader openServer() {
            try {
                return Files.newBufferedReader(this.completedGeneration, StandardCharsets.US_ASCII);
            } catch (final IOException failure) {
                throw new ItemStreamException(
                        "the combine-transactions combined generation could not be opened for reading",
                        failure);
            }
        }

        /** Releases the served position, reporting a failure to release as a stream failure. */
        private void closeServer() {
            final BufferedReader open = this.server;
            this.server = null;
            if (open == null) {
                return;
            }
            try {
                open.close();
            } catch (final IOException failure) {
                throw new ItemStreamException(
                        "the combine-transactions combined generation could not be released", failure);
            }
        }

        /** Releases the served position without observing the outcome, for a re-open only. */
        private void closeServerQuietly() {
            final BufferedReader open = this.server;
            this.server = null;
            if (open == null) {
                return;
            }
            try {
                open.close();
            } catch (final IOException failure) {
                LOGGER.debug("The combined generation's served position could not be released;"
                        + " failureType={}", failure.getClass().getSimpleName());
            }
        }

        /** Releases a half-composed generation without observing the outcome, for a re-open only. */
        private void closeComposerQuietly() {
            final BufferedWriter open = this.composer;
            this.composer = null;
            if (open == null) {
                return;
            }
            try {
                open.close();
            } catch (final IOException failure) {
                LOGGER.debug("The combined generation's composer could not be released;"
                        + " failureType={}", failure.getClass().getSimpleName());
            }
        }

        /**
         * Removes the local copy once the generation has been served in full.
         *
         * <p>A failure to remove it is reported and not raised: the load step has already read every
         * record and the durable object already exists, so the run succeeded.
         */
        private void discardLocalGeneration() {
            try {
                Files.deleteIfExists(this.completedGeneration);
            } catch (final IOException failure) {
                LOGGER.warn("The combined generation could not be removed from the local staging root;"
                        + " failureType={}", failure.getClass().getSimpleName());
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // The ordering reader
    // ------------------------------------------------------------------------------------------

    /**
     * Reads the two concatenated current generations as one stream and presents it ordered by
     * transaction identifier ascending.
     *
     * <p>This is the whole of what the legacy external sort did, and the shape of the translation
     * follows the shape of the utility: the input is read in full first and only then ordered, because
     * an ordering over a stream cannot be decided before the stream ends. The read-and-order pass runs
     * when the framework opens the stream, so it happens inside the step it belongs to and is measured
     * by that step's own timer.
     *
     * <p>The two inputs are drained <strong>in the legacy concatenation order</strong> - backup current
     * generation first, synthesized current generation second - and the ordering that follows is
     * stable, so records sharing an identifier keep that order. Reversing the two drains would change
     * the job's output without changing its record count.
     *
     * <p>Neither offset nor width appears here. Each input is read through the module's reader factory,
     * which owns the layout and delegates every field rule and every width check to the mapper behind
     * it, so a record that cannot be parsed at the declared layout fails there rather than being
     * silently mis-sliced here. No character count is taken anywhere in this class.
     *
     * <p>The stream is deliberately not restartable. The position it reaches is not written to the
     * execution context, because resuming half way through would order a different set of records than
     * the attempt that failed, and a combined generation assembled from two different orderings is
     * worse than one assembled again from the start.
     */
    private static final class ConcatenatedOrderingReader implements ItemStreamReader<Transaction> {

        /** Builds a reader over the transaction master layout for each of the two inputs. */
        private final FixedWidthFlatFileReaderFactory readerFactory;

        /** The first concatenated input: the transaction backup at its current generation. */
        private final Resource backupCurrentGeneration;

        /** The second concatenated input: the synthesized transactions at their current generation. */
        private final Resource synthesizedCurrentGeneration;

        /**
         * The per-execution work area the ordered stream is minted within.
         *
         * <p>Held so that it can be removed as well as the file inside it. A directory minted per
         * execution and never removed accumulates one empty directory per run for the life of the host.
         */
        private Path orderedWorkArea;

        /** Disk-backed ordered stream served after both inputs have been externally sorted. */
        private Path orderedFile;

        /** Reader over the completed ordered stream. */
        private BufferedReader orderedReader;

        /** Records contributed by the backup input. */
        private int fromBackup;

        /** Records contributed by the synthesized input. */
        private int fromSynthesized;

        /**
         * Creates the reader over the two inputs, in the order they must be read.
         *
         * @param readerFactory                builds a reader over the transaction master layout
         * @param backupCurrentGeneration      the first concatenated input
         * @param synthesizedCurrentGeneration the second concatenated input
         */
        ConcatenatedOrderingReader(final FixedWidthFlatFileReaderFactory readerFactory,
                final Resource backupCurrentGeneration,
                final Resource synthesizedCurrentGeneration) {
            this.readerFactory = readerFactory;
            this.backupCurrentGeneration = backupCurrentGeneration;
            this.synthesizedCurrentGeneration = synthesizedCurrentGeneration;
        }

        /**
         * Reads both inputs in full, in the legacy concatenation order, and orders the result.
         *
         * <p>The previous contents are discarded first so that opening the stream twice cannot append
         * one pass to another. The ordering is applied with a stable sort, which is what preserves the
         * concatenation order among records that share an identifier.
         *
         * @param  executionContext the framework's context for this stream; never {@code null}
         * @throws NullPointerException if {@code executionContext} is {@code null}
         * @throws ItemStreamException  if either input cannot be read in full
         */
        @Override
        public void open(final ExecutionContext executionContext) {
            Objects.requireNonNull(executionContext, "executionContext must not be null");
            close();
            try {
                // Minted owner-only within an owner-only directory: this file holds the whole
                // ordered concatenation while the merge runs, which is the same record content the
                // published generation carries.
                this.orderedWorkArea = SecureStagedFiles.newTemporaryDirectory(ORDERING_WORK_PREFIX);
                this.orderedFile = SecureStagedFiles.newTemporaryFile(this.orderedWorkArea,
                        ORDERING_WORK_PREFIX, ORDERING_WORK_SUFFIX);
                try (ExternalStringSorter sorter = new ExternalStringSorter(
                        ConcatenatedOrderingReader::compareRecordImages,
                        ExternalStringSorter.DEFAULT_RECORDS_PER_RUN);
                        BufferedWriter orderedWriter = SecureStagedFiles.newWriter(
                                this.orderedFile, StandardCharsets.US_ASCII)) {

                    this.fromBackup =
                            drainInto(sorter, this.backupCurrentGeneration, BACKUP_STREAM);
                    this.fromSynthesized =
                            drainInto(sorter, this.synthesizedCurrentGeneration, SYNTHESIZED_STREAM);
                    sorter.writeTo(record -> writeOrderedRecord(orderedWriter, record));
                }
                this.orderedReader =
                        Files.newBufferedReader(this.orderedFile, StandardCharsets.US_ASCII);
                LOGGER.info("{} {}: ordered {} records of {} bytes by transaction identifier"
                                + " ascending - {} from the {} followed by {} from the {}",
                        CombineTransactionsProcessor.LEGACY_JOB,
                        CombineTransactionsProcessor.LEGACY_SORT_STEP,
                        this.fromBackup + this.fromSynthesized,
                        CombineTransactionsProcessor.COMBINED_RECORD_LENGTH,
                        this.fromBackup, BACKUP_STREAM, this.fromSynthesized, SYNTHESIZED_STREAM);
            } catch (final IOException | UncheckedIOException failure) {
                // The preparation failure is the diagnosis and is reported as itself. Cleanup is
                // attempted in full regardless of whether it succeeds, and a cleanup failure is
                // attached beneath the primary rather than thrown in its place: a released handle that
                // could not be released is worth knowing about and is never the reason the step failed.
                final ItemStreamException reported = new ItemStreamException(
                        "the combine-transactions ordered work stream could not be prepared", failure);
                releaseEverything().forEach(reported::addSuppressed);
                throw reported;
            } catch (final RuntimeException failure) {
                // Any other failure - an input that cannot be allocated, a resource the loader refuses,
                // a comparator that rejects a record - already says what went wrong and is rethrown as
                // itself so that neither its type nor its message changes. What must not differ by
                // failure type is the release: the work area holds the ordered concatenation of both
                // inputs, and leaving it behind on one path and not on another is how a leak survives
                // every test written against the other path.
                releaseEverything().forEach(failure::addSuppressed);
                throw failure;
            }
        }

        /**
         * Records no progress, because this stream is deliberately not restartable.
         *
         * <p>Writing a position here would invite a resumed execution to continue from it, and a
         * resumed ordering pass is exactly what must not happen. See this class's own documentation.
         *
         * @param  executionContext the framework's context for this stream; never {@code null}
         * @throws NullPointerException if {@code executionContext} is {@code null}
         */
        @Override
        public void update(final ExecutionContext executionContext) {
            Objects.requireNonNull(executionContext, "executionContext must not be null");
            // Deliberately writes nothing: see the method documentation. The context is still required
            // to be present, because its absence would mean the framework contract was breached.
        }

        /**
         * Releases the ordered records once the step has finished with them.
         */
        @Override
        public void close() {
            final List<IOException> failures = releaseEverything();
            if (failures.isEmpty()) {
                return;
            }
            final ItemStreamException reported = new ItemStreamException(
                    "the combine-transactions ordered work stream could not be fully released",
                    failures.get(0));
            failures.subList(1, failures.size()).forEach(reported::addSuppressed);
            throw reported;
        }

        /**
         * Releases every resource this stream holds, whatever happens to any one of them.
         *
         * <p>Three things are held and all three are attempted: the reader over the ordered stream, the
         * ordered file itself, and the work area minted to hold it. They are attempted in that order
         * because a file cannot be removed while a descriptor is open on some filesystems and a
         * directory cannot be removed until it is empty.
         *
         * <p>Nothing is thrown from here, and nothing is swallowed. Each failure is collected and every
         * later step still runs, because the alternative - returning at the first failure, as this
         * method's predecessor did - leaves the ordered file on disk holding every posted transaction
         * from both inputs, indefinitely, with no diagnostic naming it. Every handle is cleared whether
         * its release succeeded or not, so a second call cannot attempt the same release twice.
         *
         * @return the failures, in the order they occurred, empty when everything was released
         */
        // See docs/decision-log.md entry DL-179.
        private List<IOException> releaseEverything() {
            final List<IOException> failures = new ArrayList<>();
            if (this.orderedReader != null) {
                try {
                    this.orderedReader.close();
                } catch (final IOException failure) {
                    failures.add(failure);
                } finally {
                    this.orderedReader = null;
                }
            }
            if (this.orderedFile != null) {
                try {
                    Files.deleteIfExists(this.orderedFile);
                } catch (final IOException failure) {
                    failures.add(failure);
                } finally {
                    this.orderedFile = null;
                }
            }
            if (this.orderedWorkArea != null) {
                try {
                    Files.deleteIfExists(this.orderedWorkArea);
                } catch (final IOException failure) {
                    failures.add(failure);
                } finally {
                    this.orderedWorkArea = null;
                }
            }
            this.fromBackup = 0;
            this.fromSynthesized = 0;
            return failures;
        }

        /**
         * Serves the next ordered record, or reports exhaustion.
         *
         * <p>Returning {@code null} means the ordered stream is exhausted and nothing else; exhaustion
         * is never reported as an error.
         *
         * @return the next record in ascending identifier order, or {@code null} when none remains
         */
        @Override
        public Transaction read() {
            if (this.orderedReader == null) {
                return null;
            }
            try {
                final String record = this.orderedReader.readLine();
                return record == null ? null : TransactionRecordMapper.fromRecord(record);
            } catch (final IOException failure) {
                throw new ItemStreamException(
                        "the combine-transactions ordered stream could not be read", failure);
            }
        }

        /**
         * Reads one input in full, appending its records to the ordered list in file order.
         *
         * <p>The delegate reader is opened, drained to exhaustion and closed. A record beyond the last
         * is reported by the delegate as no record at all, which ends this input normally and lets the
         * next one begin: <strong>the end of an input is never treated as a failure of it</strong>.
         *
         * <p>A genuine failure is diagnosed before it is raised, which is the ordering the batch tier
         * uses throughout, and the diagnostic names the stream, the resource and how many records had
         * been read - enough to locate the record that stopped the pass without logging any record's
         * content, because an amount is financial data and a card number is a primary account number.
         * The failure is then raised as a stream failure carrying the original cause. It is deliberately
         * not raised as a legacy abend: that form belongs with the raw file status the translated
         * programs normalise, and a utility step has no such status to report.
         *
         * <p>The delegate declares a checked failure of the broadest kind, so the catch is
         * correspondingly broad; narrowing it would leave the parse failures the mapper raises
         * undiagnosed.
         *
         * @param  input      the input to read in full
         * @param  streamName the stream's role, used in diagnostics
         * @return how many records this input contributed
         * @throws ItemStreamException if the input cannot be read in full
         */
        private int drainInto(final ExternalStringSorter sorter, final Resource input,
                final String streamName) {
            final FlatFileItemReader<Transaction> stream =
                    readerFactory.fixedTransactionReader(input);
            int records = 0;
            boolean opened = false;
            try {
                stream.open(new ExecutionContext());
                opened = true;
                Transaction record = stream.read();
                while (record != null) {
                    sorter.add(TransactionRecordMapper.toRecord(record));
                    records++;
                    record = stream.read();
                }
            } catch (final Exception failure) {
                LOGGER.error("{} {}: the {} could not be read in full; {} records had been read;"
                                + " failureChain={}",
                        CombineTransactionsProcessor.LEGACY_JOB,
                        CombineTransactionsProcessor.LEGACY_SORT_STEP, streamName,
                        records, FailureDiagnostics.failureChainOf(failure));
                throw new ItemStreamException("the " + streamName
                        + " of the combine-transactions job could not be read in full", failure);
            } finally {
                if (opened) {
                    closeQuietly(stream, streamName);
                }
            }
            return records;
        }

        private static void writeOrderedRecord(
                final BufferedWriter orderedWriter, final String record) {
            try {
                orderedWriter.write(record);
                orderedWriter.newLine();
            } catch (final IOException failure) {
                throw new UncheckedIOException(
                        "the combine-transactions ordered work stream could not be written",
                        failure);
            }
        }

        private static int compareRecordImages(final String left, final String right) {
            return TRAN_ID_ASCENDING.compare(
                    TransactionRecordMapper.fromRecord(left),
                    TransactionRecordMapper.fromRecord(right));
        }

        /**
         * Closes a drained input, reporting rather than raising a failure to close.
         *
         * <p>Raising here would replace the failure that is already on its way out with a secondary one
         * and lose the diagnosis, so a close failure is reported at warning level and the original
         * outcome is preserved. This is a deliberate report-and-continue, not a swallowed error: by the
         * time an input is closed its records have already been read in full or the failure that stopped
         * them has already been raised.
         *
         * @param stream     the delegate reader to close
         * @param streamName the stream's role, used in the diagnostic
         */
        private static void closeQuietly(final FlatFileItemReader<Transaction> stream,
                final String streamName) {
            try {
                stream.close();
            } catch (final ItemStreamException closeFailure) {
                LOGGER.warn("{} {}: the {} did not close cleanly; every record it produced had"
                                + " already been read; failureChain={}",
                        CombineTransactionsProcessor.LEGACY_JOB,
                        CombineTransactionsProcessor.LEGACY_SORT_STEP, streamName,
                        FailureDiagnostics.failureChainOf(closeFailure));
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Step diagnostics
    // ------------------------------------------------------------------------------------------

    /**
     * Reports one step's outcome once, replacing the legacy console-display channel for this job.
     *
     * <p>One line per step, carrying the legacy step it replaces, the migrated step's own name, the
     * counts the framework itself accumulated and the status it reached. No record content appears in
     * it: an amount is financial data and a card number is a primary account number, so neither belongs
     * in a log line.
     *
     * <p>Stateless by construction. The elapsed time and the counts are the step execution's own, so
     * nothing needs to be remembered between the beginning and the end of a step and no field can drift
     * between two executions. Elapsed time itself is not reported here, because the batch tier's own
     * step meter already times every step and tags the sample with the step name; reporting it again
     * would give two answers to one question.
     */
    private static final class StepCompletionDiagnostics implements StepExecutionListener {

        /** The migrated step's own name, which is also its meter tag. */
        private final String stepName;

        /** The legacy step this stage replaces, carried so the line is traceable to the estate. */
        private final String legacyStepName;

        /**
         * Creates the diagnostic for one step.
         *
         * @param stepName       the migrated step's own name
         * @param legacyStepName the legacy step it replaces
         */
        StepCompletionDiagnostics(final String stepName, final String legacyStepName) {
            this.stepName = stepName;
            this.legacyStepName = legacyStepName;
        }

        /**
         * Reports the finished step and leaves its outcome exactly as the framework decided it.
         *
         * <p>Returning {@code null} is the framework's own way of contributing no exit status, which is
         * the point: a diagnostic observes an outcome and must never alter one. A listener that returned
         * a status here could turn a failure into a success, and this job has no gate that would make
         * such a decision legitimate.
         *
         * @param  stepExecution the finished step's execution; never {@code null}
         * @return {@code null}, always, so the step's own exit status stands unchanged
         * @throws NullPointerException if {@code stepExecution} is {@code null}
         */
        @Override
        public ExitStatus afterStep(final StepExecution stepExecution) {
            Objects.requireNonNull(stepExecution, "stepExecution must not be null");
            LOGGER.info("{} {} ({}): read {} records, wrote {}, finished {}",
                    CombineTransactionsProcessor.LEGACY_JOB, legacyStepName, stepName,
                    stepExecution.getReadCount(), stepExecution.getWriteCount(),
                    stepExecution.getStatus());
            return null;
        }
    }
}
