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
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.DailyTransaction;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.domain.enums.RejectReason;
import com.carddemo.domain.id.TransactionCategoryBalanceId;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.FileStatusException;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.DailyTransactionRepository;
import com.carddemo.repository.RecordWriter;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.BoundedKeysetIterator;
import com.carddemo.util.SensitiveLogRedactor;
import com.carddemo.util.ZonedDecimalCodec;

/**
 * Posts daily transactions onto the account, category-balance and transaction stores.
 *
 * <p>Translation of {@code app/cbl/CBTRN02C.cbl}, the batch daily-transaction posting program. Each of
 * its procedure units has a named method here, and {@code docs/traceability-matrix.md} carries the
 * unit-to-method inventory with the source line range of each; every method below also states its own
 * paragraph name and line range. Provenance: checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <h2>Record authorities</h2>
 * Five copybooks fix the widths this class moves values between: {@code app/cpy/CVTRA06Y.cpy}, the
 * daily transaction, 350 bytes; {@code app/cpy/CVTRA05Y.cpy}, the posted transaction, 350 bytes;
 * {@code app/cpy/CVTRA01Y.cpy}, the transaction category balance, 50 bytes;
 * {@code app/cpy/CVACT01Y.cpy}, the account, 300 bytes; and {@code app/cpy/CVACT03Y.cpy}, the card
 * cross-reference, 50 bytes. The two transaction layouts are byte-for-byte identical in shape and
 * differ only in their field-name prefix, which is exactly why the merchant mapping below needs
 * saying out loud.
 *
 * <h2>The 430-byte reject record: shape described here, bytes written elsewhere</h2>
 * Line 178 declares an eighty-character validation trailer beside the 350-byte source image, and
 * lines 180 to 182 split that trailer into a four-digit numeric fail reason and a seventy-six
 * character description. The reject record is therefore <strong>430 bytes exactly: 350 + 4 + 76 =
 * 430</strong>. This class decides the reason code and the description text and exposes them on
 * {@link PostingResult} together with the source record; it never assembles the record. The
 * {@code RejectRecordWriter} of the batch-step package owns the 430-byte emission and the
 * {@code DailyTransactionRecordMapper} of the utility package owns the 350-byte image, so no
 * substring, no offset slicing and no width padding of a record image happens here.
 *
 * <h2>The seven behaviours that a plausible implementation gets wrong</h2>
 * <ol>
 *   <li><strong>Return code 4 is a partial success, not a failure</strong> (lines 228 to 230). Any
 *       reject at all moves 4 into the return code; the run still completes and still posts every
 *       accepted record. Nothing here throws because a record was rejected, and the returned code is
 *       never zero once a reject has occurred.</li>
 *   <li><strong>Reject code 100 short-circuits the account lookup</strong> (lines 370 to 378). The
 *       validation driver performs the account lookup only while the reason code is still zero, so a
 *       card-number failure means the account is never read at all.</li>
 *   <li><strong>Reject codes 102 and 103 are two consecutive unguarded blocks</strong> (lines 407 to
 *       420), so 103 overwrites 102 when a transaction is both over limit and past expiry. There is
 *       no {@code ELSE} between them in the source and there is none here.</li>
 *   <li><strong>The expiry test is a lexicographic string comparison</strong> (line 414), not date
 *       arithmetic. Both operands are zero-padded {@code YYYY-MM-DD} text, so a character comparison
 *       and a calendar comparison agree - and the character comparison is what the legacy performs.
 *       No date type is parsed, constructed or compared anywhere in this class.</li>
 *   <li><strong>Reject code 109 is inert</strong> (line 556). It is set in the account-rewrite
 *       invalid-key arm, which is reached only after the category balance has already been written
 *       and long after the mainline branched on the reason code being zero. The transaction-file
 *       write still runs. So 109 never produces a reject record, and that is preserved rather than
 *       repaired.</li>
 *   <li><strong>The overlimit basis is evaluated strictly left to right</strong> (lines 403 to 405).
 *       Truncating arithmetic is not associative, so an algebraically identical rearrangement changes
 *       which transactions are rejected.</li>
 *   <li><strong>A negative amount is added unchanged to the debit accumulator</strong> (lines 548 to
 *       552), which therefore holds a negative total. It is neither negated nor made absolute.</li>
 * </ol>
 *
 * <h2>Posting order</h2>
 * Lines 424 to 444 move eleven fields, copy the origination timestamp verbatim at line 436,
 * regenerate the processing timestamp at lines 437 to 438, and then run three stages in this order
 * at lines 440 to 442: <strong>category balance, then account, then transaction file</strong>. This
 * is the opposite of the online bill-payment order, which writes the transaction first and updates
 * the account last, and the two are deliberately not unified.
 *
 * <h2>Decimal policy</h2>
 * Every monetary value passes through {@link ZonedDecimalCodec}, the module's single decimal
 * authority, which imposes scale two by truncating toward zero. Truncation rather than rounding to
 * nearest is not a preference: the {@code ROUNDED} phrase occurs zero times in the entire legacy
 * estate, and a COBOL arithmetic store without it truncates. Every monetary value here is a
 * {@link BigDecimal}; no binary approximation of a decimal appears anywhere in this class, because an
 * approximation cannot reproduce a cent-exact comparison and a cent decides whether a record posts.
 *
 * <h2>Diagnostics, end of file, and abends</h2>
 * The legacy console diagnostics become structured SLF4J events. End of file is the read loop's
 * normal termination and is never folded into the error arm: the sequential read at lines 345 to 369
 * normalises status {@code 10} to the end-of-file outcome and only anything else to the error
 * outcome. On the error arm the diagnostic and the raw two-byte status are emitted first and the
 * abend is raised second, matching lines 714 to 727 followed by lines 707 to 711;
 * {@link FileStatusException} carries the raw code and is used for the error arm only, since it
 * refuses both success and end-of-file by construction.
 *
 * <h2>Control flow this member does not contain</h2>
 * The member carries no {@code EVALUATE}, no {@code PERFORM ... THRU}, no {@code GO TO} and no
 * {@code SORT} or {@code MERGE}. So no clause order needs preserving, no paragraph range falls through
 * into another, no paragraph loops back on itself, and no ordering is imposed on the records beyond the
 * order they arrive in. Every {@code PERFORM} is a plain single-paragraph invocation, which is why every
 * unit maps to an ordinary method call.
 *
 * <h2>Lifecycle, state and transactions</h2>
 * Stateless: every field is final and holds a collaborator, so the reason code, the description, the
 * counters and the create flag are all per-invocation locals. A reason code held in a field would
 * leak across records, which is precisely what the per-record reset at lines 208 to 209 exists to
 * prevent. One container-managed instance is therefore safely shared.
 *
 * <p>The per-record unit of work belongs to {@link PostingRecordTransactionBoundary} rather than to a
 * transactional method on this class. That is not a style preference: this class reproduces the mainline
 * loop in {@link #postAll(Iterable, Consumer)}, and a loop calling a transactional method on its own
 * instance reaches the target directly, so no proxy is consulted and the annotation applies to none of
 * the records the loop drives. Delegating to a separate bean removes the possibility of that split -
 * {@link #post(DailyTransaction)} and the loop take the same path, and it is the transactional one.
 * The class remains non-{@code final} because the framework still proxies it for other reasons.
 */
@Service
public class TransactionPostingService {

    /** The legacy member this class translates, used as the abend culprit. */
    public static final String PROGRAM_NAME = "CBTRN02C";

    /** Source-record width of the reject record, from the trailer declaration at line 178. */
    public static final int SOURCE_IMAGE_LENGTH = 350;

    /** Validation-trailer width, from line 178. */
    public static final int VALIDATION_TRAILER_LENGTH = 80;

    /** Width of the numeric fail reason inside the trailer, from line 181. */
    public static final int FAIL_REASON_LENGTH = 4;

    /** Width of the fail-reason description inside the trailer, from line 182. */
    public static final int FAIL_REASON_DESCRIPTION_LENGTH = 76;

    /**
     * Total reject-record width: {@value #SOURCE_IMAGE_LENGTH} + {@value #FAIL_REASON_LENGTH} +
     * {@value #FAIL_REASON_DESCRIPTION_LENGTH} = 430 bytes. Declared here so the writer's contract is
     * traceable from the service that decides its content; the bytes themselves are the writer's.
     */
    public static final int REJECT_RECORD_LENGTH =
            SOURCE_IMAGE_LENGTH + FAIL_REASON_LENGTH + FAIL_REASON_DESCRIPTION_LENGTH;

    /** Width of the batch processing timestamp built at lines 692 to 705. */
    public static final int BATCH_TIMESTAMP_LENGTH = 26;

    /** The reason code a record carries while it is still acceptable, from line 208. */
    public static final int NO_REJECT_REASON_CODE = 0;

    /** Return code of a run that rejected nothing, the value the program starts with. */
    public static final int RETURN_CODE_SUCCESS = 0;

    /** Return code of a run that rejected at least one record, from lines 229 to 230. */
    public static final int RETURN_CODE_REJECTS_PRESENT = 4;

    /**
     * The description a record carries while it is still acceptable. Line 209 moves {@code SPACES}
     * into the seventy-six character field; the width belongs to the reject writer, so the reset value
     * exposed here is the blank description rather than a padded image.
     */
    public static final String BLANK_FAIL_REASON_DESCRIPTION = "";

    /** Initial value of the one-character create flag declared at lines 189 to 190. */
    public static final String CREATE_FLAG_UNSET = "N";

    /** Value the create flag takes when the category-balance read finds no row, from line 478. */
    public static final String CREATE_FLAG_SET = "Y";

    /** DD name of the sequential daily-transaction input, from line 29. */
    public static final String DALYTRAN_DD = "DALYTRAN";

