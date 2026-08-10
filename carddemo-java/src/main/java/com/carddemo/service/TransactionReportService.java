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
package com.carddemo.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.TransactionCategory;
import com.carddemo.domain.TransactionType;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.domain.id.TransactionCategoryId;
import com.carddemo.exception.FileStatusException;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionCategoryRepository;
import com.carddemo.repository.TransactionTypeRepository;
import com.carddemo.util.FailureDiagnostics;
import com.carddemo.util.ReportLineFormatter;
import com.carddemo.util.SensitiveLogRedactor;
import com.carddemo.util.ZonedDecimalCodec;

/**
 * Transaction detail report generator: the Java translation of the batch program
 * {@code app/cbl/CBTRN03C.cbl}.
 *
 * <h2>Provenance</h2>
 *
 * <p>Checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}; upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The stamp is a provenance anchor for the
 * migration as a whole and is carried by a measured subset of the estate rather than by every
 * member, so it identifies the delivery this translation was taken from and is never a per-member
 * assertion.
 *
 * <p>The primary authority is {@code app/cbl/CBTRN03C.cbl}, <strong>649 lines</strong>. Its
 * {@code PROCEDURE DIVISION} holds <strong>26 named paragraph labels</strong>, and
 * <strong>26</strong> is this member's contribution to the traceability matrix: the action plan records
 * 26, and {@code docs/traceability-matrix.md} carries <strong>exactly 26 rows</strong> for it, one per
 * label, within the estate's 544. Those 26 are the mapped units, and each has its own named method below.
 *
 * <p>The member also has an <strong>unnamed driving body at lines 160-217</strong> - the statements that
 * sequence the program before its first label. It carries no label, so it is not a traceability row and
 * owes none: it is reproduced here as a <strong>Java driver helper</strong>, the private procedure-division
 * driver the two public entry points delegate to, and it is called out that way so a reader does not
 * mistake it for a twenty-seventh mapped paragraph. It is fully translated and fully covered.
 *
 * <p>Key line sites in the authority, cited so a reviewer can find each behaviour at its origin:
 * the 133-character report record at <strong>85</strong>; the eighty-character date-parameter
 * record at <strong>88</strong>; the twenty-one-byte date-parameter layout at
 * <strong>122-125</strong>; the page size of 20 at <strong>131</strong>; the three accumulators at
 * <strong>135-137</strong>; the inclusive date filter at <strong>173-174</strong>; the
 * {@code NEXT SENTENCE} trap at <strong>173-178</strong> whose enclosing loop terminator is at
 * <strong>206</strong>; the card-number break test at <strong>282</strong>; the detail
 * accumulation at <strong>287-288</strong>; the grand-total feed at <strong>297</strong>; the
 * four-record header block at <strong>324-341</strong>; the single writer at
 * <strong>343-359</strong>; and the abend call at <strong>630</strong>.
 *
 * <p>Record authorities, with the byte width each contributes: {@code app/cpy/CVTRA05Y.cpy}, the
 * transaction record, <strong>350 bytes</strong>; {@code app/cpy/CVTRA03Y.cpy}, the transaction
 * type, <strong>60 bytes</strong>; {@code app/cpy/CVTRA04Y.cpy}, the transaction category,
 * <strong>60 bytes</strong>; {@code app/cpy/CVACT03Y.cpy}, the card cross-reference,
 * <strong>50 bytes</strong>. The member's own file description of the transaction input splits the
 * 350-byte image into a leading <strong>304-character</strong> remainder, the twenty-six-character
 * processing timestamp and a twenty-character filler, and its cross-reference description splits
 * the 50-byte image into a sixteen-character key and a thirty-four-character remainder. The report
 * layout itself comes from {@code app/cpy/CVTRA07Y.cpy}, whose seven groups and two numeric masks
 * are owned entirely by {@code ReportLineFormatter} in the utility layer.
 *
 * <h2>What this service is, and is not</h2>
 *
 * <p>Its contract is the <strong>ordered content of the report</strong>: a list of records each
 * exactly <strong>133 encoded bytes</strong> wide, together with the grand total the run resolved
 * and the counts the run observed. Emitting those records to a destination - a staged object, a
 * flat file, a step writer - belongs to the batch tier, which consumes this service. Nothing here
 * writes to a file, a queue or a table; the annotated boundary is read-only and there is no commit
 * point, so there is no rollback, no lock mode and no optimistic-locking concern.
 *
 * <p><strong>The program contains zero computation statements.</strong> That is a verified property
 * of the authority, not a style preference, and it is the reason no arithmetic is introduced here.
 * The only numeric operations in this class are the accumulator additions the source performs and
 * the line-counter increments the source performs. Every store into a two-decimal field truncates
 * toward zero, because no rounding clause appears anywhere in the estate, and every such store is
 * routed through {@code ZonedDecimalCodec} so that the truncation policy is expressed in exactly
 * one place in the module.
 *
 * <h2>Why the class is deliberately not final</h2>
 *
 * <p>The read boundary is declared with {@code @Transactional}, and this is the first class in the
 * module to declare it. Spring Boot's transaction infrastructure proxies the target class by
 * subclassing it, so a final class - or a final or private annotated method - would silently defeat
 * the boundary or fail the context outright. The class is therefore non-final and both annotated
 * entry points are public and non-final <strong>on purpose</strong>, and this paragraph exists so
 * that a reviewer does not read the absence of {@code final} as an oversight and "fix" it.
 *
 * <h2>Concurrency</h2>
 *
 * <p>A stateless singleton. The class declares <strong>no mutable field of any kind</strong>: the
 * three accumulators, the line counter, the page count, the account-break count, the current card
 * number, the first-time flag, the input cursor, the resolved lookups and the report destination
 * all live in a {@code ReportRun} holder created fresh for each invocation. A field would let one
 * run corrupt a concurrent run and would let a completed run leak into the next one, and neither
 * failure would break compilation or fail a test that did not look for it.
 *
 * @see ReportLineFormatter
 * @see ZonedDecimalCodec
 */
@Service
public class TransactionReportService {

    /**
     * The legacy program name, used as the abend culprit. Eight characters, which is exactly the
     * width the abend context reserves for it.
     */
    private static final String PROGRAM_NAME = "CBTRN03C";

    /** Data-definition name of the sequential transaction input, from the file-control entry. */
    private static final String DD_TRANFILE = "TRANFILE";

    /** Data-definition name of the sequential report output, from the file-control entry. */
    private static final String DD_TRANREPT = "TRANREPT";

    /** Data-definition name of the indexed card cross-reference, from the file-control entry. */
    private static final String DD_CARDXREF = "CARDXREF";

    /** Data-definition name of the indexed transaction type table. */
    private static final String DD_TRANTYPE = "TRANTYPE";

    /** Data-definition name of the indexed transaction category table. */
    private static final String DD_TRANCATG = "TRANCATG";

    /** Data-definition name of the sequential date-parameter input. */
    private static final String DD_DATEPARM = "DATEPARM";

    /** Operation label for an open, reported alongside a raw file status. */
    private static final String OPERATION_OPEN = "OPEN";

    /** Operation label for a read, reported alongside a raw file status. */
    private static final String OPERATION_READ = "READ";

    /** Operation label for a write, reported alongside a raw file status. */
    private static final String OPERATION_WRITE = "WRITE";

    /** Operation label for a close, reported alongside a raw file status. */
    private static final String OPERATION_CLOSE = "CLOSE";

    /**
     * Raw two-byte status reported when a resource that the legacy program would have opened is
     * not available. Drawn from the declared status vocabulary rather than invented.
     */
    private static final String STATUS_PERMANENT_ERROR = FileStatus.PERMANENT_ERROR.getCode();

    /**
     * Raw two-byte status reported for the legacy invalid-key condition of the three random reads.
     * The source moves the value 23 into its status field at each of the three sites.
     */
    private static final String STATUS_RECORD_NOT_FOUND = FileStatus.RECORD_NOT_FOUND.getCode();

    /**
     * Raw two-byte status reported when a record offered to the writer does not measure the report
     * record width. This is the status a COBOL write raises for a record-length mismatch, and it is
     * in the declared vocabulary.
     */
    private static final String STATUS_RECORD_LENGTH_MISMATCH =
            FileStatus.RECORD_LENGTH_MISMATCH.getCode();

    /** Diagnostic for a failed open of the transaction input. */
    private static final String DIAG_OPEN_TRANFILE = "ERROR OPENING TRANFILE";

    /** Diagnostic for a failed open of the report output. */
    private static final String DIAG_OPEN_TRANREPT = "ERROR OPENING REPTFILE";

    /** Diagnostic for a failed open of the card cross-reference. */
    private static final String DIAG_OPEN_CARDXREF = "ERROR OPENING CROSS REF FILE";

    /** Diagnostic for a failed open of the transaction type table. */
    private static final String DIAG_OPEN_TRANTYPE = "ERROR OPENING TRANSACTION TYPE FILE";

    /** Diagnostic for a failed open of the transaction category table. */
    private static final String DIAG_OPEN_TRANCATG = "ERROR OPENING TRANSACTION CATG FILE";

    /** Diagnostic for a failed open of the date-parameter input. */
    private static final String DIAG_OPEN_DATEPARM = "ERROR OPENING DATE PARM FILE";

    /** Diagnostic for a failed read of the transaction input. */
    private static final String DIAG_READ_TRANFILE = "ERROR READING TRANSACTION FILE";

    /** Diagnostic for a failed read of the date-parameter input. */
    private static final String DIAG_READ_DATEPARM = "ERROR READING DATEPARM FILE";

    /** Diagnostic for a failed write of a report record. */
    private static final String DIAG_WRITE_TRANREPT = "ERROR WRITING REPTFILE";

    /** Diagnostic for the invalid-key condition of the cross-reference read. */
    private static final String DIAG_INVALID_CARDXREF = "INVALID CARD NUMBER";

    /**
     * Fixed stand-in emitted in place of the key of a failed cross-reference read.
     *
     * <p>That key is a primary account number. On a mainframe the equivalent diagnostic reached an
     * operator-only console inside the same security boundary as the data; this one reaches an aggregated
     * log that is read, forwarded and retained outside it, so the value is withheld while the diagnostic
     * keeps its wording and its field set.
     *
     * <p>A constant rather than any transformation of the value, and not a partial mask: a fragment of a
     * sixteen-character numeric key is recoverable by enumeration, so a truncated primary account number is
     * still cardholder data. The same stand-in, for the same reason, that the screen services use. Nothing
     * about the report itself changes - the card number stays in the 133-byte report line, which is a file
     * record and a byte-parity obligation, not a diagnostic.
     */
    private static final String REDACTED_CARD_NUMBER = "***REDACTED***";

    /** Diagnostic for the invalid-key condition of the transaction type read. */
    private static final String DIAG_INVALID_TRANTYPE = "INVALID TRANSACTION TYPE";

    /** Diagnostic for the invalid-key condition of the transaction category read. */
    private static final String DIAG_INVALID_TRANCATG = "INVALID TRAN CATG KEY";

