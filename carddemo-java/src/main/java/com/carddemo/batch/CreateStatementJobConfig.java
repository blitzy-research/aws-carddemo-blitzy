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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.PlatformTransactionManager;

import com.carddemo.batch.step.AbstractCobolStep;
import com.carddemo.batch.step.FixedWidthFlatFileReaderFactory;
import com.carddemo.batch.step.StagedGenerationStore;
import com.carddemo.batch.step.StatementProcessor;
import com.carddemo.config.BatchConfig.ConditionCodeGate;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.repository.TransactionScanRepository;
import com.carddemo.service.BatchJobCatalog;
import com.carddemo.service.SensitiveFieldEncryptionService;
import com.carddemo.service.StatementGenerationService;
import com.carddemo.service.StatementGenerationService.StatementRun;
import com.carddemo.service.StatementLineSummary;
import com.carddemo.service.StatementOutputSink;
import com.carddemo.service.StatementTransactionSource;
import com.carddemo.util.BoundedKeysetIterator;
import com.carddemo.util.ExternalStringSorter;
import com.carddemo.util.FailureDiagnostics;
import com.carddemo.util.FixedWidthFieldReader;
import com.carddemo.util.SecureStagedFiles;
import com.carddemo.util.StatementWorkRecordMapper;
import com.carddemo.util.StagedResourceNames;
import com.carddemo.util.TransactionRecordMapper;

/**
 * Declares the customer statement generation job: order and reproject the transaction master,
 * materialise the transient work resource, scratch the previous run's two statement outputs, then
 * generate the statements in both of their fixed-width forms.
 *
 * <p>No statement of any of those members is reproduced here; what is reproduced is their step
 * inventory, their data-definition names, their condition-code semantics, their ordering positions
 * and types, and their record lengths.
 *
 * <h2>Five legacy steps become four, with exactly three gates</h2>
 *
 * <table>
 *   <caption>The measured job stream and its disposition here</caption>
 *   <tr><th>Legacy step</th><th>Utility or program</th><th>Gate</th><th>Disposition</th></tr>
 *   <tr><td>{@code DELDEF01} at {@code app/jcl/CREASTMT.JCL:22}</td><td>dataset utility</td>
 *       <td>none</td>
 *       <td><strong>Absorbed. It becomes no step at all</strong> - it deletes and re-defines the
 *           transient cluster, and that cluster becomes the in-job ordered result described
 *           below</td></tr>
 *   <tr><td>{@code STEP010} at line 44</td><td>external sort</td><td>none</td>
 *       <td>{@link #ORDER_AND_REPROJECT_STEP_NAME}</td></tr>
 *   <tr><td>{@code STEP020} at line 56</td><td>dataset copy utility</td><td>strict</td>
 *       <td>{@link #LOAD_WORK_RESOURCE_STEP_NAME}</td></tr>
 *   <tr><td>{@code STEP030} at line 66</td><td>null allocation utility</td><td>strict</td>
 *       <td>{@link #CLEAR_STATEMENT_OUTPUTS_STEP_NAME}</td></tr>
 *   <tr><td>{@code STEP040} at line 79</td><td>the statement program</td><td>strict</td>
 *       <td>{@link #GENERATE_STATEMENTS_STEP_NAME}</td></tr>
 * </table>
 *
 * <p>The job therefore has exactly {@value #STEP_COUNT} steps and exactly
 * {@value #CONDITION_CODE_GATE_COUNT} failure-ending transitions, one guarding each of the second,
 * third and fourth steps. Neither figure may drift: a fourth transition would gate a step the estate
 * runs unconditionally, and fewer than three would run a step the estate skips.
 *
 * <p><strong>What the strict gate means, stated so it cannot be inverted.</strong> A condition-code
 * test <em>bypasses</em> the step it guards when the test is true, and the measured test on all three
 * gated steps asks whether zero is not equal to the highest return code so far. It therefore bypasses
 * whenever any earlier step returned anything other than zero, which is to say the guarded step
 * <em>executes only when every earlier step returned zero</em>. That is the strict form, and it is the
 * form the plan defines for every one of the estate's four gates, including the sibling backup job's
 * single one; {@link com.carddemo.config.BatchConfig.ConditionCodeGate} therefore carries that one
 * constant and no looser alternative beside it.
 *
 * <p><strong>Why each of the three gates is a separate placement of that one shared constant.</strong>
 * The constant is an enumeration singleton, and the framework's flow builder keys the decision state it
 * creates by the object handed to it - creating a state only when that key is absent. Handing it the
 * singleton at three points of one flow therefore yields <em>one</em> shared decision state carrying all
 * three pairs of outgoing transitions, and every gate routes wherever the first registered pair pointed:
 * back into the step just run rather than onward to the next, which runs that step a second time and
 * abends it on input the first pass already consumed. Each gate is therefore wired as its own placement
 * of the constant, obtained from
 * {@link com.carddemo.config.BatchConfig.ConditionCodeGate#atOnePlacement()}, so all three ask the
 * identical numeric question while occupying three independent states. The sibling backup job places
 * the constant exactly once and can use it directly. The semantics are the measured ones on both axes: a
 * step reached only when every earlier step returned zero, and a refused gate that carries the abend out
 * to the job's own status rather than ending the flow cleanly.
 *
 * <h2>The reprojection, which is this file's own parity obligation</h2>
 *
 * <p>Two obligations that no other file of this module carries live here, and they are deliberately
 * not in the generating service: the 328-of-350 record reprojection, and the two-byte processing
 * timestamp truncation it causes.
 *
 * <p>The ordering specification at {@code app/jcl/CREASTMT.JCL:53} is positional rather than symbolic.
 * It declares two keys, both <strong>ascending</strong> and both typed <strong>character</strong>: the
 * card number at position {@value #CARD_NUMBER_SORT_POSITION} for {@value #CARD_NUMBER_SORT_LENGTH}
 * bytes, then the transaction identifier at position {@value #TRANSACTION_ID_SORT_POSITION} for
 * {@value #TRANSACTION_ID_SORT_LENGTH} bytes. The comparison is plain lexicographic on both keys;
 * neither key is parsed to a number here and no zoned-decimal decoding is applied to either.
 *
 * <p><strong>The very same card-number field, at the same position and the same length, is typed
 * character by this job and zoned decimal by the transaction report job.</strong> Nothing in the
 * estate reconciles the two typings and nothing should, because each job's comparator must apply the
 * typing its own specification declares. A comparator shared between the two would silently hand one
 * job the other's semantics - a parity defect that compiles cleanly and passes any test written under
 * the same misunderstanding. This job's comparator is therefore declared {@code private static final}
 * in this class, is never exported, never imported and never reused, and this class neither imports a
 * comparator nor publishes one.
 *
 * <p>The reprojection specification at line 54 rewrites each record as three segments:
 *
 * <table>
 *   <caption>The measured reprojection, in one-based positions</caption>
 *   <tr><th>Output</th><th>Input</th><th>Width</th><th>Content</th></tr>
 *   <tr><td>1 - 16</td><td>263 - 278</td><td>{@value #CARD_NUMBER_SORT_LENGTH}</td>
 *       <td>the card number, moved to the front</td></tr>
 *   <tr><td>17 - 278</td><td>1 - 262</td><td>{@value #LEADING_SEGMENT_LENGTH}</td>
 *       <td>the record's leading portion, which opens with the transaction identifier</td></tr>
 *   <tr><td>279 - 328</td><td>279 - 328</td><td>{@value #TIMESTAMP_SEGMENT_LENGTH}</td>
 *       <td>the origination timestamp in full, then a <em>partial</em> processing timestamp</td></tr>
 * </table>
 *
 * <p>The projected content is {@value #PROJECTED_CONTENT_LENGTH} bytes, then blank-padded back out to
 * the declared {@value #WORK_RECORD_LENGTH}-byte fixed record length with
 * {@value #BLANK_PAD_LENGTH} pad bytes. Both figures are proved in <strong>encoded bytes</strong> and
 * never in character counts.
 *
 * <p>The transaction layout, corroborated by the report job's own sort symbols, places the card number
 * at 263 for 16 bytes, the origination timestamp at 279 for 26 bytes, the processing timestamp at 305
 * for 26 bytes and a trailing filler run at 331 for {@value #DROPPED_TRAILING_FILLER_LENGTH} bytes.
 * The third projected segment therefore carries the origination timestamp in full and only input bytes
 * 305 through 328 of the processing timestamp - 24 of its 26. <strong>Exactly
 * {@value #TRUNCATED_PROCESSING_TIMESTAMP_BYTES} processing-timestamp bytes, at input positions 329
 * and 330, are truncated, and the trailing filler run is dropped entirely.</strong>
 *
 * <p><strong>That truncation is reproduced and must not be repaired.</strong> Widening the segment to
 * carry the whole timestamp would make this job's work record disagree with the record the estate
 * actually built, which is the one thing byte parity cannot survive. It is recorded here as a fidelity
 * item for {@code docs/decision-log.md} so that a later reader can see the two missing bytes are
 * faithful rather than a defect introduced by the migration.
 *
 * <p>The projection is self-checking in one respect that validates the whole design: output bytes 1 to
 * 16 are the card number and output bytes 17 to 32 are the transaction identifier, so output bytes 1
 * to {@value #WORK_RESOURCE_KEY_LENGTH} are exactly the key the transient work resource declares.
 *
 * <h2>The transient cluster becomes an in-job ordered result</h2>
 *
 * <p>The absorbed first step, measured at lines 25 to 39, deletes a sequential dataset and a cluster,
 * resets the utility's condition code, and then defines a new indexed cluster whose key is
 * {@value #WORK_RESOURCE_KEY_LENGTH} bytes at offset {@value #WORK_RESOURCE_KEY_OFFSET}, whose record
 * size is {@value #WORK_RECORD_LENGTH} fixed, and whose space is a single extent. That cluster is
 * created and destroyed <strong>entirely within this one job stream</strong>.
 *
 * <p>It is therefore an in-job ordered result and not part of the persistent schema: not a provisioned
 * table, and <strong>not a migration</strong>. The migrations under {@code db/migration/schema} create and
 * index the eleven persistent application tables and add their invariants; this transient artefact is not
 * one of them, and no migration may be added for it. {@code config/FlywayConfig} is the authority for the
 * delivered inventory and for which part of it a production profile applies.
 * {@link TransactionWorkResource} is the whole of its
 * representation, it is scoped to one job execution, and the third step of the pipeline is an ordinary
 * read-and-write step: <strong>no process is spawned, no command is composed and no external utility
 * is invoked anywhere in this file.</strong>
 *
 * <p>Its representation is a sequential file in the staging root and not a heap collection, because the
 * cluster it stands in for is a disk cluster whose size is bounded by the transaction master rather
 * than by available memory. One record is in hand at a time when it is loaded and one when it is read,
 * and the file is scratched with the execution that minted it - which is the deletion the absorbed step
 * declares. See {@code docs/decision-log.md} entry DL-176.
 *
 * <p>Because an indexed cluster is held in key sequence, the ordered result is keyed by the same
 * {@value #WORK_RESOURCE_KEY_LENGTH} bytes the projection places at the front of every record, and the
 * key ordering of fixed-width concatenated fields is exactly the two-key character ordering the sort
 * declares. The first step's ordering and the work resource's key sequence therefore agree by
 * construction rather than by restatement.
 *
 * <h2>Two measured source anomalies, both handled and neither reproduced</h2>
 *
 * <p><strong>The record-length conflict on the HTML output, resolved to
 * {@value #HTML_RECORD_LENGTH}.</strong> The same output data definition is declared with a record
 * length of {@value #SUPERSEDED_HTML_RECORD_LENGTH} in the scratch step, measured at line 69, and
 * {@value #HTML_RECORD_LENGTH} in the generation step, measured at line 94. The conflict is resolved
 * to {@value #HTML_RECORD_LENGTH} because the emitting program declares its HTML statement record as a
 * {@value #HTML_RECORD_LENGTH}-character field, so that is the value which matches the producer. The
 * plain-text output stays at {@value #STATEMENT_RECORD_LENGTH}, which both steps declare and the
 * program's own record confirms. Planning material cites lines 66 and 79 for this conflict; the
 * <em>measured</em> lines are 69 and 94, and the discrepancy is raised for the decision log.
 *
 * <p><strong>The garbled line.</strong> Line 90 of the job member carries overtyped, corrupted text in
 * which fragments of a space allocation, a record-format clause and a dataset name run together. It is
 * a source-editing artefact. The step's intent is recovered from the surrounding well-formed
 * definitions - a newly allocated, catalogued, fixed-length blocked plain-text statement dataset at
 * {@value #STATEMENT_RECORD_LENGTH} bytes - and <strong>the corruption is reproduced nowhere</strong>:
 * not in code, not in a comment and not in a constant. Its existence and location are recorded here
 * and raised for the decision log.
 *
 * <h2>The generator is a state machine, and this configuration must not defeat it</h2>
 *
 * <p>{@link StatementGenerationService} owns that machine; the shape is recorded here so the contract
 * is discoverable at job level. A work field initialised to the first phase name holds the current
 * state, and a dispatcher paragraph evaluates it across <strong>six clauses in source order</strong> -
 * clause order is contractual, because the legacy conditional evaluates top down and stops at the
 * first match - with the final catch-all clause terminating the run. Four measured transitions assign
 * a new phase name and then jump <em>backward</em> to that dispatcher, at source lines 760, 779, 797
 * and 851, and there are further backward jumps to the mainline and to the read paragraph.
 *
 * <p>The decisive structural fact is that the phase execution order - transient work resource open,
 * then read every transaction, then cross-reference open, then customer open, then account open, then
 * mainline - <strong>diverges from the numeric order the paragraphs are laid out in</strong>. Nested
 * method calls cannot reproduce re-entry into a dispatcher after a state change, which is why the only
 * faithful structure is an explicit state enumeration driven by a loop over an ordered switch with
 * mandatory dispatcher re-entry. The legacy dispatcher additionally uses an archaic self-modifying
 * branch-target verb to select which open paragraph its shared open routine proceeds to; only the
 * resulting state transition is modelled, never the self-modification, and it is the reason this
 * construct cannot be expressed as a plain loop at all.
 *
 * <p>The program's own file list is just its two outputs. All four inputs are opened by the called
 * subprogram through the dispatched phase name, which is why the state field holds resource names
 * rather than indices, and why this job's fourth step opens two resources and not six.
 *
 * <p>The generator's work areas are bounded at {@value #MAX_CARD_ENTRIES} cards and
 * {@value #MAX_TRANSACTIONS_PER_CARD} transactions per card. That is a structural bound of the legacy
 * table and is honoured as one; it is <strong>not</strong> a tuning figure, and no commit interval,
 * throughput expectation or memory expectation is derived from it anywhere in this file.
 *
 * <p>Both output forms are literal-driven. The plain-text statement is
 * {@value #STATEMENT_RECORD_LENGTH} bytes per record and the HTML statement is
 * {@value #HTML_RECORD_LENGTH}, with the literals supplied by the two template authorities.
 * <strong>No templating engine is introduced</strong>: byte-identical output needs the same literals in
 * the same order at the same width, and any engine introduces whitespace and ordering variability.
 * Both widths are proved on the encoded byte array at the moment a record reaches its destination.
 *
 * <h2>What this job deliberately does not have</h2>
 *
 * <p><strong>No job parameter of any kind.</strong> The measured member carries no parameter on any of
 * its five steps, so no start date, no end date and no selector is invented for it. The similarly
 * named sort symbols belong to the report job, not to this one.
 *
 * <p><strong>Strictly sequential.</strong> No task executor, no partitioner, no multi-threaded step and
 * no parallel flow, because both statement outputs are compared byte for byte and any concurrency
 * would reorder them. No skip limit, retry limit, backoff or timeout either: the legacy stream
 * tolerates no record and waits on nothing, so a tolerated record would be a record the estate wrote
 * and this job dropped.
 *
 * <p><strong>Nothing fires at context start.</strong> Automatic job execution is disabled by
 * configuration, and this class declares no command-line runner, no application runner, no
 * post-construct launcher, no lifecycle callback and no scheduled method. The job is registered under
 * {@value #JOB_NAME} and launched on demand by name. It chains to no other job, and no aggregate job
 * exists that would run it as part of a wider pipeline, because the estate has no master orchestrator.
 *
 * <p>Every step is timed on the shared meter registry, so all four appear on the metrics endpoint. No
 * performance figure is stated in this file.
 *
 * <h2>Diagnostics and failure handling</h2>
 *
 * <p>The legacy console-display channel becomes structured logging. On a terminal input or output
 * failure the raw two-character file status is logged <em>first</em> and the module's abend exception
 * is raised <em>after</em> - that ordering is contractual, {@link AbstractCobolStep} owns it, and no
 * lifecycle in this file re-implements it. End of file is never collapsed into error: the tri-state
 * outcome is nested inside that base class, as is the batch-form timestamp its lifecycle announcements
 * carry, and neither is restated here as a type of its own.
 *
 * @see StatementProcessor
 * @see StatementGenerationService
 * @see com.carddemo.config.BatchConfig
 */