    /** DD name of the indexed transaction output, from line 34. */
    public static final String TRANFILE_DD = "TRANFILE";

    /** DD name of the indexed card cross-reference input, from line 40. */
    public static final String XREFFILE_DD = "XREFFILE";

    /** DD name of the sequential reject output, from line 46. */
    public static final String DALYREJS_DD = "DALYREJS";

    /** DD name of the indexed account master, opened for update, from line 51. */
    public static final String ACCTFILE_DD = "ACCTFILE";

    /** DD name of the indexed category-balance file, opened for update, from line 57. */
    public static final String TCATBALF_DD = "TCATBALF";

    private static final Logger LOG = LoggerFactory.getLogger(TransactionPostingService.class);

    private static final String START_OF_EXECUTION = "START OF EXECUTION OF PROGRAM CBTRN02C";

    private static final String END_OF_EXECUTION = "END OF EXECUTION OF PROGRAM CBTRN02C";

    private static final String TRANSACTIONS_PROCESSED_LABEL = "TRANSACTIONS PROCESSED :";

    private static final String TRANSACTIONS_REJECTED_LABEL = "TRANSACTIONS REJECTED  :";

    private static final String ABENDING_PROGRAM = "ABENDING PROGRAM";

    private static final String OPEN_DALYTRAN_FAILURE = "ERROR OPENING DALYTRAN";

    private static final String OPEN_DALYREJS_FAILURE = "ERROR OPENING DALY REJECTS FILE";

    private static final String READ_DALYTRAN_FAILURE = "ERROR READING DALYTRAN FILE";

    private static final String READ_TCATBALF_FAILURE = "ERROR READING TRANSACTION BALANCE FILE";

    private static final String WRITE_TCATBALF_FAILURE = "ERROR WRITING TRANSACTION BALANCE FILE";

    private static final String REWRITE_TCATBALF_FAILURE =
            "ERROR REWRITING TRANSACTION BALANCE FILE";

    private static final String WRITE_TRANFILE_FAILURE = "ERROR WRITING TO TRANSACTION FILE";

    private static final String TCATBAL_NOT_FOUND_PREFIX = "TCATBAL record not found for key : ";

    private static final String TCATBAL_NOT_FOUND_SUFFIX = ".. Creating.";

    private static final String OPEN_INPUT = "OPEN INPUT";

    private static final String OPEN_OUTPUT = "OPEN OUTPUT";

    private static final String OPEN_IO = "OPEN I-O";

    private static final String CLOSE = "CLOSE";

    private static final String READ = "READ";

    private static final String WRITE = "WRITE";

    private static final String REWRITE = "REWRITE";

    /**
     * The value {@code INITIALIZE} gives the category balance at line 504 before the amount is added.
     * Scale two because the receiving field is declared {@code PIC S9(09)V99} at
     * {@code app/cpy/CVTRA01Y.cpy} line 9.
     */
    private static final BigDecimal INITIALIZED_CATEGORY_BALANCE = new BigDecimal("0.00");

    /** Divisor turning a nanosecond-of-second into the two-digit hundredths field of line 700. */
    private static final int NANOS_PER_HUNDREDTH = 10_000_000;

    /** Number of characters the timestamp tail contributes, forced to a literal at line 701. */
    private static final String BATCH_TIMESTAMP_TAIL = "0000";

    /** Width of the two-byte {@code FILE STATUS} field declared at lines 131 to 133. */
    private static final int FILE_STATUS_WIDTH = 2;

    /** Width of the four-character status image the display paragraph builds at lines 717 to 724. */
    private static final int IO_STATUS_IMAGE_WIDTH = 4;

    /** First byte that forces the non-standard branch of the display paragraph at line 716. */
    private static final char NON_STANDARD_STATUS_FIRST_BYTE = '9';

    /** Leading pair the standard branch of the display paragraph supplies at lines 723 to 724. */
    private static final String STANDARD_STATUS_IMAGE_PREFIX = "00";

    /** Length of the ten-character date prefix the expiry comparison uses at line 414. */
    private static final int EXPIRY_COMPARISON_LENGTH = 10;

    /** The blank a fixed-width alphanumeric field is padded with, as {@code MOVE SPACES} leaves it. */
    private static final char COBOL_SPACE = ' ';

    private static final int KEYSET_PAGE_SIZE = BoundedKeysetIterator.DEFAULT_PAGE_SIZE;

    /**
     * The record this program rewrites, named on a version conflict so a caller can say which record
     * moved. Declared locally, as the two other services that raise the same conflict do, because the
     * name is the operator-facing word for the record and not a shared identifier.
     */
    private static final String ACCOUNT_ENTITY_NAME = "Account";

    private final DailyTransactionRepository dailyTransactionRepository;

    private final TransactionRepository transactionRepository;

    private final AccountRepository accountRepository;

    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    private final RecordWriter recordWriter;

    private final AbendService abendService;

    private final PostingRecordTransactionBoundary postingRecordTransactionBoundary;

    private final Clock clock;

    /**
     * Creates the service over the five datasets the program opens and the two collaborators it needs
     * to abend and to stamp a processing timestamp.
     *
     * @param dailyTransactionRepository           stands in for the {@value #DALYTRAN_DD} sequential
     *                                             input of line 29
     * @param transactionRepository                stands in for the {@value #TRANFILE_DD} indexed
     *                                             output of line 34
     * @param accountRepository                    stands in for the {@value #ACCTFILE_DD} account
     *                                             master of line 51, opened for update
     * @param cardCrossReferenceRepository         stands in for the {@value #XREFFILE_DD} indexed
     *                                             input of line 40
     * @param transactionCategoryBalanceRepository stands in for the {@value #TCATBALF_DD} file of
     *                                             line 57, opened for update
     * @param recordWriter                         explicit create-only write boundary
     * @param abendService                         the estate's single abend path, reached from
     *                                             {@code 9999-ABEND-PROGRAM} at lines 707 to 711
     * @param postingRecordTransactionBoundary     the per-record unit of work of lines 440 to 442, held
     *                                             on its own bean so the mainline loop cannot bypass it
     * @param clock                                the time source the processing timestamp is built
     *                                             from at lines 692 to 705
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public TransactionPostingService(
            final DailyTransactionRepository dailyTransactionRepository,
            final TransactionRepository transactionRepository,
            final AccountRepository accountRepository,
            final CardCrossReferenceRepository cardCrossReferenceRepository,
            final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
            final RecordWriter recordWriter,
            final AbendService abendService,
            final PostingRecordTransactionBoundary postingRecordTransactionBoundary,
            final Clock clock) {
        this.dailyTransactionRepository = Objects.requireNonNull(dailyTransactionRepository,
                "dailyTransactionRepository must not be null");
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "transactionRepository must not be null");
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "accountRepository must not be null");
        this.cardCrossReferenceRepository = Objects.requireNonNull(cardCrossReferenceRepository,
                "cardCrossReferenceRepository must not be null");
        this.transactionCategoryBalanceRepository = Objects.requireNonNull(
                transactionCategoryBalanceRepository,
                "transactionCategoryBalanceRepository must not be null");
        this.recordWriter = Objects.requireNonNull(recordWriter, "recordWriter must not be null");
        this.abendService = Objects.requireNonNull(abendService, "abendService must not be null");
        this.postingRecordTransactionBoundary = Objects.requireNonNull(
                postingRecordTransactionBoundary,
                "postingRecordTransactionBoundary must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * The outcome of one record: what the program decided, and every artefact that decision produced.
     *
     * <p>{@code posted} and {@code reasonCode} are separate on purpose and must not be collapsed into
     * one another. A rejected record always carries a non-zero reason code, but a <em>posted</em>
     * record may also carry one: reject code 109 is set by the account-rewrite invalid-key arm at line
     * 556, after the mainline has already committed to posting, and the transaction-file write still
     * runs. Anything deciding whether to emit a reject record must therefore test {@code posted},
     * never {@code reasonCode}.
     *
     * <p>The pairing of {@code sourceRecord} with {@code rejectReason} is exactly what the reject
     * writer consumes to build its 430-byte record, which is why both survive on the result rather
     * than being turned into bytes here.
     *
     * @param reasonCode             the value of the four-digit fail reason of line 181, zero while
     *                               the record is acceptable
     * @param reasonDescription      the description text of line 182, blank while the record is
     *                               acceptable, never padded to its seventy-six character width here
     * @param rejectReason           the typed reason, or {@code null} while the record is acceptable
     * @param posted                 {@code true} when the three posting stages ran, {@code false} when
     *                               the record was rejected by validation
     * @param sourceRecord           the daily-transaction record just processed, for the reject writer
     * @param postedTransaction      the synthesized transaction, or {@code null} on a rejected record
     * @param updatedAccount         the updated account, or {@code null} on a rejected record
     * @param updatedCategoryBalance the created or updated category balance, or {@code null} on a
     *                               rejected record
     */
    public record PostingResult(int reasonCode,
                                String reasonDescription,
                                RejectReason rejectReason,
                                boolean posted,
                                DailyTransaction sourceRecord,
                                Transaction postedTransaction,
                                Account updatedAccount,
                                TransactionCategoryBalance updatedCategoryBalance) {

        /**
         * Validates the invariants the mainline guarantees.
         *
         * @throws NullPointerException     if the source record is absent
         * @throws IllegalArgumentException if the description is absent, or if a rejected record
         *                                  carries no typed reason
         */
        public PostingResult {
            Objects.requireNonNull(sourceRecord, "sourceRecord must not be null");
            Objects.requireNonNull(reasonDescription, "reasonDescription must not be null");
            if (!posted && rejectReason == null) {
                throw new IllegalArgumentException(
                        "a rejected record must carry the reason that rejected it");
            }
        }

        /**
         * Whether this record was rejected by validation and so needs a reject record written.
         *
         * @return {@code true} when the record was rejected, the exact complement of {@code posted}
         */
        public boolean rejected() {
            return !posted;
        }
    }