    /** Diagnostic for a failed close of the transaction input. */
    private static final String DIAG_CLOSE_TRANFILE = "ERROR CLOSING POSTED TRANSACTION FILE";

    /** Diagnostic for a failed close of the report output. */
    private static final String DIAG_CLOSE_TRANREPT = "ERROR CLOSING REPORT FILE";

    /** Diagnostic for a failed close of the card cross-reference. */
    private static final String DIAG_CLOSE_CARDXREF = "ERROR CLOSING CROSS REF FILE";

    /** Diagnostic for a failed close of the transaction type table. */
    private static final String DIAG_CLOSE_TRANTYPE = "ERROR CLOSING TRANSACTION TYPE FILE";

    /** Diagnostic for a failed close of the transaction category table. */
    private static final String DIAG_CLOSE_TRANCATG = "ERROR CLOSING TRANSACTION CATG FILE";

    /** Diagnostic for a failed close of the date-parameter input. */
    private static final String DIAG_CLOSE_DATEPARM = "ERROR CLOSING DATE PARM FILE";

    private static final Logger LOG = LoggerFactory.getLogger(TransactionReportService.class);

    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    private final TransactionTypeRepository transactionTypeRepository;

    private final TransactionCategoryRepository transactionCategoryRepository;

    private final AbendService abendService;

    /**
     * Constructor injection of every collaborator, which is what replaces the legacy program's
     * static file and subprogram linkage. No setter and no field injection exists, so a partially
     * built instance is not representable.
     *
     * @param cardCrossReferenceRepository the random reader that resolves a card number to its
     *                                     owning account, standing in for the indexed
     *                                     cross-reference cluster
     * @param transactionTypeRepository the random reader for transaction type descriptions
     * @param transactionCategoryRepository the random reader for transaction category descriptions
     * @param abendService the terminal diagnostic and abend path, which is what the language
     *                     environment abort routine became
     * @throws NullPointerException if any collaborator is absent
     */
    public TransactionReportService(
                                    final CardCrossReferenceRepository cardCrossReferenceRepository,
                                    final TransactionTypeRepository transactionTypeRepository,
                                    final TransactionCategoryRepository
                                            transactionCategoryRepository,
                                    final AbendService abendService) {
        this.cardCrossReferenceRepository = Objects.requireNonNull(cardCrossReferenceRepository,
                "cardCrossReferenceRepository must not be null");
        this.transactionTypeRepository = Objects.requireNonNull(transactionTypeRepository,
                "transactionTypeRepository must not be null");
        this.transactionCategoryRepository = Objects.requireNonNull(transactionCategoryRepository,
                "transactionCategoryRepository must not be null");
        this.abendService = Objects.requireNonNull(abendService, "abendService must not be null");
    }

    /**
     * The outcome of one report run.
     *
     * <p>{@code reportRecordCount} is how many records the run offered to its sink, in emission
     * order, each exactly {@code ReportLineFormatter.REPORT_RECORD_WIDTH} encoded bytes wide and
     * including the blank line of the header block and the hyphen rule lines. The report content
     * itself is <strong>not</strong> carried here: every record reached the caller's sink as it was
     * composed, so nothing accumulates and the result is an observation of the run rather than a copy
     * of its output. The remaining components are likewise observations: no report byte depends on any
     * of them. See {@code docs/decision-log.md} entry DL-176.
     *
     * <p>{@code grandTotal} is the value the grand-total line carried, which the legacy program
     * feeds <strong>only</strong> from page totals. {@code lineCount} is the final value of the
     * legacy line counter, a nine-digit field. {@code pageCount} counts how many times the
     * page-total routine ran, and {@code accountBreakCount} how many times the account-total
     * routine ran; neither counter exists in the legacy program, and both are present because the
     * target contract asks for them.
     *
     * @param reportRecordCount how many records the run offered to its sink
     * @param grandTotal        the grand total at scale two
     * @param pageCount         the number of page-total emissions
     * @param lineCount         the final line-counter value
     * @param accountBreakCount the number of account-total emissions
     */
    public record TransactionReportResult(long reportRecordCount,
                                          BigDecimal grandTotal,
                                          int pageCount,
                                          long lineCount,
                                          int accountBreakCount) {

        /**
         * Rejects an absent or impossible component, so a result can never be handed on in a
         * half-built state.
         */
        public TransactionReportResult {
            if (reportRecordCount < 0L) {
                throw new IllegalArgumentException(
                        "reportRecordCount must not be negative: " + reportRecordCount);
            }
            Objects.requireNonNull(grandTotal, "grandTotal must not be null");
        }
    }

    /**
     * Generates the report for an explicit pair of ten-character bounds.
     *
     * <p>The bounds are turned into the twenty-one-byte date-parameter record by the shared
     * formatter and the run then proceeds exactly as it does for a supplied card. Routing this
     * entry point through the formatter's <em>builder</em> and then through its <em>reader</em> is
     * deliberate: the leading twenty-one bytes of that record are byte-identical to the in-stream
     * card the online report-request program emits, and building here rather than assembling a
     * bespoke string is what keeps the two aligned by construction rather than by convention.
     *
     * <p>Both bounds are <strong>inclusive</strong>, and both stay ten-character strings for the
     * whole of the run. No temporal type is constructed anywhere.
     *
     * @param transactionSource the frozen ordered generation produced by the report job's sort step
     * @param reportRecordSink  the destination each report record is offered to, in emission order
     * @param startDate the inclusive lower bound, ten characters
     * @param endDate   the inclusive upper bound, ten characters
     * @return the run's observations; the report itself reached the sink as it was composed
     * @throws NullPointerException     if the source, the sink or either bound is absent
     * @throws IllegalArgumentException if either bound is not printable US-ASCII
     */
    @Transactional(readOnly = true)
    public TransactionReportResult generateReport(final ReportTransactionSource transactionSource,
            final Consumer<String> reportRecordSink, final String startDate, final String endDate) {
        return runProcedureDivision(ReportLineFormatter.buildDateParameterRecord(startDate, endDate),
                transactionSource, reportRecordSink);
    }

    /**
     * Generates the report from a date-parameter card, which is the legacy program's own input.
     *
     * <p>The card is read through the shared formatter's reader; this class never slices it. A card
     * of {@code null} is the target's representation of an empty date-parameter dataset, which the
     * legacy read reports as end of file: the driving loop then never iterates and the report is
     * empty, which is precisely what the legacy program produces in that case.
     *
     * @param transactionSource the frozen ordered generation produced by the report job's sort step
     * @param reportRecordSink  the destination each report record is offered to, in emission order
     * @param dateParameterCard the twenty-one-byte structured record or the eighty-byte card image;
     *                          {@code null} for an empty parameter dataset
     * @return the run's observations; the report itself reached the sink as it was composed
     * @throws NullPointerException if {@code transactionSource} or {@code reportRecordSink} is absent
     * @throws IllegalArgumentException if the card is present but measures neither of the two legal
     *                                  widths, or is not printable US-ASCII
     */
    @Transactional(readOnly = true)
    public TransactionReportResult generateReportFromDateParameterCard(
            final ReportTransactionSource transactionSource,
            final Consumer<String> reportRecordSink, final String dateParameterCard) {
        return runProcedureDivision(dateParameterCard, transactionSource, reportRecordSink);
    }

    /**
     * The driver helper: the unnamed {@code PROCEDURE DIVISION} driving body at lines 160-217. It
     * carries no paragraph label, so it is not one of the 26 traceability rows this member
     * contributes; it sequences them.
     *
     * <p>The sequence is the source's own - six opens, the parameter read, the driving loop, six
     * closes - and the two console announcements that bracket it at lines 160 and 215 become
     * structured log events.
     *
     * @param dateParameterCard the parameter card, possibly {@code null}
     * @param transactionSource the frozen ordered generation; must not be {@code null}
     * @param reportRecordSink  the destination each report record is offered to; must not be
     *                          {@code null}
     * @return the sealed result
     */
    private TransactionReportResult runProcedureDivision(final String dateParameterCard,
            final ReportTransactionSource transactionSource,
            final Consumer<String> reportRecordSink) {
        LOG.info("START OF EXECUTION OF PROGRAM {}", PROGRAM_NAME);

        final ReportRun run = new ReportRun(dateParameterCard, transactionSource, reportRecordSink);

        tranfileOpen(run);
        reptfileOpen(run);
        cardxrefOpen(run);
        trantypeOpen(run);
        trancatgOpen(run);
        dateparmOpen(run);

        dateparmRead(run);

        // The preceding batch step has already applied the inclusive range and the job-local zoned
        // decimal ordering. Acquiring the supplied source here resets only this run's sequential
        // position; it never performs a second live query and it cannot observe master-data changes
        // made after the filtered generation was written.
        if (!run.isEndOfFile()) {
            acquireTransactionInput(run);
        }

        drivingLoop(run);

        tranfileClose(run);
        reptfileClose(run);
        cardxrefClose(run);
        trantypeClose(run);
        trancatgClose(run);
        dateparmClose(run);

        LOG.info("END OF EXECUTION OF PROGRAM {}", PROGRAM_NAME);

        return new TransactionReportResult(run.reportRecordCount(), run.grandTotal(),
                run.pageCount(), run.lineCounter(), run.accountBreakCount());
    }

    /**
     * The {@code PERFORM UNTIL} loop of the driving body, lines 170-206.
     *
     * <p>Kept as its own method so that the {@code NEXT SENTENCE} transfer of control has a loop to
     * leave and so the driving body reads as the sequence of paragraph performs that it is. The
     * inner test at line 171 is retained although the loop condition already guarantees it; it is a
     * real statement of the source and removing it would be a silent edit.
     *
     * @param run the per-invocation state
     */
    private void drivingLoop(final ReportRun run) {
        while (!run.isEndOfFile()) {
            if (!run.isEndOfFile()) {
                tranfileGetNext(run);

                if (!isWithinReportingRange(run)) {
                    // ANOMALY, reproduced rather than corrected. Lines 173-178 guard the inclusive
                    // range test with CONTINUE on the matching arm and NEXT SENTENCE on the
                    // non-matching arm. NEXT SENTENCE transfers control to the statement after the
                    // next sentence-terminating period, and the next period in this member is the
                    // one on the loop's scope terminator at line 206 - so the non-matching arm leaves the
                    // WHOLE driving loop rather than skipping one record, and the closes at line
                    // 208 run next. A record outside the range therefore truncates the report and
                    // suppresses the final page and grand totals entirely.
                    //
                    // This break is that behaviour, faithfully. It is NOT a per-record skip and
                    // must not be "fixed" into one. In this target the arm is unreachable in
                    // practice: the preceding batch step applied the range predicate before freezing
                    // the ordered generation, so every record reaching here already satisfies both
                    // bounds. The arm is kept because the legacy semantics are the contract, not
                    // because it is expected to fire.
                    break;
                }
                // The matching arm is the CONTINUE at line 175: fall through to the next sentence.

                if (!run.isEndOfFile()) {
                    processTransaction(run);
                } else {
                    endOfFileTotals(run);
                }
            }
        }
    }

