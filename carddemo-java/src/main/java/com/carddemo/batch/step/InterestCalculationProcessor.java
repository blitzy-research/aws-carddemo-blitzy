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
package com.carddemo.batch.step;

import com.carddemo.domain.Account;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.domain.id.DisclosureGroupId;
import com.carddemo.domain.id.TransactionCategoryBalanceId;
import com.carddemo.service.InterestCalculationService;
import com.carddemo.util.FailureDiagnostics;
import com.carddemo.util.SensitiveLogRedactor;
import com.carddemo.util.TransactionRecordMapper;
import com.carddemo.util.ZonedDecimalCodec;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;

/**
 * The per-account control break of the monthly interest run: the chunk-oriented stage that turns a
 * stream of transaction-category-balance rows into one accrued, posted and closed account group per
 * account identifier, reproducing the read-loop orchestration of the batch interest calculator
 * {@code app/cbl/CBACT04C.cbl}.
 *
 * <p>Provenance: the legacy estate is read-only reference at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is transcribed here and
 * nothing on this path reads the legacy tree at run time; every claim below is a citation of a member,
 * a paragraph, a line, a field name or a status code.
 *
 * <h2>Rules provenance</h2>
 *
 * <p><strong>No user-specified rules were provided for this engagement.</strong> The project's rules
 * document reports that none were supplied, so no file enters scope by rule and no rule conflict
 * exists. That absence is not permission to lower the bar: this class is held to enterprise-standard
 * best practice instead - a zero-warning build under {@code -Xlint:all -Werror -parameters}, strict
 * downward layering, constructor injection, no reflection and no code generation, structured logging
 * that never carries a primary account number or a monetary amount, and a paragraph-level
 * traceability record. The construct mapping is a <em>requirement</em> and the validation gates are
 * <em>acceptance criteria</em>; neither is a rule and neither is weakened by the absence of rules.
 *
 * <h2>Where the division of labour falls, and why</h2>
 *
 * <p>{@link InterestCalculationService} owns every per-row behaviour of the legacy program: the
 * three-part disclosure-group probe and its single default-group fallback, the non-zero rate gate, the
 * multiply-before-divide interest expression with its truncating store, the field-by-field synthesis
 * of the interest transaction, and the control-break rewrite of the account. It owns them because it
 * owns the five repositories those behaviours read and write, and because its group operation carries
 * the declarative transaction that puts the commit exactly where the legacy hardened the account
 * rewrite. <strong>None of that is re-implemented here.</strong> Re-deriving an amount, a rate lookup
 * or a rounding decision in this class would give the module two places where the same financial
 * decision is taken, which is how the two places eventually disagree.
 *
 * <p>What is left is the read loop, and it is what this class does:
 *
 * <ul>
 *   <li>count each row, as the driver does at {@code app/cbl/CBACT04C.cbl:L192};</li>
 *   <li>detect the change of account identifier at {@code L194} and, guarded by the first-time flag at
 *       {@code L195}, close the <em>previous</em> group before opening the new one at {@code L196} to
 *       {@code L201};</li>
 *   <li>close the final group after the last row - accruing it while withholding the account rewrite,
 *       because the end-of-file arm at {@code L219} to {@code L221} is unreachable;</li>
 *   <li>preserve the service's report that its single fee-paragraph owner was reached at
 *       {@code L216} once per row that the rate gate did not skip;</li>
 *   <li>and confirm, for every transaction the group synthesized, that it still satisfies the
 *       fixed-width output contract of the legacy dataset before it is handed on.</li>
 * </ul>
 *
 * <h2>The control break, stated precisely</h2>
 *
 * <p>The legacy driver holds the previous account key in an eleven-character working-storage item
 * initialised to spaces ({@code L167}) and a first-time flag initialised to {@code Y} ({@code L170}).
 * On the first row the key comparison at {@code L194} therefore succeeds, and the first-time flag is
 * what stops a group that was never opened from being closed: the flag is cleared at {@code L198} and
 * the close at {@code L196} is skipped exactly once, for exactly that reason. Both items are
 * reproduced here rather than folded into a single nullable field, because the flag is the mechanism
 * and a reader looking for it in the source will look for it here.
 *
 * <p><strong>Rows must arrive in record-key order, account identifier first.</strong> Ordering is the
 * parent job configuration's responsibility and is deliberately not taken here: this class sorts
 * nothing, holds no comparator and consults no comparator registry. Rows of one account that are not
 * adjacent would be seen as separate groups, each closing its own control break and each rewriting the
 * same account, which would post that account's interest more than once.
 *
 * <p><strong>A buffered row is not a lost row.</strong> While a group is filling, this stage reports
 * {@code null}, which the framework counts as a filtered item. The row's accrual is not discarded: it
 * is emitted as part of the group that contains it, when that group closes. A run therefore shows a
 * large filter count and a small write count, and both figures are correct.
 *
 * <h2>The final group is accrued, and its account is deliberately NOT rewritten</h2>
 *
 * <p>The last account has no successor row to trigger its control break, so it is closed from
 * {@link #afterStep(StepExecution)} with
 * {@code InterestCalculationService.AccountControlBreak.WITHHELD_AT_END_OF_FILE}. The legacy's own
 * end-of-file arm at {@code L219} to {@code L221} sits inside a test-before {@code PERFORM UNTIL} whose
 * condition the read paragraph satisfies at {@code L340}, so the loop ends and the arm never runs: the
 * last account of a run keeps its balance and keeps <strong>both</strong> cycle accumulators. Its
 * interest records are nonetheless written, because {@code L215} and {@code L468} run per row inside the
 * loop and owe nothing to the control break. Both halves of that are reproduced rather than reconciled,
 * and the module's own expected-output fixtures encode the unposted balance. See
 * {@code docs/decision-log.md} entry DL-207.
 *
 * <p>The close runs only when the step did not fail, because an abend is terminal in the legacy - the
 * close family and the end-of-execution announcement are never reached after one - and accruing a group
 * after a failed step would do work the legacy would not have reached.
 *
 * <p>Where a group's account rewrite happens at all it is durable as soon as the service returns,
 * because one closed group owns one transaction. Nothing is inserted into the live transaction master:
 * the job's guarded writer is the sole owner of the SYSTRAN generation, and COMBTRAN loads that
 * generation later.
 *
 * <p><strong>That writer is bound to this stage and is invoked while the group is still open</strong>,
 * not after it returns. {@code app/cbl/CBACT04C.cbl:L468} writes each transaction record the moment it
 * is assembled and {@code L353} rewrites the account only at the control break, so every record of a
 * group reached its dataset before that group's balance was rewritten and a failed record write abended
 * with the balance untouched. Rendering the records after the service returned would invert that order,
 * because the rewrite has committed by then. The group's records are still reported back, and this stage
 * still counts and checks them, but it does not write them - the bound writer does, at the moment each
 * one is synthesized. The final group is additionally retained on this instance and published by
 * {@link #finalAccruedGroup()} for the same counting and checking; its records were written when they
 * were synthesized, like every other group's.
 *
 * <h2>One deliberate re-ordering, recorded rather than hidden</h2>
 *
 * <p>The legacy reads the account and the cross-reference when a group <em>opens</em> ({@code L203} and
 * {@code L205}) and rewrites the account when the group <em>closes</em> ({@code L356}). Here both reads
 * happen when the group closes, because the service's group operation reads, accrues and rewrites in
 * one transaction. The observable result is identical - the account and the cross-reference are still
 * read exactly once per group, and every row of the group still resolves its rate against the same
 * account group identifier and mints its identifier against the same card number - and the unit of
 * work is strictly tighter than the legacy's, which read outside the update's scope entirely.
 *
 * <h2>The job parameter</h2>
 *
 * <p>The legacy linkage area at {@code app/cbl/CBACT04C.cbl:L175} to {@code L180} is a signed
 * four-digit binary length followed by a <strong>ten-character</strong> date, and the job stream
 * supplies that date as a program parameter at {@code app/jcl/INTCALC.jcl:L22}. Only the date crosses
 * into Java, as the job parameter named by {@link #PARM_DATE_KEY}. It carries no separators and is
 * <strong>never</strong> parsed, converted or reformatted, because it is also the first ten characters
 * of every identifier the run mints; its presence, its encoded width and its digits-only shape are
 * checked and nothing else is. The calendar validity of its leading positions belongs to the module's
 * job-parameter validator, which runs at launch, and is deliberately not duplicated here.
 *
 * <h2>Job-stream and source anomalies preserved as observations</h2>
 *
 * <ul>
 *   <li>{@code app/jcl/INTCALC.jcl:L31} allocates a fifth data definition, {@code XREFFIL1}, naming the
 *       cross-reference alternate-index path. <strong>The program never references it.</strong> Its
 *       file-control section names only the category-balance master, the cross-reference, the account
 *       master, the disclosure group and the transaction output. The allocation is therefore an
 *       artefact of the job stream and nothing here models it, opens it, reads it or resolves a
 *       resource from its name.</li>
 *   <li>The transaction output at {@code app/jcl/INTCALC.jcl:L37} to {@code L41} is a
 *       <strong>fixed-length unblocked</strong> dataset of {@value #INTEREST_RECORD_LENGTH} bytes per
 *       record, written to a new generation of a generation group. The record width is the contract
 *       this stage guards; the generation is the job configuration's staging concern.</li>
 *   <li>The fee paragraph at {@code app/cbl/CBACT04C.cbl:L518} to {@code L520} is <strong>empty and
 *       genuinely invoked</strong> at {@code L216}. Its single Java owner and invocation are in
 *       {@link InterestCalculationService}; this stage consumes that result and does not translate the
 *       paragraph a second time.</li>
 *   <li>The job card at {@code app/jcl/INTCALC.jcl:L2} notifies the submitting user; there is no
 *       equivalent in a Spring Batch step and none is invented.</li>
 * </ul>
 *
 * <h2>State, scope and threading</h2>
 *
 * <p>A control break is stateful by definition, so this class carries per-execution state - the
 * previous account key, the first-time flag, the buffered rows of the open group, the six-digit
 * identifier suffix and the run counters. All of it lives in a single holder created by
 * {@link #beforeStep(StepExecution)} and replaced on the next execution, so no value survives from one
 * step execution into another. Nothing is held statically and nothing is shared between executions.
 *
 * <p><strong>Registration contract.</strong> The instance must be registered on its step as a step
 * execution listener as well as its processor, because {@link #process(TransactionCategoryBalance)}
 * refuses to run before {@link #beforeStep(StepExecution)} has supplied the parameter date and the
 * fresh state holder, and the final control break lives in {@link #afterStep(StepExecution)}.
 * Registering it as a step-scoped bean as well is safe and is the natural way to wire it.
 *
 * <p><strong>The step must treat a fault as fatal.</strong> No skip policy and no retry policy may be
 * configured around this stage, and none is configured by it: a re-presented chunk would call this
 * method again for rows whose group had already been counted or closed, and a skipped row would be a
 * row the legacy accrued and this run did not. That is also the legacy's own semantics - an abend ends
 * the run where it stands - so the constraint costs nothing and no limit, delay or backoff figure is
 * chosen here.
 *
 * <p><strong>Execution is strictly sequential</strong>, as a legacy batch program's is. No task
 * executor, partitioning, parallel stream or asynchronous hook may be introduced around this stage:
 * the control break is order-dependent, and concurrent chunks would interleave two accounts' rows into
 * one group.
 *
 * <h2>Observability</h2>
 *
 * <p>The per-group timer and the three counters registered here are <strong>supplementary</strong>.
 * Whole-step timing belongs to {@link AbstractCobolStep}, which records the elapsed time of a legacy
 * program lifecycle under its own meter, and to the step the job configuration builds; nothing here
 * replaces it or stands in for it. The batch timestamp stamped onto each closed group is taken from
 * that same template, through the nested gateway below, so the batch tier has exactly one
 * twenty-six-character timestamp format and this stage cannot drift from it.
 *
 * <p>Diagnostics are structured and deliberately narrow. A record is never logged in whole, and
 * neither the card number - a primary account number - nor any monetary amount ever appears in a log
 * line or in the step's execution context; only identifiers, field names, widths and counts do.
 *
 * @see InterestCalculationService
 * @see AbstractCobolStep
 * @see TransactionRecordMapper
 * @since 1.0.0
 */