    /**
     * The outcome of a whole run: the three counters the program displays and the return code it sets.
     *
     * <p>The return code is a partial-success signal, not a failure signal. Lines 228 to 230 move 4
     * into it when the reject count exceeds zero, having already closed every file and displayed both
     * counts, and the run itself completes normally. A step translating this must not treat 4 as a
     * failed step.
     *
     * @param transactionsProcessed the count of line 185, incremented once per record read
     * @param transactionsPosted    the records that ran the three posting stages, which the legacy
     *                              carries implicitly as the difference between the two counters
     * @param transactionsRejected  the count of line 186, incremented once per rejected record
     * @param returnCode            {@value #RETURN_CODE_REJECTS_PRESENT} when anything was rejected,
     *                              otherwise {@value #RETURN_CODE_SUCCESS}
     */
    public record PostingRunSummary(long transactionsProcessed,
                                    long transactionsPosted,
                                    long transactionsRejected,
                                    int returnCode) {

        /**
         * Validates the counter arithmetic the mainline guarantees.
         *
         * @throws IllegalArgumentException if a count is negative, if the parts do not sum to the
         *                                  whole, or if the return code contradicts the reject count
         */
        public PostingRunSummary {
            if (transactionsProcessed < 0 || transactionsPosted < 0 || transactionsRejected < 0) {
                throw new IllegalArgumentException("run counters must not be negative");
            }
            if (transactionsPosted + transactionsRejected != transactionsProcessed) {
                throw new IllegalArgumentException("posted plus rejected must equal processed but "
                        + transactionsPosted + " plus " + transactionsRejected + " is not "
                        + transactionsProcessed);
            }
            int expected = transactionsRejected > 0
                    ? RETURN_CODE_REJECTS_PRESENT
                    : RETURN_CODE_SUCCESS;
            if (returnCode != expected) {
                throw new IllegalArgumentException("a run rejecting " + transactionsRejected
                        + " record(s) must return " + expected + " but returned " + returnCode);
            }
        }

        /**
         * Whether the run rejected anything and so reports a partial success.
         *
         * @return {@code true} when the return code is {@value #RETURN_CODE_REJECTS_PRESENT}
         */
        public boolean partialSuccess() {
            return returnCode == RETURN_CODE_REJECTS_PRESENT;
        }
    }

    /**
     * What the validation cascade resolved: the reason that rejected the record, if any, together with
     * the two records the posting stages then reuse.
     *
     * <p>The legacy holds these in working storage - the cross-reference read at line 383 and the
     * account read at line 395 both land in areas that the posting paragraphs read again at lines 469
     * and 554 - so carrying them forward is what keeps the account updated at line 547 the very same
     * record whose limit was tested at line 407.
     *
     * @param rejectReason    the reason that rejected the record, or {@code null} when it is acceptable
     * @param crossReference  the cross-reference row, or {@code null} when the card number was invalid
     * @param account         the account row, or {@code null} when no account was resolved
     */
    private record ValidationOutcome(RejectReason rejectReason,
                                     CardCrossReference crossReference,
                                     Account account) {
    }

    // -----------------------------------------------------------------------------------------------
    // PROCEDURE DIVISION mainline - lines 193 to 234
    // -----------------------------------------------------------------------------------------------

    /**
     * Runs the whole posting cycle over a supplied source, reproducing the mainline of lines 193 to
     * 234: the six opens, the read loop with its per-record reset and its post-or-reject branch, the
     * six closes, the two counter displays, and the return code.
     *
     * <p>The source is supplied rather than read here because the legacy reads a <em>sequential</em>
     * dataset, and the order in which records arrive is observable: the overlimit test at line 407
     * measures a cycle total that earlier records in the same run have already moved, so a different
     * arrival order can reject a different set of records. A caller holding the records in dataset
     * order therefore keeps that order intact by passing them through.
     *
     * <p>End of file is not an error and is not treated as one. The read paragraph returns an empty
     * result when the source is exhausted, exactly as the legacy normalises file status {@code 10} to
     * its end-of-file outcome at lines 351 to 352 and sets the loop's sentinel at line 361, and the
     * abend path is reached only by the genuinely-failed read of lines 363 to 366.
     *
     * <p>A rejected record is handed to {@code rejectSink}, which is where the legacy performed its
     * own reject write at line 215. Nothing is thrown for a reject: the run completes and reports
     * {@value #RETURN_CODE_REJECTS_PRESENT}.
     *
     * @param  source     the daily-transaction records in dataset order
     * @param  rejectSink where each rejected result is handed for the reject writer to emit
     * @return the two legacy counters, the posted count, and the resulting return code
     * @throws AbendException if a read or a write fails for any reason other than end of file
     */
    public PostingRunSummary postAll(final Iterable<DailyTransaction> source,
            final Consumer<PostingResult> rejectSink) {
        LOG.info(START_OF_EXECUTION);

        openDailyTransactionInput(source);
        openTransactionOutput();
        openCrossReferenceInput();
        openRejectOutput(rejectSink);
        openAccountUpdate();
        openCategoryBalanceUpdate();

        long processed = 0L;
        long posted = 0L;
        long rejected = 0L;

        final Iterator<DailyTransaction> cursor = source.iterator();
        for (Optional<DailyTransaction> next = getNextDailyTransaction(cursor);
                next.isPresent();
                next = getNextDailyTransaction(cursor)) {
            processed++;
            final PostingResult result = post(next.get());
            if (result.posted()) {
                posted++;
            } else {
                rejected++;
                writeRejectRecord(result, rejectSink);
            }
        }

        closeDailyTransactionInput();
        closeTransactionOutput();
        closeCrossReferenceInput();
        closeRejectOutput();
        closeAccountUpdate();
        closeCategoryBalanceUpdate();

        LOG.info("{}{}", TRANSACTIONS_PROCESSED_LABEL, processed);
        LOG.info("{}{}", TRANSACTIONS_REJECTED_LABEL, rejected);

        // Lines 229 to 230: a reject makes the run a partial success, never a failure.
        final int returnCode = rejected > 0 ? RETURN_CODE_REJECTS_PRESENT : RETURN_CODE_SUCCESS;

        LOG.info(END_OF_EXECUTION);
        return new PostingRunSummary(processed, posted, rejected, returnCode);
    }

    /**
     * Runs the posting cycle over the staged daily-transaction rows, reading them in ascending
     * business-key order.
     *
     * <p>This is the convenience entry point for a caller that has already staged the dataset into the
     * daily-transaction table and has no reader of its own. The ordering caveat of
     * {@link #postAll(Iterable, Consumer)} applies and is why the order is stated rather than left
     * implicit: a relational read has no physical dataset order to inherit, so the business key - the
     * only total order the row carries - stands in for it, which reproduces the legacy sequence
     * whenever the staged rows were loaded from a key-ordered dataset.
     *
     * @param  rejectSink where each rejected result is handed for the reject writer to emit
     * @return the two legacy counters, the posted count, and the resulting return code
     * @throws AbendException if the staged dataset cannot be read, or if a write fails
     */
    public PostingRunSummary postStagedDailyTransactions(final Consumer<PostingResult> rejectSink) {
        final Iterable<DailyTransaction> staged = () -> new BoundedKeysetIterator<>(
                "", KEYSET_PAGE_SIZE, this::loadStagedPage,
                DailyTransaction::getDalytranId, Comparator.naturalOrder());
        return postAll(staged, rejectSink);
    }

    private List<DailyTransaction> loadStagedPage(
            final String cursor, final Integer pageSize) {
        try {
            return this.dailyTransactionRepository
                    .findByDalytranIdGreaterThanOrderByDalytranIdAsc(
                            cursor, Limit.of(pageSize.intValue()));
        } catch (final DataAccessException unreadable) {
            throw abendAfterIoFailure(
                    READ_DALYTRAN_FAILURE, READ, DALYTRAN_DD, unreadable);
        }
    }

    /**
     * Processes one record: reset, validate, then post or reject. This is the per-record commit
     * boundary, so the three posting stages of lines 440 to 442 either all take effect or none does.
     *
     * <p>The reset of lines 208 to 209 is the first thing that happens, as locals rather than as
     * state. Nothing in this class holds a reason code, a description, a counter or a create flag
     * between calls; a field would let one record's verdict decide the next one's.
     *
     * <p>No legacy rollback arm is translated here, because the legacy member has none - the estate's
     * only explicit rollback belongs to the online account-update program. A reject is therefore an
     * ordinary return carrying a verdict, not a rollback: the record's three posting stages never ran,
     * so there is nothing to undo and the transaction commits. What still rolls back is a failure: the
     * work runs inside {@link PostingRecordTransactionBoundary}, so any unchecked exception that escapes
     * it - an abend, a file-status failure, or an optimistic-lock conflict on the account's version
     * attribute - marks that record's transaction for rollback under the framework's own rule, and the
     * record's stages are discarded together. That is the framework's rollback, not a legacy one, and
     * none of those three is translated into a handled arm because the legacy member handles none of
     * them.
     *
     * <p>The boundary is a separate bean and the call below is the only per-record path, so the mainline
     * loop of {@link #postAll(Iterable, Consumer)} obtains the same transaction this entry point does.
     * A transactional annotation on this method could not have achieved that: the loop's call would have
     * been a self-invocation, which reaches the target without consulting a proxy.
     *
     * @param  record the daily-transaction record to post
     * @return the verdict together with every artefact it produced
     * @throws NullPointerException if the record is absent
     * @throws AbendException       if a category-balance or transaction-file operation fails
     */
    public PostingResult post(final DailyTransaction record) {
        Objects.requireNonNull(record, "record must not be null");
        return this.postingRecordTransactionBoundary.execute(() -> postOneRecord(record));
    }

