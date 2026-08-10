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

import com.carddemo.domain.DailyTransaction;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.RejectReason;
import com.carddemo.service.TransactionPostingService;
import com.carddemo.util.FailureDiagnostics;
import com.carddemo.util.SensitiveLogRedactor;
import com.carddemo.util.ZonedDecimalCodec;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;

/**
 * Routes one daily-transaction record through the legacy posting program's per-record decision -
 * post it, or reject it - and turns that decision into the two things a chunk-oriented step needs: a
 * reject item for the records the cascade refused, and nothing at all for the records it posted.
 *
 * <h2>What this class is, and what it deliberately is not</h2>
 *
 * <p>This is the batch <em>adapter</em> for the posting program's read loop, not a second
 * implementation of it. The cascade itself - the cross-reference and account lookups, the overlimit
 * and expiration tests, the three persistence stages, the reject-code precedence and the timestamp
 * regeneration - lives in {@link TransactionPostingService}, which is the module's translation of the
 * whole legacy member, owns the durable boundary of each of its three stores and documents every parity
 * rule that governs it. No lookup, no limit test, no balance arithmetic, no persistence and no ordering rule is
 * restated here: a business rule that exists twice is a business rule that is enforced once and
 * audited nowhere. The paragraph-level inventory for the member is in
 * {@code docs/traceability-matrix.md}.
 *
 * <p>What is genuinely this class's own, and is therefore all it contains: calling the service on
 * exactly the record the reader produced, converting a refusal into the reject-dataset item, filtering
 * a posted record out of the chunk so that no reject record is emitted for it, confirming the handful
 * of per-record fidelity properties that a batch adapter is the right place to confirm, counting the
 * verdicts for the metrics endpoint, and contributing the legacy completion code once the step has
 * finished.
 *
 * <h2>Why a posted record returns {@code null}</h2>
 *
 * <p>The legacy mainline branches once per record: it posts when the fail reason is zero, and
 * otherwise counts a reject and writes a reject record. A chunk-oriented step splits that branch
 * across collaborators - the reader supplies records, this processor renders the verdict, the reject
 * writer emits the refused ones - so the branch becomes the return value of
 * {@link #process(DailyTransaction)}: the reject item on the refusing arm, and {@code null} on the
 * posting arm. Returning {@code null} is the framework's filter contract, and filtering is the only
 * translation that keeps a posted record out of the reject dataset without inventing a second
 * destination for it.
 *
 * <p>The routing question is therefore <strong>whether the record was posted</strong>, never whether
 * it carries a reason code. Reject 109 is set after the mainline has already committed to posting and
 * never produces a reject record; a posted record carrying it returns {@code null} like any other
 * posted record. Why that reason code is inert, and why it keeps its own identity, is documented on
 * {@link TransactionPostingService}; this class must simply not second-guess the verdict it is given.
 *
 * <h2>A reject is a partial success: no exception, no skip, and a warning completion code</h2>
 *
 * <p>The legacy program refuses a record, writes it to the reject dataset and <strong>carries on
 * reading</strong>. It does not stop, does not roll the run back and does not treat the refusal as an
 * error; at the very end, having closed every file and reported both counters, it raises its
 * completion code to the warning level to say that something was refused. Every part of that is
 * preserved:
 *
 * <ul>
 *   <li>{@link #process(DailyTransaction)} <strong>never throws for a business reject</strong>. A
 *       reject code is a verdict on a record's content, and a verdict is a return value. Throwing for
 *       one would fail the chunk, and a failed chunk is not a reject dataset.</li>
 *   <li>No skip policy, retry policy, retry budget, backoff or skip limit is declared here or implied
 *       by anything here. A skip is the framework's way of tolerating a <em>failure</em>, and a reject
 *       is not a failure; routing rejects through a skip policy would silently make the tolerated
 *       count depend on a limit the legacy program never had.</li>
 *   <li>{@link #afterStep(StepExecution)} contributes
 *       {@code TransactionPostingService#RETURN_CODE_REJECTS_PRESENT} when the reject count exceeds
 *       zero, and only on a normal end of run - the legacy program reaches that rule after every file
 *       has been closed and both counters reported - so the step completes and still says so.</li>
 * </ul>
 *
 * <p>A <em>technical</em> failure is the opposite and is left entirely alone. The service diagnoses it
 * and abends, in that order - the legacy program displays the failing operation and the raw file
 * status before it abends, and the base template for the batch tier preserves that ordering - and
 * whatever it raises propagates through this processor unwrapped and untranslated. Nothing here
 * catches it, downgrades it, converts it into a reject or absorbs it, because a technical fault that
 * arrives at the reject dataset is a fault that has been disguised as a business verdict.
 *
 * <h2>What this class does not own</h2>
 *
 * <p>Neither record width on this path is assembled here. The reject record's four-hundred-and-thirty
 * bytes belong to {@link RejectRecordWriter} and the posted transaction's three-hundred-and-fifty
 * bytes to the transaction record mapper, so <strong>no offset, no field width and no padding rule is
 * stated anywhere in this file</strong>. The reader and its ordering, the reject destination, the chunk
 * size, the transaction manager, the step's timer and any condition-code gate all belong to the owning
 * job configuration, and none of them is declared here. Nor is any sort or comparator: the legacy
 * posting job declares no sort of any kind.
 *
 * <h2>Order, state and concurrency</h2>
 *
 * <p>Arrival order is observable, and execution must stay strictly sequential. The overlimit test
 * measures a cycle total that earlier records of the same run have already moved, so a different
 * arrival order refuses a different set of records. A chunk-oriented step presents items to a
 * processor one at a time in the order its reader produced them, so calling the service once per
 * record in place is all that is required, and it is all that is done. No task executor, partitioning,
 * parallel stream or asynchronous stage may be introduced on this path, because every one of them
 * interleaves records and an interleaved run makes the cycle totals - and therefore the refusals -
 * depend on timing. Nothing here sorts, re-sorts, groups, re-keys, batches or defers a record.
 *
 * <p>The class holds no mutable business state whatever. Its only fields are the injected service, the
 * meter registry, and meters registered once at construction; the fail reason, the description and
 * every per-record value are method locals. A reason code held in a field would leak from one record
 * into the next, which is precisely what the legacy program's per-record reset exists to prevent. The
 * run counters are not fields either: they are read from the step execution the framework already
 * maintains per execution, so a restart cannot inherit a previous run's totals. One instance is
 * therefore safely shared for every record of every execution.
 *
 * <p>Provenance: translated from the legacy estate at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy program, its copybooks and its job
 * stream are cited, never transcribed, and are never read at run time.
 *
 * @see TransactionPostingService
 * @see RejectRecordWriter
 * @see AbstractCobolStep
 * @since 1.0.0
 */