public class InterestCalculationProcessor
        implements ItemProcessor<TransactionCategoryBalance,
                        InterestCalculationProcessor.AccruedAccountGroup>,
                StepExecutionListener {

    /**
     * Diagnostics for this stage, replacing the legacy program's console display channel.
     *
     * <p>Nothing financial and nothing card-bearing is ever passed to it. The legacy program displays
     * a whole category-balance record on every iteration at {@code app/cbl/CBACT04C.cbl:L193}; that
     * display is owned by the service, at the level its content warrants, and is not repeated here.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(InterestCalculationProcessor.class);

    /** The legacy batch member this stage's read loop reproduces. */
    public static final String LEGACY_PROGRAM = "CBACT04C";

    /** The legacy job stream that runs it, {@code app/jcl/INTCALC.jcl}. */
    public static final String LEGACY_JOB = "INTCALC";

    /** The legacy job step that invokes the program, {@code app/jcl/INTCALC.jcl:L22}. */
    public static final String LEGACY_STEP = "STEP15";

    /**
     * The legacy data definition the job stream allocates and the program never references,
     * {@code app/jcl/INTCALC.jcl:L31}.
     *
     * <p>Published as a name only, so the anomaly is recorded where a reader of this stage will look
     * for it and so a test can assert that the anomaly is still documented. Nothing resolves a
     * resource, a dataset or a path from it.
     */
    public static final String LEGACY_UNREFERENCED_DD = "XREFFIL1";

    /**
     * The legacy data definition of the transaction output, {@code app/jcl/INTCALC.jcl:L37}, as the
     * diagnostic of an output-contract breach names it.
     */
    public static final String OUTPUT_RESOURCE = "TRANSACT";

    /**
     * Name of the job parameter carrying the ten-character run date of
     * {@code app/jcl/INTCALC.jcl:L22}.
     *
     * <p>Deliberately the same literal the module's job-parameter validator publishes for the interest
     * run, so the launcher, the validator and this stage name one parameter rather than three. It is
     * restated rather than imported because the batch step layer depends downward only.
     */
    public static final String PARM_DATE_KEY = "interestParmDate";

    /**
     * Encoded width of the run date: the ten characters of {@code PARM-DATE} at
     * {@code app/cbl/CBACT04C.cbl:L178}, which are also the first ten characters of every identifier
     * the run mints.
     */
    public static final int PARM_DATE_WIDTH = 10;

    /** Width of {@code WS-TRANID-SUFFIX} at {@code app/cbl/CBACT04C.cbl:L173}: six digits. */
    public static final int TRAN_ID_SUFFIX_WIDTH = 6;

    /**
     * Encoded width of the synthesized identifier: the run date followed by the six-digit suffix,
     * which fills {@code TRAN-ID PIC X(16)} exactly.
     */
    public static final int TRAN_ID_WIDTH = PARM_DATE_WIDTH + TRAN_ID_SUFFIX_WIDTH;

    /** Width of {@code ACCT-ID PIC 9(11)}, which the description renders in full. */
    public static final int ACCT_ID_WIDTH = 11;

    /** Width of {@code DIS-ACCT-GROUP-ID PIC X(10)} and of {@code ACCT-GROUP-ID PIC X(10)}. */
    public static final int ACCT_GROUP_ID_WIDTH = 10;

    /** Width of {@code TRAN-CARD-NUM PIC X(16)}, taken from the cross-reference. */
    public static final int CARD_NUM_WIDTH = 16;

    /** Width of {@code TRAN-MERCHANT-NAME PIC X(50)}. */
    public static final int MERCHANT_NAME_WIDTH = 50;

    /** Width of {@code TRAN-MERCHANT-CITY PIC X(50)}. */
    public static final int MERCHANT_CITY_WIDTH = 50;

    /** Width of {@code TRAN-MERCHANT-ZIP PIC X(10)}. */
    public static final int MERCHANT_ZIP_WIDTH = 10;

    /** Width of {@code TRAN-DESC PIC X(100)}. */
    public static final int TRAN_DESC_WIDTH = 100;

    /**
     * Fixed width, in encoded US-ASCII bytes, of one record of the transaction output.
     *
     * <p>Taken from {@link TransactionRecordMapper#RECORD_LENGTH} rather than restated, because the
     * layout {@code app/cpy/CVTRA05Y.cpy} has exactly one authority in this module. It is also what
     * the job stream declares for the output dataset at {@code app/jcl/INTCALC.jcl:L39}.
     */
    public static final int INTEREST_RECORD_LENGTH = TransactionRecordMapper.RECORD_LENGTH;

    /**
     * The ten-character default disclosure-group identifier of
     * {@code app/cbl/CBACT04C.cbl:L437}: the seven-character literal moved into a ten-character field,
     * so <strong>the three trailing spaces are part of the value</strong>. The seven-character form
     * resolves nothing.
     */
    public static final String DEFAULT_ACCOUNT_GROUP_ID = "DEFAULT   ";

    /** The transaction type code moved at {@code app/cbl/CBACT04C.cbl:L482}. */
    public static final String INTEREST_TRAN_TYPE_CD = "01";

    /**
     * The transaction category code as stored. {@code app/cbl/CBACT04C.cbl:L483} moves the
     * two-character literal {@code 05} into a <strong>four-digit</strong> field, so the stored value is
     * {@code 0005}. It is neither {@code 5} nor {@code 05}.
     */
    public static final String INTEREST_TRAN_CAT_CD = "0005";

    /**
     * The transaction source as stored. {@code app/cbl/CBACT04C.cbl:L484} moves the six-character
     * literal into a ten-character field, so the stored value carries <strong>four trailing
     * spaces</strong> and is never trimmed.
     */
    public static final String INTEREST_TRAN_SOURCE = "System    ";

    /**
     * The description prefix assembled at {@code app/cbl/CBACT04C.cbl:L485} to {@code L489}.
     * <strong>Its trailing space is part of it</strong> and separates the literal from the eleven-digit
     * account identifier that follows.
     */
    public static final String INTEREST_DESCRIPTION_PREFIX = "Int. for a/c ";

    /**
     * The merchant identifier as stored. {@code app/cbl/CBACT04C.cbl:L491} moves numeric zero into a
     * nine-digit field, so the stored value is nine zero characters as a string and never the number
     * zero.
     */
    public static final String INTEREST_MERCHANT_ID = "000000000";

    /**
     * Encoded width of the batch timestamp, taken from {@link AbstractCobolStep} through the nested
     * gateway rather than restated, so this stage cannot drift from the batch tier's one timestamp
     * format. The legacy assembles it at {@code app/cbl/CBACT04C.cbl:L613} to {@code L626} and stores
     * it in {@code TRAN-ORIG-TS PIC X(26)} and {@code TRAN-PROC-TS PIC X(26)}.
     */
    public static final int BATCH_TIMESTAMP_WIDTH = BatchTimestampGateway.timestampWidth();

    /**
     * The suffix reported for a row the rate gate skipped: no identifier is minted for it, because the
     * legacy increment at {@code app/cbl/CBACT04C.cbl:L474} sits inside the gate.
     */
    public static final long NO_TRAN_ID_SUFFIX = 0L;

    /** Meter recording the elapsed time of one account group's accrual and control break. */
    private static final String METRIC_GROUP_ACCRUAL = "carddemo.batch.interest.group";

    /** Meter counting the category-balance rows this stage's read loop delivered. */
    private static final String METRIC_ROWS_READ = "carddemo.batch.interest.rows";

    /** Meter counting the interest transactions the run synthesized. */
    private static final String METRIC_TRANSACTIONS = "carddemo.batch.interest.transactions";

    /** Meter counting the rows whose zero rate skipped both the computation and the fee invocation. */
    private static final String METRIC_RATE_GATE_SKIPS = "carddemo.batch.interest.rate.gate.skips";

    /** Meter counting the rows whose rate came from the padded default disclosure group. */
    private static final String METRIC_DEFAULT_GROUP_FALLBACKS =
            "carddemo.batch.interest.default.group.fallbacks";

    /** Tag naming the legacy program, matching the tag the shared step template uses. */
    private static final String TAG_STEP = "step";

    /** Tag distinguishing a group that closed from one whose accrual failed. */
    private static final String TAG_OUTCOME = "outcome";

    /** Outcome tag value for a group that closed and posted. */
    private static final String OUTCOME_COMPLETED = "COMPLETED";

    /** Outcome tag value for a group whose accrual raised. */
    private static final String OUTCOME_ABENDED = "ABENDED";

    /** Execution-context key carrying how many account groups the control break closed. */
    public static final String CONTEXT_GROUPS_CLOSED = "carddemo.interest.groupsClosed";

    /** Execution-context key carrying how many category-balance rows the read loop delivered. */
    public static final String CONTEXT_ROWS_READ = "carddemo.interest.rowsRead";

    /** Execution-context key carrying how many interest transactions the run synthesized. */
    public static final String CONTEXT_TRANSACTIONS = "carddemo.interest.transactionsSynthesized";

    /** Execution-context key carrying the six-digit identifier suffix the run finished on. */
    public static final String CONTEXT_LAST_TRAN_ID_SUFFIX = "carddemo.interest.lastTranIdSuffix";

    /** Execution-context key recording whether any row's zero rate skipped the computation. */
    public static final String CONTEXT_RATE_GATE_SKIPPED = "carddemo.interest.rateGateSkipped";

    /** Execution-context key recording whether any row fell back to the default disclosure group. */
    public static final String CONTEXT_DEFAULT_GROUP_USED = "carddemo.interest.defaultGroupUsed";

    /**
     * Stands for the space-filled initial value of {@code WS-LAST-ACCT-NUM} at
     * {@code app/cbl/CBACT04C.cbl:L167}. Any real eleven-digit account key differs from it, which is
     * what makes the key comparison at {@code L194} succeed on the first row.
     */
    private static final String NO_ACCOUNT_YET = "";

    /** The zoned-decimal zero at the monetary scale, used to seed a group's running total. */
    private static final BigDecimal ZERO_MONETARY = ZonedDecimalCodec.toMonetaryScale(BigDecimal.ZERO);

    /**
     * Legacy name of the group's running-total receiving field, {@code app/cbl/CBACT04C.cbl} line 169,
     * declared {@code PIC S9(09)V99}. Named so the re-derived total is stored into the same geometry the
     * service stored it into, which is what lets the two be compared for equality at all.
     */
    private static final String FIELD_WS_TOTAL_INT = "WS-TOTAL-INT";

    /** The fill character of a right-justified zero-filled numeric move. */
    private static final char ZERO_FILL = '0';

    /** The space a right-justified move leaves before the zero fill replaces it. */
    private static final char SPACE = ' ';

    /** The artefact name an output-contract diagnostic reports, so the layout is named once. */
    private static final String ARTEFACT = TransactionRecordMapper.ARTEFACT;

    /** Owns every per-row behaviour and every repository this run touches. */
    private final InterestCalculationService interestCalculationService;

    /** The registry the supplementary per-group timer and the four counters are recorded on. */
    private final MeterRegistry meterRegistry;

    /**
     * Gateway to the shared step template's twenty-six-character batch timestamp. Held rather than
     * created per call so the template's clock is bound once, at construction.
     */
    private final BatchTimestampGateway batchTimestamps;

    /** Timer for a group that closed and posted. */
    private final Timer groupCompletedTimer;

    /** Timer for a group whose accrual raised before it could post. */
    private final Timer groupAbendedTimer;

    /** Counter of category-balance rows delivered to this stage. */
    private final Counter rowsReadCounter;

    /** Counter of interest transactions synthesized. */
    private final Counter transactionsCounter;

    /** Counter of rows whose zero rate skipped both the computation and the fee invocation. */
    private final Counter rateGateSkipCounter;

    /** Counter of rows whose rate came from the padded default disclosure group. */
    private final Counter defaultGroupFallbackCounter;

    /**
     * The current step execution's state, replaced by every {@link #beforeStep(StepExecution)} and
     * absent until the first one. It is the only mutable field on this class, and it is a reference to
     * a holder rather than a spread of loose counters so that resetting an execution is one assignment
     * and cannot half-happen.
     */
    private Accrual accrual;

    /**
     * Where a synthesized transaction record is written, bound by the step that owns the output
     * generation and unbound when that step closes it.
     *
     * <p>It is deliberately absent until bound rather than defaulting to a discarding sink. A discarding
     * default would let a step that forgot to bind run to completion, post every balance and produce an
     * empty generation - the exact failure this stage exists to make impossible. Absent, the first
     * synthesized record fails loudly instead, before any balance is rewritten.
     */
    private Consumer<Transaction> synthesizedWriter;

    /**
     * Constructs the stage.
     *
     * <p>Constructor injection throughout, and every collaborator is required. There is no degraded
     * mode: without the service there is no accrual to orchestrate, without the registry the
     * supplementary meters cannot be registered and the shared step template cannot be constructed, and
     * without the clock the batch timestamp has no instant to read.
     *
     * @param interestCalculationService the interest run's service, which owns every per-row behaviour,
     *                                  its repositories and the group transaction
     * @param meterRegistry             the registry the supplementary meters are recorded on, and the
     *                                  registry the shared step template records whole-step timing on
     * @param clock                     the source of the batch timestamp's instant, injected so a test
     *                                  can fix it
     * @throws NullPointerException if any collaborator is absent
     */
    public InterestCalculationProcessor(final InterestCalculationService interestCalculationService,
            final MeterRegistry meterRegistry, final Clock clock) {
        this.interestCalculationService = Objects.requireNonNull(interestCalculationService,
                "interestCalculationService must not be null: this stage orchestrates it and"
                        + " implements none of its behaviour itself");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.batchTimestamps = new BatchTimestampGateway(meterRegistry,
                Objects.requireNonNull(clock, "clock must not be null"));
        this.groupCompletedTimer = groupTimer(meterRegistry, OUTCOME_COMPLETED);
        this.groupAbendedTimer = groupTimer(meterRegistry, OUTCOME_ABENDED);
        this.rowsReadCounter = counter(meterRegistry, METRIC_ROWS_READ,
                "Transaction category balance rows delivered to the interest control break");
        this.transactionsCounter = counter(meterRegistry, METRIC_TRANSACTIONS,
                "Interest transactions synthesized by the interest run");
        this.rateGateSkipCounter = counter(meterRegistry, METRIC_RATE_GATE_SKIPS,
                "Category balance rows whose zero rate skipped both the computation and the fee"
                        + " paragraph");
        this.defaultGroupFallbackCounter = counter(meterRegistry, METRIC_DEFAULT_GROUP_FALLBACKS,
                "Category balance rows whose rate came from the padded default disclosure group");
    }

    /**
     * Registers the supplementary per-group timer for one outcome.
     *
     * <p>Static, so the constructor invokes nothing overridable and cannot publish a partially built
     * instance. Registration is idempotent in the registry: an instance built again for a second step
     * execution resolves the same meter rather than creating a second one.
     *
     * @param registry the registry to record on
     * @param outcome  the outcome tag value
     * @return the timer for that outcome
     */
    private static Timer groupTimer(final MeterRegistry registry, final String outcome) {
        return Timer.builder(METRIC_GROUP_ACCRUAL)
                .description("Elapsed time of one account group's interest accrual and control break")
                .tag(TAG_STEP, LEGACY_PROGRAM)
                .tag(TAG_OUTCOME, outcome)
                .register(registry);
    }

    /**
     * Registers one supplementary counter. Static for the same reason as {@link #groupTimer}.
     *
     * @param registry    the registry to record on
     * @param name        the meter name
     * @param description what the counter counts, as the registry publishes it
     * @return the counter
     */
    private static Counter counter(final MeterRegistry registry, final String name,
            final String description) {
        return Counter.builder(name)
                .description(description)
                .tag(TAG_STEP, LEGACY_PROGRAM)
                .register(registry);
    }

    /* ======================================================================================== */
    /* STEP LIFECYCLE                                                                            */
    /* ======================================================================================== */

    /**
     * Opens a step execution: validates the run date and installs a fresh state holder.
     *
     * <p>This is the announcement at {@code app/cbl/CBACT04C.cbl:L181} and the initialisation of the
     * working-storage items at {@code L167} to {@code L173}. Every value the control break depends on -
     * the previous account key, the first-time flag, the running counters and the six-digit identifier
     * suffix - starts here at the value the legacy declares for it, so no figure can survive from a
     * previous execution into this one.
     *
     * @param stepExecution the framework's step execution, which supplies the job parameters
     * @throws NullPointerException     if the step execution is absent
     * @throws IllegalArgumentException if the run date job parameter is missing, is not exactly
     *                                  {@value #PARM_DATE_WIDTH} encoded bytes, or carries a character
     *                                  that is not a digit
     */
    @Override
    public void beforeStep(final StepExecution stepExecution) {
        Objects.requireNonNull(stepExecution, "stepExecution must not be null");
        final String parameterDate = requiredParameterDate(stepExecution);
        this.accrual = new Accrual(parameterDate);
        LOGGER.info("START OF EXECUTION OF PROGRAM {} - job={} step={} runDate={}", LEGACY_PROGRAM,
                LEGACY_JOB, LEGACY_STEP, parameterDate);
    }

    /**
     * Closes the step execution: runs the <strong>final control break</strong> and publishes the run's
     * counters.
     *
     * <p>This is the end-of-file arm at {@code app/cbl/CBACT04C.cbl:L219} to {@code L221}. The last
     * account has no successor row to trigger its own break, so without this the account would be read,
     * accrued and never posted - and every other figure of the run would still look correct.
     *
     * <p>The break is skipped when the step did not succeed, because an abend is terminal in the legacy:
     * the close family and the end-of-execution announcement are never reached after one, so posting a
     * group here would run a control break the legacy would not have run. The skip is reported at
     * warning level rather than silently, because an operator reading the log needs to know that the
     * open group was not posted.
     *
     * <p>Only counts and flags reach the execution context. It is persisted to the job repository, so
     * no monetary amount, no account identifier and no card number is published through it.
     *
     * @param stepExecution the framework's step execution
     * @return the step's exit status, unchanged by this listener
     * @throws NullPointerException if the step execution is absent
     */
    @Override
    public ExitStatus afterStep(final StepExecution stepExecution) {
        Objects.requireNonNull(stepExecution, "stepExecution must not be null");
        final Accrual current = this.accrual;
        if (current == null) {
            LOGGER.warn("{} {} ended without ever having been opened, so there is no control break to"
                    + " run; register this stage as the step's execution listener as well as its"
                    + " processor", LEGACY_JOB, LEGACY_STEP);
            return stepExecution.getExitStatus();
        }

        if (stepExecution.getStatus().isUnsuccessful()) {
            LOGGER.warn("{} {} ended {}, so the final control break of program {} is NOT run and the"
                    + " open group is not posted; {} rows had been read", LEGACY_JOB, LEGACY_STEP,
                    stepExecution.getStatus(), LEGACY_PROGRAM, current.recordCount());
        } else if (current.hasOpenGroup()) {
            current.rememberFinalGroup(closeOpenGroup(current,
                    InterestCalculationService.AccountControlBreak.WITHHELD_AT_END_OF_FILE));
        }

        current.markEnded();
        publishCounters(stepExecution, current);
        LOGGER.info("END OF EXECUTION OF PROGRAM {} - groups={} rows={} transactions={}"
                + " lastTranIdSuffix={}", LEGACY_PROGRAM, current.groupsClosed(),
                current.recordCount(), current.transactionsSynthesized(), current.tranIdSuffix());
        return stepExecution.getExitStatus();
    }

    /**
     * Binds the writer that performs the legacy transaction-record write at
     * {@code app/cbl/CBACT04C.cbl:L500}.
     *
     * <p>The step that owns the output generation binds this once its generation is open and <em>before
     * the first row is read</em>, so no group can close without somewhere to write. Each record is then
     * written while its group is still open and before that group's account rewrite, which is the order
     * the legacy has and the order a write failure depends on to leave the balance untouched.
     *
     * <p>The writer is expected to raise on failure - the guarded write of the shared step template
     * does exactly that, after logging the operation, the resource and the raw status. Whatever it raises
     * propagates through the service's group boundary, so the group's transaction rolls back and the
     * account is not rewritten. Nothing here catches it or translates it, because the legacy's own arms
     * at lines 501 to 512 belong to the writer.
     *
     * @param writer where each synthesized record is written, in synthesis order
     * @throws NullPointerException if the writer is absent; unbinding is
     *         {@link #unbindSynthesizedTransactionWriter()} and is never expressed as a null binding
     */
    public void bindSynthesizedTransactionWriter(final Consumer<Transaction> writer) {
        this.synthesizedWriter = Objects.requireNonNull(writer, "writer must not be null: unbinding is"
                + " a separate operation, so a null binding is always a mistake rather than an intent");
    }

    /**
     * Releases the bound writer, so a record synthesized after the generation has been closed fails
     * rather than being written to a closed resource or silently discarded.
     *
     * <p>Called by the owning step when it closes its output, after the final control break has run.
     * Ordering matters: the final break synthesizes records and must still find the writer bound.
     */
    public void unbindSynthesizedTransactionWriter() {
        this.synthesizedWriter = null;
    }

    /**
     * Performs one legacy transaction-record write through the bound writer.
     *
     * <p>Passed to the service as a method reference rather than passing the field, so that the binding
     * is resolved at the moment of the write. A step binds after this stage is constructed, and a group
     * may close on any row, so resolving early would capture the absence rather than the binding.
     *
     * @param synthesized the record the service has just assembled
     * @throws IllegalStateException if no writer is bound, which means the owning step opened its
     *         generation without binding it and would otherwise have posted balances into a run whose
     *         records went nowhere
     */
    private void writeSynthesizedTransaction(final Transaction synthesized) {
        final Consumer<Transaction> writer = this.synthesizedWriter;
        if (writer == null) {
            throw new IllegalStateException(LEGACY_JOB + " " + LEGACY_STEP + " synthesized a"
                    + " transaction record with no writer bound; the step that owns the "
                    + OUTPUT_RESOURCE + " generation must bind one before the first row is read,"
                    + " because the legacy writes every record before it rewrites the balance");
        }
        writer.accept(synthesized);
    }

    /**
     * The group the final control break closed, once the step has ended.
     *
     * <p>Published because the group closed after the last chunk cannot travel out through the chunk
     * path: there is no chunk left to carry it. Its account rewrite is already durable in the group's
     * own transaction, and <strong>its records were already written by the bound writer</strong> at the
     * moment each was synthesized - this accessor does not carry them to a writer and must not be used
     * to. What it carries is the group's outcome: its rows, its total, its counts and the suffix it
     * finished on, which is what an operator, a counter and a check need after end of file.
     *
     * @return the final group, or an empty result when the run read no row at all, when the step did not
     *         succeed, or when the step has not ended yet
     */
    public Optional<AccruedAccountGroup> finalAccruedGroup() {
        final Accrual current = this.accrual;
        return current == null ? Optional.empty() : Optional.ofNullable(current.finalGroup());
    }

    /**
     * The run date this execution is minting identifiers against.
     *
     * @return the ten-character run date, or an empty result before the first
     *         {@link #beforeStep(StepExecution)}
     */
    public Optional<String> parameterDate() {
        final Accrual current = this.accrual;
        return current == null ? Optional.empty() : Optional.of(current.parameterDate());
    }

    /* ======================================================================================== */
    /* THE CONTROL BREAK                                                                         */
    /* ======================================================================================== */

    /**
     * Takes one category-balance row and reports the account group the row's arrival <em>closed</em>,
     * or {@code null} while the group it belongs to is still filling.
     *
     * <p>This is the driver at {@code app/cbl/CBACT04C.cbl:L188} to {@code L222}, minus the read itself,
     * which the framework's reader performs:
     *
     * <ol>
     *   <li>the row is counted, as at {@code L192};</li>
     *   <li>its account identifier is compared with the previous one, as at {@code L194};</li>
     *   <li>on a change, the first-time flag decides between closing the previous group at {@code L196}
     *       and merely clearing the flag at {@code L198} - which is what stops a group that was never
     *       opened from being closed on the very first row;</li>
     *   <li>the running total is reset and the new key remembered, as at {@code L200} and {@code L201};
     *       the account and cross-reference reads of {@code L203} and {@code L205} happen when the group
     *       closes, for the reason given on this class;</li>
     *   <li>and the row joins the open group's buffer, because the group's rate lookups, computations
     *       and account rewrite are one transaction and therefore one call.</li>
     * </ol>
     *
     * <p>Reporting {@code null} is the framework's filter signal and is used deliberately: the row's
     * accrual is emitted with the group that contains it, when that group closes, so nothing is lost.
     * The last group has no successor row and is closed by {@link #afterStep(StepExecution)}.
     *
     * @param  item the category-balance row, in record-key order; never {@code null}, as the framework's
     *              own contract for this method guarantees
     * @return the group the arrival of this row closed, or {@code null} when no group closed
     * @throws NullPointerException  if the row is absent, or if it carries no account identifier
     * @throws IllegalStateException if the step execution was never opened or has already ended, which
     *                               means this stage was not registered as its step's execution listener
     * @throws com.carddemo.exception.AbendException propagated from the service when a closing group
     *                               cannot read the account, cannot resolve a rate even from the default
     *                               group, or cannot write
     */
    @Override
    public AccruedAccountGroup process(final TransactionCategoryBalance item) {
        final Accrual current = requireOpenExecution();
        Objects.requireNonNull(item, LEGACY_JOB + " " + LEGACY_STEP + " received a null"
                + " category-balance row, which the framework contract forbids");
        final String accountId = requiredAccountKey(item);

        // Line 192 adds 1 to WS-RECORD-COUNT, before any control-break decision.
        current.countRecord();
        this.rowsReadCounter.increment();

        AccruedAccountGroup closed = null;
        if (!accountId.equals(current.lastAccountNumber())) {          // Line 194.
            if (current.isFirstTime()) {                              // Line 195.
                current.clearFirstTime();                             // Line 198.
            } else {
                closed = closeOpenGroup(current,
                        InterestCalculationService.AccountControlBreak.REWRITE); // Line 196.
            }
            current.beginGroup(accountId);                            // Lines 200 and 201.
        }
        current.buffer(item);
        return closed;
    }

    /**
     * Closes the open account group: accrues every buffered row and posts the control break.
     *
     * <p>Times the whole close, including the output-contract confirmation, on the supplementary
     * per-group timer. The timer is stopped on both paths so a failed accrual is measured under its own
     * outcome tag rather than disappearing; whole-step timing remains
     * {@link AbstractCobolStep}'s and the parent step's, and this measurement does not stand in for it.
     *
     * @param  current      the open execution state
     * @param  controlBreak which invocation site of {@code 1050-UPDATE-ACCOUNT} closes this group
     * @return what the group produced
     */
    private AccruedAccountGroup closeOpenGroup(final Accrual current,
            final InterestCalculationService.AccountControlBreak controlBreak) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);
        boolean completed = false;
        try {
            final AccruedAccountGroup group = accrueOpenGroup(current, controlBreak);
            completed = true;
            return group;
        } finally {
            sample.stop(completed ? this.groupCompletedTimer : this.groupAbendedTimer);
        }
    }

    /**
     * Accrues and posts the open group, then confirms what it produced still satisfies the output
     * contract.
     *
     * <p>The identifier suffix is threaded through rather than reset, because
     * {@code WS-TRANID-SUFFIX} at {@code app/cbl/CBACT04C.cbl:L173} belongs to the <em>run</em> and not
     * to any one group: the group is told where to continue from and reports back where it finished.
     *
     * <p>The batch timestamp is taken <strong>once</strong> per closed group, from the shared step
     * template's twenty-six-character format, and stamps the emitted group. It is not re-derived per
     * field and not re-derived per row.
     *
     * <p>The bound writer is handed to the service rather than applied to the returned records, so each
     * record is written at {@code app/cbl/CBACT04C.cbl:L500} - inside the group's unit of work and before
     * its account rewrite - and a write failure abends with the balance still untouched.
     *
     * @param  current      the open execution state
     * @param  controlBreak which invocation site of {@code 1050-UPDATE-ACCOUNT} closes this group
     * @return what the group produced
     */
    private AccruedAccountGroup accrueOpenGroup(final Accrual current,
            final InterestCalculationService.AccountControlBreak controlBreak) {
        final String accountId = current.lastAccountNumber();
        final List<TransactionCategoryBalance> rows = current.detachOpenGroupRows();
        final long initialSuffix = current.tranIdSuffix();
        final String parameterDate = current.parameterDate();

        final InterestCalculationService.GroupInterestResult result = this.interestCalculationService
                .calculateGroupInterest(parameterDate, accountId, rows, initialSuffix,
                        this::writeSynthesizedTransaction, controlBreak);
        requireGroupIdentity(accountId, rows.size(), result);

        final String accruedAt = this.batchTimestamps.batchTimestamp();
        final List<AccruedCategoryRow> accruedRows =
                accrueRows(result, parameterDate, initialSuffix);
        requireRunningTotals(result, accruedRows, initialSuffix);
        if (result.accountRewritten()) {
            requireClosedCycleAccumulators(result.updatedAccount());
        }

        current.completeGroup(result.lastTranIdSuffix(), result.interestTransactions().size(),
                result.rateGateSkipped(), result.defaultGroupUsed());
        this.transactionsCounter.increment(result.interestTransactions().size());

        LOGGER.debug("control break closed accountRef={} rows={} transactions={} defaultGroupUsed={}"
                + " rateGateSkipped={} accruedAt={}", SensitiveLogRedactor.redact(accountId),
                result.recordCount(),
                result.interestTransactions().size(), result.defaultGroupUsed(),
                result.rateGateSkipped(), accruedAt);

        return new AccruedAccountGroup(accountId, parameterDate, result.totalInterest(), accruedRows,
                result.interestTransactions(), result.updatedAccount(), result.accountRewritten(),
                result.rateGateSkipped(), result.defaultGroupUsed(), result.recordCount(),
                result.lastTranIdSuffix(), accruedAt);
    }

    /**
     * Walks the group's per-row outcomes in the order the rows were presented, preserving the service's
     * gate decision and confirming each synthesized transaction.
     *
     * <p><strong>The gate at {@code app/cbl/CBACT04C.cbl:L214} encloses both {@code L215} and
     * {@code L216}</strong>, so a row whose rate was zero produces no interest, no transaction and
     * <em>no fee invocation</em>. A row whose rate was not zero produces exactly one transaction. The
     * service has already invoked the one translated fee paragraph immediately after the computation;
     * this processor records that gated fact without invoking or owning a duplicate no-op.
     *
     * <p>The identifier suffix is advanced only inside the gate, mirroring the increment at
     * {@code L474}, which is why a skipped row reports {@link #NO_TRAN_ID_SUFFIX}.
     *
     * @param  result        what the group produced
     * @param  parameterDate the run date, which every identifier begins with
     * @param  initialSuffix the suffix the group continued from
     * @return one entry per row, in the order the rows were presented
     */
    private List<AccruedCategoryRow> accrueRows(
            final InterestCalculationService.GroupInterestResult result, final String parameterDate,
            final long initialSuffix) {
        final String accountGroupId = requiredAccountGroupId(result.updatedAccount());
        final List<AccruedCategoryRow> accrued = new ArrayList<>(result.categoryInterests().size());
        long suffix = initialSuffix;
        String groupCardNumber = null;

        for (final InterestCalculationService.CategoryInterest row : result.categoryInterests()) {
            final TransactionCategoryBalanceId rowKey =
                    new TransactionCategoryBalanceId(row.accountId(), row.tranTypeCd(),
                            row.tranCatCd());
            // Lines 210 to 212 move the key parts as group, CATEGORY, TYPE; the key itself is ordered
            // group, TYPE, CATEGORY. The builder below states the KEY order, never the move order.
            final DisclosureGroupId probeKey =
                    primaryDisclosureKey(accountGroupId, row.tranTypeCd(), row.tranCatCd());
            if (row.defaultGroupUsed()) {
                this.defaultGroupFallbackCounter.increment();
            }

            final boolean gated = row.rateGateSkipped();
            final long mintedSuffix;
            if (gated) {
                requireSkippedRow(row, rowKey);
                this.rateGateSkipCounter.increment();
                mintedSuffix = NO_TRAN_ID_SUFFIX;
            } else {
                suffix++;                                                          // Line 474.
                groupCardNumber = requireSynthesizedTransaction(row, rowKey, parameterDate, suffix,
                        groupCardNumber);
                mintedSuffix = suffix;
            }

            accrued.add(new AccruedCategoryRow(rowKey, probeKey, row.categoryBalance(),
                    row.disclosedRate(), row.monthlyInterest(), gated, row.defaultGroupUsed(), !gated,
                    mintedSuffix, row.interestTransaction()));
        }
        return accrued;
    }

    /* ======================================================================================== */
    /* THE WRITER-FACING CONTRACT                                                                */
    /* ======================================================================================== */

    /**
     * Builds the primary disclosure-group key for one category-balance row.
     *
     * <p><strong>Component order: group, then TYPE, then CATEGORY.</strong> The source moves the parts
     * in the order group, <em>category</em>, <em>type</em> at {@code app/cbl/CBACT04C.cbl:L210} to
     * {@code L212}; the key itself is ordered group, type, category - fixed by the copybook field order
     * at {@code app/cpy/CVTRA02Y.cpy:L6} to {@code L8}, by the cluster's key definition, and by the
     * program's own key declaration at {@code app/cbl/CBACT04C.cbl:L79} to {@code L81}. The move order
     * is an artefact of how the source happens to read; the key order is the contract. Typing the
     * arguments in the order the moves appear produces a key that compiles, looks right and resolves
     * nothing.
     *
     * <p>Published so the job configuration, this stage's diagnostics and a test can name one key
     * builder rather than three, and so the ordering is asserted somewhere rather than assumed
     * everywhere.
     *
     * @param  accountGroupId the account's ten-character group identifier, moved at {@code L210}
     * @param  tranTypeCd     the row's two-character type code, moved at {@code L212}
     * @param  tranCatCd      the row's four-digit category code, moved at {@code L211}
     * @return the key in the destination's declared component order
     * @throws NullPointerException if any part is absent
     */
    public static DisclosureGroupId primaryDisclosureKey(final String accountGroupId,
            final String tranTypeCd, final String tranCatCd) {
        Objects.requireNonNull(accountGroupId, "accountGroupId must not be null");
        Objects.requireNonNull(tranTypeCd, "tranTypeCd must not be null");
        Objects.requireNonNull(tranCatCd, "tranCatCd must not be null");
        // KEY ORDER: group, TYPE, CATEGORY. Do not reorder to match the source's move order.
        return new DisclosureGroupId(accountGroupId, tranTypeCd, tranCatCd);
    }

    /**
     * Builds the fallback disclosure-group key: the same row, probed against the padded default group.
     *
     * <p>{@code app/cbl/CBACT04C.cbl:L437} moves the seven-character literal into a ten-character
     * field, so the value probed carries <strong>three trailing spaces</strong>; see
     * {@link #DEFAULT_ACCOUNT_GROUP_ID}. Only the group identifier changes - the type and category codes
     * are unchanged from the first probe - and the probe happens <strong>exactly once</strong>: a second
     * miss abends, because the fallback paragraph at {@code L443} to {@code L460} accepts success alone.
     * There is no loop, no third attempt, no framework retry, no retry limit, no delay and no backoff
     * anywhere on this path.
     *
     * @param  tranTypeCd the row's two-character type code, carried over from the first probe
     * @param  tranCatCd  the row's four-digit category code, carried over from the first probe
     * @return the fallback key, in the destination's declared component order
     * @throws NullPointerException if either part is absent
     */
    public static DisclosureGroupId defaultDisclosureKey(final String tranTypeCd,
            final String tranCatCd) {
        return primaryDisclosureKey(DEFAULT_ACCOUNT_GROUP_ID, tranTypeCd, tranCatCd);
    }

    /**
     * Assembles the sixteen-character identifier of one synthesized interest transaction.
     *
     * <p>{@code app/cbl/CBACT04C.cbl:L476} to {@code L480} concatenate the ten-character run date -
     * <strong>copied verbatim, never parsed or reformatted</strong> - with the six-digit suffix rendered
     * right-justified and zero-filled, which fills {@code TRAN-ID PIC X(16)} exactly. The suffix starts
     * at zero and is incremented before use at {@code L474}, so the first identifier of a run carries
     * suffix one. No sequence and no generated value is involved anywhere.
     *
     * <p>The zero fill retains the rightmost characters, which is also how the six-digit field would
     * shed a high-order digit if a run ever minted more than a million identifiers - so the rendering
     * carries the legacy field's truncation for free.
     *
     * @param  parameterDate the ten-character run date
     * @param  suffix        the identifier suffix, counting from one
     * @return the identifier, exactly {@value #TRAN_ID_WIDTH} characters
     * @throws NullPointerException     if the run date is absent
     * @throws IllegalArgumentException if the run date is not exactly {@value #PARM_DATE_WIDTH} encoded
     *                                  bytes, or the suffix is below one
     */
    public static String interestTranId(final String parameterDate, final long suffix) {
        Objects.requireNonNull(parameterDate, "parameterDate must not be null");
        if (encodedWidth(parameterDate) != PARM_DATE_WIDTH) {
            throw new IllegalArgumentException("the run date must be exactly " + PARM_DATE_WIDTH
                    + " encoded bytes to fill the identifier's date prefix, but \""
                    + FailureDiagnostics.printableForm(parameterDate) + "\" encodes to "
                    + encodedWidth(parameterDate));
        }
        if (suffix < 1L) {
            throw new IllegalArgumentException("the identifier suffix is incremented before use, so it"
                    + " counts from one, but was " + suffix);
        }
        return parameterDate + rightJustifyZeroFill(Long.toString(suffix), TRAN_ID_SUFFIX_WIDTH);
    }

    /**
     * Assembles the description of one synthesized interest transaction.
     *
     * <p>{@code app/cbl/CBACT04C.cbl:L485} to {@code L489} concatenate the literal prefix - whose
     * <strong>trailing space is part of it</strong> - with the account identifier rendered at its full
     * eleven digits, because the sending field is an eleven-digit numeric item and a shorter rendering
     * would shift every following character. The concatenation is <strong>not</strong> padded out to the
     * hundred-character description field, because the source assembles it with a statement that leaves
     * the remainder of the receiving field untouched; the record mapper pads it when it renders.
     *
     * @param  accountId the eleven-digit account identifier
     * @return the description as the source assembles it
     * @throws NullPointerException if the account identifier is absent
     */
    public static String interestDescriptionFor(final String accountId) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        return INTEREST_DESCRIPTION_PREFIX + rightJustifyZeroFill(accountId, ACCT_ID_WIDTH);
    }

    /**
     * Renders one synthesized interest transaction as the fixed-width record image the legacy dataset
     * carries.
     *
     * <p>The layout {@code app/cpy/CVTRA05Y.cpy} has exactly one authority in this module, the record
     * mapper, and this method delegates to it: <strong>no offset arithmetic is performed here.</strong>
     * The rendered width is confirmed on the encoded byte array before the image is handed back.
     *
     * @param  transaction the transaction to render
     * @return the record image, exactly {@value #INTEREST_RECORD_LENGTH} encoded bytes wide
     * @throws NullPointerException     if the transaction or a mapped property of it is absent
     * @throws IllegalArgumentException propagated from the mapper if a field does not fit its layout
     * @throws IllegalStateException    if the rendered image is not exactly
     *                                  {@value #INTEREST_RECORD_LENGTH} encoded bytes
     */
    public static String interestRecordImage(final Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        final String image = TransactionRecordMapper.toRecord(transaction);
        requireInterestRecordWidth(image.getBytes(StandardCharsets.US_ASCII),
                transaction.getTranId());
        return image;
    }

    /**
     * Renders one synthesized interest transaction as the encoded bytes of its record image.
     *
     * @param  transaction the transaction to render
     * @return the record image's bytes, exactly {@value #INTEREST_RECORD_LENGTH} of them
     * @throws NullPointerException     if the transaction or a mapped property of it is absent
     * @throws IllegalArgumentException propagated from the mapper if a field does not fit its layout
     * @throws IllegalStateException    if the rendered image is not exactly
     *                                  {@value #INTEREST_RECORD_LENGTH} encoded bytes
     */
    public static byte[] interestRecordImageBytes(final Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        final byte[] image = TransactionRecordMapper.toRecordBytes(transaction);
        requireInterestRecordWidth(image, transaction.getTranId());
        return image;
    }

    /**
     * Confirms that a rendered record image is exactly {@value #INTEREST_RECORD_LENGTH} encoded bytes,
     * diagnosing before failing when it is not.
     *
     * <p>The width is measured on the <strong>encoded byte array</strong> and on nothing else. A
     * character count is not a width - a single character outside the seven-bit range satisfies one and
     * breaches the other - so no character count is taken here or anywhere in this class.
     *
     * <p>This is a postcondition on a delegate rather than an expected runtime condition: the mapper
     * builds its buffer at the declared record width and so cannot return another, which is why the
     * check is cheap and why it is worth stating. It is deliberately package-visible rather than private
     * so a test in this package can exercise it with a mis-sized image instead of it being an assertion
     * nobody can reach. The job configuration sits in another package and therefore cannot call it.
     *
     * @param  renderedImage the image the mapper produced
     * @param  tranId        the transaction's identifier, used only in the diagnostic and the message
     * @throws IllegalStateException if the width differs from {@value #INTEREST_RECORD_LENGTH}
     */
    static void requireInterestRecordWidth(final byte[] renderedImage, final String tranId) {
        final int renderedWidth = renderedImage.length;
        if (renderedWidth != INTEREST_RECORD_LENGTH) {
            throw outputContractBreach(ARTEFACT + " rendered " + renderedWidth + " bytes for "
                    + interestTransactionDiagnostic(tranId) + ", but every record of the "
                    + OUTPUT_RESOURCE + " dataset is fixed at " + INTEREST_RECORD_LENGTH + " bytes");
        }
    }

    /* ======================================================================================== */
    /* PRECONDITIONS AND OUTPUT-CONTRACT POSTCONDITIONS                                          */
    /* ======================================================================================== */

    /**
     * Reports the open execution state, refusing to accrue outside a step execution.
     *
     * @return the open state
     * @throws IllegalStateException if the execution was never opened, or has already ended
     */
    private Accrual requireOpenExecution() {
        final Accrual current = this.accrual;
        if (current == null) {
            throw new IllegalStateException(LEGACY_JOB + " " + LEGACY_STEP + " has no open execution:"
                    + " register this stage as its step's execution listener as well as its processor,"
                    + " because the run date and the control-break state are installed before the step");
        }
        if (current.isEnded()) {
            throw new IllegalStateException(LEGACY_JOB + " " + LEGACY_STEP + " has already ended and"
                    + " its final control break has run, so no further row may be accrued against it");
        }
        return current;
    }

    /**
     * Reads and checks the run date job parameter.
     *
     * <p><strong>The value is checked and never converted.</strong> Its presence, its encoded width and
     * its digits-only shape are confirmed - the shape because the legacy parameter at
     * {@code app/jcl/INTCALC.jcl:L22} carries no separators and is not a hyphenated date - and nothing
     * else is. It is not parsed into a temporal type, not reformatted and not normalised, because it is
     * the literal first ten characters of every identifier the run mints. The calendar validity of its
     * leading positions belongs to the module's job-parameter validator, which runs at launch, and is
     * deliberately not duplicated here.
     *
     * @param  stepExecution the step execution carrying the job parameters
     * @return the run date, exactly as supplied
     * @throws IllegalArgumentException if the parameter is missing, is not exactly
     *                                  {@value #PARM_DATE_WIDTH} encoded bytes, or carries a character
     *                                  that is not a digit
     */
    private static String requiredParameterDate(final StepExecution stepExecution) {
        final String supplied = stepExecution.getJobParameters().getString(PARM_DATE_KEY);
        if (supplied == null) {
            throw new IllegalArgumentException("job parameter [" + PARM_DATE_KEY + "] is required: it"
                    + " supplies the first " + PARM_DATE_WIDTH + " characters of every interest"
                    + " transaction identifier " + LEGACY_PROGRAM + " mints");
        }
        if (encodedWidth(supplied) != PARM_DATE_WIDTH) {
            throw new IllegalArgumentException("job parameter [" + PARM_DATE_KEY + "] must be exactly "
                    + PARM_DATE_WIDTH + " encoded bytes to fill the legacy parameter date field, but \""
                    + FailureDiagnostics.printableForm(supplied) + "\" encodes to "
                    + encodedWidth(supplied));
        }
        for (int index = 0; index < supplied.length(); index++) {
            final char character = supplied.charAt(index);
            if (character < ZERO_FILL || character > '9') {
                throw new IllegalArgumentException("job parameter [" + PARM_DATE_KEY + "] must be "
                        + PARM_DATE_WIDTH + " digits with no separators, and is not a hyphenated date,"
                        + " but \"" + FailureDiagnostics.printableForm(supplied) + "\" carries "
                        + FailureDiagnostics.printableForm(character) + " at position "
                        + (index + 1));
            }
        }
        return supplied;
    }

    /**
     * Reports the account key the control break at {@code app/cbl/CBACT04C.cbl:L194} compares.
     *
     * @param  row the category-balance row
     * @return the row's account identifier
     * @throws NullPointerException if the row carries no account identifier, which would make the
     *                              control break compare against nothing and merge unrelated accounts
     */
    private static String requiredAccountKey(final TransactionCategoryBalance row) {
        return Objects.requireNonNull(row.getTrancatAcctId(), "a category-balance row must carry the"
                + " account identifier the control break keys on; a row without one cannot be grouped");
    }

    /**
     * Reports the account group identifier every row of a group probes the disclosure group with.
     *
     * @param  account the account the group read
     * @return the account's ten-character group identifier
     * @throws NullPointerException if the account carries no group identifier
     */
    private static String requiredAccountGroupId(final Account account) {
        return Objects.requireNonNull(account.getAcctGroupId(), "the account must carry the "
                + ACCT_GROUP_ID_WIDTH + "-character group identifier the disclosure key is built from");
    }

    /**
     * Confirms that the group the service closed is the group this stage buffered.
     *
     * <p>A mismatch would mean an account's rows had been accrued under another account's key, which is
     * the one failure of a control break that no downstream figure would reveal.
     *
     * @param accountId    the account identifier the group was opened on
     * @param bufferedRows how many rows this stage buffered for it
     * @param result       what the service reported
     */
    private static void requireGroupIdentity(final String accountId, final int bufferedRows,
            final InterestCalculationService.GroupInterestResult result) {
        if (!accountId.equals(result.accountId())) {
            throw outputContractBreach("the control break closed " + accountDiagnostic(accountId)
                    + " but the accrual reported " + accountDiagnostic(result.accountId()));
        }
        if (result.recordCount() != bufferedRows
                || result.categoryInterests().size() != bufferedRows) {
            throw outputContractBreach("the control break buffered " + bufferedRows + " rows for "
                    + accountDiagnostic(accountId) + " but the accrual reported "
                    + result.recordCount() + " counted and " + result.categoryInterests().size()
                    + " detailed");
        }
        Objects.requireNonNull(result.updatedAccount(), "the control break must report the account it"
                + " rewrote, because the rewrite is what posts the group's interest");
    }

    /**
     * Confirms a row the rate gate skipped: no interest, and <strong>no transaction</strong>.
     *
     * <p>{@code app/cbl/CBACT04C.cbl:L214} tests that the rate is not zero and the matching end of the
     * condition is at {@code L217}, so a zero rate skips the computation at {@code L215} <em>and</em> the
     * fee invocation at {@code L216}. A transaction minted for a zero rate would be a record the legacy
     * never writes.
     *
     * @param row    the row's outcome
     * @param rowKey the row's key, named in the diagnostic
     */
    private static void requireSkippedRow(final InterestCalculationService.CategoryInterest row,
            final TransactionCategoryBalanceId rowKey) {
        if (row.interestTransaction() != null) {
            throw outputContractBreach(rowDiagnostic(rowKey) + " reported a zero rate yet carried a"
                    + " synthesized transaction; the rate gate encloses the computation and the write");
        }
        if (row.monthlyInterest().signum() != 0) {
            throw outputContractBreach(rowDiagnostic(rowKey) + " reported a zero rate yet a non-zero"
                    + " monthly interest");
        }
        if (row.disclosedRate().signum() != 0) {
            throw outputContractBreach(rowDiagnostic(rowKey) + " was gated as a zero rate yet carried a"
                    + " non-zero disclosed rate");
        }
    }

    /**
     * Confirms that one synthesized transaction still satisfies the fixed-width output contract, field
     * by field, in the order {@code app/cbl/CBACT04C.cbl:L473} to {@code L515} assigns them.
     *
     * <p>This is the guard this stage owes the writer, and it is why the stage exists between the
     * accrual and the dataset: every one of these is a value the legacy fixes exactly, and a byte-parity
     * comparison against the documented baseline fails on any one of them. The checks are made against
     * expectations derived here rather than by asking the service what it produced, because a check that
     * consults the same helper as the code it checks confirms nothing.
     *
     * <p>Neither the amount nor the card number ever appears in a diagnostic: one is financial data and
     * the other is a primary account number. Their breaches are reported by relationship and by width.
     *
     * @param  row             the row's outcome, carrying the transaction the group synthesized
     * @param  rowKey          the row's key, named in a diagnostic
     * @param  parameterDate   the run date every identifier begins with
     * @param  expectedSuffix  the suffix this transaction must carry
     * @param  groupCardNumber the card number established by the group's first transaction, or
     *                         {@code null} when this is the group's first
     * @return the group's card number: the one supplied, or the one this transaction establishes
     */
    private static String requireSynthesizedTransaction(
            final InterestCalculationService.CategoryInterest row,
            final TransactionCategoryBalanceId rowKey, final String parameterDate,
            final long expectedSuffix, final String groupCardNumber) {
        final Transaction transaction = row.interestTransaction();
        if (transaction == null) {
            throw outputContractBreach(rowDiagnostic(rowKey) + " reported a non-zero rate yet synthesized no"
                    + " transaction; every row inside the rate gate mints exactly one");
        }

        final String tranId = transaction.getTranId();
        requireMintedIdentifier(interestTranId(parameterDate, expectedSuffix), tranId);
        requireEncodedWidth("TRAN-ID", tranId, TRAN_ID_WIDTH, tranId);
        requireExactField("TRAN-TYPE-CD", INTEREST_TRAN_TYPE_CD, transaction.getTranTypeCd(), tranId);
        requireExactField("TRAN-CAT-CD", INTEREST_TRAN_CAT_CD, transaction.getTranCatCd(), tranId);
        requireExactField("TRAN-SOURCE", INTEREST_TRAN_SOURCE, transaction.getTranSource(), tranId);
        requireDescription(transaction.getTranDesc(), row.accountId(), tranId);
        requireExactField("TRAN-MERCHANT-ID", INTEREST_MERCHANT_ID, transaction.getMerchantId(),
                tranId);
        requireBlankField("TRAN-MERCHANT-NAME", transaction.getMerchantName(), MERCHANT_NAME_WIDTH,
                tranId);
        requireBlankField("TRAN-MERCHANT-CITY", transaction.getMerchantCity(), MERCHANT_CITY_WIDTH,
                tranId);
        requireBlankField("TRAN-MERCHANT-ZIP", transaction.getMerchantZip(), MERCHANT_ZIP_WIDTH,
                tranId);
        requireInterestAmount(transaction, row, tranId);
        requireIdenticalTimestamps(transaction, tranId);
        final String cardNumber = requireGroupCardNumber(transaction, groupCardNumber, tranId);
        requireInterestRecordWidth(TransactionRecordMapper.toRecordBytes(transaction), tranId);
        return cardNumber;
    }

    /**
     * Confirms the amount carried is the interest the row computed.
     *
     * <p>Compared by value rather than by scale, so a value stored at a different number of decimal
     * places still compares equal. Neither figure is logged: both are financial data.
     *
     * @param transaction the synthesized transaction
     * @param row         the row's outcome
     * @param tranId      the identifier named in the diagnostic
     */
    private static void requireInterestAmount(final Transaction transaction,
            final InterestCalculationService.CategoryInterest row, final String tranId) {
        final BigDecimal carried = Objects.requireNonNull(transaction.getTranAmt(),
                interestTransactionDiagnostic(tranId) + " carries no amount");
        if (carried.compareTo(row.monthlyInterest()) != 0) {
            throw outputContractBreach(interestTransactionDiagnostic(tranId) + " carries an amount that is"
                    + " not the monthly interest its row computed");
        }
    }

    /**
     * Confirms both timestamps are present, byte-identical and of the batch format's width.
     *
     * <p>{@code app/cbl/CBACT04C.cbl:L496} builds the timestamp <strong>once</strong> and {@code L497}
     * and {@code L498} move that one value into <em>both</em> the origination and the processing
     * timestamp, so the two are byte-identical. Two values that merely look alike would place different
     * bytes at offsets 278 and 304 of the record and would break parity without breaking anything that
     * would notice.
     *
     * @param transaction the synthesized transaction
     * @param tranId      the identifier named in the diagnostic
     */
    private static void requireIdenticalTimestamps(final Transaction transaction,
            final String tranId) {
        final String origination = transaction.getTranOrigTs();
        final String processing = transaction.getTranProcTs();
        requireEncodedWidth("TRAN-ORIG-TS", origination, BATCH_TIMESTAMP_WIDTH, tranId);
        requireEncodedWidth("TRAN-PROC-TS", processing, BATCH_TIMESTAMP_WIDTH, tranId);
        if (!origination.equals(processing)) {
            throw outputContractBreach(interestTransactionDiagnostic(tranId) + " carries origination"
                    + " timestamp \"" + FailureDiagnostics.printableForm(origination)
                    + "\" and processing timestamp \"" + FailureDiagnostics.printableForm(processing)
                    + "\"; one timestamp is built once and moved into both fields, so they are"
                    + " byte-identical");
        }
    }

    /**
     * Confirms the card number came from the group's cross-reference and is constant across the group.
     *
     * <p>{@code app/cbl/CBACT04C.cbl:L495} takes the card number from the cross-reference record the
     * group read at {@code L205}, <strong>never from the account identifier</strong>. The
     * cross-reference is read once per group, so every transaction of one group carries the same card
     * number; a change inside a group would mean the identifier had been resolved per row from something
     * else. <strong>The value is never logged</strong>, because it is a primary account number.
     *
     * @param  transaction the synthesized transaction
     * @param  established the card number the group's first transaction established, or {@code null}
     * @param  tranId      the identifier named in the diagnostic
     * @return the group's card number
     */
    private static String requireGroupCardNumber(final Transaction transaction,
            final String established, final String tranId) {
        final String carried = transaction.getTranCardNum();
        requireEncodedWidth("TRAN-CARD-NUM", carried, CARD_NUM_WIDTH, tranId);
        if (established != null && !established.equals(carried)) {
            throw outputContractBreach(interestTransactionDiagnostic(tranId) + " carries a card number that"
                    + " differs from the one every earlier transaction of its group carried; the"
                    + " cross-reference is read once per group");
        }
        return carried;
    }

    /**
     * Confirms the description is the literal prefix followed by the eleven-digit account identifier,
     * with nothing but field padding after it.
     *
     * <p>Accepting trailing spaces is deliberate: the source's own assembly leaves the remainder of the
     * hundred-character field untouched, so the value may reach here either unpadded or padded to the
     * field width depending on whether it has been through the column. Anything other than padding after
     * the prefix and the identifier is a breach.
     *
     * @param carried   the description carried
     * @param accountId the account identifier the description must render
     * @param tranId    the identifier named in the diagnostic
     */
    private static void requireDescription(final String carried, final String accountId,
            final String tranId) {
        Objects.requireNonNull(carried, interestTransactionDiagnostic(tranId) + " carries no description");
        final String expected = interestDescriptionFor(accountId);
        if (!carried.startsWith(expected) || !carried.substring(expected.length()).isBlank()) {
            throw outputContractBreach(interestTransactionDiagnostic(tranId)
                    + " carries description \"" + SensitiveLogRedactor.redact(carried)
                    + "\" but TRAN-DESC is the literal \"" + INTEREST_DESCRIPTION_PREFIX
                    + "\" followed by the " + ACCT_ID_WIDTH + "-digit account identifier and then only"
                    + " field padding");
        }
        if (encodedWidth(carried) > TRAN_DESC_WIDTH) {
            throw outputContractBreach(interestTransactionDiagnostic(tranId) + " carries a description of "
                    + encodedWidth(carried) + " encoded bytes, wider than TRAN-DESC at "
                    + TRAN_DESC_WIDTH);
        }
    }

    /**
     * Confirms a field carries exactly the value the source moves into it.
     *
     * @param field    the layout field name, as the diagnostic reports it
     * @param expected the value the source moves
     * @param carried  the value carried
     * @param tranId   the identifier named in the diagnostic
     */
    private static void requireExactField(final String field, final String expected,
            final String carried, final String tranId) {
        if (!expected.equals(carried)) {
            // Both operands are rendered, because for these fields the value IS the diagnosis: they
            // carry reference codes and a fixed merchant literal, and neither is an identifier nor
            // financial data. Rendered inert nonetheless - this helper is generic, and the day a caller
            // passes it something read out of a record is the day a control byte reaches the message.
            throw outputContractBreach(interestTransactionDiagnostic(tranId) + " carries " + field
                    + " \"" + FailureDiagnostics.printableForm(carried) + "\" but the source stores \""
                    + FailureDiagnostics.printableForm(expected) + "\"");
        }
    }

    /**
     * Confirms a field the source fills with spaces carries nothing else.
     *
     * @param field   the layout field name, as the diagnostic reports it
     * @param carried the value carried
     * @param width   the field's width, which the value may not exceed
     * @param tranId  the identifier named in the diagnostic
     */
    private static void requireBlankField(final String field, final String carried, final int width,
            final String tranId) {
        Objects.requireNonNull(carried, interestTransactionDiagnostic(tranId) + " carries no " + field
                + ", but the source moves spaces into it rather than leaving it absent");
        if (!carried.isBlank()) {
            throw outputContractBreach(interestTransactionDiagnostic(tranId) + " carries a non-blank "
                    + field + "; the source moves spaces into it");
        }
        if (encodedWidth(carried) > width) {
            throw outputContractBreach(interestTransactionDiagnostic(tranId) + " carries " + field + " at "
                    + encodedWidth(carried) + " encoded bytes, wider than its layout at " + width);
        }
    }

    /**
     * Confirms a field's width, measured on encoded US-ASCII bytes and never on a character count.
     *
     * @param field   the layout field name, as the diagnostic reports it
     * @param carried the value carried
     * @param width   the width the layout fixes
     * @param tranId  the identifier named in the diagnostic
     */
    private static void requireEncodedWidth(final String field, final String carried, final int width,
            final String tranId) {
        Objects.requireNonNull(carried, interestTransactionDiagnostic(tranId) + " carries no " + field);
        if (encodedWidth(carried) != width) {
            throw outputContractBreach(interestTransactionDiagnostic(tranId) + " carries " + field + " at "
                    + encodedWidth(carried) + " encoded bytes, but its layout fixes it at " + width);
        }
    }

    /**
     * Confirms the group's three running figures: its interest total, its transaction count and the
     * suffix it finished on.
     *
     * <p>The total is re-accumulated through the codec that owns the monetary scale, so the truncation
     * this stage applies is the same truncation the estate applies - the source contains no rounding
     * clause anywhere, which is why the codec truncates toward zero rather than rounding. No rounding
     * mode is selected here and no scale is imposed here.
     *
     * <p>The suffix belongs to the run and not to the group, so it is confirmed to have advanced by
     * exactly one per transaction minted: a suffix that jumped would mint identifiers the legacy never
     * mints, and a suffix that stalled would mint duplicates.
     *
     * @param result        what the group produced
     * @param accruedRows   the per-row detail this stage assembled, in presentation order
     * @param initialSuffix the suffix the group continued from
     */
    private static void requireRunningTotals(
            final InterestCalculationService.GroupInterestResult result,
            final List<AccruedCategoryRow> accruedRows, final long initialSuffix) {
        BigDecimal running = ZERO_MONETARY;
        long minted = 0L;
        long lastSuffix = initialSuffix;
        for (final AccruedCategoryRow row : accruedRows) {
            if (row.rateGateSkipped()) {
                continue;
            }
            // Line 467: ADD WS-MONTHLY-INT TO WS-TOTAL-INT, one already-truncated addend at a time.
            running = ZonedDecimalCodec.storeIntoMonetary(running.add(row.monthlyInterest()),
                    ZonedDecimalCodec.INTEGER_DIGITS_PIC_S9_09_V99, FIELD_WS_TOTAL_INT);
            minted++;
            lastSuffix = row.tranIdSuffix();
        }
        if (running.compareTo(result.totalInterest()) != 0) {
            throw outputContractBreach(accountDiagnostic(result.accountId()) + " posted a group total that"
                    + " is not the sum of its " + minted + " truncated monthly interest amounts");
        }
        if (minted != result.interestTransactions().size()) {
            throw outputContractBreach(accountDiagnostic(result.accountId()) + " minted an identifier for "
                    + minted + " rows but reported " + result.interestTransactions().size()
                    + " transactions");
        }
        if (lastSuffix != result.lastTranIdSuffix()) {
            throw outputContractBreach(accountDiagnostic(result.accountId()) + " advanced the identifier"
                    + " suffix to " + lastSuffix + " but reported " + result.lastTranIdSuffix()
                    + "; the suffix advances by exactly one per transaction and belongs to the run");
        }
    }

    /**
     * Confirms the control break zeroed <strong>both</strong> cycle accumulators.
     *
     * <p>{@code app/cbl/CBACT04C.cbl:L353} and {@code L354} zero the cycle credit and the cycle debit,
     * not one of them, before the account is rewritten at {@code L356}. Zeroing only one would leave a
     * half-closed cycle that every later run would compound - a defect that grows quietly rather than
     * failing, which is why it is asserted here rather than assumed.
     *
     * <p><strong>Asserted only for a group whose control break actually ran.</strong> The run's final
     * group closes through the unreachable end-of-file arm, which zeroes nothing, so applying this
     * confirmation to it would assert the very divergence the translation refuses to introduce.
     *
     * @param updatedAccount the account as the control break rewrote it
     */
    private static void requireClosedCycleAccumulators(final Account updatedAccount) {
        final BigDecimal cycleCredit = Objects.requireNonNull(updatedAccount.getAcctCurrCycCredit(),
                "the rewritten account carries no cycle credit accumulator");
        final BigDecimal cycleDebit = Objects.requireNonNull(updatedAccount.getAcctCurrCycDebit(),
                "the rewritten account carries no cycle debit accumulator");
        if (cycleCredit.signum() != 0 || cycleDebit.signum() != 0) {
            throw outputContractBreach("the control break of "
                    + accountDiagnostic(updatedAccount.getAcctId())
                    + " left a cycle accumulator un-zeroed; the source zeroes both the credit and the"
                    + " debit before rewriting the account");
        }
    }

    /**
     * Publishes the run's counters and flags to the step's execution context.
     *
     * <p>Counts and flags only. The execution context is persisted to the job repository, so no monetary
     * amount, no account identifier and no card number is published through it.
     *
     * @param stepExecution the step execution to publish on
     * @param current       the execution state to publish
     */
    private static void publishCounters(final StepExecution stepExecution, final Accrual current) {
        final ExecutionContext context = stepExecution.getExecutionContext();
        context.putInt(CONTEXT_GROUPS_CLOSED, current.groupsClosed());
        context.putInt(CONTEXT_ROWS_READ, current.recordCount());
        context.putInt(CONTEXT_TRANSACTIONS, current.transactionsSynthesized());
        context.putLong(CONTEXT_LAST_TRAN_ID_SUFFIX, current.tranIdSuffix());
        context.putString(CONTEXT_RATE_GATE_SKIPPED, Boolean.toString(current.isRateGateSkipped()));
        context.putString(CONTEXT_DEFAULT_GROUP_USED, Boolean.toString(current.isDefaultGroupUsed()));
    }

    /**
     * Diagnoses an output-contract breach and returns the failure to raise.
     *
     * <p>The diagnostic precedes the failure, which is the ordering the batch tier uses throughout. A
     * breach is never answered by filtering the row: a filtered interest transaction is a transaction
     * the legacy wrote and this run did not, so the step fails instead. Diagnosing at the moment of
     * first detection matters because a failure raised inside a processor fails the whole chunk, after
     * which the framework may re-present that chunk item by item.
     *
     * @param  detail what was breached, carrying no monetary amount and no card number
     * @return the failure the caller raises
     */
    private static IllegalStateException outputContractBreach(final String detail) {
        LOGGER.error("{} {} output contract breached in program {}: {}", LEGACY_JOB, LEGACY_STEP,
                LEGACY_PROGRAM, detail);
        return new IllegalStateException(detail);
    }

    /**
     * Names a synthesized interest transaction in a diagnostic by a redacted reference.
     *
     * <p>Every postcondition on this stage's output opens with this clause, so the identifier is
     * withheld once rather than at each of the fourteen places a message could carry it, and a message
     * added later cannot reintroduce it by omission.
     *
     * <p>A reference rather than nothing at all: the postconditions are stated per row, and a reader of
     * a failed run needs to know which of an account's rows breached. {@link
     * SensitiveLogRedactor#redact(String)} returns a reference that is stable for a given identifier
     * within the run, so the several messages one breaching row can produce still tie to one another.
     * The token is lower-case ASCII hexadecimal, so a value assembled from field content cannot carry a
     * control byte, a delimiter or a line terminator into a log record or an exception message.
     *
     * @param  tranId the transaction's identifier, never rendered
     * @return the opening clause, carrying a redacted reference
     */
    // See docs/decision-log.md entry DL-177 for why one composer per identifier kind, rather than a
    // redaction at each of the fourteen messages that would otherwise have named the identifier.
    private static String interestTransactionDiagnostic(final String tranId) {
        return "interest transaction " + SensitiveLogRedactor.redact(tranId);
    }

    /**
     * Names an account in a diagnostic by a redacted reference.
     *
     * @param  accountId the eleven-digit account identifier, never rendered
     * @return the clause {@code account <reference>}
     */
    private static String accountDiagnostic(final String accountId) {
        return "account " + SensitiveLogRedactor.redact(accountId);
    }

    /**
     * Names a transaction-category-balance row in a diagnostic without rendering its account.
     *
     * <p>The composite key's own {@code toString} renders all three components, and the first of them is
     * the account identifier, so the key is decomposed here: the account travels as a redacted reference
     * and the two-character type code and four-character category code travel as themselves, being
     * reference codes drawn from the estate's own tables rather than data about a cardholder. The two
     * codes are what a reader needs to find the row within the account's group.
     *
     * @param  rowKey the row's composite key
     * @return the clause {@code row <reference>/<type>/<category>}
     */
    private static String rowDiagnostic(final TransactionCategoryBalanceId rowKey) {
        return "row " + SensitiveLogRedactor.redact(rowKey.getTrancatAcctId()) + "/"
                + rowKey.getTrancatTypeCd() + "/" + rowKey.getTrancatCd();
    }

    /**
     * Confirms the identifier carried is the one the source mints for the row.
     *
     * <p>Held apart from {@link #requireExactField(String, String, String, String)} rather than routed
     * through it, because that check renders both operands and both operands here are transaction
     * identifiers. The remaining fields it checks carry reference codes and a fixed merchant literal,
     * which are neither identifiers nor financial data, so they are still rendered as themselves - the
     * value is the whole diagnostic there.
     *
     * <p>Neither identifier is rendered. What the message states instead is the rule that was broken,
     * which is what a reader acts on: the identifier is the parameter date followed by a six-digit
     * suffix, and the suffix belongs to the run rather than to the row.
     *
     * @param  minted  the identifier the source mints for this row
     * @param  carried the identifier the synthesized transaction carries
     * @throws IllegalStateException if the two differ
     */
    private static void requireMintedIdentifier(final String minted, final String carried) {
        if (!minted.equals(carried)) {
            throw outputContractBreach(interestTransactionDiagnostic(carried) + " carries a TRAN-ID"
                    + " that is not the one the source mints for its row; TRAN-ID is the "
                    + PARM_DATE_WIDTH + "-character parameter date followed by the "
                    + TRAN_ID_SUFFIX_WIDTH + "-digit suffix the run had reached");
        }
    }

    /**
     * Reports a value's width in encoded US-ASCII bytes, which is the only width a fixed-width layout
     * recognises.
     *
     * @param  value the value to measure
     * @return its encoded width
     */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Renders a value into a numeric field that is right-justified and zero-filled, reproducing a COBOL
     * move into a {@code JUST RIGHT} item whose remaining spaces are replaced by zeros.
     *
     * <p>The rightmost characters are retained, so an over-long sender loses its high-order digits
     * exactly as the legacy field would. Deliberately derived here rather than delegated to the module's
     * COBOL string utilities: these values are the <em>expectations</em> the output-contract checks are
     * made against, and an expectation computed by the same helper as the value it checks would confirm
     * nothing.
     *
     * @param  value the sending value
     * @param  width the receiving field's character-position count
     * @return the receiver's contents, exactly {@code width} characters
     */
    private static String rightJustifyZeroFill(final String value, final int width) {
        final char[] receiver = new char[width];
        final int retained = Math.min(value.length(), width);
        final int leftFill = width - retained;
        for (int index = 0; index < leftFill; index++) {
            receiver[index] = ZERO_FILL;
        }
        value.getChars(value.length() - retained, value.length(), receiver, leftFill);
        for (int index = leftFill; index < width; index++) {
            if (receiver[index] == SPACE) {
                receiver[index] = ZERO_FILL;
            }
        }
        return new String(receiver);
    }

    /* ======================================================================================== */
    /* WHAT THIS STAGE EMITS                                                                     */
    /* ======================================================================================== */

    /**
     * What one category-balance row of a closed group decided.
     *
     * <p>One entry exists per row the group contained, in the order the rows were presented, so a writer
     * or a parity comparison can walk a group's decisions in the order the legacy read loop took them.
     *
     * @param rowKey              the row's three-part record key, in the destination's declared
     *                            component order
     * @param primaryDisclosureKey the disclosure-group key the row was <em>first</em> probed with,
     *                            ordered group, type, category - which is <strong>not</strong> the order
     *                            the source moves the parts in
     * @param categoryBalance     the row's balance, at the monetary scale
     * @param disclosedRate       the rate the disclosure group supplied, at the monetary scale
     * @param monthlyInterest     the interest the row produced, truncated to two decimals; zero when the
     *                            rate gate skipped the row
     * @param rateGateSkipped     {@code true} when the rate was zero, so both the computation and the fee
     *                            invocation were skipped
     * @param defaultGroupUsed    {@code true} when the first probe missed and the padded default group
     *                            supplied the rate, after exactly one fallback probe
     * @param feeParagraphInvoked {@code true} when the service reached its single fee-paragraph
     *                            invocation for this row, exactly when the rate gate did not skip it
     * @param tranIdSuffix        the six-digit suffix this row's identifier carries, or
     *                            {@link #NO_TRAN_ID_SUFFIX} when no identifier was minted
     * @param interestTransaction the transaction the row synthesized, or {@code null} when the rate gate
     *                            skipped it
     * @since 1.0.0
     */
    public record AccruedCategoryRow(TransactionCategoryBalanceId rowKey,
                                     DisclosureGroupId primaryDisclosureKey,
                                     BigDecimal categoryBalance,
                                     BigDecimal disclosedRate,
                                     BigDecimal monthlyInterest,
                                     boolean rateGateSkipped,
                                     boolean defaultGroupUsed,
                                     boolean feeParagraphInvoked,
                                     long tranIdSuffix,
                                     Transaction interestTransaction) {

        /**
         * Canonical constructor, which requires every value the row is identified and measured by. The
         * transaction is deliberately not required, because its absence is the rate gate's own signal.
         */
        public AccruedCategoryRow {
            Objects.requireNonNull(rowKey, "rowKey must not be null");
            Objects.requireNonNull(primaryDisclosureKey, "primaryDisclosureKey must not be null");
            Objects.requireNonNull(categoryBalance, "categoryBalance must not be null");
            Objects.requireNonNull(disclosedRate, "disclosedRate must not be null");
            Objects.requireNonNull(monthlyInterest, "monthlyInterest must not be null");
        }

        /**
         * Reports whether this row produced a transaction, which is the negation of the rate gate having
         * skipped it.
         *
         * @return {@code true} when a transaction was synthesized for this row
         */
        public boolean producedTransaction() {
            return this.interestTransaction != null;
        }

        /**
         * The disclosure-group key that actually supplied this row's rate: the primary key, or the
         * padded default-group key when the single fallback probe was the one that resolved.
         *
         * @return the key the rate came from
         */
        public DisclosureGroupId effectiveDisclosureKey() {
            if (!this.defaultGroupUsed) {
                return this.primaryDisclosureKey;
            }
            return defaultDisclosureKey(this.primaryDisclosureKey.getDisTranTypeCd(),
                    this.primaryDisclosureKey.getDisTranCatCd());
        }
    }

    /**
     * What one closed account group produced: the item this stage hands on when a control break fires.
     *
     * <p>Every value here is already durable when the group is emitted - the service saved every
     * transaction, and the rewritten account where the control break rewrote one, inside its own
     * transaction before returning - so a writer consuming this record is rendering the legacy
     * fixed-width output, not performing the posting.
     *
     * @param accountId            the eleven-digit account identifier the group keyed on
     * @param parameterDate        the ten-character run date every identifier in the group begins with
     * @param totalInterest        the running total the control break added to the account's current
     *                             balance, or the total the end-of-file arm withheld from it
     * @param rows                 one entry per category-balance row, in the order the rows were
     *                             presented
     * @param interestTransactions the transactions the group synthesized, in the order written
     * @param updatedAccount       the account as the control break rewrote it, with <strong>both</strong>
     *                             cycle accumulators zeroed - or, for the run's final group, the account
     *                             exactly as it was read
     * @param accountRewritten     {@code true} when paragraph {@code 1050-UPDATE-ACCOUNT} ran for this
     *                             group; {@code false} for the run's final group, whose control break
     *                             the loop's own termination makes unreachable
     * @param rateGateSkipped      {@code true} when at least one row of the group had a zero rate
     * @param defaultGroupUsed     {@code true} when at least one row of the group fell back to the padded
     *                             default group
     * @param recordCount          how many category-balance rows the group contained
     * @param lastTranIdSuffix     the six-digit suffix the run stands at after this group, because the
     *                             suffix belongs to the run and not to any one group
     * @param accruedAt            the twenty-six-character batch timestamp taken once when this group
     *                             closed, in the batch tier's single timestamp format
     * @since 1.0.0
     */
    public record AccruedAccountGroup(String accountId,
                                      String parameterDate,
                                      BigDecimal totalInterest,
                                      List<AccruedCategoryRow> rows,
                                      List<Transaction> interestTransactions,
                                      Account updatedAccount,
                                      boolean accountRewritten,
                                      boolean rateGateSkipped,
                                      boolean defaultGroupUsed,
                                      int recordCount,
                                      long lastTranIdSuffix,
                                      String accruedAt) {

        /**
         * Canonical constructor, which requires every value and defends both collections against later
         * mutation so a group handed to a writer cannot change underneath it.
         */
        public AccruedAccountGroup {
            Objects.requireNonNull(accountId, "accountId must not be null");
            Objects.requireNonNull(parameterDate, "parameterDate must not be null");
            Objects.requireNonNull(totalInterest, "totalInterest must not be null");
            Objects.requireNonNull(updatedAccount, "updatedAccount must not be null");
            Objects.requireNonNull(accruedAt, "accruedAt must not be null");
            rows = List.copyOf(rows);
            interestTransactions = List.copyOf(interestTransactions);
        }

        /**
         * How many transactions this group synthesized.
         *
         * @return the transaction count, which is the number of rows the rate gate did not skip
         */
        public int transactionCount() {
            return this.interestTransactions.size();
        }
    }

    /* ======================================================================================== */
    /* PER-EXECUTION STATE                                                                       */
    /* ======================================================================================== */

    /**
     * The working-storage items of {@code app/cbl/CBACT04C.cbl:L166} to {@code L173}, plus the buffer of
     * the open group, held for the life of one step execution.
     *
     * <p>Held as one holder rather than as a spread of loose fields on the stage so that opening an
     * execution is a single assignment and cannot half-happen, and so that nothing survives from one
     * execution into the next. Every accessor and mutator is package-private and the type is private, so
     * no caller outside this stage can reach any of it.
     *
     * <p>Not thread-safe, and deliberately so: a control break is order-dependent and this stage is
     * documented as sequential-only. Making it safe for concurrent chunks would make an unsupported
     * configuration silently produce wrong groups instead of loudly producing none.
     */
    private static final class Accrual {

        /** The ten-character run date, fixed for the execution. */
        private final String parameterDate;

        /**
         * The rows of the group currently filling, in the order they arrived.
         *
         * <p>Not final, because a closing group's rows are <em>handed over</em> rather than copied: the
         * buffer is replaced with a fresh one, so at no point do two lists of one group's rows exist.
         * See {@code docs/decision-log.md} entry DL-176.
         */
        private List<TransactionCategoryBalance> openGroupRows = new ArrayList<>();

        /** {@code WS-LAST-ACCT-NUM} at line 167, which starts as spaces. */
        private String lastAccountNumber = NO_ACCOUNT_YET;

        /** {@code WS-FIRST-TIME} at line 170, which starts as {@code Y}. */
        private boolean firstTime = true;

        /** {@code WS-TRANID-SUFFIX} at line 173, which starts at zero and belongs to the run. */
        private long tranIdSuffix;

        /** {@code WS-RECORD-COUNT} at line 172, incremented once per row at line 192. */
        private int recordCount;

        /** How many control breaks have closed a group in this execution. */
        private int groupsClosed;

        /** How many interest transactions this execution has synthesized. */
        private int transactionsSynthesized;

        /** Whether any row anywhere in the execution had a zero rate. */
        private boolean rateGateSkipped;

        /** Whether any row anywhere in the execution fell back to the padded default group. */
        private boolean defaultGroupUsed;

        /** Whether the final control break has run, after which no further row may be accrued. */
        private boolean ended;

        /** The group the final control break closed, retained for the job configuration. */
        private AccruedAccountGroup finalGroup;

        Accrual(final String parameterDate) {
            this.parameterDate = parameterDate;
        }

        String parameterDate() {
            return this.parameterDate;
        }

        String lastAccountNumber() {
            return this.lastAccountNumber;
        }

        boolean isFirstTime() {
            return this.firstTime;
        }

        /** Line 198: the flag is cleared exactly once, on the first key change of the execution. */
        void clearFirstTime() {
            this.firstTime = false;
        }

        /** Line 192. */
        void countRecord() {
            this.recordCount++;
        }

        int recordCount() {
            return this.recordCount;
        }

        /**
         * Lines 200 and 201: remembers the new key and empties the buffer, which is this stage's form of
         * resetting the group's running total - the total itself is accumulated by the group call.
         *
         * <p>The buffer is already empty on the control-break path, because closing a group hands its
         * rows over and installs a fresh buffer. The reset is kept because the source keeps it, and it
         * is what makes the first group of a run start from an empty buffer too.
         *
         * @param accountId the account identifier the new group keys on
         */
        void beginGroup(final String accountId) {
            this.lastAccountNumber = accountId;
            this.openGroupRows.clear();
        }

        void buffer(final TransactionCategoryBalance row) {
            this.openGroupRows.add(row);
        }

        boolean hasOpenGroup() {
            return !this.openGroupRows.isEmpty();
        }

        /**
         * Hands the open group's rows over and starts a fresh buffer in their place.
         *
         * <p>Handed over rather than copied. Replacing the buffer instead of emptying it gives the
         * service a view that cannot change underneath it <em>and</em> leaves only one list of the
         * group's rows in existence, where a defensive copy would have left two. The view is
         * unmodifiable because the group is the service's input and never its workspace.
         *
         * @return the rows of the group being closed, in arrival order
         */
        List<TransactionCategoryBalance> detachOpenGroupRows() {
            final List<TransactionCategoryBalance> closing = this.openGroupRows;
            this.openGroupRows = new ArrayList<>();
            return Collections.unmodifiableList(closing);
        }

        long tranIdSuffix() {
            return this.tranIdSuffix;
        }

        /**
         * Folds a closed group's outcome back into the execution.
         *
         * <p>The buffer is not emptied here: {@link #detachOpenGroupRows()} already replaced it when the
         * group's rows were handed over, so emptying again would clear the buffer the next group has
         * begun filling.
         *
         * @param lastSuffix   the suffix the group finished on, which the next group continues from
         * @param transactions how many transactions the group synthesized
         * @param gateSkipped  whether any row of the group had a zero rate
         * @param defaultUsed  whether any row of the group fell back to the padded default group
         */
        void completeGroup(final long lastSuffix, final int transactions, final boolean gateSkipped,
                final boolean defaultUsed) {
            this.tranIdSuffix = lastSuffix;
            this.transactionsSynthesized += transactions;
            this.rateGateSkipped |= gateSkipped;
            this.defaultGroupUsed |= defaultUsed;
            this.groupsClosed++;
        }

        int groupsClosed() {
            return this.groupsClosed;
        }

        int transactionsSynthesized() {
            return this.transactionsSynthesized;
        }

        boolean isRateGateSkipped() {
            return this.rateGateSkipped;
        }

        boolean isDefaultGroupUsed() {
            return this.defaultGroupUsed;
        }

        void markEnded() {
            this.ended = true;
        }

        boolean isEnded() {
            return this.ended;
        }

        void rememberFinalGroup(final AccruedAccountGroup group) {
            this.finalGroup = group;
        }

        AccruedAccountGroup finalGroup() {
            return this.finalGroup;
        }
    }

    /**
     * The stage's gateway to {@link AbstractCobolStep}'s twenty-six-character batch timestamp.
     *
     * <p>The estate carries <strong>two different</strong> twenty-six-character timestamp forms and they
     * must never be swapped: the batch form separates the date from the time with a hyphen, separates the
     * time's parts with dots, carries a two-digit hundredths field and ends with a literal four-character
     * tail, while the online form carries a space, colons and a six-digit fraction. Emitting the online
     * form from a batch run produces a value of exactly the right length that is wrong in four character
     * positions - which breaks byte parity without breaking anything that would notice. The batch form is
     * assembled by the shared step template and is reachable only from a subclass of it, so this stage
     * subclasses the template rather than assembling a second formatter that could drift from the first.
     *
     * <p><strong>This is not a tasklet and must never be run as one.</strong> The interest step is
     * chunk-oriented: the framework's reader performs the read, the enclosing stage performs the control
     * break and the job configuration's writer performs the output, so the template's own open,
     * read-loop, process and close skeleton has no work to do here. Its four hooks therefore refuse
     * rather than returning, because the alternative is worse than a failure: a template driven with an
     * immediately exhausted read would complete in one chunk having read nothing, and a zero-record
     * interest run that reports success is precisely the outcome no operator would question. The refusal
     * turns a wiring mistake into a loud one.
     *
     * <p>The instance is held privately by the enclosing stage and is never registered as a bean, a
     * tasklet or a step. The type is deliberately package-visible rather than private, so that a test in
     * this package can exercise the wiring guard directly instead of it being a refusal nobody can
     * reach; the job configuration sits in another package and therefore cannot name it at all.
     */
    static final class BatchTimestampGateway
            extends AbstractCobolStep<TransactionCategoryBalance> {

        BatchTimestampGateway(final MeterRegistry meterRegistry, final Clock clock) {
            super(LEGACY_PROGRAM, meterRegistry, clock);
        }

        /**
         * The batch timestamp for this instant, in the batch tier's single twenty-six-character format:
         * paragraph {@code Z-GET-DB2-FORMAT-TIMESTAMP} of {@code app/cbl/CBACT04C.cbl:L613} to
         * {@code L626}.
         *
         * @return the timestamp, {@link #BATCH_TIMESTAMP_WIDTH} encoded bytes wide
         */
        String batchTimestamp() {
            return currentBatchTimestamp();
        }

        /**
         * The encoded width of that timestamp, as the shared step template declares it.
         *
         * @return the timestamp width
         */
        static int timestampWidth() {
            return BATCH_TIMESTAMP_LENGTH;
        }

        @Override
        protected void openResources() {
            throw notATasklet("open");
        }

        @Override
        protected Optional<TransactionCategoryBalance> readNextRecord() {
            throw notATasklet("read");
        }

        @Override
        protected void processRecord(final TransactionCategoryBalance record) {
            throw notATasklet("process");
        }

        @Override
        protected void closeResources() {
            throw notATasklet("close");
        }

        /**
         * The wiring guard behind all four template hooks.
         *
         * @param  family the paragraph family that was reached
         * @return the failure the hook raises
         */
        private static IllegalStateException notATasklet(final String family) {
            return new IllegalStateException("the " + family + " family of program " + LEGACY_PROGRAM
                    + " is not driven through this gateway: the interest step is chunk-oriented, so its"
                    + " reader, its control break and its writer own that work. This gateway exists"
                    + " only to reach the batch tier's " + BATCH_TIMESTAMP_LENGTH
                    + "-character timestamp, and reaching it as a tasklet is a wiring error");
        }
    }
}