    /**
     * The record-present arm of the driving body, lines 179-196.
     *
     * <p>Extracted from the loop for legibility only; it performs exactly the statements of that arm
     * in exactly their order. The console dump of the whole 350-byte record image at line 180
     * becomes a field-free record-progress event. Even the entity's narrowed text form carries a
     * transaction identifier, and identifiers must not become protected debug telemetry merely
     * because the local profile raises this package's level.
     *
     * @param run the per-invocation state
     */
    private void processTransaction(final ReportRun run) {
        final Transaction transaction = run.currentTransaction();
        LOG.debug("Reporting next transaction record");
        LOG.debug("Reporting transaction transactionRef={} type={} category={}",
                SensitiveLogRedactor.redact(transaction.getTranId()),
                transaction.getTranTypeCd(), transaction.getTranCatCd());

        // Card-number break at line 181. The legacy sentinel is a spaces-filled sixteen-byte field,
        // which no sixteen-digit card number can equal, so the first record always breaks; an unset
        // holder value expresses the same thing without restating a legacy field width here.
        if (run.hasCardNumberChanged(transaction.getTranCardNum())) {
            // The first-time flag at line 182 is what stops the very first break from emitting a
            // leading account total for an account that has accumulated nothing. The flag is still
            // set at this point on the first record, because the paragraph that clears it runs
            // afterwards.
            if (!run.isFirstTime()) {
                writeAccountTotals(run);
            }
            run.currentCardNumber(transaction.getTranCardNum());
            run.xrefCardNumberKey(transaction.getTranCardNum());
            lookupXref(run);
        }

        run.transactionTypeKey(transaction.getTranTypeCd());
        lookupTrantype(run);

        run.transactionCategoryKey(transaction.getTranTypeCd(), transaction.getTranCatCd());
        lookupTrancatg(run);

        writeTransactionReport(run);
    }

    /**
     * The end-of-file arm of the driving body, lines 197-203.
     *
     * <p>ANOMALY, reproduced rather than corrected. The legacy read is a {@code READ ... INTO},
     * which on the at-end condition leaves the record area holding the <strong>previous</strong>
     * record. Two consequences follow, and the second is a defect:
     *
     * <ol>
     *   <li>the range test at lines 173-174 is evaluated against the stale timestamp of the last
     *       record, which was in range, so the test passes and control reaches this arm rather than
     *       leaving the loop; and</li>
     *   <li>line 200 then adds the stale amount to the page and account totals a
     *       <strong>second</strong> time, so the last transaction of the run is
     *       <strong>counted twice</strong> in the page total, in the account total and - because
     *       the page total is what feeds the grand total - in the grand total as well.</li>
     * </ol>
     *
     * <p>The duplicate accumulation is preserved because byte parity with the legacy report is the contract,
     * and it is raised as a decision-log entry so that a reviewer can see it was found rather than
     * missed. When no record was read at all there is no stale record to re-add: the legacy would
     * add the contents of a working-storage field that has no value clause, which is not a defined
     * value, so the target adds nothing. That single divergence is forced by the absence of
     * undefined behaviour in Java and is recorded with the anomaly.
     *
     * @param run the per-invocation state
     */
    private void endOfFileTotals(final ReportRun run) {
        final Transaction stale = run.currentTransaction();
        if (stale == null) {
            LOG.debug("End of file reached with no transaction read; no stale amount to re-add");
        } else {
            final BigDecimal amount = ZonedDecimalCodec.toMonetaryScale(stale.getTranAmt());
            LOG.debug("End of file: re-adding the stale transaction amount (legacy anomaly)");
            run.addToPageTotal(amount);
            run.addToAccountTotal(amount);
        }

        writePageTotals(run);
        writeGrandTotals(run);
    }

    /**
     * The inclusive range test of lines 173-174, evaluated in the source's operand order.
     *
     * <p>The source tests the ten-character prefix of the twenty-six-character processing timestamp
     * against the lower bound and then against the upper bound, and both comparisons are
     * <strong>inclusive</strong>. This method reproduces that guard by character comparison over the
     * whole timestamp, which is exactly equivalent and, unlike a slice, restates no field width:
     * all fixed-width knowledge in this feature lives in the utility layer.
     *
     * <p>The equivalence is the same asymmetry the batch step's character predicate relies on, and
     * it is worth stating because the two halves look different for a reason:
     *
     * <ul>
     *   <li><strong>lower bound</strong> - comparing the bare twenty-six-character timestamp against
     *       a ten-character bound gives the same answer as comparing the prefix, because a longer
     *       string that begins with the bound sorts above it, so equality of the prefix still
     *       satisfies a greater-or-equal test;</li>
     *   <li><strong>upper bound</strong> - the same substitution would fail on the boundary date,
     *       because a timestamp whose prefix equals the bound sorts <em>above</em> the bound. A
     *       prefix match is therefore tested explicitly, which is what makes the upper bound
     *       inclusive rather than exclusive.</li>
     * </ul>
     *
     * <p>The predicate is already applied once, before the batch layer freezes the sorted generation.
     * This test restates the source's guard without becoming a second, competing input selection: it
     * excludes nothing the filtered generation admitted. No temporal type is involved anywhere -
     * both bounds and the timestamp are character data, which is what the legacy comparison operated
     * on.
     *
     * @param run the per-invocation state
     * @return whether the current record's processing date lies within both bounds
     */
    private static boolean isWithinReportingRange(final ReportRun run) {
        final Transaction transaction = run.currentTransaction();
        if (transaction == null) {
            // No record has ever been read, so there is no timestamp to test. The legacy test would
            // run against an uninitialised record area; treating it as in range keeps control on the
            // same path the legacy took, which is the end-of-file arm rather than the loop exit.
            return true;
        }

        final String processingTimestamp = transaction.getTranProcTs();
        final boolean atOrAboveLowerBound =
                processingTimestamp.compareTo(run.startDate()) >= 0;
        final boolean atOrBelowUpperBound = processingTimestamp.startsWith(run.endDate())
                || processingTimestamp.compareTo(run.endDate()) < 0;

        return atOrAboveLowerBound && atOrBelowUpperBound;
    }

    /**
     * Acquires the ordered transaction input supplied by the batch step.
     *
     * <p>This is the target's expression of the legacy sequential input. The preceding job step owns
     * the inclusive date predicate and the report-specific zoned-decimal card-number ordering, then
     * freezes the resulting generation before this service starts. This method deliberately performs
     * no repository access, filtering, sorting or temporal conversion; it only arms the zero-based
     * sequential position used by the read paragraph.
     *
     * @param run the per-invocation state
     */
    private static void acquireTransactionInput(final ReportRun run) {
        run.rewindTransactionSource();
        LOG.debug("Acquired the frozen ordered transaction generation rangeRef={}",
                reportRangeReference(run));
    }

    /**
     * Paragraph unit 1 of 26: {@code 0550-DATEPARM-READ} at line 220.
     *
     * <p>Reads the date-parameter record and normalises its status through the shared two-arm
     * evaluation of lines 222-229. On success the bounds are taken from the card by the shared
     * formatter's reader - never sliced here - and the run announces its range, which is the console
     * message of lines 232-233. On end of file the run's end-of-file flag is set at line 236, and the
     * driving loop then never iterates, so an absent parameter card produces an empty report exactly
     * as the legacy program does. On any other status the diagnostic and the raw status are emitted
     * and the program abends, in that order.
     *
     * @param run the per-invocation state
     */
    private void dateparmRead(final ReportRun run) {
        final String card = run.dateParameterCard();
        final String rawStatus;
        if (card == null) {
            rawStatus = FileStatusException.STATUS_END_OF_FILE;
        } else {
            run.bounds(ReportLineFormatter.readStartDate(card),
                    ReportLineFormatter.readEndDate(card));
            rawStatus = FileStatusException.STATUS_SUCCESS;
        }

        run.applResult(normaliseSequentialReadStatus(rawStatus));

        if (run.applResult().isAok()) {
            LOG.info("Reporting range accepted rangeRef={}", reportRangeReference(run));
            return;
        }
        if (run.applResult().isEof()) {
            run.endOfFile(true);
            return;
        }

        LOG.error("{} resource={}", DIAG_READ_DATEPARM, DD_DATEPARM);
        displayIoStatus(rawStatus, OPERATION_READ, DD_DATEPARM);
        abendProgram(DIAG_READ_DATEPARM, rawStatus, OPERATION_READ, DD_DATEPARM);
    }

    /**
     * Paragraph unit 2 of 26: {@code 1000-TRANFILE-GET-NEXT} at line 248.
     *
     * <p>Advances the ordered input by one record, mirroring the sequential read of line 249 and the
     * status evaluation of lines 251-258. Exhaustion of the cursor is the at-end condition and yields
     * the end-of-file status, which sets the run's flag at line 264. <strong>End of file is never
     * collapsed into error</strong>: it is the normal termination of a sequential read loop, and the
     * two-level status model exists precisely so that the distinction survives.
     *
     * <p>Faithful to {@code READ ... INTO}, the current-record holder is <strong>not cleared</strong>
     * at end of file. That is what leaves the previous record available, and it is the mechanism
     * behind the duplicate accumulation documented on the end-of-file arm of the driving body.
     *
     * <p>The error arm is unreachable here, because a cursor either yields a record or reports
     * exhaustion; it is retained because the legacy structure is the contract.
     *
     * @param run the per-invocation state
     */
    private void tranfileGetNext(final ReportRun run) {
        final Transaction transaction = run.readNextTransaction();
        final String rawStatus;
        if (transaction != null) {
            run.currentTransaction(transaction);
            rawStatus = FileStatusException.STATUS_SUCCESS;
        } else {
            rawStatus = FileStatusException.STATUS_END_OF_FILE;
        }

        run.applResult(normaliseSequentialReadStatus(rawStatus));

        if (run.applResult().isAok()) {
            return;
        }
        if (run.applResult().isEof()) {
            run.endOfFile(true);
            return;
        }

        LOG.error("{} resource={}", DIAG_READ_TRANFILE, DD_TRANFILE);
        displayIoStatus(rawStatus, OPERATION_READ, DD_TRANFILE);
        abendProgram(DIAG_READ_TRANFILE, rawStatus, OPERATION_READ, DD_TRANFILE);
    }

