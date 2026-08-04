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

import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.TransactionCategory;
import com.carddemo.domain.TransactionType;
import com.carddemo.service.TransactionReportService;
import com.carddemo.service.TransactionReportService.TransactionReportResult;
import com.carddemo.util.ReportLineFormatter;
import com.carddemo.util.ZonedDecimalCodec;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;

/**
 * Produces the transaction detail report of the legacy batch program {@code CBTRN03C} for one
 * date-parameter card, and refuses to pass on a report whose records are not exactly
 * {@value #REPORT_RECORD_LENGTH} encoded bytes wide.
 *
 * <h2>Provenance</h2>
 *
 * <p>Translated from the legacy estate at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19 - the trailer comment carried by every
 * COBOL and JCL member. The authorities are the report program {@code app/cbl/CBTRN03C.cbl}
 * (649 lines), the report layout copybook {@code app/cpy/CVTRA07Y.cpy}, the job
 * {@code app/jcl/TRANREPT.jcl} and the cataloged procedure {@code app/proc/TRANREPT.prc}. They are
 * <strong>cited, never transcribed</strong>, and nothing on this path reads the legacy tree at run
 * time. A census of the report program must be taken over both extension casings the estate uses -
 * {@code app/cbl/**.cbl} and {@code app/cbl/**.CBL} - because a lowercase-only pattern silently
 * drops working members elsewhere in the same directory.
 *
 * <h2>What one item is</h2>
 *
 * <p>One item is <strong>one date-parameter card</strong>, and one card produces one whole report.
 * That is the legacy program's own shape: it reads a single record from its date-parameter dataset,
 * takes the reporting bounds from it, and then walks the ordered transaction input once from
 * beginning to end. The card is accepted in either of the two widths the estate uses - the
 * {@code ReportLineFormatter#DATE_PARAMETER_STRUCTURED_WIDTH}-byte structured record or the
 * {@code ReportLineFormatter#DATE_PARAMETER_CARD_WIDTH}-byte card image whose leading bytes are
 * byte-identical to the in-stream card the online report-request program submits.
 *
 * <p>The card's significant prefix is ten start-date characters, one space separator and ten
 * end-date characters. <strong>The remaining bytes of the card image are not a further
 * parameter</strong>: they are the trailing run of an eighty-column card and carry no meaning, so
 * nothing here reads past the end date.
 *
 * <p>An absent date-parameter dataset is represented by the parent job's reader producing
 * <em>no item at all</em>, which is why this method never accepts a null card. The legacy read
 * reports end of file for an empty dataset, its driving loop never iterates and the report is
 * empty; a step whose reader yields nothing reaches this processor zero times and writes nothing,
 * which is the same outcome expressed the way a chunk-oriented step expresses it.
 *
 * <h2>Why this class delegates instead of driving the report itself</h2>
 *
 * <p>{@link TransactionReportService} is the module's translation of the report program: all
 * twenty-seven paragraph units of its procedure division, the inclusive character-date guard, the
 * card-number break, the account totals, the page break, the four-record header block, the
 * accumulation chain and the end-of-input flush. Every one of those lives there, in state created
 * fresh for each invocation, and the report's content and its order are therefore decided in
 * exactly one place in the module.
 *
 * <p>Re-driving the loop here - reading transactions one at a time, holding the counters, resolving
 * the lookups and emitting the lines - was considered and rejected, on three grounds that are worth
 * recording because the alternative looks attractive from a distance:
 *
 * <ul>
 *   <li>it would put a <strong>second</strong> implementation of one legacy program in the module,
 *       and two implementations of one decision are how the two eventually disagree - the same
 *       reasoning {@link CombineTransactionsProcessor} records for declining to own its job's
 *       ordering;</li>
 *   <li>it could not resolve the report's three lookups. The detail line needs the account
 *       identifier from {@link CardCrossReference}, the description from {@link TransactionType} and
 *       the description from {@link TransactionCategory}, and {@link Transaction} carries none of
 *       them and no association to them, so a per-record driver here would have to take three
 *       repositories that belong to the service;</li>
 *   <li>and it would need mutable per-execution fields - the line counter, the three accumulators,
 *       the current card number and the first-time flag - which is precisely the state this class is
 *       required not to hold.</li>
 * </ul>
 *
 * <p>What is left is genuinely per-item, and it is what this class does: fail fast on a card that
 * is not a card, delegate, and then <strong>prove</strong> the contracted record width of every
 * record the delegate produced before any of them can reach a destination.
 *
 * <h2>What the parent job configuration supplies</h2>
 *
 * <p>This class carries no framework annotation, so the job configuration declares it as a bean and
 * hands it the two collaborators its constructor names. Around it the configuration owns three
 * things this class deliberately does not: a reader that yields the date-parameter card - one item,
 * built from the job's validated start and end parameters - the ordered range query that replaces the
 * legacy external sort, and a writer that expands {@code TransactionReportResult#reportLines()} onto
 * the fixed-length destination. It also owns the step's own metering and its failure semantics.
 * <strong>The item type is the card, not a transaction</strong>, which is worth stating plainly
 * because a reader wired to the transaction stream instead would compile and then generate one
 * complete report per transaction.
 *
 * <h2>The accumulation chain this class must not disturb</h2>
 *
 * <p>Stated here so that a reader of the batch tier can verify it without opening the service, and
 * so that nobody adds a "convenient" total to this stage:
 *
 * <ol>
 *   <li>a qualifying detail adds its amount to the <strong>page total and the account total, and to
 *       nothing else</strong>;</li>
 *   <li>the page total is folded into the <strong>grand total only when a page total is
 *       emitted</strong>, and is zeroed immediately afterwards. That is the single place the grand
 *       total grows;</li>
 *   <li>emitting an account total zeroes the <strong>account total alone</strong> - an account total
 *       never reaches the grand total, because doing so would count every amount of the report
 *       twice;</li>
 *   <li>the end of the input adds nothing new of its own beyond what the legacy read left in its
 *       record area, then emits the final page total and the grand total.</li>
 * </ol>
 *
 * <p>The report program contains <strong>zero computation statements</strong>. That is a verified
 * property of the authority rather than a style observation, and it is why neither the service nor
 * this class introduces a derived figure of any kind: no percentage, no average, no recomputation of
 * a total from the rows and no algebraic rearrangement. Accumulation of already-scaled amounts is
 * the only arithmetic in the feature, scaling is {@link ZonedDecimalCodec}'s single responsibility,
 * and <strong>this class performs no arithmetic at all</strong> - it holds no accumulator, calls no
 * scaling operation and declares no floating-point type.
 *
 * <h2>Width fidelity is proved, not assumed</h2>
 *
 * <p>The legacy report dataset is fixed-length with a record length of
 * {@value #REPORT_RECORD_LENGTH}, so a report record is a byte contract and not a line of text.
 * Every record handed on from here is therefore measured on its <strong>encoded byte array</strong>,
 * never on a character count: a character count is not a width, and a single character outside the
 * seven-bit range satisfies one while breaching the other.
 *
 * <p>Purity is proved before the measurement is taken, for the reason {@link RejectRecordWriter}
 * records for the reject dataset: an encoder asked to emit a character it cannot represent
 * substitutes a single replacement byte for it, which keeps the byte count right and makes the
 * content wrong. Rejecting such a character is the only way the width figure can be trusted.
 *
 * <p>This is a postcondition on a delegate rather than an expected runtime condition -
 * {@link ReportLineFormatter} builds every group at a declared width and checks it - and it is worth
 * its cost because the failure it catches is otherwise silent: a mis-sized record loads into a
 * fixed-length dataset without complaint and breaks the byte fidelity of a report that is compared
 * against a golden file one job away from the code that caused it.
 *
 * <p>No offset, no filler, no separator, no description truncation width and no amount mask is
 * restated here. All of them belong to {@link ReportLineFormatter}, which owns the layout's seven
 * groups, the two literal separators of the detail line, the dot-fill runs of the three totals, the
 * separate fifteen-character detail and total amount masks - the detail mask carries a space where
 * the total mask carries a plus - and the rule that a value of exactly zero blanks the whole amount
 * field rather than rendering a zero. External record framing belongs to the parent's writer:
 * nothing is added inside a record here.
 *
 * <h2>Ordering, filtering and the sort specification that stays in the job</h2>
 *
 * <p>Records arrive in the order the parent job supplies and leave in that order. The legacy job
 * sorts its input on <strong>one</strong> key - the card number, sixteen bytes at one-based position
 * 263, ascending, typed as <em>zoned decimal</em> in that job's own sort specification, while the
 * processing-date field the same specification filters on is ten bytes at one-based position 305
 * typed as <em>character</em> - and declares no secondary key. This class therefore
 * <strong>declares and imports no comparator,
 * no comparator registry, no comparison helper and no card-number parser of any kind</strong>, and
 * adds no secondary transaction-identifier key. That is not fastidiousness: another job in the same
 * estate types the same physical field differently, so a comparator shared between the two would
 * hand one of them the other's semantics without anything failing to compile. The specification
 * belongs to the job configuration that owns it, together with the query ordering that replaces the
 * external sort.
 *
 * <p>The reporting range is <strong>inclusive at both ends</strong> and is applied as a character
 * comparison of the ten-character prefix of the twenty-six-character processing timestamp: at or
 * above the start date <em>and</em> at or below the end date. It works as characters because the
 * format is fixed ISO with hyphens, so lexical order and calendar order coincide. No temporal type
 * is constructed anywhere on this path - nothing is parsed to a date, no punctuation is normalised
 * and no zone is applied - and the comparison is stated in exactly one place, the service and the
 * range query it issues. Restating it here as a second filter would give the feature two predicates
 * to keep in step.
 *
 * <p>{@link #process(String)} <strong>never returns null</strong>. A null return instructs the batch
 * framework to filter the item, and since one item is the whole report, a filtered item would be a
 * report the legacy job produced and this one silently did not. The method has exactly two
 * outcomes: the result, or a thrown exception.
 *
 * <h2>State, instrumentation, diagnostics and sequencing</h2>
 *
 * <p>The class holds <strong>no mutable field</strong>: no counter, no total, no current card
 * number, no line counter and no accumulated list. Every value of a run lives in the holder the
 * service creates for that run, so this instance is safe to register once and reuse, and it cannot
 * become the place where a stale total from one execution leaks into the next.
 *
 * <p>Instrumentation is coordinated rather than duplicated. The whole legacy program lifecycle is
 * timed where a step is timed - by {@link AbstractCobolStep} for a tasklet step and by the job
 * configuration's own step-level meters otherwise - and the timer here does not replace that. It
 * measures the report generation and its width proof, which for this feature is one item and
 * therefore very nearly the whole step, and it exists so that a failing generation is visible as a
 * separate outcome on the metrics endpoint. Nothing here fixes a chunk size, a skip or retry limit,
 * a timeout, a backoff, a thread count, a connection figure or a throughput target.
 *
 * <p>Diagnostics go through the logging facade only. No record content is logged: the report carries
 * primary account numbers, merchant fields and monetary amounts, and none of them belongs in a log
 * line - which is why the summary event carries counts and the reporting range, and not the grand
 * total. Technical failures are terminal and propagate: the service's diagnose-then-abend ordering
 * already names the failing operation and its raw two-byte file status before it raises, so nothing
 * is caught and re-wrapped here, and end of input is a normal outcome rather than a failure.
 *
 * <p>Execution is strictly sequential. No task executor, partitioning, parallel stream or
 * asynchronous emission may be introduced on this path: the report's account grouping, its page
 * breaks and its accumulation chain are all order-dependent, and interleaving them destroys the
 * ordering the report is defined by.
 *
 * <h2>Two legacy naming findings, recorded and not reproduced</h2>
 *
 * <p>The report job labels two consecutive steps with the <strong>same</strong> name, and the
 * cataloged procedure declares itself under the name of a <em>different</em> procedure member of the
 * estate while its own first step invokes that name. Both are source defects. They are recorded here
 * because the constants below publish the legacy names and a reader will notice that two of them are
 * equal; the target generates distinct step names and neither defect is propagated.
 *
 * @see TransactionReportService
 * @see ReportLineFormatter
 * @see AbstractCobolStep
 * @since 1.0.0
 */