@Configuration(proxyBeanMethods = false)
public final class CreateStatementJobConfig {

    /** This class's own diagnostic channel, replacing the legacy console display. */
    private static final Logger LOGGER = LoggerFactory.getLogger(CreateStatementJobConfig.class);

    // -----------------------------------------------------------------------------------------------
    // Identity. The job is launched and queried BY NAME through the registry the batch infrastructure
    // configuration exposes, so these names are part of this file's published contract.
    // -----------------------------------------------------------------------------------------------

    /**
     * Registered name of the job.
     *
     * <p>The value is read from {@code service/BatchJobCatalog}, which is the module's single
     * declaration of the nine stable job names. Both tiers that need a name - this configuration
     * and the operational control surface above it - resolve it from there, so the name exists as
     * one literal and the two cannot drift apart across a boundary the layering keeps closed.
     */
    public static final String JOB_NAME = BatchJobCatalog.CREATE_STATEMENT_JOB;

    /** Name of the first step: order the master and reproject every record. */
    public static final String ORDER_AND_REPROJECT_STEP_NAME =
            "createStatementOrderAndReprojectStep";

    /** Name of the second step: materialise the transient work resource. Gated. */
    public static final String LOAD_WORK_RESOURCE_STEP_NAME = "createStatementLoadWorkResourceStep";

    /** Name of the third step: scratch the previous run's two statement outputs. Gated. */
    public static final String CLEAR_STATEMENT_OUTPUTS_STEP_NAME =
            "createStatementClearOutputsStep";

    /** Name of the fourth step: generate both statement forms. Gated. */
    public static final String GENERATE_STATEMENTS_STEP_NAME =
            "createStatementGenerateStatementsStep";

    /** Bean name of the per-execution transient work resource. */
    public static final String WORK_RESOURCE_BEAN_NAME = "createStatementTransactionWorkResource";

    /** Bean name of the fourth step's per-record stage. */
    public static final String STATEMENT_PROCESSOR_BEAN_NAME = "createStatementProcessor";

    /** Steps the legacy member declares, of which one is absorbed and four are migrated. */
    public static final int LEGACY_STEP_COUNT = 5;

    /** Legacy steps absorbed into schema and resource handling rather than migrated as a step. */
    public static final int ABSORBED_LEGACY_STEP_COUNT = 1;

    /** Steps this job declares: the measured five less the one absorbed. */
    public static final int STEP_COUNT = LEGACY_STEP_COUNT - ABSORBED_LEGACY_STEP_COUNT;

    /**
     * Condition-code gates this job wires, one guarding each gated step.
     *
     * <p>Three, because the measured member gates its second, third and fourth steps and no other.
     *
     * <p>Each gate is a failure-<em>propagating</em> transition rather than a failure-ending one: it
     * bypasses every later step and carries the abend out to the job's own status. See DL-208.
     */
    public static final int CONDITION_CODE_GATE_COUNT = 3;

    /**
     * The gate this job's three guarded steps are routed through.
     *
     * <p><strong>The legacy gate is a numeric test, and this is the numeric decider.</strong> The measured
     * condition-code gate on {@code app/jcl/CREASTMT.JCL} lines 56, 66 and 79 runs the guarded step only
     * when <em>every</em> earlier step returned exactly zero - it does not ask whether the previous step
     * carried one particular status name. Routing on the framework's {@code FAILED} exit code against a
     * wildcard alternative asked the wrong question: a step ending with a nonzero code that is not spelled
     * {@code FAILED} - a declared warning code, or an exit code a step set for itself - matched the
     * wildcard and the guarded step ran, which the legacy gate would have refused. The shared decider
     * evaluates the highest return code any earlier step produced against a ceiling of zero, which is the
     * condition as written, and it is the same decider the estate's fourth gate on
     * {@code app/jcl/TRANBKP.jcl:51} uses, so all four gates are one rule. See
     * {@code docs/decision-log.md} entries DL-145 and DL-208.
     *
     * <p><strong>This constant states the rule; it is not what the flow is wired with.</strong> The flow
     * takes one {@link ConditionCodeGate#atOnePlacement() placement} of it per gated step, because a flow
     * builder keys its decision states by the object handed to it and one singleton handed over three
     * times becomes one state with three pairs of transitions. The rule is published here so it can be
     * asserted, and so the identity with the backup job's gate stays visible.
     */
    public static final ConditionCodeGate CONDITION_CODE_GATE =
            ConditionCodeGate.ALL_PRIOR_STEPS_ZERO;

    /**
     * The outcome a gate refuses to continue past.
     *
     * <p>The decider's own refusing verdict rather than a framework status name, so a gate verdict cannot
     * be confused with an ordinary step outcome inside the same flow.
     *
     * <p><strong>This outcome ends the flow as a failure, not as a completion.</strong> The measured
     * gates bypass every later step, and on the job entry system the submission's own completion code is
     * the <em>highest</em> step code it reached - so a step that abended produced an abended submission
     * and never a clean one. Routing this outcome to a plain end would have reported a job whose first
     * step abended as COMPLETED, which is invisible to an operator, to the batch metadata and to the
     * completion notification alike. See {@code docs/decision-log.md} entry DL-208.
     */
    public static final String GATE_FAILURE_OUTCOME = ConditionCodeGate.REFUSED;

    /** The outcome a gate routes onward to the step it guards: the decider's permitting verdict. */
    public static final String GATE_ONWARD_OUTCOME = ConditionCodeGate.PERMITTED;

    // -----------------------------------------------------------------------------------------------
    // Record geometry. Every offset and every length is taken from the layout owner in the utility
    // layer, so this file states no offset of its own and performs no offset arithmetic outside the
    // shared fixed-width primitives.
    // -----------------------------------------------------------------------------------------------

    /** Declared record length of the work resource, fixed, in encoded bytes. */
    public static final int WORK_RECORD_LENGTH = StatementWorkRecordMapper.RECORD_LENGTH;

    /** One-based position of the first ordering key, the card number. */
    public static final int CARD_NUMBER_SORT_POSITION =
            TransactionRecordMapper.TRAN_CARD_NUM_ONE_BASED_SORT_POSITION;

    /** Declared length of the first ordering key. */
    public static final int CARD_NUMBER_SORT_LENGTH = TransactionRecordMapper.TRAN_CARD_NUM_LENGTH;

    /** One-based position of the second ordering key, the transaction identifier. */
    public static final int TRANSACTION_ID_SORT_POSITION = TransactionRecordMapper.TRAN_ID_OFFSET + 1;

    /** Declared length of the second ordering key. */
    public static final int TRANSACTION_ID_SORT_LENGTH = TransactionRecordMapper.TRAN_ID_LENGTH;

    /**
     * Width of the reprojection's second segment: the record's leading portion, which runs up to but
     * not into the card number and therefore opens with the transaction identifier.
     *
     * <p>Expressed as the card number's own offset rather than as a number, because that offset
     * <em>is</em> the width of everything preceding it.
     */
    public static final int LEADING_SEGMENT_LENGTH =
            StatementWorkRecordMapper.LEADING_SEGMENT_LENGTH;

    /**
     * Width of the reprojection's third segment, exactly as the specification declares it.
     *
     * <p>Fifty bytes against a 26-byte origination timestamp followed by a 26-byte processing
     * timestamp, which is what causes the truncation this file reproduces.
     */
    public static final int TIMESTAMP_SEGMENT_LENGTH =
            StatementWorkRecordMapper.TIMESTAMP_SEGMENT_LENGTH;

    /** Projected content width: the three segments added up. */
    public static final int PROJECTED_CONTENT_LENGTH =
            StatementWorkRecordMapper.PROJECTED_CONTENT_LENGTH;

    /** Blank pad that carries the projected content back out to the declared record length. */
    public static final int BLANK_PAD_LENGTH = StatementWorkRecordMapper.BLANK_PAD_LENGTH;

    /** Zero-based offset of the work resource's key, as its cluster definition declares it. */
    public static final int WORK_RESOURCE_KEY_OFFSET =
            StatementWorkRecordMapper.CARD_NUMBER_OFFSET;

    /** Declared key length of the work resource: the card number then the transaction identifier. */
    public static final int WORK_RESOURCE_KEY_LENGTH = StatementWorkRecordMapper.KEY_LENGTH;

    /**
     * Processing-timestamp bytes the reprojection truncates.
     *
     * <p>Derived rather than asserted: the third segment's width less the origination timestamp it
     * carries in full is what remains for the processing timestamp, and the shortfall against that
     * timestamp's own declared length is the truncation. Two bytes, at input positions 329 and 330.
     */
    public static final int TRUNCATED_PROCESSING_TIMESTAMP_BYTES =
            StatementWorkRecordMapper.TRUNCATED_PROCESSING_TIMESTAMP_LENGTH;

    /** Trailing filler run the reprojection drops entirely. */
    public static final int DROPPED_TRAILING_FILLER_LENGTH = TransactionRecordMapper.FILLER_LENGTH;

    /** Declared record length of the plain-text statement output, in encoded bytes. */
    public static final int STATEMENT_RECORD_LENGTH = StatementProcessor.STATEMENT_RECORD_LENGTH;

    /** Declared record length of the HTML statement output, in encoded bytes; the resolved value. */
    public static final int HTML_RECORD_LENGTH = StatementProcessor.HTML_RECORD_LENGTH;

    /**
     * The superseded HTML record length the scratch step declares, kept only so the conflict is
     * visible and assertable.
     *
     * <p>Nothing configures an output at this width; the resolved width is
     * {@value #HTML_RECORD_LENGTH}.
     */
    public static final int SUPERSEDED_HTML_RECORD_LENGTH = 80;

    /** Structural bound of the generator's card table. Never a tuning figure. */
    public static final int MAX_CARD_ENTRIES = StatementGenerationService.MAX_CARD_ENTRIES;

    /** Structural bound of each card's transaction table. Never a tuning figure. */
    public static final int MAX_TRANSACTIONS_PER_CARD =
            StatementGenerationService.MAX_TRANSACTIONS_PER_CARD;

    /** Logical name of the transient cluster the work resource stands in for, for diagnostics only. */
    public static final String TRANSIENT_WORK_RESOURCE_NAME = "AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS";

    // -----------------------------------------------------------------------------------------------
    // Internal names: legacy step and data-definition names, layout labels, and the one separator.
    // -----------------------------------------------------------------------------------------------

    /** The legacy step this file absorbs rather than migrates. */
    private static final String LEGACY_ABSORBED_STEP = "DELDEF01";

    /** Data definition the first step reads: the transaction master. */
    private static final String DD_SORT_INPUT = "SORTIN";

    /** Data definition the first step writes: the ordered, reprojected sequential result. */
    private static final String DD_SORT_OUTPUT = "SORTOUT";

    /** Data definition the second step reads. */
    private static final String DD_LOAD_INPUT = "INFILE";

    /** Data definition the second step writes: the transient work resource. */
    private static final String DD_LOAD_OUTPUT = "OUTFILE";

    /** Legacy field name of the first ordering key. */
    private static final String FIELD_TRAN_CARD_NUM = "TRAN-CARD-NUM";

    /** Legacy field name of the second ordering key. */
    private static final String FIELD_TRAN_ID = "TRAN-ID";