    /**
     * The body of one record's processing, run inside the per-record transaction the boundary opened.
     *
     * @param  record the daily-transaction record to post, already checked for presence
     * @return the verdict together with every artefact it produced
     * @throws AbendException if a category-balance or transaction-file operation fails
     */
    private PostingResult postOneRecord(final DailyTransaction record) {

        // Lines 208 to 209: MOVE 0 TO the fail reason, MOVE SPACES TO its description. Per record,
        // every record, before anything is looked at.
        int reasonCode = NO_REJECT_REASON_CODE;
        String reasonDescription = BLANK_FAIL_REASON_DESCRIPTION;

        final ValidationOutcome validated = validateTransaction(record);
        reasonCode = reasonCodeOf(validated.rejectReason());
        reasonDescription = reasonDescriptionOf(validated.rejectReason());

        // Line 211: IF WS-VALIDATION-FAIL-REASON = 0 - post; otherwise the mainline rejects.
        if (reasonCode == NO_REJECT_REASON_CODE) {
            return postTransaction(record, validated.crossReference(), validated.account());
        }

        LOG.warn("record rejected program={} transactionRef={} reasonCode={} reason={}",
                PROGRAM_NAME, SensitiveLogRedactor.redact(record.getDalytranId()),
                reasonCode, reasonDescription);
        return new PostingResult(reasonCode, reasonDescription, validated.rejectReason(), false,
                record, null, null, null);
    }

    // -----------------------------------------------------------------------------------------------
    // Resource acquisition and release - the twelve open and close paragraphs
    //
    // Each of these twelve paragraphs is the same eighteen-line skeleton in the source: issue the
    // OPEN or CLOSE, normalise its status, and on anything other than success display the operation's
    // own literal, display the status, and abend. In the target there is no dataset handle to acquire:
    // the five datasets are repositories whose connection the container owns, and the reject
    // destination belongs to the reject writer. What each paragraph still owns, and what these methods
    // therefore carry, is its own diagnostic and its own failure verdict - and for the two resources
    // that genuinely arrive per run, the record source and the reject sink, a real check with the
    // legacy literal behind it. The acquisition is delegated, never invented: a probe query issued
    // only to have something to fail on would be work the legacy never did.
    // -----------------------------------------------------------------------------------------------

    /**
     * {@code 0000-DALYTRAN-OPEN}, lines 236 to 252: acquires the sequential daily-transaction input.
     *
     * <p>The source is the one thing this open genuinely acquires, so an absent source takes the
     * legacy failure arm with its own literal, {@code ERROR OPENING DALYTRAN}.
     *
     * @param  source the records the run will read
     * @throws AbendException if no source was supplied
     */
    private void openDailyTransactionInput(final Iterable<DailyTransaction> source) {
        if (source == null) {
            throw abendAfterIoFailure(OPEN_DALYTRAN_FAILURE, OPEN_INPUT, DALYTRAN_DD, null);
        }
        announceAcquired(OPEN_INPUT, DALYTRAN_DD);
    }

    /**
     * {@code 0100-TRANFILE-OPEN}, lines 254 to 270: acquires the indexed transaction output, whose
     * failure literal is {@code ERROR OPENING TRANSACTION FILE}.
     */
    private void openTransactionOutput() {
        announceAcquired(OPEN_OUTPUT, TRANFILE_DD);
    }

    /**
     * {@code 0200-XREFFILE-OPEN}, lines 273 to 289: acquires the indexed cross-reference input, whose
     * failure literal is {@code ERROR OPENING CROSS REF FILE}.
     */
    private void openCrossReferenceInput() {
        announceAcquired(OPEN_INPUT, XREFFILE_DD);
    }

    /**
     * {@code 0300-DALYREJS-OPEN}, lines 291 to 307: acquires the sequential reject output.
     *
     * <p>The sink is where a reject leaves this service, so an absent sink takes the legacy failure arm
     * with its own literal, {@code ERROR OPENING DALY REJECTS FILE}. Failing here rather than at the
     * first reject matters: the legacy opened this file before reading a single record, so a run that
     * cannot record its rejects never starts.
     *
     * @param  rejectSink where rejected results will be handed
     * @throws AbendException if no sink was supplied
     */
    private void openRejectOutput(final Consumer<PostingResult> rejectSink) {
        if (rejectSink == null) {
            throw abendAfterIoFailure(OPEN_DALYREJS_FAILURE, OPEN_OUTPUT, DALYREJS_DD, null);
        }
        announceAcquired(OPEN_OUTPUT, DALYREJS_DD);
    }

    /**
     * {@code 0400-ACCTFILE-OPEN}, lines 309 to 325: acquires the account master for update - the
     * legacy {@code OPEN I-O} - whose failure literal is {@code ERROR OPENING ACCOUNT MASTER FILE}.
     */
    private void openAccountUpdate() {
        announceAcquired(OPEN_IO, ACCTFILE_DD);
    }

    /**
     * {@code 0500-TCATBALF-OPEN}, lines 327 to 343: acquires the category-balance file for update,
     * whose failure literal is {@code ERROR OPENING TRANSACTION BALANCE FILE}. The update mode is what
     * lets the category-balance stage both create and rewrite a row.
     */
    private void openCategoryBalanceUpdate() {
        announceAcquired(OPEN_IO, TCATBALF_DD);
    }

    /**
     * {@code 9000-DALYTRAN-CLOSE}, lines 582 to 598: releases the daily-transaction input, whose
     * failure literal is {@code ERROR CLOSING DALYTRAN FILE}.
     */
    private void closeDailyTransactionInput() {
        announceReleased(DALYTRAN_DD);
    }

    /**
     * {@code 9100-TRANFILE-CLOSE}, lines 600 to 616: releases the transaction output, whose failure
     * literal is {@code ERROR CLOSING TRANSACTION FILE}.
     */
    private void closeTransactionOutput() {
        announceReleased(TRANFILE_DD);
    }

    /**
     * {@code 9200-XREFFILE-CLOSE}, lines 619 to 635: releases the cross-reference input, whose failure
     * literal is {@code ERROR CLOSING CROSS REF FILE}.
     */
    private void closeCrossReferenceInput() {
        announceReleased(XREFFILE_DD);
    }

    /**
     * {@code 9300-DALYREJS-CLOSE}, lines 637 to 653: releases the reject output, whose failure literal
     * is {@code ERROR CLOSING DAILY REJECTS FILE}.
     *
     * <p>A source anomaly worth recording rather than reproducing: on its failure arm the legacy
     * paragraph moves the <em>cross-reference</em> file status into the display area at line 649 rather
     * than the reject file's own, so the status it displays belongs to a different file. Nothing is
     * carried across here, so the defect has no expression to inherit.
     */
    private void closeRejectOutput() {
        announceReleased(DALYREJS_DD);
    }

    /**
     * {@code 9400-ACCTFILE-CLOSE}, lines 655 to 671: releases the account master, whose failure literal
     * is {@code ERROR CLOSING ACCOUNT FILE}.
     */
    private void closeAccountUpdate() {
        announceReleased(ACCTFILE_DD);
    }

    /**
     * {@code 9500-TCATBALF-CLOSE}, lines 674 to 690: releases the category-balance file, whose failure
     * literal is {@code ERROR CLOSING TRANSACTION BALANCE FILE}.
     */
    private void closeCategoryBalanceUpdate() {
        announceReleased(TCATBALF_DD);
    }

    private static void announceAcquired(final String mode, final String resourceName) {
        LOG.debug("{} acquired resource={} mode={}", PROGRAM_NAME, resourceName, mode);
    }

    private static void announceReleased(final String resourceName) {
        LOG.debug("{} released resource={} mode={}", PROGRAM_NAME, resourceName, CLOSE);
    }

    // -----------------------------------------------------------------------------------------------
    // The read paragraph - lines 345 to 369
    // -----------------------------------------------------------------------------------------------

    /**
     * {@code 1000-DALYTRAN-GET-NEXT}, lines 345 to 369: delivers the next record, or nothing at end of
     * file.
     *
     * <p>The paragraph is a three-way branch, and keeping all three arms distinct is the point. Status
     * {@code 00} normalises to the success outcome at line 348 and the record is processed; status
     * {@code 10} normalises to the end-of-file outcome at line 352, which line 361 turns into the
     * loop's sentinel; anything else normalises to the error outcome at line 354 and only that arm
     * displays and abends at lines 363 to 366. Exhausting the source is the middle arm, so it returns
     * an empty result and never reaches the abend - collapsing it into the error arm would abend every
     * run at the last record.
     *
     * @param  cursor the source's cursor
     * @return the next record, or empty at end of file
     * @throws AbendException if the source fails to deliver a record for any other reason
     */
    private Optional<DailyTransaction> getNextDailyTransaction(
            final Iterator<DailyTransaction> cursor) {
        if (!cursor.hasNext()) {
            LOG.debug("{} reached end of file resource={}", PROGRAM_NAME, DALYTRAN_DD);
            return Optional.empty();
        }

        final DailyTransaction record;
        try {
            record = cursor.next();
        } catch (final DataAccessException unreadable) {
            throw abendAfterIoFailure(READ_DALYTRAN_FAILURE, READ, DALYTRAN_DD, unreadable);
        }

        if (record == null) {
            // A cursor that reports a record and then delivers nothing is the error arm, not the
            // end-of-file arm: the legacy read either filled the record area or reported a status.
            throw abendAfterIoFailure(READ_DALYTRAN_FAILURE, READ, DALYTRAN_DD, null);
        }
        return Optional.of(record);
    }

    // -----------------------------------------------------------------------------------------------
    // The validation cascade - lines 370 to 422
    // -----------------------------------------------------------------------------------------------