public final class TransactionReportProcessor
        implements ItemProcessor<String, TransactionReportResult> {

    /**
     * Diagnostics for this stage.
     *
     * <p>Used for the per-run summary and for the two postcondition breaches, and for nothing else.
     * No report record and no field of a transaction is ever written to a log, in whole or in part.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionReportProcessor.class);

    /**
     * Name of the legacy job whose report step this processor's job replaces.
     *
     * <p>Published so that the job configuration, the traceability matrix and the tests can name the
     * same origin without restating a literal in three places. It is an identifier for a job, never
     * a path or a dataset name, and nothing resolves a resource from it.
     */
    public static final String LEGACY_JOB = "TRANREPT";

    /** The legacy batch program this stage's report content comes from. */
    public static final String LEGACY_PROGRAM = "CBTRN03C";

    /**
     * Name of the legacy step that unloaded the transaction master before the report was produced.
     *
     * <p>Equal to {@link #LEGACY_SORT_STEP} <strong>on purpose</strong>: the job labels both of its
     * first two steps identically, which is a source defect. Publishing both names records the
     * defect where a reader of the batch tier will meet it, and the target's own step names are
     * distinct.
     */
    public static final String LEGACY_UNLOAD_STEP = "STEP05R";

    /**
     * Name of the legacy step that filtered the unloaded transactions to the reporting range and
     * ordered them by card number - work the target expresses as one ordered range query owned by
     * the job configuration.
     */
    public static final String LEGACY_SORT_STEP = "STEP05R";

    /** Name of the legacy step that ran the report program itself, which is this stage. */
    public static final String LEGACY_REPORT_STEP = "STEP10R";

    /**
     * Name the report procedure member declares itself under.
     *
     * <p>A second source defect: the member is named for the report while the procedure it declares
     * carries the name of the unrelated copy procedure of the estate, which its own first step then
     * invokes. Recorded, not reproduced.
     */
    public static final String LEGACY_PROCEDURE = "REPROC";

    /** Data-definition name of the sequential date-parameter input this stage's item comes from. */
    public static final String LEGACY_DD_DATEPARM = "DATEPARM";

    /** Data-definition name of the fixed-length report output the parent's writer emits to. */
    public static final String LEGACY_DD_TRANREPT = "TRANREPT";

    /**
     * Fixed width, in encoded US-ASCII bytes, of every report record this stage hands on.
     *
     * <p>Taken from {@link ReportLineFormatter#REPORT_RECORD_WIDTH} rather than restated, because
     * the layout has exactly one authority. The legacy report dataset declares the same figure as
     * its record length, which is what makes the width a contract rather than a convention.
     */
    public static final int REPORT_RECORD_LENGTH = ReportLineFormatter.REPORT_RECORD_WIDTH;

    /**
     * Highest code point US-ASCII can represent.
     *
     * <p>Used to prove purity before a width is measured. An encoder asked to emit a character above
     * this substitutes a single replacement byte for it, which keeps the byte count right and makes
     * the content wrong; rejecting the character instead is the only way the width check can be
     * trusted.
     */
    private static final int HIGHEST_ASCII_CODE_POINT = 0x7F;

    /**
     * Name of the timer measuring one report generation together with its width proof.
     *
     * <p>Narrower than the batch step template's lifecycle timer and not a replacement for it: a
     * migration baseline is stated in whole legacy program lifecycles, and this measures the
     * delegated generation alone.
     */
    private static final String METRIC_GENERATION = "carddemo.batch.report.generation";

    /** Name of the counter accumulating report records handed on, the metric form of a line count. */
    private static final String METRIC_RECORDS = "carddemo.batch.report.records";

    /** Tag key naming the legacy dataset the meter refers to. */
    private static final String TAG_RESOURCE = "resource";

    /** Tag key separating a generation that completed from one that failed. */
    private static final String TAG_OUTCOME = "outcome";

    /** Tag value for a report that was generated and passed its width proof in full. */
    private static final String OUTCOME_COMPLETED = "COMPLETED";

    /** Tag value for a generation or width proof that failed, which is always propagated. */
    private static final String OUTCOME_FAILED = "FAILED";

    /** The report generator: the module's single translation of the legacy report program. */
    private final TransactionReportService reportService;

    /** Registry the generation timer is recorded on, resolved once per stopped sample. */
    private final MeterRegistry meterRegistry;

    /** Counter of report records handed on, registered once so no meter is built per item. */
    private final Counter reportRecordCounter;

    /**
     * Constructor injection of both collaborators, which is what replaces the legacy program's
     * static file and subprogram linkage. No setter and no field injection exists, so a partially
     * built instance is not representable, and the instance that results holds no mutable state.
     *
     * <p>Deliberately <strong>not</strong> injected: the three lookup repositories, the ordered
     * range query and the report layout. The first two belong to {@link TransactionReportService},
     * which resolves the account identifier and the two descriptions the detail line needs, and the
     * third belongs to {@link ReportLineFormatter}, whose members are static because a layout has no
     * per-instance state to carry. Taking any of them here would give the module two owners for one
     * decision.
     *
     * @param reportService the report generator this stage delegates a whole report to
     * @param meterRegistry the registry the generation timer and the record counter are recorded on
     * @throws NullPointerException if either collaborator is absent
     */
    public TransactionReportProcessor(final TransactionReportService reportService,
            final MeterRegistry meterRegistry) {
        this.reportService = Objects.requireNonNull(reportService,
                LEGACY_JOB + " " + LEGACY_REPORT_STEP + " requires a report service");
        this.meterRegistry = Objects.requireNonNull(meterRegistry,
                LEGACY_JOB + " " + LEGACY_REPORT_STEP + " requires a meter registry");
        this.reportRecordCounter = Counter.builder(METRIC_RECORDS)
                .description("Report records handed on from the transaction detail report stage")
                .baseUnit("records")
                .tag(TAG_RESOURCE, LEGACY_DD_TRANREPT)
                .register(meterRegistry);
    }

    /**
     * Generates the whole transaction detail report for one date-parameter card and hands it on with
     * every record proved to be exactly {@value #REPORT_RECORD_LENGTH} encoded US-ASCII bytes wide.
     *
     * <p>Four things happen, in this order, and the order is deliberate:
     *
     * <ol>
     *   <li>a null card is rejected. One item is one whole report, so a null here would mean the
     *       framework contract had been breached rather than that a parameter dataset was empty - an
     *       empty dataset is a reader that yields no item;</li>
     *   <li>the two ten-character bounds are read out of the card by
     *       {@link ReportLineFormatter#readStartDate(String)} and
     *       {@link ReportLineFormatter#readEndDate(String)}. Nothing is sliced, trimmed, padded or
     *       truncated here: that reader accepts only the two legal card widths and only printable
     *       US-ASCII, so a malformed card fails <strong>with the width it measured</strong> before
     *       any work is done, instead of being quietly shortened into something that parses. The
     *       bounds stay ten-character strings - no temporal type is constructed, no punctuation is
     *       normalised and no zone is applied - and they are used for the failure message and the
     *       summary event only. <strong>They never build a report record</strong>; the record content
     *       is produced from the same card by the delegate, so the card has exactly one reader of
     *       record. Validating a bound as a job parameter, before the step runs, belongs to the job
     *       configuration's parameter validator and is not repeated here;</li>
     *   <li>the report is generated by {@link TransactionReportService}, which applies the inclusive
     *       character-date range, walks the card-ordered input once, breaks on a change of card
     *       number to emit and zero the prior account total, resolves the account identifier and the
     *       two descriptions, emits the four-record header block on the first qualifying detail and
     *       after every page break, breaks the page when the line counter modulo
     *       {@link ReportLineFormatter#PAGE_SIZE} is zero, keeps the accumulation chain intact and
     *       flushes the final page total and the grand total at the end of the input. That page size
     *       is a <strong>report-layout fact</strong>, not a tuning value: it decides where every rule
     *       line and every repeated header lands, so changing it would change the report rather than
     *       its performance, and it is never to be confused with a chunk size;</li>
     *   <li>every record of the result is proved pure US-ASCII and then measured on its encoded byte
     *       array. A breach of either is a defect in the layout authority rather than bad input, so
     *       it is diagnosed and raised rather than skipped.</li>
     * </ol>
     *
     * <p>An empty report is a legitimate outcome and is <strong>not</strong> converted into a
     * failure: a range that no transaction falls into still produces the totals the legacy program
     * produces for it.
     *
     * <p>The generation and the width proof are timed together on one sample, tagged with the
     * outcome, so a failed generation is visible on the metrics endpoint as its own series. That
     * timer supplements the whole-step timing owned by {@link AbstractCobolStep} and the job
     * configuration and does not stand in for it.
     *
     * <p>Nothing is caught for translation. The delegate diagnoses a technical failure - naming the
     * failing operation, the resource and the raw two-byte file status - and abends after doing so,
     * and that ordering must not be disturbed by an intervening handler here. The only exception
     * handling on this path re-tags the timer and rethrows the very same exception.
     *
     * @param  dateParameterCard the date-parameter card, in either the structured or the
     *                           eighty-column width; never {@code null}, which the framework's own
     *                           contract for this method guarantees
     * @return the ordered report content and the run's observations; never {@code null}, because a
     *         null return would filter the only item and silently produce no report
     * @throws NullPointerException     if {@code dateParameterCard} is {@code null}, or if the
     *                                  delegate reports no result at all
     * @throws IllegalArgumentException propagated from the card reader if the card measures neither
     *                                  legal width or is not printable US-ASCII
     * @throws IllegalStateException    if any report record is not exactly
     *                                  {@value #REPORT_RECORD_LENGTH} encoded bytes wide, or carries
     *                                  a character US-ASCII cannot represent
     */
    @Override
    public TransactionReportResult process(final String dateParameterCard) {
        Objects.requireNonNull(dateParameterCard, LEGACY_JOB + " " + LEGACY_REPORT_STEP
                + " received a null date-parameter card; an absent " + LEGACY_DD_DATEPARM
                + " dataset is expressed by the reader yielding no item at all");

        final String startDate = ReportLineFormatter.readStartDate(dateParameterCard);
        final String endDate = ReportLineFormatter.readEndDate(dateParameterCard);

        final Timer.Sample sample = Timer.start(this.meterRegistry);
        final TransactionReportResult result;
        try {
            result = Objects.requireNonNull(
                    this.reportService.generateReportFromDateParameterCard(dateParameterCard),
                    () -> LEGACY_PROGRAM + " reported no result for the reporting range "
                            + startDate + " to " + endDate);
            // The width proof is inside the timed region and inside this guard on purpose: it is
            // part of what this stage promises, so a report that fails it must be reported as a
            // failed generation rather than as a completed one.
            requireReportRecordWidths(result.reportLines());
        } catch (RuntimeException failure) {
            stopSample(sample, OUTCOME_FAILED);
            throw failure;
        }
        stopSample(sample, OUTCOME_COMPLETED);

        final List<String> reportLines = result.reportLines();
        this.reportRecordCounter.increment(reportLines.size());
        // Counts and the reporting range only. The grand total is a monetary value and every detail
        // record carries a primary account number and merchant fields, so none of that is logged.
        LOGGER.info("{} {} ({}) produced {} record(s) of {} bytes for the range {} to {}:"
                        + " {} page total(s), {} account total(s), line counter {}",
                LEGACY_JOB, LEGACY_REPORT_STEP, LEGACY_PROGRAM, reportLines.size(),
                REPORT_RECORD_LENGTH, startDate, endDate, result.pageCount(),
                result.accountBreakCount(), result.lineCount());
        return result;
    }

    /**
     * Proves the contracted width of every record of a generated report.
     *
     * <p>Records are checked in emission order and the first breach stops the run, so the diagnostic
     * names the earliest record that is wrong rather than the last. The list itself cannot hold a
     * null element - the result record seals it with an immutable copy, which rejects one - so the
     * per-record null check exists only to keep this helper safe if it is ever called with a list
     * assembled elsewhere.
     *
     * @param  reportLines the report's records in emission order
     * @throws IllegalStateException if any record breaches purity or width
     */
    private static void requireReportRecordWidths(final List<String> reportLines) {
        for (int index = 0; index < reportLines.size(); index++) {
            requireReportRecordWidth(reportLines.get(index), index);
        }
    }

    /**
     * Proves that one report record is representable in US-ASCII and is exactly
     * {@value #REPORT_RECORD_LENGTH} encoded bytes wide.
     *
     * <p>Purity is proved first and the width is then taken from the <strong>encoded byte
     * array</strong>. Neither step is redundant and their order is not interchangeable: a character
     * the charset cannot represent is replaced by a single substitution byte, so a width measured
     * without proving purity first can be satisfied by a record whose content has already been
     * corrupted. The character scan walks the record's characters and reports the position of the
     * offending one; it never reports the character itself, because a report record carries account
     * numbers, merchant fields and amounts.
     *
     * @param  record the candidate report record
     * @param  index  the record's position in the report, named in any diagnostic
     * @throws NullPointerException  if {@code record} is {@code null}
     * @throws IllegalStateException if the record is impure or mis-sized
     */
    private static void requireReportRecordWidth(final String record, final int index) {
        Objects.requireNonNull(record, () -> LEGACY_JOB + " " + LEGACY_REPORT_STEP
                + ": report record " + index + " is absent, but a fixed-length dataset has no"
                + " representation for an absent record");

        final char[] characters = record.toCharArray();
        for (int position = 0; position < characters.length; position++) {
            if (characters[position] > HIGHEST_ASCII_CODE_POINT) {
                LOGGER.error("{} {}: report record {} carries a character outside US-ASCII at"
                                + " position {}", LEGACY_JOB, LEGACY_REPORT_STEP, index, position);
                throw new IllegalStateException(LEGACY_JOB + " " + LEGACY_REPORT_STEP
                        + ": report record " + index + " carries a character outside US-ASCII at"
                        + " position " + position + ", which the fixed-width report record cannot"
                        + " represent");
            }
        }

        final int measuredWidth = record.getBytes(StandardCharsets.US_ASCII).length;
        if (measuredWidth != REPORT_RECORD_LENGTH) {
            LOGGER.error("{} {}: report record {} measures {} bytes, expected {}", LEGACY_JOB,
                    LEGACY_REPORT_STEP, index, measuredWidth, REPORT_RECORD_LENGTH);
            throw new IllegalStateException(LEGACY_JOB + " " + LEGACY_REPORT_STEP
                    + ": report record " + index + " measures " + measuredWidth
                    + " encoded bytes, but every record of the " + LEGACY_DD_TRANREPT
                    + " dataset is fixed at " + REPORT_RECORD_LENGTH + " bytes");
        }
    }

    /**
     * Stops the generation sample against the timer for the given outcome.
     *
     * <p>Exactly one call is made per invocation, on whichever of the two paths the invocation takes,
     * because a sample may only be stopped once. The timer is built at the point of use so that the
     * outcome tag is part of its identity, which is what makes a failed generation a distinct series
     * rather than a hidden contributor to the successful one.
     *
     * @param sample  the sample started before the generation
     * @param outcome {@link #OUTCOME_COMPLETED} or {@link #OUTCOME_FAILED}
     */
    private void stopSample(final Timer.Sample sample, final String outcome) {
        sample.stop(Timer.builder(METRIC_GENERATION)
                .description("Elapsed time of one transaction detail report generation and its"
                        + " fixed-width record proof")
                .tag(TAG_RESOURCE, LEGACY_DD_TRANREPT)
                .tag(TAG_OUTCOME, outcome)
                .register(this.meterRegistry));
    }
}