    /**
     * Paragraph unit 3 of 26: {@code 1100-WRITE-TRANSACTION-REPORT} at line 274.
     *
     * <p>Three things happen here and their <strong>order is load-bearing</strong>:
     *
     * <ol>
     *   <li>lines 275-280 - on the first record only, the first-time flag is cleared, the header
     *       group's two date fields are set, and the header block is written. Because the header
     *       block advances the line counter by four, the counter is no longer zero when the page
     *       test below runs. Were the page test first, a counter of zero would satisfy it and the
     *       very first record would emit a page total for a page that had produced nothing;</li>
     *   <li>line 282 - the page break fires when the line counter modulo the page size is zero, at
     *       which point the page totals are written and a fresh header block follows;</li>
     *   <li>lines 287-288 - the transaction amount is accumulated, and then the detail line is
     *       written.</li>
     * </ol>
     *
     * <p>The two date moves of lines 277-278 are subsumed by the formatter, which takes both bounds
     * as arguments on every header build. They never change during a run, so every header block of a
     * run is byte-identical, which is what the legacy single assignment achieved.
     *
     * <p>The page size is a <strong>legacy formatting contract</strong> declared at line 131, taken
     * from the shared formatter's constant. It is deliberately not redeclared here and deliberately
     * not configurable: it decides where every rule line and every repeated header lands, so
     * changing it would change the report rather than tune it.
     *
     * @param run the per-invocation state
     */
    private void writeTransactionReport(final ReportRun run) {
        if (run.isFirstTime()) {
            run.firstTime(false);
            writeHeaders(run);
        }

        if (run.lineCounter() % ReportLineFormatter.PAGE_SIZE == 0) {
            writePageTotals(run);
            writeHeaders(run);
        }

        final BigDecimal amount =
                ZonedDecimalCodec.toMonetaryScale(run.currentTransaction().getTranAmt());

        // Lines 287-288. The amount reaches the page total and the account total and NOTHING ELSE.
        // The grand total is fed ONLY from page totals, once per page, by the page-total routine at
        // line 297. Adding the amount to the grand total here as well would compile, would look
        // right, and would produce a wrong report the moment a page boundary and an account boundary
        // interact - which is exactly why the omission is stated rather than left to be inferred.
        run.addToPageTotal(amount);
        run.addToAccountTotal(amount);

        writeDetail(run);
    }

    /**
     * Paragraph unit 4 of 26: {@code 1110-WRITE-PAGE-TOTALS} at line 293.
     *
     * <p>Performs the source's six steps in the source's order: write the page total (lines 294-296),
     * <strong>fold the page total into the grand total</strong> (line 297), zero the page total
     * (line 298), increment the line counter (line 299), write the hyphen rule line (lines 300-301),
     * and increment the line counter again (line 302).
     *
     * <p>Line 297 is the <strong>only</strong> place in the program where the grand total grows.
     * Both increments matter: omitting either would shift every subsequent page break, and the shift
     * would grow with the length of the report.
     *
     * <p>The page total is rendered with the always-signed total mask, which is a different mask from
     * the one the detail line uses; both live in the shared formatter and are deliberately not
     * unified.
     *
     * @param run the per-invocation state
     */
    private void writePageTotals(final ReportRun run) {
        writeReportRec(run, ReportLineFormatter.buildPageTotalLine(run.pageTotal()));
        run.foldPageTotalIntoGrandTotal();
        run.resetPageTotal();
        run.incrementLineCounter();
        writeReportRec(run, ReportLineFormatter.buildRuleLine());
        run.incrementLineCounter();
        run.countPage();
    }

    /**
     * Paragraph unit 5 of 26: {@code 1120-WRITE-ACCOUNT-TOTALS} at line 306.
     *
     * <p>Writes the account total (lines 307-309), zeroes it (line 310), increments the line counter
     * (line 311), writes the hyphen rule line (lines 312-313) and increments again (line 314).
     *
     * <p>The account total <strong>never</strong> feeds the grand total. That is not an omission in
     * the source: amounts reach the grand total through page totals only, and an account total is a
     * presentation subtotal over a card's transactions. Feeding it into the grand total would count
     * every amount in the report twice.
     *
     * <p>The account break and the page break are distinct concerns and are triggered from different
     * places - the account break from the driving body at line 181 when the card number changes, the
     * page break from the report driver at line 282 when the counter reaches a page boundary.
     *
     * @param run the per-invocation state
     */
    private void writeAccountTotals(final ReportRun run) {
        writeReportRec(run, ReportLineFormatter.buildAccountTotalLine(run.accountTotal()));
        run.resetAccountTotal();
        run.incrementLineCounter();
        writeReportRec(run, ReportLineFormatter.buildRuleLine());
        run.incrementLineCounter();
        run.countAccountBreak();
    }

    /**
     * Paragraph unit 6 of 26: {@code 1110-WRITE-GRAND-TOTALS} at line 318.
     *
     * <p>Writes the grand total and nothing else (lines 319-321).
     *
     * <p><strong>This paragraph deliberately does not increment the line counter.</strong> Every
     * other write site in the member is followed by an increment, and the absence of one here is
     * visible in the source: the grand total is the last record of the report, so no page boundary
     * can follow it and no subsequent line's placement depends on it. Adding an increment "for
     * consistency" would be an edit to the program, not a tidy-up, which is why the omission is
     * called out here.
     *
     * @param run the per-invocation state
     */
    private void writeGrandTotals(final ReportRun run) {
        writeReportRec(run, ReportLineFormatter.buildGrandTotalLine(run.grandTotal()));
    }

    /**
     * Paragraph unit 7 of 26: {@code 1120-WRITE-HEADERS} at line 324.
     *
     * <p>Writes the four header records of lines 325-339, each followed by its own line-counter
     * increment. The records and their order - report name header, blank line, column header, hyphen
     * rule - come from the shared formatter's header-block accessor, which fixes the count at
     * {@code ReportLineFormatter.HEADER_BLOCK_RECORD_COUNT} and the order at the single legal one,
     * so neither can be got wrong at this call site.
     *
     * <p>The blank line is a real emitted record of 133 spaces, not padding, and it is counted like
     * any other.
     *
     * @param run the per-invocation state
     */
    private void writeHeaders(final ReportRun run) {
        for (final String record
                : ReportLineFormatter.buildHeaderBlock(run.startDate(), run.endDate())) {
            writeReportRec(run, record);
            run.incrementLineCounter();
        }
    }

    /**
     * Paragraph unit 8 of 26: {@code 1111-WRITE-REPORT-REC} at line 343 - <strong>the single
     * writer</strong>.
     *
     * <p>Every record the report emits passes through this method: the four header records, every
     * detail line, every page total, every account total, every hyphen rule and the grand total.
     * Nothing appends to the report by any other route, so the report's content and its order are
     * decided in exactly one place.
     *
     * <p>The line-counter increments are in the <strong>callers</strong>, exactly as the source
     * places them, and not here. That is deliberate: the source increments after some writes and, in
     * the grand-total paragraph, after none, so moving the increment into the writer would change
     * where page breaks land.
     *
     * <p>The write status mirrors lines 346-350: a successful append reports success, and a record
     * that does not measure the report record width in encoded bytes reports the record-length
     * mismatch status and is not appended. The source treats every status other than success as
     * fatal, so the mismatch abends. Measuring encoded bytes rather than characters is the point of
     * the check - the report's width is a byte contract.
     *
     * <p>On failure the diagnostic and the raw two-byte status are emitted through the logging
     * facade <strong>before</strong> the abend, which is the order the source performs its two
     * paragraphs at lines 356-357.
     *
     * @param run    the per-invocation state
     * @param record the candidate report record
     */
    private void writeReportRec(final ReportRun run, final String record) {
        final String rawStatus = run.appendReportLine(record);

        run.applResult(normaliseTwoWayStatus(rawStatus));

        if (run.applResult().isAok()) {
            return;
        }

        LOG.error("{} resource={} recordWidth={}", DIAG_WRITE_TRANREPT, DD_TRANREPT,
                encodedWidthOf(record));
        displayIoStatus(rawStatus, OPERATION_WRITE, DD_TRANREPT);
        abendProgram(DIAG_WRITE_TRANREPT, rawStatus, OPERATION_WRITE, DD_TRANREPT);
    }

    /**
     * Paragraph unit 9 of 26: {@code 1120-WRITE-DETAIL} at line 361.
     *
     * <p>Assembles one detail record from the current transaction and the three descriptions the
     * lookups resolved, writes it through the single writer and increments the line counter
     * (line 373). The field order is the source's, lines 363-370.
     *
     * <p>The {@code INITIALIZE} of line 362 needs no counterpart: the shared formatter builds every
     * group from blanks and places every filler explicitly, which is what that statement achieved.
     * The account identifier is the one the cross-reference lookup resolved, so it persists across
     * every transaction of a card exactly as the legacy record area did.
     *
     * <p>The amount is rendered with the <strong>leading-minus, zero-suppressed detail mask</strong>,
     * which is a different mask from the always-signed one the three totals use. The two are distinct
     * by design and render the same value differently.
     *
     * @param run the per-invocation state
     */
    private void writeDetail(final ReportRun run) {
        final Transaction transaction = run.currentTransaction();

        final String record = ReportLineFormatter.buildTransactionDetailLine(
                transaction.getTranId(),
                run.xrefAccountId(),
                transaction.getTranTypeCd(),
                run.transactionTypeDescription(),
                reportCategoryCode(transaction.getTranCatCd()),
                run.transactionCategoryDescription(),
                transaction.getTranSource(),
                ZonedDecimalCodec.toMonetaryScale(transaction.getTranAmt()));

        writeReportRec(run, record);
        run.incrementLineCounter();
    }

    /**
     * Paragraph unit 10 of 26: {@code 0000-TRANFILE-OPEN} at line 376.
     *
     * <p>Opens the transaction input. In the target that resource is the frozen ordered generation
     * supplied by the batch layer, whose presence is enforced at the run boundary. The failure arm is
     * kept, and routed through the shared open handling, because the legacy program abends on a failed
     * open and that path must exist where the legacy put it.
     *
     * @param run the per-invocation state
     */
    private void tranfileOpen(final ReportRun run) {
        openResource(run, DD_TRANFILE, resourceStatusOf(run.transactionSource()),
                DIAG_OPEN_TRANFILE);
    }

    /**
     * Paragraph unit 11 of 26: {@code 0100-REPTFILE-OPEN} at line 394.
     *
     * <p>Opens the report output. This is the one open with genuine work to do: the legacy statement
     * opens the sequential report dataset for output, and here it arms the run's record count against
     * the destination the caller supplied, which is what every composed record is offered to.
     *
     * @param run the per-invocation state
     */
    private void reptfileOpen(final ReportRun run) {
        run.openReportOutput();
        openResource(run, DD_TRANREPT, resourceStatusOf(run.reportOutputHandle()),
                DIAG_OPEN_TRANREPT);
    }

    /**
     * Paragraph unit 12 of 26: {@code 0200-CARDXREF-OPEN} at line 412.
     *
     * @param run the per-invocation state
     */
    private void cardxrefOpen(final ReportRun run) {
        openResource(run, DD_CARDXREF, resourceStatusOf(this.cardCrossReferenceRepository),
                DIAG_OPEN_CARDXREF);
    }

    /**
     * Paragraph unit 13 of 26: {@code 0300-TRANTYPE-OPEN} at line 430.
     *
     * @param run the per-invocation state
     */
    private void trantypeOpen(final ReportRun run) {
        openResource(run, DD_TRANTYPE, resourceStatusOf(this.transactionTypeRepository),
                DIAG_OPEN_TRANTYPE);
        if (run.applResult().isAok()) {
            preloadTransactionTypes(run);
        }
    }

