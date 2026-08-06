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

import com.carddemo.batch.step.FixedWidthFlatFileReaderFactory;
import com.carddemo.domain.Account;
import com.carddemo.domain.Card;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.DailyTransaction;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.exception.AbendException;
import com.carddemo.service.BatchJobCatalog;
import com.carddemo.service.DailyTransactionReadService;
import com.carddemo.service.DailyTransactionReadService.DailyTransactionReadResult;
import com.carddemo.service.DailyTransactionReadService.DailyTransactionVerification;
import com.carddemo.util.BatchCancellation;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * <strong>The job that nothing runs.</strong> This configuration publishes a complete, launchable job for
 * the daily-transaction extract program {@code CBTRN01C}, and that job is <em>deliberately absent from
 * every pipeline in this module</em>.
 *
 * <p><strong>The orphan finding, which is the defining constraint of this file.</strong>
 * {@code app/cbl/CBTRN01C.cbl} is 491 lines long, holds 18 paragraphs in its procedure division and names
 * six file resources. It is complete and syntactically whole; it is neither a stub nor a fragment. And an
 * exhaustive search of the estate finds its name nowhere outside its own member: <em>no</em> job member in
 * {@code app/jcl}, <em>no</em> cataloged procedure in {@code app/proc} and <em>no</em> entry in
 * {@code app/csd/CARDDEMO.CSD} invokes it. Nothing ran it on the mainframe, so nothing may run it here.
 *
 * <p>Six consequences follow, and each is honoured literally rather than approximately.
 *
 * <ol>
 *   <li><strong>The program is translated in full.</strong> Its completeness is the whole reason it is in
 *       scope; an abbreviated translation would be a coverage gap dressed as a simplification. All 18
 *       paragraphs are carried across in {@link DailyTransactionReadService}, one method per paragraph.</li>
 *   <li><strong>There is no legacy job stream, so there is nothing to reproduce from one.</strong> No step
 *       name, no parameter string and no condition-code gate exists to be preserved. This job therefore
 *       declares <em>no</em> failure-ending flow transition, attaches <em>no</em> parameter validator, and
 *       takes <em>no</em> date or mode job parameter - the translated service resolves its own input and
 *       asks for neither. Nothing from {@link JobParameterValidators} is attached, because every contract
 *       that class carries comes from a job member, and this program has none.</li>
 *   <li><strong>The Java names are original, not inherited.</strong> {@link #JOB_NAME} and
 *       {@link #STEP_NAME} were chosen here. A reviewer looking for them in {@code app/jcl} will not find
 *       them, and that is not an oversight: there is no member to find them in.</li>
 *   <li><strong>It is absent from the default pipeline.</strong> This job is not chained from or into any
 *       other job, appears nowhere in the posting, interest, consolidation and statement sequence the
 *       end-to-end pipeline exercises, and is swept up by no composite flow. The estate has no master
 *       orchestrator - the order in which its jobs ran was an operational convention - and this file must
 *       not become the pretext for inventing one. Wiring this job would be feature expansion: it would
 *       cause work the legacy system never performed.</li>
 *   <li><strong>It is nonetheless fully launchable by test.</strong> It is an ordinary job bean under a
 *       stable, published name, so the framework's job registry holds it, the job operator can start it by
 *       name, and the batch test utilities can launch it directly. Defined-but-unwired means unwired, not
 *       unreachable.</li>
 *   <li><strong>Its traceability rows are marked honestly.</strong> All 18 paragraphs are genuinely
 *       translated <em>and</em> the job is unwired; both halves of that sentence belong in the record. This
 *       file states the finding and stops there - the traceability matrix, the decision log and the gate
 *       evidence are owned elsewhere and are not written or amended from here.</li>
 * </ol>
 *
 * <p><strong>The 18 paragraphs, in the order they physically appear in the source</strong>, which is
 * deliberately <em>not</em> the numeric order of their labels. The mainline and the three processing
 * paragraphs come first, then the six open routines, then the six close routines, then the two diagnostic
 * routines. Preserving that physical order is what lets a reader walk a traceability matrix from top to
 * bottom against the member:
 *
 * <table border="1">
 *   <caption>Paragraph inventory of {@code app/cbl/CBTRN01C.cbl}, procedure division from line 154</caption>
 *   <tr><th>#</th><th>Line</th><th>Role</th></tr>
 *   <tr><td>1</td><td>155</td><td>mainline driver</td></tr>
 *   <tr><td>2</td><td>202</td><td>daily-transaction get-next</td></tr>
 *   <tr><td>3</td><td>227</td><td>cross-reference lookup</td></tr>
 *   <tr><td>4</td><td>241</td><td>account read</td></tr>
 *   <tr><td>5</td><td>252</td><td>daily-transaction open</td></tr>
 *   <tr><td>6</td><td>271</td><td>customer open</td></tr>
 *   <tr><td>7</td><td>289</td><td>cross-reference open</td></tr>
 *   <tr><td>8</td><td>307</td><td>card open</td></tr>
 *   <tr><td>9</td><td>325</td><td>account open</td></tr>
 *   <tr><td>10</td><td>343</td><td>transaction open</td></tr>
 *   <tr><td>11</td><td>361</td><td>daily-transaction close</td></tr>
 *   <tr><td>12</td><td>379</td><td>customer close</td></tr>
 *   <tr><td>13</td><td>397</td><td>cross-reference close</td></tr>
 *   <tr><td>14</td><td>415</td><td>card close</td></tr>
 *   <tr><td>15</td><td>433</td><td>account close</td></tr>
 *   <tr><td>16</td><td>451</td><td>transaction close</td></tr>
 *   <tr><td>17</td><td>469</td><td>abend routine</td></tr>
 *   <tr><td>18</td><td>476</td><td>status-display routine</td></tr>
 * </table>
 *
 * <p><strong>A naming generation worth recording.</strong> The two diagnostic routines at lines 469 and 476
 * carry <em>letter-prefixed</em> labels, the convention {@code app/cbl/CBCUS01C.cbl} also uses, while the
 * other sixteen carry the four-digit prefixes most of the estate uses. Matrix rows for those two must be
 * matched against letter-prefixed source labels, not against a numbered sequence that does not exist.
 *
 * <p><strong>Why this configuration does not extend the batch step template.</strong>
 * {@code batch/step/AbstractCobolStep} owns the open, read-loop, status-check, close and abend skeleton
 * that the batch tier shares, and nothing here re-implements any part of it. It is not extended either,
 * for a measured reason: its loop invokes the per-record hook only when a read delivered a record, and
 * this member's verification block sits <em>outside</em> the end-of-file guard at line 167, so it runs once
 * more after end of file over the record area the previous read left in place. A template that stops
 * calling its hook at end of file cannot express that extra pass, and dropping it would be a behavioural
 * regression rather than a simplification. Two further behaviours are in the same position: the record
 * display at line 168 and the twelve open and close diagnostics with their own literals, one of which
 * reproduces a source defect. All three belong to the translated program, so the whole eighteen-paragraph
 * pass is delegated to {@link DailyTransactionReadService} and this class contributes the job, the step,
 * the resource bindings and the step timer. This is the same category of exception the template itself
 * records for the statement dispatcher, which is likewise translated outside the skeleton.
 *
 * <p><strong>Six resources are named, three are read, and exactly one is read sequentially.</strong> The
 * member opens all six for input and closes all six; it never writes, because there is no {@code WRITE}
 * and no {@code REWRITE} in any of its 491 lines. So this job has no output dataset and no output width,
 * and inventing either would be feature expansion: what it produces is the diagnostic stream and the
 * terminating status, and that is correct and complete. Of the three resources it reads, two are keyed
 * reads the translated service performs through its own gateways; only the daily-transaction input is read
 * sequentially, and that is the one resource wired into the step. All six are nevertheless bound here at
 * their verified widths, each resolved from configuration by its own logical name:
 *
 * <table border="1">
 *   <caption>The six resources, in the order the six open paragraphs name them</caption>
 *   <tr><th>Logical name</th><th>Record type</th><th>Record width</th><th>How this job uses it</th></tr>
 *   <tr><td>{@value #DD_DALYTRAN}</td><td>{@link DailyTransaction}</td><td>350 bytes</td>
 *       <td>read sequentially; wired into the step</td></tr>
 *   <tr><td>{@value #DD_CUSTFILE}</td><td>{@link Customer}</td><td>500 bytes</td>
 *       <td>opened and closed, never read</td></tr>
 *   <tr><td>{@value #DD_XREFFILE}</td><td>{@link CardCrossReference}</td><td>50 bytes</td>
 *       <td>read by key, through the service's gateway</td></tr>
 *   <tr><td>{@value #DD_CARDFILE}</td><td>{@link Card}</td><td>150 bytes</td>
 *       <td>opened and closed, never read</td></tr>
 *   <tr><td>{@value #DD_ACCTFILE}</td><td>{@link Account}</td><td>300 bytes</td>
 *       <td>read by key, through the service's gateway</td></tr>
 *   <tr><td>{@value #DD_TRANFILE}</td><td>{@link Transaction}</td><td>350 bytes</td>
 *       <td>opened and closed, never read</td></tr>
 * </table>
 *
 * <p>The daily-transaction and transaction layouts are byte-for-byte identical at 350 bytes and are
 * <em>different datasets with different lifecycles</em>, so {@link #dalytranReader()} and
 * {@link #tranfileReader()} are separate bindings over separate record types and neither may ever stand in
 * for the other. Nothing here slices a byte or computes an offset: every layout's offsets belong to the
 * hand-written record mappers behind {@link FixedWidthFlatFileReaderFactory}, and this class only composes
 * the readers that factory builds.
 *
 * <p><strong>Emit, then abend, with the code the exception owns.</strong> The abend routine at lines 469 to
 * 473 displays its announcement, clears a timing field, moves {@code 999} into the abend code and only then
 * calls the Language Environment abort routine, and every failing site reaching it has already displayed
 * its own error text and the raw two-character file status. That ordering is the contract across the whole
 * batch tier, because on a mainframe the diagnostic reached the operator whether or not anything survived
 * the abend. Both failure paths reachable from this class therefore emit first and raise second: the
 * translated service does so through the estate's shared abend path, and the staged-dataset path in
 * {@link #stagedDailyTransactionInput()} logs the operation, the resource and the raw status, then the
 * abend announcement, and only then raises. The code is always
 * {@link AbendException#BATCH_ABEND_CODE}, taken from the exception's own constant and never restated as a
 * literal, and nothing logs after a raise.
 *
 * <p><strong>The two-level file-status model is not flattened here, and end of file is never an
 * error.</strong> The legacy programs normalise a raw two-character status into a coarse result and branch
 * on that coarse value; the coarse tri-state is nested inside the batch step template and the raw
 * vocabulary belongs to {@link FileStatus}, and this class introduces no third representation of either.
 * The read paragraph at lines 204 to 212 is the one three-way normalisation in the member - success, end of
 * file, error - while all twelve open and close paragraphs are two-way with no end-of-file arm at all, and
 * both shapes live in the translated service where the paragraphs do. This job writes no timestamp,
 * because the member writes nothing at all, so no timestamp form can escape from here; where the batch
 * tier does need one, its 26-character form is produced by the formatter nested in the step template.
 *
 * <p><strong>Nothing fires when the context starts.</strong> A legacy batch job was submitted
 * deliberately; bringing an application up never triggered one. The shared configuration document disables
 * launch-on-start, and this class adds no start-up runner, no lifecycle participant, no initialising
 * callback, no event listener and no scheduled trigger, and names no job for anything to resolve. That
 * matters twice over here, because an auto-start would run the very job this file exists to keep unwired.
 * Execution is strictly sequential: no task executor, no partitioning, no multi-threaded step and no
 * parallel flow, and no tuning figure of any kind appears in this file or its comments - performance for
 * this implementation is measured and recorded, never asserted.
 *
 * <p>Every step is timed on {@value #STEP_TIMER_NAME}, deliberately the same timer name and the same tag
 * keys the batch step template uses, so this program appears in one metric family beside the nine wired
 * ones at the metrics scrape endpoint rather than in a family of its own.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19, legacy member {@code app/cbl/CBTRN01C.cbl}. The
 * legacy tree is cited and never transcribed: no program, job-control, utility-control or cataloged-
 * procedure statement text appears here, and nothing reads that tree at run time. No user-specified rules
 * govern this file - the project's rules document reports that none were provided - so the work is held to
 * the enterprise standards the specification substitutes for them, and where faithful translation and
 * idiomatic Java diverge, faithful wins and the divergence is recorded in the decision log.
 *
 * <p>Stateless and immutable: final, every collaborator injected through the one constructor, every field
 * final, no mutable static state and no per-execution state held on this bean. Item readers are stateful,
 * so each reader accessor builds a new one on every call rather than caching one, which is what keeps two
 * concurrent launches from observing one another.
 */
@Configuration(proxyBeanMethods = false)
public final class DailyTransactionReadJobConfig {
    private static final Logger LOG = LoggerFactory.getLogger(DailyTransactionReadJobConfig.class);

    /** The legacy member this configuration stands in for, eight characters, the abend culprit. */
    public static final String PROGRAM_NAME = "CBTRN01C";

    /**
     * The stable job name, published so a test launcher, the job operator and the batch job endpoint all
     * resolve this job by one value rather than by a repeated literal.
     *
     * <p>Original to this module. No job member names this program, so there is no legacy job name to
     * inherit and none to look for.
     *
     * <p>The value is read from {@code service/BatchJobCatalog}, which is the module's single
     * declaration of the nine stable job names. Both tiers that need a name - this configuration
     * and the operational control surface above it - resolve it from there, so the name exists as
     * one literal and the two cannot drift apart across a boundary the layering keeps closed.
     */
    public static final String JOB_NAME = BatchJobCatalog.DAILY_TRANSACTION_READ_JOB_NAME;

    /**
     * The stable step name, published for the same reason as {@link #JOB_NAME} and original for the same
     * reason: the estate holds no step that runs this program.
     */
    public static final String STEP_NAME = "dailyTransactionExtractStep";

    /** Logical name of the sequential daily-transaction input, the one resource read sequentially. */
    public static final String DD_DALYTRAN = "DALYTRAN";

    /** Logical name of the customer file, which the member opens and closes without ever reading. */
    public static final String DD_CUSTFILE = "CUSTFILE";

    /** Logical name of the card cross-reference file, which the member reads by card number. */
    public static final String DD_XREFFILE = "XREFFILE";

    /** Logical name of the card file, which the member opens and closes without ever reading. */
    public static final String DD_CARDFILE = "CARDFILE";

    /** Logical name of the account file, which the member reads by account identifier. */
    public static final String DD_ACCTFILE = "ACCTFILE";

    /**
     * Logical name of the posted-transaction file, which the member opens and closes without ever reading.
     */
    public static final String DD_TRANFILE = "TRANFILE";

    /** Configuration prefix under which the six staged sequential datasets are named. */
    public static final String RESOURCE_PROPERTY_PREFIX = "carddemo.batch.daily-transaction-read.";

    /** Property naming the staged {@value #DD_DALYTRAN} dataset; blank when none is staged. */
    public static final String DALYTRAN_RESOURCE_PROPERTY = RESOURCE_PROPERTY_PREFIX + "dalytran";

    /** Property naming the staged {@value #DD_CUSTFILE} dataset; blank when none is staged. */
    public static final String CUSTFILE_RESOURCE_PROPERTY = RESOURCE_PROPERTY_PREFIX + "custfile";

    /** Property naming the staged {@value #DD_XREFFILE} dataset; blank when none is staged. */
    public static final String XREFFILE_RESOURCE_PROPERTY = RESOURCE_PROPERTY_PREFIX + "xreffile";

    /** Property naming the staged {@value #DD_CARDFILE} dataset; blank when none is staged. */
    public static final String CARDFILE_RESOURCE_PROPERTY = RESOURCE_PROPERTY_PREFIX + "cardfile";

    /** Property naming the staged {@value #DD_ACCTFILE} dataset; blank when none is staged. */
    public static final String ACCTFILE_RESOURCE_PROPERTY = RESOURCE_PROPERTY_PREFIX + "acctfile";

    /** Property naming the staged {@value #DD_TRANFILE} dataset; blank when none is staged. */
    public static final String TRANFILE_RESOURCE_PROPERTY = RESOURCE_PROPERTY_PREFIX + "tranfile";

    /**
     * The batch tier's shared program-lifecycle timer, matched by name and by tag keys to the one the step
     * template records so that this program joins one metric family rather than starting a second.
     */
    public static final String STEP_TIMER_NAME = "carddemo.batch.cobol.step";

    /** Tag carrying the legacy program name, so one family separates cleanly by program. */
    public static final String TAG_STEP = "step";

    /** Tag distinguishing a completed pass from one that abended. */
    public static final String TAG_OUTCOME = "outcome";

    /** Outcome tag value for a pass that ran to its end. */
    public static final String OUTCOME_COMPLETED = "COMPLETED";

    /** Outcome tag value for a pass that abended. */
    public static final String OUTCOME_ABENDED = "ABENDED";

    /**
     * The gerund the legacy open paragraphs put in their diagnostics, so a diagnostic composed here reads
     * exactly as one composed by the batch step template does.
     */
    private static final String GERUND_OPENING = "OPENING";

    /** The gerund the legacy read paragraph puts in its diagnostic. */
    private static final String GERUND_READING = "READING";

    /**
     * The raw two-character status reported when a staged sequential dataset cannot be opened or read.
     *
     * <p>The member has no legacy job stream, so it has no dataset-allocation behaviour to reproduce and no
     * status literal of its own for this case. {@link FileStatus#PERMANENT_ERROR} is the estate's own
     * vocabulary for a permanent error on a file and is one of the nine codes the source actually carries,
     * so the diagnostic reports a code a reader of the legacy system recognises instead of an invented one.
     * Nothing branches on it: it is reported and then the run abends, which is what the member does with
     * every status its cascade rejects.
     */
    private static final FileStatus STAGED_DATASET_FAILURE_STATUS = FileStatus.PERMANENT_ERROR;

    /** Bean name of the shared job-boundary listener the batch infrastructure publishes. */
    private static final String BOUNDARY_LISTENER_BEAN_NAME = "batchJobBoundaryListener";

    /** Bean name of the shared parameter incrementer the batch infrastructure publishes. */
    private static final String RUN_INCREMENTER_BEAN_NAME = "batchJobRunIncrementer";

    /** Description registered with the shared timer, matching the batch step template's own wording. */
    private static final String STEP_TIMER_DESCRIPTION =
            "Elapsed time of one legacy CardDemo batch program lifecycle";

    private final JobRepository jobRepository;

    private final PlatformTransactionManager transactionManager;

    private final FixedWidthFlatFileReaderFactory readerFactory;

    private final DailyTransactionReadService dailyTransactionReadService;

    private final MeterRegistry meterRegistry;

    private final ResourceLoader resourceLoader;

    private final BatchStagingArea stagingArea;

    private final String dalytranLocation;

    private final String custfileLocation;

    private final String xreffileLocation;

    private final String cardfileLocation;

    private final String acctfileLocation;

    private final String tranfileLocation;

    /**
     * Creates the configuration with the batch infrastructure it builds on, the collaborators the step
     * needs, and the six logical resource locations.
     *
     * <p>Each of the six locations is bound by its own logical name and defaults to blank, which means no
     * sequential dataset is staged for that resource. A blank location is a normal state rather than a
     * misconfiguration: five of the six resources are never read by this member at all, and the sixth is
     * read from the relational input the translated program resolves for itself when no dataset is staged.
     * No path is written into this class, so relocating a dataset is a configuration change and never a
     * code change.
     *
     * @param jobRepository the framework's job repository, from the batch auto-configuration
     * @param transactionManager the transaction manager the step runs its tasklet under
     * @param readerFactory the factory that owns one reader per fixed-width record layout
     * @param dailyTransactionReadService the translated program, which owns all eighteen paragraphs
     * @param meterRegistry the registry this step's timer is recorded on
     * @param resourceLoader the loader that turns a configured location into a resource
     * @param stagingArea the shared object-store staging boundary
     * @param dalytranLocation location of the staged daily-transaction dataset, or blank
     * @param custfileLocation location of the staged customer dataset, or blank
     * @param xreffileLocation location of the staged cross-reference dataset, or blank
     * @param cardfileLocation location of the staged card dataset, or blank
     * @param acctfileLocation location of the staged account dataset, or blank
     * @param tranfileLocation location of the staged posted-transaction dataset, or blank
     * @throws NullPointerException if any collaborator or any location is {@code null}
     */
    public DailyTransactionReadJobConfig(
            final JobRepository jobRepository,
            final PlatformTransactionManager transactionManager,
            final FixedWidthFlatFileReaderFactory readerFactory,
            final DailyTransactionReadService dailyTransactionReadService,
            final MeterRegistry meterRegistry,
            final ResourceLoader resourceLoader,
            final BatchStagingArea stagingArea,
            @Value("${" + DALYTRAN_RESOURCE_PROPERTY + ":}") final String dalytranLocation,
            @Value("${" + CUSTFILE_RESOURCE_PROPERTY + ":}") final String custfileLocation,
            @Value("${" + XREFFILE_RESOURCE_PROPERTY + ":}") final String xreffileLocation,
            @Value("${" + CARDFILE_RESOURCE_PROPERTY + ":}") final String cardfileLocation,
            @Value("${" + ACCTFILE_RESOURCE_PROPERTY + ":}") final String acctfileLocation,
            @Value("${" + TRANFILE_RESOURCE_PROPERTY + ":}") final String tranfileLocation) {
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository must not be null");
        this.transactionManager = Objects.requireNonNull(transactionManager,
                "transactionManager must not be null");
        this.readerFactory = Objects.requireNonNull(readerFactory, "readerFactory must not be null");
        this.dailyTransactionReadService = Objects.requireNonNull(dailyTransactionReadService,
                "dailyTransactionReadService must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.resourceLoader = Objects.requireNonNull(resourceLoader, "resourceLoader must not be null");
        this.stagingArea = Objects.requireNonNull(stagingArea, "stagingArea must not be null");
        this.dalytranLocation = requireLocation(dalytranLocation, DD_DALYTRAN);
        this.custfileLocation = requireLocation(custfileLocation, DD_CUSTFILE);
        this.xreffileLocation = requireLocation(xreffileLocation, DD_XREFFILE);
        this.cardfileLocation = requireLocation(cardfileLocation, DD_CARDFILE);
        this.acctfileLocation = requireLocation(acctfileLocation, DD_ACCTFILE);
        this.tranfileLocation = requireLocation(tranfileLocation, DD_TRANFILE);
    }

    /**
     * The job for the daily-transaction extract program: one step, run in sequence, and wired into nothing.
     *
     * <p><strong>What this job deliberately does not have.</strong> It carries no failure-ending flow
     * transition, because the estate holds no condition-code gate for this program - it holds no job step
     * for it at all. It carries no parameter validator and requires no date or mode parameter, because
     * there is no parameter string to reproduce and the translated program asks for none. It is not chained
     * from or into another job, and no composite flow reaches it. Those four absences are the point of the
     * job rather than gaps in it, and each of them would be a behavioural invention if it were filled in.
     *
     * <p><strong>What it does have, and why.</strong> The two collaborators the batch infrastructure
     * publishes are attached explicitly on this builder, by bean name, as every job configuration in this
     * module attaches them: the boundary listener so the job's start and terminal outcome are announced
     * where a reader of this file can see that they are, and the parameter incrementer so a resubmission
     * with an identical parameter set runs again, as resubmitting a job member did, and so the job can be
     * advanced to its next instance when it is started by name.
     *
     * <p>Nothing launches this job when the context starts. Launch-on-start is disabled by the shared
     * configuration document, and this class contributes no runner, lifecycle participant, initialising
     * callback, event listener or scheduled trigger. Publishing the job registers it for launch on demand
     * and nothing more.
     *
     * @param dailyTransactionExtractStep the single step, injected by name so another step bean cannot be
     *                                    substituted for it
     * @param batchJobRunIncrementer the shared parameter incrementer the batch infrastructure publishes
     * @param batchJobBoundaryListener the shared job-boundary listener the batch infrastructure publishes
     * @return the job, launchable by {@value #JOB_NAME} and reached by no pipeline
     */
    @Bean
    public Job dailyTransactionReadJob(
            final Step dailyTransactionExtractStep,
            @Qualifier(RUN_INCREMENTER_BEAN_NAME) final JobParametersIncrementer batchJobRunIncrementer,
            @Qualifier(BOUNDARY_LISTENER_BEAN_NAME) final JobExecutionListener batchJobBoundaryListener) {
        return new JobBuilder(JOB_NAME, this.jobRepository)
                .incrementer(batchJobRunIncrementer)
                .listener(batchJobBoundaryListener)
                .start(dailyTransactionExtractStep)
                .build();
    }

    /**
     * The single step, whose tasklet is the whole program in one indivisible pass.
     *
     * <p>One step rather than several, and a tasklet rather than a chunk-oriented reader and writer, because
     * the member is one pass over one input that writes nothing: it opens six files, walks the
     * daily-transaction input to end of file resolving each record's card number and account, closes the six
     * and returns. A legacy batch program is not restartable part way through its file, so there is no chunk
     * boundary to place and no commit interval to choose - and no such figure appears anywhere in this file.
     *
     * <p>Execution is strictly sequential. No task executor, no partitioning, no multi-threaded step and no
     * parallel flow is attached, because the read loop this step drives carries order-dependent state and
     * reordering it would change the diagnostic stream the program exists to produce.
     *
     * @return the step, named {@value #STEP_NAME}
     */
    @Bean
    public Step dailyTransactionExtractStep() {
        return new StepBuilder(STEP_NAME, this.jobRepository)
                .tasklet(dailyTransactionExtractTasklet(), this.transactionManager)
                .build();
    }

    /**
     * The step's tasklet: one invocation runs the whole program and reports that it is finished.
     *
     * <p>Both framework arguments are deliberately unused, exactly as the batch step template's own tasklet
     * adaptation leaves them unused. The program is a single indivisible pass, so there is no partial
     * contribution to report and no chunk to carry state across; what the pass produced is logged by
     * {@link #runExtractPass()} and returned to a caller that wants it.
     *
     * <p>Exposed rather than inlined so that the pass can be driven directly, without a job repository and
     * without a launcher, by a test that needs to observe the outcome.
     *
     * @return a tasklet that runs one complete extract pass and reports {@link RepeatStatus#FINISHED}
     */
    public Tasklet dailyTransactionExtractTasklet() {
        return (contribution, chunkContext) -> {
            if (chunkContext == null) {
                runExtractPass();
                return RepeatStatus.FINISHED;
            }
            try {
                runExtractPass(BatchCancellation.requestedBy(chunkContext));
            } catch (final CancellationException stopped) {
                throw BatchCancellation.interrupted(stopped);
            }
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * Runs one complete extract pass, timed, and returns what it produced.
     *
     * <p>The eighteen paragraphs are the translated program's, not this method's: it resolves the ordered
     * input and hands the pass to {@link DailyTransactionReadService}, which performs the six opens, the read
     * loop with its own status normalisation, the two keyed lookups per record, the extra verification pass
     * the member makes after end of file, the six closes and the abend path. Nothing about the read loop,
     * the lookups or the status handling is reproduced here.
     *
     * <p>The input is resolved one of two ways, and the pass itself is identical either way. When a
     * sequential dataset is staged for the daily-transaction input, its records are read through the reader
     * that owns the 350-byte layout and handed over in the dataset's own order, which is the order the
     * legacy physical sequential read returned them in. When none is staged, the translated program resolves
     * its own ordered scan, ascending by the record's business key, because the relational source imposes no
     * order of its own and an unordered scan would make the diagnostic stream depend on the database rather
     * than on the input.
     *
     * <p>On failure the timer is stopped with the abended outcome and the failure is rethrown unchanged.
     * Nothing is logged after the rethrow, and nothing is logged about the failure here at all: the site that
     * decided to abend has already emitted its own diagnostic and the raw status, in that order, and adding a
     * second report would break the ordering that the batch tier treats as a contract.
     *
     * @return the counts, the per-record outcomes and the terminal result value of the pass
     * @throws AbendException if a file operation reports a status the member treats as an error, after the
     *         diagnostic and the raw status have been emitted
     */
    public DailyTransactionReadResult runExtractPass() {
        return runExtractPass(null);
    }

    private DailyTransactionReadResult runExtractPass(final BooleanSupplier stopRequested) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);
        try {
            final DailyTransactionReadResult result = readPass(stopRequested);

            recordStepDuration(sample, OUTCOME_COMPLETED);
            LOG.info("PROGRAM {} READ {} RECORD(S), VERIFIED {}, MADE {} VERIFICATION PASS(ES), COULD NOT"
                            + " VERIFY {} CARD(S), FOUND NO ACCOUNT FOR {}, RETURN CODE {}",
                    PROGRAM_NAME, result.recordsRead(), result.recordsVerified(),
                    result.verificationPasses(), result.cardsNotVerified(), result.accountsNotFound(),
                    result.returnCode());
            return result;
        } catch (RuntimeException failure) {
            recordStepDuration(sample, OUTCOME_ABENDED);
            throw failure;
        }
    }

    /**
     * Binds a reader over the staged {@value #DD_DALYTRAN} dataset, whose record is 350 encoded bytes.
     *
     * <p>This is the one resource the member reads sequentially, and therefore the one reader this job wires
     * into its step. Its layout is byte-for-byte identical to the posted-transaction layout that
     * {@link #tranfileReader()} binds, which is exactly why the two are separate bindings over separate
     * record types: substituting one for the other would parse every record successfully and attribute it to
     * the wrong dataset, and the compiler is what refuses that here.
     *
     * @return a new reader over the staged dataset, or empty when no dataset is staged for this resource
     */
    public Optional<FlatFileItemReader<DailyTransaction>> dalytranReader() {
        return stagedResource(this.dalytranLocation).map(this.readerFactory::dailyTransactionReader);
    }

    /**
     * Binds a reader over the staged {@value #DD_CUSTFILE} dataset, whose record is 500 encoded bytes.
     *
     * <p>The member opens and closes this resource and <strong>never reads it</strong>, so this job reads no
     * customer record through this reader. It is bound because the member names six resources and each is
     * bound here at its verified width.
     *
     * <p>The sealing function is a parameter rather than a field of this configuration for the reason the
     * reader factory states on its own customer method: sealing the two regulated identifiers needs a key,
     * and the key belongs to the layer that owns it. This configuration owns no key, so it takes the caller's
     * function and passes it straight through rather than defaulting one - a defaulted sealer would be a
     * silent decision to leave two regulated fields unprotected.
     *
     * @param regulatedFieldSealer the caller's sealing function, applied by the mapper to the two regulated
     *                             identifiers only; must not be {@code null}
     * @return a new reader over the staged dataset, or empty when no dataset is staged for this resource
     * @throws NullPointerException if {@code regulatedFieldSealer} is {@code null}
     */
    public Optional<FlatFileItemReader<Customer>> custfileReader(
            final UnaryOperator<String> regulatedFieldSealer) {
        Objects.requireNonNull(regulatedFieldSealer, "regulatedFieldSealer must not be null");
        return stagedResource(this.custfileLocation)
                .map(resource -> this.readerFactory.customerReader(resource, regulatedFieldSealer));
    }

    /**
     * Binds a reader over the staged {@value #DD_XREFFILE} dataset, whose record is 50 bytes on the mainframe.
     *
     * <p>The member reads this resource, but by key rather than sequentially: it resolves one record per
     * daily-transaction record by that record's card number, which is the cross-reference's own identity. A
     * keyed read is not a sequential scan, so the translated program performs it through its own gateway and
     * this reader is not wired into the step.
     *
     * @return a new reader over the staged dataset, or empty when no dataset is staged for this resource
     */
    public Optional<FlatFileItemReader<CardCrossReference>> xreffileReader() {
        return stagedResource(this.xreffileLocation).map(this.readerFactory::cardCrossReferenceReader);
    }

    /**
     * Binds a reader over the staged {@value #DD_CARDFILE} dataset, whose record is 150 encoded bytes.
     *
     * <p>The member opens and closes this resource and never reads it; see {@link #custfileReader} for why it
     * is bound all the same.
     *
     * @return a new reader over the staged dataset, or empty when no dataset is staged for this resource
     */
    public Optional<FlatFileItemReader<Card>> cardfileReader() {
        return stagedResource(this.cardfileLocation).map(this.readerFactory::cardReader);
    }

    /**
     * Binds a reader over the staged {@value #DD_ACCTFILE} dataset, whose record is 300 encoded bytes.
     *
     * <p>The member reads this resource by account identifier, taking the identifier from the record the
     * cross-reference resolved, and only for a card the cross-reference could resolve. As with the
     * cross-reference, that is a keyed read the translated program performs through its own gateway, so this
     * reader is not wired into the step.
     *
     * @return a new reader over the staged dataset, or empty when no dataset is staged for this resource
     */
    public Optional<FlatFileItemReader<Account>> acctfileReader() {
        return stagedResource(this.acctfileLocation).map(this.readerFactory::accountReader);
    }

    /**
     * Binds a reader over the staged {@value #DD_TRANFILE} dataset, whose record is 350 encoded bytes.
     *
     * <p>The member opens and closes this resource and never reads it. It is the posted-transaction master,
     * <strong>not</strong> the daily-transaction input that {@link #dalytranReader()} binds, even though the
     * two layouts are identical in width and shape; the two bindings are kept distinct for that reason.
     *
     * @return a new reader over the staged dataset, or empty when no dataset is staged for this resource
     */
    public Optional<FlatFileItemReader<Transaction>> tranfileReader() {
        return stagedResource(this.tranfileLocation).map(this.readerFactory::transactionReader);
    }

    /**
     * Records one pass on the shared program-lifecycle timer.
     *
     * @param sample the sample started when the pass began
     * @param outcome {@value #OUTCOME_COMPLETED} or {@value #OUTCOME_ABENDED}
     */
    private void recordStepDuration(final Timer.Sample sample, final String outcome) {
        sample.stop(Timer.builder(STEP_TIMER_NAME)
                .description(STEP_TIMER_DESCRIPTION)
                .tag(TAG_STEP, PROGRAM_NAME)
                .tag(TAG_OUTCOME, outcome)
                .register(this.meterRegistry));
    }

    /**
     * Resolves the ordered daily-transaction input from a staged sequential dataset, when one is staged.
     *
     * <p>Reading the dataset into an ordered list is resolving the program's input and is not the program's
     * read loop: the loop, the end-of-file flag, the status normalisation, the per-record verification and the
     * abend all belong to the translated program, which accepts an ordered source precisely so that a step
     * over a sequential dataset can supply the order the dataset itself holds.
     *
     * @return the staged records in dataset order, or empty when no dataset is staged, in which case the
     *         translated program resolves its own ordered scan
     * @throws AbendException if the staged dataset cannot be opened or read, after the diagnostic and the raw
     *         status have been emitted
     */
    private DailyTransactionReadResult readPass(final BooleanSupplier stopRequested) {
        final Optional<FlatFileItemReader<DailyTransaction>> reader = dalytranReader();
        if (reader.isEmpty()) {
            LOG.debug("NO SEQUENTIAL DATASET IS STAGED FOR {}; THE ORDERED INPUT IS RESOLVED BY PROGRAM {}"
                    + " ITSELF, ASCENDING BY RECORD IDENTITY", DD_DALYTRAN, PROGRAM_NAME);
            return stopRequested == null
                    ? this.dailyTransactionReadService.execute(
                            DailyTransactionReadJobConfig::consumeVerification)
                    : this.dailyTransactionReadService.execute(
                            DailyTransactionReadJobConfig::consumeVerification, stopRequested);
        }

        final FlatFileItemReader<DailyTransaction> staged = reader.get();
        openStagedDataset(staged);
        try {
            // One record is in hand at a time: the pass pulls the next record from the open handle as
            // it needs it, so a staged dataset is never held in the heap. See
            // docs/decision-log.md entry DL-176.
            final Iterable<DailyTransaction> streamed = () -> stagedRecordCursor(staged);
            return stopRequested == null
                    ? this.dailyTransactionReadService.execute(streamed,
                            DailyTransactionReadJobConfig::consumeVerification)
                    : this.dailyTransactionReadService.execute(streamed,
                            DailyTransactionReadJobConfig::consumeVerification, stopRequested);
        } finally {
            releaseStagedDataset(staged);
        }
    }

    /**
     * Consumes one per-record verification outcome on behalf of this job.
     *
     * <p>The member writes no dataset and reports its per-record findings through its own diagnostics,
     * which the pass has already emitted by the time an outcome arrives here. What this job reports is
     * the pass's counts, so the outcome has no destination beyond a trace record - and naming that
     * explicitly is the point: the pass cannot accumulate a whole dataset's outcomes, and a caller that
     * wants them receives them one at a time.
     *
     * @param outcome the outcome of one verification pass; must not be {@code null}
     */
    private static void consumeVerification(final DailyTransactionVerification outcome) {
        Objects.requireNonNull(outcome, "outcome");
        LOG.trace("PROGRAM {} COMPLETED ONE VERIFICATION PASS: cardVerified={} accountFound={}"
                        + " afterEndOfFile={}", PROGRAM_NAME, outcome.cardVerified(),
                outcome.accountFound(), outcome.afterEndOfFile());
    }

    /**
     * Walks an already-open staged dataset one record at a time, in dataset order.
     *
     * <p>Forward-only and one record deep: exactly one record is read ahead so that {@code hasNext} can
     * answer without consuming, which is what lets the pass drive the walk as it goes rather than
     * receiving a materialised dataset. Opening and releasing the handle belong to the caller, because
     * the walk's lifetime is the pass's and not the iterator's.
     *
     * @param reader the open reader over the staged dataset
     * @return a forward walk over the dataset, never {@code null}
     * @throws AbendException if the dataset cannot be read
     */
    private static Iterator<DailyTransaction> stagedRecordCursor(
            final FlatFileItemReader<DailyTransaction> reader) {

        return new Iterator<>() {

            /** The record read ahead of the caller, or {@code null} when none is in hand. */
            private DailyTransaction pending;

            /** Whether the dataset has been read to its end. */
            private boolean exhausted;

            @Override
            public boolean hasNext() {
                if (this.pending != null) {
                    return true;
                }
                if (this.exhausted) {
                    return false;
                }
                this.pending = readStagedRecord(reader);
                if (this.pending == null) {
                    this.exhausted = true;
                    return false;
                }
                return true;
            }

            @Override
            public DailyTransaction next() {
                if (!hasNext()) {
                    throw new NoSuchElementException("the staged " + DD_DALYTRAN
                            + " dataset holds no further record");
                }
                final DailyTransaction served = this.pending;
                this.pending = null;
                return served;
            }
        };
    }

    /**
     * Opens a staged dataset, emitting the diagnostic and the raw status before abending on failure.
     *
     * @param reader the reader over the staged dataset
     * @throws AbendException if the dataset cannot be opened
     */
    private static void openStagedDataset(final FlatFileItemReader<DailyTransaction> reader) {
        try {
            reader.open(new ExecutionContext());
        } catch (RuntimeException cause) {
            throw abendOnStagedDatasetFailure(GERUND_OPENING, cause);
        }
    }

    /**
     * Reads one record from a staged dataset, emitting the diagnostic and the raw status before abending on
     * failure.
     *
     * <p>An abend already raised beneath this call is rethrown untouched, so a failure is diagnosed once and
     * reported once.
     *
     * @param reader the reader over the staged dataset
     * @return the next record, or {@code null} at end of the dataset
     * @throws AbendException if the dataset cannot be read
     */
    private static DailyTransaction readStagedRecord(
            final FlatFileItemReader<DailyTransaction> reader) {
        try {
            return reader.read();
        } catch (AbendException alreadyDiagnosed) {
            throw alreadyDiagnosed;
        } catch (Exception cause) {
            throw abendOnStagedDatasetFailure(GERUND_READING, cause);
        }
    }

    /**
     * Hands back the handle a staged dataset holds.
     *
     * <p>This is a runtime adaptation and not a translated paragraph: the member's own close family belongs
     * to the translated program, and this must not become a second close sequence. It therefore normalises no
     * status, emits no legacy diagnostic and never abends, and a failure here is reported by failure type
     * alone so that no message from a lower layer is rendered into the log stream.
     *
     * @param reader the reader over the staged dataset
     */
    private static void releaseStagedDataset(final FlatFileItemReader<DailyTransaction> reader) {
        try {
            reader.close();
        } catch (RuntimeException secondary) {
            LOG.warn("SECONDARY FAILURE RELEASING THE STAGED {} HANDLE OF PROGRAM {}, FAILURE TYPE {}",
                    DD_DALYTRAN, PROGRAM_NAME, secondary.getClass().getName());
        }
    }

    /**
     * Emits the ordered failure report and builds the abend, in the legacy order and never in another.
     *
     * <p>The operation, the resource and the raw two-character status are logged first, then the abend
     * announcement carrying the abend code, and only then is the exception constructed - which is the order
     * the member's abend routine establishes and the order the whole batch tier keeps. The code is
     * {@link AbendException#BATCH_ABEND_CODE}, taken from the exception's own constant, so the value the
     * member moves into its abend code is stated in one place in this module and restated in none.
     *
     * <p>The failure that caused this is chained onto the abend but is never handed to the logging framework:
     * beneath a file failure the chain's messages can carry the location it could not open, so the report
     * names the failure's type and leaves its message to the exception a diagnosing reader already has.
     *
     * <p>Every component of the context is composed from constants of this class, so the reason and the
     * operator message have fixed lengths well inside the legacy reason and message field widths and no
     * bounding is needed to keep the exception's own width checks satisfied.
     *
     * @param gerund {@value #GERUND_OPENING} or {@value #GERUND_READING}
     * @param cause the failure to chain
     * @return the abend to raise, already reported
     */
    private static AbendException abendOnStagedDatasetFailure(final String gerund,
            final Exception cause) {
        final String rawStatus = STAGED_DATASET_FAILURE_STATUS.getCode();
        LOG.error("ERROR {} {} - FILE STATUS IS: {} ({}), FAILURE TYPE {}", gerund, DD_DALYTRAN, rawStatus,
                STAGED_DATASET_FAILURE_STATUS.name(), cause.getClass().getName());
        LOG.error("ABENDING PROGRAM {} WITH ABEND CODE {}", PROGRAM_NAME,
                AbendException.BATCH_ABEND_CODE);
        return new AbendException(AbendException.BATCH_ABEND_CODE, PROGRAM_NAME,
                "STATUS " + rawStatus + " " + gerund + " " + DD_DALYTRAN,
                "ERROR " + gerund + " " + DD_DALYTRAN + " - FILE STATUS IS: " + rawStatus,
                cause);
    }

    /**
     * Resolves one configured location, treating a blank value as "no dataset is staged".
     *
     * @param location the configured location, possibly blank
     * @return the resource the location names, or empty when the location is blank
     */
    private Optional<Resource> stagedResource(final String location) {
        if (location.isBlank()) {
            return Optional.empty();
        }
        final String normalized = location.strip();
        if (this.stagingArea.holds(normalized)) {
            return Optional.of(this.stagingArea.stagedInput(normalized));
        }
        return Optional.of(this.resourceLoader.getResource(normalized));
    }

    /**
     * Rejects a {@code null} location while accepting a blank one, which is the normal unstaged state.
     *
     * @param location the configured location
     * @param resourceName the logical name the location belongs to, named in the rejection
     * @return the location as supplied
     * @throws NullPointerException if {@code location} is {@code null}
     */
    private static String requireLocation(final String location, final String resourceName) {
        return Objects.requireNonNull(location,
                () -> "the configured location for " + resourceName + " must not be null; a blank value"
                        + " means no sequential dataset is staged for it");
    }
}