    /**
     * {@code 1500-VALIDATE-TRAN}, lines 370 to 378: runs the cross-reference lookup, then the account
     * lookup, but only while the record is still acceptable.
     *
     * <p>The guard at line 372 is the whole substance of this paragraph and is reproduced literally: it
     * tests the fail reason against zero, and its {@code ELSE} at lines 374 to 375 is a bare
     * {@code CONTINUE}. So a card number that failed the first lookup stops the cascade there and
     * <strong>the account is never read</strong> - which is why reject code 100 and reject code 101 are
     * mutually exclusive no matter how bad the record is.
     *
     * <p>The comment at line 377 invites further validations. None is added: this class validates
     * exactly what the source validates.
     *
     * @param  record the record to validate
     * @return the reason that rejected it, if any, with the rows the posting stages will reuse
     */
    private ValidationOutcome validateTransaction(final DailyTransaction record) {
        final ValidationOutcome afterCardLookup = lookupCrossReference(record);

        // Line 372: IF WS-VALIDATION-FAIL-REASON = 0.
        if (reasonCodeOf(afterCardLookup.rejectReason()) == NO_REJECT_REASON_CODE) {
            return lookupAccount(record, afterCardLookup.crossReference());
        }

        // Lines 374 to 375: ELSE CONTINUE. The account lookup is skipped entirely.
        return afterCardLookup;
    }

    /**
     * {@code 1500-A-LOOKUP-XREF}, lines 380 to 392: resolves the card number through the
     * cross-reference.
     *
     * <p>Line 382 moves the daily transaction's card number into the record key and line 383 reads the
     * cross-reference by it. The card number is that file's key, so the lookup is a keyed read and its
     * {@code INVALID KEY} arm at lines 384 to 387 - an empty result here - is reject code 100. The
     * {@code NOT INVALID KEY} arm at lines 388 to 390 is a bare {@code CONTINUE}.
     *
     * <p>There is no status check and no abend in this paragraph. A missing card number is a business
     * rejection, not an I/O failure, and the daily-transaction table deliberately carries no foreign
     * key to the cross-reference so that this rejection stays reachable at all.
     *
     * @param  record the record whose card number is being resolved
     * @return the resolved cross-reference, or reject code 100
     */
    private ValidationOutcome lookupCrossReference(final DailyTransaction record) {
        final Optional<CardCrossReference> found =
                this.cardCrossReferenceRepository.findById(record.getDalytranCardNum());

        if (found.isEmpty()) {
            return new ValidationOutcome(RejectReason.INVALID_CARD_NUMBER, null, null);
        }
        return new ValidationOutcome(null, found.get(), null);
    }

    /**
     * {@code 1500-B-LOOKUP-ACCT}, lines 393 to 422: resolves the account and applies the two limit
     * tests.
     *
     * <p>Line 394 moves the cross-reference's account identifier into the record key and line 395 reads
     * the account by it. The {@code INVALID KEY} arm at lines 396 to 399 is reject code 101.
     *
     * <p>The {@code NOT INVALID KEY} arm then runs <strong>two consecutive unguarded blocks</strong>,
     * and their order is contractual:
     * <ol>
     *   <li>Lines 407 to 413 compare the credit limit against the temporary balance and, when the limit
     *       is the smaller, set reject code 102.</li>
     *   <li>Lines 414 to 420 compare the expiration date against the origination date and, when the
     *       expiration is the earlier, set reject code 103.</li>
     * </ol>
     * There is no {@code ELSE} between them, no early exit, and no guard on the second: a record that
     * is both over limit and past expiry has 102 written first and then <strong>overwritten by
     * 103</strong>. Guarding the second block, or swapping the two, would emit 102 for such a record
     * and the byte comparison against the expected reject file would fail. The overwrite is
     * intentional and is preserved exactly.
     *
     * <p>There is no status check and no abend in this paragraph either, for the same reason as the
     * cross-reference lookup.
     *
     * @param  record         the record being validated
     * @param  crossReference the cross-reference naming the account
     * @return the reason that rejected the record, if any, with the account it resolved
     */
    private ValidationOutcome lookupAccount(final DailyTransaction record,
            final CardCrossReference crossReference) {
        final Optional<Account> found =
                this.accountRepository.findById(crossReference.getXrefAcctId());

        if (found.isEmpty()) {
            return new ValidationOutcome(RejectReason.ACCOUNT_NOT_FOUND_ON_READ, crossReference,
                    null);
        }

        final Account account = found.get();
        RejectReason rejectReason = null;

        // Block one, lines 407 to 413. Unguarded.
        final BigDecimal temporaryBalance = computeTemporaryBalance(account, record);
        if (account.getAcctCreditLimit().compareTo(temporaryBalance) < 0) {
            rejectReason = RejectReason.OVERLIMIT_TRANSACTION;
        }

        // Block two, lines 414 to 420. Also unguarded, and deliberately NOT an else of block one:
        // when both conditions hold, this assignment overwrites the one above and 103 is the reason
        // the record is rejected with. That precedence is the legacy contract.
        if (accountExpirationDate(account).compareTo(originationDate(record)) < 0) {
            rejectReason = RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION;
        }

        return new ValidationOutcome(rejectReason, crossReference, account);
    }

    /**
     * Lines 403 to 405: the temporary balance the overlimit test measures against the credit limit.
     *
     * <p>The expression is current-cycle credit, <em>minus</em> current-cycle debit, <em>plus</em> the
     * transaction amount, and it is evaluated strictly left to right in exactly that sequence. It is
     * not rearranged, factored or reassociated, and the reason is not stylistic: the receiving field at
     * line 187 is declared with nine integer digits and two decimals - the width
     * {@link ZonedDecimalCodec#TRANSACTION_AMOUNT_WIDTH} names - and a store into it truncates, which
     * makes the arithmetic non-associative. Two algebraically identical orderings can therefore differ
     * by a cent, and a cent decides whether this record is rejected as over limit.
     *
     * <p>The scaling is the codec's, never this class's, so the truncating policy is applied in exactly
     * one place in the module.
     *
     * @param  account the account whose cycle totals are being measured
     * @param  record  the record whose amount is being added
     * @return the temporary balance at scale two
     */
    private static BigDecimal computeTemporaryBalance(final Account account,
            final DailyTransaction record) {
        final BigDecimal leftToRight = account.getAcctCurrCycCredit()
                .subtract(account.getAcctCurrCycDebit())
                .add(record.getDalytranAmt());
        return ZonedDecimalCodec.toMonetaryScale(leftToRight);
    }

    /**
     * The account expiration date as the ten-character field of {@code app/cpy/CVACT01Y.cpy} line 11
     * holds it.
     *
     * <p>That field is the misspelled {@code ACCT-EXPIRAION-DATE}; the Java property spells it
     * correctly while the record layout keeps the original spelling, so the byte positions are
     * untouched and only the name differs.
     *
     * @param  account the account being tested
     * @return the expiration date as a ten-character view
     */
    private static String accountExpirationDate(final Account account) {
        return fixedWidthView(account.getAcctExpirationDate(), EXPIRY_COMPARISON_LENGTH);
    }

    /**
     * The origination date as line 414 reads it: the first ten characters of the twenty-six character
     * origination timestamp.
     *
     * <p>This is COBOL reference modification of a named fixed-width field, not a slice of a record
     * image - the timestamp field is {@code PIC X(26)} and always holds twenty-six bytes, so its first
     * ten are always a complete {@code YYYY-MM-DD}. Taking the same ten characters here is what makes
     * the comparison in {@link #lookupAccount(DailyTransaction, CardCrossReference)} a character
     * comparison rather than a date comparison, which is the whole point: no date is parsed, no
     * calendar type is constructed, and no time zone can intrude. Both operands are zero-padded
     * {@code YYYY-MM-DD} text, so their character order and their calendar order are the same order.
     *
     * @param  record the record whose origination timestamp is being read
     * @return the origination date as a ten-character view
     */
    private static String originationDate(final DailyTransaction record) {
        return fixedWidthView(record.getDalytranOrigTs(), EXPIRY_COMPARISON_LENGTH);
    }

    /**
     * Renders a value as a fixed-width alphanumeric field of the given width would hold it: truncated
     * when longer, blank-padded when shorter, because a COBOL field of that width is always exactly
     * that wide however the value that reached it was sized.
     *
     * @param  value the value to view, treated as all blanks when absent
     * @param  width the field width
     * @return exactly {@code width} characters
     */
    private static String fixedWidthView(final String value, final int width) {
        final String present = value == null ? "" : value;
        if (present.length() == width) {
            return present;
        }
        if (present.length() > width) {
            return present.substring(0, width);
        }
        final StringBuilder padded = new StringBuilder(width).append(present);
        while (padded.length() < width) {
            padded.append(COBOL_SPACE);
        }
        return padded.toString();
    }

    // -----------------------------------------------------------------------------------------------
    // The posting stages - lines 424 to 579
    // -----------------------------------------------------------------------------------------------