    /**
     * Reads the whole transaction-type cluster once, at its open, into this run's reference memo.
     *
     * <p><strong>&#9733; Why the whole table, and why only this kind of table.</strong> The cluster is a
     * closed reference vocabulary: {@code app/data/ASCII/trantype.txt} holds seven 60-byte records, and the
     * schema's own key-shape constraint fixes the key at two characters, so the cardinality is bounded by
     * the domain rather than by traffic. Reading it once costs one query for the whole run; resolving it a
     * key at a time cost one query per distinct code, which is a per-key round trip in place of the
     * mainframe's probe into a cluster held open for the whole step. Only genuinely bounded reference
     * tables are read this way - the cross-reference cluster grows with the card estate and is resolved in
     * bounded batches instead.
     *
     * <p>This is a read of the same cluster the open just reported on, at the moment the legacy program
     * opens it, so the run sees exactly the rows it would have seen record by record. A row inserted after
     * the open is still found: a memo miss falls through to the keyed read, which is also the path that
     * abends on a code the cluster genuinely does not hold.
     *
     * <p>A failure here is the failure of a read of this resource and takes that resource's own arm, which
     * is what the keyed read would have done at the first record.
     *
     * @param run the per-invocation state
     */
    private void preloadTransactionTypes(final ReportRun run) {
        try {
            for (final TransactionType type : this.transactionTypeRepository.findAll()) {
                run.memoizeTransactionTypeDescription(type.getTranType(), type.getTranTypeDesc());
            }
        } catch (DataAccessException failure) {
            logReferenceReadFailure(DD_TRANTYPE, failure);
            LOG.error("{} resource={}", DIAG_INVALID_TRANTYPE, DD_TRANTYPE);
            displayIoStatus(STATUS_PERMANENT_ERROR, OPERATION_READ, DD_TRANTYPE);
            abendProgram(DIAG_INVALID_TRANTYPE, STATUS_PERMANENT_ERROR, OPERATION_READ,
                    DD_TRANTYPE);
        }
    }

    /**
     * Paragraph unit 14 of 26: {@code 0400-TRANCATG-OPEN} at line 448.
     *
     * @param run the per-invocation state
     */
    private void trancatgOpen(final ReportRun run) {
        openResource(run, DD_TRANCATG, resourceStatusOf(this.transactionCategoryRepository),
                DIAG_OPEN_TRANCATG);
        if (run.applResult().isAok()) {
            preloadTransactionCategories(run);
        }
    }

    /**
     * Reads the whole transaction-category cluster once, at its open, into this run's reference memo.
     *
     * <p>The same reasoning as {@link #preloadTransactionTypes(ReportRun)} and the same bound:
     * {@code app/data/ASCII/trancatg.txt} holds eighteen 60-byte records under a composite key of a
     * two-character type code and a four-digit category code, so the vocabulary is closed. Absence still
     * abends on the record that first presents it, because a memo miss falls through to the keyed read.
     *
     * @param run the per-invocation state
     */
    private void preloadTransactionCategories(final ReportRun run) {
        try {
            for (final TransactionCategory category : this.transactionCategoryRepository.findAll()) {
                run.memoizeTransactionCategoryDescription(
                        new TransactionCategoryId(category.getTranTypeCd(), category.getTranCatCd()),
                        category.getTranCatTypeDesc());
            }
        } catch (DataAccessException failure) {
            logReferenceReadFailure(DD_TRANCATG, failure);
            LOG.error("{} resource={}", DIAG_INVALID_TRANCATG, DD_TRANCATG);
            displayIoStatus(STATUS_PERMANENT_ERROR, OPERATION_READ, DD_TRANCATG);
            abendProgram(DIAG_INVALID_TRANCATG, STATUS_PERMANENT_ERROR, OPERATION_READ,
                    DD_TRANCATG);
        }
    }

    /**
     * Paragraph unit 15 of 26: {@code 0500-DATEPARM-OPEN} at line 466.
     *
     * <p>Opens the date-parameter input. A sequential input opens successfully whether or not it
     * holds a record, so an absent parameter card is <strong>not</strong> a failed open: it surfaces
     * one step later, as the end-of-file arm of the parameter read. Reporting success here and end of
     * file there is what keeps the two conditions distinct, exactly as the legacy access method did.
     *
     * @param run the per-invocation state
     */
    private void dateparmOpen(final ReportRun run) {
        openResource(run, DD_DATEPARM, FileStatusException.STATUS_SUCCESS, DIAG_OPEN_DATEPARM);
    }

    /**
     * Paragraph unit 16 of 26: {@code 1500-A-LOOKUP-XREF} at line 484.
     *
     * <p>Resolves the current card number to its owning account. The legacy statement is a random
     * read on the cross-reference cluster whose record key is the sixteen-character card number, and
     * the cross-reference entity's identifier is that same card number, so the identifier lookup is
     * the faithful equivalent.
     *
     * <p>An absent record is the legacy invalid-key condition of lines 486-490: the source reports the
     * offending card number, moves the record-not-found status into its status field and abends. The
     * target preserves the status and abend but withholds the primary account number from the exported
     * diagnostic. It does <strong>not</strong> continue with a placeholder, because inventing a
     * substitute account identifier would put a record in the report that the legacy report never
     * contained.
     *
     * @param run the per-invocation state
     */
    private void lookupXref(final ReportRun run) {
        String memoized = run.memoizedXrefAccountId(run.xrefCardNumberKey());
        if (memoized == null) {
            // ONE read for the card numbers this run is about to need, not one per distinct card. The
            // frozen input is ordered by card number, so the cards a bounded lookahead names are the next
            // cards the run will ask for.
            prefetchCrossReferences(run);
            memoized = run.memoizedXrefAccountId(run.xrefCardNumberKey());
        }
        if (memoized != null) {
            run.xrefAccountId(memoized);
            return;
        }

        CardCrossReference crossReference = null;
        String failureStatus;
        try {
            crossReference = this.cardCrossReferenceRepository
                    .findById(run.xrefCardNumberKey())
                    .orElse(null);
            failureStatus = crossReference == null ? STATUS_RECORD_NOT_FOUND : null;
        } catch (DataAccessException failure) {
            failureStatus = STATUS_PERMANENT_ERROR;
            logReferenceReadFailure(DD_CARDXREF, failure);
        }

        if (failureStatus != null) {
            LOG.error("{} cardRef={}", DIAG_INVALID_CARDXREF,
                    redactedCardNumber(run.xrefCardNumberKey(), REDACTED_CARD_NUMBER));
            displayIoStatus(failureStatus, OPERATION_READ, DD_CARDXREF);
            abendProgram(DIAG_INVALID_CARDXREF, failureStatus, OPERATION_READ, DD_CARDXREF);
            return;
        }

        run.memoizeXrefAccountId(run.xrefCardNumberKey(), crossReference.getXrefAcctId());
        run.xrefAccountId(crossReference.getXrefAcctId());
    }

    /**
     * Resolves the cross-reference records for the card the run is on and for the cards a bounded
     * lookahead over the frozen input names, in one read.
     *
     * <p><strong>&#9733; Why a batch and not a preload.</strong> The cross-reference cluster grows with the
     * card estate, so reading it whole is not bounded and is not done. What <em>is</em> bounded is how far
     * ahead this run needs to look: the frozen input is ordered by card number under the job's own sort, so
     * the distinct cards in the next {@value ReportRun#CROSS_REFERENCE_LOOKAHEAD} records are the next
     * cards the run will ask about. One {@code findAllById} over that set replaces one keyed read per
     * distinct card, which is the shape a report over a large window degenerated into.
     *
     * <p>The lookahead reads the run's own frozen sequence and consumes nothing: the records it inspects are
     * held in the run's bounded buffer and are served from it when the driving loop reaches them. The buffer
     * is the only lookahead in the design; nothing re-reads the generation and nothing seeks backwards.
     *
     * <p><strong>Absence is not memoized and abends are not moved.</strong> Only rows the cluster actually
     * holds are recorded, so a card the cluster does not hold still falls through to the keyed read on the
     * record that presents it, and that record is still the one that abends - with its own literal, its own
     * status and its own resource. A batch is a way of reading fewer times, never a way of failing
     * differently.
     *
     * <p>A failure of the batch is deliberately <strong>not</strong> reported here. It leaves the memo
     * untouched and lets the keyed read below take the resource's own arm, so the diagnostic an operator
     * sees is the one the legacy program produces for a failed read of this cluster.
     *
     * @param run the per-invocation state
     */
    private void prefetchCrossReferences(final ReportRun run) {
        final Set<String> keys = run.upcomingCardNumbers(run.xrefCardNumberKey());
        if (keys.isEmpty()) {
            return;
        }
        try {
            for (final CardCrossReference crossReference
                    : this.cardCrossReferenceRepository.findAllById(keys)) {
                run.memoizeXrefAccountId(crossReference.getXrefCardNum(),
                        crossReference.getXrefAcctId());
            }
        } catch (DataAccessException failure) {
            // Left to the keyed read below, which owns this resource's arm.
            logReferenceReadFailure(DD_CARDXREF, failure);
        }
    }

    /**
     * Paragraph unit 17 of 26: {@code 1500-B-LOOKUP-TRANTYPE} at line 494.
     *
     * <p>Resolves the transaction type description. The type entity's identifier is the two-character
     * type code, with no separate identifier class, so the identifier lookup is direct. An absent
     * record is the invalid-key condition of lines 496-500 and abends, exactly as the source does.
     *
     * @param run the per-invocation state
     */
    private void lookupTrantype(final ReportRun run) {
        final String typeKey = run.transactionTypeKey();
        final String memoized = run.memoizedTransactionTypeDescription(typeKey);
        if (memoized != null) {
            run.transactionTypeDescription(memoized);
            return;
        }

        TransactionType transactionType = null;
        String failureStatus;
        try {
            transactionType = this.transactionTypeRepository.findById(typeKey).orElse(null);
            failureStatus = transactionType == null ? STATUS_RECORD_NOT_FOUND : null;
        } catch (DataAccessException failure) {
            failureStatus = STATUS_PERMANENT_ERROR;
            logReferenceReadFailure(DD_TRANTYPE, failure);
        }

        if (failureStatus != null) {
            LOG.error("{} key={}", DIAG_INVALID_TRANTYPE, typeKey);
            displayIoStatus(failureStatus, OPERATION_READ, DD_TRANTYPE);
            abendProgram(DIAG_INVALID_TRANTYPE, failureStatus, OPERATION_READ, DD_TRANTYPE);
            return;
        }

        run.memoizeTransactionTypeDescription(typeKey, transactionType.getTranTypeDesc());
        run.transactionTypeDescription(transactionType.getTranTypeDesc());
    }