    /** Diagnostic label of the reprojection's third segment. */
    private static final String FIELD_TIMESTAMP_SEGMENT = "TRAN-ORIG-TS-THROUGH-TRAN-PROC-TS";

    /** Output data definitions the third step allocates and scratches, in declaration order. */
    private static final int STATEMENT_OUTPUT_COUNT = 2;

    // -----------------------------------------------------------------------------------------------
    // THE ORDERING. Private to this class, and it must stay private: see the class documentation for
    // why a comparator shared with the report job would be a parity defect rather than reuse.
    // -----------------------------------------------------------------------------------------------

    /**
     * The ordering the first step applies: the card number ascending, then the transaction identifier
     * ascending, <strong>both typed character</strong> exactly as this job's own specification declares,
     * over the record image the sort addresses - which is the record before reprojection, because the
     * specification orders first and reprojects afterwards.
     *
     * <p>Declared {@code private static final} deliberately, and never handed out. The report job
     * orders the identical bytes at the identical position typed zoned decimal, so the two typings must
     * not be expressed by one comparator.
     *
     * <p>Plain lexicographic on both keys. Neither key is parsed to a number and no sign convention is
     * decoded, because a character-typed key compares bytes and a value carrying a sign in its final
     * byte must sort as those bytes and not as the number they would decode to. Applied with a stable
     * sort, so two records agreeing on both keys keep the sequence the master delivered them in.
     */
    private static final Comparator<String> CARD_NUMBER_THEN_TRANSACTION_ID_CHARACTER_ASCENDING =
            Comparator.<String, String>comparing(CreateStatementJobConfig::cardNumberSortField)
                    .thenComparing(CreateStatementJobConfig::transactionIdSortField);

    private static final int KEYSET_PAGE_SIZE = BoundedKeysetIterator.DEFAULT_PAGE_SIZE;

    // -----------------------------------------------------------------------------------------------
    // Collaborators. Constructor injection only; every field final; not one of them accumulates, so no
    // execution can observe another's progress through this class.
    // -----------------------------------------------------------------------------------------------

    /** The framework's metadata repository, handed to every builder rather than to a factory. */
    private final JobRepository jobRepository;

    /** Transaction manager each step's single indivisible pass runs under. */
    private final PlatformTransactionManager transactionManager;

    /** The shared job-boundary diagnostic the batch infrastructure configuration publishes. */
    private final JobExecutionListener jobBoundaryListener;

    /**
     * The shared run incrementer from the same configuration, which is what lets this job be
     * resubmitted with an identical parameter set exactly as the legacy member could be.
     */
    private final JobParametersIncrementer jobRunIncrementer;

    /** Bounded sequential-read view kept separate from the frozen online repository surface. */
    private final TransactionScanRepository transactionScanRepository;

    /** The statement generator the fourth step's stage delegates one whole run to. */
    private final StatementGenerationService statementGenerationService;

    /**
     * Owner of the module's field-protection policy for the two regulated customer identifiers.
     *
     * <p>Taken as a service here and handed on as two operations, because the generator holds no key
     * and no encryption collaborator of its own and must not acquire one.
     */
    private final SensitiveFieldEncryptionService fieldEncryption;

    /** Registry every step and every program lifecycle is timed on. */
    private final MeterRegistry meterRegistry;

    /** The module's clock, which stamps each program lifecycle's boundaries. */
    private final Clock clock;

    /** Directory the staged resources of this job resolve within. */
    private final String stagingDirectory;

    /** Logical name of the ordered, reprojected sequential resource the first step writes. */
    private final String transactionWorkSequentialName;

    /** Logical name of the plain-text statement output. */
    private final String statementOutputName;

    /** Logical name of the HTML statement output. */
    private final String htmlStatementOutputName;

    /**
     * Wires the job's collaborators.
     *
     * <p>Every resource is named by configuration rather than by a path written into this class, and
     * each logical name defaults to the name the legacy stream uses for the same resource, so a
     * deployment may relocate the staging area without the job losing the identity of what it writes.
     * None of these values is a credential and none that could be one is defaulted.
     *
     * <p>Deliberately not taken: the three other repositories and the file-handling collaborator, which
     * belong to the generator and its data-access service; the two template authorities, which expose
     * static members because a fixed-width layout has no per-instance state; and any comparator, which
     * this class declares privately for the reason its documentation gives.
     *
     * @param jobRepository the framework's metadata repository; must not be {@code null}
     * @param transactionManager the manager each step runs under; must not be {@code null}
     * @param jobBoundaryListener the shared job-boundary diagnostic; must not be {@code null}
     * @param jobRunIncrementer the shared run incrementer; must not be {@code null}
     * @param transactionScanRepository bounded sequential-read view of the transaction master
     * @param statementGenerationService the statement generator; must not be {@code null}
     * @param fieldEncryption owner of the field-protection policy; must not be {@code null}
     * @param meterRegistry the registry every step is timed on; must not be {@code null}
     * @param clock the module's clock; must not be {@code null}
     * @param stagingDirectory directory the staged resources resolve within; must not be blank
     * @param transactionWorkSequentialName logical name of the reprojected sequential resource; must
     *                                      not be blank
     * @param statementOutputName logical name of the plain-text statement output; must not be blank
     * @param htmlStatementOutputName logical name of the HTML statement output; must not be blank
     */
    public CreateStatementJobConfig(
            final JobRepository jobRepository,
            final PlatformTransactionManager transactionManager,
            @Qualifier("batchJobBoundaryListener") final JobExecutionListener jobBoundaryListener,
            @Qualifier("batchJobRunIncrementer") final JobParametersIncrementer jobRunIncrementer,
            final TransactionScanRepository transactionScanRepository,
            final StatementGenerationService statementGenerationService,
            final SensitiveFieldEncryptionService fieldEncryption,
            final MeterRegistry meterRegistry,
            final Clock clock,
            @Value("${carddemo.batch.create-statement.staging-directory:${"
                    + StagedGenerationStore.SHARED_STAGING_DIRECTORY_PROPERTY
                    + ":${java.io.tmpdir}}}")
                    final String stagingDirectory,
            @Value("${carddemo.batch.create-statement.transaction-work-sequential:"
                    + "AWS.M2.CARDDEMO.TRXFL.SEQ}") final String transactionWorkSequentialName,
            @Value("${carddemo.batch.create-statement.statement-output:"
                    + "AWS.M2.CARDDEMO.STATEMNT.PS}") final String statementOutputName,
            @Value("${carddemo.batch.create-statement.html-statement-output:"
                    + "AWS.M2.CARDDEMO.STATEMNT.HTML}") final String htmlStatementOutputName) {

        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.jobBoundaryListener = Objects.requireNonNull(jobBoundaryListener, "jobBoundaryListener");
        this.jobRunIncrementer = Objects.requireNonNull(jobRunIncrementer, "jobRunIncrementer");
        this.transactionScanRepository = Objects.requireNonNull(
                transactionScanRepository, "transactionScanRepository");
        this.statementGenerationService =
                Objects.requireNonNull(statementGenerationService, "statementGenerationService");
        this.fieldEncryption = Objects.requireNonNull(fieldEncryption, "fieldEncryption");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.stagingDirectory = requireResourceName(stagingDirectory, "stagingDirectory");
        this.transactionWorkSequentialName =
                StagedResourceNames.requireSimpleName(transactionWorkSequentialName,
                        "transactionWorkSequentialName");
        this.statementOutputName =
                StagedResourceNames.requireSimpleName(statementOutputName, "statementOutputName");
        this.htmlStatementOutputName =
                StagedResourceNames.requireSimpleName(htmlStatementOutputName,
                        "htmlStatementOutputName");
    }

    // -----------------------------------------------------------------------------------------------
    // The pieces the four steps are composed from.
    // -----------------------------------------------------------------------------------------------

    /**
     * Publishes the transient work resource: the ordered result the second step loads and the fourth
     * step names as its input.
     *
     * <p>Scoped to the job, which is the exact lifetime of the artefact it stands for. The legacy
     * cluster is defined by the absorbed first step and deleted by the next submission's absorbed first
     * step, and an executed search of the legacy tree finds it named nowhere outside this one member, so
     * one instance per job execution reproduces its lifetime precisely. A singleton would carry one
     * execution's records into the next, and a step-scoped bean would give each step its own empty copy
     * so the fourth step would find nothing.
     *
     * <p>Declared by its interface rather than by its implementation class, which matters for one
     * reason beyond taste: a scoped bean declared by an interface is proxied through that interface, so
     * no subclass of an implementation type is generated, and this path stays clear of the class
     * generation the module's reflection budget rules out.
     *
     * <p>Its sequential resource is minted within the configured staging root under a name unique to
     * the instance, and the destroy callback scratches it. That is the second half of the lifetime the
     * absorbed definition step declares: the artefact is created and deleted entirely within one
     * submission, so nothing it held survives the execution that minted it.
     *
     * @return the per-execution transient work resource, never {@code null}
     */
    @Bean(name = WORK_RESOURCE_BEAN_NAME, destroyMethod = "discard")
    @JobScope
    public TransactionWorkResource createStatementTransactionWorkResource() {
        return new FileBackedTransactionWorkResource(Path.of(this.stagingDirectory));
    }

    /**
     * Publishes the fourth step's per-record stage.
     *
     * <p>Declared here rather than discovered, because it carries no framework stereotype of its own.
     * It holds no mutable state, so one instance serves every execution.
     *
     * <p><strong>This is the module's composition root for the two regulated-field operations.</strong>
     * The stage takes them as operations rather than as a key or a service, so that neither it nor the
     * generator holds cryptographic material, and no bean of an operation type exists anywhere in the
     * module - which is precisely why the root has to compose them, and this is that root.
     *
     * @return the fourth step's stage, never {@code null}
     */
    @Bean(STATEMENT_PROCESSOR_BEAN_NAME)
    public StatementProcessor createStatementProcessor() {
        return new StatementProcessor(this.statementGenerationService,
                this::revealRegulatedIdentifier, this::sealRegulatedIdentifier, this.meterRegistry);
    }

    // -----------------------------------------------------------------------------------------------
    // The four steps, and the job that sequences them behind three gates.
    // -----------------------------------------------------------------------------------------------

    /**
     * The job: four steps in strict sequence, the last three behind a gate, and nothing else.
     *
     * <p>Each of the {@value #CONDITION_CODE_GATE_COUNT} gates is its own placement of
     * {@link #CONDITION_CODE_GATE} carrying a pair of transitions: the refusing outcome <strong>ends the
     * flow as a failure</strong>, and the permitting outcome proceeds to the guarded step. Three
     * placements rather than three uses of the one constant, because the flow builder keys a decision
     * state by the object it is handed and would otherwise give all three gates one shared state whose
     * first transition pair answers for every one of them. That reproduces the measured semantics on both
     * axes at once -
     * the guarded step runs only when everything before it completed cleanly, and a submission whose step
     * abended is reported as having failed, because the job entry system's completion code is the highest
     * step code the submission reached. Ending the flow as a <em>completion</em> would have satisfied the
     * bypass half and inverted the reporting half. See {@code docs/decision-log.md} entry DL-208.
     *
     * <p>Two collaborators the batch infrastructure publishes are attached, which is what they exist
     * for. The run incrementer makes a resubmission of the same logical work a new instance rather than
     * a completed-instance refusal. The boundary diagnostic frames the four step-level lines this
     * class's lifecycles emit with a job-level pair.
     *
     * <p><strong>No parameter validator is attached, because this job takes no parameter.</strong> The
     * measured member carries none on any of its five steps.
     *
     * <p>The steps arrive as qualified parameters rather than as calls to this class's own bean methods,
     * because bean methods are not proxied here - which is what lets this class be {@code final} - so a
     * direct call would build a second, unregistered step.
     *
     * <p>Nothing launches this job automatically and it chains to no other job.
     *
     * @param orderAndReprojectStep the first step; must not be {@code null}
     * @param loadWorkResourceStep the second step, gated; must not be {@code null}
     * @param clearStatementOutputsStep the third step, gated; must not be {@code null}
     * @param generateStatementsStep the fourth step, gated; must not be {@code null}
     * @return the job, registered under {@value #JOB_NAME}, never {@code null}
     */
    @Bean
    public Job createStatementJob(
            @Qualifier(ORDER_AND_REPROJECT_STEP_NAME) final Step orderAndReprojectStep,
            @Qualifier(LOAD_WORK_RESOURCE_STEP_NAME) final Step loadWorkResourceStep,
            @Qualifier(CLEAR_STATEMENT_OUTPUTS_STEP_NAME) final Step clearStatementOutputsStep,
            @Qualifier(GENERATE_STATEMENTS_STEP_NAME) final Step generateStatementsStep) {

        Objects.requireNonNull(orderAndReprojectStep, "orderAndReprojectStep");
        Objects.requireNonNull(loadWorkResourceStep, "loadWorkResourceStep");
        Objects.requireNonNull(clearStatementOutputsStep, "clearStatementOutputsStep");
        Objects.requireNonNull(generateStatementsStep, "generateStatementsStep");

        // Each gated step is reached through the numeric decider rather than through a status-name
        // transition on its predecessor: the condition is "every earlier step returned zero", which is a
        // property of the whole execution so far and not of the step immediately before.
        //
        // One placement of the shared gate per gated step, and never the shared constant itself at more
        // than one point of this flow. The flow builder keys the decision state it creates by the object
        // it is handed, so handing it one enumeration singleton three times yields a single state holding
        // all three pairs of transitions, and every gate then routes wherever the first pair pointed -
        // back into the step just run rather than onward to the next.
        final JobExecutionDecider gateBeforeLoad = CONDITION_CODE_GATE.atOnePlacement();
        final JobExecutionDecider gateBeforeClear = CONDITION_CODE_GATE.atOnePlacement();
        final JobExecutionDecider gateBeforeGenerate = CONDITION_CODE_GATE.atOnePlacement();

        return new JobBuilder(JOB_NAME, this.jobRepository)
                .incrementer(this.jobRunIncrementer)
                .listener(this.jobBoundaryListener)
                .start(orderAndReprojectStep)
                .next(gateBeforeLoad)
                    .on(GATE_FAILURE_OUTCOME).fail()
                .from(gateBeforeLoad)
                    .on(GATE_ONWARD_OUTCOME).to(loadWorkResourceStep)
                .from(loadWorkResourceStep)
                    .next(gateBeforeClear)
                    .on(GATE_FAILURE_OUTCOME).fail()
                .from(gateBeforeClear)
                    .on(GATE_ONWARD_OUTCOME).to(clearStatementOutputsStep)
                .from(clearStatementOutputsStep)
                    .next(gateBeforeGenerate)
                    .on(GATE_FAILURE_OUTCOME).fail()
                .from(gateBeforeGenerate)
                    .on(GATE_ONWARD_OUTCOME).to(generateStatementsStep)
                .end()
                .build();
    }