    /**
     * {@code 2000-POST-TRANSACTION}, lines 424 to 444: builds the posted transaction and runs the three
     * persistence stages.
     *
     * <p>Lines 425 to 435 move eleven fields from the daily-transaction record into the transaction
     * record. Four of those eleven cross a naming boundary that is the likeliest mapping error in the
     * package: the source's merchant fields carry the {@code DALYTRAN-} prefix and the target's do not,
     * so a prefixed getter feeds an unprefixed column in each of the four cases - identifier, name,
     * city and postal code. The two layouts are otherwise byte-for-byte identical, which is exactly
     * what makes the asymmetry easy to miss and impossible to detect by eye afterwards.
     *
     * <p>The two timestamps are treated differently and must stay that way. Line 436
     * <strong>copies</strong> the origination timestamp verbatim - it records when the transaction
     * happened, which posting does not change - while lines 437 to 438 <strong>regenerate</strong> the
     * processing timestamp from the clock, because that records when posting happened, which is now.
     * Regenerating both would destroy the origination record; copying both would claim the transaction
     * was posted when it was made.
     *
     * <p>Lines 440 to 442 then run three stages in one order and one order only: <strong>category
     * balance, account, transaction file</strong>. The online bill-payment flow writes its transaction
     * first and updates its account last; the two orders are opposites and are deliberately not
     * unified.
     *
     * @param  record         the record being posted
     * @param  crossReference the cross-reference naming the account, reused from validation
     * @param  account        the account read during validation, which this posts onto
     * @return the posted result, carrying the inert reject code 109 when the account rewrite found no
     *         row
     * @throws AbendException if a category-balance or transaction-file operation fails
     */
    private PostingResult postTransaction(final DailyTransaction record,
            final CardCrossReference crossReference, final Account account) {
        final Transaction transaction = new Transaction(
                record.getDalytranId(),
                record.getDalytranTypeCd(),
                record.getDalytranCatCd(),
                record.getDalytranSource(),
                record.getDalytranDesc(),
                ZonedDecimalCodec.toMonetaryScale(record.getDalytranAmt()),
                // The four prefixed-to-unprefixed merchant moves of lines 431 to 434.
                record.getDalytranMerchantId(),
                record.getDalytranMerchantName(),
                record.getDalytranMerchantCity(),
                record.getDalytranMerchantZip(),
                record.getDalytranCardNum(),
                // Line 436: the origination timestamp is copied, byte for byte, never regenerated.
                record.getDalytranOrigTs(),
                // Lines 437 to 438: the processing timestamp is regenerated, never copied.
                getDb2FormatTimestamp());

        final TransactionCategoryBalance categoryBalance =
                updateCategoryBalance(record, crossReference);
        final Optional<RejectReason> rewriteFailure = updateAccountRecord(record, account);
        final Transaction storedTransaction = writeTransactionFile(transaction);

        final RejectReason inertReason = rewriteFailure.orElse(null);
        return new PostingResult(reasonCodeOf(inertReason), reasonDescriptionOf(inertReason),
                inertReason, true, record, storedTransaction, account, categoryBalance);
    }

    /**
     * {@code 2500-WRITE-REJECT-REC}, lines 446 to 465: hands the rejected record on for its reject
     * record to be written.
     *
     * <p>The paragraph moves the 350-byte source image into the first segment at line 447 and the
     * eighty-character validation trailer into the second at line 448, then writes the 430 bytes. Only
     * the first two of those three steps have an equivalent here, and neither of them touches a byte:
     * the source record and the typed reason travel on the result, and the writer in the batch-step
     * package assembles {@value #SOURCE_IMAGE_LENGTH} + {@value #FAIL_REASON_LENGTH} +
     * {@value #FAIL_REASON_DESCRIPTION_LENGTH} = {@value #REJECT_RECORD_LENGTH} bytes from them. That
     * split is deliberate: one place in the module owns record widths, and it is not this one.
     *
     * <p>The paragraph's own failure arm - {@code ERROR WRITING TO REJECTS FILE} at line 460, followed
     * by the status display and the abend - belongs with the write, so it belongs to the writer too. A
     * failure in the sink propagates from here untouched rather than being swallowed or downgraded.
     *
     * @param result     the rejected result, carrying the source record and the typed reason
     * @param rejectSink where the result is handed
     */
    private static void writeRejectRecord(final PostingResult result,
            final Consumer<PostingResult> rejectSink) {
        if (LOG.isWarnEnabled()) {
            LOG.warn("reject record queued program={} transactionRef={} reasonCode={} reason={}"
                            + " recordLength={}",
                    PROGRAM_NAME,
                    SensitiveLogRedactor.redact(result.sourceRecord().getDalytranId()),
                    result.reasonCode(),
                    result.reasonDescription(), REJECT_RECORD_LENGTH);
        }
        rejectSink.accept(result);
    }

    /**
     * {@code 2700-UPDATE-TCATBAL}, lines 467 to 501: reads the category balance for this account, type
     * and category, then creates or updates it.
     *
     * <p>Lines 469 to 471 build the composite key from the cross-reference's account identifier and the
     * daily transaction's type and category codes. Line 473 sets the create flag to
     * {@value #CREATE_FLAG_UNSET} and the read's {@code INVALID KEY} arm at lines 475 to 478 - an empty
     * result here - sets it to {@value #CREATE_FLAG_SET} after displaying the missing key. The flag is
     * a local, not a field, for the same reason the reason code is.
     *
     * <p><strong>A missing row is not an error.</strong> The status test at line 481 accepts
     * {@code 00} and {@code 23} alike, so {@link FileStatus#RECORD_NOT_FOUND} normalises to success
     * just as {@link FileStatus#SUCCESS} does, and only some third value reaches the display-and-abend
     * arm at lines 489 to 492. Lines 495 to 499 then branch on the flag: create, or update. Persisting
     * either one is an immediate save-and-flush, because the write status belongs to this paragraph and
     * must be observed before control reaches the account rewrite. The decision between create and update
     * is made here, in the service, from the emptiness of the read; it is not delegated to an upsert.
     *
     * @param  record         the record being posted
     * @param  crossReference the cross-reference naming the account
     * @return the created or updated category balance
     * @throws AbendException if the read or the write fails
     */
    private TransactionCategoryBalance updateCategoryBalance(final DailyTransaction record,
            final CardCrossReference crossReference) {
        final TransactionCategoryBalanceId key = new TransactionCategoryBalanceId(
                crossReference.getXrefAcctId(),
                record.getDalytranTypeCd(),
                record.getDalytranCatCd());

        String createFlag = CREATE_FLAG_UNSET;
        final Optional<TransactionCategoryBalance> existing;
        try {
            existing = this.transactionCategoryBalanceRepository.findById(key);
        } catch (final DataAccessException unreadable) {
            throw abendAfterIoFailure(READ_TCATBALF_FAILURE, READ, TCATBALF_DD, unreadable);
        }

        if (existing.isEmpty()) {
            // Lines 476 to 478: the legacy displays the key it could not find and says it is creating
            // the row. Reproduced, at a level that says the same thing without implying a failure.
            LOG.info("{}{}{}", TCATBAL_NOT_FOUND_PREFIX,
                    SensitiveLogRedactor.redact(legacyKeyImage(key)),
                    TCATBAL_NOT_FOUND_SUFFIX);
            createFlag = CREATE_FLAG_SET;
        }

        // Lines 495 to 499: IF WS-CREATE-TRANCAT-REC = 'Y' create, ELSE update.
        if (CREATE_FLAG_SET.equals(createFlag)) {
            return createCategoryBalanceRecord(record, crossReference);
        }
        return updateCategoryBalanceRecord(record, existing.get());
    }

    /**
     * {@code 2700-A-CREATE-TCATBAL-REC}, lines 503 to 524: creates the category-balance row the read
     * did not find.
     *
     * <p>Line 504 initialises the record, which zeroes the balance; lines 505 to 507 fill the composite
     * key; line 508 adds the transaction amount to that zeroed balance. So the new row's balance is the
     * amount and nothing else - it is not seeded from anywhere and it does not inherit a value. The
     * amount may be negative, and it is added as it is.
     *
     * @param  record         the record being posted
     * @param  crossReference the cross-reference naming the account
     * @return the created category balance as persisted
     * @throws AbendException if the write fails
     */
    private TransactionCategoryBalance createCategoryBalanceRecord(final DailyTransaction record,
            final CardCrossReference crossReference) {
        final BigDecimal balance = ZonedDecimalCodec.toMonetaryScale(
                INITIALIZED_CATEGORY_BALANCE.add(record.getDalytranAmt()));

        final TransactionCategoryBalance created = new TransactionCategoryBalance(
                crossReference.getXrefAcctId(),
                record.getDalytranTypeCd(),
                record.getDalytranCatCd(),
                balance);

        try {
            return this.recordWriter.insert(created);
        } catch (final DataAccessException unwritable) {
            throw abendAfterIoFailure(
                    WRITE_TCATBALF_FAILURE, WRITE, TCATBALF_DD, unwritable);
        }
    }

    /**
     * {@code 2700-B-UPDATE-TCATBAL-REC}, lines 526 to 542: updates the category-balance row the read
     * found.
     *
     * <p>Line 527 adds the transaction amount to the balance already there and line 528 rewrites the
     * row. A negative amount reduces the balance, which is what a return is supposed to do; the sign is
     * not examined here at all.
     *
     * @param  record   the record being posted
     * @param  existing the row the read found, updated in place
     * @return the updated category balance as persisted
     * @throws AbendException if the rewrite fails
     */
    private TransactionCategoryBalance updateCategoryBalanceRecord(final DailyTransaction record,
            final TransactionCategoryBalance existing) {
        existing.setTranCatBal(ZonedDecimalCodec.toMonetaryScale(
                existing.getTranCatBal().add(record.getDalytranAmt())));

        return saveCategoryBalance(existing, REWRITE_TCATBALF_FAILURE, REWRITE);
    }

    private TransactionCategoryBalance saveCategoryBalance(
            final TransactionCategoryBalance categoryBalance, final String failureLiteral,
            final String operation) {
        try {
            return this.transactionCategoryBalanceRepository.saveAndFlush(categoryBalance);
        } catch (final DataAccessException unwritable) {
            throw abendAfterIoFailure(failureLiteral, operation, TCATBALF_DD, unwritable);
        }
    }