public final class TransactionValidationProcessor
        implements ItemProcessor<DailyTransaction, RejectRecordWriter.RejectedTransaction>,
        StepExecutionListener {

    /**
     * The legacy batch program whose read-loop decision this processor adapts.
     *
     * <p>Published so that the owning job configuration, the traceability matrix and the tests can name
     * the same origin without a literal appearing in three places. It identifies a program; nothing
     * resolves a path, a dataset or a load module from it, and no legacy artefact is read at run time.
     */
    public static final String LEGACY_PROGRAM = TransactionPostingService.PROGRAM_NAME;

    /** The legacy job stream that runs {@value #LEGACY_PROGRAM}, in its single application step. */
    public static final String LEGACY_JOB = "POSTTRAN";

    /** The one step of {@value #LEGACY_JOB} that executes {@value #LEGACY_PROGRAM}. */
    public static final String LEGACY_STEP = "STEP15";

    /**
     * Step execution-context key under which the count of records read is published.
     *
     * <p>Stands in for the legacy transaction counter, which the mainline increments once per record
     * read and reports at the end of the run.
     */
    public static final String EXECUTION_CONTEXT_TRANSACTIONS_PROCESSED =
            "transactionValidationProcessor.transactionsProcessed";

    /**
     * Step execution-context key under which the count of records that ran the three persistence
     * stages is published.
     *
     * <p>The legacy program carries this figure implicitly, as the difference between the two counters
     * it displays; it is published explicitly here so that a reader of the step does not have to
     * subtract.
     */
    public static final String EXECUTION_CONTEXT_TRANSACTIONS_POSTED =
            "transactionValidationProcessor.transactionsPosted";

    /**
     * Step execution-context key under which the count of refused records is published.
     *
     * <p>Stands in for the legacy reject counter, which the mainline increments once immediately before
     * each reject record is written.
     */
    public static final String EXECUTION_CONTEXT_TRANSACTIONS_REJECTED =
            "transactionValidationProcessor.transactionsRejected";

    /**
     * Step execution-context key under which the legacy completion code of the step is published.
     *
     * <p>Zero when nothing was refused, and the warning-level code when anything was. Published as a
     * number as well as being expressed in the exit status, because a downstream gate reasons about
     * completion codes numerically while the framework reasons about exit codes textually.
     */
    public static final String EXECUTION_CONTEXT_RETURN_CODE =
            "transactionValidationProcessor.returnCode";

    /**
     * Exit code a completed step carries when at least one record was refused.
     *
     * <p>This is the decimal image of {@code TransactionPostingService#RETURN_CODE_REJECTS_PRESENT} and
     * is <strong>derived from it rather than restated</strong>, so the textual and numeric forms of the
     * completion code cannot drift apart.
     *
     * <p>Writing the completion code into the exit code is the convention the batch tier of this module
     * already uses: a condition-code gate reads a step's exit code and, when it is a short run of ASCII
     * digits, reads it as the completion code that step contributed. Every gate in this module is the one
     * strict form, so a step carrying this code is refused by any gate that follows it inside the same
     * execution - which is exactly how the legacy job entry system behaved. The legacy posting job carries
     * no gate of its own, so nothing inside it is bypassed; the code exists so that an operator, and the
     * job's own recorded outcome, can tell a run that refused records from one that refused none.
     *
     * <p>The digits deliberately do not spell a framework status name. An exit code that matches none of
     * the framework's own names ranks above all of them when exit statuses are combined, which is what
     * makes it survive to the step execution - and is also why it is contributed only to a step that
     * genuinely completed. See {@link #afterStep(StepExecution)}.
     */
    public static final String EXIT_CODE_REJECTS_PRESENT =
            Integer.toString(TransactionPostingService.RETURN_CODE_REJECTS_PRESENT);

    /**
     * Diagnostics for this stage.
     *
     * <p>A record is never logged in whole or in part beyond its transaction identifier and its verdict.
     * The amount is financial data, the card number is a primary account number, and neither belongs in
     * a log line, an exception message or a metric tag.
     */
    private static final Logger LOGGER =
            LoggerFactory.getLogger(TransactionValidationProcessor.class);

    /** Prefix every diagnostic and every message carries, naming the legacy job step being replaced. */
    private static final String DIAGNOSTIC_PREFIX = LEGACY_JOB + " " + LEGACY_STEP;

    /** Meter counting per-record verdicts, tagged by outcome and by the reason code involved. */
    private static final String METRIC_RECORDS = "carddemo.batch.posting.records";

    /** Meter timing the per-record posting call, tagged by outcome. */
    private static final String METRIC_RECORD_TIMER = "carddemo.batch.posting.record";

    /** Tag naming what became of a record. */
    private static final String TAG_OUTCOME = "outcome";

    /** Tag naming the four-digit reject reason a record carried, or that it carried none. */
    private static final String TAG_REASON = "reason";

    /** Outcome tag value of a record that ran the three persistence stages. */
    private static final String OUTCOME_POSTED = "POSTED";

    /** Outcome tag value of a record the validation cascade refused. */
    private static final String OUTCOME_REJECTED = "REJECTED";

    /**
     * Outcome tag value of a record whose posting attempt raised a technical failure.
     *
     * <p>Distinct from {@link #OUTCOME_REJECTED} on purpose: a refusal is a verdict on content and a
     * failure is a fault in the platform, and a dashboard that could not tell them apart would read a
     * database outage as a surge of bad data.
     */
    private static final String OUTCOME_FAILED = "FAILED";

    /** Reason tag value of a record that carried no reject reason at all. */
    private static final String REASON_NONE = "NONE";

    /**
     * The moment the batch timestamp template is rendered from.
     *
     * <p>Its only purpose is to make the helper produce one well-formed image whose separator positions
     * can then be read off. The particular moment is immaterial - every field of the format is
     * zero-padded to a fixed width, so any moment yields the same shape - and it is fixed rather than
     * taken from a clock so that the template is deterministic and computed once.
     */
    private static final LocalDateTime TEMPLATE_MOMENT =
            LocalDateTime.of(2000, 1, 2, 3, 4, 5, 60_000_000);

    /**
     * A well-formed batch timestamp image, used as the template a regenerated processing timestamp is
     * checked against.
     *
     * <p>Rendered by the batch timestamp helper this package owns, so the expected separators are
     * <strong>derived from the single authority for the format</strong> rather than restated as literals
     * here. That matters more than it looks: the whole point of the check is to catch a value of the
     * right width in the wrong format, and a check whose expectations were typed out by hand could
     * disagree with the helper without either of them being obviously wrong.
     */
    private static final String BATCH_TIMESTAMP_TEMPLATE =
            AbstractCobolStep.formatBatchTimestamp(TEMPLATE_MOMENT);

    /** Lowest ASCII digit, used instead of a locale-sensitive digit test. */
    private static final char ASCII_ZERO = '0';

    /** Highest ASCII digit, used instead of a locale-sensitive digit test. */
    private static final char ASCII_NINE = '9';

    /** The posting cascade, which opens a durable unit per store rather than one per record. */
    private final TransactionPostingService postingService;

    /** The registry the per-record timer samples are stopped against. */
    private final MeterRegistry meterRegistry;

    /** Counter for a record that carried no reject reason, which is every ordinary posted record. */
    private final Counter recordsWithoutReason;

    /**
     * Counters for a record that carried a reject reason, one per reason.
     *
     * <p>Total over every reason the estate defines, immutable, and registered once at construction, so
     * no meter is created on the record path and no map grows at run time. The outcome tag is not the
     * same for every entry: the four validation reasons are tagged as refusals, while the inert
     * account-rewrite reason is tagged as a <em>posted</em> record, because that is what such a record
     * is. Pre-registering the pairing this way puts the inertness of that reason into the shape of the
     * metric, where a dashboard will see it.
     */
    private final Map<RejectReason, Counter> recordsByReason;

    /** Timer for a record that ran the three persistence stages. */
    private final Timer postedRecordTimer;

    /** Timer for a record the validation cascade refused. */
    private final Timer rejectedRecordTimer;

    /** Timer for a record whose posting attempt raised a technical failure. */
    private final Timer failedRecordTimer;

    /**
     * Creates the processor over the posting cascade and the registry its verdict meters live in.
     *
     * <p>Both collaborators are genuinely used, and there is deliberately no third. The reader, the
     * reject destination, the chunk size and the transaction manager all belong to the owning job
     * configuration; the record layouts belong to their mappers; and the step's own timing belongs to
     * the step. Injecting any of them here would put a dependency in this constructor that this class
     * has no use for.
     *
     * <p>Every meter is registered here rather than on the record path, so the record path performs no
     * registry lookup and cannot be the place where an unbounded set of meters accumulates. The meters
     * are cumulative by nature, which is what a monitoring counter is for; they are
     * <strong>not</strong> the source of the run counters, because those must describe one execution and
     * are read from the step execution instead.
     *
     * @param  postingService the translation of the legacy posting program, whose aggregate per-record
     *                        entry point this processor calls exactly once per record
     * @param  meterRegistry  the registry the verdict counters and per-record timers are registered with
     * @throws NullPointerException if either collaborator is {@code null}
     */
    public TransactionValidationProcessor(final TransactionPostingService postingService,
            final MeterRegistry meterRegistry) {
        this.postingService = Objects.requireNonNull(postingService,
                DIAGNOSTIC_PREFIX + " posting service must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry,
                DIAGNOSTIC_PREFIX + " meter registry must not be null");

        this.recordsWithoutReason = Counter.builder(METRIC_RECORDS)
                .description("Daily-transaction records handled by the posting step, by verdict")
                .tag(TAG_OUTCOME, OUTCOME_POSTED)
                .tag(TAG_REASON, REASON_NONE)
                .register(meterRegistry);

        final Map<RejectReason, Counter> byReason = new EnumMap<>(RejectReason.class);
        for (final RejectReason reason : RejectReason.values()) {
            byReason.put(reason, Counter.builder(METRIC_RECORDS)
                    .description("Daily-transaction records handled by the posting step, by verdict")
                    .tag(TAG_OUTCOME, outcomeOf(reason))
                    // The trailer's own four-digit rendering, so a dashboard label and a reject record
                    // name the same reason the same way.
                    .tag(TAG_REASON, RejectRecordWriter.failReasonField(reason))
                    .register(meterRegistry));
        }
        this.recordsByReason = Collections.unmodifiableMap(byReason);

        this.postedRecordTimer = recordTimer(meterRegistry, OUTCOME_POSTED);
        this.rejectedRecordTimer = recordTimer(meterRegistry, OUTCOME_REJECTED);
        this.failedRecordTimer = recordTimer(meterRegistry, OUTCOME_FAILED);
    }

    /**
     * Renders the legacy program's verdict on one record and returns what the reject dataset needs from
     * it: the reject item when the cascade refused the record, and {@code null} when it posted.
     *
     * <p><strong>Returning {@code null} is the posting arm, and it is deliberate.</strong> A processor
     * that returns {@code null} tells the framework to filter the item, so the record never reaches the
     * reject writer and no reject record is emitted for it - which is precisely the legacy behaviour,
     * because the legacy program writes a reject record on one arm of its branch and not on the other.
     * The posted record itself is not lost by being filtered: it was already persisted, inside the
     * service's own per-record transaction, before this method returned. This step's writer exists to
     * emit <em>refusals</em>, and a posted record has nothing to say to it.
     *
     * <p>The cascade is delegated in one call, in place, on exactly the record the reader produced.
     * Arrival order therefore survives untouched, which matters because the overlimit test measures a
     * cycle total that earlier records of the same run have already moved.
     *
     * <p>The fail reason and its description are read out of the verdict into locals and are never held
     * as state, mirroring the legacy per-record reset that clears both before anything is looked at. The
     * routing decision is taken on <strong>whether the record was posted</strong> and never on whether it
     * carries a reason code, because a posted record may carry the inert reject 109 and must still not
     * produce a reject record. That distinction is the whole of the 109 contract.
     *
     * <p>Nothing is thrown for a refusal. Reject codes 100, 101, 102 and 103 are verdicts on content, and
     * this method returns them as an item; the step completes and reports the warning-level completion
     * code at the end. A technical failure raised by the cascade - a failed read, a failed write, an
     * abend - propagates unwrapped, having already been diagnosed by the service in the legacy order of
     * diagnostic first and abend second. It is measured on the way past, and nothing else here touches
     * it.
     *
     * @param  item the daily-transaction record to post or refuse; never {@code null}, as the
     *              framework's own contract for this method guarantees
     * @return the reject item when the record was refused, or {@code null} when it was posted
     * @throws NullPointerException  if {@code item} is {@code null}, which would mean the caller breached
     *                               the framework contract rather than that a record was absent
     * @throws IllegalStateException if a posted record breached one of the per-record fidelity properties
     *                               confirmed by {@link #requirePostedRecordFidelity(DailyTransaction,
     *                               Transaction)}
     */
    @Override
    public RejectRecordWriter.RejectedTransaction process(final DailyTransaction item) {
        Objects.requireNonNull(item, DIAGNOSTIC_PREFIX
                + " received a null daily-transaction record, which the framework contract forbids");

        final Timer.Sample sample = Timer.start(this.meterRegistry);
        // Pessimistic until the verdict is in hand: anything that escapes this method escaped as a
        // technical failure, and the sample must not be attributed to a business outcome it never had.
        Timer outcomeTimer = this.failedRecordTimer;
        try {
            final TransactionPostingService.PostingResult verdict = this.postingService.post(item);

            // The per-record fail reason and its description, as locals. Never fields: a reason held in
            // a field would decide the next record's verdict as well as this one's.
            final RejectReason reason = verdict.rejectReason();
            final int reasonCode = verdict.reasonCode();
            final String reasonDescription = verdict.reasonDescription();

            if (verdict.rejected()) {
                final RejectRecordWriter.RejectedTransaction refused =
                        new RejectRecordWriter.RejectedTransaction(item, reason);
                outcomeTimer = this.rejectedRecordTimer;
                countRecord(reason);
                LOGGER.warn("{} refused a daily-transaction record and will write a reject record"
                                + " - program={} transactionRef={} reasonCode={} reason={}",
                        DIAGNOSTIC_PREFIX, LEGACY_PROGRAM,
                        SensitiveLogRedactor.redact(item.getDalytranId()), reasonCode,
                        reasonDescription);
                return refused;
            }

            requirePostedRecordFidelity(item, verdict.postedTransaction());
            outcomeTimer = this.postedRecordTimer;
            countRecord(reason);
            reportInertReason(item, reasonCode, reasonDescription);
            // Filtered on purpose: a posted record produces no reject record. See the method contract.
            return null;
        } finally {
            sample.stop(outcomeTimer);
        }
    }

    /**
     * Reports the two run counters the legacy mainline displays and contributes its completion code.
     *
     * <p>This is the tail of the legacy mainline, which the read loop's decomposition into reader,
     * processor and writer leaves without a natural home anywhere else: the loop's counters are
     * per-execution figures, and the completion-code rule is evaluated once, after the loop, when every
     * file has been closed and both counters reported.
     *
     * <p><strong>The counters are not fields.</strong> They are read from the step execution the
     * framework already maintains for each execution, which is what makes them per-execution rather than
     * per-instance and what stops a restart inheriting a previous run's totals. The mapping is exact:
     * the legacy transaction counter is incremented once per record read, which is the step's read
     * count; the legacy reject counter is incremented once immediately before each reject record is
     * written, which is the step's write count, because the only items this processor lets through to the
     * writer are refusals. The posted count is the difference, which is the figure the legacy program
     * carries implicitly, and the framework's own filter count corroborates it. The difference is floored
     * at zero so that a step whose counters were manipulated externally cannot publish a negative total.
     *
     * <p><strong>The completion code is contributed only to a step that genuinely completed.</strong>
     * That is both faithful and necessary. Faithful, because the legacy program reaches its
     * completion-code line only after closing every file and reporting both counters - an abend never
     * gets there. Necessary, because an exit code made of digits matches none of the framework's own
     * status names and therefore outranks all of them when exit statuses are combined: contributed to a
     * failed step it would <em>replace</em> the failure verdict and hide it. So a step that did not
     * complete keeps whatever verdict it already has, and this method returns {@code null} to leave it
     * alone.
     *
     * <p>All four figures are also published into the step's execution context under the keys this class
     * declares, so the owning job configuration, a following condition-code gate and a test can read the
     * run's outcome without recomputing it. They are written here rather than during the run because
     * this listener runs before the framework persists the context, so the published values are the
     * final ones and are durable.
     *
     * <p>Calling this method more than once for the same execution is harmless: it reads the same
     * counters, publishes the same values and returns the same verdict. That matters because a step
     * builder registers a processor that implements this interface as a listener automatically, so the
     * owning job configuration need not register it explicitly - and a redundant explicit registration
     * changes nothing.
     *
     * @param  stepExecution the execution whose counters are read and whose context is published into
     * @return the warning-level exit status when a completed step refused at least one record, otherwise
     *         {@code null} to leave the step's existing verdict untouched
     * @throws NullPointerException if {@code stepExecution} is {@code null}
     */
    @Override
    public ExitStatus afterStep(final StepExecution stepExecution) {
        Objects.requireNonNull(stepExecution,
                DIAGNOSTIC_PREFIX + " step execution must not be null");

        final long transactionsProcessed = stepExecution.getReadCount();
        final long transactionsRejected = stepExecution.getWriteCount();
        final long transactionsPosted =
                Math.max(0L, transactionsProcessed - transactionsRejected);
        final int returnCode = transactionsRejected > 0L
                ? TransactionPostingService.RETURN_CODE_REJECTS_PRESENT
                : TransactionPostingService.RETURN_CODE_SUCCESS;

        final ExecutionContext context = stepExecution.getExecutionContext();
        context.putLong(EXECUTION_CONTEXT_TRANSACTIONS_PROCESSED, transactionsProcessed);
        context.putLong(EXECUTION_CONTEXT_TRANSACTIONS_POSTED, transactionsPosted);
        context.putLong(EXECUTION_CONTEXT_TRANSACTIONS_REJECTED, transactionsRejected);
        context.putInt(EXECUTION_CONTEXT_RETURN_CODE, returnCode);

        LOGGER.info("{} finished - program={} transactionsProcessed={} transactionsPosted={}"
                        + " transactionsRejected={} returnCode={}",
                DIAGNOSTIC_PREFIX, LEGACY_PROGRAM, transactionsProcessed, transactionsPosted,
                transactionsRejected, returnCode);

        if (returnCode == TransactionPostingService.RETURN_CODE_SUCCESS) {
            return null;
        }
        if (stepExecution.getStatus() != BatchStatus.COMPLETED) {
            LOGGER.warn("{} refused {} record(s) but did not complete, so its completion code is left"
                            + " as it stands - program={} batchStatus={}",
                    DIAGNOSTIC_PREFIX, transactionsRejected, LEGACY_PROGRAM,
                    stepExecution.getStatus());
            return null;
        }
        return new ExitStatus(EXIT_CODE_REJECTS_PRESENT, DIAGNOSTIC_PREFIX + " completed with "
                + transactionsRejected + " rejected record(s) out of " + transactionsProcessed
                + "; the reject dataset holds one record for each");
    }

    /**
     * Confirms the four per-record properties of a posted record that a batch adapter is the right place
     * to confirm, diagnosing nothing and throwing on the first breach.
     *
     * <p>Each of the four is a property the cascade guarantees by construction, so none of these
     * conditions is expected to arise. They are checked anyway because each would otherwise fail
     * <em>silently</em>, producing a transaction row that looks entirely plausible and only shows up a
     * full cycle later, as a byte difference in a statement or a report generated from the master:
     *
     * <ol>
     *   <li><strong>the record was actually built</strong> - a posted verdict without a transaction is a
     *       broken postcondition, not an empty result;</li>
     *   <li><strong>the identifier was carried across</strong> - the posted transaction keeps the source
     *       record's own sixteen-character business identifier, and no surrogate key is generated,
     *       requested or implied anywhere on this path. A mis-keyed posted row cannot be traced back to
     *       the record that produced it;</li>
     *   <li><strong>the origination timestamp was copied verbatim</strong> - it records when the
     *       transaction happened, which posting does not change, so it is carried across character for
     *       character and is never parsed, normalised, re-zoned or reformatted. The expiration test
     *       earlier in the cascade compares its first ten characters, so a normalisation here would also
     *       change which records are refused on a later run;</li>
     *   <li><strong>the amount is still worth what the source record was worth</strong>, at the module's
     *       single decimal scale. The comparison is numeric rather than textual so that it asserts value
     *       rather than representation, and the expected value is obtained from
     *       {@link ZonedDecimalCodec} - the one place in the module that scales anything - rather than
     *       computed here. <strong>Nothing in this class scales, rounds or rescales a value.</strong>
     *       This is the check that would catch a sign flip, a magnitude taken, a rounding mode other
     *       than truncation toward zero, or a value that had been through a binary floating-point type
     *       on the way.</li>
     * </ol>
     *
     * <p>The regenerated processing timestamp is confirmed separately, by
     * {@link #requireBatchTimestampForm(String, String)}.
     *
     * <p>Two things are deliberately <strong>not</strong> confirmed here. The posted record's fixed
     * width is the transaction record mapper's to guarantee, and that mapper is intentionally not a
     * collaborator of this class, so no offset and no width is stated in this file. And the remaining
     * copied fields - type, category, source, description, the four merchant fields and the card number -
     * belong to the cascade's own contract and to its own tests; re-asserting them here would put the
     * same expectation in two places without either place owning it.
     *
     * <p>Messages name the field and the transaction identifier and nothing else. The amount is
     * financial data and the card number is a primary account number, so neither appears in a message
     * even on a failure path, where a message is most likely to be copied somewhere it should not be.
     *
     * <p>Package-visible rather than private so that each property can be exercised directly by a test in
     * this package. The owning job configuration lives in another package and so gains no new entry
     * point.
     *
     * @param  source the record that was posted
     * @param  posted the transaction the cascade built from it
     * @throws IllegalStateException if any of the four properties does not hold
     */
    static void requirePostedRecordFidelity(final DailyTransaction source, final Transaction posted) {
        final String transactionId = source.getDalytranId();
        if (posted == null) {
            throw new IllegalStateException(postedRecordDiagnostic(transactionId)
                    + " without building a transaction record");
        }
        requireCarriedVerbatim("TRAN-ID", source.getDalytranId(), posted.getTranId(), transactionId);
        requireCarriedVerbatim("TRAN-ORIG-TS", source.getDalytranOrigTs(), posted.getTranOrigTs(),
                transactionId);
        requireAmountCarried(source.getDalytranAmt(), posted.getTranAmt(), transactionId);
        requireBatchTimestampForm(posted.getTranProcTs(), transactionId);
    }

    /**
     * Confirms that a regenerated processing timestamp is in the batch form, at the batch width.
     *
     * <p>Two independent things are checked, and the second is the one that earns its keep.
     *
     * <p>The width is measured on the <strong>encoded bytes</strong> and compared against the length the
     * batch timestamp field holds. A character count is not a width - a single character outside the
     * seven-bit range would satisfy one and breach the other - so no character count stands in for it.
     *
     * <p>The form is then compared against a template rendered by the batch timestamp helper this
     * package owns: at every position where the template holds a separator the candidate must hold the
     * same separator, and at every position where the template holds a digit the candidate must hold an
     * ASCII digit. The expectations are therefore <strong>derived from the single authority for the
     * format</strong> and not typed out here, so the check cannot drift away from what the helper
     * produces.
     *
     * <p>The reason a width check alone is not enough: the online tier of this module builds a
     * twenty-six character timestamp of its own, and it is <strong>a different format</strong> - a space
     * where the batch form has its third separator, colons where the batch form has dots, and a
     * six-digit fraction where the batch form has two digits and a fixed tail. Emitting the online form
     * from a batch path would produce a value of exactly the right width whose bytes differ, and the
     * only symptom would be a failed byte comparison against an expected dataset, one job away from the
     * code that caused it. The positional comparison catches it immediately.
     *
     * <p>Digits are recognised by their ASCII range rather than by a locale-aware or Unicode-aware
     * digit test. Under a locale whose default numbering system is not Western Arabic, a locale-aware
     * test would accept characters that fall outside the seven-bit range the dataset is encoded in, and
     * the value would become unreadable in a way the width check could not reveal.
     *
     * <p>Package-visible so that a test in this package can present a malformed value directly. The
     * value is never regenerated here: generating the processing timestamp belongs to the cascade, which
     * uses this package's helper for it, and a second generator would be a second format.
     *
     * @param  candidate     the processing timestamp the cascade regenerated
     * @param  transactionId the record's identifier, used only to make the message locatable
     * @throws IllegalStateException if the value is absent, is not the batch width, or is not in the
     *                               batch form
     */
    static void requireBatchTimestampForm(final String candidate, final String transactionId) {
        if (candidate == null) {
            throw new IllegalStateException(postedRecordDiagnostic(transactionId)
                    + " without a processing timestamp; TRAN-PROC-TS is regenerated on every posted"
                    + " record and is never left absent");
        }
        final int encodedWidth = candidate.getBytes(StandardCharsets.US_ASCII).length;
        if (encodedWidth != AbstractCobolStep.BATCH_TIMESTAMP_LENGTH) {
            throw new IllegalStateException(postedRecordDiagnostic(transactionId)
                    + " with a TRAN-PROC-TS of " + encodedWidth + " encoded bytes, but the batch"
                    + " processing timestamp is fixed at " + AbstractCobolStep.BATCH_TIMESTAMP_LENGTH);
        }
        if (candidate.length() != BATCH_TIMESTAMP_TEMPLATE.length()) {
            throw new IllegalStateException(postedRecordDiagnostic(transactionId)
                    + " with a TRAN-PROC-TS of " + candidate.length() + " characters, but the batch"
                    + " processing timestamp holds exactly " + BATCH_TIMESTAMP_TEMPLATE.length());
        }
        for (int position = 0; position < BATCH_TIMESTAMP_TEMPLATE.length(); position++) {
            final char templated = BATCH_TIMESTAMP_TEMPLATE.charAt(position);
            final char actual = candidate.charAt(position);
            final boolean acceptable = isAsciiDigit(templated)
                    ? isAsciiDigit(actual)
                    : templated == actual;
            if (!acceptable) {
                throw new IllegalStateException(postedRecordDiagnostic(transactionId)
                        + " with a TRAN-PROC-TS that is not in the batch form at"
                        + " position " + position + "; the batch form has "
                        + (isAsciiDigit(templated) ? "a digit" : "'" + templated + "'")
                        + " there and the value has " + FailureDiagnostics.printableForm(actual)
                        + ". The online timestamp form is the same width as the batch form and must"
                        + " never be substituted for it");
            }
        }
    }

    /**
     * Confirms that a fixed-width character field was carried from the source record to the posted
     * transaction without alteration.
     *
     * <p>Compared for equality including absence, and never trimmed, padded, case folded or normalised
     * before comparing: the values are fixed-width legacy fields, so their trailing spaces are part of
     * what was read and part of what must be written.
     *
     * @param  field         the legacy field name, used only in the message
     * @param  expected      the value the source record holds
     * @param  actual        the value the posted transaction holds
     * @param  transactionId the record's identifier, used only to make the message locatable
     * @throws IllegalStateException if the two differ
     */
    private static void requireCarriedVerbatim(final String field, final String expected,
            final String actual, final String transactionId) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException(postedRecordDiagnostic(transactionId)
                    + " with a " + field + " that differs from the source record; this field is copied"
                    + " verbatim and is never normalised, reformatted or regenerated");
        }
    }

    /**
     * Confirms that the posted amount is still worth exactly what the source record was worth.
     *
     * <p>The expected value is the source amount brought to the module's monetary scale by
     * {@link ZonedDecimalCodec}, which truncates toward zero. It is obtained from the codec, and not
     * computed here, because the codec is the module's only scaling authority and the whole point of the
     * check is that the posting path agreed with it.
     *
     * <p>The comparison is numeric, so a value that is equal but differently scaled passes and a value
     * that is textually similar but differently signed fails. Neither operand appears in the message: an
     * amount is financial data.
     *
     * @param  sourceAmount  the amount the source record holds
     * @param  postedAmount  the amount the posted transaction holds
     * @param  transactionId the record's identifier, used only to make the message locatable
     * @throws IllegalStateException if either amount is absent, or if the two differ in value
     */
    private static void requireAmountCarried(final BigDecimal sourceAmount,
            final BigDecimal postedAmount, final String transactionId) {
        if (sourceAmount == null || postedAmount == null) {
            throw new IllegalStateException(postedRecordDiagnostic(transactionId)
                    + " with an absent amount; a fixed-width amount field is never absent");
        }
        if (ZonedDecimalCodec.toMonetaryScale(sourceAmount).compareTo(postedAmount) != 0) {
            throw new IllegalStateException(postedRecordDiagnostic(transactionId)
                    + " with a TRAN-AMT that is not worth what the source DALYTRAN-AMT was worth; the"
                    + " amount is carried across unchanged and is scaled only by the module's decimal"
                    + " codec, which truncates toward zero");
        }
    }

    /**
     * Reports that a posted record carried the inert account-rewrite reason, and that no reject record
     * was emitted for it.
     *
     * <p>Emitted at warning level because the condition is genuinely anomalous - the account the record
     * was validated against was gone by the time it was rewritten - while the record was nevertheless
     * posted and its transaction written. The cascade reports the condition; this reports the routing
     * consequence, which is the part that surprises a reader of the reject dataset, because the dataset
     * will contain nothing about this record at all.
     *
     * <p>Nothing else happens. Turning this into an exception, a rollback, a reject item or a skip would
     * each be more idiomatic and each would change what the run produces.
     *
     * @param item              the record that was posted
     * @param reasonCode        the reason code the verdict carried, zero on an ordinary posted record
     * @param reasonDescription the description the verdict carried
     */
    private static void reportInertReason(final DailyTransaction item, final int reasonCode,
            final String reasonDescription) {
        if (reasonCode == TransactionPostingService.NO_REJECT_REASON_CODE) {
            return;
        }
        LOGGER.warn("{} posted a daily-transaction record that carried reason code {} and deliberately"
                        + " wrote no reject record for it, because the reason was raised after the"
                        + " decision to post - program={} transactionRef={} reason={}",
                DIAGNOSTIC_PREFIX, reasonCode, LEGACY_PROGRAM,
                SensitiveLogRedactor.redact(item.getDalytranId()), reasonDescription);
    }

    /**
     * Counts one record against the meter for the reason it carried, or against the no-reason meter.
     *
     * <p>The meters are pre-registered and total over every reason the estate defines, so this performs a
     * lookup and an increment and creates nothing.
     *
     * @param reason the reason the verdict carried, or {@code null} when it carried none
     */
    private void countRecord(final RejectReason reason) {
        if (reason == null) {
            this.recordsWithoutReason.increment();
            return;
        }
        this.recordsByReason.get(reason).increment();
    }

    /**
     * The outcome a record carrying a given reason ends up with.
     *
     * <p>Every reason but one means the record was refused. The account-rewrite reason means it was
     * <strong>posted</strong>, because it is raised after the decision to post and changes nothing, so
     * its meter is tagged accordingly. Encoding that here keeps the inertness of that reason visible in
     * the shape of the metric rather than only in prose.
     *
     * @param  reason the reason to classify
     * @return the outcome tag value for that reason
     */
    private static String outcomeOf(final RejectReason reason) {
        return reason == RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE ? OUTCOME_POSTED : OUTCOME_REJECTED;
    }

    /**
     * Registers the per-record timer for one outcome.
     *
     * <p>Per-record timing complements the step's own timer and does not replace it: the step timer
     * measures reading, processing and writing together, while this measures only the cascade call, which
     * is what a records-per-second figure for the posting path is actually made of. Nothing here declares
     * a target, a threshold or a budget for that figure.
     *
     * @param  meterRegistry the registry to register with
     * @param  outcome       the outcome tag value
     * @return the registered timer
     */
    private static Timer recordTimer(final MeterRegistry meterRegistry, final String outcome) {
        return Timer.builder(METRIC_RECORD_TIMER)
                .description("Time to post or refuse one daily-transaction record")
                .tag(TAG_OUTCOME, outcome)
                .register(meterRegistry);
    }

    /**
     * Whether a character is one of the ten ASCII digits.
     *
     * <p>Used instead of a locale-aware or Unicode-aware digit test, which under a locale whose default
     * numbering system is not Western Arabic would accept characters outside the seven-bit range the
     * datasets on this path are encoded in.
     *
     * @param  character the character to test
     * @return {@code true} when the character is an ASCII digit
     */
    private static boolean isAsciiDigit(final char character) {
        return character >= ASCII_ZERO && character <= ASCII_NINE;
    }

    /**
     * Opens a fidelity diagnostic, naming the record by a redacted reference rather than by its
     * identifier.
     *
     * <p>Every fidelity message on this path is composed through this one method, so the identifier is
     * redacted once and a message added later cannot reintroduce it by forgetting to.
     *
     * <p>The identifier is <strong>read out of the fixed-width daily-transaction image</strong>, so it
     * is external input on two counts: it identifies a cardholder's transaction, and its bytes are
     * whatever the record held. {@link SensitiveLogRedactor#redact(String)} answers both counts at once
     * - it withholds the value and returns a stable per-value correlation reference whose token is
     * lower-case ASCII hexadecimal, so a record carrying control bytes, a delimiter or a line terminator
     * cannot inject any of them into a log record or an exception message through this route. The
     * reference is stable within a run, so the several messages a single failing record produces can
     * still be tied to one another, which is what the identifier was in the message for.
     *
     * @param  transactionId the record's identifier, as read from the image, never rendered
     * @return the opening clause of the diagnostic, carrying a redacted reference
     */
    // See docs/decision-log.md entry DL-177 for why the identifier is withheld here and what the
    // reference that replaces it retains.
    private static String postedRecordDiagnostic(final String transactionId) {
        return DIAGNOSTIC_PREFIX + " posted transaction "
                + SensitiveLogRedactor.redact(transactionId);
    }

}