    /**
     * The first step: read the transaction master in its own key sequence, order it by this class's
     * two character-typed keys, reproject every record, and write the ordered sequential result.
     *
     * <p>Ungated, because the measured step carries no condition-code dependency: it is the first thing
     * the submission does with data.
     *
     * <p>It also absorbs the deleted-and-redefined sequential dataset of the step this file does not
     * migrate: the resource is created if absent and truncated if present, which is exactly what a
     * delete followed by a new allocation achieves.
     *
     * <p>A single-invocation step rather than a chunked one, because an ordering pass cannot write its
     * first record until it has read its last, and a chunked step would have to state a commit interval
     * this translation has no legacy basis for choosing.
     *
     * @return the step, registered under {@value #ORDER_AND_REPROJECT_STEP_NAME}, never {@code null}
     */
    @Bean
    public Step createStatementOrderAndReprojectStep() {
        return new StepBuilder(ORDER_AND_REPROJECT_STEP_NAME, this.jobRepository)
                .tasklet(this::orderAndReprojectTransactions, this.transactionManager)
                .meterRegistry(this.meterRegistry)
                .build();
    }

    /**
     * The second step: read the ordered sequential result and materialise the transient work resource
     * from it, keyed by the {@value #WORK_RESOURCE_KEY_LENGTH}-byte key the projection placed at the
     * front of every record.
     *
     * <p>Gated. An ordinary read-and-write step and nothing more: <strong>no utility is invoked, no
     * process is spawned and no external tool is addressed</strong>, which is the single most important
     * property of this translation.
     *
     * @param workResource the per-execution work resource this step loads; must not be {@code null}
     * @return the step, registered under {@value #LOAD_WORK_RESOURCE_STEP_NAME}, never {@code null}
     */
    @Bean
    public Step createStatementLoadWorkResourceStep(
            @Qualifier(WORK_RESOURCE_BEAN_NAME) final TransactionWorkResource workResource) {

        Objects.requireNonNull(workResource, "workResource");
        return new StepBuilder(LOAD_WORK_RESOURCE_STEP_NAME, this.jobRepository)
                .tasklet((contribution, chunkContext) ->
                        loadWorkResource(workResource, chunkContext),
                        this.transactionManager)
                .meterRegistry(this.meterRegistry)
                .build();
    }

    /**
     * The third step: scratch the two statement outputs the previous run left behind.
     *
     * <p>Gated. The measured step runs the null program and allocates both outputs with a disposition
     * that deletes them at deallocation whether the step ends normally or not, so its whole effect is
     * that the next step allocates them new. The translation is the same: each output is allocated,
     * enrolled, and scratched when the step closes.
     *
     * <p>This is also where the record-length conflict is resolved. The scratch step declares the HTML
     * output at {@value #SUPERSEDED_HTML_RECORD_LENGTH} bytes and the generation step declares it at
     * {@value #HTML_RECORD_LENGTH}; the resolved width is {@value #HTML_RECORD_LENGTH}, and the
     * allocation this step reports carries that resolved width rather than the superseded one.
     *
     * @return the step, registered under {@value #CLEAR_STATEMENT_OUTPUTS_STEP_NAME}, never
     *         {@code null}
     */
    @Bean
    public Step createStatementClearOutputsStep() {
        return new StepBuilder(CLEAR_STATEMENT_OUTPUTS_STEP_NAME, this.jobRepository)
                .tasklet(this::clearStatementOutputs, this.transactionManager)
                .meterRegistry(this.meterRegistry)
                .build();
    }

    /**
     * The fourth step: generate both statement forms and write each record at its declared width.
     *
     * <p>Gated. The item handed to the stage is a frozen source over the work resource's projected
     * records, because the measured step carries no parameter and the only thing distinguishing one run
     * from another is the content the three preceding steps built. All statement logic - the dispatcher,
     * its six clauses, the bounded card table and both literal layouts - belongs to the generator and its
     * stage; this step composes them and proves the width of every record that reaches a destination.
     *
     * @param statementProcessor the fourth step's stage; must not be {@code null}
     * @param workResource the per-execution work resource this step names as its input; must not be
     *                     {@code null}
     * @param stagingArea the shared object-store staging boundary
     * @return the step, registered under {@value #GENERATE_STATEMENTS_STEP_NAME}, never {@code null}
     */
    @Bean
    public Step createStatementGenerateStatementsStep(
            @Qualifier(STATEMENT_PROCESSOR_BEAN_NAME) final StatementProcessor statementProcessor,
            @Qualifier(WORK_RESOURCE_BEAN_NAME) final TransactionWorkResource workResource,
            final BatchStagingArea stagingArea) {

        Objects.requireNonNull(statementProcessor, "statementProcessor");
        Objects.requireNonNull(workResource, "workResource");
        return new StepBuilder(GENERATE_STATEMENTS_STEP_NAME, this.jobRepository)
                .tasklet((contribution, chunkContext) ->
                        generateStatements(statementProcessor, workResource, chunkContext),
                        this.transactionManager)
                .meterRegistry(this.meterRegistry)
                .build();
    }

    // -----------------------------------------------------------------------------------------------
    // Tasklet adapters. Each builds a FRESH program lifecycle for the execution it is serving, so every
    // per-execution handle, work area and counter lives on a short-lived object and nothing is shared
    // between executions.
    //
    // Package-visible rather than private so that a test in this package can drive one adapter against
    // a step execution instead of reaching it only through a launcher. They are not the launch surface:
    // the beans above are.
    // -----------------------------------------------------------------------------------------------

    /**
     * Runs the ordering and reprojection pass for one step execution.
     *
     * @param contribution the framework's per-step contribution, unused because the whole step is one
     *                     indivisible pass
     * @param chunkContext the framework's chunk context, unused because this job takes no parameter and
     *                     names no per-execution resource
     * @return {@link RepeatStatus#FINISHED} always
     */
    RepeatStatus orderAndReprojectTransactions(final StepContribution contribution,
            final ChunkContext chunkContext) {

        final long executionId = jobExecutionIdOf(chunkContext);
        final Path resource = transactionWorkSequentialResource(executionId);
        final Path working = StagedGenerationStore.workingPath(resource);
        newOrderAndReprojectProgram(working).run();
        StagedGenerationStore.completeWorkingFile(working, resource);
        return RepeatStatus.FINISHED;
    }

    /**
     * Runs the work-resource load for one step execution.
     *
     * @param workResource the work resource this execution loads; must not be {@code null}
     * @param chunkContext framework chunk context naming the execution-scoped sequential input
     * @return {@link RepeatStatus#FINISHED} always
     */
    RepeatStatus loadWorkResource(final TransactionWorkResource workResource,
            final ChunkContext chunkContext) {
        newLoadWorkResourceProgram(
                transactionWorkSequentialResource(jobExecutionIdOf(chunkContext)),
                workResource).run();
        return RepeatStatus.FINISHED;
    }

    /**
     * Runs the scratch of both statement outputs for one step execution.
     *
     * @param contribution the framework's per-step contribution, unused for the reason above
     * @param chunkContext the framework's chunk context, unused for the reason above
     * @return {@link RepeatStatus#FINISHED} always
     */
    RepeatStatus clearStatementOutputs(final StepContribution contribution,
            final ChunkContext chunkContext) {

        newClearStatementOutputsProgram().run();
        return RepeatStatus.FINISHED;
    }

    /**
     * Runs the statement generation for one step execution.
     *
     * @param statementProcessor the stage one whole run is delegated to; must not be {@code null}
     * @param workResource the work resource this step names as its input; must not be {@code null}
     * @param chunkContext framework chunk context naming this execution's two output generations
     * @return {@link RepeatStatus#FINISHED} always
     */
    RepeatStatus generateStatements(final StatementProcessor statementProcessor,
            final TransactionWorkResource workResource, final ChunkContext chunkContext) {
        final long executionId = jobExecutionIdOf(chunkContext);
        final Path statementGeneration = statementOutputGeneration(executionId);
        final Path htmlGeneration = htmlStatementOutputGeneration(executionId);
        final Path statementWorking = StagedGenerationStore.workingPath(statementGeneration);
        final Path htmlWorking = StagedGenerationStore.workingPath(htmlGeneration);

        newGenerateStatementsProgram(statementProcessor, workResource,
                statementWorking, htmlWorking).run();
        StagedGenerationStore.completeWorkingFile(statementWorking, statementGeneration);
        StagedGenerationStore.completeWorkingFile(htmlWorking, htmlGeneration);
        final StepExecution stepExecution = stepExecutionOf(chunkContext);
        StagedGenerationStore.register(stepExecution, this.statementOutputName,
                statementGeneration, StagedGenerationStore.STANDARD_RETENTION_LIMIT,
                statementOutputResource());
        StagedGenerationStore.register(stepExecution, this.htmlStatementOutputName,
                htmlGeneration, StagedGenerationStore.STANDARD_RETENTION_LIMIT,
                htmlStatementOutputResource());
        return RepeatStatus.FINISHED;
    }

    // -----------------------------------------------------------------------------------------------
    // Program-lifecycle factories. Package-visible so a test in this package can drive one lifecycle
    // directly against temporary resources rather than leaving the four of them reachable only through
    // a launcher.
    // -----------------------------------------------------------------------------------------------

    /**
     * Builds one ordering-and-reprojection lifecycle.
     *
     * @param projectedResource the ordered sequential resource to write; must not be {@code null}
     * @return a fresh lifecycle, never {@code null}
     */
    OrderAndReprojectProgram newOrderAndReprojectProgram(final Path projectedResource) {
        return new OrderAndReprojectProgram(this.meterRegistry, this.clock,
                this.transactionScanRepository, projectedResource);
    }

    /**
     * Builds one work-resource load lifecycle.
     *
     * @param projectedResource the ordered sequential resource to read; must not be {@code null}
     * @param workResource the work resource to load; must not be {@code null}
     * @return a fresh lifecycle, never {@code null}
     */
    LoadWorkResourceProgram newLoadWorkResourceProgram(final Path projectedResource,
            final TransactionWorkResource workResource) {

        return new LoadWorkResourceProgram(this.meterRegistry, this.clock, projectedResource,
                workResource);
    }

    /**
     * Builds one scratch lifecycle over both statement outputs, in the order the member declares them.
     *
     * @return a fresh lifecycle, never {@code null}
     */
    ClearStatementOutputsProgram newClearStatementOutputsProgram() {
        return new ClearStatementOutputsProgram(this.meterRegistry, this.clock,
                List.of(new StatementOutput(StatementProcessor.OUTPUT_DD_HTMLFILE,
                                htmlStatementOutputResource(), HTML_RECORD_LENGTH),
                        new StatementOutput(StatementProcessor.OUTPUT_DD_STMTFILE,
                                statementOutputResource(), STATEMENT_RECORD_LENGTH)));
    }

    /**
     * Builds one statement-generation lifecycle.
     *
     * @param statementProcessor the stage one whole run is delegated to; must not be {@code null}
     * @param workResource the work resource supplying the frozen projected input; must not be
     *                     {@code null}
     * @return a fresh lifecycle, never {@code null}
     */
    GenerateStatementsProgram newGenerateStatementsProgram(
            final StatementProcessor statementProcessor, final TransactionWorkResource workResource) {

        return newGenerateStatementsProgram(statementProcessor, workResource,
                statementOutputResource(), htmlStatementOutputResource());
    }

    /**
     * Builds one statement-generation lifecycle over explicitly selected output resources.
     *
     * <p>The production tasklet supplies execution-scoped working paths. The two-argument overload
     * remains the direct parity-test surface over the fixed logical names; both reach the same lifecycle
     * and therefore the same width and ordering checks.</p>
     *
     * @param statementProcessor stage one whole run is delegated to
     * @param workResource work resource named as the input
     * @param statementResource plain-text output path
     * @param htmlResource HTML output path
     * @return a fresh lifecycle
     */
    GenerateStatementsProgram newGenerateStatementsProgram(
            final StatementProcessor statementProcessor, final TransactionWorkResource workResource,
            final Path statementResource, final Path htmlResource) {
        return new GenerateStatementsProgram(this.meterRegistry, this.clock, statementProcessor,
                workResource, statementResource, htmlResource);
    }

    // -----------------------------------------------------------------------------------------------
    // Resource resolution. Every resource is a logical name resolved against a configured directory; no
    // path is written into this class and no storage resource is provisioned by it.
    //
    // The three names are absolute rather than per-execution on purpose: the measured member allocates
    // each of them under one fixed name, and the scratch step exists precisely because the next
    // submission finds the previous one's output under that same name. Naming them per execution would
    // make that step a no-operation and would silently change the contract.
    // -----------------------------------------------------------------------------------------------

    /**
     * The ordered, reprojected sequential resource the first step writes and the second step reads.
     *
     * @return the resolved resource, never {@code null}
     */
    Path transactionWorkSequentialResource() {
        return resolve(this.transactionWorkSequentialName);
    }

    /**
     * Execution-scoped ordered sequential resource shared only by this execution's first two steps.
     *
     * @param jobExecutionId owning job execution
     * @return completed transient sequential resource
     */
    Path transactionWorkSequentialResource(final long jobExecutionId) {
        return StagedGenerationStore.generationPath(Path.of(this.stagingDirectory),
                this.transactionWorkSequentialName, jobExecutionId);
    }

    /**
     * The plain-text statement output, whose records are {@value #STATEMENT_RECORD_LENGTH} bytes.
     *
     * @return the resolved resource, never {@code null}
     */
    Path statementOutputResource() {
        return resolve(this.statementOutputName);
    }