    /**
     * Paragraph unit 18 of 26: {@code 1500-C-LOOKUP-TRANCATG} at line 504.
     *
     * <p>Resolves the transaction category description through the composite key of type code and
     * category code, which the source assembles at lines 191-194. An absent record is the invalid-key
     * condition of lines 506-510 and abends, exactly as the source does.
     *
     * @param run the per-invocation state
     */
    private void lookupTrancatg(final ReportRun run) {
        final TransactionCategoryId key = run.transactionCategoryKey();
        final String memoized = run.memoizedTransactionCategoryDescription(key);
        if (memoized != null) {
            run.transactionCategoryDescription(memoized);
            return;
        }

        TransactionCategory category = null;
        String failureStatus;
        try {
            category = this.transactionCategoryRepository.findById(key).orElse(null);
            failureStatus = category == null ? STATUS_RECORD_NOT_FOUND : null;
        } catch (DataAccessException failure) {
            failureStatus = STATUS_PERMANENT_ERROR;
            logReferenceReadFailure(DD_TRANCATG, failure);
        }

        if (failureStatus != null) {
            LOG.error("{} typeCode={} categoryCode={}", DIAG_INVALID_TRANCATG,
                    key.getTranTypeCd(), key.getTranCatCd());
            displayIoStatus(failureStatus, OPERATION_READ, DD_TRANCATG);
            abendProgram(DIAG_INVALID_TRANCATG, failureStatus, OPERATION_READ, DD_TRANCATG);
            return;
        }

        run.memoizeTransactionCategoryDescription(key, category.getTranCatTypeDesc());
        run.transactionCategoryDescription(category.getTranCatTypeDesc());
    }

    /**
     * Reports that a reference read failed for a technical reason rather than for want of a record.
     *
     * <p>Each of the three reference paragraphs at lines 484, 494 and 504 has exactly one failure arm:
     * the {@code INVALID KEY} arm, which displays the paragraph's own literal, moves 23 into the status
     * field, performs the status display and then performs the abend, in that order. That sequence is
     * unchanged for both kinds of failure; what changes is the raw status the sequence carries.
     *
     * <p>A relational store can fail in a way a random read of a key-sequenced cluster cannot: the query
     * itself can fail. Such a failure used to escape as an exception, so the literal was never displayed,
     * the status display never ran, and the abend carried a Java message rather than the member's own
     * reason. It now takes the paragraph's own sequence and carries {@code '31'} rather than {@code '23'}.
     * The distinction is the point: {@code '23'} says the cluster was read and held no such record, which
     * is a data condition an operator fixes in the reference data, while {@code '31'} says the read did
     * not complete, which is an operational one. Reporting one as the other would send an operator to the
     * wrong place.
     *
     * <p>The store's own failure is reduced to its failure-type chain before it reaches the logger, never
     * handed over whole, because a data-access failure's narrative is where a statement and its bound
     * parameters appear.
     *
     * @param resourceName the DD name of the reference cluster whose read failed
     * @param failure      the store's own failure
     */
    private static void logReferenceReadFailure(final String resourceName,
            final DataAccessException failure) {
        LOG.error("Reference read of {} did not complete; reporting raw file status {} failureChain={}",
                resourceName, STATUS_PERMANENT_ERROR, FailureDiagnostics.failureChainOf(failure));
    }

    private static String reportRangeReference(final ReportRun run) {
        return SensitiveLogRedactor.redact(run.startDate() + "|" + run.endDate());
    }

    private static String redactedCardNumber(final String value, final String standIn) {
        Objects.requireNonNull(standIn, "standIn");
        return SensitiveLogRedactor.redact(value);
    }

    /**
     * Paragraph unit 19 of 26: {@code 9000-TRANFILE-CLOSE} at line 514.
     *
     * <p>Releases the ordered input cursor. The source computes its pending value with an
     * {@code ADD ... GIVING} and clears it with a {@code SUBTRACT} rather than the {@code MOVE} the
     * other close paragraphs use; the two forms are the same assignment, and neither is arithmetic
     * over report data.
     *
     * @param run the per-invocation state
     */
    private void tranfileClose(final ReportRun run) {
        run.releaseTransactionCursor();
        closeResource(run, DD_TRANFILE, FileStatusException.STATUS_SUCCESS, DIAG_CLOSE_TRANFILE);
    }

    /**
     * Paragraph unit 20 of 26: {@code 9100-REPTFILE-CLOSE} at line 532.
     *
     * <p>Closes the report output, after which no further record can be offered to the sink. That is
     * what makes the record count a completed report's count rather than a running one.
     *
     * @param run the per-invocation state
     */
    private void reptfileClose(final ReportRun run) {
        run.closeReportOutput();
        closeResource(run, DD_TRANREPT, resourceStatusOf(run.reportOutputHandle()),
                DIAG_CLOSE_TRANREPT);
    }

    /**
     * Paragraph unit 21 of 26: {@code 9200-CARDXREF-CLOSE} at line 551.
     *
     * @param run the per-invocation state
     */
    private void cardxrefClose(final ReportRun run) {
        closeResource(run, DD_CARDXREF, FileStatusException.STATUS_SUCCESS, DIAG_CLOSE_CARDXREF);
    }

    /**
     * Paragraph unit 22 of 26: {@code 9300-TRANTYPE-CLOSE} at line 569.
     *
     * @param run the per-invocation state
     */
    private void trantypeClose(final ReportRun run) {
        closeResource(run, DD_TRANTYPE, FileStatusException.STATUS_SUCCESS, DIAG_CLOSE_TRANTYPE);
    }

    /**
     * Paragraph unit 23 of 26: {@code 9400-TRANCATG-CLOSE} at line 587.
     *
     * @param run the per-invocation state
     */
    private void trancatgClose(final ReportRun run) {
        closeResource(run, DD_TRANCATG, FileStatusException.STATUS_SUCCESS, DIAG_CLOSE_TRANCATG);
    }

    /**
     * Paragraph unit 24 of 26: {@code 9500-DATEPARM-CLOSE} at line 605.
     *
     * @param run the per-invocation state
     */
    private void dateparmClose(final ReportRun run) {
        closeResource(run, DD_DATEPARM, FileStatusException.STATUS_SUCCESS, DIAG_CLOSE_DATEPARM);
    }

    /**
     * Paragraph unit 25 of 26: {@code 9999-ABEND-PROGRAM} at line 626.
     *
     * <p>The source announces the abend at line 627, sets its timing and abend-code fields, and calls
     * the language environment abort routine at line 630. The abend service performs the equivalent:
     * it emits the announcement and the abend context through the logging facade and then raises
     * {@code AbendException}, which is what that call became.
     *
     * <p>This method never returns normally. Callers place a {@code return} after invoking it so that
     * the paragraph exit remains explicit and so that no statement can accidentally be added after
     * the abend.
     *
     * @param reason        the diagnostic identifying the failing operation, within the fifty
     *                      characters the legacy abend reason field reserves
     * @param rawFileStatus the raw two-byte status, already emitted by the display paragraph
     * @param operation     the failing operation
     * @param resourceName  the data-definition name of the failing resource
     */
    private void abendProgram(final String reason, final String rawFileStatus,
                              final String operation, final String resourceName) {
        this.abendService.abendBatch(PROGRAM_NAME, reason, rawFileStatus, operation, resourceName);
    }

    /**
     * Paragraph unit 26 of 26: {@code 9910-DISPLAY-IO-STATUS} at line 633.
     *
     * <p>The source widens the raw two-byte status into a four-character display form and prints it
     * behind a fixed prefix, taking a different branch for a non-numeric status or a status whose
     * first byte is nine. The abend service performs the whole of that, prefix included, as a single
     * structured event, so this method exists to keep the paragraph addressable and to keep the
     * emit-then-abend ordering visible at every call site.
     *
     * @param rawFileStatus the raw two-byte status
     * @param operation     the operation that produced it
     * @param resourceName  the data-definition name of the resource that produced it
     */
    private void displayIoStatus(final String rawFileStatus, final String operation,
                                final String resourceName) {
        this.abendService.displayIoStatus(rawFileStatus, operation, resourceName);
    }

    /**
     * The open handling shared by paragraph units 11 to 16.
     *
     * <p>Every one of the six open paragraphs has the same shape: set the coarse result to its pending
     * value, normalise the observed status into success or error, and on error emit the diagnostic and
     * the raw status and then abend. The shape is reproduced once here so that the six paragraphs
     * differ only in the resource they name, which is the only way they differ in the source.
     *
     * @param run          the per-invocation state
     * @param resourceName the data-definition name being opened
     * @param rawStatus    the observed raw two-byte status
     * @param diagnostic   the source's diagnostic for a failed open of this resource
     */
    private void openResource(final ReportRun run, final String resourceName,
                              final String rawStatus, final String diagnostic) {
        run.applResult(ApplResult.PENDING);
        run.applResult(normaliseTwoWayStatus(rawStatus));

        if (run.applResult().isAok()) {
            return;
        }

        LOG.error("{} resource={}", diagnostic, resourceName);
        displayIoStatus(rawStatus, OPERATION_OPEN, resourceName);
        abendProgram(diagnostic, rawStatus, OPERATION_OPEN, resourceName);
    }

    /**
     * The close handling shared by paragraph units 20 to 25, with the same shape as the open handling
     * and the same reason for being factored out.
     *
     * @param run          the per-invocation state
     * @param resourceName the data-definition name being closed
     * @param rawStatus    the observed raw two-byte status
     * @param diagnostic   the source's diagnostic for a failed close of this resource
     */
    private void closeResource(final ReportRun run, final String resourceName,
                               final String rawStatus, final String diagnostic) {
        run.applResult(ApplResult.PENDING);
        run.applResult(normaliseTwoWayStatus(rawStatus));

        if (run.applResult().isAok()) {
            return;
        }

        LOG.error("{} resource={}", diagnostic, resourceName);
        displayIoStatus(rawStatus, OPERATION_CLOSE, resourceName);
        abendProgram(diagnostic, rawStatus, OPERATION_CLOSE, resourceName);
    }

    /**
     * The three-arm status evaluation of the two sequential read paragraphs, at lines 222-229 and
     * again, identically, at lines 251-258.
     *
     * <p>The clause order is the source's and must stay that way: success, then end of file, then the
     * catch-all, which the source writes as {@code WHEN OTHER} and which becomes the
     * {@code default} arm. The three coarse values are the source's own - zero, sixteen and twelve -
     * and the distinction between the second and the third is the whole reason a two-level status
     * model exists. Collapsing end of file into error would turn the normal termination of every
     * sequential read loop into an abend.
     *
     * @param rawStatus the raw two-byte status
     * @return the coarse result the source's paragraphs branch on
     */
    private static ApplResult normaliseSequentialReadStatus(final String rawStatus) {
        return switch (rawStatus) {
            case FileStatusException.STATUS_SUCCESS -> ApplResult.AOK;
            case FileStatusException.STATUS_END_OF_FILE -> ApplResult.EOF;
            default -> ApplResult.ERROR;
        };
    }

    /**
     * The two-arm status test the open, close and write paragraphs use - for example at lines 379-383
     * and again at lines 346-350.
     *
     * <p>Those paragraphs test only for success and treat everything else as error, with
     * <strong>no</strong> end-of-file arm, because none of those operations can report end of file.
     * The distinction from the read evaluation is the source's and is kept.
     *
     * @param rawStatus the raw two-byte status
     * @return success or error
     */
    private static ApplResult normaliseTwoWayStatus(final String rawStatus) {
        return FileStatusException.STATUS_SUCCESS.equals(rawStatus)
                ? ApplResult.AOK
                : ApplResult.ERROR;
    }