    /**
     * The key image the missing-key diagnostic displays: the seventeen-character group of lines 93 to
     * 96 as the legacy displays it, its three parts concatenated in record order.
     *
     * @param  key the composite key that was not found
     * @return the key as one seventeen-character image
     */
    private static String legacyKeyImage(final TransactionCategoryBalanceId key) {
        return key.getTrancatAcctId() + key.getTrancatTypeCd() + key.getTrancatCd();
    }

    /**
     * {@code 2800-UPDATE-ACCOUNT-REC}, lines 545 to 560: moves the posted amount onto the account's
     * three balances and rewrites it.
     *
     * <p>Line 547 adds the amount to the current balance unconditionally. Lines 548 to 552 then branch
     * on the sign, and the branch is not what an intuition about credits and debits expects: a
     * non-negative amount is added to the current-cycle credit, and a negative amount is added
     * <strong>unchanged, still negative,</strong> to the current-cycle debit. It is not negated and its
     * magnitude is not taken, so <strong>the debit accumulator holds a negative total</strong> and is
     * meant to. The seeded fixture reaches both arms - two hundred and fifty point-of-sale purchases
     * carry positive amounts and fifty operator-originated returns carry negative ones - so this is not
     * a theoretical path. Correcting the sign here would silently change every cycle total the account
     * reports.
     *
     * <p>The rewrite's {@code INVALID KEY} arm at lines 555 to 558 sets reject code 109 and does
     * <strong>nothing else</strong>: no status is checked, no diagnostic is displayed, and no abend is
     * raised - uniquely among this member's write paragraphs. Line 560 then exits normally and the
     * caller goes straight on to the transaction-file write. So <strong>109 is inert</strong>: the
     * mainline decided to post before this stage ran and never reconsiders, so the record is counted as
     * posted, no reject record is produced, and the transaction is still written. That inertness is
     * deliberate and is preserved rather than repaired. 109 keeps its own identity even though its
     * description text is identical to 101's, because the two arise at different points and a
     * traceability row needs to tell them apart.
     *
     * <p>The repository performs one update-only statement and returns its affected-row count, which is
     * the relational equivalent of {@code REWRITE ... INVALID KEY}: one row means the rewrite completed
     * at this paragraph. The statement increments the version explicitly and clears the persistence
     * context afterward, so the managed account read during validation cannot be dirty-flushed a second
     * time.
     *
     * <p><strong>Why the row is read before the rewrite.</strong> An affected-row count of zero has two
     * possible causes - the row is gone, which is the legacy invalid-key condition, or the row is present
     * carrying a version other than the one validation read, which the legacy could not observe - and
     * they reach opposite outcomes here: the first is inert 109 and the second refuses the record. Asking
     * <em>after</em> the rewrite which of the two happened cannot be trusted, because a writer committing
     * between the rewrite and the question turns one answer into the other and this paragraph cannot tell
     * that it did. So the answer is obtained <em>first</em>, from a keyed read that holds the row for the
     * rest of the record's unit of work: from that moment nothing else can delete the row or change its
     * version, so an absence observed there is still an absence when the rewrite runs, and a presence
     * observed there leaves a zero count with only one remaining explanation. Neither outcome can be
     * wrong for a timing reason.
     *
     * <p>The hold is a strengthening of the legacy baseline and is recorded as one in
     * {@code docs/decision-log.md} entry DL-170 rather than presented as parity. The legacy cluster is defined {@code READINTEG(UNCOMMITTED)},
     * {@code RECOVERY(NONE)} and {@code JOURNAL(NO)}, and this program rewrites by key - the file is
     * {@code ORGANIZATION IS INDEXED, ACCESS MODE IS RANDOM} at lines 51 to 53 - so no row was ever
     * actually held and a concurrent change was invisible. It does not replace the version predicate,
     * which still carries the version validation read; the two answer different questions, presence and
     * change.
     *
     * @param  record  the record being posted
     * @param  account the account read during validation
     * @return the inert reject code 109 when the rewrite found no row, otherwise empty
     */
    private Optional<RejectReason> updateAccountRecord(final DailyTransaction record,
            final Account account) {
        final BigDecimal amount = record.getDalytranAmt();

        // Line 547: ADD DALYTRAN-AMT TO ACCT-CURR-BAL.
        final BigDecimal currentBalance =
                ZonedDecimalCodec.toMonetaryScale(account.getAcctCurrBal().add(amount));
        BigDecimal currentCycleCredit = account.getAcctCurrCycCredit();
        BigDecimal currentCycleDebit = account.getAcctCurrCycDebit();

        if (amount.compareTo(BigDecimal.ZERO) >= 0) {
            // Line 549: a non-negative amount joins the cycle credit.
            currentCycleCredit = ZonedDecimalCodec.toMonetaryScale(
                    account.getAcctCurrCycCredit().add(amount));
        } else {
            // Line 551: a negative amount joins the cycle debit UNCHANGED - not negated, not made
            // absolute - so the debit total goes negative. Deliberate and legacy-faithful.
            currentCycleDebit = ZonedDecimalCodec.toMonetaryScale(
                    account.getAcctCurrCycDebit().add(amount));
        }

        // Establish, under a write lock and BEFORE the rewrite, whether the row the rewrite is about to
        // address exists. This is the paragraph's invalid-key question, asked at a point where the answer
        // cannot subsequently change: the row is held for the remainder of this record's unit of work, so
        // it can be neither deleted nor re-versioned between here and the statement below. The result is
        // only ever consulted to tell the two zero-count causes apart, so the returned image is not used
        // and its staleness relative to the validation read does not matter.
        final boolean rowHeld =
                this.accountRepository.findByIdForUpdate(account.getAcctId()).isPresent();

        // Lines 554 to 559: REWRITE ... INVALID KEY. The update count is the rewrite status. The
        // version read during validation is part of the predicate, so the statement is a compare-and-set
        // and a concurrent write cannot be overwritten unnoticed.
        final int rewritten = this.accountRepository.rewritePostingBalances(account.getAcctId(),
                account.getVersion(), currentBalance, currentCycleCredit, currentCycleDebit);

        // The bulk update cleared the persistence context. Keep the detached result image aligned with
        // the values the paragraph attempted to rewrite, including on the inert invalid-key path.
        account.setAcctCurrBal(currentBalance);
        account.setAcctCurrCycCredit(currentCycleCredit);
        account.setAcctCurrCycDebit(currentCycleDebit);

        if (rewritten == 0) {
            // Two conditions produce no row, and they are not the same event. The row may be gone, which
            // is the legacy invalid-key condition and reaches the inert reject code 109 exactly as the
            // source does. Or the row may still be there carrying a different version, which means
            // another writer committed between this record's read and this rewrite. The legacy file had
            // no way to observe that - it read uncommitted with no recovery - so there is no legacy arm
            // to reproduce; the correct answer is to refuse the record rather than to report an absent
            // account that is not absent, and to let the record's transaction roll back so the
            // transaction-file write and the category-balance update do not harden against a balance that
            // was never rewritten.
            //
            // Which of the two it is was settled by the held read above, not by a second question asked
            // now. A row that was held is still present, so a zero count leaves the version as the only
            // remaining explanation; a row that was absent then cannot have been removed by anything this
            // paragraph did since.
            if (rowHeld) {
                LOG.warn("account rewrite lost a version race program={} accountRef={} effect=none",
                        PROGRAM_NAME, SensitiveLogRedactor.redact(account.getAcctId()));
                throw new OptimisticLockConflictException(
                        OptimisticLockConflictException.ConflictKind.RECORD_CHANGED_BEFORE_UPDATE,
                        ACCOUNT_ENTITY_NAME, account.getAcctId());
            }

            LOG.warn("account rewrite found no row program={} accountRef={} reasonCode={} reason={}"
                            + " effect=none",
                    PROGRAM_NAME, SensitiveLogRedactor.redact(account.getAcctId()),
                    RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getReasonCode(),
                    RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getDescription());
            return Optional.of(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE);
        }

        return Optional.empty();
    }

    /**
     * {@code 2900-WRITE-TRANSACTION-FILE}, lines 562 to 579: writes the posted transaction.
     *
     * <p>This runs third and last, after the category balance and the account, and it runs whether or
     * not the account rewrite took its invalid-key arm - which is the mechanism by which reject code
     * 109 ends up inert. Its failure arm displays {@code ERROR WRITING TO TRANSACTION FILE} at line
     * 574, then the status, then abends, in that order.
     *
     * @param  transaction the transaction to write
     * @return the transaction as persisted
     * @throws AbendException if the write fails
     */
    private Transaction writeTransactionFile(final Transaction transaction) {
        try {
            return this.transactionRepository.insertAndFlush(transaction);
        } catch (final DataAccessException unwritable) {
            throw abendAfterIoFailure(WRITE_TRANFILE_FAILURE, WRITE, TRANFILE_DD, unwritable);
        }
    }

    // -----------------------------------------------------------------------------------------------
    // The timestamp helper - lines 692 to 705
    // -----------------------------------------------------------------------------------------------