    /** Execution-scoped durable plain-text statement generation. */
    Path statementOutputGeneration(final long jobExecutionId) {
        return StagedGenerationStore.generationPath(Path.of(this.stagingDirectory),
                this.statementOutputName, jobExecutionId);
    }

    /**
     * The HTML statement output, whose records are {@value #HTML_RECORD_LENGTH} bytes - the resolved
     * width of the two the member declares.
     *
     * @return the resolved resource, never {@code null}
     */
    Path htmlStatementOutputResource() {
        return resolve(this.htmlStatementOutputName);
    }

    /** Execution-scoped durable HTML statement generation. */
    Path htmlStatementOutputGeneration(final long jobExecutionId) {
        return StagedGenerationStore.generationPath(Path.of(this.stagingDirectory),
                this.htmlStatementOutputName, jobExecutionId);
    }

    /** Reads the current step execution from a tasklet context. */
    private static StepExecution stepExecutionOf(final ChunkContext chunkContext) {
        Objects.requireNonNull(chunkContext, "chunkContext");
        return Objects.requireNonNull(chunkContext.getStepContext().getStepExecution(),
                "the framework must have opened a step execution before its tasklet runs");
    }

    /** Reads the framework identifier that makes every local resource execution-scoped. */
    private static long jobExecutionIdOf(final ChunkContext chunkContext) {
        return Objects.requireNonNull(stepExecutionOf(chunkContext).getJobExecutionId(),
                "the framework must have assigned a job execution identifier before a step runs")
                .longValue();
    }

    /**
     * Resolves one logical name within the configured staging directory.
     *
     * @param logicalName the logical resource name; must not be {@code null}
     * @return the resolved resource
     */
    private Path resolve(final String logicalName) {
        return Path.of(this.stagingDirectory).resolve(logicalName);
    }

    /**
     * Validates a configured logical name, because an absent or blank one would resolve to the staging
     * directory itself and a step would then write over a directory rather than a resource.
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
                    + " directory itself rather than to a resource");
        }
        return value;
    }

    // -----------------------------------------------------------------------------------------------
    // The two ordering keys. Each is addressed at the position and length its own specification
    // declares, and each is read out of the record image by the shared fixed-width reader, so no offset
    // arithmetic and no slicing happens in this class.
    // -----------------------------------------------------------------------------------------------

    /**
     * The first ordering key, compared as <strong>character</strong> data exactly as this job's own
     * specification declares - which is why it is read as characters, never decoded and never parsed.
     *
     * @param recordImage the whole record image, exactly {@value #WORK_RECORD_LENGTH} encoded bytes;
     *                    must not be {@code null}
     * @return the field as it stands in the record, exactly {@value #CARD_NUMBER_SORT_LENGTH}
     *         characters, never {@code null}
     */
    private static String cardNumberSortField(final String recordImage) {
        return sortField(recordImage, FIELD_TRAN_CARD_NUM,
                TransactionRecordMapper.TRAN_CARD_NUM_OFFSET, CARD_NUMBER_SORT_LENGTH);
    }

    /**
     * The second ordering key, likewise compared as <strong>character</strong> data.
     *
     * @param recordImage the whole record image, exactly {@value #WORK_RECORD_LENGTH} encoded bytes;
     *                    must not be {@code null}
     * @return the field as it stands in the record, exactly {@value #TRANSACTION_ID_SORT_LENGTH}
     *         characters, never {@code null}
     */
    private static String transactionIdSortField(final String recordImage) {
        return sortField(recordImage, FIELD_TRAN_ID, TransactionRecordMapper.TRAN_ID_OFFSET,
                TRANSACTION_ID_SORT_LENGTH);
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
                .of(TransactionRecordMapper.ARTEFACT, recordImage, WORK_RECORD_LENGTH)
                .field(fieldName, offset, length);
    }

    // -----------------------------------------------------------------------------------------------
    // THE REPROJECTION. This file's own parity obligation, expressed entirely through the shared
    // fixed-width primitives and the layout owner's offsets.
    // -----------------------------------------------------------------------------------------------

    /**
     * Reprojects one record image into the work resource's layout, reproducing the measured
     * specification segment for segment.
     *
     * <p>Three placements and one pad, in the order the specification declares them: the card number to
     * the front, the record's leading portion after it, and then
     * {@value #TIMESTAMP_SEGMENT_LENGTH} bytes taken from the origination timestamp's own offset. That
     * third segment covers the origination timestamp in full and stops
     * {@value #TRUNCATED_PROCESSING_TIMESTAMP_BYTES} bytes short of the end of the processing
     * timestamp, and the {@value #DROPPED_TRAILING_FILLER_LENGTH}-byte trailing filler run is not
     * carried at all.
     *
     * <p><strong>The truncation is faithful and is not repaired here.</strong> Widening the third
     * segment would produce a work record the estate never built.
     *
     * <p>The remaining {@value #BLANK_PAD_LENGTH} bytes are blank pad, which carries the
     * {@value #PROJECTED_CONTENT_LENGTH} bytes of content out to the declared
     * {@value #WORK_RECORD_LENGTH}-byte fixed record length. The builder's buffer is space-initialised,
     * so naming the pad run restates the default at the call site rather than changing it - and it keeps
     * the placements adding up to the whole record, which is how a missing segment is noticed.
     *
     * <p>Package-visible so a test in this package can prove the projection directly, including that
     * the leading {@value #WORK_RESOURCE_KEY_LENGTH} bytes of the result are the work resource's key.
     *
     * @param recordImage the whole source record image, exactly {@value #WORK_RECORD_LENGTH} encoded
     *                    bytes; must not be {@code null}
     * @return the projected record image, exactly {@value #WORK_RECORD_LENGTH} encoded bytes, never
     *         {@code null}
     */
    static String reproject(final String recordImage) {
        return StatementWorkRecordMapper.fromTransactionRecord(recordImage);
    }

    /**
     * Reads the work resource's key out of a projected record image.
     *
     * <p>The cluster declares its key at offset {@value #WORK_RESOURCE_KEY_OFFSET} for
     * {@value #WORK_RESOURCE_KEY_LENGTH} bytes, so the key is the record's leading substring and the
     * shared reader's own key accessor is what returns it. Nothing here slices.
     *
     * <p>Because the projection places the card number at the front and the transaction identifier
     * immediately behind it, this key is the two ordering keys concatenated - which is why the work
     * resource's key sequence and the first step's ordering agree without either being restated.
     *
     * <p>Package-visible so a test in this package can prove the key is those
     * {@value #WORK_RESOURCE_KEY_LENGTH} bytes and no others.
     *
     * @param projectedImage a projected record image, exactly {@value #WORK_RECORD_LENGTH} encoded
     *                       bytes; must not be {@code null}
     * @return the key, exactly {@value #WORK_RESOURCE_KEY_LENGTH} characters, never {@code null}
     */
    static String workResourceKey(final String projectedImage) {
        return StatementWorkRecordMapper.key(projectedImage);
    }

    // -----------------------------------------------------------------------------------------------
    // The two regulated-field operations this composition root supplies to the fourth step's stage.
    // -----------------------------------------------------------------------------------------------

    /**
     * Recovers the cleartext of one regulated customer identifier so that a customer record image can
     * be composed.
     *
     * <p>The record layout applies <em>one</em> operation to <em>both</em> regulated identifiers, while
     * the schema seals each of them under its own column binding so that a value written for one column
     * cannot be replayed into the other. Resolving that binding is therefore the composition root's
     * work and not the layout's: this method asks the field-protection owner for the binding of the
     * national identifier first and, when the stored value reports a different binding, for the
     * government-issued one. Both names are the owner's own published constants, the unbinding stays
     * with the owner, and no separator, envelope prefix or key is known here.
     *
     * <p>A value carrying neither binding is a value that was not written for either regulated column,
     * and the second attempt's own failure is the right report for it - so nothing is swallowed and
     * nothing is substituted.
     *
     * <p>Raised for the decision log: a layout seam that carries one operation for two differently
     * bound columns is why this resolution exists at all.
     *
     * @param envelope the stored protected value; must not be {@code null}
     * @return the recovered cleartext, never {@code null}
     */
    private String revealRegulatedIdentifier(final String envelope) {
        Objects.requireNonNull(envelope, "envelope");
        try {
            return this.fieldEncryption.reveal(SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD,
                    envelope);
        } catch (final IllegalStateException notTheNationalIdentifier) {
            // The stored value reports a binding other than the national identifier's, which for a
            // regulated customer column leaves exactly one possibility. The first attempt's report is
            // retained as the cause so a value bound to neither column is still explicable.
            LOGGER.debug("{} {}: a regulated identifier is bound to the government-issued column"
                            + " rather than the national one; failureChain={}",
                    StatementProcessor.LEGACY_JOB, StatementProcessor.LEGACY_STATEMENT_STEP,
                    FailureDiagnostics.failureChainOf(notTheNationalIdentifier));
            return this.fieldEncryption.reveal(
                    SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD, envelope);
        }
    }

    /**
     * Seals one regulated customer identifier back into the module's protected-value envelope when a
     * composed record image is read into an entity.
     *
     * <p>The unbound seal, because the layout seam supplies no column name and the entity a statement
     * run produces is transient: it is composed from an image, read for the statement, and never
     * written to a column. A column binding exists to stop a value written for one column being
     * replayed into another, and nothing here writes to a column at all.
     *
     * <p>The identity operation is not a substitute for this: the entity refuses cleartext outright.
     *
     * @param cleartext the recovered cleartext; must not be {@code null}
     * @return the protected-value envelope, never {@code null}
     */
    private String sealRegulatedIdentifier(final String cleartext) {
        Objects.requireNonNull(cleartext, "cleartext");
        return this.fieldEncryption.protect(cleartext);
    }

    // -----------------------------------------------------------------------------------------------
    // Staged-resource mechanics, shared by the four lifecycles.
    // -----------------------------------------------------------------------------------------------

    /**
     * Opens one staged resource for writing, creating its container if the staging area does not yet
     * exist and truncating any earlier content of the same absolute resource.
     *
     * <p>The truncation is what absorbs {@value #LEGACY_ABSORBED_STEP}: a delete followed by a new
     * allocation and a write leaves exactly what a create-and-truncate leaves.
     *
     * @param target the resource to write; must not be {@code null}
     * @return the writer
     * @throws IOException if the resource cannot be created or opened
     */
    private static BufferedWriter openForWriting(final Path target) throws IOException {
        Objects.requireNonNull(target, "target");
        // Owner-only from the first byte, never through a planted link, and never onto a file a
        // previous run left: a statement generation carries a cardholder's name, address and every
        // transaction on their account, and a default umask would stage all of it world-readable.
        // See docs/decision-log.md entry DL-178.
        return SecureStagedFiles.newWriter(target, StandardCharsets.US_ASCII);
    }

    /**
     * Opens one staged resource for reading.
     *
     * @param source the resource to read; must not be {@code null}
     * @return the reader
     * @throws IOException if the resource cannot be opened
     */
    private static BufferedReader openForReading(final Path source) throws IOException {
        Objects.requireNonNull(source, "source");
        // Framed by width and never by line: every resource this method opens carries fixed-length
        // records with nothing written between them, exactly as the job stream's own DDs declare.
        // See docs/decision-log.md entry DL-213.
        return FixedWidthFlatFileReaderFactory.fixedWidthReader(source, WORK_RECORD_LENGTH);
    }

    /**
     * Proves that one record is exactly the declared record length in <strong>encoded bytes</strong>.
     *
     * <p>Measured on the encoded array rather than on a character count, because it is a byte count the
     * record length declares. A record of the wrong width would leave every downstream reader of that
     * resource reading the wrong field, and for the two statement outputs it would fail a byte-for-byte
     * comparison in a way no semantic check would catch.
     *
     * <p>Width alone does not make a record right and this proof does not pretend otherwise: the encoder
     * substitutes a single replacement byte for a character it cannot represent, which keeps the count
     * correct and makes the content wrong. Purity is proved by the layers that build these records - the
     * layout owner for the work resource and the statement stage for both outputs - and is deliberately
     * not proved a second time here.
     *
     * <p>Package-visible so a test in this package can drive the proof itself, which is the only way to
     * reach its refusal: every producer this file writes guarantees its own width.
     *
     * @param image the record about to be written; must not be {@code null}
     * @param declaredLength the record length the resource declares
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
                    + " resource reading the wrong field");
        }
    }

    /**
     * Hands back a handle after a failure without emitting a legacy diagnostic and without abending.
     *
     * <p>Non-observable runtime adaptation rather than a second close sequence: the mainframe enclave
     * released its own handles and the virtual machine does not.
     *
     * <p>Package-visible for the same reason the width proof is: both of its branches exist for a
     * failure path, and a test drives them directly rather than provoking them through a lifecycle.
     *
     * @param handle the handle to release, or {@code null} when the failure preceded its acquisition
     * @param resource the data definition the handle belongs to, for the diagnostic
     */
    static void releaseQuietly(final AutoCloseable handle, final String resource) {
        if (handle == null) {
            return;
        }
        try {
            handle.close();
        } catch (final Exception release) {
            LOGGER.warn("HANDLE ON {} COULD NOT BE RELEASED AFTER A FAILURE; THE RUN HAS ALREADY"
                    + " REPORTED ITS OWN DIAGNOSTIC; failureChain={}", resource,
                    FailureDiagnostics.failureChainOf(release));
        }
    }

    // -----------------------------------------------------------------------------------------------
    // The transient work resource: the whole of what the absorbed step's cluster becomes.
    // -----------------------------------------------------------------------------------------------

    /**
     * The transient work resource of one job execution: the ordered result the second step loads and the
     * fourth step names as its input.
     *
     * <p>Held in key sequence, because the cluster it stands in for is an indexed cluster whose key is
     * {@value #WORK_RESOURCE_KEY_LENGTH} bytes at offset {@value #WORK_RESOURCE_KEY_OFFSET}. That key is
     * the card number followed by the transaction identifier, so key sequence over fixed-width
     * concatenated fields is the same two-key character ordering the first step applies - the two agree
     * by construction rather than by restatement.
     *
     * <p>An interface rather than a class because the bean that publishes it is job-scoped and a scoped
     * bean declared by an interface is proxied through that interface, which keeps this path clear of
     * generated subclasses.
     *
     * <p>It carries record <em>content</em> and nothing about files, clusters or object stores: it is
     * created and discarded inside one submission, exactly as the cluster is, and is <strong>not part of
     * the persistent schema</strong>.
     */
    public interface TransactionWorkResource {