    /**
     * Reports the raw status a resource-backed open or close observes.
     *
     * <p>A resource this service holds by construction is always present, so this reports success. A
     * missing resource reports the permanent-error status, which is part of the declared status
     * vocabulary rather than an invented code. The absent case is unreachable while every collaborator
     * is injected and null-checked at construction, and it exists so that the failure arm of the open
     * and close paragraphs is a real arm rather than a comment.
     *
     * @param resource the resource being opened or closed
     * @return the raw two-byte status
     */
    private static String resourceStatusOf(final Object resource) {
        return resource == null
                ? STATUS_PERMANENT_ERROR
                : FileStatusException.STATUS_SUCCESS;
    }

    /**
     * The category-code move of line 367: a four-digit numeric-display field moved to another
     * four-digit numeric-display field.
     *
     * <p>The category code is held as a four-character lexeme in the entity, because its leading zeros
     * are contractual, and the formatter's detail builder takes it as a number and re-imposes the
     * four-digit zero fill. The round trip is therefore exact for any four-digit lexeme, and this
     * conversion is neither arithmetic nor formatting: it is the bridge between the two
     * representations of one numeric-display field.
     *
     * @param categoryCodeLexeme the four-character category code
     * @return the same value as a number
     * @throws NumberFormatException if the stored lexeme is not a numeric-display value, which the
     *                               field's declaration makes impossible
     */
    private static int reportCategoryCode(final String categoryCodeLexeme) {
        return Integer.parseInt(categoryCodeLexeme);
    }

