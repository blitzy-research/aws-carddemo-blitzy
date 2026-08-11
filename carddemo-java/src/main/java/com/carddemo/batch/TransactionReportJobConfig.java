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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.PathResource;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.PlatformTransactionManager;

import com.carddemo.batch.step.AbstractCobolStep;
import com.carddemo.batch.step.FixedWidthFlatFileReaderFactory;
import com.carddemo.batch.step.StagedGenerationStore;
import com.carddemo.batch.step.TransactionReportProcessor;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.repository.TransactionScanRepository;
import com.carddemo.service.BatchJobCatalog;
import com.carddemo.service.ReportTransactionInput;
import com.carddemo.service.ReportTransactionSource;
import com.carddemo.service.TransactionReportService;
import com.carddemo.service.TransactionReportService.TransactionReportResult;
import com.carddemo.util.BoundedKeysetIterator;
import com.carddemo.util.ExternalStringSorter;
import com.carddemo.util.FixedWidthFieldReader;
import com.carddemo.util.ReportLineFormatter;
import com.carddemo.util.SecureStagedFiles;
import com.carddemo.util.StagedResourceNames;
import com.carddemo.util.TransactionRecordMapper;
import com.carddemo.util.ZonedDecimalCodec;

/**
 * Declares the date-windowed transaction detail report job: unload the transaction master, filter and
 * order the unloaded generation, then emit the fixed-width report.
 *
 * <h2>The measured job stream: three steps, and not one condition-code gate</h2>
 *
 * <p>The job member declares a procedure-library reference and then exactly three steps:
 *
 * <ol>
 *   <li>the cataloged unload wrapper, which copies the transaction master to a new sequential backup
 *       generation whose declared record length is {@value #UNLOAD_RECORD_LENGTH} bytes, fixed-length
 *       blocked - reproduced by {@link #UNLOAD_STEP_NAME};</li>
 *   <li>the external sort, which filters the unloaded generation by the processing-date window and
 *       orders what survives by card number - reproduced by {@link #FILTER_AND_ORDER_STEP_NAME};</li>
 *   <li>the report program, which emits the report at {@value #REPORT_RECORD_LENGTH} bytes per
 *       record, fixed-length blocked - reproduced by {@link #EMIT_STEP_NAME}.</li>
 * </ol>
 *
 * <p><strong>The member carries no condition-code dependency on any step, so this job carries no
 * failure-ending transition.</strong> The only condition-code construct anywhere in the member is the
 * sort utility's record-inclusion predicate, which decides which <em>records</em> survive and is not a
 * step gate. Wiring a gate here by analogy with the statement job would either skip a step the legacy
 * stream runs unconditionally or end the job cleanly where the legacy stream would have failed it, and
 * both are behavioural changes dressed as robustness. Ordinary framework failure semantics apply: a
 * failed step stops the job.
 *
 * <h2>Source defects recorded here, reproduced nowhere - raised for the decision log</h2>
 *
 * <ul>
 *   <li><strong>Duplicate step name.</strong> Two different steps of the job member are both named
 *       {@code STEP05R} - the unload at line 23 and the sort at line 37. Two Spring Batch steps may
 *       not share a name, and reproducing the collision would make either step unaddressable, so
 *       three distinct stable names are generated. The diagnostics of the two program lifecycles
 *       disambiguate them by preferring the <em>cataloged</em> form's naming, described next.</li>
 *   <li><strong>The cataloged form does not carry the collision.</strong> The procedure member names
 *       its three steps {@code STEP01R}, {@code STEP05R} and {@code STEP10R}. The duplication was
 *       introduced in the job member by renaming the first step, so the procedure's naming is the
 *       unambiguous one and is what {@link #LEGACY_CATALOGED_UNLOAD_STEP} carries.</li>
 *   <li><strong>Procedure-name collision.</strong> The procedure member declares its own internal
 *       procedure label as {@code REPROC}, which is also the internal label of the unload wrapper
 *       member. Two members of one procedure library therefore claim one name. The member name is
 *       what resolves an invocation, so the internal label is dead text; nothing here depends on
 *       it.</li>
 *   <li><strong>Comment-banner corruption.</strong> The job member's banner above the first step
 *       carries a stray backtick inside its rule of asterisks. Cosmetic, recorded, never
 *       reproduced.</li>
 *   <li><strong>The report generation base is double-declared with divergent attributes.</strong> One
 *       member declares it with a retention limit of five and a scratch-on-roll-off attribute; a second
 *       member re-declares the same base with a limit of ten and <em>no</em> scratch attribute. Both
 *       divergences are recorded and the conflict is resolved to the later and more specific
 *       declaration, which is {@link StagedGenerationStore#REPORT_RETENTION_LIMIT}. Neither figure is
 *       declared here and no retention logic runs here: the store owns both depths and applies them, so
 *       this file names the store's constant at the point it registers a generation and carries no
 *       retention figure of its own.</li>
 *   <li><strong>Mislabelled re-declaring member.</strong> That second member's banner claims to delete
 *       a transaction-master indexed file while its control stream only defines a generation group,
 *       and it misspells a word in the same banner. Recorded, never reproduced.</li>
 * </ul>
 *
 * <h2>The sort specification, which is the parity core of this file</h2>
 *
 * <p>The specification declares two field symbols over the {@value #UNLOAD_RECORD_LENGTH}-byte record
 * image: the card number at position {@value #CARD_NUMBER_SORT_POSITION} for
 * {@value #CARD_NUMBER_SORT_LENGTH} bytes typed <strong>zoned decimal</strong>, and the processing
 * date at position {@value #PROCESSING_DATE_SORT_POSITION} for
 * {@value #PROCESSING_DATE_SORT_LENGTH} bytes typed <strong>character</strong>. The ordering is by
 * card number <strong>ascending</strong> and declares no secondary key.
 *
 * <p><strong>The very same field, at the same position and the same length, is typed
 * <em>character</em> by the statement job's own sort and <em>zoned decimal</em> here.</strong> Nothing
 * in the estate reconciles the two typings, and nothing should: each job's comparator must apply the
 * typing its own specification declares. A comparator shared between the two jobs would silently
 * apply one job's typing to the other's data - a parity defect that compiles cleanly and passes any
 * test written under the same misunderstanding. This job's comparator is therefore declared
 * {@code private static final} in this class, is never exported, never imported and never reused, and
 * the decoding itself is delegated to {@link ZonedDecimalCodec}, which owns the overpunch convention
 * in which the sign is folded into the final byte. Nothing here hand-rolls that decode and nothing
 * here rescales a value.
 *
 * <p>For a sixteen-digit, zero-padded, unsigned card number the zoned-decimal and character orderings
 * coincide. That coincidence is <strong>not</strong> relied on: the declared typing is implemented, so
 * a signed or short value cannot silently reorder the report. The ordering is applied with a stable
 * sort and a single key, so records sharing a card number keep the sequence the unload delivered them
 * in, which is the transaction-master key sequence.
 *
 * <p>Ordering belongs to job configuration rather than to a service because a verb census of all 28
 * legacy programs finds zero internal sort and zero merge statements: every ordering in the estate is
 * external, declared by a job stream, and there are four such specifications.
 *
 * <h2>The date window: both bounds inclusive</h2>
 *
 * <p>The record-inclusion predicate tests the processing-date field greater-than-or-equal to the start
 * value and less-than-or-equal to the end value. <strong>Both bounds are inclusive</strong>, so a
 * window whose start equals its end selects that one day rather than nothing. Both parameters are
 * ten-character values in hyphenated ISO form, and the comparison is a <em>character</em> comparison,
 * which coincides with chronological order precisely because the format carries hyphens. No temporal
 * type is constructed anywhere in this file and the record side is never converted in order to be
 * compared. The single expression of that predicate is
 * {@link JobParameterValidators.ReportDateWindow#includes(String)}, which this job calls rather than
 * restating, so the bound cannot drift to an exclusive comparison here.
 *
 * <h2>The date-parameter record, and the one measured asymmetry in how it is supplied</h2>
 *
 * <p>The two collaborating legacy steps receive the same window by two different mechanisms: the sort
 * step receives it as sort symbols, whereas the report program consumes it as an <em>assigned
 * sequential file</em> - a catalogued dataset - and not as a program parameter. That asymmetry is
 * measured, and it is why this job derives one validated window from one parameter pair and then
 * materialises the record image the report program's own reader contract expects. The record is
 * {@value ReportLineFormatter#DATE_PARAMETER_STRUCTURED_WIDTH} significant bytes - ten characters, one
 * space, ten characters - inside an eighty-byte record area. The build and the parse both belong to
 * shared owners: {@link ReportLineFormatter#buildDateParameterRecord(String, String)} places the
 * group, and {@link JobParameterValidators#parseDateParmRecord(String)} validates it and measures its
 * width in <em>encoded bytes</em>. Nothing here slices, pads or measures that record itself.
 *
 * <h2>The report output contract</h2>
 *
 * <p>Every report record is exactly {@value #REPORT_RECORD_LENGTH} encoded bytes, and the width is
 * proved on the encoded byte array at the moment a record reaches its destination - never on a
 * character count, which would accept a record that no longer fits the declared record length. The
 * layout itself belongs to {@link ReportLineFormatter}; this configuration composes a reader, a
 * formatter and a comparator and assembles no line of its own.
 *
 * <p>The page bound is {@value #PAGE_SIZE} records with a modulo break. It is a structural property of
 * the report, not a tuning figure, and no chunk size is derived from it: all three steps are
 * single-invocation steps, so this job states no commit interval at all.
 *
 * <p><strong>The accumulation chain is contractual: a transaction amount reaches the grand total only
 * by way of a page total.</strong> The report program adds each amount into the page total, and the
 * page-total routine is the only place that adds into the grand total. With truncating arithmetic the
 * indirect route and a direct sum can differ, so the indirect route is the parity-correct one. This
 * class neither adds an amount to a grand total nor recomputes one: the whole chain belongs to
 * {@link TransactionReportService}, and the figures this class reports are the ones that service
 * observed.
 *
 * <p>The report program contains no arithmetic-compute statement across any of its lines, so
 * <strong>no arithmetic is introduced into the report path</strong>. This class performs none: it
 * holds no accumulator, computes no total and rescales nothing. The rounding keyword occurs nowhere in
 * the estate, so truncation is the module-wide rule and it lives exclusively in
 * {@link ZonedDecimalCodec}. Every decimal figure that passes through this file is a
 * {@link BigDecimal}; no binary floating-point type appears anywhere in it.
 *
 * <h2>What this configuration deliberately does not do</h2>
 *
 * <ul>
 *   <li><strong>No process is spawned.</strong> The legacy first step invokes a cataloged wrapper around a
 *       copy utility and the second an external sort utility; both become ordinary read-and-write steps,
 *       so no shell is invoked and no command is composed.</li>
 *   <li><strong>No concurrency</strong> - no task executor, partitioner, multi-threaded step or parallel
 *       flow, because ordering is the reason the second step exists and the report is compared byte for
 *       byte.</li>
 *   <li><strong>Nothing fires at context start.</strong> Job launching is disabled in configuration and
 *       this class declares no runner, lifecycle callback or schedule; the job is launched on demand by
 *       {@link #JOB_NAME} through the registry the shared batch infrastructure publishes. It chains to no
 *       other job, because the estate has no master orchestrator.</li>
 *   <li><strong>No mutable shared state.</strong> Every field is final and none accumulates, and each step
 *       execution builds a fresh program lifecycle, so a page counter, running total, file handle and read
 *       position live only for the execution that owns them and cannot leak into the next run.</li>
 *   <li><strong>No storage resource is created, no retention applied and no performance figure set</strong>
 *       - each generation is one output resource per job execution resolved from configuration by logical
 *       name, and every step is timed on the shared meter registry, which is where a baseline is read
 *       from.</li>
 * </ul>
 *
 * <h2>The report consumes the sort step's frozen generation</h2>
 *
 * <p>The legacy report step reads the filtered, ordered generation the sort step produced. The target
 * does the same: the emit lifecycle parses that exact generation once, detaches it into an immutable
 * ordered snapshot and passes a service-owned {@link ReportTransactionSource} through the processor to
 * {@link TransactionReportService}. No report-stage query of the mutable transaction master exists, so
 * a row changed after the sort boundary cannot appear, disappear or move while the report is emitted.
 *
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public final class TransactionReportJobConfig {

    // -----------------------------------------------------------------------------------------------
    // Names. Public, because the launch surface addresses a job and a step by name and a test asserts
    // the same names against the measured member.
    // -----------------------------------------------------------------------------------------------

    /**
     * Registered name of this job, and the name a launch request addresses it by.
     *
     * <p>The value is read from {@code service/BatchJobCatalog}, which is the module's single
     * declaration of the nine stable job names. Both tiers that need a name - this configuration
     * and the operational control surface above it - resolve it from there, so the name exists as
     * one literal and the two cannot drift apart across a boundary the layering keeps closed.
     */
    public static final String JOB_NAME = BatchJobCatalog.TRANSACTION_REPORT_JOB;

    /**
     * Name of the first step: the unload, standing in for the legacy step at line 23 of the job
     * member.
     *
     * <p>Distinct from {@link #FILTER_AND_ORDER_STEP_NAME} by construction. The legacy member names
     * that step and this one identically, and this is where that collision is resolved.
     */
    public static final String UNLOAD_STEP_NAME = "transactionReportUnloadStep";

    /**
     * Name of the second step: the record-inclusion filter and the ordering, standing in for the
     * legacy external-sort step at line 37 of the job member - the second of the two steps the member
     * gives one name.
     */
    public static final String FILTER_AND_ORDER_STEP_NAME = "transactionReportFilterAndOrderStep";

    /** Name of the third step: the report emission, standing in for the legacy report step. */
    public static final String EMIT_STEP_NAME = "transactionReportEmitStep";

    /**
     * Steps the measured member declares, and therefore the steps this job wires. Published so a test
     * can assert the count against the member rather than against the wiring it is checking.
     */
    public static final int STEP_COUNT = 3;

    /**
     * Failure-ending transitions this job declares, which is none, because the measured member
     * declares no condition-code dependency on any step.
     */
    public static final int CONDITION_CODE_GATE_COUNT = 0;

    // -----------------------------------------------------------------------------------------------
    // Measured record contracts. Every width is taken from the layer that owns the layout rather than
    // restated, so a width has exactly one authority in the module.
    // -----------------------------------------------------------------------------------------------

    /**
     * Declared record length of both staged generations, fixed-length blocked. The unload wrapper's
     * output definition and the sort's output definition declare the same figure, the second by
     * copying the attributes of its input.
     */
    public static final int UNLOAD_RECORD_LENGTH = TransactionRecordMapper.RECORD_LENGTH;

    /** Declared record length of the report, fixed-length blocked. */
    public static final int REPORT_RECORD_LENGTH = TransactionReportProcessor.REPORT_RECORD_LENGTH;

    /**
     * Records per page of the report, the modulo bound the report program breaks a page on.
     *
     * <p>A structural property of the report layout, not a tuning figure: no chunk size, buffer size
     * or commit interval is derived from it anywhere in this file.
     */
    public static final int PAGE_SIZE = ReportLineFormatter.PAGE_SIZE;

    // -----------------------------------------------------------------------------------------------
    // The sort specification, as measured. Positions are stated in the specification's own one-based
    // form and derived from the zero-based offsets the record layout owns, so the two can never drift.
    // -----------------------------------------------------------------------------------------------

    /** One-based position of the card-number sort field within the record image. */
    public static final int CARD_NUMBER_SORT_POSITION = TransactionRecordMapper.TRAN_CARD_NUM_OFFSET + 1;

    /** Declared length of the card-number sort field. */
    public static final int CARD_NUMBER_SORT_LENGTH = TransactionRecordMapper.TRAN_CARD_NUM_LENGTH;

    /**
     * One-based position of the processing-date sort field within the record image, which is the
     * leading date portion of the wider processing-timestamp field.
     */
    public static final int PROCESSING_DATE_SORT_POSITION =
            TransactionRecordMapper.TRAN_PROC_TS_OFFSET + 1;

    /** Declared length of the processing-date sort field. */
    public static final int PROCESSING_DATE_SORT_LENGTH = ReportLineFormatter.DATE_WIDTH;

    // -----------------------------------------------------------------------------------------------
    // Generation retention. Named by the store that applies it, never restated here.
    //
    // The three bases this job publishes to are retained at the two depths StagedGenerationStore
    // publishes: the transaction backup and the filtered transactions at its standard depth, and the
    // report at its own measured exception. Nothing is declared here.
    //
    // Depth is a property of the BASE, not of the job that happens to be publishing to it, and the
    // transaction-backup base is published by two jobs - this one's unload step and the backup job. Two
    // publishers each carrying their own copy of the depth is precisely how the retained set comes to
    // depend on which job ran last, so this file reads the store's constants at the point of use and
    // holds no retention figure of its own. The store is the one place a measured depth is written down,
    // // and the double declaration of the report base - a limit of five with a scratch attribute against a
    // // later limit of ten without one - is resolved there and recorded in
    // // {@code docs/decision-log.md} rather than being carried here as a second, unread constant.
    // -----------------------------------------------------------------------------------------------

    // -----------------------------------------------------------------------------------------------
    // Diagnostics and legacy provenance. The step and data-definition names below are the legacy
    // member's own identifiers, carried so a diagnostic can be traced back to the stream it replaces.
    // -----------------------------------------------------------------------------------------------

    /** Diagnostics for this configuration and for its three program lifecycles. */
    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionReportJobConfig.class);

    /**
     * The cataloged form's name for the unload step, preferred over the job member's colliding name so
     * the two lifecycles are distinguishable in a diagnostic.
     */
    private static final String LEGACY_CATALOGED_UNLOAD_STEP = "STEP01R";

    /** Data-definition name of the unload wrapper's input, the transaction master. */
    private static final String DD_UNLOAD_INPUT = "FILEIN";

    /** Data-definition name of the unload wrapper's output, the backup generation. */
    private static final String DD_UNLOAD_OUTPUT = "FILEOUT";

    /** Data-definition name of the sort's input, the backup generation. */
    private static final String DD_SORT_INPUT = "SORTIN";

    /** Data-definition name of the sort's output, the filtered and ordered generation. */
    private static final String DD_SORT_OUTPUT = "SORTOUT";

    /**
     * Data-definition name of the report step's transaction input.
     *
     * <p>The third step opens and parses this generation, then freezes its ordered contents before
     * report generation begins.
     */
    private static final String DD_REPORT_INPUT = "TRANFILE";

    /** Legacy field name of the card-number sort symbol, used in a decode diagnostic. */
    private static final String FIELD_TRAN_CARD_NUM = "TRAN-CARD-NUM";

    /** Legacy field name of the processing-date sort symbol, used in a slice diagnostic. */
    private static final String FIELD_TRAN_PROC_DT = "TRAN-PROC-DT";

    // -----------------------------------------------------------------------------------------------
    // Staged-dataset mechanics.
    // -----------------------------------------------------------------------------------------------

    /**
     * Scale the card-number sort key is decoded at. The field is a whole-number identifier, so it
     * carries no implied decimal position; the codec still owns the decode and the sign convention.
     */
    private static final int SORT_KEY_SCALE = 0;

    /** Offset of the date portion within the record image's processing-timestamp field. */
    private static final int PROCESSING_DATE_OFFSET_IN_IMAGE = TransactionRecordMapper.TRAN_PROC_TS_OFFSET;

    private static final int KEYSET_PAGE_SIZE = BoundedKeysetIterator.DEFAULT_PAGE_SIZE;

    // -----------------------------------------------------------------------------------------------
    // THE ORDERING. Private to this class, and it must stay private - see the class documentation for
    // why a comparator shared with the statement job would be a parity defect rather than reuse.
    // -----------------------------------------------------------------------------------------------

    /**
     * The ordering the second step applies: card number ascending, typed <strong>zoned decimal</strong>
     * exactly as this job's own specification declares, over the record image the sort addresses.
     *
     * <p>Declared {@code private static final} deliberately. The statement job orders the identical
     * bytes at the identical position typed <em>character</em>, so the two typings must not be
     * expressed by one comparator: sharing one would apply a typing that job never declared and would
     * do so without any symptom a compiler or an unwitting test could detect.
     *
     * <p>Exactly one key, because the specification declares no secondary key. Applied with a stable
     * sort, so records sharing a card number retain the order the unload delivered them in.
     */
    private static final Comparator<String> CARD_NUMBER_ZONED_DECIMAL_ASCENDING =
            Comparator.comparing(TransactionReportJobConfig::cardNumberSortKey);

    // -----------------------------------------------------------------------------------------------
    // Collaborators. Constructor injection only; every field final; none of them accumulates.
    // -----------------------------------------------------------------------------------------------

    /** The framework's metadata repository, handed to every builder rather than to a factory. */
    private final JobRepository jobRepository;

    /** Transaction manager each step's single invocation runs under. */
    private final PlatformTransactionManager transactionManager;

    /** The shared job-boundary diagnostic the batch infrastructure configuration publishes. */
    private final JobExecutionListener jobBoundaryListener;

    /**
     * The shared run incrementer from the same configuration, which is what lets this job be
     * resubmitted with an identical parameter set exactly as the legacy member could be.
     */
    private final JobParametersIncrementer jobRunIncrementer;

    /** Owner of the launch-parameter cascade and of the single inclusive date-window predicate. */
    private final JobParameterValidators jobParameterValidators;

    /** Bounded sequential-read view kept separate from the frozen online repository surface. */
    private final TransactionScanRepository transactionScanRepository;

    /** Source of the fixed-width reader over the unloaded generation. */
    private final FixedWidthFlatFileReaderFactory readerFactory;

    /** The report generator this job's third step delegates one whole report to. */
    private final TransactionReportService reportService;

    /** Registry every step and every program lifecycle is timed on. */
    private final MeterRegistry meterRegistry;

    /** The module's clock, which stamps each program lifecycle's boundaries. */
    private final Clock clock;

    /** Directory the staged generations of this job resolve within. */
    private final String stagingDirectory;

    /** Logical name of the transaction backup generation base the unload writes a generation of. */
    private final String transactionBackupBase;

    /** Logical name of the filtered transaction generation base the ordering step writes. */
    private final String filteredTransactionBase;

    /** Logical name of the report generation base the third step writes. */
    private final String reportBase;

    /**
     * Wires the job's collaborators.
     *
     * <p>Every resource is named by configuration rather than by a path written into this class, and
     * each logical name defaults to the name the legacy stream uses for the same resource - so a
     * deployment may relocate the staging area without the job losing the identity of what it writes.
     * None of these values is a credential, and none is defaulted that could be one.
     *
     * @param jobRepository the framework's metadata repository; must not be {@code null}
     * @param transactionManager the manager each step runs under; must not be {@code null}
     * @param jobBoundaryListener the shared job-boundary diagnostic; must not be {@code null}
     * @param jobRunIncrementer the shared run incrementer; must not be {@code null}
     * @param jobParameterValidators owner of the parameter cascade and of the window predicate; must
     *                               not be {@code null}
     * @param transactionScanRepository bounded sequential-read view of the transaction master
     * @param readerFactory source of the fixed-width reader; must not be {@code null}
     * @param reportService the report generator; must not be {@code null}
     * @param meterRegistry the registry every step is timed on; must not be {@code null}
     * @param clock the module's clock; must not be {@code null}
     * @param stagingDirectory directory the staged generations resolve within; must not be blank
     * @param transactionBackupBase logical name of the backup generation base; must not be blank
     * @param filteredTransactionBase logical name of the filtered generation base; must not be blank
     * @param reportBase logical name of the report generation base; must not be blank
     */
    public TransactionReportJobConfig(
            final JobRepository jobRepository,
            final PlatformTransactionManager transactionManager,
            @Qualifier("batchJobBoundaryListener") final JobExecutionListener jobBoundaryListener,
            @Qualifier("batchJobRunIncrementer") final JobParametersIncrementer jobRunIncrementer,
            final JobParameterValidators jobParameterValidators,
            final TransactionScanRepository transactionScanRepository,
            final FixedWidthFlatFileReaderFactory readerFactory,
            final TransactionReportService reportService,
            final MeterRegistry meterRegistry,
            final Clock clock,
            @Value("${carddemo.batch.transaction-report.staging-directory:${"
                    + StagedGenerationStore.SHARED_STAGING_DIRECTORY_PROPERTY
                    + ":${java.io.tmpdir}}}")
                    final String stagingDirectory,
            // The default is READ FROM the backup job's own declaration rather than repeated here. Both
            // jobs publish generations of this one base - this job's unload step and that job's archive
            // step - and the base is the unit of retention counting, so two spellings of it would be two
            // retention groups that only appeared to be one. A constant reference cannot drift.
            @Value("${carddemo.batch.transaction-report.transaction-backup-base:"
                    + BackupTransactionJobConfig.ARCHIVE_DATASET_BASE + "}")
                    final String transactionBackupBase,
            @Value("${carddemo.batch.transaction-report.filtered-transaction-base:"
                    + "AWS.M2.CARDDEMO.TRANSACT.DALY}") final String filteredTransactionBase,
            @Value("${carddemo.batch.transaction-report.report-base:AWS.M2.CARDDEMO.TRANREPT}")
                    final String reportBase) {

        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.jobBoundaryListener =
                Objects.requireNonNull(jobBoundaryListener, "jobBoundaryListener");
        this.jobRunIncrementer = Objects.requireNonNull(jobRunIncrementer, "jobRunIncrementer");
        this.jobParameterValidators =
                Objects.requireNonNull(jobParameterValidators, "jobParameterValidators");
        this.transactionScanRepository = Objects.requireNonNull(
                transactionScanRepository, "transactionScanRepository");
        this.readerFactory = Objects.requireNonNull(readerFactory, "readerFactory");
        this.reportService = Objects.requireNonNull(reportService, "reportService");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.stagingDirectory = requireResourceName(stagingDirectory, "stagingDirectory");
        this.transactionBackupBase =
                StagedResourceNames.requireSimpleName(transactionBackupBase, "transactionBackupBase");
        this.filteredTransactionBase =
                StagedResourceNames.requireSimpleName(filteredTransactionBase,
                        "filteredTransactionBase");
        this.reportBase = StagedResourceNames.requireSimpleName(reportBase, "reportBase");
    }

    // -----------------------------------------------------------------------------------------------
    // The per-record stage of the third step.
    // -----------------------------------------------------------------------------------------------

    /**
     * Publishes the report stage, which carries no framework stereotype of its own and is therefore
     * declared here rather than discovered.
     *
     * <p>It holds no mutable state, so one instance serves every execution. It is deliberately given
     * only the report generator and the registry: the three lookup readers belong to that generator and
     * the report layout belongs to the formatter, so taking either here would give the module two
     * owners for one decision.
     *
     * @return the report stage, never {@code null}
     */
    @Bean
    public TransactionReportProcessor transactionReportProcessor() {
        return new TransactionReportProcessor(this.reportService, this.meterRegistry);
    }

    // -----------------------------------------------------------------------------------------------
    // The job and its three steps.
    // -----------------------------------------------------------------------------------------------

    /**
     * The job: the unload, then the filter and ordering, then the report emission - and nothing else.
     *
     * <p><strong>Three steps in plain sequence and not one failure-ending transition</strong>, because
     * the measured member declares no condition-code dependency on any step. Strictly sequential: no
     * task executor, no partitioning, no parallel flow, because concurrency would reorder output that
     * is compared byte for byte.
     *
     * <p>Three collaborators are attached. The parameter validator refuses a launch whose date window
     * is absent, malformed or inverted before any step runs, so a job cannot reach the report stage
     * with a window the legacy stream could not have expressed. The run incrementer makes a
     * resubmission of the same logical work a new instance rather than a completed-instance refusal,
     * matching a legacy member that could simply be submitted again. The boundary diagnostic frames
     * the three step-level lines this class emits with a job-level pair.
     *
     * <p>The steps arrive as qualified parameters rather than as calls to this class's own bean
     * methods, because bean methods are not proxied here - which is what lets this class be
     * {@code final} - so a direct call would build a second, unregistered step.
     *
     * <p>Nothing launches this job automatically and it chains to no other job.
     *
     * @param unloadStep the first step; must not be {@code null}
     * @param filterAndOrderStep the second step; must not be {@code null}
     * @param emitStep the third step; must not be {@code null}
     * @return the job, registered under {@link #JOB_NAME}, never {@code null}
     */
    @Bean
    public Job transactionReportJob(
            @Qualifier(UNLOAD_STEP_NAME) final Step unloadStep,
            @Qualifier(FILTER_AND_ORDER_STEP_NAME) final Step filterAndOrderStep,
            @Qualifier(EMIT_STEP_NAME) final Step emitStep) {

        Objects.requireNonNull(unloadStep, "unloadStep");
        Objects.requireNonNull(filterAndOrderStep, "filterAndOrderStep");
        Objects.requireNonNull(emitStep, "emitStep");

        return new JobBuilder(JOB_NAME, this.jobRepository)
                .validator(this.jobParameterValidators.reportDateRangeValidator())
                .incrementer(this.jobRunIncrementer)
                .listener(this.jobBoundaryListener)
                .start(unloadStep)
                .next(filterAndOrderStep)
                .next(emitStep)
                .build();
    }

    /**
     * The unload step: copy the transaction master, in cluster-key sequence, to the backup generation
     * this job execution owns, at {@value #UNLOAD_RECORD_LENGTH} bytes per record.
     *
     * <p>One ordinary read-and-write step. The legacy step invoked a cataloged wrapper around a copy
     * utility; <strong>no process is spawned and no external tool is addressed here</strong>.
     *
     * <p>A single-invocation step rather than a chunked one, because the legacy unload was one
     * indivisible copy of one dataset and a chunked step would have to state a commit interval - a
     * tuning figure this translation has no legacy basis for choosing.
     *
     * @return the step, registered under {@link #UNLOAD_STEP_NAME}, never {@code null}
     */
    @Bean
    public Step transactionReportUnloadStep() {
        return new StepBuilder(UNLOAD_STEP_NAME, this.jobRepository)
                .tasklet(this::unloadTransactionMaster, this.transactionManager)
                .meterRegistry(this.meterRegistry)
                .build();
    }

    /**
     * The filter-and-order step: read the frozen unload, admit the records whose processing date falls
     * inside the inclusive window, order them by card number ascending under this class's own
     * zoned-decimal typing, and write the filtered generation at
     * {@value #UNLOAD_RECORD_LENGTH} bytes per record.
     *
     * <p>Reproduces the legacy external-sort step. It is a read-and-write step for the same reason the
     * unload is: <strong>the sort utility is not invoked, it is replaced</strong>. Its record source is
     * the frozen generation the preceding step wrote, which is what the procedure's own
     * {@code SORTIN DD} names, so the report and the archive taken beside it describe the same records.
     *
     * @return the step, registered under {@link #FILTER_AND_ORDER_STEP_NAME}, never {@code null}
     */
    @Bean
    public Step transactionReportFilterAndOrderStep() {
        return new StepBuilder(FILTER_AND_ORDER_STEP_NAME, this.jobRepository)
                .tasklet(this::filterAndOrderTransactions, this.transactionManager)
                .meterRegistry(this.meterRegistry)
                .build();
    }

    /**
     * The report step: materialise the date-parameter record the report program reads, delegate one
     * whole report to the report stage, and write every record of it at
     * {@value #REPORT_RECORD_LENGTH} bytes.
     *
     * <p>All business logic - the card-number break, the account totals, the page break, the header
     * block and the page-to-grand-total chain - belongs to the report generator and its stage. This
     * step composes them and proves the width of every record that reaches the generation.
     *
     * <p>The step seals and registers the report generation; the shared job-boundary listener uploads it
     * once the submission has completed. That is what keeps one generation to one key and keeps a failed
     * submission's report out of the bucket entirely. See {@code docs/decision-log.md} entry DL-212.
     *
     * @param reportProcessor the report stage; must not be {@code null}
     * @return the step, registered under {@link #EMIT_STEP_NAME}, never {@code null}
     */
    @Bean
    public Step transactionReportEmitStep(
            @Qualifier("transactionReportProcessor")
            final TransactionReportProcessor reportProcessor) {

        Objects.requireNonNull(reportProcessor, "reportProcessor");
        return new StepBuilder(EMIT_STEP_NAME, this.jobRepository)
                .tasklet((contribution, chunkContext) -> emitReport(reportProcessor, chunkContext),
                        this.transactionManager)
                .meterRegistry(this.meterRegistry)
                .build();
    }

    /**
     * One forward walk over a filtered transaction generation.
     *
     * <p>Forward-only and one record deep. The position most recently served may be asked for again
     * and is served from the record in hand without a further read, which is what lets a caller re-arm
     * its position at the first record without consuming it. A position below the one last served is
     * refused, because serving it would mean re-reading the generation from its start.
     */
    static final class SequentialGenerationWalk implements ReportTransactionSource {

        /** Position value standing for "no record has been served yet". */
        private static final int BEFORE_FIRST_RECORD_POSITION = -1;

        /** The gated read of the next record of the generation. */
        private final Supplier<Optional<Transaction>> nextRecord;

        /** Position of the record in hand. */
        private int servedPosition = BEFORE_FIRST_RECORD_POSITION;

        /** The record in hand, or {@code null} before the first read and after exhaustion. */
        private Transaction served;

        /** Whether the generation has been read to end of file. */
        private boolean exhausted;

        /**
         * @param nextRecord the gated read of the next record; must not be {@code null}
         */
        SequentialGenerationWalk(final Supplier<Optional<Transaction>> nextRecord) {
            this.nextRecord = Objects.requireNonNull(nextRecord, "nextRecord");
        }

        @Override
        public Optional<Transaction> readAt(final int position) {
            if (position < 0) {
                throw new IllegalArgumentException(
                        "report transaction position must not be negative: " + position);
            }
            if (position == this.servedPosition) {
                return Optional.ofNullable(this.served);
            }
            if (position < this.servedPosition) {
                throw new IllegalStateException("the " + DD_REPORT_INPUT
                        + " generation is read forward: position " + position
                        + " was asked for after position " + this.servedPosition
                        + " had already been served");
            }
            while (this.servedPosition < position && advance()) {
                // Advancing is the whole of the loop body; the guard performs it.
            }
            return this.servedPosition == position ? Optional.ofNullable(this.served)
                    : Optional.empty();
        }

        /**
         * Serves the next record, if there is one.
         *
         * @return {@code true} when a record was served, {@code false} at end of file
         */
        private boolean advance() {
            if (this.exhausted) {
                return false;
            }
            final Optional<Transaction> next = Objects.requireNonNull(this.nextRecord.get(),
                    "the gated read must report an Optional, never null");
            if (next.isEmpty()) {
                this.exhausted = true;
                this.served = null;
                return false;
            }
            this.served = next.get();
            this.servedPosition++;
            return true;
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Tasklet adapters. Each builds a FRESH program lifecycle for the execution it is serving, so every
    // per-execution handle, counter and total lives on a short-lived object and nothing is shared
    // between executions. The chunk context supplies the job execution identifier, which is how the
    // three steps agree on one set of generations without state passing between them.
    //
    // Package-visible rather than private so that a test in this package can drive one adapter against
    // a step execution and confirm that agreement directly, instead of leaving it reachable only
    // through a launcher. They are not part of the launch surface: the beans above are.
    // -----------------------------------------------------------------------------------------------

    /**
     * Runs the unload for one step execution.
     *
     * @param contribution the framework's per-step contribution, unused because the whole step is one
     *                     indivisible pass
     * @param chunkContext the framework's chunk context; must not be {@code null}
     * @return {@link RepeatStatus#FINISHED} always
     */
    RepeatStatus unloadTransactionMaster(final StepContribution contribution,
            final ChunkContext chunkContext) {

        final long jobExecutionId = jobExecutionIdOf(chunkContext);
        final Path generation = backupGeneration(jobExecutionId);
        final Path working = StagedGenerationStore.workingPath(generation);
        newUnloadProgram(working).run();
        StagedGenerationStore.completeWorkingFile(working, generation);
        StagedGenerationStore.register(stepExecutionOf(chunkContext),
                this.transactionBackupBase, generation,
                StagedGenerationStore.STANDARD_RETENTION_LIMIT);
        return RepeatStatus.FINISHED;
    }

    /**
     * Runs the record-inclusion filter and the ordering for one step execution, reading the generation
     * the unload step of the same job execution wrote.
     *
     * @param contribution the framework's per-step contribution, unused for the reason above
     * @param chunkContext the framework's chunk context; must not be {@code null}
     * @return {@link RepeatStatus#FINISHED} always
     */
    RepeatStatus filterAndOrderTransactions(final StepContribution contribution,
            final ChunkContext chunkContext) {

        final long jobExecutionId = jobExecutionIdOf(chunkContext);
        final Path generation = filteredGeneration(jobExecutionId);
        final Path working = StagedGenerationStore.workingPath(generation);
        // The SEALED backup generation the unload step of this same job execution wrote, which is the
        // cataloged procedure's own SORTIN. Not the live cluster: the report must describe the copy the
        // backup took, and the two diverge the moment anything commits between the steps.
        newFilterAndOrderProgram(backupGeneration(jobExecutionId), working,
                reportDateWindow(chunkContext)).run();
        StagedGenerationStore.completeWorkingFile(working, generation);
        StagedGenerationStore.register(stepExecutionOf(chunkContext),
                this.filteredTransactionBase, generation,
                StagedGenerationStore.STANDARD_RETENTION_LIMIT);
        return RepeatStatus.FINISHED;
    }

    /**
     * Runs the report emission for one step execution.
     *
     * @param reportProcessor the report stage; must not be {@code null}
     * @param chunkContext the framework's chunk context; must not be {@code null}
     * @return {@link RepeatStatus#FINISHED} always
     */
    RepeatStatus emitReport(final TransactionReportProcessor reportProcessor,
            final ChunkContext chunkContext) {

        final long jobExecutionId = jobExecutionIdOf(chunkContext);
        final Path generation = reportGeneration(jobExecutionId);
        final Path working = StagedGenerationStore.workingPath(generation);
        newEmitProgram(reportProcessor, dateParameterCard(chunkContext),
                filteredGeneration(jobExecutionId), working).run();
        StagedGenerationStore.completeWorkingFile(working, generation);
        StagedGenerationStore.register(stepExecutionOf(chunkContext),
                this.reportBase, generation, StagedGenerationStore.REPORT_RETENTION_LIMIT);
        return RepeatStatus.FINISHED;
    }

    // -----------------------------------------------------------------------------------------------
    // Program-lifecycle factories. Package-visible so a test in this package can drive one lifecycle
    // directly against temporary generations, rather than leaving the three of them reachable only
    // through a launcher. They are not part of the launch surface: the beans above are.
    // -----------------------------------------------------------------------------------------------

    /**
     * Builds one unload lifecycle.
     *
     * @param generation the backup generation to write; must not be {@code null}
     * @return a fresh lifecycle, never {@code null}
     */
    TransactionUnloadProgram newUnloadProgram(final Path generation) {
        return new TransactionUnloadProgram(this.meterRegistry, this.clock,
                this.transactionScanRepository, generation);
    }

    /**
     * Builds one filter-and-order lifecycle.
     *
     * @param unloadedGeneration the frozen generation the unload step of the same execution wrote,
     *                           which is this step's input; must not be {@code null}
     * @param filteredGeneration the generation to write; must not be {@code null}
     * @param window the inclusive processing-date window; must not be {@code null}
     * @return a fresh lifecycle, never {@code null}
     */
    FilterAndOrderProgram newFilterAndOrderProgram(final Path unloadedGeneration,
            final Path filteredGeneration,
            final JobParameterValidators.ReportDateWindow window) {

        return new FilterAndOrderProgram(this.meterRegistry, this.clock, unloadedGeneration,
                filteredGeneration, window);
    }

    /**
     * Builds one report-emission lifecycle.
     *
     * @param reportProcessor the report stage; must not be {@code null}
     * @param dateParameterCard the validated date-parameter record, or {@code null} for an empty
     *                          parameter dataset
     * @param filteredGeneration the frozen report input produced by the preceding step
     * @param reportGeneration the report generation to write; must not be {@code null}
     * @return a fresh lifecycle, never {@code null}
     */
    ReportEmitProgram newEmitProgram(final TransactionReportProcessor reportProcessor,
            final String dateParameterCard, final Path filteredGeneration,
            final Path reportGeneration) {

        return new ReportEmitProgram(this.meterRegistry, this.clock, this.readerFactory,
                reportProcessor, dateParameterCard, filteredGeneration, reportGeneration);
    }

    // -----------------------------------------------------------------------------------------------
    // The date window. One parameter pair, one validated window, one materialised record - so the sort
    // step and the report step cannot disagree about which days the run covers.
    // -----------------------------------------------------------------------------------------------

    /**
     * Materialises the date-parameter record the report program reads, from the launch parameters.
     *
     * <p>The record is built by the shared formatter and then validated by the shared parameter owner,
     * whose cascade measures the significant group in <em>encoded bytes</em>. Nothing is sliced,
     * padded or measured here.
     *
     * @param chunkContext the framework's chunk context; must not be {@code null}
     * @return the validated record image, never {@code null}
     */
    String dateParameterCard(final ChunkContext chunkContext) {
        final String card = buildDateParameterCard(chunkContext);
        // Validating what was just built is not redundant: the cascade is what refuses an inverted or
        // non-calendar window, and it is the same cascade the launch-time validator runs, so the record
        // the report program receives has passed it whichever route produced it. The parsed window is
        // not needed here - the report program's input is the record image, not a window object.
        this.jobParameterValidators.parseDateParmRecord(card);
        return card;
    }

    /**
     * The inclusive processing-date window of one execution, parsed from the same record image the
     * report program receives - so the filter and the report answer to one window rather than to two
     * independent readings of two parameters.
     *
     * @param chunkContext the framework's chunk context; must not be {@code null}
     * @return the validated window, never {@code null}
     */
    JobParameterValidators.ReportDateWindow reportDateWindow(final ChunkContext chunkContext) {
        return this.jobParameterValidators.parseDateParmRecord(buildDateParameterCard(chunkContext));
    }

    /**
     * Places the two launch bounds into the record image the report program's reader contract expects.
     *
     * <p>The placement belongs to the shared formatter, which owns the group's widths and its separator
     * position; this method supplies the two bounds and nothing else.
     *
     * @param chunkContext the framework's chunk context; must not be {@code null}
     * @return the record image, never {@code null}
     */
    private String buildDateParameterCard(final ChunkContext chunkContext) {
        final JobParameters parameters = parametersOf(chunkContext);
        return ReportLineFormatter.buildDateParameterRecord(
                requireDateParameter(parameters, JobParameterValidators.REPORT_START_DATE_KEY),
                requireDateParameter(parameters, JobParameterValidators.REPORT_END_DATE_KEY));
    }

    /**
     * Reads one date bound from the launch parameters, refusing an absent one.
     *
     * <p>The diagnostic precedes the refusal, which is the ordering the batch tier uses throughout. No
     * default is substituted: a job that resolved a missing bound to some conventional date would
     * report a window nobody asked for, and would do so without failing.
     *
     * @param parameters the launch parameters; must not be {@code null}
     * @param key the parameter key to read; must not be {@code null}
     * @return the bound as supplied, never {@code null}
     */
    private static String requireDateParameter(final JobParameters parameters, final String key) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(key, "key");

        final String value = parameters.getString(key);
        if (value == null || value.isBlank()) {
            LOGGER.error("{} {}: launch parameter {} named no date bound, so the reporting window"
                            + " cannot be established", TransactionReportProcessor.LEGACY_JOB,
                    TransactionReportProcessor.LEGACY_REPORT_STEP, key);
            throw new IllegalArgumentException("launch parameter " + key
                    + " must name a date bound of the reporting window, and no default may be"
                    + " substituted for it");
        }
        return value;
    }

    /**
     * Reads the launch parameters of the execution a step is serving.
     *
     * @param chunkContext the framework's chunk context; must not be {@code null}
     * @return the launch parameters, never {@code null}
     */
    private static JobParameters parametersOf(final ChunkContext chunkContext) {
        Objects.requireNonNull(chunkContext, "chunkContext");
        return Objects.requireNonNull(
                chunkContext.getStepContext().getStepExecution().getJobParameters(),
                "the framework must have supplied the launch parameters before a step runs");
    }

    /**
     * Reads the job execution identifier the framework assigned, which all three steps use to name the
     * generations their one job execution owns.
     *
     * @param chunkContext the framework's chunk context; must not be {@code null}
     * @return the job execution identifier
     */
    private static long jobExecutionIdOf(final ChunkContext chunkContext) {
        final Long identifier = stepExecutionOf(chunkContext).getJobExecutionId();
        return Objects.requireNonNull(identifier,
                "the framework must have assigned a job execution identifier before a step runs")
                .longValue();
    }

    /**
     * Reads the step execution that owns one tasklet invocation.
     *
     * @param chunkContext framework chunk context
     * @return current step execution
     */
    private static StepExecution stepExecutionOf(final ChunkContext chunkContext) {
        Objects.requireNonNull(chunkContext, "chunkContext");
        return Objects.requireNonNull(chunkContext.getStepContext().getStepExecution(),
                "the framework must have opened a step execution before its tasklet runs");
    }

    // -----------------------------------------------------------------------------------------------
    // Resource resolution. Every generation is a logical name resolved against a configured directory;
    // no path is written into this class, no storage resource is created and no retention is applied.
    // -----------------------------------------------------------------------------------------------

    /**
     * The transaction backup generation one job execution owns.
     *
     * @param jobExecutionId the job execution the generation belongs to
     * @return the resolved generation
     */
    Path backupGeneration(final long jobExecutionId) {
        return generationOf(this.transactionBackupBase, jobExecutionId);
    }

    /**
     * The filtered, ordered generation one job execution owns.
     *
     * @param jobExecutionId the job execution the generation belongs to
     * @return the resolved generation
     */
    Path filteredGeneration(final long jobExecutionId) {
        return generationOf(this.filteredTransactionBase, jobExecutionId);
    }

    /**
     * The report generation one job execution owns.
     *
     * @param jobExecutionId the job execution the generation belongs to
     * @return the resolved generation
     */
    Path reportGeneration(final long jobExecutionId) {
        return generationOf(this.reportBase, jobExecutionId);
    }

    /**
     * Resolves one absolute generation of a base within the configured staging directory.
     *
     * @param base the logical generation base; must not be {@code null}
     * @param jobExecutionId the job execution the generation belongs to
     * @return the resolved generation
     */
    private Path generationOf(final String base, final long jobExecutionId) {
        return StagedGenerationStore.generationPath(Path.of(this.stagingDirectory),
                base, jobExecutionId);
    }

    /**
     * Validates a configured logical name, because an absent or blank one would resolve to the staging
     * directory itself and a step would then write over a directory rather than a generation.
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
     */
    private static String requireResourceName(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name
                    + " must name a resource; a blank logical name would resolve to the staging"
                    + " directory itself rather than to a generation");
        }
        return value;
    }

    // -----------------------------------------------------------------------------------------------
    // The two sort symbols. Each is addressed at the position and length its own specification
    // declares, and each is read out of the record image by the fixed-width utility, so no offset
    // arithmetic and no slicing happens in this class.
    // -----------------------------------------------------------------------------------------------

    /**
     * The card-number ordering key, decoded with the <strong>zoned-decimal</strong> typing this job's
     * specification declares, so a sign folded into the field's final byte is honoured as a sign rather
     * than compared as a character.
     *
     * <p>The decode belongs to {@link ZonedDecimalCodec}, which owns the overpunch convention. Nothing
     * here rescales the result: the field is a whole-number identifier and the key is used only for
     * ordering.
     *
     * @param recordImage the whole record image, exactly {@value #UNLOAD_RECORD_LENGTH} encoded bytes;
     *                    must not be {@code null}
     * @return the decoded ordering key, never {@code null}
     */
    private static BigDecimal cardNumberSortKey(final String recordImage) {
        return ZonedDecimalCodec.decode(
                sortField(recordImage, FIELD_TRAN_CARD_NUM,
                        TransactionRecordMapper.TRAN_CARD_NUM_OFFSET, CARD_NUMBER_SORT_LENGTH),
                CARD_NUMBER_SORT_LENGTH, SORT_KEY_SCALE, FIELD_TRAN_CARD_NUM);
    }

    /**
     * The processing-date field the record-inclusion predicate tests, typed <strong>character</strong>
     * as its own specification declares - which is why it is read as characters and never converted to
     * a temporal type in order to be compared.
     *
     * @param recordImage the whole record image, exactly {@value #UNLOAD_RECORD_LENGTH} encoded bytes;
     *                    must not be {@code null}
     * @return the field as it stands in the record, exactly {@value #PROCESSING_DATE_SORT_LENGTH}
     *         characters, never {@code null}
     */
    private static String processingDateSortField(final String recordImage) {
        return sortField(recordImage, FIELD_TRAN_PROC_DT, PROCESSING_DATE_OFFSET_IN_IMAGE,
                PROCESSING_DATE_SORT_LENGTH);
    }

    /**
     * Reads one declared field out of a record image through the shared fixed-width reader, which
     * refuses a mis-sized image and names the field in any diagnostic.
     *
     * @param recordImage the whole record image; must not be {@code null}
     * @param fieldName the field's legacy name, for the diagnostic; must not be {@code null}
     * @param offset the field's zero-based offset, as the record layout declares it
     * @param length the field's declared length
     * @return the raw, untrimmed field value, never {@code null}
     */
    private static String sortField(final String recordImage, final String fieldName,
            final int offset, final int length) {

        Objects.requireNonNull(recordImage, "recordImage");
        return FixedWidthFieldReader
                .of(TransactionRecordMapper.ARTEFACT, recordImage, UNLOAD_RECORD_LENGTH)
                .field(fieldName, offset, length);
    }

    // -----------------------------------------------------------------------------------------------
    // Staged-dataset mechanics, shared by the three lifecycles.
    // -----------------------------------------------------------------------------------------------

    /**
     * Opens one generation for writing, creating its container if the staging area does not yet exist
     * and truncating any earlier content of the same absolute generation.
     *
     * @param target the generation to write; must not be {@code null}
     * @return the writer
     * @throws IOException if the generation cannot be created or opened
     */
    private static BufferedWriter openForWriting(final Path target) throws IOException {
        Objects.requireNonNull(target, "target");
        // Owner-only from the first byte, never through a planted link, and never onto a file a
        // previous run left: every 133-byte line of this generation names an account and a card.
        // See docs/decision-log.md entry DL-178.
        return SecureStagedFiles.newWriter(target, StandardCharsets.US_ASCII);
    }

    /**
     * Proves that one record is exactly the declared record length in <strong>encoded bytes</strong>.
     *
     * <p>Measured on the encoded array rather than on a character count, because it is the byte count
     * the record length declares. A record of the wrong width would leave every downstream reader of
     * that generation reading the wrong field.
     *
     * <p>Width alone is not enough to make a record right, and this proof does not pretend otherwise:
     * the encoder substitutes a single replacement byte for a character it cannot represent, which keeps
     * the count correct and makes the content wrong. Purity is therefore proved by the layers that build
     * these records - the record layout for a staged generation and the report stage for a report
     * record - and is deliberately not proved a second time here.
     *
     * <p>Package-visible so a test in this package can drive the proof itself. Its refusal cannot be
     * provoked through either staged generation, because the record layout and the report layout each
     * guarantee their own width - which is exactly why the proof has to be exercised directly rather
     * than left as a line nobody can reach.
     *
     * @param image the record about to be written; must not be {@code null}
     * @param declaredLength the record length the generation declares
     * @param resource the data definition being written, for the diagnostic
     */
    static void requireEncodedWidth(final String image, final int declaredLength,
            final String resource) {

        Objects.requireNonNull(image, "image");
        final int encoded = image.getBytes(StandardCharsets.US_ASCII).length;
        if (encoded != declaredLength) {
            throw new IllegalStateException("record written to " + resource + " is " + encoded
                    + " encoded byte(s) but the declared record length is " + declaredLength
                    + "; a record of the wrong width would leave every downstream reader of that"
                    + " generation reading the wrong field");
        }
    }

    /**
     * Hands back a handle after a failure without emitting a legacy diagnostic and without abending.
     *
     * <p>Non-observable runtime adaptation, not a second close sequence: the mainframe enclave released
     * its own handles, the virtual machine does not.
     *
     * <p>Package-visible for the same reason the width proof is: both of its branches exist for a
     * failure path, and a test drives them directly rather than provoking them through a lifecycle.
     *
     * @param handle the handle to release, possibly {@code null}
     * @param resource the data definition it belongs to, for the trace line
     */
    static void releaseQuietly(final AutoCloseable handle, final String resource) {
        if (handle == null) {
            return;
        }
        try {
            handle.close();
        } catch (Exception release) {
            LOGGER.debug("RELEASING HANDLE OF {} REPORTED {}", resource,
                    release.getClass().getSimpleName());
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Step one: the unload.
    // -----------------------------------------------------------------------------------------------

    /**
     * One unload of the transaction master into the backup generation of the execution that owns it.
     *
     * <p>Package-visible so a test in this package can drive it directly. It carries the per-execution
     * state - the read position, the write handle and the record count - and it is built fresh for each
     * execution, which is why that state may live in its fields and why it can never leak between runs.
     *
     * <p>The open, read-loop, status-check, close and abend skeleton, the two-level file-status
     * discipline and the display-then-abend ordering all belong to the shared batch template; none of
     * them is re-implemented here.
     */
    static final class TransactionUnloadProgram extends AbstractCobolStep<Transaction> {

        /** The transaction master being unloaded. */
        private final TransactionScanRepository transactionScanRepository;

        /** The generation this execution writes. */
        private final Path generation;

        /** Read position over the master, in cluster-key sequence. */
        private Iterator<Transaction> unloadCursor;

        /** Handle on the generation being written. */
        private BufferedWriter writer;

        /** Records written, reported once the pass completes. */
        private long recordsUnloaded;

        /**
         * @param meterRegistry the registry the lifecycle is timed on; must not be {@code null}
         * @param clock the clock stamping the lifecycle boundaries; must not be {@code null}
         * @param transactionScanRepository bounded transaction-master scan; must not be {@code null}
         * @param generation the generation to write; must not be {@code null}
         */
        TransactionUnloadProgram(final MeterRegistry meterRegistry, final Clock clock,
                final TransactionScanRepository transactionScanRepository,
                final Path generation) {

            super(LEGACY_CATALOGED_UNLOAD_STEP, meterRegistry, clock);
            this.transactionScanRepository = Objects.requireNonNull(
                    transactionScanRepository, "transactionScanRepository");
            this.generation = Objects.requireNonNull(generation, "generation");
        }

        @Override
        protected void openResources() {
            openResource(DD_UNLOAD_INPUT, () -> {
                this.unloadCursor = new BoundedKeysetIterator<>("", KEYSET_PAGE_SIZE,
                        (cursor, size) -> this.transactionScanRepository
                                .findByTranIdGreaterThanOrderByTranIdAsc(
                                        cursor, Limit.of(size.intValue())),
                        Transaction::getTranId, Comparator.naturalOrder());
                return FileStatus.SUCCESS.getCode();
            });

            openResource(DD_UNLOAD_OUTPUT, () -> {
                this.writer = openForWriting(this.generation);
                return FileStatus.SUCCESS.getCode();
            });
        }

        @Override
        protected Optional<Transaction> readNextRecord() {
            return this.<Transaction>readRecord(DD_UNLOAD_INPUT, () -> {
                if (this.unloadCursor.hasNext()) {
                    return IoResult.of(FileStatus.SUCCESS.getCode(), this.unloadCursor.next());
                }
                return IoResult.endOfFile();
            });
        }

        @Override
        protected void processRecord(final Transaction record) {
            writeRecord(DD_UNLOAD_OUTPUT, () -> {
                // The record image, and with it every offset of the layout, comes from the utility
                // layer; this step renders and never slices.
                final String image = TransactionRecordMapper.toRecord(record);
                requireEncodedWidth(image, UNLOAD_RECORD_LENGTH, DD_UNLOAD_OUTPUT);
                this.writer.write(image);
                this.recordsUnloaded++;
                return FileStatus.SUCCESS.getCode();
            });
        }

        @Override
        protected void closeResources() {
            closeResource(DD_UNLOAD_OUTPUT, () -> {
                this.writer.flush();
                this.writer.close();
                return FileStatus.SUCCESS.getCode();
            });

            // The input side holds nothing open: the cursor iterates an already-materialised sequence.
            this.unloadCursor = null;

            LOGGER.info("{} UNLOADED {} RECORD(S) OF {} BYTE(S) TO {}",
                    LEGACY_CATALOGED_UNLOAD_STEP, this.recordsUnloaded, UNLOAD_RECORD_LENGTH,
                    DD_UNLOAD_OUTPUT);
        }

        @Override
        protected void releaseResources() {
            releaseQuietly(this.writer, DD_UNLOAD_OUTPUT);
        }

        /**
         * Records this pass wrote, for a caller that drives the lifecycle directly.
         *
         * @return the count, never negative
         */
        long recordsUnloaded() {
            return this.recordsUnloaded;
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Step two: the record-inclusion filter and the ordering.
    // -----------------------------------------------------------------------------------------------

    /**
     * One pass of the legacy external sort: read the generation the unload step wrote, admit the records
     * whose processing date falls inside the inclusive window, order them by card number ascending under
     * the zoned-decimal typing this job declares, and write the filtered generation.
     *
     * <p><strong>{@code SORTIN} is the unload, not the transaction master.</strong>
     * {@code app/proc/TRANREPT.prc} declares {@code SORTIN DD DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)} -
     * the generation the immediately preceding step wrote - so the report and the backup describe one and
     * the same set of records by construction. Re-reading the master here would let the two disagree
     * whenever a row is posted between the two steps, which makes the report's own input a moving target
     * and its figures unreconcilable against the archive taken beside it. The generation is additionally
     * this execution's private snapshot, so nothing outside the job can change it mid-pass. Recorded in
     * {@code docs/decision-log.md} DL-276.
     *
     * <p><strong>The pass is bounded.</strong> One record image is held at a time - the reader is a
     * fixed-stride reader over the generation, never a materialised list of entities - and the only
     * accumulation is the sorter's, which spills to disk. An ordering pass still cannot write its first
     * record until it has read its last, but the memory it costs to reach that point no longer grows with
     * the size of the window. All of it is per-execution state on a per-execution object, never a field of
     * a singleton.
     *
     * <p>Two properties of this class are the parity core of the file and must not be relaxed. The
     * predicate is <strong>inclusive at both ends</strong> and is evaluated by the single shared window
     * predicate, applied to every record the generation holds, so the window the emitted generation
     * reflects is the shared predicate's window and nothing else. The ordering applies
     * {@link TransactionReportJobConfig#CARD_NUMBER_ZONED_DECIMAL_ASCENDING}, which is private to the
     * enclosing class precisely so it cannot be shared with the job that types the same field as
     * character.
     *
     * <p>The excluded count is now counted rather than inferred: it is the records of the unloaded
     * generation the window did not admit, which is the figure the legacy sort reported for the same run.
     */
    static final class FilterAndOrderProgram extends AbstractCobolStep<String> {

        /** The frozen generation the unload step wrote, which is this step's whole input. */
        private final Path unloadedGeneration;

        /** The filtered, ordered generation this execution writes. */
        private final Path filteredGeneration;

        /** The inclusive processing-date window the predicate tests against. */
        private final JobParameterValidators.ReportDateWindow window;

        /** Position over the unloaded generation, one record at a time and never a materialised list. */
        private BufferedReader sortInput;

        /** Disk-backed bounded work area, standing in for the sort utility's work datasets. */
        private ExternalStringSorter sorter;

        /** Handle on the generation being written. */
        private BufferedWriter writer;

        /** Records the unloaded generation held outside the window, reported once the pass completes. */
        private long recordsExcluded;

        /** Records the window admitted and the sorter emitted. */
        private long recordsIncluded;

        /** Records the unloaded generation held, which is what the two figures are taken from. */
        private long recordsRead;

        /**
         * @param meterRegistry the registry the lifecycle is timed on; must not be {@code null}
         * @param clock the clock stamping the lifecycle boundaries; must not be {@code null}
         * @param unloadedGeneration the frozen generation the unload step wrote; must not be
         *                           {@code null}
         * @param filteredGeneration the generation to write; must not be {@code null}
         * @param window the inclusive processing-date window; must not be {@code null}
         */
        FilterAndOrderProgram(final MeterRegistry meterRegistry, final Clock clock,
                final Path unloadedGeneration,
                final Path filteredGeneration,
                final JobParameterValidators.ReportDateWindow window) {

            super(TransactionReportProcessor.LEGACY_SORT_STEP, meterRegistry, clock);
            this.unloadedGeneration = Objects.requireNonNull(unloadedGeneration,
                    "unloadedGeneration");
            this.filteredGeneration = Objects.requireNonNull(filteredGeneration,
                    "filteredGeneration");
            this.window = Objects.requireNonNull(window, "window");
        }

        @Override
        protected void openResources() {
            openResource(DD_SORT_INPUT, () -> {
                // SORTIN IS THE UNLOAD, NOT THE LIVE TABLE. app/proc/TRANREPT.prc declares
                // SORTIN DD DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(+1) - the generation the preceding step
                // wrote - so the report and the backup describe one and the same set of records. Reading
                // the master again would let the two disagree whenever a row is posted between the steps,
                // and the report's own input would then be a moving target. The generation is also this
                // execution's private snapshot, so nothing outside the job can change it mid-pass.
                this.sortInput = FixedWidthFlatFileReaderFactory
                        .fixedWidthReader(this.unloadedGeneration, UNLOAD_RECORD_LENGTH);
                return FileStatus.SUCCESS.getCode();
            });

            openResource(DD_SORT_OUTPUT, () -> {
                this.writer = openForWriting(this.filteredGeneration);
                this.sorter = new ExternalStringSorter(
                        CARD_NUMBER_ZONED_DECIMAL_ASCENDING,
                        ExternalStringSorter.DEFAULT_RECORDS_PER_RUN);
                return FileStatus.SUCCESS.getCode();
            });
        }

        @Override
        protected Optional<String> readNextRecord() {
            return this.<String>readRecord(DD_SORT_INPUT, () -> {
                // One record is held at a time. The work area beyond it is the sorter's, which spills to
                // disk, so the pass is bounded whatever the generation's size.
                //
                // THE RECORD IS COUNTED WHERE IT IS ACCOUNTED FOR, which is processRecord and not here.
                // Counting in both places double-counts every record, and the read figure is the one the
                // excluded figure is taken from - so a double count there silently doubles the reported
                // input of the step as well.
                final String image = this.sortInput.readLine();
                if (image == null) {
                    return IoResult.endOfFile();
                }
                return IoResult.of(FileStatus.SUCCESS.getCode(), image);
            });
        }

        @Override
        protected void processRecord(final String image) {
            // The record arrives as the image the unload step wrote, which is the form the sort utility
            // read and the form the sorter orders. Nothing is parsed and nothing is re-rendered: the
            // offsets belong to the layout owner and this step neither slices nor reassembles.
            requireEncodedWidth(image, UNLOAD_RECORD_LENGTH, DD_SORT_INPUT);
            this.recordsRead++;

            // THE INCLUDE COND OF THE LEGACY SORT, evaluated here because here is where the sort utility
            // evaluated it: over the records of SORTIN, one at a time. BOTH BOUNDS ARE INCLUSIVE and the
            // shared predicate is the authority. A record outside the window is excluded rather than
            // refused - exclusion is what the condition is for.
            final String processingDate = processingDateSortField(image);
            if (!this.window.includes(processingDate)) {
                this.recordsExcluded++;
                return;
            }
            this.sorter.add(image);
        }

        @Override
        protected void closeResources() {
            closeResource(DD_SORT_INPUT, () -> {
                if (this.sortInput != null) {
                    this.sortInput.close();
                    this.sortInput = null;
                }
                return FileStatus.SUCCESS.getCode();
            });

            this.recordsIncluded = this.sorter.writeTo(this::writeOrderedRecord);

            closeResource(DD_SORT_OUTPUT, () -> {
                this.writer.flush();
                this.writer.close();
                return FileStatus.SUCCESS.getCode();
            });

            LOGGER.info("{} READ {} RECORD(S) FROM {}, INCLUDED {} AND EXCLUDED {} FOR THE WINDOW {}"
                            + " TO {}, ORDERED BY {} AT POSITION {} FOR {} BYTE(S) ASCENDING",
                    TransactionReportProcessor.LEGACY_SORT_STEP, this.recordsRead, DD_SORT_INPUT,
                    this.recordsIncluded, this.recordsExcluded, this.window.startDate(),
                    this.window.endDate(), FIELD_TRAN_CARD_NUM, CARD_NUMBER_SORT_POSITION,
                    CARD_NUMBER_SORT_LENGTH);
        }

        @Override
        protected void releaseResources() {
            // Every handle this lifecycle opened, released on every failure path as well as the normal
            // one. The reader is included precisely because a read that abends leaves it open otherwise.
            releaseQuietly(this.sortInput, DD_SORT_INPUT);
            releaseQuietly(this.writer, DD_SORT_OUTPUT);
            releaseQuietly(this.sorter, "SORTWK");
            this.sortInput = null;
        }

        private void writeOrderedRecord(final String ordered) {
            writeRecord(DD_SORT_OUTPUT, () -> {
                requireEncodedWidth(ordered, UNLOAD_RECORD_LENGTH, DD_SORT_OUTPUT);
                this.writer.write(ordered);
                return FileStatus.SUCCESS.getCode();
            });
        }

        /**
         * Applies an action to each record of the ordered result, in emission order, one at a time.
         *
         * <p>Bounded by construction: the traversal holds the record it is presenting and nothing
         * else, so a caller that wants a count, a width proof or a sequence check pays for one record
         * rather than for the whole generation.
         *
         * @param consumer the action to apply to each record image; must not be {@code null}
         * @throws NullPointerException if {@code consumer} is {@code null}
         */
        void forEachOrderedRecord(final Consumer<String> consumer) {
            Objects.requireNonNull(consumer, "consumer");
            try (BufferedReader ordered = FixedWidthFlatFileReaderFactory
                    .fixedWidthReader(this.filteredGeneration, UNLOAD_RECORD_LENGTH)) {
                String image = ordered.readLine();
                while (image != null) {
                    consumer.accept(image);
                    image = ordered.readLine();
                }
            } catch (final IOException failure) {
                throw new UncheckedIOException(
                        "unable to read the filtered transaction-report generation", failure);
            }
        }

        /**
         * Records the unloaded generation held outside the window, for a caller driving the lifecycle
         * directly.
         *
         * @return the count, never negative
         */
        long recordsExcluded() {
            return this.recordsExcluded;
        }

        /**
         * Records the unloaded generation held, which is the sum of the included and excluded figures.
         *
         * @return the count, never negative
         */
        long recordsRead() {
            return this.recordsRead;
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Step three: the report emission.
    // -----------------------------------------------------------------------------------------------

    /**
     * One report emission: accept the date-parameter record, delegate one whole report to the report
     * stage, and write every record of it at the declared report record length.
     *
     * <p>The date-parameter record is read once, which is what the report program does with its
     * assigned sequential parameter file - a single read, after which the driving loop begins. Here the
     * driving loop is inside the report generator, so this lifecycle's read loop delivers that one
     * record and then reports end of file.
     *
     * <p><strong>No arithmetic happens here.</strong> The page totals, the account totals and the grand
     * total are the generator's, and the grand total it reports was reached only by way of page totals.
     * This class logs those figures and computes none of them.
     */
    static final class ReportEmitProgram extends AbstractCobolStep<ReportTransactionInput> {

        /** The report stage this lifecycle delegates one whole report to. */
        private final TransactionReportProcessor reportProcessor;

        /** Reader over the filtered, ordered generation produced by the preceding step. */
        private final FlatFileItemReader<Transaction> transactionReader;

        /**
         * The validated date-parameter record image, or {@code null} for an empty parameter dataset.
         *
         * <p>Absent is a legitimate state rather than a defect: the legacy read of an empty parameter
         * dataset reports end of file, the driving loop then never iterates and the run produces no
         * report record at all. A launch cannot reach this state, because the enclosing configuration
         * refuses an absent bound before a lifecycle is built - but the lifecycle models it, because
         * that is what the program it stands in for does.
         */
        private final String dateParameterCard;

        /** The report generation this execution writes. */
        private final Path reportGeneration;

        /** Whether the parameter record has already been served to the read loop. */
        private boolean parameterRecordServed;

        /** Handle on the generation being written. */
        private BufferedWriter writer;

        /** The report the stage produced, retained for the completion diagnostic. */
        private TransactionReportResult result;

        /** Records written, reported once the pass completes. */
        private long recordsWritten;

        /**
         * @param meterRegistry the registry the lifecycle is timed on; must not be {@code null}
         * @param clock the clock stamping the lifecycle boundaries; must not be {@code null}
         * @param readerFactory source of the fixed-width transaction reader; must not be {@code null}
         * @param reportProcessor the report stage; must not be {@code null}
         * @param dateParameterCard the validated parameter record, or {@code null} for an empty
         *                          parameter dataset
         * @param filteredGeneration the filtered, ordered generation to snapshot
         * @param reportGeneration the report generation to write; must not be {@code null}
         */
        ReportEmitProgram(final MeterRegistry meterRegistry, final Clock clock,
                final FixedWidthFlatFileReaderFactory readerFactory,
                final TransactionReportProcessor reportProcessor, final String dateParameterCard,
                final Path filteredGeneration, final Path reportGeneration) {

            super(TransactionReportProcessor.LEGACY_REPORT_STEP, meterRegistry, clock);
            Objects.requireNonNull(readerFactory, "readerFactory");
            this.reportProcessor = Objects.requireNonNull(reportProcessor, "reportProcessor");
            this.dateParameterCard = dateParameterCard;
            this.transactionReader = readerFactory.fixedTransactionReader(
                    new PathResource(Objects.requireNonNull(filteredGeneration,
                            "filteredGeneration")));
            this.reportGeneration = Objects.requireNonNull(reportGeneration, "reportGeneration");
        }

        @Override
        protected void openResources() {
            openResource(TransactionReportProcessor.LEGACY_DD_DATEPARM,
                    () -> FileStatus.SUCCESS.getCode());

            openResource(DD_REPORT_INPUT, () -> {
                this.transactionReader.open(new ExecutionContext());
                return FileStatus.SUCCESS.getCode();
            });

            openResource(TransactionReportProcessor.LEGACY_DD_TRANREPT, () -> {
                this.writer = openForWriting(this.reportGeneration);
                return FileStatus.SUCCESS.getCode();
            });
        }

        @Override
        protected Optional<ReportTransactionInput> readNextRecord() {
            return this.<ReportTransactionInput>readRecord(
                    TransactionReportProcessor.LEGACY_DD_DATEPARM, () -> {
                if (this.parameterRecordServed || this.dateParameterCard == null) {
                    return IoResult.endOfFile();
                }
                this.parameterRecordServed = true;
                return IoResult.of(FileStatus.SUCCESS.getCode(),
                        new ReportTransactionInput(this.dateParameterCard,
                                sequentialTransactionSource(), this::writeReportRecord));
            });
        }

        @Override
        protected void processRecord(final ReportTransactionInput input) {
            // Every report record reached the generation through the sink this lifecycle handed the
            // stage, as the stage composed it. There is no list to expand here, which is the whole
            // point: the report is bounded by one record rather than by its own length.
            this.result = Objects.requireNonNull(this.reportProcessor.process(input),
                    TransactionReportProcessor.LEGACY_PROGRAM
                            + " reported no report for the parameter record it was given");
        }

        /**
         * Writes one composed report record to the generation at the declared record length.
         *
         * <p>Proved again at the destination: the stage proves what it hands on, and this proves what
         * actually reaches the generation the record length is declared for.
         *
         * @param reportRecord the composed record, exactly {@value #REPORT_RECORD_LENGTH} bytes
         */
        private void writeReportRecord(final String reportRecord) {
            writeRecord(TransactionReportProcessor.LEGACY_DD_TRANREPT, () -> {
                requireEncodedWidth(reportRecord, REPORT_RECORD_LENGTH,
                        TransactionReportProcessor.LEGACY_DD_TRANREPT);
                this.writer.write(reportRecord);
                this.recordsWritten++;
                return FileStatus.SUCCESS.getCode();
            });
        }

        /**
         * One forward walk over the filtered generation this step reads.
         *
         * <p>Sequential and forward-only, which is exactly how the report program reads its input: the
         * position it asks for advances by one for every record it consumes and it never returns to a
         * position it has left. The generation is read one record at a time rather than copied into
         * the heap first, so a run's memory is bounded by the record width and not by the number of
         * records the date range admitted. The generation is written by the preceding step and is not
         * touched again, so what this walk serves is as frozen as a copy would have been - and it
         * cannot observe a change to the transaction master, because it never queries it. See
         * {@code docs/decision-log.md} entry DL-176.
         *
         * @return the forward source, never {@code null}
         */
        private ReportTransactionSource sequentialTransactionSource() {
            return new SequentialGenerationWalk(this::readNextFilteredRecord);
        }

        /**
         * Reads the next record of the filtered generation through this lifecycle's own read gate.
         *
         * <p>Routed through the gate rather than straight at the reader so that a technical failure on
         * any record - not only on the first - is normalised into this member's read status and
         * reported under the input data definition, exactly as it was when the whole generation was
         * drained here in one pass.
         *
         * @return the next record, or empty at end of file
         */
        private Optional<Transaction> readNextFilteredRecord() {
            return this.<Transaction>readRecord(DD_REPORT_INPUT, () -> {
                final Transaction transaction = this.transactionReader.read();
                if (transaction == null) {
                    return IoResult.endOfFile();
                }
                return IoResult.of(FileStatus.SUCCESS.getCode(), transaction);
            });
        }

        @Override
        protected void closeResources() {
            closeResource(TransactionReportProcessor.LEGACY_DD_TRANREPT, () -> {
                this.writer.flush();
                this.writer.close();
                return FileStatus.SUCCESS.getCode();
            });

            closeResource(DD_REPORT_INPUT, () -> {
                this.transactionReader.close();
                return FileStatus.SUCCESS.getCode();
            });

            closeResource(TransactionReportProcessor.LEGACY_DD_DATEPARM,
                    () -> FileStatus.SUCCESS.getCode());

            if (this.result == null) {
                // The parameter dataset delivered nothing, which the legacy read reports as end of
                // file: the driving loop never iterates and no report record exists. Not a failure.
                LOGGER.info("{} WROTE NO RECORD TO {} BECAUSE {} DELIVERED NO PARAMETER RECORD",
                        TransactionReportProcessor.LEGACY_REPORT_STEP,
                        TransactionReportProcessor.LEGACY_DD_TRANREPT,
                        TransactionReportProcessor.LEGACY_DD_DATEPARM);
                return;
            }

            LOGGER.info("{} WROTE {} RECORD(S) OF {} BYTE(S) TO {} OVER {} PAGE(S) AND {} ACCOUNT"
                            + " BREAK(S), READING ITS RANGE THROUGH {}",
                    TransactionReportProcessor.LEGACY_REPORT_STEP, this.recordsWritten,
                    REPORT_RECORD_LENGTH, TransactionReportProcessor.LEGACY_DD_TRANREPT,
                    this.result.pageCount(), this.result.accountBreakCount(), DD_REPORT_INPUT);
        }

        @Override
        protected void releaseResources() {
            releaseQuietly(this.writer, TransactionReportProcessor.LEGACY_DD_TRANREPT);
            releaseQuietly(this.transactionReader::close, DD_REPORT_INPUT);
        }

        /**
         * The report this pass produced, for a caller that drives the lifecycle directly.
         *
         * @return the report, or {@code null} when the parameter dataset delivered nothing
         */
        TransactionReportResult result() {
            return this.result;
        }

        /**
         * Records this pass wrote, for a caller that drives the lifecycle directly.
         *
         * @return the count, never negative
         */
        long recordsWritten() {
            return this.recordsWritten;
        }
    }
}