        /**
         * Loads one projected record, keyed by the {@value #WORK_RESOURCE_KEY_LENGTH} bytes the
         * projection placed at the front of it.
         *
         * @param projectedRecord the projected record image, exactly {@value #WORK_RECORD_LENGTH}
         *                        encoded bytes; must not be {@code null}
         * @throws NullPointerException if {@code projectedRecord} is {@code null}
         * @throws IllegalStateException if the record is not the declared width, if its key repeats the
         *                               key last admitted - an indexed cluster admits one record per key
         *                               - if its key falls below the key last admitted, or if the
         *                               resource has already been frozen for generation
         */
        void load(String projectedRecord);

        /**
         * Applies an action to every loaded record, in key sequence, one record at a time.
         *
         * <p>Bounded by construction: the traversal holds the record it is presenting and nothing else,
         * so a caller that wants a count, a width proof or a sequence check pays for one record rather
         * than for the whole cluster. It freezes the resource exactly as {@link #snapshot()} does,
         * because reading a sequential resource requires that nothing is still writing to it.
         *
         * @param consumer the action to apply to each record in key sequence; must not be {@code null}
         * @throws NullPointerException if {@code consumer} is {@code null}
         */
        void forEachRecordInKeySequence(Consumer<String> consumer);

        /**
         * How many records the resource holds.
         *
         * @return the count, never negative
         */
        int recordCount();

        /**
         * Freezes the key-sequenced contents for statement generation and opens one forward walk over
         * them.
         *
         * <p>Freezing is what detaches the fourth step from the second: once the resource is frozen no
         * further record may be loaded, so STEP040 observes exactly the sequence STEP020 built and a
         * faulty caller is refused rather than silently changing what generation sees. Freezing is
         * idempotent, so several walks may be opened over the same frozen sequence and each advances
         * independently.
         *
         * @return a forward source over the frozen projected records, never {@code null}
         */
        StatementTransactionSource snapshot();

        /**
         * Deletes the transient resource, which is the second half of the lifetime the absorbed
         * definition step declares.
         *
         * <p>The measured step deletes the cluster and defines it again within one submission, so the
         * artefact exists for exactly one job execution and no longer. This is the deletion: it is
         * invoked when the job-scoped bean is destroyed, it is idempotent, and it never raises - a
         * cluster that cannot be scratched is a diagnostic, not a job failure, exactly as a deallocation
         * disposition is.
         */
        void discard();
    }

    /**
     * The sequential-file realisation of the transient work resource.
     *
     * <p>Held on disk rather than in the heap, because the cluster it stands in for is a disk cluster
     * whose size is bounded by the transaction master rather than by available memory. One record is in
     * hand at a time in both directions: the load writes each record as it arrives and the generation
     * walk serves each record as it is asked for, so neither the loaded dataset nor a copy of it is ever
     * resident.
     *
     * <p>Ordered by arrival, and the arrival order is <em>proved</em> to be key order rather than
     * imposed on top of it. Every record's key must strictly exceed the key last admitted, which is the
     * same rule a keyed load into an empty cluster enforces: the copy utility the second step replaces
     * requires its input in ascending key sequence and refuses a record that breaks it. The first step
     * already establishes that sequence, so the rule confirms the two agree and reports it loudly if
     * they ever do not - where a sorted map would have silently repaired the disagreement and hidden a
     * defect in the ordering step.
     *
     * <p>Per-execution state on a per-execution object. It is never a field of a singleton, and it is
     * not thread safe because nothing in this job is concurrent.
     *
     * <p>See {@code docs/decision-log.md} entry DL-176 for the staged-file substitution and for why the
     * resource is deleted with the execution that minted it.
     */
    static final class FileBackedTransactionWorkResource implements TransactionWorkResource {

        /** Name prefix of the sequential file one execution's cluster is held in. */
        private static final String RESOURCE_NAME_PREFIX = "createStatementJob.workResource.";

        /** Name suffix of the sequential file one execution's cluster is held in. */
        private static final String RESOURCE_NAME_SUFFIX = ".dat";

        /** The staging root the resource is minted within. */
        private final Path stagingDirectory;

        /** The sequential resource, or {@code null} until the first record is admitted. */
        private Path resource;

        /** Handle on the resource while it is being loaded, or {@code null}. */
        private BufferedWriter composer;

        /** The key last admitted, or {@code null} before the first record. */
        private String lastKeyAdmitted;

        /** Records admitted so far. */
        private int records;

        /** Whether the resource has been frozen for generation. */
        private boolean frozen;

        /**
         * @param stagingDirectory the staging root the resource is minted within; must not be
         *                         {@code null}
         */
        FileBackedTransactionWorkResource(final Path stagingDirectory) {
            this.stagingDirectory = Objects.requireNonNull(stagingDirectory, "stagingDirectory");
        }

        @Override
        public void load(final String projectedRecord) {
            Objects.requireNonNull(projectedRecord, "projectedRecord");
            requireEncodedWidth(projectedRecord, WORK_RECORD_LENGTH, DD_LOAD_OUTPUT);
            requireStillLoading();

            // Every refusal is decided before a byte is written, so a refused record leaves the
            // resource exactly as it was - the same guarantee the key check gave when the records were
            // held in a map.
            final String key = workResourceKey(projectedRecord);
            requireAscendingKey(key);

            try {
                if (this.composer == null) {
                    beginLoad();
                }
                // Nothing is written between two records: the resource is fixed-length and its
                // consumers frame it by width - see docs/decision-log.md entry DL-213.
                this.composer.write(projectedRecord);
            } catch (final IOException failure) {
                throw new UncheckedIOException("unable to write a record to the "
                        + TRANSIENT_WORK_RESOURCE_NAME + " work resource", failure);
            }
            this.lastKeyAdmitted = key;
            this.records++;
        }

        @Override
        public void forEachRecordInKeySequence(final Consumer<String> consumer) {
            Objects.requireNonNull(consumer, "consumer");
            final StatementTransactionSource walk = snapshot();
            for (int position = 0;; position++) {
                final Optional<String> record = walk.readAt(position);
                if (record.isEmpty()) {
                    return;
                }
                consumer.accept(record.get());
            }
        }

        @Override
        public int recordCount() {
            return this.records;
        }

        @Override
        public StatementTransactionSource snapshot() {
            freeze();
            return new SequentialWorkResourceWalk(this.resource);
        }

        @Override
        public void discard() {
            closeComposerQuietly();
            this.frozen = true;
            final Path scratched = this.resource;
            this.resource = null;
            if (scratched == null) {
                return;
            }
            try {
                Files.deleteIfExists(scratched);
            } catch (final IOException failure) {
                LOGGER.warn("THE {} WORK RESOURCE COULD NOT BE SCRATCHED; THE EXECUTION THAT MINTED"
                        + " IT HAS ALREADY FINISHED AND ITS RESULT IS UNAFFECTED; failureChain={}",
                        TRANSIENT_WORK_RESOURCE_NAME, FailureDiagnostics.failureChainOf(failure));
            }
        }

        /** Refuses a record presented after the resource was frozen for generation. */
        private void requireStillLoading() {
            if (this.frozen) {
                throw new IllegalStateException("a record was presented to the "
                        + TRANSIENT_WORK_RESOURCE_NAME + " work resource after it was frozen for"
                        + " generation; the load step completes before the generation step opens it,"
                        + " so what generation reads is exactly what the load step built");
            }
        }

        /**
         * Refuses a key that repeats or falls below the key last admitted.
         *
         * <p>Neither diagnostic carries the key, because a key opens with a card number.
         *
         * @param key the key of the record being presented
         */
        private void requireAscendingKey(final String key) {
            if (this.lastKeyAdmitted == null) {
                return;
            }
            final int order = key.compareTo(this.lastKeyAdmitted);
            if (order == 0) {
                throw new IllegalStateException("a second record was presented for a key the "
                        + TRANSIENT_WORK_RESOURCE_NAME + " work resource already holds; an indexed"
                        + " cluster keyed at " + WORK_RESOURCE_KEY_LENGTH + " byte(s) admits one"
                        + " record per key, so the ordering step cannot have produced this pair");
            }
            if (order < 0) {
                throw new IllegalStateException("a record was presented out of key sequence to the "
                        + TRANSIENT_WORK_RESOURCE_NAME + " work resource; a keyed load into an empty"
                        + " cluster admits ascending keys only, so the ordering step cannot have"
                        + " produced this pair");
            }
        }

        /** Mints the sequential resource for this execution and opens it for loading. */
        private void beginLoad() throws IOException {
            // Minted rather than named: the artefact belongs to one execution, a unique name is what
            // makes that true without a job parameter, and the utility that mints it creates both the
            // directory and the file owner-only.
            this.resource = SecureStagedFiles.newTemporaryFile(this.stagingDirectory,
                    RESOURCE_NAME_PREFIX, RESOURCE_NAME_SUFFIX);
            this.composer = openForWriting(this.resource);
        }

        /** Closes the load handle so the resource can be read, once. */
        private void freeze() {
            if (this.frozen) {
                return;
            }
            if (this.composer != null) {
                try {
                    this.composer.flush();
                    this.composer.close();
                } catch (final IOException failure) {
                    throw new UncheckedIOException("unable to close the "
                            + TRANSIENT_WORK_RESOURCE_NAME + " work resource for reading", failure);
                } finally {
                    this.composer = null;
                }
            }
            this.frozen = true;
        }

        /** Closes the load handle without raising, for the scratch path. */
        private void closeComposerQuietly() {
            releaseQuietly(this.composer, TRANSIENT_WORK_RESOURCE_NAME);
            this.composer = null;
        }
    }

    /**
     * One forward walk over the frozen transient work resource.
     *
     * <p>Sequential and forward-only, which is exactly how the generation step reads it: the position it
     * asks for advances by one for every record it consumes, and it never returns to a position it has
     * left. The position most recently served may be asked for again and is served from the record in
     * hand without touching the resource, which is what lets an open position at the first record
     * without consuming it. A position below the one last served is refused, because serving it would
     * require re-reading the resource from its start.
     *
     * <p>The handle is opened on the first read rather than in the constructor, and closed the moment
     * the resource is exhausted - so a walk that is created and never read holds nothing, and a walk
     * that is read to end of file leaves nothing open.
     */
    private static final class SequentialWorkResourceWalk implements StatementTransactionSource {

        /** Position value standing for "no record has been served yet". */
        private static final int BEFORE_FIRST_RECORD_POSITION = -1;

        /** The frozen resource, or {@code null} when the cluster was never written. */
        private final Path resource;

        /** Handle on the resource while records remain, or {@code null}. */
        private BufferedReader reader;

        /** Position of the record in hand. */
        private int servedPosition = BEFORE_FIRST_RECORD_POSITION;

        /** The record in hand, or {@code null} before the first read and after exhaustion. */
        private String served;

        /** Whether the resource has been read to end of file. */
        private boolean exhausted;

        /**
         * @param resource the frozen resource, or {@code null} for a cluster that holds nothing
         */
        SequentialWorkResourceWalk(final Path resource) {
            this.resource = resource;
        }