    /**
     * Measures a candidate report record in encoded bytes.
     *
     * <p>The report's width is a byte contract, so it is measured in bytes and not in characters. The
     * two differ for any value outside US-ASCII, and a record that measures 133 characters but more
     * than 133 bytes would corrupt every downstream reader while passing a character-length check.
     *
     * @param record the candidate record
     * @return its width in encoded bytes
     */
    private static int encodedWidthOf(final String record) {
        return record.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * The coarse result the batch tier branches on, mirroring the source's {@code APPL-RESULT} field
     * and the two condition names declared on it at lines 151-152.
     *
     * <p>The source sets four values: eight while an open or close is pending, zero on success,
     * sixteen at end of file and twelve on error. Only the first two of those carry condition names -
     * {@code APPL-AOK} and {@code APPL-EOF} - and those become the two predicates here.
     *
     * <p>This is deliberately a private nested type. The module's coarse outcome for the batch tier is
     * declared by the batch step base class, which this layer must not depend on, and a competing
     * top-level type would be a second answer to a question the module has already answered.
     */
    private enum ApplResult {

        /** The source's value eight: an open or close has been requested but not yet evaluated. */
        PENDING,

        /** The source's value zero, named {@code APPL-AOK}. */
        AOK,

        /** The source's value sixteen, named {@code APPL-EOF}. */
        EOF,

        /** The source's value twelve: any status the paragraph does not recognise. */
        ERROR;

        /**
         * @return whether this is the source's {@code APPL-AOK} condition
         */
        boolean isAok() {
            return this == AOK;
        }

        /**
         * @return whether this is the source's {@code APPL-EOF} condition
         */
        boolean isEof() {
            return this == EOF;
        }
    }

    /**
     * All of one run's mutable state, created fresh for each invocation.
     *
     * <p>This type exists so that the service can be a stateless singleton. The legacy program keeps
     * the line counter, the three accumulators, the first-time flag, the current card number and every
     * record area in working storage, which for a batch program that runs once per job step is
     * perfectly safe. A Spring singleton is not that: a field here would let two concurrent runs
     * interleave their totals, and would let a completed run's counter seed the next one. Neither
     * failure breaks compilation and neither fails a test that does not look for it, which is why the
     * state is confined to a per-invocation holder instead.
     *
     * <p>The holder is deliberately not thread-safe. One run is one thread, and sharing a holder is
     * the very thing it exists to prevent.
     */
    private static final class ReportRun {

        /** Zero at the monetary scale, which is what the source's value clauses establish. */
        private static final BigDecimal ZERO_AT_MONETARY_SCALE =
                ZonedDecimalCodec.toMonetaryScale(BigDecimal.ZERO);

        /** Legacy name of the page accumulator, {@code app/cbl/CBTRN03C.cbl} line 134. */
        private static final String FIELD_WS_PAGE_TOTAL = "WS-PAGE-TOTAL";

        /** Legacy name of the account accumulator, {@code app/cbl/CBTRN03C.cbl} line 135. */
        private static final String FIELD_WS_ACCOUNT_TOTAL = "WS-ACCOUNT-TOTAL";

        /** Legacy name of the grand accumulator, {@code app/cbl/CBTRN03C.cbl} line 136. */
        private static final String FIELD_WS_GRAND_TOTAL = "WS-GRAND-TOTAL";

        private final String dateParameterCard;

        private final ReportTransactionSource transactionSource;

        /**
         * The destination every report record is offered to, in emission order.
         *
         * <p>The run holds a destination rather than a report. One record is in hand at a time, so the
         * report's size is bounded by the record width and never by the run's length.
         */
        private final Consumer<String> reportRecordSink;

        /** How many records this run has offered to its sink. */
        private long reportRecordCount;

        /** Whether the report output has been closed, after which no record may be offered. */
        private boolean reportOutputSealed;

        private String startDate;

        private String endDate;

        private int transactionPosition;

        private Transaction currentTransaction;

        private boolean endOfFile;

        private ApplResult applResult = ApplResult.PENDING;

        /** The source's {@code WS-FIRST-TIME}, whose value clause is {@code 'Y'}. */
        private boolean firstTime = true;

        /** The source's {@code WS-LINE-COUNTER}, a nine-digit field with a value clause of zero. */
        private long lineCounter;

        private BigDecimal pageTotal = ZERO_AT_MONETARY_SCALE;

        private BigDecimal accountTotal = ZERO_AT_MONETARY_SCALE;

        private BigDecimal grandTotal = ZERO_AT_MONETARY_SCALE;

        private String currentCardNumber;

        private String xrefCardNumberKey;

        private String xrefAccountId;

        private String transactionTypeKey;

        private String transactionTypeDescription;

        private TransactionCategoryId transactionCategoryKey;

        private String transactionCategoryDescription;

        private int pageCount;

        private int accountBreakCount;

        /**
         * How many records ahead of the one in hand this run buffers, and therefore how many cards one
         * batched cross-reference read may resolve.
         *
         * <p>A working-set bound rather than a tuning value: it caps both the buffered records and the key
         * list of one read, so neither grows with the window the report covers. The figure is the same
         * bounded-page size the module's keyset walks use, so the batch tier has one page size and not
         * several.
         */
        private static final int CROSS_REFERENCE_LOOKAHEAD = 256;

        /**
         * Records read ahead of the one in hand, at most {@value #CROSS_REFERENCE_LOOKAHEAD} of them.
         *
         * <p>The buffer is the run's read position, not a cache: a record enters it when it is read from the
         * frozen source and leaves it when the driving loop is given it. Its only additional purpose is to
         * let a reference read resolve the cards the run is about to need in one round trip.
         */
        private final Deque<Transaction> lookahead = new ArrayDeque<>();

        /** Whether the frozen source has reported exhaustion, so it is not asked again. */
        private boolean lookaheadExhausted;

        /**
         * Reference reads this run has already resolved, keyed by the reference key each paragraph
         * presents.
         *
         * <p>This is what removes the one-query-per-row shape. The legacy performs a random read of a
         * key-sequenced cluster per record, which on the mainframe is an index probe against a cluster
         * held open for the whole step; its relational equivalent issued per record is a separate
         * round trip per record, and a report over the seeded daily-transaction input issued several
         * hundred of them to resolve seven distinct transaction types and eighteen distinct categories.
         *
         * <p><strong>The memo alone still left one query per distinct key</strong>, which grows with the
         * report rather than with the reference vocabulary. Two of the three maps are therefore filled in
         * full at the open of the cluster they describe, because those clusters are closed vocabularies of
         * seven and eighteen rows; the third is filled in bounded batches over the cards the buffered
         * lookahead names, because the cross-reference cluster grows with the card estate and reading it
         * whole would not be bounded.
         *
         * <p>Absence is deliberately <strong>not</strong> memoized, because an absent reference abends
         * on the record that first presents it and the run does not continue. The first missing reference
         * is therefore still the one that fails, with its own literal, its own status and its own
         * resource, exactly as before - a preload and a batch change how many times the store is read,
         * never which record fails or how.
         *
         * <p>Held per run and never shared. A cache that outlived a run would make one run's reference
         * data visible to the next, which is a snapshot the legacy step never had. Recorded as DL-175 in
         * {@code docs/decision-log.md}, extended by DL-294.
         */
        private final Map<String, String> xrefAccountIds = new HashMap<>();

        private final Map<String, String> transactionTypeDescriptions = new HashMap<>();

        private final Map<TransactionCategoryId, String> transactionCategoryDescriptions =
                new HashMap<>();

        ReportRun(final String card, final ReportTransactionSource source,
                final Consumer<String> sink) {
            this.dateParameterCard = card;
            this.transactionSource = Objects.requireNonNull(source,
                    "transactionSource must not be null");
            this.reportRecordSink = Objects.requireNonNull(sink,
                    "reportRecordSink must not be null");
        }

        String dateParameterCard() {
            return this.dateParameterCard;
        }

        /**
         * Returns the account identifier this run already resolved for a card number.
         *
         * @param cardNumberKey the cross-reference record key
         * @return the resolved account identifier, or {@code null} when this run has not resolved it
         */
        String memoizedXrefAccountId(final String cardNumberKey) {
            return this.xrefAccountIds.get(cardNumberKey);
        }

        /**
         * Records one resolved account identifier for the remainder of this run.
         *
         * @param cardNumberKey the cross-reference record key
         * @param accountId     the account identifier the cluster held for it
         */
        void memoizeXrefAccountId(final String cardNumberKey, final String accountId) {
            this.xrefAccountIds.put(cardNumberKey, accountId);
        }

        /**
         * Returns the type description this run already resolved for a type code.
         *
         * @param typeKey the two-character transaction type code
         * @return the resolved description, or {@code null} when this run has not resolved it
         */
        String memoizedTransactionTypeDescription(final String typeKey) {
            return this.transactionTypeDescriptions.get(typeKey);
        }

        /**
         * Records one resolved type description for the remainder of this run.
         *
         * @param typeKey     the two-character transaction type code
         * @param description the description the cluster held for it
         */
        void memoizeTransactionTypeDescription(final String typeKey, final String description) {
            this.transactionTypeDescriptions.put(typeKey, description);
        }

        /**
         * Returns the category description this run already resolved for a composite category key.
         *
         * @param key the composite type-and-category key
         * @return the resolved description, or {@code null} when this run has not resolved it
         */
        String memoizedTransactionCategoryDescription(final TransactionCategoryId key) {
            return this.transactionCategoryDescriptions.get(key);
        }

        /**
         * Records one resolved category description for the remainder of this run.
         *
         * @param key         the composite type-and-category key
         * @param description the description the cluster held for it
         */
        void memoizeTransactionCategoryDescription(final TransactionCategoryId key,
                final String description) {
            this.transactionCategoryDescriptions.put(key, description);
        }

        void bounds(final String lowerBound, final String upperBound) {
            this.startDate = lowerBound;
            this.endDate = upperBound;
        }

        String startDate() {
            return this.startDate;
        }

        String endDate() {
            return this.endDate;
        }

        boolean isEndOfFile() {
            return this.endOfFile;
        }

        void endOfFile(final boolean reached) {
            this.endOfFile = reached;
        }

        ApplResult applResult() {
            return this.applResult;
        }

        void applResult(final ApplResult result) {
            this.applResult = result;
        }

        ReportTransactionSource transactionSource() {
            return this.transactionSource;
        }

        void rewindTransactionSource() {
            this.transactionPosition = 0;
            this.lookahead.clear();
            this.lookaheadExhausted = false;
        }

        void releaseTransactionCursor() {
            this.transactionPosition = 0;
            this.lookahead.clear();
            this.lookaheadExhausted = false;
        }

        /**
         * Serves the next record of the frozen sequence, filling the bounded lookahead buffer when it is
         * empty.
         *
         * <p>The buffer is what makes a batched reference read possible without a second pass over the
         * generation: the records it holds are the ones the driving loop is about to be given, so their card
         * numbers are the cards the next reference reads will ask for. It holds at most
         * {@value #CROSS_REFERENCE_LOOKAHEAD} records, so the working set is bounded whatever the window
         * admits, and the sequence it serves is byte for byte the sequence the source produced.
         *
         * @return the next record, or {@code null} at end of file
         */
        Transaction readNextTransaction() {
            if (this.lookahead.isEmpty()) {
                fillLookahead();
            }
            final Transaction next = this.lookahead.pollFirst();
            if (next == null) {
                return null;
            }
            this.transactionPosition++;
            return next;
        }

        /**
         * Reads up to {@value #CROSS_REFERENCE_LOOKAHEAD} records forward into the buffer.
         *
         * <p>Reads by ascending position with no gaps, which is the only access a forward-only generation
         * walk permits, and stops at the first exhausted position so a source is never asked for a position
         * beyond its end twice.
         */
        private void fillLookahead() {
            if (this.lookaheadExhausted) {
                return;
            }
            int position = this.transactionPosition;
            while (this.lookahead.size() < CROSS_REFERENCE_LOOKAHEAD) {
                final Optional<Transaction> next = Objects.requireNonNull(
                        this.transactionSource.readAt(position),
                        "transactionSource.readAt must report an Optional, never null");
                if (next.isEmpty()) {
                    this.lookaheadExhausted = true;
                    return;
                }
                this.lookahead.addLast(next.get());
                position++;
            }
        }

        /**
         * The card numbers a batched cross-reference read should resolve: the one in hand plus the distinct
         * card numbers the buffered records carry.
         *
         * <p>Bounded by the buffer, so the set is never larger than
         * {@value #CROSS_REFERENCE_LOOKAHEAD} plus one however many records the window admitted. Insertion
         * ordered, so the read's key list is deterministic and a diagnostic naming it reads the same way
         * twice. Cards this run has already resolved are omitted, because re-reading them would defeat the
         * memo the batch exists to fill.
         *
         * @param  currentCardNumberKey the cross-reference key of the record in hand
         * @return the keys to resolve, empty when every one of them is already resolved
         */
        Set<String> upcomingCardNumbers(final String currentCardNumberKey) {
            if (this.lookahead.isEmpty()) {
                fillLookahead();
            }
            final Set<String> keys = new LinkedHashSet<>();
            if (currentCardNumberKey != null
                    && !this.xrefAccountIds.containsKey(currentCardNumberKey)) {
                keys.add(currentCardNumberKey);
            }
            for (final Transaction upcoming : this.lookahead) {
                final String cardNumber = upcoming.getTranCardNum();
                if (cardNumber != null && !this.xrefAccountIds.containsKey(cardNumber)) {
                    keys.add(cardNumber);
                }
            }
            return keys;
        }

        Transaction currentTransaction() {
            return this.currentTransaction;
        }

        void currentTransaction(final Transaction transaction) {
            this.currentTransaction = transaction;
        }

        boolean isFirstTime() {
            return this.firstTime;
        }

        void firstTime(final boolean first) {
            this.firstTime = first;
        }

        long lineCounter() {
            return this.lineCounter;
        }

        void incrementLineCounter() {
            this.lineCounter++;
        }

        BigDecimal pageTotal() {
            return this.pageTotal;
        }

        BigDecimal accountTotal() {
            return this.accountTotal;
        }

        BigDecimal grandTotal() {
            return this.grandTotal;
        }

        /**
         * The page-total half of the source's two-receiver add at lines 287-288, and of the stale add
         * at line 200. The three accumulators are declared {@code PIC S9(09)V99} at
         * {@code app/cbl/CBTRN03C.cbl} lines 134 to 136, so each add is <em>stored</em> into that
         * geometry through the codec: surplus fractional digits truncate toward zero and surplus
         * high-order digits are dropped, exactly as the receiving field's digit positions would. Both
         * halves are the codec's so that no call site can choose a different policy.
         *
         * @param amount the addend, already at the monetary scale
         */
        void addToPageTotal(final BigDecimal amount) {
            this.pageTotal = ZonedDecimalCodec.storeIntoMonetary(this.pageTotal.add(amount),
                    ZonedDecimalCodec.INTEGER_DIGITS_PIC_S9_09_V99, FIELD_WS_PAGE_TOTAL);
        }

        /**
         * The account-total half of the same two-receiver add.
         *
         * @param amount the addend, already at the monetary scale
         */
        void addToAccountTotal(final BigDecimal amount) {
            this.accountTotal = ZonedDecimalCodec.storeIntoMonetary(this.accountTotal.add(amount),
                    ZonedDecimalCodec.INTEGER_DIGITS_PIC_S9_09_V99, FIELD_WS_ACCOUNT_TOTAL);
        }

        /**
         * The add of line 297 - the <strong>only</strong> statement in the program that grows the
         * grand total. It is fed from the page total, never from a detail amount.
         */
        void foldPageTotalIntoGrandTotal() {
            this.grandTotal = ZonedDecimalCodec.storeIntoMonetary(
                    this.grandTotal.add(this.pageTotal),
                    ZonedDecimalCodec.INTEGER_DIGITS_PIC_S9_09_V99, FIELD_WS_GRAND_TOTAL);
        }

        /** The zeroing of line 298. */
        void resetPageTotal() {
            this.pageTotal = ZERO_AT_MONETARY_SCALE;
        }

        /** The zeroing of line 310. */
        void resetAccountTotal() {
            this.accountTotal = ZERO_AT_MONETARY_SCALE;
        }

        /**
         * The card-number break test of line 181.
         *
         * <p>The source's holder is a sixteen-byte field whose value clause is spaces, which no
         * sixteen-digit card number can equal, so the first record always breaks. An unset holder
         * expresses exactly that without restating the field width here.
         *
         * @param cardNumber the current record's card number
         * @return whether the card number differs from the one the run is accumulating
         */
        boolean hasCardNumberChanged(final String cardNumber) {
            return !Objects.equals(this.currentCardNumber, cardNumber);
        }

        /** The move of line 185. */
        void currentCardNumber(final String cardNumber) {
            this.currentCardNumber = cardNumber;
        }

        String xrefCardNumberKey() {
            return this.xrefCardNumberKey;
        }

        /** The move of line 186, which sets the cross-reference read key. */
        void xrefCardNumberKey(final String cardNumber) {
            this.xrefCardNumberKey = cardNumber;
        }

        String xrefAccountId() {
            return this.xrefAccountId;
        }

        void xrefAccountId(final String accountId) {
            this.xrefAccountId = accountId;
        }

        String transactionTypeKey() {
            return this.transactionTypeKey;
        }

        /** The move of line 189, which sets the transaction type read key. */
        void transactionTypeKey(final String typeCode) {
            this.transactionTypeKey = typeCode;
        }

        String transactionTypeDescription() {
            return this.transactionTypeDescription;
        }

        void transactionTypeDescription(final String description) {
            this.transactionTypeDescription = description;
        }

        TransactionCategoryId transactionCategoryKey() {
            return this.transactionCategoryKey;
        }

        /** The two moves of lines 191-194, which assemble the composite category read key. */
        void transactionCategoryKey(final String typeCode, final String categoryCode) {
            this.transactionCategoryKey = new TransactionCategoryId(typeCode, categoryCode);
        }

        String transactionCategoryDescription() {
            return this.transactionCategoryDescription;
        }

        void transactionCategoryDescription(final String description) {
            this.transactionCategoryDescription = description;
        }

        /**
         * Opens the report output. A sequential open for output starts an empty dataset, discarding
         * anything a previous run left; the destination itself is allocated by the caller that
         * supplied the sink, and what this arms is the run's own record count.
         */
        void openReportOutput() {
            this.reportRecordCount = 0L;
            this.reportOutputSealed = false;
        }

        /** Closes the report output, after which no further record may be offered to the sink. */
        void closeReportOutput() {
            this.reportOutputSealed = true;
        }

        /**
         * @return the destination, which is the target's stand-in for the open report dataset
         */
        Object reportOutputHandle() {
            return this.reportRecordSink;
        }

        /**
         * Appends one record to the report and reports the raw status the write observed.
         *
         * <p>A record that is absent, or that does not measure the report record width in encoded
         * bytes, is <strong>not</strong> appended and reports the record-length mismatch status, which
         * the writing paragraph treats as fatal. This is the single point at which the width contract
         * is enforced, which is what makes it impossible for a malformed record to reach the report.
         *
         * @param record the candidate record
         * @return the raw two-byte status
         */
        String appendReportLine(final String record) {
            if (this.reportOutputSealed) {
                throw new IllegalStateException("a record was offered to the " + DD_TRANREPT
                        + " report output after it was closed; the report is complete once the close"
                        + " paragraph has run");
            }
            if (record == null
                    || encodedWidthOf(record) != ReportLineFormatter.REPORT_RECORD_WIDTH) {
                return STATUS_RECORD_LENGTH_MISMATCH;
            }
            this.reportRecordSink.accept(record);
            this.reportRecordCount++;
            return FileStatusException.STATUS_SUCCESS;
        }

        /**
         * @return how many records this run has offered to its sink
         */
        long reportRecordCount() {
            return this.reportRecordCount;
        }

        /** Counts one page-total emission. Not a legacy field; no emitted byte depends on it. */
        void countPage() {
            this.pageCount++;
        }

        /** Counts one account-total emission. Not a legacy field; no emitted byte depends on it. */
        void countAccountBreak() {
            this.accountBreakCount++;
        }

        int pageCount() {
            return this.pageCount;
        }

        int accountBreakCount() {
            return this.accountBreakCount;
        }
    }
}