    /**
     * {@code Z-GET-DB2-FORMAT-TIMESTAMP}, lines 692 to 705: builds the twenty-six character processing
     * timestamp.
     *
     * <p>The twenty-six characters are declared field by field at lines 159 to 174 and assembled at
     * lines 694 to 703, and the layout is unusual enough to be worth spelling out in full. Reading left
     * to right: four year characters; a <strong>hyphen at position 5</strong>; two month; a
     * <strong>hyphen at position 8</strong>; two day; a <strong>hyphen at position 11</strong> - where
     * a conventional timestamp would put a space or a {@code T}; two hour; a <strong>dot at position
     * 14</strong>; two minute; a <strong>dot at position 17</strong>; two second; a <strong>dot at
     * position 20</strong>; a two-digit hundredths field; and a <strong>literal {@code 0000}</strong>
     * that line 701 moves in unconditionally and that is therefore never anything else. Four plus one
     * plus two plus one plus two plus one plus two plus one plus two plus one plus two plus one plus
     * two plus four is {@value #BATCH_TIMESTAMP_LENGTH}.
     *
     * <p>The online tier builds a twenty-six character timestamp too, and it is <strong>a different
     * format</strong>: a space where this one has its third hyphen, colons where this one has its dots,
     * and a six-digit fraction where this one has two digits and a literal tail. The two must never be
     * unified and never swapped. Emitting the online form from here would produce a value of exactly
     * the right length that fails a byte comparison, which is the hardest kind of defect to see.
     *
     * <p>Built from the injected clock rather than from the system clock directly, so a test can pin
     * the value and assert the format exactly.
     *
     * @return the processing timestamp, exactly {@value #BATCH_TIMESTAMP_LENGTH} characters
     */
    private String getDb2FormatTimestamp() {
        return formatBatchTimestamp(LocalDateTime.now(this.clock));
    }

    /**
     * Renders a moment in the batch timestamp format described by {@link #getDb2FormatTimestamp()}.
     *
     * @param  moment the moment to render
     * @return the rendered timestamp, exactly {@value #BATCH_TIMESTAMP_LENGTH} characters
     * @throws IllegalStateException if the rendered value is not exactly that wide, which a year
     *                               outside the four-digit range the legacy field can hold would cause
     */
    private static String formatBatchTimestamp(final LocalDateTime moment) {
        final int hundredths = moment.getNano() / NANOS_PER_HUNDREDTH;
        final String image = String.format(Locale.ROOT, "%04d-%02d-%02d-%02d.%02d.%02d.%02d%s",
                moment.getYear(),
                moment.getMonthValue(),
                moment.getDayOfMonth(),
                moment.getHour(),
                moment.getMinute(),
                moment.getSecond(),
                hundredths,
                BATCH_TIMESTAMP_TAIL);

        if (image.length() != BATCH_TIMESTAMP_LENGTH) {
            throw new IllegalStateException("the processing timestamp must be exactly "
                    + BATCH_TIMESTAMP_LENGTH + " characters but \"" + image + "\" has length "
                    + image.length());
        }
        return image;
    }

    // -----------------------------------------------------------------------------------------------
    // The diagnostic and abend paragraphs - lines 707 to 727
    // -----------------------------------------------------------------------------------------------

    /**
     * Emits the diagnostic and then abends, in that order, which is the ordering every failure arm in
     * the member uses: {@code 9910-DISPLAY-IO-STATUS} first, {@code 9999-ABEND-PROGRAM} second, at
     * lines 365 to 366, 462 to 463, 491 to 492, 522 to 523, 540 to 541 and 576 to 577 alike.
     *
     * <p>Logging before raising rather than from a caller's handler is the point. On the mainframe the
     * display reached the operator whether or not anything survived the abend, and a handler-based
     * equivalent loses exactly the diagnostic that is needed while the run is failing.
     *
     * <p>The status reported is {@link FileStatus#PERMANENT_ERROR}, the raw code the legacy read loops
     * treat as the error arm. Success and end-of-file are never reported here, and could not be: the
     * carried status refuses both by construction, which is the guarantee that stops an exhausted read
     * from ever being dressed up as a failure.
     *
     * <p>The failure literal is what travels onward as the operation description, because that is what
     * the legacy displayed and what the abend path documents its operation parameter to be. The verb is
     * carried separately and only into this class's own log line, where it gives a machine-readable
     * field that distinguishes a failed read from a failed write without parsing the literal.
     *
     * @param  failureLiteral the legacy display literal of the arm that failed
     * @param  legacyVerb     the COBOL verb whose failure this is, in the legacy's own vocabulary
     * @param  resourceName   the DD name of the resource that failed
     * @param  cause          the underlying failure, or {@code null} when the arm has none
     * @return never returns; the return type lets a caller write {@code throw} and keep its own control
     *         flow definite
     */
    private AbendException abendAfterIoFailure(final String failureLiteral, final String legacyVerb,
            final String resourceName, final Throwable cause) {
        final String rawFileStatus = FileStatus.PERMANENT_ERROR.getCode();
        displayIoStatus(rawFileStatus, failureLiteral, resourceName);
        return abendProgram(failureLiteral, rawFileStatus, legacyVerb, resourceName, cause);
    }

    /**
     * {@code 9910-DISPLAY-IO-STATUS}, lines 714 to 727: emits the raw file status as the legacy renders
     * it.
     *
     * <p>The paragraph has two arms and neither is a plain print. When the status is not numeric, or
     * its first byte is {@code 9} - the condition at lines 715 to 716 - lines 717 to 720 keep that
     * first byte and render the <em>binary value</em> of the second byte as three digits, so a status
     * whose second byte is not a digit still comes out as four readable characters. Otherwise lines 723
     * to 724 render the status as {@code 00} followed by its two characters. Either way the operator
     * sees four characters behind the same {@code FILE STATUS IS: NNNN} prefix, which is the literal
     * the carried status type publishes so that both tiers spell it identically.
     *
     * @param rawFileStatus the raw two-character file status
     * @param operation     the operation that reported it
     * @param resourceName  the DD name of the resource that reported it
     */
    private void displayIoStatus(final String rawFileStatus, final String operation,
            final String resourceName) {
        LOG.error("{}{}", FileStatusException.DISPLAY_PREFIX, ioStatusImage(rawFileStatus));
        this.abendService.displayIoStatus(rawFileStatus, operation, resourceName);
    }

    /**
     * Renders the four-character status image of lines 717 to 724.
     *
     * <p>Visible to the package rather than private so that both arms of the paragraph can be verified
     * against the source directly. Only the standard arm is reachable through this service's own
     * callers, because the target has no raw file status to inherit and therefore synthesises a single
     * numeric one; the non-standard arm exists because the paragraph has it, and a rendering that
     * nothing can exercise is a rendering that nobody can check.
     *
     * @param  rawFileStatus the raw two-character file status
     * @return exactly {@value #IO_STATUS_IMAGE_WIDTH} characters
     */
    static String ioStatusImage(final String rawFileStatus) {
        final String status = fixedWidthView(rawFileStatus, FILE_STATUS_WIDTH);
        final char firstByte = status.charAt(0);
        final char secondByte = status.charAt(1);

        // Lines 715 to 716: IF IO-STATUS NOT NUMERIC OR IO-STAT1 = '9'.
        if (!isNumeric(status) || firstByte == NON_STANDARD_STATUS_FIRST_BYTE) {
            // Lines 717 to 720: the first byte verbatim, then the second byte's binary value as three
            // digits, which is what moving one byte into the low half of a binary halfword yields.
            return String.valueOf(firstByte)
                    + String.format(Locale.ROOT, "%03d", (int) secondByte);
        }

        // Lines 723 to 724: MOVE '0000', then the status into positions three and four.
        return STANDARD_STATUS_IMAGE_PREFIX + status;
    }

    private static boolean isNumeric(final String status) {
        for (int index = 0; index < status.length(); index++) {
            final char character = status.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code 9999-ABEND-PROGRAM}, lines 707 to 711: displays {@code ABENDING PROGRAM}, sets the abend
     * code to 999, and abends.
     *
     * <p>The abend itself is delegated to the estate's single abend path, which raises after logging
     * its own diagnostic, so the whole terminal sequence - this method's log line, the abend service's
     * log line, the raise - happens in the legacy order. The carried status is what names the failed
     * resource and operation on the way out.
     *
     * @param  reason        the legacy display literal of the arm that failed
     * @param  rawFileStatus the raw two-character file status, never success and never end of file
     * @param  legacyVerb    the COBOL verb whose failure this is
     * @param  resourceName  the DD name of the resource that failed
     * @param  cause         the underlying failure, or {@code null} when the arm has none
     * @return never returns; the abend service always raises
     */
    private AbendException abendProgram(final String reason, final String rawFileStatus,
            final String legacyVerb, final String resourceName, final Throwable cause) {
        LOG.error("{} program={} reason={} fileStatus={} verb={} resource={}",
                ABENDING_PROGRAM, PROGRAM_NAME, reason, rawFileStatus, legacyVerb, resourceName);

        this.abendService.abendOnFileStatus(PROGRAM_NAME,
                new FileStatusException(rawFileStatus, reason, resourceName, cause));

        // Unreachable: the call above always raises. It exists so that every caller can write
        // "throw abendAfterIoFailure(...)" and keep its own control flow definite without a second
        // statement that a reader would have to check for reachability.
        return new AbendException(PROGRAM_NAME, reason);
    }

    // -----------------------------------------------------------------------------------------------
    // Reason-code helpers - the two working-storage fields of lines 181 and 182
    // -----------------------------------------------------------------------------------------------

    /**
     * The numeric fail reason of line 181 for a typed reason, or the acceptable value of line 208 when
     * there is none.
     *
     * @param  rejectReason the typed reason, or {@code null}
     * @return the reason code, or {@value #NO_REJECT_REASON_CODE}
     */
    private static int reasonCodeOf(final RejectReason rejectReason) {
        return rejectReason == null ? NO_REJECT_REASON_CODE : rejectReason.getReasonCode();
    }

    /**
     * The fail-reason description of line 182 for a typed reason, or the blank of line 209 when there
     * is none.
     *
     * <p>The text is taken from the typed reason rather than restated, so the five literals live in one
     * place and cannot drift apart from the codes they belong to.
     *
     * @param  rejectReason the typed reason, or {@code null}
     * @return the description text, or {@value #BLANK_FAIL_REASON_DESCRIPTION}
     */
    private static String reasonDescriptionOf(final RejectReason rejectReason) {
        return rejectReason == null
                ? BLANK_FAIL_REASON_DESCRIPTION
                : rejectReason.getDescription();
    }
}
