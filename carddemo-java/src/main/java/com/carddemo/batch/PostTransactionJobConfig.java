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
import com.carddemo.batch.step.RejectRecordWriter;
import com.carddemo.batch.step.StagedGenerationStore;
import com.carddemo.batch.step.TransactionValidationProcessor;
import com.carddemo.domain.DailyTransaction;
import com.carddemo.domain.enums.RejectReason;
import com.carddemo.service.BatchJobCatalog;
import com.carddemo.exception.AbendException;
import com.carddemo.service.TransactionPostingService;
import com.carddemo.util.SecureStagedFiles;
import com.carddemo.util.StagedResourceNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.PathResource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The daily transaction posting job: one chunk-oriented step over the sequential daily-transaction
 * input, refusing what the validation cascade refuses and posting the rest.
 *
 * <h2>Provenance</h2>
 *
 * <p>Translated from the legacy estate at checkout commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy antecedents are the job stream
 * {@code app/jcl/POSTTRAN.jcl}, the program it runs, {@code app/cbl/CBTRN02C.cbl}, and
 * {@code app/jcl/DALYREJS.jcl}, which defines the generation group the reject dataset is written into.
 * Every figure stated below was measured by direct read of those three members and is a contract rather
 * than guidance.
 *
 * <p><strong>No COBOL, JCL or utility statement text is reproduced anywhere in this file.</strong> What
 * is carried across is confined to what a contract needs: step and data-definition names, dataset and
 * generation-group names, record lengths, condition-code semantics, completion codes and reject codes.
 * Nothing here reads any legacy artefact at run time, and the legacy tree is neither modified nor
 * copied.
 *
 * <h2>The measured job stream: one step, no gate, no parameter string</h2>
 *
 * <p>The job member is forty-five lines and declares <strong>exactly one application step</strong>,
 * named {@value TransactionValidationProcessor#LEGACY_STEP}, which runs
 * {@value TransactionPostingService#PROGRAM_NAME}. It carries <strong>no condition-code gate</strong>
 * and <strong>no parameter string</strong>. Three absences therefore follow, and each of them is the
 * shape of the job rather than a gap in it:
 *
 * <ul>
 *   <li>the job holds <strong>one</strong> step, so there is no sequence to gate;</li>
 *   <li>it declares <strong>no failure-ending flow transition</strong>, because the member holds no
 *       condition-code dependency to reproduce;</li>
 *   <li>it requires <strong>no date, mode or any other job parameter</strong>, because the member
 *       passes no parameter string to the program.</li>
 * </ul>
 *
 * <p>Filling any of the three in would be a behavioural invention. The job is also chained to nothing
 * and reached by no composite flow: the estate has no master orchestrator, and the order in which the
 * batch members were submitted was an operational convention rather than a declared pipeline.
 *
 * <h2>The six data definitions and what each becomes</h2>
 *
 * <table border="1">
 *   <caption>Measured data definitions of the single application step</caption>
 *   <tr><th>Definition</th><th>Direction</th><th>Legacy resource</th><th>Target</th></tr>
 *   <tr><td>{@value TransactionPostingService#TRANFILE_DD}</td><td>in and out</td>
 *       <td>transaction master, key-sequenced</td><td>the transaction repository</td></tr>
 *   <tr><td>{@value TransactionPostingService#DALYTRAN_DD}</td><td><strong>input, sequential</strong>
 *       </td><td>daily-transaction sequential dataset</td>
 *       <td>a fixed-width reader from {@link FixedWidthFlatFileReaderFactory}</td></tr>
 *   <tr><td>{@value TransactionPostingService#XREFFILE_DD}</td><td>input</td>
 *       <td>card cross-reference, key-sequenced</td><td>the cross-reference repository</td></tr>
 *   <tr><td>{@value TransactionPostingService#ACCTFILE_DD}</td><td>in and out</td>
 *       <td>account master, key-sequenced</td><td>the account repository</td></tr>
 *   <tr><td>{@value TransactionPostingService#TCATBALF_DD}</td><td>in and out</td>
 *       <td>transaction category balance, key-sequenced</td>
 *       <td>the category-balance repository</td></tr>
 *   <tr><td>{@value TransactionPostingService#DALYREJS_DD}</td><td><strong>output</strong></td>
 *       <td>reject dataset, a new generation</td><td>{@link RejectRecordWriter}</td></tr>
 * </table>
 *
 * <p>Only the sequential input and the reject output are wired into this step. The four key-sequenced
 * resources are reached by key, one record at a time, from inside {@link TransactionPostingService},
 * which is where the legacy program reached them from; a keyed read is not a sequential scan and does
 * not become a reader here.
 *
 * <p>Every resource is resolved from configuration by its logical name. <strong>No filesystem path is
 * written into this file.</strong> The staging directory is configurable and the two dataset names
 * default to the names the job stream itself used, so relocating either is a configuration change and
 * never a code change.
 *
 * <h2>The reject dataset: fixed length, unblocked, and a new generation each run</h2>
 *
 * <p>The reject definition is measured as fixed-length <strong>unblocked</strong> with a record length
 * of {@value RejectRecordWriter#REJECT_RECORD_LENGTH} and no block size. Unblocked, not blocked: there
 * is therefore <strong>no block padding</strong>, and the produced artefact is an exact multiple of
 * {@value RejectRecordWriter#REJECT_RECORD_LENGTH} bytes with nothing appended - no record separator,
 * no trailing terminator and no fill to a block boundary. A fixture or an assertion over this dataset
 * divides its encoded length by the record length and expects no remainder.
 *
 * <p>The destination is a <strong>new generation</strong> of a generation group whose definition is
 * measured as a limit of five generations with the generation that rolls off being scratched. Two
 * consequences are reproduced and one is deliberately not:
 *
 * <ul>
 *   <li><em>reproduced</em> - each job execution writes its own distinct generation, named in the
 *       legacy absolute-generation form, so no execution can overwrite another's rejects;</li>
 *   <li><em>reproduced</em> - the generation is created whether or not anything was rejected, because
 *       the legacy allocation catalogued the dataset unconditionally, so a clean run leaves an empty
 *       artefact rather than none at all;</li>
 *   <li><em>not reproduced here</em> - <strong>retention is not implemented.</strong> The depth of a
 *       generation group and the scratching of what rolls off are properties of the group, which the
 *       environment owns, and not of the job that writes one generation into it.</li>
 * </ul>
 *
 * <p>One recorded anomaly, for the reader who opens the generation-group member expecting to find a
 * delete: its comment banner announces the deletion of a key-sequenced transaction-master file while
 * its control stream only defines the generation group, and the banner also misspells one word. The
 * defect is recorded and <strong>never reproduced</strong>; the group's provisioning intent is served
 * by the module's schema migrations and by its container stack.
 *
 * <h2>Correction carried forward: the reject record is 430 bytes and there is no conflicting 500</h2>
 *
 * <p>The record is {@value TransactionPostingService#SOURCE_IMAGE_LENGTH} plus
 * {@value TransactionPostingService#VALIDATION_TRAILER_LENGTH}, that is
 * {@value RejectRecordWriter#REJECT_RECORD_LENGTH} bytes: the source record image, followed by a
 * failure trailer composed of a {@value TransactionPostingService#FAIL_REASON_LENGTH}-digit numeric
 * reason code and a {@value TransactionPostingService#FAIL_REASON_DESCRIPTION_LENGTH}-character
 * description. The width is verified three ways - in the program's file-description area at lines 81 to
 * 84, in its working-storage area at lines 176 to 182, and on the data definition's record length.
 *
 * <p><strong>Planning material claims a conflict between a 500-byte allocation and a 430-byte emitted
 * record. That claim is not supported by the source.</strong> An executed search of the program found
 * the 350-and-80 split in the file description, the same split in working storage and the total on the
 * data definition, and found <strong>no 500 anywhere</strong> - the only matches for those digits are
 * paragraph labels. So {@value RejectRecordWriter#REJECT_RECORD_LENGTH} is implemented, and the alleged
 * conflict is recorded as searched for and <strong>not found</strong> rather than logged as an anomaly
 * that does not exist.
 *
 * <p>Every width on this path is measured in <strong>encoded bytes</strong> and never in character
 * count. The widths themselves belong to {@link RejectRecordWriter} and to the record mappers, so no
 * offset and no field width is restated in this file: this configuration composes a reader and a
 * writer, and never slices a record itself.
 *
 * <h2>The five reject reason codes</h2>
 *
 * <p>These literals appear byte for byte inside the reject record, which makes them contract
 * <em>data</em> rather than statement text. Each description is space-padded to the description field's
 * width by the writer that emits it:
 *
 * <table border="1">
 *   <caption>The reject reason codes and their description literals</caption>
 *   <tr><th>Code</th><th>Description literal</th><th>Raised where</th></tr>
 *   <tr><td>100</td><td>{@link RejectReason#INVALID_CARD_NUMBER}</td>
 *       <td>the cross-reference lookup missed</td></tr>
 *   <tr><td>101</td><td>{@link RejectReason#ACCOUNT_NOT_FOUND_ON_READ}</td>
 *       <td>the account lookup missed</td></tr>
 *   <tr><td>102</td><td>{@link RejectReason#OVERLIMIT_TRANSACTION}</td>
 *       <td>the cycle total would exceed the credit limit</td></tr>
 *   <tr><td>103</td><td>{@link RejectReason#TRANSACTION_AFTER_ACCOUNT_EXPIRATION}</td>
 *       <td>the transaction originated after the account expired</td></tr>
 *   <tr><td>109</td><td>{@link RejectReason#ACCOUNT_NOT_FOUND_ON_REWRITE}</td>
 *       <td>the account rewrite reported an invalid key</td></tr>
 * </table>
 *
 * <p><strong>101 and 109 carry identical text and are distinct codes. They must never be merged.</strong>
 * The reason code and its description are reset to zero and to spaces at the top of every record's
 * validation, and the posting mainline proceeds <strong>only</strong> while the reason code is zero.
 *
 * <h2>The cascade order, which is contractual</h2>
 *
 * <p>The cascade itself lives in {@link TransactionPostingService} and
 * {@link TransactionValidationProcessor}. It is stated here because this configuration must not defeat
 * any part of it, and because a reader of the job is entitled to find the contract at the job level:
 *
 * <ol>
 *   <li>code 100 is raised on the cross-reference miss and <strong>short-circuits the account lookup
 *       entirely</strong>;</li>
 *   <li>code 101 is raised on the account miss;</li>
 *   <li>codes 102 and 103 are <strong>two consecutive, unguarded checks</strong>. The overlimit test
 *       runs, then the expiry test runs immediately afterwards with no guard on the first result, so
 *       <strong>103 overwrites 102 when both conditions fail</strong>. That overwrite is contractual:
 *       it is reproduced, and the second check is never guarded;</li>
 *   <li>the overlimit basis is the current-cycle credit <em>minus</em> the current-cycle debit
 *       <em>plus</em> the transaction amount, evaluated <strong>strictly left to right</strong> into a
 *       two-decimal field, and the credit limit is compared against that computed value. The
 *       expression is <strong>never rearranged algebraically</strong>: with truncating arithmetic it is
 *       not associative, so a rearrangement would change which transactions are refused;</li>
 *   <li>the expiry check is a <strong>lexicographic string comparison</strong> of the ten-character
 *       account expiration date against the <strong>first ten characters</strong> of the transaction's
 *       origination timestamp. Neither side is converted to a date type and no date-time comparison is
 *       used, because string comparison is the measured behaviour. The legacy field name for the
 *       expiration date is misspelled in its copybook; the Java property is spelled correctly while
 *       the record offset is unchanged;</li>
 *   <li><strong>code 109 is inert.</strong> It is set on the failure arm of the account rewrite, which
 *       is reached only after the mainline has already branched on a zero reason code and after the
 *       category balance has been committed, and the transaction write still proceeds. It therefore
 *       <strong>never yields a reject record</strong>. It is reproduced as a genuine but
 *       unreachable-for-reject branch, and it is documented as such so that a later reader does not
 *       "fix" it into an active reject.</li>
 * </ol>
 *
 * <h2>Sign handling, posting order and the two timestamp forms</h2>
 *
 * <p>The transaction amount is added to the debit accumulator <strong>unchanged</strong>. The
 * operator-originated returns in the estate's own sample data are negative, so they drive that
 * accumulator negative, and that is correct: the amount is never negated, never taken in absolute
 * value, and never branched on by sign to change its magnitude.
 *
 * <p>The three persistence stages run in exactly this order - <strong>category balance, then account,
 * then transaction file</strong> - and the category-balance stage treats a not-found condition as
 * <strong>non-error</strong> and creates the row. The order is not rearranged and the missing row is
 * not treated as a failure.
 *
 * <p>The two twenty-six-character timestamp forms are never confused. The <strong>origination</strong>
 * timestamp is copied <strong>verbatim</strong> from the input record: never regenerated, never
 * reparsed, never reformatted. The <strong>processing</strong> timestamp is regenerated in the
 * <strong>batch</strong> form, which places hyphens at the fifth, eighth and <em>eleventh</em>
 * positions, dots at the fourteenth, seventeenth and twentieth, a two-digit hundredths field, and then
 * a fixed four-character tail. The <strong>online</strong> form - a space in the eleventh position,
 * colons between the time parts, a period before a six-digit fraction - must <strong>never</strong> be
 * emitted from a batch step: it breaks byte parity silently and no compiler catches it. The formatter
 * is nested inside {@code batch/step/AbstractCobolStep} and is obtained from there; this module has one
 * such formatter and no second one is written.
 *
 * <h2>Completion code four is a partial success and never an exception</h2>
 *
 * <p>Measured after the two count diagnostics and before the end-of-run diagnostic: the program sets
 * completion code {@value TransactionPostingService#RETURN_CODE_REJECTS_PRESENT} when the reject count
 * is greater than zero. <strong>That is a partial success, not a failure.</strong> The step and the job
 * both complete, the step's exit status conveys the tolerated code, and <strong>no exception is thrown
 * for a non-zero reject count</strong>. Refusals are verdicts on content; only a technical failure ends
 * the step.
 *
 * <p>The contribution itself belongs to {@link TransactionValidationProcessor#afterStep(StepExecution)},
 * which the step builder registers automatically because the processor implements the listener
 * interface. It is deliberately <strong>not</strong> registered a second time here.
 *
 * <p><strong>The code is this job's own, and it gates nothing outside this job.</strong> Every
 * condition-code step gate in the estate is the one strict form,
 * {@code BatchConfig.ConditionCodeGate.ALL_PRIOR_STEPS_ZERO}, and a gate reads only the steps of the
 * execution it sits inside. So the tolerated code this job reports is an outcome the operator and the
 * job entry system read on <em>this</em> job's own step; it is not a value another job's gate admits,
 * and it must not be re-derived from any gate's ceiling. Its authority is the program itself, which
 * sets it at {@code app/cbl/CBTRN02C.cbl} lines 229 to 230, and it is published once as
 * {@link TransactionPostingService#RETURN_CODE_REJECTS_PRESENT}.
 *
 * <h2>Diagnostics and failure handling</h2>
 *
 * <p>The legacy console channel becomes structured logging. This configuration reports the step's own
 * completion - the records read, the records written to the reject dataset and the terminal batch
 * status - and does not recompute the run counters, whose semantics belong to
 * {@link TransactionValidationProcessor} and are published by it into the step's execution context.
 *
 * <p>On a terminal input or output failure the raw two-character file status is logged <strong>first</strong>
 * and only then is the abend raised; that ordering is contractual. The shared skeleton in
 * {@code batch/step/AbstractCobolStep} owns that sequence and it is <strong>not re-implemented</strong>
 * here. End of file is never collapsed into error: the coarse three-way outcome is nested in that same
 * skeleton, and the raw status vocabulary the estate actually compares lives in
 * {@code domain/enums/FileStatus}. Two further status values are documented there but exercised
 * nowhere, and no code path depends on them.
 *
 * <h2>Execution is strictly sequential</h2>
 *
 * <p>No task executor, no partitioning, no multi-threaded step and no parallel flow. The refusal
 * decision reads a cycle total that earlier records of the same run have already moved, and several
 * fixed-width outputs of this estate are compared byte for byte, so arrival order is part of the
 * result. Concurrency would change it.
 *
 * <p>Nothing launches this job when the context starts. Launch on start is disabled by the shipped
 * configuration document, and this class contributes no runner, no lifecycle participant, no
 * initialising callback, no event listener and no scheduled trigger, and never names a job to run.
 * Publishing the job registers it for launch on demand and nothing more - which is how
 * {@code api/BatchJobController} starts it and asks after it, by the name {@link #JOB_NAME} publishes.
 *
 * <h2>The standards this file is held to</h2>
 *
 * <p>No user-specified rules were provided for this migration, so the work is held to
 * enterprise-standard best practice instead, and the absence is stated rather than filled with invented
 * rules. In prose, and in the order they bear on this file: the build is reproducible and hermetic, with
 * every version pinned and the build tool shipped with the project; compilation is warning-free and a
 * warning fails the build, which is why no deprecated builder factory, no raw type, no unchecked
 * operation and no warning suppression appears here; concerns are layered, so this package may depend on
 * the service, domain, utility, repository, exception and configuration packages and is imported by none
 * of them, and all fixed-width offset knowledge stays in the utility layer; there is no code generation
 * and the reflection budget is zero, which is why collaborators arrive through one constructor and every
 * scoped bean is declared by an interface so that no class is generated for it; secrets never appear in
 * source and are never defaulted, and this file holds none; the schema is owned solely by the migration
 * tool, and the framework's own metadata tables are provisioned by the framework and are never referred
 * to here; the test estate is a pyramid with an enforced line-coverage floor; supply-chain hygiene is a
 * scan executed at verification rather than a plugin merely declared; observability is first class, so
 * the step is timed and published, and <strong>no performance target is stated anywhere</strong> - no
 * throughput, latency, heap, timeout, thread-pool, skip, retry, commit-interval, backoff or connection
 * figure appears in this file, in code or in comment, because the estate documents no service level
 * against which one could be set; licence continuity is preserved by the header above, which is the
 * header every legacy member carries; the translation is fully auditable through the traceability matrix
 * and the decision log, which another agent owns and which this file only raises notes for; and the
 * tie-break that decides every hard case is that <strong>faithful beats idiomatic</strong> - the legacy
 * behaviour wins, and each divergence becomes a decision-log entry.
 *
 * @see TransactionPostingService
 * @see TransactionValidationProcessor
 * @see RejectRecordWriter
 * @see FixedWidthFlatFileReaderFactory
 * @see BackupTransactionJobConfig
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public final class PostTransactionJobConfig {

    /**
     * The legacy program this job runs.
     *
     * <p>Derived from {@link TransactionPostingService#PROGRAM_NAME} rather than restated, so the job,
     * the per-record stage and the translated program can never disagree about which member they came
     * from. It names a program and nothing resolves a path, a dataset or a load module from it.
     */
    public static final String PROGRAM_NAME = TransactionPostingService.PROGRAM_NAME;

    /** The legacy job member translated here, derived rather than restated for the same reason. */
    public static final String LEGACY_JOB = TransactionValidationProcessor.LEGACY_JOB;

    /** The single application step of {@value #LEGACY_JOB}, likewise derived rather than restated. */
    public static final String LEGACY_STEP = TransactionValidationProcessor.LEGACY_STEP;

    /**
     * Stable, discoverable name of the job bean.
     *
     * <p>Published as a constant because the job is launched and queried <strong>by name</strong>
     * through the registry and operator the batch infrastructure exposes, so an unstable name would
     * break that surface rather than fail a compile. The value is fixed by the module and is not a free
     * choice.
     *
     * <p>The value is read from {@code service/BatchJobCatalog}, which is the module's single
     * declaration of the nine stable job names. Both tiers that need a name - this configuration
     * and the operational control surface above it - resolve it from there, so the name exists as
     * one literal and the two cannot drift apart across a boundary the layering keeps closed.
     */
    public static final String JOB_NAME = BatchJobCatalog.POST_TRANSACTION_JOB_NAME;

    /** Stable name of the single chunk-oriented step, which is the whole of the job. */
    public static final String STEP_NAME = "postDailyTransactionsStep";

    /**
     * Bean name of the step-scoped reader over the sequential daily-transaction input.
     *
     * <p>Each of the three collaborator bean names is both the name its factory method is registered
     * under and the qualifier the step selects it by, stated once here so the two cannot drift apart.
     */
    public static final String DAILY_TRANSACTION_READER_BEAN_NAME =
            "postTransactionDailyTransactionReader";

    /** Bean name of the per-record validation and posting stage. */
    public static final String VALIDATION_PROCESSOR_BEAN_NAME = "postTransactionValidationProcessor";

    /** Bean name of the step-scoped reject-dataset writer. */
    public static final String REJECT_RECORD_WRITER_BEAN_NAME = "postTransactionRejectRecordWriter";

    /** Prefix every configuration key of this job carries. */
    public static final String RESOURCE_PROPERTY_PREFIX = "carddemo.batch.post-transaction.";

    /** Key of the directory every logical dataset name of this job is resolved against. */
    public static final String STAGING_DIRECTORY_PROPERTY =
            RESOURCE_PROPERTY_PREFIX + "staging-directory";

    /** Key of the logical name of the sequential daily-transaction input. */
    public static final String DALYTRAN_DATASET_PROPERTY =
            RESOURCE_PROPERTY_PREFIX + "dalytran-dataset";

    /** Key of the logical name of the reject generation group's base. */
    public static final String DALYREJS_DATASET_BASE_PROPERTY =
            RESOURCE_PROPERTY_PREFIX + "dalyrejs-dataset-base";

    /**
     * Logical name of the sequential input, defaulted to the dataset name the job stream itself used.
     *
     * <p>A dataset name is carried across deliberately: an operator looking for the input a run consumed
     * finds it under the name the job stream named. It is a <em>name</em> and not a path - the directory
     * it is resolved against is configured separately - so nothing here fixes a location.
     */
    public static final String DEFAULT_DALYTRAN_DATASET = "AWS.M2.CARDDEMO.DALYTRAN.PS";

    /** Logical base of the reject generation group, defaulted to the base the group is defined under. */
    public static final String DEFAULT_DALYREJS_DATASET_BASE = "AWS.M2.CARDDEMO.DALYREJS";

    /**
     * Commit granularity of the step: one record.
     *
     * <p><strong>This is a legacy semantic and not a tuning figure.</strong> The migrated batch tier is
     * strictly sequential and record at a time, which is the granularity the legacy tier exhibited, and
     * a chunk-oriented step must state some granularity for the framework to commit on. Stating one
     * record keeps the unit of work identical to the unit of processing, so a later row cannot roll back
     * an earlier posting and its reject output. The value is deliberately written into the step rather
     * than exposed as configuration: changing it would change the program's commit semantics, not tune
     * throughput.
     */
    static final int RECORD_AT_A_TIME = 1;

    /** Timer name under which this job's step is recorded, shared with the rest of the batch tier. */
    private static final String STEP_TIMER_NAME = "carddemo.batch.job.step";

    /** Timer description, worded as the rest of the batch tier words it. */
    private static final String STEP_TIMER_DESCRIPTION =
            "Elapsed time of one step of a migrated CardDemo batch job stream";

    /** Tag naming the job a sample belongs to. */
    private static final String TAG_JOB = "job";

    /** Tag naming the step a sample belongs to. */
    private static final String TAG_STEP = "step";

    /** Tag carrying the terminal batch status of the sampled step. */
    private static final String TAG_OUTCOME = "outcome";

    /** Bean name of the shared job-boundary diagnostic the batch infrastructure publishes. */
    private static final String BOUNDARY_LISTENER_BEAN_NAME = "batchJobBoundaryListener";

    /** Bean name of the shared parameter incrementer the batch infrastructure publishes. */
    private static final String RUN_INCREMENTER_BEAN_NAME = "batchJobRunIncrementer";

    /** This configuration's own diagnostic channel, replacing the legacy console display. */
    private static final Logger LOGGER = LoggerFactory.getLogger(PostTransactionJobConfig.class);

    /** The framework's job repository, from the batch auto-configuration. */
    private final JobRepository jobRepository;

    /** The transaction manager the chunk-oriented step commits each unit of work through. */
    private final PlatformTransactionManager transactionManager;

    /** The factory that owns one reader per fixed-width record layout, including the 350-byte input. */
    private final FixedWidthFlatFileReaderFactory readerFactory;

    /** The translated posting program, which owns every business rule this job exercises. */
    private final TransactionPostingService postingService;

    /** The registry this step's timer and the per-record meters are registered on. */
    private final MeterRegistry meterRegistry;

    /** The clock the step's elapsed time is measured against. */
    private final Clock clock;

    /** Directory every logical dataset name of this job is resolved against. */
    private final String stagingDirectory;

    /** Logical name of the sequential daily-transaction input. */
    private final String dalytranDataset;

    /** Logical base of the reject generation group. */
    private final String dalyrejsDatasetBase;

    /**
     * Creates the configuration from the batch infrastructure it builds on, the collaborators the step
     * needs, and the three configured values that resolve its two datasets.
     *
     * <p>Constructor injection only, every field final, and no field injection anywhere: the
     * configuration is fully formed once constructed and holds no mutable state that one job execution
     * could carry into another. The only per-execution state in this file lives in step-scoped beans,
     * which is the one place it is allowed to live.
     *
     * <p>Every configured value has a default, so the job is publishable and launchable without any
     * configuration document naming a key. A default is a <em>logical name</em> or a directory and never
     * a path this class chose: the two dataset names default to the names the legacy job stream used, and
     * the directory defaults to the platform's own temporary location, so a deployment relocates either
     * by configuration and never by a code change. None of the three is a secret and none of them is
     * credential-bearing.
     *
     * @param  jobRepository        the framework's job repository
     * @param  transactionManager   the transaction manager the step commits through
     * @param  readerFactory        the factory that owns the fixed-width record layouts
     * @param  postingService       the translated posting program
     * @param  meterRegistry        the registry the step timer and per-record meters live on
     * @param  clock                the clock the step's elapsed time is measured against
     * @param  stagingDirectory     directory the two logical dataset names are resolved against
     * @param  dalytranDataset      logical name of the sequential daily-transaction input
     * @param  dalyrejsDatasetBase  logical base of the reject generation group
     * @throws NullPointerException     if any collaborator or configured value is {@code null}
     * @throws IllegalArgumentException if either logical name is blank
     */
    public PostTransactionJobConfig(
            final JobRepository jobRepository,
            final PlatformTransactionManager transactionManager,
            final FixedWidthFlatFileReaderFactory readerFactory,
            final TransactionPostingService postingService,
            final MeterRegistry meterRegistry,
            final Clock clock,
            @Value("${" + STAGING_DIRECTORY_PROPERTY + ":${"
                    + StagedGenerationStore.SHARED_STAGING_DIRECTORY_PROPERTY
                    + ":${java.io.tmpdir}}}")
                    final String stagingDirectory,
            @Value("${" + DALYTRAN_DATASET_PROPERTY + ":" + DEFAULT_DALYTRAN_DATASET + "}")
                    final String dalytranDataset,
            @Value("${" + DALYREJS_DATASET_BASE_PROPERTY + ":" + DEFAULT_DALYREJS_DATASET_BASE + "}")
                    final String dalyrejsDatasetBase) {
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository must not be null");
        this.transactionManager =
                Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.readerFactory = Objects.requireNonNull(readerFactory, "readerFactory must not be null");
        this.postingService = Objects.requireNonNull(postingService, "postingService must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.stagingDirectory = requireResourceName(stagingDirectory, STAGING_DIRECTORY_PROPERTY);
        this.dalytranDataset =
                StagedResourceNames.requireSimpleName(dalytranDataset, DALYTRAN_DATASET_PROPERTY);
        this.dalyrejsDatasetBase = StagedResourceNames.requireSimpleName(dalyrejsDatasetBase,
                DALYREJS_DATASET_BASE_PROPERTY);
    }

    // -----------------------------------------------------------------------------------------------
    // The job, and the one step that is the whole of it
    // -----------------------------------------------------------------------------------------------

    /**
     * The posting job: one step, run once, wired into nothing.
     *
     * <p>Built with the current job builder over the injected repository. The superseded builder
     * factories are never used: they are deprecated, and a deprecation warning ends this build.
     *
     * <p><strong>What this job deliberately does not have.</strong> No second step, because the member
     * declares one application step. No failure-ending flow transition, because the member carries no
     * condition-code dependency. No parameter validator and no date or mode parameter, because the member
     * passes no parameter string. No chaining to or from another job and no composite flow, because the
     * estate has no master orchestrator. Each of those four absences is measured, and filling any of them
     * in would be a behavioural invention.
     *
     * <p><strong>What it does have.</strong> The two collaborators the batch infrastructure publishes,
     * attached explicitly on this builder by bean name as every job configuration in this module attaches
     * them: the boundary diagnostic, so a reader of this file can see that the job's start and terminal
     * outcome are announced, and the parameter incrementer, so a resubmission with an identical parameter
     * set runs again - as resubmitting a job member did - and so the job can be advanced to its next
     * instance when it is started by name.
     *
     * <p>Nothing launches this job when the context starts.
     *
     * @param  postDailyTransactionsStep the single step, injected by name so no other step bean can be
     *                                   substituted for it
     * @param  batchJobRunIncrementer    the shared parameter incrementer
     * @param  batchJobBoundaryListener  the shared job-boundary diagnostic
     * @return the job, launchable by {@value #JOB_NAME} and reached by no pipeline
     */
    @Bean(name = JOB_NAME)
    public Job postTransactionJob(
            @Qualifier(STEP_NAME) final Step postDailyTransactionsStep,
            @Qualifier(RUN_INCREMENTER_BEAN_NAME) final JobParametersIncrementer batchJobRunIncrementer,
            @Qualifier(BOUNDARY_LISTENER_BEAN_NAME)
                    final JobExecutionListener batchJobBoundaryListener) {
        return new JobBuilder(JOB_NAME, this.jobRepository)
                .incrementer(batchJobRunIncrementer)
                .listener(batchJobBoundaryListener)
                .start(postDailyTransactionsStep)
                .build();
    }

    /**
     * The single chunk-oriented step: read one record of the sequential input, decide it, and emit a
     * reject record when it was refused.
     *
     * <p>Chunk-oriented rather than a single indivisible pass, because the member is a read loop over a
     * sequential input that writes per record, and a chunk-oriented step is the shape that expresses a
     * read loop with a commit boundary. The three stages map exactly onto the legacy loop:
     *
     * <ul>
     *   <li>the <strong>reader</strong> is the sequential read of the daily-transaction input, and it
     *       hands records over in dataset order, which is the order the legacy physical read returned
     *       them in;</li>
     *   <li>the <strong>processor</strong> is the legacy per-record branch. It resets the reason and its
     *       description, runs the validation cascade and the posting cascade through
     *       {@link TransactionPostingService}, and returns the reject item on the refusing arm and
     *       {@code null} on the posting arm. Returning {@code null} filters the item, so no reject record
     *       is emitted for a posted record - which is precisely what the legacy branch did;</li>
     *   <li>the <strong>writer</strong> emits the fixed-length reject record and nothing else. The
     *       posting path itself is not a writer: the legacy program persisted a posted record inside its
     *       own per-record work, which is where {@link TransactionPostingService} persists it, so the two
     *       arms of the legacy branch compose here as a filtering processor over a reject-only writer.</li>
     * </ul>
     *
     * <p><strong>No business rule is implemented in this class.</strong> Every rule - the cascade order,
     * the unguarded overwrite of 102 by 103, the left-to-right overlimit expression, the lexicographic
     * expiry comparison, the inert 109, the unchanged sign, the three-stage posting order and both
     * timestamp forms - belongs to the translated program and to the per-record stage. This method wires
     * them together and must not defeat any of them.
     *
     * <p><strong>Execution is strictly sequential.</strong> No task executor, no partitioning, no
     * multi-threaded step and no parallel flow is attached. The refusal decision reads a cycle total that
     * earlier records of the same run have already moved, and fixed-width outputs of this estate are
     * compared byte for byte, so arrival order is part of the result.
     *
     * <p>The commit granularity is the semantic constant {@link #RECORD_AT_A_TIME}. It is not configurable:
     * each source record and its posting or reject result is one independent unit of work.
     *
     * <p>The completion-code contribution is <strong>not</strong> registered here. The per-record stage
     * implements the step-execution listener interface, and the step builder registers a reader,
     * processor or writer that does so automatically; registering it a second time would invoke it twice
     * and report the run twice. The one listener this method attaches is this configuration's own step
     * diagnostic and timer, which observes and never alters a verdict.
     *
     * @param  postTransactionDailyTransactionReader the step-scoped sequential reader, by name
     * @param  postTransactionValidationProcessor    the per-record validation and posting stage, by name
     * @param  postTransactionRejectRecordWriter     the step-scoped reject writer, by name
     * @return the step, named {@value #STEP_NAME}
     */
    @Bean(name = STEP_NAME)
    public Step postDailyTransactionsStep(
            @Qualifier(DAILY_TRANSACTION_READER_BEAN_NAME)
                    final ItemStreamReader<DailyTransaction> postTransactionDailyTransactionReader,
            @Qualifier(VALIDATION_PROCESSOR_BEAN_NAME)
                    final TransactionValidationProcessor postTransactionValidationProcessor,
            @Qualifier(REJECT_RECORD_WRITER_BEAN_NAME)
                    final ItemStreamWriter<RejectRecordWriter.RejectedTransaction>
                            postTransactionRejectRecordWriter) {
        return new StepBuilder(STEP_NAME, this.jobRepository)
                .<DailyTransaction, RejectRecordWriter.RejectedTransaction>chunk(
                        RECORD_AT_A_TIME, this.transactionManager)
                .reader(postTransactionDailyTransactionReader)
                .processor(postTransactionValidationProcessor)
                .writer(postTransactionRejectRecordWriter)
                .listener(postingStepDiagnostics())
                .build();
    }

    // -----------------------------------------------------------------------------------------------
    // The three step collaborators
    // -----------------------------------------------------------------------------------------------

    /**
     * The per-record validation and posting stage.
     *
     * <p>An ordinary singleton, which is what the stage itself declares it may be: it holds no
     * per-record and no per-execution state, taking the run counters from the step execution the
     * framework already maintains. Published here rather than as a scanned component because it is a
     * collaborator of this one step and has no meaning outside it.
     *
     * @return the per-record stage over the translated posting program, never {@code null}
     */
    @Bean(name = VALIDATION_PROCESSOR_BEAN_NAME)
    public TransactionValidationProcessor postTransactionValidationProcessor() {
        return new TransactionValidationProcessor(this.postingService, this.meterRegistry);
    }

    /**
     * The sequential reader over the daily-transaction input, at its verified fixed width.
     *
     * <p>Scoped to the step, because the position it has reached is <strong>per-execution state</strong>.
     * Holding a read position in a shared singleton field would let one execution observe another's
     * progress, so this is one of the two places such state may live in this file and it lives here
     * rather than anywhere else.
     *
     * <p>The declared type is the stream-reader interface rather than an implementation class, and that
     * matters twice. The framework opens and closes a reader that is also a stream around the step, so
     * the whole pass happens inside the step it is attributed to. And a scoped bean declared by an
     * interface is proxied <em>through</em> that interface, so no subclass of an implementation type is
     * generated - which keeps this path clear of the class generation this module's reflection budget
     * rules out.
     *
     * <p>The layout is the factory's, not this method's: no offset, field width or padding rule of the
     * 350-byte record appears in this file. The reader is bound to the one resource the member reads
     * sequentially; the four key-sequenced resources are reached by key from inside the translated
     * program and are deliberately not bound here.
     *
     * @return a reader over the resolved sequential input, never {@code null}
     */
    @Bean(name = DAILY_TRANSACTION_READER_BEAN_NAME)
    @StepScope
    public ItemStreamReader<DailyTransaction> postTransactionDailyTransactionReader(
            final BatchStagingArea stagingArea) {
        final org.springframework.core.io.Resource input =
                stagingArea.holds(this.dalytranDataset)
                        ? stagingArea.stagedInput(this.dalytranDataset)
                        : new PathResource(dalytranInput());
        // The acquisition is the framework's, but the diagnostic is the program's: an input that cannot
        // be opened is 0000-DALYTRAN-OPEN's failure arm and must read as that arm does, not as the
        // reader's own strict-mode message. See docs/decision-log.md entry DL-215.
        return new DiagnosingDailyTransactionReader(
                this.readerFactory.dailyTransactionReader(input), this.postingService);
    }

    /**
     * Wraps one reader so that a failure to open is reported as the program's own open-failure arm.
     *
     * <p>Package-visible rather than private so that a test in this package can drive the decorator
     * directly against a scripted delegate, instead of leaving the re-throw path as a line nobody can
     * reach. The bean method above is the only production caller.
     *
     * @param  delegate       the reader to read through; must not be {@code null}
     * @param  postingService the program whose open-failure arm an acquisition failure produces; must not
     *                        be {@code null}
     * @return the decorated reader, never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    static ItemStreamReader<DailyTransaction> diagnosingReader(
            final ItemStreamReader<DailyTransaction> delegate,
            final TransactionPostingService postingService) {
        return new DiagnosingDailyTransactionReader(delegate, postingService);
    }

    /**
     * A reader whose failure to open is reported as {@code 0000-DALYTRAN-OPEN}'s failure arm.
     *
     * <p><strong>Only the open is decorated.</strong> Reading, updating and closing pass straight
     * through, because the driving loop's own read failure arm already belongs to the translated program
     * and is raised from there; decorating those too would emit one diagnostic twice. An
     * {@link AbendException} the delegate itself raises also passes through unchanged, so a failure that
     * already carries the program's verdict is never re-wrapped in a second one.
     *
     * <p>The delegate is a stream, so this decorator is one too: the framework opens and closes it
     * around the step, which is what keeps the whole pass attributed to the step that performs it.
     */
    private static final class DiagnosingDailyTransactionReader
            implements ItemStreamReader<DailyTransaction> {

        /** The reader that actually reads; the layout and the strictness are entirely its own. */
        private final ItemStreamReader<DailyTransaction> delegate;

        /** The translated program, which owns the literal, the status image and the abend code. */
        private final TransactionPostingService postingService;

        /**
         * @param delegate        the reader to read through; must not be {@code null}
         * @param postingService  the program whose open-failure arm an acquisition failure produces;
         *                        must not be {@code null}
         */
        DiagnosingDailyTransactionReader(final ItemStreamReader<DailyTransaction> delegate,
                final TransactionPostingService postingService) {
            this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
            this.postingService = Objects.requireNonNull(postingService,
                    "postingService must not be null");
        }

        @Override
        public void open(final ExecutionContext executionContext) {
            try {
                this.delegate.open(executionContext);
            } catch (final AbendException alreadyDiagnosed) {
                throw alreadyDiagnosed;
            } catch (final RuntimeException unopenable) {
                throw this.postingService.dailyTransactionOpenFailure(unopenable);
            }
        }

        @Override
        public DailyTransaction read() throws Exception {
            return this.delegate.read();
        }

        @Override
        public void update(final ExecutionContext executionContext) {
            this.delegate.update(executionContext);
        }

        @Override
        public void close() {
            this.delegate.close();
        }
    }

    /**
     * The reject-dataset writer, bound to the new generation this job execution owns.
     *
     * <p>Scoped to the step for two reasons at once. The write position is per-execution state, as the
     * read position is; and the destination is a <strong>different generation for every execution</strong>,
     * which a singleton could not be. The generation number is taken from the execution the framework
     * assigned, so two executions cannot resolve the same generation and neither can overwrite the
     * other's rejects.
     *
     * <p>Declared by the stream-writer interface for the same two reasons the reader is, and with one
     * more that is decisive here: the implementation is a final class, so a scoped bean declared by that
     * class could not be proxied at all.
     *
     * <p>The staging directory is created before the writer is handed the destination, because the legacy
     * allocation <em>created</em> the dataset rather than requiring it to exist. Everything about the
     * record - its width, its trailer, its padding, the absence of any separator - belongs to the writer,
     * and none of it is restated here.
     *
     * @param  jobExecutionId the execution the generation belongs to, supplied by the framework
     * @param  stagingArea the shared object-store staging boundary
     * @return a writer over this execution's own generation, never {@code null}
     * @throws NullPointerException if the framework supplied no execution identifier
     * @throws UncheckedIOException if the containing directory cannot be created
     */
    @Bean(name = REJECT_RECORD_WRITER_BEAN_NAME)
    @StepScope
    public ItemStreamWriter<RejectRecordWriter.RejectedTransaction> postTransactionRejectRecordWriter(
            @Value("#{stepExecution.jobExecutionId}") final Long jobExecutionId,
            @Value("#{stepExecution}") final StepExecution stepExecution) {
        final long executionId = Objects.requireNonNull(jobExecutionId,
                "the framework must have assigned a job execution identifier before a step runs")
                .longValue();
        final Path generation = rejectGeneration(executionId);
        final Path working = StagedGenerationStore.workingPath(generation);
        prepareStagingDirectory();
        final ItemStreamWriter<RejectRecordWriter.RejectedTransaction> writer =
                new RejectRecordWriter(new PathResource(working), this.meterRegistry);
        return StagedGenerationStore.completingWriter(writer,
                Objects.requireNonNull(stepExecution, "stepExecution"),
                this.dalyrejsDatasetBase, working, generation,
                StagedGenerationStore.STANDARD_RETENTION_LIMIT);
    }

    // -----------------------------------------------------------------------------------------------
    // Resource resolution. Every resource is a logical name resolved against a configured directory;
    // no path is written into this class.
    // -----------------------------------------------------------------------------------------------

    /**
     * The sequential daily-transaction input this run reads.
     *
     * <p>Package-visible and free of side effects, so a test can assert what a given configuration
     * resolves to without opening anything and without creating anything.
     *
     * @return the resolved input resource
     */
    Path dalytranInput() {
        return Path.of(this.stagingDirectory).resolve(this.dalytranDataset);
    }

    /**
     * The reject generation one job execution owns, named in the legacy absolute-generation form.
     *
     * <p>The framework execution identifier is rendered without a modulo, so no later execution can wrap
     * onto and truncate an earlier generation. The shared durable store publishes the completed file and
     * enforces the measured depth of {@link StagedGenerationStore#STANDARD_RETENTION_LIMIT} only after
     * the whole job completes.
     *
     * <p>Package-visible and free of side effects for the same reason {@link #dalytranInput()} is.
     * Creating the staging directory is deliberately <em>not</em> part of resolving a name; that is done
     * by the writer's factory method, at the point the legacy allocation would have created the dataset.
     *
     * @param  jobExecutionId the execution the generation belongs to
     * @return the resolved generation resource
     */
    Path rejectGeneration(final long jobExecutionId) {
        return StagedGenerationStore.generationPath(Path.of(this.stagingDirectory),
                this.dalyrejsDatasetBase, jobExecutionId);
    }

    /**
     * This configuration's own step diagnostic and timer.
     *
     * <p>Package-visible so the diagnostic and the timing can be exercised directly by a test, without a
     * job repository and without a launcher. A new instance is returned on each call and it holds nothing
     * but its two final collaborators, so no state is shared between step executions.
     *
     * @return a listener that reports the finished step and publishes its elapsed time
     */
    StepExecutionListener postingStepDiagnostics() {
        return new PostingStepDiagnostics(this.meterRegistry, this.clock);
    }

    /**
     * Creates the staging directory, which is the directory every generation of this job is written into.
     *
     * <p>Called before the reject writer is handed its destination, because the legacy allocation
     * <em>created</em> its dataset rather than requiring it to exist already. Creating the staging
     * directory is exactly creating the containing directory of the generation, because
     * {@link #rejectGeneration(long)} resolves the generation directly against it; there is therefore no
     * containing directory to derive and no case in which one is absent.
     *
     * <p>Creating a directory that already exists is a no-operation, so this is safe on every execution
     * after the first.
     *
     * @throws UncheckedIOException if the directory cannot be created
     */
    private void prepareStagingDirectory() {
        try {
            SecureStagedFiles.prepareDirectory(Path.of(this.stagingDirectory));
        } catch (IOException failure) {
            throw new UncheckedIOException("the staging directory the "
                    + TransactionPostingService.DALYREJS_DD + " generation is written into could not be"
                    + " created: " + this.stagingDirectory, failure);
        }
    }

    /**
     * Validates a configured logical name.
     *
     * <p>A blank name would resolve to the staging directory itself, so a run would read or write a
     * directory rather than a dataset. No default is substituted here: the defaults live on the
     * constructor's bindings, where they are visible, and a value that arrives blank is a configuration
     * defect rather than an absence to be filled in.
     *
     * <p><strong>This governs the staging root only.</strong> A dataset name resolved against that root
     * is screened by {@link StagedResourceNames#requireSimpleName(String, String)}, which additionally
     * refuses an absolute value, a path separator and a directory reference - none of which a blank
     * check catches, and each of which would resolve outside the root. The root itself is legitimately
     * a multi-segment path and may be absolute, so the stricter rule cannot be applied to it.
     *
     * @param  value the configured value
     * @param  key   the property the value was bound from, named in the diagnostic
     * @return the validated value
     * @throws NullPointerException     if the value is {@code null}
     * @throws IllegalArgumentException if the value is blank
     */
    private static String requireResourceName(final String value, final String key) {
        Objects.requireNonNull(value, () -> key + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " must name a resource; a blank value would resolve"
                    + " to the staging directory itself rather than to a dataset");
        }
        return value;
    }

    // -----------------------------------------------------------------------------------------------
    // The step diagnostic
    // -----------------------------------------------------------------------------------------------

    /**
     * Reports the finished step and publishes its elapsed time, altering nothing.
     *
     * <p>Holds only final collaborators and no accumulated state, so one step execution cannot observe
     * another's progress through it.
     */
    private static final class PostingStepDiagnostics implements StepExecutionListener {

        /** The registry the elapsed-time sample is published on. */
        private final MeterRegistry meterRegistry;

        /** The clock the observation instant is taken from. */
        private final Clock clock;

        /**
         * Creates the diagnostic for the one step of this job.
         *
         * @param meterRegistry the registry the elapsed-time sample is published on
         * @param clock         the clock the observation instant is taken from
         */
        PostingStepDiagnostics(final MeterRegistry meterRegistry, final Clock clock) {
            this.meterRegistry = meterRegistry;
            this.clock = clock;
        }

        /**
         * Publishes the step's elapsed time and reports its counters, leaving its verdict untouched.
         *
         * <p>The two figures reported are the legacy program's own two count diagnostics, mapped exactly:
         * the transaction counter is incremented once per record read, which is the step's read count,
         * and the reject counter is incremented once immediately before each reject record is written,
         * which is the step's write count - because the only items that reach this step's writer are
         * refusals. The counters are <strong>read</strong> from the execution the framework maintains and
         * are never accumulated in a field, so they describe one execution and a restart inherits
         * nothing.
         *
         * <p>The run counters' <em>semantics</em>, the posted total and the completion code belong to
         * {@link TransactionValidationProcessor#afterStep(StepExecution)}, which publishes them into the
         * step's execution context. They are deliberately not recomputed here: this line is the step's
         * own view, and duplicating the other listener's arithmetic would put one expectation in two
         * places with neither owning it.
         *
         * <p><strong>Returning {@code null} is the framework's own way of contributing no exit status,
         * and that is the point.</strong> A diagnostic observes an outcome and must never alter one. In
         * particular it must not turn the tolerated completion code this job raises for refused records
         * into something else, and it must not mask a technical failure.
         *
         * @param  stepExecution the finished step's execution; never {@code null}
         * @return {@code null}, always, so the step's own exit status stands unchanged
         * @throws NullPointerException if {@code stepExecution} is {@code null}
         */
        @Override
        public ExitStatus afterStep(final StepExecution stepExecution) {
            Objects.requireNonNull(stepExecution, "stepExecution must not be null");
            publishElapsedTime(stepExecution);
            LOGGER.info("{} {} ({}): PROGRAM {} PROCESSED {} TRANSACTION(S), REJECTED {}, FINISHED {}",
                    LEGACY_JOB, LEGACY_STEP, STEP_NAME, PROGRAM_NAME, stepExecution.getReadCount(),
                    stepExecution.getWriteCount(), stepExecution.getStatus());
            return null;
        }

        /**
         * Publishes one elapsed-time observation for the finished step.
         *
         * <p>The framework has not stamped an end time by the time this listener runs, so the elapsed
         * time is the interval between the start it did stamp and the moment observed here. The start is
         * stamped as a local date and time in the platform's own zone, while this module's clock reads
         * UTC, so the observation is converted from the clock's instant <em>through the platform zone</em>
         * rather than read as a UTC local time - otherwise the two ends of the interval would be offset
         * from one another by however far the platform sits from UTC.
         *
         * <p>An absent start time and a negative interval are both tolerated rather than thrown on: a
         * diagnostic must not be the thing that fails a step that otherwise succeeded. An absent start is
         * noted and nothing is published; a negative interval is published as no elapsed time at all,
         * because a timer records a duration and never a direction.
         *
         * @param stepExecution the finished step's execution
         */
        private void publishElapsedTime(final StepExecution stepExecution) {
            final LocalDateTime startedAt = stepExecution.getStartTime();
            if (startedAt == null) {
                LOGGER.debug("{} {} ({}): no start time is recorded for the execution, so no elapsed"
                        + " time is published", LEGACY_JOB, LEGACY_STEP, STEP_NAME);
                return;
            }
            final LocalDateTime observedAt =
                    LocalDateTime.ofInstant(this.clock.instant(), ZoneId.systemDefault());
            final Duration elapsed = Duration.between(startedAt, observedAt);
            Timer.builder(STEP_TIMER_NAME)
                    .description(STEP_TIMER_DESCRIPTION)
                    .tag(TAG_JOB, JOB_NAME)
                    .tag(TAG_STEP, STEP_NAME)
                    .tag(TAG_OUTCOME, stepExecution.getStatus().name())
                    .register(this.meterRegistry)
                    .record(elapsed.isNegative() ? Duration.ZERO : elapsed);
        }
    }
}
