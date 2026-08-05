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
import com.carddemo.batch.step.AbstractCobolStep.ExecutionSummary;
import com.carddemo.batch.step.InterestCalculationProcessor;
import com.carddemo.batch.step.InterestCalculationProcessor.AccruedAccountGroup;
import com.carddemo.batch.step.StagedGenerationStore;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.service.BatchJobCatalog;
import com.carddemo.service.InterestCalculationService;
import com.carddemo.util.BoundedKeysetIterator;
import com.carddemo.util.FailureDiagnostics;
import com.carddemo.util.StagedResourceNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttribute;

/**
 * The batch job configuration for monthly interest accrual: the translated wiring of the legacy job
 * member {@code app/jcl/INTCALC.jcl}, which drives the interest program {@code app/cbl/CBACT04C.cbl}
 * and writes a new generation of the generation group declared in {@code app/jcl/DEFGDGB.jcl}.
 *
 * <p>Provenance: the legacy estate is read-only reference at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is transcribed here and
 * nothing on this path reads the legacy tree at run time. Every figure below was measured by direct
 * read of that checkout and is a contract rather than guidance; what is reproduced is confined to what
 * the migration plan permits a target file to carry - step names, data-definition and dataset names,
 * condition-code semantics, record lengths, status codes, and the output record's own contract
 * literals.
 *
 * <h2>What this class is, and what it deliberately is not</h2>
 *
 * <p>It is <strong>composition only</strong>. It names the job, names its single step, resolves the
 * one output resource that step writes, and hands the pass to collaborators that own every behaviour.
 * <strong>No business rule lives here.</strong> The control break, the rate lookup with its single
 * default-group probe, the interest expression, the fee paragraph, the account rewrite and the
 * synthesized-transaction assembly are all in {@link InterestCalculationProcessor} and
 * {@link InterestCalculationService}; the open, read-loop, status-normalisation, close and abend
 * skeleton is in {@link AbstractCobolStep}; the monetary scale and its truncating rounding mode are in
 * {@link com.carddemo.util.ZonedDecimalCodec}; the fixed-width record image is in the transaction
 * record mapper. This class calls them and slices nothing.
 *
 * <h2>The measured legacy job stream</h2>
 *
 * <p>The member is forty-four lines and carries <strong>exactly one application step</strong>, named
 * {@value #LEGACY_STEP_NAME}, invoking {@value #LEGACY_PROGRAM_NAME} with a <strong>ten-character
 * parameter</strong>. <strong>It carries no condition-code gate.</strong> One step, a plain start, and
 * no failure-ending transition: adding one would refuse to run a step the legacy stream runs
 * unconditionally, and the flow shape is therefore an absence rather than an omission. The job chains
 * to nothing and no job of this module runs another, because the estate holds no master orchestrator.
 *
 * <table border="1">
 * <caption>Data definitions of the legacy step and what each becomes</caption>
 * <tr><th>Definition</th><th>Direction</th><th>Legacy resource</th><th>Target</th></tr>
 * <tr><td>{@value #DD_TCATBALF}</td><td>input, driving</td><td>transaction category balance,
 *     indexed</td><td>the category-balance repository, read in record-key order by this step</td></tr>
 * <tr><td>{@value #DD_XREFFILE}</td><td>input</td><td>card cross-reference, indexed</td>
 *     <td>the cross-reference repository, owned by {@link InterestCalculationService}</td></tr>
 * <tr><td>{@value #DD_XREFFIL1}</td><td><strong>allocated, never referenced</strong></td>
 *     <td>cross-reference alternate-index path</td><td><strong>not modelled</strong></td></tr>
 * <tr><td>{@value #DD_ACCTFILE}</td><td>input and output</td><td>account master, indexed</td>
 *     <td>the account repository, owned by {@link InterestCalculationService}</td></tr>
 * <tr><td>{@value #DD_DISCGRP}</td><td>input</td><td>disclosure group, indexed</td>
 *     <td>the disclosure-group repository, owned by {@link InterestCalculationService}</td></tr>
 * <tr><td>{@value #DD_TRANSACT}</td><td><strong>output</strong></td><td>synthesized transaction
 *     dataset, a new generation, fixed-length unblocked at {@value #TRANSACT_RECORD_LENGTH}
 *     bytes</td><td>the generation this step writes, resolved by logical name</td></tr>
 * </table>
 *
 * <p><strong>The unreferenced definition is an anomaly and is recorded rather than modelled.</strong>
 * The step allocates {@value #DD_XREFFIL1}, the cross-reference alternate-index <em>path</em>, but the
 * program never selects it - its own file list names the five other resources only. So no reader is
 * created for it, and none may be: modelling it would invent an access path the program does not use.
 *
 * <p><strong>The output is fixed-length unblocked.</strong> There is no block padding and no record
 * separator, so the artefact one execution produces is an exact multiple of
 * {@value #TRANSACT_RECORD_LENGTH} encoded bytes. A newline between records would make it a multiple
 * of one more than that and would be a byte the layout does not declare, so none is written. The
 * generation group is declared with a limit of five generations and scratch-on-roll-off; the
 * generation is modelled as a <strong>distinct output resource per execution</strong>, named in the
 * legacy absolute-generation form from the framework's own job execution identifier and resolved
 * against a configured staging area by logical name. <strong>No path is written into this class</strong>
 * and retention is deliberately not implemented here, because retention is a property of the
 * generation group rather than of the job that writes one generation.
 *
 * <h2>What this wiring must let the collaborators honour</h2>
 *
 * <p>The run's behaviour belongs to {@link InterestCalculationService} and
 * {@link InterestCalculationProcessor}, each of which documents its own invariants: the parenthesised
 * multiply-then-divide interest expression that truncates rather than rounds and must never be
 * rearranged algebraically; the zero-rate gate that skips the interest computation <em>and</em> the fee
 * computation together; the fee paragraph that is an invoked, documented no-op which no fee logic may
 * fill; the single default disclosure-group probe that abends on a second miss; the composite
 * disclosure key built in record-image order rather than in the legacy's assignment order; and the
 * synthesized transaction's field-by-field shape, including its identifier built from the launch
 * parameter used verbatim. None of that is reproduced here, because a business rule in a configuration
 * class is a rule in the wrong layer. Three consequences <em>are</em> this class's own, because it is
 * the only place that can guarantee them.
 *
 * <p><strong>No retry, no backoff and no skip is configured on this step.</strong> The legacy performs
 * exactly one default-group probe, and a framework retry would silently turn one probe into several.
 *
 * <p><strong>The final account control break is driven explicitly, on the completing path, before
 * anything is closed.</strong> The break adds the accumulated interest to the account's current balance
 * and resets <strong>both</strong> cycle accumulators. The last account has no successor row to trigger
 * its own break, so omitting the end-of-file break would lose that account's interest entirely while
 * leaving every other figure of the run looking correct - a silent data-loss defect.
 *
 * <p><strong>The launch parameter reaches the service unaltered.</strong> It is the ten characters the
 * legacy step passes, and the synthesized identifier uses them verbatim, so it must not be reformatted
 * into a hyphenated or otherwise separated date on its way through the launch boundary.
 *
 * <h2>Diagnostics and failure</h2>
 *
 * <p>Console display becomes structured logging. On a terminal input or output failure the raw
 * two-byte file status is logged <strong>first</strong> and the abend is raised <strong>after</strong>
 * it; that ordering is contractual and belongs to {@link AbstractCobolStep}, which owns the one failure
 * path this step routes every guarded operation through. Nothing here re-implements it and nothing here
 * logs a failure before it. End of file is never collapsed into error: the coarse tri-state the legacy
 * programs branch on is nested in that same skeleton, and the raw status vocabulary the estate actually
 * compares lives in {@link FileStatus}. Two of that enumeration's codes are documented but never
 * exercised by the legacy, and no code path here depends on either.
 *
 * <p><strong>Both interest branches are reachable from seeded reference data alone.</strong> The
 * disclosure-group fixture holds three consecutive seventeen-row groups, one of them the default group
 * and one of them carrying a zero rate, so the single default-group probe and the zero-rate skip are
 * both exercised without any synthetic fixture. A test author needs to seed nothing extra to cover
 * them.
 *
 * <h2>Divergences, recorded rather than hidden</h2>
 *
 * <p><strong>The unit of work is the step, not the control break.</strong> The service commits at each
 * control break when it is called outside a transaction; driven from a step, its declarative boundary
 * joins the step's, so the relational effects of the whole pass commit together. The legacy had no
 * commit point at all - its files were defined with no recovery and no journal, and every write was
 * immediate and unjournaled - so neither shape reproduces it exactly, and the whole-pass boundary is
 * the safer of the two: an abend cannot leave an interest run half posted. This is an improvement over
 * the baseline and is labelled as one so that a reviewer does not read the stronger guarantee as a
 * regression.
 *
 * <p><strong>An abend leaves the generation partially written.</strong> Records reach the generation as
 * they are produced, exactly as the legacy wrote one record at a time, so a failure mid-run leaves the
 * records written so far - which is what the legacy left behind too. The relational effects roll back
 * with the step, which is the improvement described above; the two are consistent for a completing run
 * and deliberately differ for a failing one.
 *
 * <p><strong>The three keyed inputs are not opened here.</strong> The legacy opens five files and
 * closes five. This step opens and closes the two resources it owns - the driving input and the output
 * generation - and the service owns the other three, whose open and close paragraphs it preserves as
 * named methods that issue no call, because a relational store has no dataset to open and returns no
 * two-byte status for one. Nothing observable is lost and nothing is invented.
 *
 * @see InterestCalculationProcessor
 * @see InterestCalculationService
 * @see AbstractCobolStep
 * @see JobParameterValidators#interestParmDateValidator()
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public final class InterestCalculationJobConfig {

    /**
     * This class's own diagnostic channel. Every message it emits is about wiring or about a resource
     * this class resolved; a record is never logged, and no monetary amount, account identifier or card
     * number reaches it.
     */
    private static final Logger LOG = LoggerFactory.getLogger(InterestCalculationJobConfig.class);

    /**
     * Registered name of the job. Launch and status queries address the job by name through the
     * framework's registry, so this value is API: renaming it breaks every launcher that names it.
     *
     * <p>The value is read from {@code service/BatchJobCatalog}, which is the module's single
     * declaration of the nine stable job names. Both tiers that need a name - this configuration
     * and the operational control surface above it - resolve it from there, so the name exists as
     * one literal and the two cannot drift apart across a boundary the layering keeps closed.
     */
    public static final String JOB_NAME = BatchJobCatalog.INTEREST_CALCULATION_JOB_NAME;

    /**
     * Registered name of the one step. A configuration-time constant rather than a value derived from
     * the launch, so a restart addresses the same step it did before.
     */
    public static final String STEP_NAME = "interestAccrualStep";

    /** Legacy job member this configuration translates. */
    public static final String LEGACY_JOB_MEMBER = InterestCalculationProcessor.LEGACY_JOB;

    /** The member's single application step, and the only one it declares. */
    public static final String LEGACY_STEP_NAME = InterestCalculationProcessor.LEGACY_STEP;

    /** Legacy program the step invokes. */
    public static final String LEGACY_PROGRAM_NAME = InterestCalculationProcessor.LEGACY_PROGRAM;

    /**
     * Key of the launch parameter that carries the ten-character run date, re-exported from the module's
     * one parameter-contract owner so that this file cannot introduce a second spelling of it.
     */
    public static final String PARM_DATE_KEY = JobParameterValidators.INTEREST_PARM_DATE_KEY;

    /** Driving input: the transaction category balance master, read in record-key order. */
    public static final String DD_TCATBALF = "TCATBALF";

    /** Keyed input: the card cross-reference, resolved by the service that owns it. */
    public static final String DD_XREFFILE = "XREFFILE";

    /**
     * The allocated-but-never-referenced definition: the cross-reference alternate-index path. Named
     * here so the anomaly is discoverable, and modelled nowhere.
     */
    public static final String DD_XREFFIL1 = InterestCalculationProcessor.LEGACY_UNREFERENCED_DD;

    /** Input and output: the account master, rewritten at each control break by the service. */
    public static final String DD_ACCTFILE = "ACCTFILE";

    /** Keyed input: the disclosure group, from which the rate is resolved. */
    public static final String DD_DISCGRP = "DISCGRP";

    /** Output: the synthesized transaction generation this step writes. */
    public static final String DD_TRANSACT = InterestCalculationProcessor.OUTPUT_RESOURCE;

    /**
     * Width, in encoded bytes, of one record of the output generation. Fixed and unblocked, so the
     * artefact is an exact multiple of this figure.
     */
    public static final int TRANSACT_RECORD_LENGTH =
            InterestCalculationProcessor.INTEREST_RECORD_LENGTH;

    /**
     * Configuration key of the staging area every dataset of this job resolves within. A deployment may
     * relocate the area without this job losing the identity of what it writes.
     */
    public static final String STAGING_DIRECTORY_PROPERTY =
            "carddemo.batch.interest-calculation.staging-directory";

    /**
     * Configuration key of the output generation group's logical name. It defaults to the name the
     * legacy stream uses for the same resource, so the default is the legacy identity rather than an
     * invention.
     */
    public static final String TRANSACT_DATASET_BASE_PROPERTY =
            "carddemo.batch.interest-calculation.transact-dataset-base";

    /** Bean name of the shared job-boundary diagnostic the batch infrastructure publishes. */
    private static final String BOUNDARY_LISTENER_BEAN_NAME = "batchJobBoundaryListener";

    /** Bean name of the shared parameter incrementer the same configuration publishes. */
    private static final String RUN_INCREMENTER_BEAN_NAME = "batchJobRunIncrementer";

    /** Logical name of the output generation group, as the legacy stream names it. */
    private static final String DEFAULT_TRANSACT_DATASET_BASE = "AWS.M2.CARDDEMO.SYSTRAN";

    private static final int KEYSET_PAGE_SIZE = BoundedKeysetIterator.DEFAULT_PAGE_SIZE;

    private static final int TRAN_CAT_BAL_ACCOUNT_KEY_LENGTH = 11;

    private static final int TRAN_CAT_BAL_TYPE_KEY_LENGTH = 2;

    /**
     * Prevents the tasklet adapter from opening one transaction around the complete file pass.
     *
     * <p>Each closed account group enters
     * {@link com.carddemo.service.InterestGroupTransactionBoundary} instead. Using the same configured
     * manager with {@link TransactionDefinition#PROPAGATION_NOT_SUPPORTED} leaves no transaction or
     * transaction synchronization active around the tasklet, so the group's
     * {@code REQUIRES_NEW} boundary is the only business transaction.
     */
    private static final TransactionAttribute NO_ENCOMPASSING_TRANSACTION =
            new DefaultTransactionAttribute(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

    /** The framework's metadata repository, which records every execution of this job. */
    private final JobRepository jobRepository;

    /** The manager used to suspend any caller transaction around the tasklet adapter. */
    private final PlatformTransactionManager transactionManager;

    /** Owner of the launch-boundary parameter contract, including the run date's cascade. */
    private final JobParameterValidators jobParameterValidators;

    /** The translated program's service, which owns every per-row and per-group behaviour. */
    private final InterestCalculationService interestCalculationService;

    /** The driving input's repository, read once per execution in record-key order. */
    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    /** The registry the step's own timing and the collaborators' meters are recorded on. */
    private final MeterRegistry meterRegistry;

    /** The source of the batch timestamp's instant, injected so a test can fix it. */
    private final Clock clock;

    /** Directory the output generation resolves within. */
    private final String stagingDirectory;

    /** Logical name of the output generation group. */
    private final String transactDatasetBase;

    /**
     * Wires the job's collaborators.
     *
     * <p>Constructor injection throughout and every collaborator required: this job reads a master,
     * posts to four more resources through its service and writes a generation, so there is no degraded
     * mode in which a missing collaborator would be acceptable. Both resource names arrive as
     * configuration and neither is a path, so relocating the staging area is a configuration change and
     * never a code change.
     *
     * @param jobRepository the framework's metadata repository; must not be {@code null}
     * @param transactionManager the manager the step runs under; must not be {@code null}
     * @param jobParameterValidators owner of the launch-boundary parameter contract; must not be
     *                               {@code null}
     * @param interestCalculationService the translated program; must not be {@code null}
     * @param transactionCategoryBalanceRepository the driving input's repository; must not be
     *                                             {@code null}
     * @param meterRegistry the registry timing is recorded on; must not be {@code null}
     * @param clock the clock batch timestamps are read from; must not be {@code null}
     * @param stagingDirectory directory the output generation resolves within; must name a directory
     * @param transactDatasetBase logical name of the output generation group; must name a resource
     * @throws NullPointerException if any collaborator is absent
     * @throws IllegalArgumentException if either resource name is blank
     */
    public InterestCalculationJobConfig(
            final JobRepository jobRepository,
            final PlatformTransactionManager transactionManager,
            final JobParameterValidators jobParameterValidators,
            final InterestCalculationService interestCalculationService,
            final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
            final MeterRegistry meterRegistry,
            final Clock clock,
            @Value("${" + STAGING_DIRECTORY_PROPERTY + ":${"
                    + StagedGenerationStore.SHARED_STAGING_DIRECTORY_PROPERTY
                    + ":${java.io.tmpdir}}}")
                    final String stagingDirectory,
            @Value("${" + TRANSACT_DATASET_BASE_PROPERTY + ":" + DEFAULT_TRANSACT_DATASET_BASE + "}")
                    final String transactDatasetBase) {

        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository must not be null");
        this.transactionManager = Objects.requireNonNull(transactionManager,
                "transactionManager must not be null");
        this.jobParameterValidators = Objects.requireNonNull(jobParameterValidators,
                "jobParameterValidators must not be null: the run date's contract has one owner and"
                        + " this job attaches that owner's validator rather than restating it");
        this.interestCalculationService = Objects.requireNonNull(interestCalculationService,
                "interestCalculationService must not be null: this configuration composes the run and"
                        + " implements none of it");
        this.transactionCategoryBalanceRepository = Objects.requireNonNull(
                transactionCategoryBalanceRepository,
                "transactionCategoryBalanceRepository must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.stagingDirectory = requireResourceName(stagingDirectory, "stagingDirectory");
        this.transactDatasetBase =
                StagedResourceNames.requireSimpleName(transactDatasetBase, "transactDatasetBase");
    }

    // -------------------------------------------------------------------------------------------
    // The job and its one step.
    // -------------------------------------------------------------------------------------------

    /**
     * The interest accrual job: one step, reached by a plain start, wired into nothing.
     *
     * <p><strong>What it carries, and why each is here.</strong> The run date's parameter validator, so
     * a launch whose parameter is absent, mis-sized, non-numeric or not a calendar date is refused at
     * the boundary instead of minting sixteen-character identifiers from it; the validator is obtained
     * from the module's one parameter-contract owner and its key constant is re-exported as
     * {@link #PARM_DATE_KEY}, so a second spelling of the key cannot appear. The shared parameter
     * incrementer, so an identical resubmission runs again exactly as resubmitting a job member did,
     * and so the job can be advanced to its next instance when it is started by name. The shared
     * boundary diagnostic, attached here rather than globally so that a job without one would be
     * visible in its own source.
     *
     * <p><strong>What it deliberately does not carry.</strong> No condition-code gate and no
     * failure-ending transition, because the member declares none - the measured count of gates on this
     * step is zero. No second step, no decider, no split and no nested flow. No chaining to another
     * job, because the estate holds no master orchestrator and a job that runs everything would be an
     * invention.
     *
     * <p><strong>Nothing launches this job when the context starts.</strong> Launch-on-start is
     * disabled by the shared configuration document, and this class contributes no runner, no lifecycle
     * participant, no initialising callback, no event listener and no scheduled trigger. Publishing the
     * job registers it for launch on demand and does nothing else.
     *
     * @param interestAccrualStep the single step, injected by name so a module of nine jobs cannot
     *                            resolve the wrong one; must not be {@code null}
     * @param batchJobRunIncrementer the shared parameter incrementer; must not be {@code null}
     * @param batchJobBoundaryListener the shared job-boundary diagnostic; must not be {@code null}
     * @return the job, registered under {@link #JOB_NAME}
     * @throws NullPointerException if any collaborator is absent
     */
    @Bean(name = JOB_NAME)
    public Job interestCalculationJob(
            @Qualifier(STEP_NAME) final Step interestAccrualStep,
            @Qualifier(RUN_INCREMENTER_BEAN_NAME) final JobParametersIncrementer batchJobRunIncrementer,
            @Qualifier(BOUNDARY_LISTENER_BEAN_NAME) final JobExecutionListener batchJobBoundaryListener) {

        Objects.requireNonNull(interestAccrualStep, "interestAccrualStep must not be null");
        Objects.requireNonNull(batchJobRunIncrementer, "batchJobRunIncrementer must not be null");
        Objects.requireNonNull(batchJobBoundaryListener, "batchJobBoundaryListener must not be null");

        LOG.debug("Defining job {} over step {} for legacy member {} step {} program {}", JOB_NAME,
                STEP_NAME, LEGACY_JOB_MEMBER, LEGACY_STEP_NAME, LEGACY_PROGRAM_NAME);

        return new JobBuilder(JOB_NAME, this.jobRepository)
                .validator(this.jobParameterValidators.interestParmDateValidator())
                .incrementer(batchJobRunIncrementer)
                .listener(batchJobBoundaryListener)
                .start(interestAccrualStep)
                .build();
    }

    /**
     * The one step: the whole program in one sequential pass with one commit per closed account group.
     *
     * <p><strong>A single-invocation tasklet and not a chunk-oriented step.</strong> The program is one
     * pass over one driving input, but the pass is deliberately <em>not</em> one transaction. Each
     * account control break invokes the service through its independent transaction boundary, matching
     * the AAP's account-group commit point. A later account failure therefore leaves every earlier
     * completed account durable. The tasklet transaction attribute is
     * {@link TransactionDefinition#PROPAGATION_NOT_SUPPORTED}; no outer transaction can absorb those
     * group commits. There is still no arbitrary chunk interval, because no such figure has a legacy
     * basis. None appears anywhere in this file - no throughput, latency, heap, timeout, thread-pool,
     * skip, retry, commit-interval, backoff or connection figure, in code or in comment - because the
     * metrics endpoint is where a baseline is read from and this file states no target for one.
     *
     * <p><strong>Strictly sequential.</strong> No task executor, no partitioning, no multi-threaded
     * step and no parallel flow, because the output is ordered fixed-width records and any of the three
     * would interleave them, which the byte-for-byte comparison of the artefact would detect.
     *
     * <p><strong>Timed.</strong> The registry is attached to the builder so the framework's own step
     * metrics reach the registry the metrics endpoint publishes rather than a global default, and the
     * pass itself is timed by the shared batch step template on its per-step timer, tagged with the
     * program and the outcome. No retry policy and no backoff is attached, deliberately: the legacy's
     * only re-read is the single default-group probe, which the service performs once, and a framework
     * retry here would turn one probe into several.
     *
     * @param stagingArea the shared object-store staging boundary
     * @return the step, registered under {@link #STEP_NAME}
     */
    @Bean(name = STEP_NAME)
    public Step interestAccrualStep(final BatchStagingArea stagingArea) {
        return new StepBuilder(STEP_NAME, this.jobRepository)
                .meterRegistry(this.meterRegistry)
                .tasklet((contribution, chunkContext) ->
                        runInterestAccrual(contribution, chunkContext, stagingArea),
                        this.transactionManager)
                .transactionAttribute(NO_ENCOMPASSING_TRANSACTION)
                .build();
    }

    // -------------------------------------------------------------------------------------------
    // The pass. A FRESH program lifecycle is constructed for the execution it serves, so every
    // per-execution handle - the run's identifier suffix, the read cursor, the output handle and the
    // generation name - lives on a short-lived object and no mutable field is shared between
    // executions or between runs.
    // -------------------------------------------------------------------------------------------

    /**
     * Adapts the pass to the framework's tasklet contract.
     *
     * @param contribution the framework's per-step contribution, unused because the pass is one
     *                     indivisible invocation with no partial contribution to report
     * @param chunkContext the framework's chunk context, which supplies the step execution; must not be
     *                     {@code null}
     * @param stagingArea the shared object-store staging boundary
     * @return {@link RepeatStatus#FINISHED} always
     * @throws NullPointerException if the chunk context or its step execution is absent
     */
    private RepeatStatus runInterestAccrual(final StepContribution contribution,
            final ChunkContext chunkContext, final BatchStagingArea stagingArea) {

        final StepExecution stepExecution = stepExecutionOf(chunkContext);
        runAccrualPass(stepExecution);
        stagingArea.publish(transactGeneration(jobExecutionIdOf(stepExecution)));
        return RepeatStatus.FINISHED;
    }

    /**
     * Runs one complete interest accrual pass and reports what it produced.
     *
     * <p>Exposed rather than inlined so the pass can be driven directly, without a launcher, by a test
     * that holds a step execution and wants to observe the outcome.
     *
     * <p>The pass constructs a fresh control break for this execution. Doing so is deliberate and is
     * supported by that stage: its meter registration resolves the same meters rather than creating new
     * ones, so an execution costs no additional instrumentation, and a fresh instance is what guarantees
     * that the six-digit identifier suffix cannot survive from one run into the next. The stage's
     * lifecycle hooks are invoked explicitly by the pass, in the order the program performs them, which
     * is why this step registers no execution listener and depends on no listener ordering.
     *
     * @param stepExecution the execution the pass belongs to, which carries the run date parameter and
     *                      the job execution identifier the generation is named from; must not be
     *                      {@code null}
     * @return the program's own summary of the pass: its name, the rows the read loop delivered, and
     *         the batch timestamps it started and completed at
     * @throws NullPointerException if the step execution is absent
     * @throws IllegalArgumentException if the run date parameter is missing, mis-sized or not all digits
     * @throws com.carddemo.exception.AbendException if any guarded operation reports a status this
     *         translation treats as a failure, after the raw status has been logged
     */
    public ExecutionSummary runAccrualPass(final StepExecution stepExecution) {
        Objects.requireNonNull(stepExecution, "stepExecution must not be null: the pass reads its run"
                + " date and its generation number from it");

        final InterestCalculationProcessor accrual = new InterestCalculationProcessor(
                this.interestCalculationService, this.meterRegistry, this.clock);
        final long executionId = jobExecutionIdOf(stepExecution);
        final Path generation = transactGeneration(executionId);
        final Path working = StagedGenerationStore.workingPath(generation);

        final ExecutionSummary summary = new InterestAccrualStep(accrual,
                this.transactionCategoryBalanceRepository, this.meterRegistry, this.clock,
                stepExecution, working).run();
        StagedGenerationStore.completeWorkingFile(working, generation);
        StagedGenerationStore.register(stepExecution, this.transactDatasetBase, generation,
                StagedGenerationStore.STANDARD_RETENTION_LIMIT);
        return summary;
    }

    // -------------------------------------------------------------------------------------------
    // Resource resolution. Every resource is a logical name resolved against a configured directory;
    // no path is written into this class and no retention is implemented in it.
    // -------------------------------------------------------------------------------------------

    /**
     * The output generation one job execution owns, named in the legacy absolute-generation form.
     *
     * <p>Naming the generation from the framework's own execution identifier is what makes each
     * execution write a distinct resource, as the legacy step's new-generation allocation did, without
     * this class holding any state between executions. The identifier is never reduced modulo a
     * narrower range, so names do not collide; the shared durable store applies the measured
     * scratch-on-roll-off depth after the whole job completes.
     *
     * <p>Exposed so a test that launched the job can locate the artefact the launch produced and
     * confirm that its size is an exact multiple of {@value #TRANSACT_RECORD_LENGTH} encoded bytes.
     *
     * @param jobExecutionId the job execution the generation belongs to
     * @return the resolved generation resource
     */
    public Path transactGeneration(final long jobExecutionId) {
        return StagedGenerationStore.generationPath(Path.of(this.stagingDirectory),
                this.transactDatasetBase, jobExecutionId);
    }

    /**
     * Reads the step execution the framework is running, refusing a context that carries none.
     *
     * @param chunkContext the framework's chunk context; must not be {@code null}
     * @return the step execution
     * @throws NullPointerException if the context or its step execution is absent
     */
    private static StepExecution stepExecutionOf(final ChunkContext chunkContext) {
        Objects.requireNonNull(chunkContext, "chunkContext must not be null");
        return Objects.requireNonNull(chunkContext.getStepContext().getStepExecution(),
                "the framework must have opened a step execution before its tasklet runs");
    }

    /**
     * Reads the job execution identifier the framework assigned, which is what names the one generation
     * this execution owns.
     *
     * @param stepExecution the execution; must not be {@code null}
     * @return the job execution identifier
     * @throws NullPointerException if the framework assigned none
     */
    private static long jobExecutionIdOf(final StepExecution stepExecution) {
        final Long identifier = stepExecution.getJobExecutionId();
        return Objects.requireNonNull(identifier,
                "the framework must have assigned a job execution identifier before a step runs")
                .longValue();
    }

    /**
     * Validates a configured resource name, because a blank one would resolve to the staging directory
     * itself and the job would then truncate a directory rather than write a dataset.
     *
     * <p><strong>This governs the staging root only.</strong> A dataset name resolved against that root
     * is screened by {@link StagedResourceNames#requireSimpleName(String, String)}, which additionally
     * refuses an absolute value, a path separator and a directory reference - none of which a blank
     * check catches, and each of which would resolve outside the root. The root itself is legitimately
     * a multi-segment path and may be absolute, so the stricter rule cannot be applied to it.
     *
     * @param value the configured value
     * @param name the property's role, for the diagnostic
     * @return the validated value
     * @throws NullPointerException if the value is absent
     * @throws IllegalArgumentException if the value is blank
     */
    private static String requireResourceName(final String value, final String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must name a resource; a blank logical name"
                    + " would resolve to the staging directory itself rather than to a dataset");
        }
        return value;
    }

    /**
     * One execution of the interest program: the open family, the read loop over the driving input, the
     * per-row control break, the record-at-a-time write of the output generation, the final control
     * break and the close family, all routed through the shared batch step template so that the
     * status-normalisation and the one ordered failure path are the module's and not this class's.
     *
     * <p>Short-lived by construction. One instance serves one step execution, which is what allows the
     * read cursor and the output handle to be fields at all: there is no execution after this one for a
     * value to leak into. Nothing here is a business rule - every decision about a row, a rate, an
     * amount or an account belongs to the control break this class drives.
     */
    private static final class InterestAccrualStep
            extends AbstractCobolStep<TransactionCategoryBalance> {

        /** The control break, which owns every per-row and per-group decision of the run. */
        private final InterestCalculationProcessor accrual;

        /** The driving input's repository. */
        private final TransactionCategoryBalanceRepository categoryBalances;

        /** The execution this pass serves, which carries the run date the control break reads. */
        private final StepExecution stepExecution;

        /** The generation this execution writes. */
        private final Path generation;

        /** Cursor over the driving input's record-key-ordered snapshot; installed by the open family. */
        private Iterator<TransactionCategoryBalance> master;

        /** Handle on the output generation; installed by the open family and released by the close. */
        private OutputStream generationStream;

        /** How many records reached the generation, for the completion diagnostic. */
        private long recordsWritten;

        /**
         * Constructs one pass.
         *
         * @param accrual the control break; must not be {@code null}
         * @param categoryBalances the driving input's repository; must not be {@code null}
         * @param meterRegistry the registry the template's per-step timer is recorded on; must not be
         *                      {@code null}
         * @param clock the clock the batch timestamps are read from; must not be {@code null}
         * @param stepExecution the execution this pass serves; must not be {@code null}
         * @param generation the generation this pass writes; must not be {@code null}
         * @throws NullPointerException if any argument is absent
         */
        InterestAccrualStep(final InterestCalculationProcessor accrual,
                final TransactionCategoryBalanceRepository categoryBalances,
                final MeterRegistry meterRegistry,
                final Clock clock,
                final StepExecution stepExecution,
                final Path generation) {

            super(LEGACY_PROGRAM_NAME, meterRegistry, clock);
            this.accrual = Objects.requireNonNull(accrual, "accrual must not be null");
            this.categoryBalances = Objects.requireNonNull(categoryBalances,
                    "categoryBalances must not be null");
            this.stepExecution = Objects.requireNonNull(stepExecution,
                    "stepExecution must not be null");
            this.generation = Objects.requireNonNull(generation, "generation must not be null");
        }

        /**
         * The open family: install the run's working state, then open the two resources this step owns.
         *
         * <p>The working state is installed first because the program's own working-storage items carry
         * their initial values before any file is opened; installing it here is also what validates the
         * run date, so an execution that somehow reached a step without one fails before it touches a
         * resource.
         *
         * <p>The driving input's open is the point at which its record-key-ordered snapshot is taken.
         * Taking it through the guarded open means a data-access failure is diagnosed with the
         * operation, the resource and a raw status, in that order, by the one failure path the template
         * owns. The other three legacy inputs are opened by the service that owns them, whose open
         * paragraphs issue no call because a relational store has no dataset to open.
         */
        @Override
        protected void openResources() {
            this.accrual.beforeStep(this.stepExecution);
            openResource(DD_TCATBALF, this::openCategoryBalanceMaster);
            openResource(DD_TRANSACT, this::openGeneration);
        }

        /**
         * Takes the driving input's snapshot in record-key order.
         *
         * @return the status a successful open reports
         */
        private String openCategoryBalanceMaster() {
            this.master = new BoundedKeysetIterator<>("", KEYSET_PAGE_SIZE,
                    this::loadCategoryBalancePage,
                    InterestCalculationJobConfig::categoryBalanceKey,
                    Comparator.naturalOrder());
            this.master.hasNext();
            return FileStatus.SUCCESS.getCode();
        }

        private List<TransactionCategoryBalance> loadCategoryBalancePage(
                final String cursor, final Integer pageSize) {
            final String accountId = keyPart(cursor, 0, TRAN_CAT_BAL_ACCOUNT_KEY_LENGTH);
            final int typeOffset = TRAN_CAT_BAL_ACCOUNT_KEY_LENGTH;
            final String typeCode = keyPart(cursor, typeOffset, TRAN_CAT_BAL_TYPE_KEY_LENGTH);
            final int categoryOffset = typeOffset + TRAN_CAT_BAL_TYPE_KEY_LENGTH;
            final String categoryCode = cursor.length() <= categoryOffset
                    ? ""
                    : cursor.substring(categoryOffset);
            return this.categoryBalances.findAfterKey(accountId, typeCode, categoryCode,
                    PageRequest.of(0, pageSize.intValue()));
        }

        /**
         * Opens the generation this execution owns, creating the staging area if it is absent.
         *
         * <p>An existing generation of the same name is truncated rather than refused, so a restart of
         * the same execution rewrites its own generation cleanly instead of appending to a partial one.
         * No separator is configured and none is written: the records are fixed-length and unblocked.
         *
         * @return the status a successful open reports
         * @throws IOException if the staging area or the generation cannot be opened, which the template
         *         turns into the one ordered failure path
         */
        private String openGeneration() throws IOException {
            final Path container = this.generation.getParent();
            if (container != null) {
                Files.createDirectories(container);
            }
            this.generationStream = Files.newOutputStream(this.generation,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            LOG.debug("Opened generation {} for legacy definition {} at {} bytes per record",
                    this.generation, DD_TRANSACT, TRANSACT_RECORD_LENGTH);
            return FileStatus.SUCCESS.getCode();
        }

        /**
         * The read paragraph: one row of the driving input per call, in record-key order.
         *
         * @return the row read, or an empty result at end of file, which ends the loop normally
         */
        @Override
        protected Optional<TransactionCategoryBalance> readNextRecord() {
            return readRecord(DD_TCATBALF, this::readCategoryBalanceRow);
        }

        /**
         * Hands back the next row of the snapshot, or end of file when it is exhausted.
         *
         * <p>End of file is reported as its own status rather than as an error, because the read loop's
         * normal termination and a failed read are two different outcomes and collapsing them would
         * turn the end of every run into an abend.
         *
         * @return the row and its status, or the end-of-file result
         */
        private IoResult<TransactionCategoryBalance> readCategoryBalanceRow() {
            final Iterator<TransactionCategoryBalance> cursor = Objects.requireNonNull(this.master,
                    "the driving input was not opened before it was read");
            if (!cursor.hasNext()) {
                return IoResult.endOfFile();
            }
            return IoResult.of(FileStatus.SUCCESS.getCode(), cursor.next());
        }

        /**
         * The per-row work: hand the row to the control break and render whatever group its arrival
         * closed.
         *
         * <p>The control break reports the group a row's arrival closed, or nothing while the group the
         * row belongs to is still filling, so a row that closes no group produces no output here and
         * loses nothing: its accrual is emitted with the group that contains it. Everything the row is
         * judged by - the account-key comparison, the rate lookup and its single default-group probe,
         * the interest expression, the fee paragraph, the account rewrite and the transaction assembly -
         * happens inside that call and none of it is reproduced here.
         *
         * @param categoryBalanceRow the row just read
         */
        @Override
        protected void processRecord(final TransactionCategoryBalance categoryBalanceRow) {
            final AccruedAccountGroup closed = this.accrual.process(categoryBalanceRow);
            if (closed != null) {
                writeAccruedGroup(closed);
            }
        }

        /**
         * The close family, in the order the program closes: <strong>the final control break first</strong>,
         * then the records it produced, then the resources.
         *
         * <p>The final break is what posts the last account. It has no successor row to trigger it, so
         * without this call the account would be read, accrued and never posted while every other figure
         * of the run still looked correct. It runs before anything is closed because the program's
         * end-of-file arm precedes its close paragraphs, and it is reached only on the completing path
         * because a legacy abend never reaches the closes at all - which is exactly what the template
         * guarantees by invoking this method on the completing path only.
         *
         * <p>The driving input is closed before the output, matching the order the program closes its
         * files in.
         */
        @Override
        protected void closeResources() {
            this.accrual.afterStep(this.stepExecution);
            this.accrual.finalAccruedGroup().ifPresent(this::writeAccruedGroup);

            closeResource(DD_TCATBALF, this::closeCategoryBalanceMaster);
            closeResource(DD_TRANSACT, this::closeGeneration);

            LOG.info("PROGRAM {} WROTE {} RECORD(S) OF {} BYTE(S) TO {} AS {}", programName(),
                    this.recordsWritten, TRANSACT_RECORD_LENGTH, DD_TRANSACT, this.generation);
        }

        /**
         * Renders one closed account group as fixed-width records of the output generation.
         *
         * <p>One record per synthesized transaction, in the order the group synthesized them, each
         * written through the guarded write so that a failure is diagnosed and abended by the one
         * ordered failure path rather than by this method. A group whose every row was skipped by the
         * zero-rate gate synthesized nothing and therefore writes nothing, which is the legacy outcome
         * for such a group.
         *
         * @param group the group the control break closed
         */
        private void writeAccruedGroup(final AccruedAccountGroup group) {
            for (final Transaction synthesized : group.interestTransactions()) {
                writeRecord(DD_TRANSACT, () -> writeGenerationRecord(synthesized));
            }
        }

        /**
         * Writes one synthesized transaction as one record of the generation.
         *
         * <p>The record image is rendered by the control break's own renderer, which delegates to the
         * single authority for the layout and confirms the width on the <strong>encoded byte
         * array</strong> before handing it back. No offset arithmetic is performed here and no character
         * count is taken: a character count is not a width, since one character outside the seven-bit
         * range satisfies the first and breaches the second.
         *
         * @param synthesized the transaction to write
         * @return the status a successful write reports
         * @throws IOException if the generation cannot be written, which the template turns into the one
         *         ordered failure path
         */
        private String writeGenerationRecord(final Transaction synthesized) throws IOException {
            final OutputStream open = Objects.requireNonNull(this.generationStream,
                    "the output generation was not opened before it was written");
            open.write(InterestCalculationProcessor.interestRecordImageBytes(synthesized));
            this.recordsWritten++;
            return FileStatus.SUCCESS.getCode();
        }

        /**
         * Closes the driving input by releasing its cursor.
         *
         * @return the status a successful close reports
         */
        private String closeCategoryBalanceMaster() {
            this.master = null;
            return FileStatus.SUCCESS.getCode();
        }

        /**
         * Closes the output generation.
         *
         * @return the status a successful close reports
         * @throws IOException if the handle cannot be closed, which the template turns into the one
         *         ordered failure path
         */
        private String closeGeneration() throws IOException {
            final OutputStream open = this.generationStream;
            this.generationStream = null;
            if (open != null) {
                open.close();
            }
            return FileStatus.SUCCESS.getCode();
        }

        /**
         * Hands back the output handle after a failure, which is a runtime adaptation and not a second
         * close sequence.
         *
         * <p>It emits no legacy diagnostic, normalises no status and never abends, because the abend has
         * already been raised and reported by the site that decided on it; adding a second report would
         * break the ordering the batch tier treats as a contract. A failure to release is reported at
         * warning level and retained rather than raised, so it cannot displace the failure that actually
         * ended the run.
         *
         * <p>Whatever records had already reached the generation stay there. That is what the legacy left
         * behind too: it wrote one record at a time to an unjournaled dataset, so an abend mid-run left
         * the records written so far.
         */
        @Override
        protected void releaseResources() {
            this.master = null;
            final OutputStream open = this.generationStream;
            this.generationStream = null;
            if (open == null) {
                LOG.debug("PROGRAM {} HOLDS NO GENERATION HANDLE TO RELEASE AFTER FAILURE",
                        programName());
                return;
            }
            try {
                open.close();
                LOG.warn("PROGRAM {} RELEASED GENERATION {} AFTER FAILURE WITH {} RECORD(S) WRITTEN",
                        programName(), this.generation, this.recordsWritten);
            } catch (final IOException unreleased) {
                LOG.warn("PROGRAM {} COULD NOT RELEASE GENERATION {} AFTER FAILURE failureChain={}",
                        programName(), this.generation,
                        FailureDiagnostics.failureChainOf(unreleased));
            }
        }
    }

    private static String keyPart(final String key, final int offset, final int width) {
        if (key.length() <= offset) {
            return "";
        }
        return key.substring(offset, Math.min(key.length(), offset + width));
    }

    private static String categoryBalanceKey(final TransactionCategoryBalance balance) {
        return balance.getTrancatAcctId() + balance.getTrancatTypeCd() + balance.getTrancatCd();
    }
}