        @Override
        public Optional<String> readAt(final int position) {
            if (position < 0) {
                throw new IllegalArgumentException(
                        "statement transaction position must not be negative: " + position);
            }
            if (position == this.servedPosition) {
                return Optional.ofNullable(this.served);
            }
            if (position < this.servedPosition) {
                throw new IllegalStateException("the " + TRANSIENT_WORK_RESOURCE_NAME
                        + " work resource is read forward: position " + position
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
            if (this.resource == null) {
                this.exhausted = true;
                return false;
            }
            try {
                if (this.reader == null) {
                    this.reader = openForReading(this.resource);
                }
                final String next = this.reader.readLine();
                if (next == null) {
                    this.exhausted = true;
                    this.served = null;
                    this.reader.close();
                    this.reader = null;
                    return false;
                }
                this.served = next;
                this.servedPosition++;
                return true;
            } catch (final IOException failure) {
                throw new UncheckedIOException("unable to read the "
                        + TRANSIENT_WORK_RESOURCE_NAME + " work resource", failure);
            }
        }
    }

    /**
     * One statement output data definition: its name, the resource it resolves to, and the record length
     * that resource declares.
     *
     * @param ddName the legacy data-definition name; must not be {@code null} or blank
     * @param resource the resolved resource; must not be {@code null}
     * @param recordLength the declared record length in encoded bytes; must be at least one
     */
    record StatementOutput(String ddName, Path resource, int recordLength) {

        StatementOutput {
            Objects.requireNonNull(ddName, "ddName");
            Objects.requireNonNull(resource, "resource");
            if (ddName.isBlank()) {
                throw new IllegalArgumentException("ddName must name a data definition");
            }
            if (recordLength < 1) {
                throw new IllegalArgumentException(
                        "recordLength must be at least one encoded byte: " + recordLength);
            }
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Step one: the ordering and the reprojection.
    // -----------------------------------------------------------------------------------------------

    /**
     * One pass of the legacy external sort: read the transaction master in its own key sequence, order
     * it by this job's two character-typed keys, reproject every record, and write the ordered sequential
     * result.
     *
     * <p>The work area accumulates before anything is emitted, because an ordering pass cannot write its
     * first record until it has read its last. It is per-execution state on a per-execution object and
     * never a field of a singleton.
     *
     * <p>Two properties of this class are the parity core of the file and must not be relaxed. The
     * ordering applies
     * {@link CreateStatementJobConfig#CARD_NUMBER_THEN_TRANSACTION_ID_CHARACTER_ASCENDING}, which is
     * private to the enclosing class precisely so it cannot be shared with the job that types the same
     * field as zoned decimal. And the reprojection is applied on the way out, after the ordering, which
     * is the order the specification declares them in - ordering the projected records instead would
     * order by the projected positions and not by the declared ones.
     */
    static final class OrderAndReprojectProgram extends AbstractCobolStep<Transaction> {

        /** The transaction master being ordered. */
        private final TransactionScanRepository transactionScanRepository;

        /** The ordered, reprojected sequential resource this execution writes. */
        private final Path projectedResource;

        /** Disk-backed bounded work area, standing in for the sort utility's work datasets. */
        private ExternalStringSorter sorter;

        /** Read position over the master, in cluster-key sequence. */
        private Iterator<Transaction> masterCursor;

        /** Handle on the resource being written. */
        private BufferedWriter writer;

        /** Records written, reported once the pass completes. */
        private long recordsProjected;

        /**
         * @param meterRegistry the registry the lifecycle is timed on; must not be {@code null}
         * @param clock the clock stamping the lifecycle boundaries; must not be {@code null}
         * @param transactionScanRepository bounded transaction-master scan; must not be {@code null}
         * @param projectedResource the resource to write; must not be {@code null}
         */
        OrderAndReprojectProgram(final MeterRegistry meterRegistry, final Clock clock,
                final TransactionScanRepository transactionScanRepository,
                final Path projectedResource) {

            super(StatementProcessor.LEGACY_SORT_STEP, meterRegistry, clock);
            this.transactionScanRepository = Objects.requireNonNull(
                    transactionScanRepository, "transactionScanRepository");
            this.projectedResource = Objects.requireNonNull(projectedResource, "projectedResource");
        }

        @Override
        protected void openResources() {
            openResource(DD_SORT_INPUT, () -> {
                this.masterCursor = new BoundedKeysetIterator<>("", KEYSET_PAGE_SIZE,
                        (cursor, size) -> this.transactionScanRepository
                                .findByTranIdGreaterThanOrderByTranIdAsc(
                                        cursor, Limit.of(size.intValue())),
                        Transaction::getTranId, Comparator.naturalOrder());
                return FileStatus.SUCCESS.getCode();
            });

            openResource(DD_SORT_OUTPUT, () -> {
                this.writer = openForWriting(this.projectedResource);
                this.sorter = new ExternalStringSorter(
                        CARD_NUMBER_THEN_TRANSACTION_ID_CHARACTER_ASCENDING,
                        ExternalStringSorter.DEFAULT_RECORDS_PER_RUN);
                return FileStatus.SUCCESS.getCode();
            });
        }

        @Override
        protected Optional<Transaction> readNextRecord() {
            return this.<Transaction>readRecord(DD_SORT_INPUT, () -> {
                if (this.masterCursor.hasNext()) {
                    return IoResult.of(FileStatus.SUCCESS.getCode(), this.masterCursor.next());
                }
                return IoResult.endOfFile();
            });
        }

        @Override
        protected void processRecord(final Transaction record) {
            // The record image, and with it every offset of the layout, comes from the utility layer;
            // this step renders and never slices. The image is the unprojected one on purpose: the
            // specification orders the input record and reprojects afterwards.
            final String image = TransactionRecordMapper.toRecord(record);
            requireEncodedWidth(image, WORK_RECORD_LENGTH, DD_SORT_INPUT);
            this.sorter.add(image);
        }

        @Override
        protected void closeResources() {
            closeResource(DD_SORT_INPUT, () -> {
                // The input side holds nothing open: the cursor iterates an already-materialised
                // sequence, so releasing it is dropping the reference.
                this.masterCursor = null;
                return FileStatus.SUCCESS.getCode();
            });

            this.sorter.writeTo(this::writeProjectedRecord);

            closeResource(DD_SORT_OUTPUT, () -> {
                this.writer.flush();
                this.writer.close();
                return FileStatus.SUCCESS.getCode();
            });

            LOGGER.info("{} ORDERED {} RECORD(S) BY {} AT POSITION {} FOR {} BYTE(S) ASCENDING THEN"
                            + " {} AT POSITION {} FOR {} BYTE(S) ASCENDING, BOTH AS CHARACTER DATA,"
                            + " AND WROTE {} RECORD(S) OF {} CONTENT BYTE(S) WITHIN {} BYTE(S) TO {}",
                    StatementProcessor.LEGACY_SORT_STEP, this.recordsProjected,
                    FIELD_TRAN_CARD_NUM, CARD_NUMBER_SORT_POSITION, CARD_NUMBER_SORT_LENGTH,
                    FIELD_TRAN_ID, TRANSACTION_ID_SORT_POSITION, TRANSACTION_ID_SORT_LENGTH,
                    this.recordsProjected, PROJECTED_CONTENT_LENGTH, WORK_RECORD_LENGTH,
                    DD_SORT_OUTPUT);
            LOGGER.info("{} TRUNCATED {} BYTE(S) OF {} AND DROPPED {} BYTE(S) OF TRAILING FILLER PER"
                            + " RECORD, REPRODUCING THE PROJECTION THE JOB STREAM DECLARES",
                    StatementProcessor.LEGACY_SORT_STEP, TRUNCATED_PROCESSING_TIMESTAMP_BYTES,
                    FIELD_TIMESTAMP_SEGMENT, DROPPED_TRAILING_FILLER_LENGTH);
        }

        @Override
        protected void releaseResources() {
            releaseQuietly(this.writer, DD_SORT_OUTPUT);
            releaseQuietly(this.sorter, "SORTWK");
        }

        private void writeProjectedRecord(final String ordered) {
            writeRecord(DD_SORT_OUTPUT, () -> {
                final String projected = reproject(ordered);
                requireEncodedWidth(projected, WORK_RECORD_LENGTH, DD_SORT_OUTPUT);
                this.writer.write(projected);
                this.recordsProjected++;
                return FileStatus.SUCCESS.getCode();
            });
        }

        /**
         * Applies an action to each ordered, projected record image, one at a time, for a caller that
         * drives the lifecycle directly.
         *
         * <p>Bounded by construction: the traversal holds the record it is presenting and nothing else,
         * so a caller that wants a count, a width proof or a sequence check pays for one record rather
         * than for the whole generation. See {@code docs/decision-log.md} entry DL-176.
         *
         * @param consumer the action to apply to each image in ordering sequence; must not be
         *                 {@code null}
         * @throws NullPointerException if {@code consumer} is {@code null}
         */
        void forEachOrderedRecord(final Consumer<String> consumer) {
            Objects.requireNonNull(consumer, "consumer");
            try (BufferedReader ordered = openForReading(this.projectedResource)) {
                String image = ordered.readLine();
                while (image != null) {
                    consumer.accept(image);
                    image = ordered.readLine();
                }
            } catch (final IOException failure) {
                throw new UncheckedIOException(
                        "unable to read the projected statement work generation", failure);
            }
        }

        /**
         * Records this pass wrote, for a caller that drives the lifecycle directly.
         *
         * @return the count, never negative
         */
        long recordsProjected() {
            return this.recordsProjected;
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Step two: materialising the transient work resource.
    // -----------------------------------------------------------------------------------------------

    /**
     * One pass of the legacy copy utility: read the ordered sequential resource and load every record
     * into the transient work resource under its own key.
     *
     * <p><strong>Nothing is invoked.</strong> The utility is replaced, not called: no process is spawned,
     * no control statement is composed and no external tool is addressed. Reading a resource and writing
     * an ordered result is the whole of it.
     *
     * <p>The key is read through the shared reader's own key accessor, because the cluster declares its
     * key as the record's leading substring; this class performs no slicing.
     */
    static final class LoadWorkResourceProgram extends AbstractCobolStep<String> {

        /** The ordered sequential resource this execution reads. */
        private final Path projectedResource;

        /** The work resource this execution loads. */
        private final TransactionWorkResource workResource;

        /** Handle on the resource being read. */
        private BufferedReader reader;

        /** Records loaded, reported once the pass completes. */
        private long recordsLoaded;

        /**
         * @param meterRegistry the registry the lifecycle is timed on; must not be {@code null}
         * @param clock the clock stamping the lifecycle boundaries; must not be {@code null}
         * @param projectedResource the resource to read; must not be {@code null}
         * @param workResource the work resource to load; must not be {@code null}
         */
        LoadWorkResourceProgram(final MeterRegistry meterRegistry, final Clock clock,
                final Path projectedResource, final TransactionWorkResource workResource) {

            super(StatementProcessor.LEGACY_LOAD_STEP, meterRegistry, clock);
            this.projectedResource = Objects.requireNonNull(projectedResource, "projectedResource");
            this.workResource = Objects.requireNonNull(workResource, "workResource");
        }

        @Override
        protected void openResources() {
            openResource(DD_LOAD_INPUT, () -> {
                this.reader = openForReading(this.projectedResource);
                return FileStatus.SUCCESS.getCode();
            });

            openResource(DD_LOAD_OUTPUT, () -> {
                // The work resource is minted empty by the job execution that owns it, so opening it is
                // proving it is the empty resource the absorbed definition step would have created. A
                // resource carrying records here would mean two loads shared one execution's cluster.
                if (this.workResource.recordCount() != 0) {
                    throw new IllegalStateException("the " + TRANSIENT_WORK_RESOURCE_NAME
                            + " work resource already holds " + this.workResource.recordCount()
                            + " record(s) before the load step opened it; it is defined and deleted"
                            + " within one submission and must therefore be empty at this point");
                }
                return FileStatus.SUCCESS.getCode();
            });
        }

        @Override
        protected Optional<String> readNextRecord() {
            return this.<String>readRecord(DD_LOAD_INPUT, () -> {
                final String next = this.reader.readLine();
                if (next == null) {
                    return IoResult.endOfFile();
                }
                return IoResult.of(FileStatus.SUCCESS.getCode(), next);
            });
        }

        @Override
        protected void processRecord(final String record) {
            writeRecord(DD_LOAD_OUTPUT, () -> {
                requireEncodedWidth(record, WORK_RECORD_LENGTH, DD_LOAD_INPUT);
                this.workResource.load(record);
                this.recordsLoaded++;
                return FileStatus.SUCCESS.getCode();
            });
        }

        @Override
        protected void closeResources() {
            closeResource(DD_LOAD_INPUT, () -> {
                this.reader.close();
                return FileStatus.SUCCESS.getCode();
            });

            closeResource(DD_LOAD_OUTPUT, () -> FileStatus.SUCCESS.getCode());

            LOGGER.info("{} LOADED {} RECORD(S) OF {} BYTE(S) FROM {} INTO {}, KEYED AT OFFSET {} FOR"
                            + " {} BYTE(S); THE RESOURCE NOW HOLDS {} RECORD(S) IN KEY SEQUENCE",
                    StatementProcessor.LEGACY_LOAD_STEP, this.recordsLoaded, WORK_RECORD_LENGTH,
                    DD_LOAD_INPUT, DD_LOAD_OUTPUT, WORK_RESOURCE_KEY_OFFSET,
                    WORK_RESOURCE_KEY_LENGTH, this.workResource.recordCount());
        }

        @Override
        protected void releaseResources() {
            releaseQuietly(this.reader, DD_LOAD_INPUT);
        }

        /**
         * Records this pass loaded, for a caller that drives the lifecycle directly.
         *
         * @return the count, never negative
         */
        long recordsLoaded() {
            return this.recordsLoaded;
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Step three: scratching the previous run's two statement outputs.
    // -----------------------------------------------------------------------------------------------

    /**
     * One pass of the legacy null program: allocate both statement outputs and let their disposition
     * scratch them.
     *
     * <p>The measured step transfers no data. What it does is allocate each output with a disposition
     * that deletes it at deallocation whether the step ends normally or abnormally, so its whole effect
     * is that the generation step allocates both anew. The translation keeps that shape: each output is
     * allocated when the step opens, enrolled as the read loop reaches it, and scratched when the step
     * closes - which is the point at which a deallocation disposition takes effect.
     *
     * <p>An output that is not there yet is not a failure. The first submission against a fresh staging
     * area finds neither output, and the legacy allocation with that disposition tolerates exactly that.
     */
    static final class ClearStatementOutputsProgram extends AbstractCobolStep<StatementOutput> {

        /** The two allocations, in the order the member declares them. */
        private final List<StatementOutput> allocations;

        /** Allocations the read loop has enrolled for scratching, in the order it reached them. */
        private final List<StatementOutput> enrolled = new ArrayList<>();

        /** How many allocations the read loop has served. */
        private int served;

        /** Allocations actually scratched, as opposed to found already absent. */
        private long resourcesScratched;

        /**
         * @param meterRegistry the registry the lifecycle is timed on; must not be {@code null}
         * @param clock the clock stamping the lifecycle boundaries; must not be {@code null}
         * @param allocations the two output allocations in declaration order; must not be {@code null}
         *                    and must hold exactly {@value CreateStatementJobConfig#STATEMENT_OUTPUT_COUNT}
         *                    entries
         */
        ClearStatementOutputsProgram(final MeterRegistry meterRegistry, final Clock clock,
                final List<StatementOutput> allocations) {

            super(StatementProcessor.LEGACY_CLEAR_STEP, meterRegistry, clock);
            Objects.requireNonNull(allocations, "allocations");
            if (allocations.size() != STATEMENT_OUTPUT_COUNT) {
                throw new IllegalArgumentException("the scratch step allocates exactly "
                        + STATEMENT_OUTPUT_COUNT + " output(s), as the member declares, but "
                        + allocations.size() + " were supplied");
            }
            this.allocations = List.copyOf(allocations);
        }

        @Override
        protected void openResources() {
            for (final StatementOutput allocation : this.allocations) {
                openResource(allocation.ddName(), () -> {
                    // The allocation, which is the whole of what the null program does on the way in:
                    // the container has to exist for the resource to be allocatable within it.
                    SecureStagedFiles.prepareContainerOf(allocation.resource());
                    return FileStatus.SUCCESS.getCode();
                });
            }
        }

        @Override
        protected Optional<StatementOutput> readNextRecord() {
            final boolean exhausted = this.served >= this.allocations.size();
            final StatementOutput candidate =
                    this.allocations.get(exhausted ? this.allocations.size() - 1 : this.served);
            return this.<StatementOutput>readRecord(candidate.ddName(), () -> {
                if (exhausted) {
                    return IoResult.endOfFile();
                }
                this.served++;
                return IoResult.of(FileStatus.SUCCESS.getCode(), candidate);
            });
        }

        @Override
        protected void processRecord(final StatementOutput record) {
            this.enrolled.add(record);
            LOGGER.info("{} ALLOCATED {} AT {} BYTE(S) PER RECORD WITH A DISPOSITION THAT SCRATCHES IT"
                            + " AT DEALLOCATION", StatementProcessor.LEGACY_CLEAR_STEP,
                    record.ddName(), record.recordLength());
        }

        @Override
        protected void closeResources() {
            for (final StatementOutput allocation : this.enrolled) {
                closeResource(allocation.ddName(), () -> {
                    if (Files.deleteIfExists(allocation.resource())) {
                        this.resourcesScratched++;
                    }
                    return FileStatus.SUCCESS.getCode();
                });
            }

            LOGGER.info("{} SCRATCHED {} OF {} ENROLLED OUTPUT(S); THE REMAINDER WERE ALREADY ABSENT,"
                            + " WHICH THE DECLARED DISPOSITION TOLERATES",
                    StatementProcessor.LEGACY_CLEAR_STEP, this.resourcesScratched,
                    this.enrolled.size());
        }

        /**
         * Allocations this pass scratched, for a caller that drives the lifecycle directly.
         *
         * @return the count, never negative
         */
        long resourcesScratched() {
            return this.resourcesScratched;
        }

        /**
         * Allocations this pass enrolled, in the order it reached them, for a caller that drives the
         * lifecycle directly.
         *
         * @return the enrolled allocations, never {@code null}
         */
        List<StatementOutput> enrolledAllocations() {
            return List.copyOf(this.enrolled);
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Step four: the statement generation.
    // -----------------------------------------------------------------------------------------------

    /**
     * One statement generation: open both outputs, hand a frozen snapshot of the projected work resource
     * and both output handles to the stage, and write every record it streams back at its declared width.
     *
     * <p>The two outputs are the only resources this lifecycle opens, because the program it stands in
     * for declares only those two in its own file section: all four of its inputs are opened by the
     * called subprogram through the dispatched phase name. The one item is therefore the complete frozen
     * transaction source rather than an individual record.
     *
     * <p>The read loop therefore delivers exactly one item and then reports end of file, which is what
     * the program does with a driving loop that lives inside the generator rather than out here.
     *
     * <p><strong>No statement logic lives here.</strong> The dispatcher, its six clauses in source order,
     * the mandatory re-entry after each phase transition, the bounded card table and both literal layouts
     * belong to the generator and its stage. This lifecycle composes them, proves the width of every
     * record that reaches a destination, and reports the counts.
     *
     * <p>Records arrive one at a time through the two sinks this lifecycle supplies, in the order the
     * legacy program writes them, so the two outputs receive their bytes interleaved exactly as the two
     * {@code WRITE} statements interleave them and neither output is ever held whole in memory.
     *
     * <p>An empty run is a legitimate outcome and is not converted into a failure: a cross-reference file
     * with no records produces the two empty outputs the legacy program produces for it.
     */
    static final class GenerateStatementsProgram extends AbstractCobolStep<StatementTransactionSource> {

        /** The stage one whole run is delegated to. */
        private final StatementProcessor statementProcessor;

        /**
         * The work resource whose contents this step snapshots as its input.
         *
         * <p>Its projected records reach the generator through the frozen source captured below and the
         * generator's own data-access collaborator. No live transaction query occurs after STEP010:
         * the sequence the first two steps established is the exact sequence generation sees.
         */
        private final TransactionWorkResource workResource;

        /** Frozen projected input captured after STEP020 completed and before generation starts. */
        private final StatementTransactionSource transactionSource;

        /** The plain-text statement output this execution writes. */
        private final Path statementResource;

        /** The HTML statement output this execution writes. */
        private final Path htmlResource;

        /** Handle on the plain-text output. */
        private BufferedWriter statementWriter;

        /** Handle on the HTML output. */
        private BufferedWriter htmlWriter;

        /** Whether the one frozen source item has been served. */
        private boolean transactionSourceServed;

        /** The run the stage produced, retained for the completion diagnostic. */
        private StatementRun run;

        /** Plain-text records written. */
        private long statementRecordsWritten;

        /** HTML records written. */
        private long htmlRecordsWritten;

        /**
         * Per-line transaction summaries the run emitted. Counted and dropped: the legacy program writes
         * no summary to any dataset, and a count is bounded where the summaries are not.
         */
        private long transactionSummariesObserved;

        /** Dispatcher entries the run emitted, counted for the completion diagnostic. */
        private long dispatcherEntriesObserved;

        /**
         * @param meterRegistry the registry the lifecycle is timed on; must not be {@code null}
         * @param clock the clock stamping the lifecycle boundaries; must not be {@code null}
         * @param statementProcessor the stage one whole run is delegated to; must not be {@code null}
         * @param workResource the work resource supplying the frozen input; must not be {@code null}
         * @param statementResource the plain-text output to write; must not be {@code null}
         * @param htmlResource the HTML output to write; must not be {@code null}
         */
        GenerateStatementsProgram(final MeterRegistry meterRegistry, final Clock clock,
                final StatementProcessor statementProcessor,
                final TransactionWorkResource workResource, final Path statementResource,
                final Path htmlResource) {

            super(StatementProcessor.LEGACY_STATEMENT_STEP, meterRegistry, clock);
            this.statementProcessor =
                    Objects.requireNonNull(statementProcessor, "statementProcessor");
            this.workResource = Objects.requireNonNull(workResource, "workResource");
            this.transactionSource = Objects.requireNonNull(workResource.snapshot(),
                    "workResource snapshot");
            this.statementResource = Objects.requireNonNull(statementResource, "statementResource");
            this.htmlResource = Objects.requireNonNull(htmlResource, "htmlResource");
        }

        @Override
        protected void openResources() {
            openResource(StatementProcessor.OUTPUT_DD_STMTFILE, () -> {
                this.statementWriter = openForWriting(this.statementResource);
                return FileStatus.SUCCESS.getCode();
            });

            openResource(StatementProcessor.OUTPUT_DD_HTMLFILE, () -> {
                this.htmlWriter = openForWriting(this.htmlResource);
                return FileStatus.SUCCESS.getCode();
            });
        }

        @Override
        protected Optional<StatementTransactionSource> readNextRecord() {
            return this.<StatementTransactionSource>readRecord(
                    StatementProcessor.INPUT_DD_TRNXFILE, () -> {
                if (this.transactionSourceServed) {
                    return IoResult.endOfFile();
                }
                this.transactionSourceServed = true;
                return IoResult.of(FileStatus.SUCCESS.getCode(),
                        this.transactionSource);
            });
        }

        /**
         * Runs the generation, writing each record to its resource at the moment the run emits it.
         *
         * <p><strong>&#9733; Nothing is accumulated between the run and the writers.</strong> The run
         * emits through {@link StatementOutputSink}, this lifecycle's implementation of that sink writes
         * the record it is handed and forgets it, and the value the run returns is seven tallies. The
         * working set is therefore one record however many statements the cross-reference file produces -
         * the superseded shape held both whole streams, copied them into the run's result and only then
         * wrote them, so heap use scaled with the output.
         *
         * <p>Both resources are execution-scoped <em>working</em> files that
         * {@link CreateStatementJobConfig#generateStatements} seals into generations only once this
         * lifecycle has completed. That is what contains the one consequence of streaming: a run that
         * fails a closing proof has already written records, and those records are in an unsealed working
         * file that is never published and is discarded with the execution.
         *
         * @param source the frozen projected transaction source, the lifecycle's single item
         */
        @Override
        protected void processRecord(final StatementTransactionSource source) {
            this.run = Objects.requireNonNull(this.statementProcessor.process(
                            new StatementProcessor.StatementRunRequest(source, new OutputWriterSink())),
                    () -> StatementGenerationService.PROGRAM_NAME + " reported no result for the "
                            + StatementProcessor.INPUT_DD_TRNXFILE + " work resource");
        }

        /**
         * This lifecycle's destination: it writes each emitted record to its own resource through the
         * step template's write accounting, and retains nothing.
         *
         * <p>Each record's encoded width is proved once more here, at the destination. That is not
         * duplication for its own sake: the stage proves what it forwards, and this proves what actually
         * reaches the resource whose record length is declared, so a destination wired to the wrong
         * resource is caught by the resource's own contract.
         *
         * <p>The two items the legacy program writes to no dataset - the per-line transaction summary and
         * the dispatcher entry - are counted and dropped. Counting them is what lets the completion
         * diagnostic report them without holding them.
         */
        private final class OutputWriterSink implements StatementOutputSink {

            @Override
            public void statementRecord(final String record) {
                writeRecord(StatementProcessor.OUTPUT_DD_STMTFILE, () -> {
                    // Proved again at the destination: the stage proves what it hands on, and this proves
                    // what actually reaches the resource the record length is declared for.
                    requireEncodedWidth(record, STATEMENT_RECORD_LENGTH,
                            StatementProcessor.OUTPUT_DD_STMTFILE);
                    GenerateStatementsProgram.this.statementWriter.write(record);
                    GenerateStatementsProgram.this.statementRecordsWritten++;
                    return FileStatus.SUCCESS.getCode();
                });
            }

            @Override
            public void htmlRecord(final String record) {
                writeRecord(StatementProcessor.OUTPUT_DD_HTMLFILE, () -> {
                    // The resolved width, not the superseded one the scratch step declares.
                    requireEncodedWidth(record, HTML_RECORD_LENGTH,
                            StatementProcessor.OUTPUT_DD_HTMLFILE);
                    GenerateStatementsProgram.this.htmlWriter.write(record);
                    GenerateStatementsProgram.this.htmlRecordsWritten++;
                    return FileStatus.SUCCESS.getCode();
                });
            }

            @Override
            public void transactionSummary(final StatementLineSummary summary) {
                Objects.requireNonNull(summary, "summary");
                GenerateStatementsProgram.this.transactionSummariesObserved++;
            }

            @Override
            public void dispatchedPhase(final String phase) {
                Objects.requireNonNull(phase, "phase");
                GenerateStatementsProgram.this.dispatcherEntriesObserved++;
            }
        }

        @Override
        protected void closeResources() {
            closeResource(StatementProcessor.OUTPUT_DD_STMTFILE, () -> {
                this.statementWriter.flush();
                this.statementWriter.close();
                return FileStatus.SUCCESS.getCode();
            });

            closeResource(StatementProcessor.OUTPUT_DD_HTMLFILE, () -> {
                this.htmlWriter.flush();
                this.htmlWriter.close();
                return FileStatus.SUCCESS.getCode();
            });

            if (this.run == null) {
                // The read loop served no item, which the legacy read reports as end of file: the driving
                // loop never iterates and neither output receives a record. Not a failure.
                LOGGER.info("{} WROTE NO RECORD TO {} OR {} BECAUSE {} DELIVERED NO ITEM",
                        StatementProcessor.LEGACY_STATEMENT_STEP,
                        StatementProcessor.OUTPUT_DD_STMTFILE, StatementProcessor.OUTPUT_DD_HTMLFILE,
                        StatementProcessor.INPUT_DD_TRNXFILE);
                return;
            }

            LOGGER.info("{} ({} via {}) PRODUCED {} STATEMENT(S) FROM {} CARD(S) AND {}"
                            + " TRANSACTION(S): {} RECORD(S) OF {} BYTE(S) TO {} AND {} RECORD(S) OF"
                            + " {} BYTE(S) TO {}, OVER {} DISPATCHER ENTRY(IES)",
                    StatementProcessor.LEGACY_STATEMENT_STEP,
                    StatementGenerationService.PROGRAM_NAME, StatementProcessor.LEGACY_SUBPROGRAM,
                    this.run.statementsWritten(), this.run.cardsTabulated(),
                    this.run.transactionsTabulated(), this.statementRecordsWritten,
                    STATEMENT_RECORD_LENGTH, StatementProcessor.OUTPUT_DD_STMTFILE,
                    this.htmlRecordsWritten, HTML_RECORD_LENGTH,
                    StatementProcessor.OUTPUT_DD_HTMLFILE, this.run.dispatcherEntries());
            LOGGER.info("{} READ ITS TRANSACTIONS IN THE KEY SEQUENCE OF {}, WHICH HELD {} RECORD(S)"
                            + " KEYED AT OFFSET {} FOR {} BYTE(S); THE CARD TABLE BOUNDS ARE {} CARD(S)"
                            + " AND {} TRANSACTION(S) PER CARD",
                    StatementProcessor.LEGACY_STATEMENT_STEP, StatementProcessor.INPUT_DD_TRNXFILE,
                    this.workResource.recordCount(), WORK_RESOURCE_KEY_OFFSET,
                    WORK_RESOURCE_KEY_LENGTH, MAX_CARD_ENTRIES, MAX_TRANSACTIONS_PER_CARD);
        }

        @Override
        protected void releaseResources() {
            releaseQuietly(this.statementWriter, StatementProcessor.OUTPUT_DD_STMTFILE);
            releaseQuietly(this.htmlWriter, StatementProcessor.OUTPUT_DD_HTMLFILE);
        }

        /**
         * The run this pass produced, for a caller that drives the lifecycle directly.
         *
         * @return the run, or {@code null} when the read loop served no item
         */
        StatementRun statementRun() {
            return this.run;
        }

        /**
         * Plain-text records this pass wrote, for a caller that drives the lifecycle directly.
         *
         * @return the count, never negative
         */
        long statementRecordsWritten() {
            return this.statementRecordsWritten;
        }

        /**
         * HTML records this pass wrote, for a caller that drives the lifecycle directly.
         *
         * @return the count, never negative
         */
        long htmlRecordsWritten() {
            return this.htmlRecordsWritten;
        }

        /**
         * Per-line transaction summaries this pass observed, for a caller that drives the lifecycle
         * directly.
         *
         * @return the count, never negative
         */
        long transactionSummariesObserved() {
            return this.transactionSummariesObserved;
        }

        /**
         * Dispatcher entries this pass observed, for a caller that drives the lifecycle directly.
         *
         * @return the count, never negative
         */
        long dispatcherEntriesObserved() {
            return this.dispatcherEntriesObserved;
        }
    }
}
