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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.DisclosureGroup;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.domain.id.DisclosureGroupId;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.DisclosureGroupRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.util.BoundedKeysetIterator;
import com.carddemo.util.CobolStringUtils;
import com.carddemo.util.FailureDiagnostics;
import com.carddemo.util.SensitiveLogRedactor;
import com.carddemo.util.ZonedDecimalCodec;

/**
 * The monthly interest accrual run: the Java realisation of the batch interest calculator
 * {@code app/cbl/CBACT04C.cbl}, <strong>652 lines and 22 procedure-division paragraphs</strong>.
 *
 * <p>Provenance: the legacy estate is read-only reference at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is transcribed here;
 * every claim below is a citation of a member, a paragraph, a line, a field name or a status code.
 *
 * <h2>Record authorities and their byte widths</h2>
 *
 * <ul>
 *   <li>{@code app/cpy/CVTRA01Y.cpy} - transaction category balance, <strong>50 bytes</strong>: the
 *       three-part record key then the balance at nine integer digits and two decimals.</li>
 *   <li>{@code app/cpy/CVTRA02Y.cpy} - disclosure group, <strong>50 bytes</strong>: the three-part
 *       record key then the interest rate at <strong>four</strong> integer digits and two decimals,
 *       the estate's only field of that width.</li>
 *   <li>{@code app/cpy/CVACT01Y.cpy} - account, <strong>300 bytes</strong>: five money fields at ten
 *       integer digits and two decimals, including the current balance and both cycle
 *       accumulators.</li>
 *   <li>{@code app/cpy/CVACT03Y.cpy} - card cross-reference, <strong>50 bytes</strong>: card number,
 *       customer identifier, account identifier and fourteen bytes of filler.</li>
 *   <li>{@code app/cpy/CVTRA05Y.cpy} - transaction, <strong>350 bytes</strong>: the record this run
 *       synthesizes, whose two 26-character timestamps sit at offsets 278 and 304.</li>
 * </ul>
 *
 * <h2>Paragraph mapping</h2>
 *
 * <p>Each of the member's paragraphs resolves to one named method here, and every method states its own
 * paragraph name and source line range. {@code docs/traceability-matrix.md} carries the full row-per-
 * paragraph inventory, including the one documented non-implementation: {@code 1400-COMPUTE-FEES} at
 * lines 518 to 520 is empty in the source and genuinely invoked at line 216, so {@link #computeFees}
 * is an invoked no-op and no fee logic may be invented for it.
 *
 * <p>The linkage area at lines 175 to 180 holds a signed four-digit binary length and a
 * <strong>ten-character date</strong>. Only the date crosses into Java, as a validated job parameter,
 * and it stays a ten-character string throughout because it is also the first ten characters of every
 * identifier this run mints. It is never converted to a temporal type.
 *
 * <p>The member carries no {@code EVALUATE}, no {@code GO TO}, no {@code SORT} and no {@code MERGE}, so
 * there is no clause-ordered switch to preserve and no backward jump to turn into a loop; every transfer
 * of control is a {@code PERFORM} of a named paragraph, which is why the mapping is one-to-one and total.
 *
 * <h2>The six behaviours a plausible translation gets wrong</h2>
 *
 * <ol>
 *   <li><strong>The interest expression multiplies before it divides</strong> (lines 464 to 465), and
 *       every store into a two-decimal field <strong>truncates</strong> because the estate contains no
 *       rounding clause anywhere. See {@link #computeInterest}.</li>
 *   <li><strong>The rate gate at lines 214 to 217 encloses both the computation and the fee
 *       invocation</strong>, so a zero rate skips <em>both</em>.</li>
 *   <li><strong>The fee paragraph is empty and still invoked.</strong> No fee logic may be
 *       invented.</li>
 *   <li><strong>The disclosure key's move order is not its constructor order.</strong> Lines 210 to
 *       212 move group, then category, then type; the key is group, then type, then category. See
 *       {@link #getInterestRate}.</li>
 *   <li><strong>The default-group fallback retries exactly once and then abends.</strong> Its probe
 *       key is the ten-character padded form of the literal, because the receiving field at line 437
 *       is ten characters wide.</li>
 *   <li><strong>The account control break zeroes both cycle accumulators</strong>, not one, and it
 *       fires for the final group as well as on a key change.</li>
 * </ol>
 *
 * <h2>What replaced what</h2>
 *
 * <p>Five datasets become five repositories. The category-balance master is read sequentially in
 * record-key order, so the scan passes an <strong>explicit sort</strong> rather than relying on an
 * ordering the repository does not impose. The cross-reference read at lines 393 to 413 uses the
 * alternate key on the account identifier, which is the repository's account-scoped finder; that
 * finder resolves the first matching row and reports absence as an empty result, which is the legacy
 * not-found path. The transaction file is opened for output and written record at a time, which is
 * the repository's save. The language-environment abort call at line 632 becomes the injected abend
 * collaborator. The program's console diagnostics become structured logging at the level each
 * diagnostic's severity warrants.
 *
 * <h2>Statelessness, and why this class is not final</h2>
 *
 * <p>This is a stateless singleton. It holds <strong>no mutable field whatsoever</strong>: the running
 * interest total, the six-digit identifier suffix, the current group key, the record count and the
 * end-of-file flag are all per-invocation locals carried in the private state holders declared at the
 * bottom of this class, mirroring the legacy working-storage items at lines 166 to 173. The suffix in
 * particular is <em>per run</em> and is threaded through the invocation rather than stored.
 *
 * <p>The class is deliberately <em>not</em> declared final. The group boundary carries a declarative
 * transaction annotation, and the container realises that with a class-based proxy, which has to be
 * able to subclass this type.
 *
 * @see com.carddemo.util.ZonedDecimalCodec
 * @see AbendService
 * @since 1.0.0
 */
@Service
public class InterestCalculationService {

    /** Replaces this member's console diagnostics; the legacy channel was the display statement. */
    private static final Logger LOG = LoggerFactory.getLogger(InterestCalculationService.class);

    /**
     * The legacy program identifier, reported as the abend culprit. Exactly eight characters, which
     * is the width the abend context's culprit field allows.
     */
    private static final String PROGRAM_NAME = "CBACT04C";

    /* ---------------------------------------------------------------------------------------- */
    /* Widths taken from the record layouts, one constant per field this member writes or keys on. */
    /* ---------------------------------------------------------------------------------------- */

    /** {@code PARM-DATE} at line 178 is ten characters, and so is every identifier's date prefix. */
    private static final int PARM_DATE_WIDTH = 10;

    /** {@code WS-TRANID-SUFFIX} at line 173 is six digits. */
    private static final int TRANID_SUFFIX_WIDTH = 6;

    /** {@code ACCT-ID} is eleven digits, and the description at lines 485 to 489 renders all of them. */
    private static final int ACCT_ID_WIDTH = 11;

    /** {@code DIS-ACCT-GROUP-ID} and {@code ACCT-GROUP-ID} are ten characters. */
    private static final int ACCT_GROUP_ID_WIDTH = 10;

    /** {@code TRAN-CAT-CD} is four digits, which is why the literal moved at line 483 stores as four. */
    private static final int TRAN_CAT_CD_WIDTH = 4;

    /** {@code TRAN-SOURCE} is ten characters, so the source literal is space-padded to ten. */
    private static final int TRAN_SOURCE_WIDTH = 10;

    /** {@code TRAN-MERCHANT-ID} is nine digits, so the numeric zero moved at line 491 stores as nine. */
    private static final int MERCHANT_ID_WIDTH = 9;

    /** {@code TRAN-MERCHANT-NAME} is fifty characters. */
    private static final int MERCHANT_NAME_WIDTH = 50;

    /** {@code TRAN-MERCHANT-CITY} is fifty characters. */
    private static final int MERCHANT_CITY_WIDTH = 50;

    /** {@code TRAN-MERCHANT-ZIP} is ten characters. */
    private static final int MERCHANT_ZIP_WIDTH = 10;

    /* ---------------------------------------------------------------------------------------- */
    /* The synthesized interest transaction's literals, lines 473 to 515, in source order.        */
    /* ---------------------------------------------------------------------------------------- */

    /** Line 482 moves this two-character literal into the transaction type code. */
    private static final String INTEREST_TRAN_TYPE_CD = "01";

    /**
     * Line 483 moves the two-character literal {@code 05} into a <strong>four-digit</strong> field, so
     * the stored value is {@code 0005}. It is neither {@code 5} nor {@code 05}.
     */
    private static final String INTEREST_TRAN_CAT_CD =
            CobolStringUtils.rightJustifyZeroFill("05", TRAN_CAT_CD_WIDTH);

    /**
     * Line 484 moves the six-character literal {@code System} into a ten-character field, so the
     * stored value carries four trailing spaces. It is never trimmed.
     */
    private static final String INTEREST_TRAN_SOURCE =
            alphanumericMove("System", TRAN_SOURCE_WIDTH);

    /**
     * The description prefix from lines 485 to 489. <strong>Its trailing space is significant</strong>
     * and separates the literal from the account identifier that follows it.
     */
    private static final String INTEREST_DESCRIPTION_PREFIX = "Int. for a/c ";

    /**
     * Line 491 moves numeric zero into a nine-digit field, so the stored value is nine zero
     * characters. It is a string, never the number zero.
     */
    private static final String INTEREST_MERCHANT_ID =
            CobolStringUtils.rightJustifyZeroFill("0", MERCHANT_ID_WIDTH);

    /** Line 492 moves spaces into the fifty-character merchant name. */
    private static final String BLANK_MERCHANT_NAME = alphanumericMove("", MERCHANT_NAME_WIDTH);

    /** Line 493 moves spaces into the fifty-character merchant city. */
    private static final String BLANK_MERCHANT_CITY = alphanumericMove("", MERCHANT_CITY_WIDTH);

    /** Line 494 moves spaces into the ten-character merchant postal code. */
    private static final String BLANK_MERCHANT_ZIP = alphanumericMove("", MERCHANT_ZIP_WIDTH);

    /* ---------------------------------------------------------------------------------------- */
    /* The disclosure-group fallback literal, line 437.                                          */
    /* ---------------------------------------------------------------------------------------- */

    /**
     * The seven-character default group literal moved at line 437 into a <strong>ten-character</strong>
     * receiving field, so the probe key is the padded form with three trailing spaces.
     *
     * <p>The padding is built here rather than written as a literal with invisible trailing spaces, so
     * that it survives any tool that strips trailing whitespace, and so that the ten-character width
     * is stated rather than counted by eye. <strong>The seven-character form resolves nothing</strong>,
     * and this value is never trimmed. The padding belongs to this service, never to the repository.
     */
    private static final String DEFAULT_ACCOUNT_GROUP_ID =
            alphanumericMove("DEFAULT", ACCT_GROUP_ID_WIDTH);

    /* ---------------------------------------------------------------------------------------- */
    /* Arithmetic constants.                                                                     */
    /* ---------------------------------------------------------------------------------------- */

    /**
     * The literal divisor of the interest expression at lines 464 to 465: twelve months times one
     * hundred, because the rate is a whole-number annual percentage.
     */
    private static final BigDecimal MONTHLY_INTEREST_DIVISOR = new BigDecimal("1200");

    /**
     * The scale the quotient is carried at before it is truncated to the receiving field's two
     * decimals. It is deliberately far wider than the receiver, which is what the compiler's
     * intermediate-result rules do, and it is safe because truncation toward zero is idempotent in
     * this direction: truncating to twelve decimals and then to two gives the same value as
     * truncating the exact quotient straight to two. Carrying an intermediate also means the division
     * never fails on a quotient with no terminating decimal expansion.
     */
    private static final int INTERMEDIATE_QUOTIENT_SCALE = 12;

    /** Zero at the monetary scale, which is what a move of zero into a two-decimal field stores. */
    private static final BigDecimal ZERO_MONETARY =
            ZonedDecimalCodec.toMonetaryScale(BigDecimal.ZERO);

    /* ---------------------------------------------------------------------------------------- */
    /* The batch 26-character timestamp, lines 150 to 165 and 613 to 626.                        */
    /* ---------------------------------------------------------------------------------------- */

    /**
     * The batch timestamp's assembly pattern. Reading the redefinition at lines 151 to 165 field by
     * field: four year characters, a <strong>hyphen</strong>, two month, a hyphen, two day, a
     * <strong>hyphen</strong>, two hour, a <strong>dot</strong>, two minute, a dot, two second, a dot,
     * a <strong>two-digit hundredths</strong> field, then a literal four-character tail. That places
     * hyphens at positions 5, 8 and 11 and dots at 14, 17 and 20, and sums to exactly 26.
     */
    private static final String BATCH_TIMESTAMP_PATTERN = "%04d-%02d-%02d-%02d.%02d.%02d.%02d%s";

    /** The literal four characters moved into the timestamp's trailing field at line 622. */
    private static final String BATCH_TIMESTAMP_TAIL = "0000";

    /**
     * The current-date intrinsic supplies hundredths of a second, which is what the two-digit field at
     * line 164 holds, so the nanosecond reading is reduced by this divisor rather than truncated as
     * text.
     */
    private static final int NANOS_PER_HUNDREDTH = 10_000_000;

    /** The lowest year the four-character year field can hold. */
    private static final int MIN_REPRESENTABLE_YEAR = 0;

    /** The highest year the four-character year field can hold. */
    private static final int MAX_REPRESENTABLE_YEAR = 9999;

    /* ---------------------------------------------------------------------------------------- */
    /* The coarse status model's normalised values, lines 133 to 135.                            */
    /* ---------------------------------------------------------------------------------------- */

    /** The value the level-88 condition {@code APPL-AOK} at line 134 tests for. */
    private static final int APPL_RESULT_AOK = 0;

    /** The value moved on any status the program treats as a failure. */
    private static final int APPL_RESULT_ERROR = 12;

    /** The value the level-88 condition {@code APPL-EOF} at line 135 tests for. */
    private static final int APPL_RESULT_EOF = 16;

    /* ---------------------------------------------------------------------------------------- */
    /* Diagnostic texts and resource names, reproduced from this member's display statements.    */
    /* ---------------------------------------------------------------------------------------- */

    /** Line 181. */
    private static final String START_OF_EXECUTION = "START OF EXECUTION OF PROGRAM CBACT04C";

    /** Line 230. */
    private static final String END_OF_EXECUTION = "END OF EXECUTION OF PROGRAM CBACT04C";

    /** Line 342, the read failure on the category-balance master. */
    private static final String ERROR_READING_TCATBAL = "ERROR READING TRANSACTION CATEGORY FILE";

    /** Line 365, the rewrite failure on the account master. */
    private static final String ERROR_REWRITING_ACCTFILE = "ERROR RE-WRITING ACCOUNT FILE";

    /** Line 386, the read failure on the account master. */
    private static final String ERROR_READING_ACCTFILE = "ERROR READING ACCOUNT FILE";

    /** Line 408, the read failure on the cross-reference. */
    private static final String ERROR_READING_XREFFILE = "ERROR READING XREF FILE";

    /** Line 431, the read failure on the disclosure group. */
    private static final String ERROR_READING_DISCGRP = "ERROR READING DISCLOSURE GROUP FILE";

    /** Line 455, the read failure on the <em>default</em> disclosure group - a distinct diagnostic. */
    private static final String ERROR_READING_DEFAULT_DISCGRP =
            "ERROR READING DEFAULT DISCLOSURE GROUP";

    /** Lines 375 and 397 both report a missing key with this text. */
    private static final String ACCOUNT_NOT_FOUND = "ACCOUNT NOT FOUND";

    /** Line 418. */
    private static final String DISCGRP_RECORD_MISSING = "DISCLOSURE GROUP RECORD MISSING";

    /** Line 419. */
    private static final String TRY_WITH_DEFAULT_GROUP_CODE = "TRY WITH DEFAULT GROUP CODE";

    /** The dataset the category-balance master is assigned to at line 28. */
    private static final String RESOURCE_TCATBALF = "TCATBALF";

    /** The dataset the cross-reference is assigned to at line 34. */
    private static final String RESOURCE_XREFFILE = "XREFFILE";

    /** The dataset the account master is assigned to at line 41. */
    private static final String RESOURCE_ACCTFILE = "ACCTFILE";

    /** The dataset the disclosure group is assigned to at line 47. */
    private static final String RESOURCE_DISCGRP = "DISCGRP";

    /** The dataset the transaction output is assigned to at line 53. */
    private static final String RESOURCE_TRANSACT = "TRANSACT";

    /** The operation reported when a read fails. */
    private static final String OPERATION_READ = "READ";

    /** The operation reported when the account rewrite fails. */
    private static final String OPERATION_REWRITE = "REWRITE";

    /**
     * The ordering the sequential scan asks for: the record key of the category-balance master, which
     * lines 63 to 66 declare as the account identifier, then the type code, then the category code.
     * The repository imposes no ordering of its own, so the scan states this explicitly - without it
     * the control break would see the same account's rows scattered and would break on every row.
     */
    private static final int KEYSET_PAGE_SIZE = BoundedKeysetIterator.DEFAULT_PAGE_SIZE;

    private static final int TRAN_CAT_BAL_ACCOUNT_KEY_LENGTH = 11;

    private static final int TRAN_CAT_BAL_TYPE_KEY_LENGTH = 2;

    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    private final DisclosureGroupRepository disclosureGroupRepository;

    private final AccountRepository accountRepository;

    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    private final InterestGroupTransactionBoundary groupTransactionBoundary;

    private final AbendService abendService;

    private final Clock clock;

    /**
     * Constructs the service. Constructor injection throughout, and every collaborator is required:
     * this member reads four masters, rewrites the account master and assembles the sequential output
     * record, so there is no degraded mode in which a missing collaborator would be acceptable.
     *
     * @param transactionCategoryBalanceRepository the category-balance master, read sequentially in
     *                                             record-key order
     * @param disclosureGroupRepository            the disclosure group, read by its three-part key
     * @param accountRepository                    the account master, read and rewritten per group
     * @param cardCrossReferenceRepository         the cross-reference, read by the account identifier
     * @param groupTransactionBoundary             the independent commit boundary for one closed
     *                                             account group
     * @param abendService                         the language-environment abort call's replacement
     * @param clock                                the source of the batch timestamp's instant
     */
    public InterestCalculationService(
            final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
            final DisclosureGroupRepository disclosureGroupRepository,
            final AccountRepository accountRepository,
            final CardCrossReferenceRepository cardCrossReferenceRepository,
            final InterestGroupTransactionBoundary groupTransactionBoundary,
            final AbendService abendService,
            final Clock clock) {
        this.transactionCategoryBalanceRepository = Objects.requireNonNull(
                transactionCategoryBalanceRepository,
                "transactionCategoryBalanceRepository must not be null");
        this.disclosureGroupRepository = Objects.requireNonNull(disclosureGroupRepository,
                "disclosureGroupRepository must not be null");
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "accountRepository must not be null");
        this.cardCrossReferenceRepository = Objects.requireNonNull(cardCrossReferenceRepository,
                "cardCrossReferenceRepository must not be null");
        this.groupTransactionBoundary = Objects.requireNonNull(groupTransactionBoundary,
                "groupTransactionBoundary must not be null");
        this.abendService = Objects.requireNonNull(abendService, "abendService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /* ======================================================================================== */
    /* PUBLIC RESULTS                                                                            */
    /* ======================================================================================== */

    /**
     * What the run decided for one category-balance row: the rate it resolved, the interest it
     * computed, whether the rate gate skipped the row, whether the default group supplied the rate,
     * and the transaction the row synthesized.
     *
     * @param accountId          the eleven-digit account identifier the row keys on
     * @param tranTypeCd         the two-character transaction type code the row keys on
     * @param tranCatCd          the four-digit transaction category code the row keys on
     * @param categoryBalance    the row's balance, exactly as stored, at the monetary scale
     * @param disclosedRate      the rate the disclosure group supplied, at the monetary scale
     * @param monthlyInterest    the interest the expression at lines 464 to 465 produced, truncated to
     *                           the receiving field's two decimals; zero when the rate gate skipped
     * @param rateGateSkipped    {@code true} when the rate was zero, so lines 215 <em>and</em> 216
     *                           were both skipped
     * @param defaultGroupUsed   {@code true} when the first probe missed and the padded default group
     *                           literal supplied the rate
     * @param interestTransaction the synthesized transaction, or {@code null} when the rate gate
     *                           skipped the row and nothing was written
     */
    public record CategoryInterest(String accountId,
                                   String tranTypeCd,
                                   String tranCatCd,
                                   BigDecimal categoryBalance,
                                   BigDecimal disclosedRate,
                                   BigDecimal monthlyInterest,
                                   boolean rateGateSkipped,
                                   boolean defaultGroupUsed,
                                   Transaction interestTransaction) {

        /**
         * Reports whether this row produced a transaction, which is the negation of the rate gate
         * having skipped it.
         *
         * @return {@code true} when a transaction was synthesized and written for this row
         */
        public boolean producedTransaction() {
            return this.interestTransaction != null;
        }
    }

    /**
     * What one account group produced: the running interest total the control break posted, the
     * per-row detail, the transactions written, the rewritten account, and the suffix the next group
     * must continue from.
     *
     * @param accountId            the eleven-digit account identifier this group keys on
     * @param totalInterest        the group's running total, which the control break at lines 350 to
     *                             370 added to the account's current balance
     * @param categoryInterests    one entry per category-balance row of the group, in the order the
     *                             rows were presented
     * @param interestTransactions the transactions this group synthesized, in the order written
     * @param updatedAccount       the account as the control break rewrote it, with both cycle
     *                             accumulators zeroed
     * @param rateGateSkipped      {@code true} when at least one row of the group had a zero rate
     * @param defaultGroupUsed     {@code true} when at least one row of the group fell back to the
     *                             padded default group literal
     * @param recordCount          how many category-balance rows the group contained
     * @param lastTranIdSuffix     the six-digit identifier suffix after this group, which the next
     *                             group continues from because the suffix is per run and not per group
     */
    public record GroupInterestResult(String accountId,
                                      BigDecimal totalInterest,
                                      List<CategoryInterest> categoryInterests,
                                      List<Transaction> interestTransactions,
                                      Account updatedAccount,
                                      boolean rateGateSkipped,
                                      boolean defaultGroupUsed,
                                      int recordCount,
                                      long lastTranIdSuffix) {

        /**
         * Canonical constructor, which defends the two collections against later mutation so a result
         * handed to a caller cannot change underneath it.
         */
        public GroupInterestResult {
            categoryInterests = List.copyOf(categoryInterests);
            interestTransactions = List.copyOf(interestTransactions);
        }
    }

    /**
     * What the whole run produced: one entry per account group, every transaction written, and the two
     * counters the legacy program maintained.
     *
     * @param groups               one entry per account group, in the order the control break closed
     *                             them
     * @param interestTransactions every transaction the run synthesized, in the order written
     * @param groupCount           how many account groups the control break closed
     * @param recordCount          the value the counter at line 172 reached, incremented once per
     *                             category-balance row read at line 192
     * @param rateGateSkipped      {@code true} when at least one row anywhere in the run had a zero
     *                             rate
     * @param defaultGroupUsed     {@code true} when at least one row anywhere in the run fell back to
     *                             the padded default group literal
     * @param lastTranIdSuffix     the six-digit identifier suffix the run finished on
     */
    public record InterestRunResult(List<GroupInterestResult> groups,
                                    List<Transaction> interestTransactions,
                                    int groupCount,
                                    int recordCount,
                                    boolean rateGateSkipped,
                                    boolean defaultGroupUsed,
                                    long lastTranIdSuffix) {

        /**
         * Canonical constructor, which defends the two collections against later mutation.
         */
        public InterestRunResult {
            groups = List.copyOf(groups);
            interestTransactions = List.copyOf(interestTransactions);
        }
    }

    /* ======================================================================================== */
    /* PUBLIC OPERATIONS                                                                         */
    /* ======================================================================================== */

    /**
     * Runs the interest accrual over the whole category-balance master: the procedure division at lines
     * 180 to 232, end to end.
     *
     * <p>The master is read sequentially in record-key order, which is what the file declaration at
     * lines 28 to 32 specifies and what the control break at line 194 depends on. The repository
     * imposes no ordering, so the scan supplies it explicitly.
     *
     * @param parameterDate the ten-character date from the linkage area at line 178, which is also the
     *                      first ten characters of every identifier this run mints
     * @return what the run produced
     * @throws IllegalArgumentException  when the parameter date is not exactly ten characters
     * @throws com.carddemo.exception.AbendException when any read or write reports a status this
     *                                   member treats as a failure, or when a group's rate cannot be
     *                                   resolved even from the default group
     */
    public InterestRunResult calculateInterest(final String parameterDate) {
        final String validatedDate = validatedParameterDate(parameterDate);
        return calculateInterest(validatedDate, readCategoryBalanceMasterInKeyOrder());
    }

    /**
     * Runs the interest accrual over a caller-supplied ordered source: the same procedure division at
     * lines 180 to 232, driven by rows the caller has already read.
     *
     * <p>This is the entry point the batch tier uses, so that the reader, the chunking and the metrics
     * stay in the batch tier and this service stays free of any job-orchestration type. The direction
     * is one way: the batch tier depends on this service and never the reverse.
     *
     * <p><strong>The rows must arrive in record-key order</strong>, account identifier first. The
     * control break at line 194 fires on a change of account identifier, so rows of the same account
     * that are not adjacent would be treated as separate groups - each closing its own control break
     * and each rewriting the account - which would post the same account's interest more than once.
     *
     * <p><strong>The final group is flushed.</strong> The source expresses the end-of-file control
     * break as the {@code ELSE} arm at lines 219 to 221 of a test-before {@code PERFORM UNTIL}, and a
     * literal reading of that loop leaves the arm unreachable, because the read paragraph raises the
     * end-of-file flag and the loop's own condition then ends the loop before the arm can run.
     * Reproducing that unreachability would silently drop the last account's accrued interest, so the
     * arm is honoured here: the last group is closed after the read loop ends. The divergence is
     * recorded in the decision log rather than left implicit.
     *
     * @param parameterDate            the ten-character date from the linkage area at line 178
     * @param orderedCategoryBalances  the category-balance rows, in record-key order; may be empty, in
     *                                 which case the run reads nothing and closes no group
     * @return what the run produced
     * @throws IllegalArgumentException  when the parameter date is not exactly ten characters, or when
     *                                   the row list is {@code null}
     * @throws com.carddemo.exception.AbendException on any failure this member abends on
     */
    public InterestRunResult calculateInterest(final String parameterDate,
            final Iterable<TransactionCategoryBalance> orderedCategoryBalances) {
        final String validatedDate = validatedParameterDate(parameterDate);
        Objects.requireNonNull(orderedCategoryBalances,
                "orderedCategoryBalances must not be null: an absent source is not an empty source");

        LOG.info(START_OF_EXECUTION);

        // Lines 182 to 186, in source order.
        tcatbalfOpen();
        xreffileOpen();
        discgrpOpen();
        acctfileOpen();
        tranfileOpen();

        final RunState run = new RunState();
        final List<GroupInterestResult> groups = new ArrayList<>();
        final List<Transaction> written = new ArrayList<>();
        final Iterator<TransactionCategoryBalance> cursor = orderedCategoryBalances.iterator();
        List<TransactionCategoryBalance> currentGroup = new ArrayList<>();

        // Lines 188 to 222: PERFORM UNTIL END-OF-FILE = 'Y'.
        while (!run.isEndOfFile()) {
            // Line 190. The paragraph raises the end-of-file flag itself, exactly as line 340 does.
            final Optional<TransactionCategoryBalance> read = tcatbalfGetNext(cursor, run);
            if (read.isEmpty()) {
                // The loop's own condition now ends it; the final group is closed below, which is the
                // end-of-file control break the ELSE arm at lines 219 to 221 stands for.
                continue;
            }
            final TransactionCategoryBalance row = read.get();
            final String rowAccountId = requiredAccountKey(row);

            run.countRecord();                                             // Line 192.
            logCategoryBalanceRecord(row);                                 // Line 193.

            // Line 194: the account control break.
            if (!rowAccountId.equals(run.lastAccountNumber())) {
                if (run.isFirstTime()) {
                    // Lines 197 to 198: the very first key change has no preceding group to close.
                    run.clearFirstTime();
                } else {
                    // Line 196. Closing the preceding group resets its running total by construction,
                    // which is what line 200 does for the group about to start.
                    final GroupInterestResult closed =
                            closeGroup(validatedDate, currentGroup, run);
                    groups.add(closed);
                    written.addAll(closed.interestTransactions());
                    currentGroup = new ArrayList<>();
                }
                // Line 201.
                run.rememberAccountNumber(rowAccountId);
            }
            currentGroup.add(row);
        }

        // Lines 219 to 221, honoured for the final group. See the note in this method's contract.
        if (!currentGroup.isEmpty()) {
            final GroupInterestResult closed = closeGroup(validatedDate, currentGroup, run);
            groups.add(closed);
            written.addAll(closed.interestTransactions());
        }

        // Lines 224 to 228, in source order.
        tcatbalfClose();
        xreffileClose();
        discgrpClose();
        acctfileClose();
        tranfileClose();

        LOG.info(END_OF_EXECUTION);

        return new InterestRunResult(groups,
                written,
                groups.size(),
                run.recordCount(),
                run.isRateGateSkipped(),
                run.isDefaultGroupUsed(),
                run.tranIdSuffix());
    }

    /**
     * Accrues interest for one account group and closes it: the group boundary, and therefore the
     * commit point.
     *
     * <p>The sequence is the source's own. The account and the cross-reference are read once for the
     * group, at lines 202 to 205. Every row of the group then resolves its rate at lines 210 to 213,
     * and where the rate is not zero - and only there - the interest is computed at line 215 and the
     * fee paragraph is invoked at line 216. The control break at lines 350 to 370 finally posts the
     * running total to the account's current balance, zeroes <strong>both</strong> cycle accumulators
     * and rewrites the account.
     *
     * <p>The separate {@link InterestGroupTransactionBoundary} puts the commit at this control break,
     * matching where the legacy hardened the account rewrite. It always opens a new transaction, so a
     * later account failure cannot roll back a group that already completed. <strong>No rollback is
     * forced anywhere</strong>: this member contains no rollback site at all - the estate's only
     * explicit one is in the account-update program - so nothing here marks a transaction for rollback.
     * The account carries a version attribute and this member has no conflict-handling arm, so a
     * concurrent-modification failure is allowed to propagate rather than being translated into an
     * abend. No pessimistic lock is taken.
     *
     * @param parameterDate            the ten-character date from the linkage area at line 178
     * @param accountId                the eleven-digit account identifier every row of the group keys
     *                                 on
     * @param groupCategoryBalances    the group's category-balance rows, all sharing that account
     *                                 identifier, in record-key order; must not be empty
     * @param initialTranIdSuffix      the six-digit identifier suffix to continue from, because the
     *                                 suffix at line 173 is per run and not per group; zero starts a
     *                                 fresh run, so the first identifier minted carries suffix one
     * @return what the group produced, including the suffix the next group must continue from
     * @throws IllegalArgumentException  when the parameter date is not exactly ten characters, when the
     *                                   group is empty, when the suffix is negative, or when a row does
     *                                   not belong to the named account
     * @throws com.carddemo.exception.AbendException when the account or the cross-reference cannot be
     *                                   read, when the rate cannot be resolved even from the default
     *                                   group, or when a write fails
     */
    public GroupInterestResult calculateGroupInterest(final String parameterDate,
            final String accountId,
            final List<TransactionCategoryBalance> groupCategoryBalances,
            final long initialTranIdSuffix) {
        return this.groupTransactionBoundary.execute(() -> calculateGroupInterestWithinBoundary(
                parameterDate, accountId, groupCategoryBalances, initialTranIdSuffix));
    }

    /**
     * Performs one account group's work inside {@link InterestGroupTransactionBoundary}.
     *
     * <p>Kept separate from the public boundary method so both the batch control break and this
     * service's whole-file driver take the same proxied transaction path. Calling a transactionally
     * annotated method on this same instance would bypass Spring interception and silently recreate
     * the whole-pass rollback defect.
     *
     * @param parameterDate         the ten-character date from the linkage area
     * @param accountId             the group account identifier
     * @param groupCategoryBalances the rows belonging to the group
     * @param initialTranIdSuffix   the run suffix entering the group
     * @return the completed account-group result
     */
    private GroupInterestResult calculateGroupInterestWithinBoundary(final String parameterDate,
            final String accountId,
            final List<TransactionCategoryBalance> groupCategoryBalances,
            final long initialTranIdSuffix) {
        final String validatedDate = validatedParameterDate(parameterDate);
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(groupCategoryBalances, "groupCategoryBalances must not be null");
        if (groupCategoryBalances.isEmpty()) {
            throw new IllegalArgumentException("an account group must carry at least one"
                    + " category-balance row, because the control break only closes a group the read"
                    + " loop actually opened");
        }
        if (initialTranIdSuffix < 0L) {
            throw new IllegalArgumentException("the transaction identifier suffix counts upward from"
                    + " zero but was " + initialTranIdSuffix);
        }
        for (final TransactionCategoryBalance row : groupCategoryBalances) {
            Objects.requireNonNull(row, "a category-balance row must not be null");
            if (!accountId.equals(row.getTrancatAcctId())) {
                throw new IllegalArgumentException("every row of an account group must carry the"
                        + " group's own account identifier, but a row keyed on \""
                        + row.getTrancatAcctId() + "\" was presented for group \"" + accountId
                        + "\"; the control break is driven by that key and a mixed group would post"
                        + " one account's interest to another");
            }
        }

        // Lines 202 to 203, then lines 204 to 205: read once for the group, before any row of it.
        final Account account = getAcctData(accountId);
        final CardCrossReference crossReference = getXrefData(accountId);
        final GroupContext context = new GroupContext(validatedDate, account, crossReference);

        // Line 200: the running total starts at zero for every group.
        final GroupState group = new GroupState(initialTranIdSuffix);
        final List<CategoryInterest> categoryInterests =
                new ArrayList<>(groupCategoryBalances.size());
        final List<Transaction> interestTransactions = new ArrayList<>();

        for (final TransactionCategoryBalance row : groupCategoryBalances) {
            categoryInterests.add(accrueCategoryRow(row, context, group, interestTransactions));
        }

        // Lines 350 to 370, and for the run's final group also lines 219 to 221.
        final Account updatedAccount = updateAccount(account, group.totalInterest());

        return new GroupInterestResult(accountId,
                group.totalInterest(),
                categoryInterests,
                interestTransactions,
                updatedAccount,
                group.isRateGateSkipped(),
                group.isDefaultGroupUsed(),
                groupCategoryBalances.size(),
                group.tranIdSuffix());
    }

    /* ======================================================================================== */
    /* THE 22 PARAGRAPHS, IN SOURCE ORDER                                                        */
    /* ======================================================================================== */

    /**
     * Paragraph {@code 0000-TCATBALF-OPEN}, lines 234 to 250: opens the category-balance master for
     * input and abends on any status other than success.
     *
     * <p>In the relational realisation there is no dataset to open. The connection is supplied by the
     * container's pool and the unit of work is the declarative transaction the group boundary opens, so
     * no open call is issued, no two-byte status comes back, and the abend arm at lines 245 to 248 has
     * nothing that could reach it. The paragraph is nonetheless retained as a named method that the
     * driving operation genuinely invokes, so that the paragraph-level mapping resolves to a method
     * rather than disappearing from the traceability record - the same treatment the empty fee
     * paragraph receives, and for the same reason.
     *
     * @return the normalised coarse outcome, which the container's ownership of the connection makes
     *         success
     */
    private ApplResult tcatbalfOpen() {
        LOG.debug("open resource={} access=INPUT owner=container", RESOURCE_TCATBALF);
        return ApplResult.OK;
    }

    /**
     * Paragraph {@code 0100-XREFFILE-OPEN}, lines 252 to 268: opens the cross-reference for input.
     * Retained as a named, invoked method for the reason given on {@code tcatbalfOpen}.
     *
     * @return the normalised coarse outcome
     */
    private ApplResult xreffileOpen() {
        LOG.debug("open resource={} access=INPUT owner=container", RESOURCE_XREFFILE);
        return ApplResult.OK;
    }

    /**
     * Paragraph {@code 0200-DISCGRP-OPEN}, lines 270 to 286: opens the disclosure group for input.
     * Retained as a named, invoked method for the reason given on {@code tcatbalfOpen}.
     *
     * <p>The diagnostic this paragraph would emit on failure names the wrong file - the source text
     * refers to a rejects file rather than to the disclosure group. Nothing depends on it, so it is
     * neither reproduced nor corrected here; the observation belongs in the decision log.
     *
     * @return the normalised coarse outcome
     */
    private ApplResult discgrpOpen() {
        LOG.debug("open resource={} access=INPUT owner=container", RESOURCE_DISCGRP);
        return ApplResult.OK;
    }

    /**
     * Paragraph {@code 0300-ACCTFILE-OPEN}, lines 289 to 305: opens the account master for
     * <strong>input and output</strong>, because this member rewrites it at the control break.
     * Retained as a named, invoked method for the reason given on {@code tcatbalfOpen}.
     *
     * @return the normalised coarse outcome
     */
    private ApplResult acctfileOpen() {
        LOG.debug("open resource={} access=I-O owner=container", RESOURCE_ACCTFILE);
        return ApplResult.OK;
    }

    /**
     * Paragraph {@code 0400-TRANFILE-OPEN}, lines 307 to 323: opens the transaction file for
     * <strong>output</strong>. Retained as a named, invoked method for the reason given on
     * {@code tcatbalfOpen}.
     *
     * @return the normalised coarse outcome
     */
    private ApplResult tranfileOpen() {
        LOG.debug("open resource={} access=OUTPUT owner=container", RESOURCE_TRANSACT);
        return ApplResult.OK;
    }

    /**
     * Paragraph {@code 1000-TCATBALF-GET-NEXT}, lines 325 to 348: advances the sequential read of the
     * category-balance master by one record.
     *
     * <p>This paragraph is where the estate's <strong>two-level status model</strong> is clearest, and
     * it is reproduced rather than collapsed. A raw two-byte file status is normalised first - success
     * to the success value, end of file to the end-of-file value, and <em>anything else</em> to the
     * error value, at lines 327 to 335 - and only the normalised value is branched on, at lines 336 to
     * 347. <strong>End of file is never folded into error</strong>: it is the ordinary way a sequential
     * read loop terminates, and the source proves the distinction matters by raising the end-of-file
     * flag on one arm and abending on the other.
     *
     * <p>The cursor's exhaustion is the end-of-file status. A data-access failure is the
     * "anything else" status and is reported as the permanent-error code, which is in the raw
     * vocabulary this estate actually compares; it is assigned as a status and then normalised, exactly
     * as the source does, rather than short-circuiting to the abend, so the error arm below is the one
     * and only place this paragraph abends from.
     *
     * @param cursor the position in the sequential scan, which the caller holds for the whole run
     * @param run    the run's per-invocation state, whose end-of-file flag this paragraph raises at
     *               line 340
     * @return the record just read, or empty at end of file
     */
    private Optional<TransactionCategoryBalance> tcatbalfGetNext(
            final Iterator<TransactionCategoryBalance> cursor, final RunState run) {
        Optional<TransactionCategoryBalance> fetched;
        String rawFileStatus;
        try {
            // Line 326: READ ... INTO. Exhaustion is the end-of-file status, not a failure.
            if (cursor.hasNext()) {
                fetched = Optional.of(cursor.next());
                rawFileStatus = FileStatus.SUCCESS.getCode();
            } else {
                fetched = Optional.empty();
                rawFileStatus = FileStatus.END_OF_FILE.getCode();
            }
        } catch (final DataAccessException unreadable) {
            LOG.error("{} failureChain={}", ERROR_READING_TCATBAL,
                    FailureDiagnostics.failureChainOf(unreadable));
            fetched = Optional.empty();
            rawFileStatus = FileStatus.PERMANENT_ERROR.getCode();
        }

        final ApplResult outcome = sequentialReadApplResult(rawFileStatus);   // Lines 327 to 335.
        // The coarse value, beside the raw code that produced it. Both are reported because the
        // program branches on the coarse one and diagnoses with the raw one.
        LOG.trace("sequential read resource={} rawFileStatus={} applResult={}",
                RESOURCE_TCATBALF, rawFileStatus, outcome.normalisedResult());
        if (outcome.isAok()) {                                                // Line 336.
            return fetched;
        }
        if (outcome.isEof()) {                                                // Lines 339 to 340.
            run.markEndOfFile();
            return fetched;
        }
        LOG.error(ERROR_READING_TCATBAL);                                     // Line 342.
        displayIoStatus(rawFileStatus, OPERATION_READ, RESOURCE_TCATBALF);    // Lines 343 to 344.
        throw abendProgram(rawFileStatus, OPERATION_READ, RESOURCE_TCATBALF); // Line 345.
    }

    /**
     * Paragraph {@code 1050-UPDATE-ACCOUNT}, lines 350 to 370: the account control break.
     *
     * <p>Three things happen, in this order. The group's running interest total is added to the
     * account's current balance at line 352. <strong>Both</strong> cycle accumulators are then zeroed -
     * the current-cycle credit at line 353 <em>and</em> the current-cycle debit at line 354. The account
     * is finally rewritten at line 356. Zeroing only one of the two accumulators would leave a
     * half-closed cycle that every later run would compound, so both are stated explicitly here.
     *
     * <p>A concurrent-modification failure is allowed to propagate untranslated, because this member
     * has no arm that handles one; every other data-access failure is the rewrite error arm at lines
     * 365 to 368.
     *
     * @param account       the account the group read at line 203, carrying the balances to post to
     * @param totalInterest the group's running total, at the monetary scale
     * @return the account as it was rewritten
     */
    private Account updateAccount(final Account account, final BigDecimal totalInterest) {
        // Line 352: ADD WS-TOTAL-INT TO ACCT-CURR-BAL. Both operands carry two decimals, so the sum
        // is exact; it is still routed through the codec because the receiving field has two decimals
        // and the codec is the module's single point of decimal truth.
        account.setAcctCurrBal(
                ZonedDecimalCodec.toMonetaryScale(account.getAcctCurrBal().add(totalInterest)));
        // Lines 353 and 354: BOTH cycle accumulators, not one.
        account.setAcctCurrCycCredit(ZERO_MONETARY);
        account.setAcctCurrCycDebit(ZERO_MONETARY);

        try {
            // Line 356: REWRITE.
            return this.accountRepository.save(account);
        } catch (final OptimisticLockingFailureException conflict) {
            // Deliberately not translated. This member declares no conflict-handling arm - the
            // estate's only explicit rollback is in the account-update program - so the conflict is
            // propagated for the caller to see rather than being reported as a file-status abend.
            throw conflict;
        } catch (final DataAccessException unwritable) {
            // Line 365.
            LOG.error("{} failureChain={}", ERROR_REWRITING_ACCTFILE,
                    FailureDiagnostics.failureChainOf(unwritable));
            final String rawFileStatus = FileStatus.PERMANENT_ERROR.getCode();
            displayIoStatus(rawFileStatus, OPERATION_REWRITE, RESOURCE_ACCTFILE);   // Lines 366-367.
            throw abendProgram(rawFileStatus, OPERATION_REWRITE, RESOURCE_ACCTFILE); // Line 368.
        }
    }

    /**
     * Paragraph {@code 1100-GET-ACCT-DATA}, lines 372 to 391: reads the account master by its key.
     *
     * <p><strong>A missing account abends.</strong> The invalid-key clause at lines 374 to 375 only
     * reports the missing key; the status check that follows at lines 378 to 382 accepts
     * <em>success alone</em>, so a not-found status is normalised to the error value and lines 386 to
     * 389 abend. Returning an empty result to the caller instead would let a run continue past an
     * account it cannot post to.
     *
     * @param accountId the eleven-digit account identifier moved into the key at line 202
     * @return the account
     */
    private Account getAcctData(final String accountId) {
        final Optional<Account> fetched;
        final String rawFileStatus;
        try {
            // Line 373: READ ... INTO.
            fetched = this.accountRepository.findById(accountId);
            rawFileStatus = fetched.isPresent()
                    ? FileStatus.SUCCESS.getCode()
                    : FileStatus.RECORD_NOT_FOUND.getCode();
        } catch (final DataAccessException unreadable) {
            LOG.error("{} failureChain={}", ERROR_READING_ACCTFILE,
                    FailureDiagnostics.failureChainOf(unreadable));
            final String failureStatus = FileStatus.PERMANENT_ERROR.getCode();
            displayIoStatus(failureStatus, OPERATION_READ, RESOURCE_ACCTFILE);
            throw abendProgram(failureStatus, OPERATION_READ, RESOURCE_ACCTFILE);
        }

        if (keyedReadApplResult(rawFileStatus).isAok()) {          // Lines 378 to 384.
            return fetched.orElseThrow();
        }
        LOG.error(ACCOUNT_NOT_FOUND);                              // Lines 374 to 375.
        LOG.error(ERROR_READING_ACCTFILE);                         // Line 386.
        displayIoStatus(rawFileStatus, OPERATION_READ, RESOURCE_ACCTFILE);    // Lines 387 to 388.
        throw abendProgram(rawFileStatus, OPERATION_READ, RESOURCE_ACCTFILE); // Line 389.
    }

    /**
     * Paragraph {@code 1110-GET-XREF-DATA}, lines 393 to 413: reads the cross-reference by the
     * <strong>alternate</strong> key on the account identifier, which the file declaration establishes
     * at line 38.
     *
     * <p>The alternate key is non-unique, so the read resolves the first matching row. The repository's
     * account-scoped finder does exactly that, ordering by card number ascending and reporting absence
     * as an empty result - which is the legacy not-found path, and which the caller must not confuse
     * with an empty group.
     *
     * <p><strong>A missing cross-reference abends</strong>, for the same reason a missing account does:
     * the status check at lines 400 to 404 accepts success alone, so lines 408 to 411 run.
     *
     * @param accountId the eleven-digit account identifier moved into the alternate key at line 204
     * @return the cross-reference row that supplies the card number the synthesized transaction carries
     */
    private CardCrossReference getXrefData(final String accountId) {
        final Optional<CardCrossReference> fetched;
        final String rawFileStatus;
        try {
            // Lines 394 to 398: READ ... KEY IS the alternate key. The finder resolves the first
            // matching row, which is what a non-unique alternate-key read returns.
            fetched = firstXrefByBaseKey(
                    this.cardCrossReferenceRepository.findByXrefAcctId(accountId));
            rawFileStatus = fetched.isPresent()
                    ? FileStatus.SUCCESS.getCode()
                    : FileStatus.RECORD_NOT_FOUND.getCode();
        } catch (final DataAccessException unreadable) {
            LOG.error("{} failureChain={}", ERROR_READING_XREFFILE,
                    FailureDiagnostics.failureChainOf(unreadable));
            final String failureStatus = FileStatus.PERMANENT_ERROR.getCode();
            displayIoStatus(failureStatus, OPERATION_READ, RESOURCE_XREFFILE);
            throw abendProgram(failureStatus, OPERATION_READ, RESOURCE_XREFFILE);
        }

        if (keyedReadApplResult(rawFileStatus).isAok()) {          // Lines 400 to 406.
            return fetched.orElseThrow();
        }
        LOG.error(ACCOUNT_NOT_FOUND);                              // Lines 396 to 397.
        LOG.error(ERROR_READING_XREFFILE);                         // Line 408.
        displayIoStatus(rawFileStatus, OPERATION_READ, RESOURCE_XREFFILE);    // Lines 409 to 410.
        throw abendProgram(rawFileStatus, OPERATION_READ, RESOURCE_XREFFILE); // Line 411.
    }

    /**
     * Paragraph {@code 1200-GET-INTEREST-RATE}, lines 415 to 440: resolves the interest rate for one
     * category-balance row from the disclosure group.
     *
     * <p><strong>Two statuses are acceptable, not one.</strong> Line 422 accepts success
     * <em>or</em> record-not-found; anything else is normalised to the error value and lines 431 to 434
     * report the raw two-byte status and abend. A not-found is therefore not a failure here - it is the
     * trigger for the single fallback at lines 436 to 439.
     *
     * <p><strong>The key's construction order is not the source's move order.</strong> Lines 210 to 212
     * move the parts in the order group, then <em>category</em>, then <em>type</em>. The composite key
     * is ordered group, then <em>type</em>, then <em>category</em> - fixed by the copybook field order
     * at {@code app/cpy/CVTRA02Y.cpy} lines 6 to 8, by the cluster's key definition, and by this
     * member's own field declarations at lines 79 to 81. The move order is an artefact of how the
     * source happens to read; the key order is the contract. Typing the arguments in the order the
     * moves appear produces a key that compiles, looks right and resolves nothing.
     *
     * @param accountGroupId the account's ten-character group identifier, moved at line 210
     * @param tranTypeCd     the row's two-character type code, moved at line 212
     * @param tranCatCd      the row's four-digit category code, moved at line 211
     * @return the disclosure group that supplied the rate, and whether the default group supplied it
     */
    private ResolvedRate getInterestRate(final String accountGroupId, final String tranTypeCd,
            final String tranCatCd) {
        // ---------------------------------------------------------------------------------------
        // KEY ORDER: group, TYPE, CATEGORY.
        // The source moves these parts in the order group, CATEGORY, TYPE at lines 210 to 212.
        // DO NOT reorder the arguments below to match that sequence. The copybook field order
        // (app/cpy/CVTRA02Y.cpy lines 6 to 8) and this member's key declaration (lines 79 to 81)
        // both put the type code before the category code, and that ordering is the record key.
        // ---------------------------------------------------------------------------------------
        final DisclosureGroupId probeKey =
                new DisclosureGroupId(accountGroupId, tranTypeCd, tranCatCd);

        final Optional<DisclosureGroup> direct =
                probeDisclosureGroup(probeKey, ERROR_READING_DISCGRP, RESOURCE_DISCGRP);
        if (direct.isPresent()) {
            // Success: the acceptable status at line 422 that needs no fallback.
            return new ResolvedRate(direct.get(), false);
        }

        // Record-not-found: the other acceptable status, which lines 436 to 439 act on.
        LOG.warn(DISCGRP_RECORD_MISSING);                                     // Line 418.
        LOG.warn(TRY_WITH_DEFAULT_GROUP_CODE);                                // Line 419.
        return new ResolvedRate(getDefaultIntRate(tranTypeCd, tranCatCd), true); // Lines 437 to 438.
    }

    /**
     * Paragraph {@code 1200-A-GET-DEFAULT-INT-RATE}, lines 443 to 460: the single fallback probe.
     *
     * <p><strong>This retries exactly once and then abends. There is no third attempt.</strong> Unlike
     * the paragraph that calls it, this one has no invalid-key clause at all and its status check at
     * line 446 accepts <em>success alone</em>, so a second miss is normalised to the error value and
     * lines 455 to 458 report the raw status and abend. There is no loop here, no configurable retry
     * count, no framework retry, no backoff and no delay - the retry count is the literal one.
     *
     * <p><strong>The probe key carries the padded ten-character group literal.</strong> Line 437 moves
     * the seven-character literal into a ten-character field, so the value probed is the literal
     * followed by three spaces. The seven-character form resolves nothing. The padding is built and
     * held by this service and is never trimmed and never pushed down to the repository, which
     * receives a key and applies no normalisation of its own.
     *
     * <p>The type and category codes are <strong>unchanged</strong> from the first probe: line 437
     * replaces only the group identifier.
     *
     * @param tranTypeCd the row's two-character type code, carried over from the first probe
     * @param tranCatCd  the row's four-digit category code, carried over from the first probe
     * @return the default group's disclosure row
     */
    private DisclosureGroup getDefaultIntRate(final String tranTypeCd, final String tranCatCd) {
        // Same key order as the first probe: group, TYPE, CATEGORY. Only the group changes.
        final DisclosureGroupId defaultKey =
                new DisclosureGroupId(DEFAULT_ACCOUNT_GROUP_ID, tranTypeCd, tranCatCd);

        // Line 444: READ ... INTO, with no invalid-key clause.
        final Optional<DisclosureGroup> fallback =
                probeDisclosureGroup(defaultKey, ERROR_READING_DEFAULT_DISCGRP, RESOURCE_DISCGRP);
        if (fallback.isPresent()) {                                 // Lines 446 to 453.
            return fallback.get();
        }
        // A second miss. Only success is acceptable here, so this is the error arm.
        final String rawFileStatus = FileStatus.RECORD_NOT_FOUND.getCode();
        LOG.error(ERROR_READING_DEFAULT_DISCGRP);                             // Line 455.
        displayIoStatus(rawFileStatus, OPERATION_READ, RESOURCE_DISCGRP);     // Lines 456 to 457.
        throw abendProgram(rawFileStatus, OPERATION_READ, RESOURCE_DISCGRP);  // Line 458.
    }

    /**
     * Paragraph {@code 1300-COMPUTE-INTEREST}, lines 462 to 470: computes one row's monthly interest,
     * adds it to the group's running total and writes the transaction - in that order.
     *
     * <h2>The expression is reproduced operand for operand</h2>
     *
     * <p>Lines 464 to 465 <strong>multiply the category balance by the rate and only then divide by
     * 1200</strong>, storing into a field declared at line 168 with nine integer digits and two
     * decimals. Dividing the rate by 1200 first is algebraically identical in exact arithmetic and
     * <em>wrong</em> here, because it moves where the truncation happens. The expression is therefore
     * never rearranged, never re-associated and never simplified.
     *
     * <p><strong>Every store into a two-decimal field truncates toward zero</strong>, because the estate
     * contains no rounding clause anywhere - not one occurrence in any program or copybook. All scaling
     * goes through the codec, which is the module's single point of decimal truth and applies the
     * truncating mode. The conventional Java choice of a half-even mode would differ by one cent on
     * roughly half of all interest computations: a byte-parity failure that is invisible to any test
     * written under the same assumption. Nothing here uses an approximate binary numeric type.
     *
     * <p>Line 467 adds the interest to the running total, and line 468 then writes the transaction. The
     * order is load bearing and is preserved: the total already includes this row's interest by the time
     * the transaction is written.
     *
     * @param categoryBalance the row's balance at the monetary scale
     * @param disclosedRate   the resolved rate at the monetary scale, already known to be non-zero
     * @param context         the group's account, cross-reference and parameter date
     * @param group           the group's per-invocation state, whose running total this updates
     * @return the interest computed and the transaction written
     */
    private ComputedInterest computeInterest(final BigDecimal categoryBalance,
            final BigDecimal disclosedRate, final GroupContext context, final GroupState group) {
        // Lines 464 to 465. MULTIPLY FIRST, THEN DIVIDE. Do not rearrange.
        final BigDecimal product = categoryBalance.multiply(disclosedRate);
        final BigDecimal quotient = product.divide(MONTHLY_INTEREST_DIVISOR,
                INTERMEDIATE_QUOTIENT_SCALE, ZonedDecimalCodec.COBOL_TRUNCATION_MODE);
        final BigDecimal monthlyInterest = ZonedDecimalCodec.toMonetaryScale(quotient);

        // Line 467, and only then line 468.
        group.addToTotalInterest(monthlyInterest);
        final Transaction interestTransaction = writeTx(monthlyInterest, context, group);

        return new ComputedInterest(monthlyInterest, interestTransaction);
    }

    /**
     * Paragraph {@code 1300-B-WRITE-TX}, lines 473 to 515: synthesizes the interest transaction,
     * field by field, in the order the source assigns them, for the batch job's guarded SYSTRAN
     * generation writer.
     *
     * <ul>
     *   <li>Line 474 increments the six-digit suffix, whose initial value is zero, so the first
     *       identifier of a run carries suffix one.</li>
     *   <li>Lines 476 to 480 concatenate the ten-character parameter date with that suffix rendered at
     *       six digits, giving an identifier of <strong>exactly sixteen characters</strong>. The
     *       right-justified zero fill is what renders the suffix; an unpadded decimal rendering would
     *       produce a short identifier, and no sequence or generated value is involved anywhere.</li>
     *   <li>Line 482 sets the type code to the two-character literal.</li>
     *   <li>Line 483 moves a two-character literal into a <strong>four-digit</strong> category field, so
     *       the stored value is four characters.</li>
     *   <li>Line 484 moves a six-character literal into a ten-character source field, so the stored
     *       value carries trailing spaces and is never trimmed.</li>
     *   <li>Lines 485 to 489 concatenate the description literal - <strong>whose trailing space is
     *       part of it</strong> - with the account identifier rendered at its full eleven digits.</li>
     *   <li>Line 490 sets the amount to the interest just computed.</li>
     *   <li>Line 491 moves numeric zero into a nine-digit merchant identifier, so the stored value is
     *       nine zero characters as a string and never the number zero.</li>
     *   <li>Lines 492 to 494 move spaces into the merchant name, city and postal code at their full
     *       widths. All three of these, and the identifier above, are <em>unprefixed</em> attributes on
     *       the transaction, and each of the four is set individually.</li>
     *   <li>Line 495 takes the card number from the cross-reference the group read.</li>
     *   <li>Line 496 builds the batch timestamp <strong>once</strong>, and lines 497 and 498 move that
     *       one value into <em>both</em> the origination and the processing timestamp, so the two are
     *       byte-identical.</li>
     * </ul>
     *
     * <p>The legacy WRITE targets the sequential {@code SYSTRAN(+1)} generation allocated by
     * {@code INTCALC.jcl}; it does not target the live transaction master. This method therefore
     * returns the complete record without persisting it. The job configuration owns the sole guarded
     * write and maps its output status at the actual file-I/O boundary.
     *
     * @param monthlyInterest the amount to carry, at the monetary scale
     * @param context         the group's account, cross-reference and parameter date
     * @param group           the group's per-invocation state, which holds the identifier suffix
     * @return the transaction ready for the guarded generation writer
     */
    private Transaction writeTx(final BigDecimal monthlyInterest, final GroupContext context,
            final GroupState group) {
        // Line 474.
        final long suffix = group.nextTranIdSuffix();

        // Lines 476 to 480. The right-justified zero fill retains the rightmost characters, which is
        // also how the six-digit field would shed a high-order digit if a run ever minted more than a
        // million identifiers - so the rendering carries the legacy field's truncation for free.
        final String tranId = context.parameterDate()
                + CobolStringUtils.rightJustifyZeroFill(Long.toString(suffix), TRANID_SUFFIX_WIDTH);

        // Line 496, performed once. Lines 497 and 498 then move this one value into both timestamps.
        final String batchTimestamp = zGetDb2FormatTimestamp();

        final Transaction interestTransaction = new Transaction(
                tranId,                                                   // Lines 476 to 480.
                INTEREST_TRAN_TYPE_CD,                                    // Line 482.
                INTEREST_TRAN_CAT_CD,                                     // Line 483.
                INTEREST_TRAN_SOURCE,                                     // Line 484.
                interestDescriptionFor(context.account().getAcctId()),    // Lines 485 to 489.
                monthlyInterest,                                          // Line 490.
                INTEREST_MERCHANT_ID,                                     // Line 491.
                BLANK_MERCHANT_NAME,                                      // Line 492.
                BLANK_MERCHANT_CITY,                                      // Line 493.
                BLANK_MERCHANT_ZIP,                                       // Line 494.
                context.crossReference().getXrefCardNum(),                // Line 495.
                batchTimestamp,                                           // Line 497.
                batchTimestamp);                                          // Line 498, the same value.

        // Line 500 is performed by InterestCalculationJobConfig's guarded SYSTRAN writer. Returning
        // the record here keeps INTCALC from exposing it in the live master before COMBTRAN runs.
        return interestTransaction;
    }

    /**
     * Paragraph {@code 1400-COMPUTE-FEES}, lines 518 to 520.
     *
     * <p><strong>The legacy body is empty.</strong> It consists of a single comment marking the paragraph
     * as unimplemented, followed by the exit statement - and nothing else. There is no fee calculation in
     * the legacy program, in any copybook it includes, or anywhere else in the estate.
     *
     * <p><strong>The paragraph is nonetheless genuinely invoked, at line 216</strong>, from inside the
     * rate gate, which is why it survives here as a named method rather than disappearing. It is
     * deliberately kept empty:
     *
     * <ul>
     *   <li><strong>No fee logic may be invented.</strong> Adding any would be feature expansion, and
     *       it would change what an interest run outputs - which is a byte-parity failure against the
     *       documented baseline, not an improvement.</li>
     *   <li>It is not deleted and not inlined away, because it carries its own traceability row -
     *       recorded honestly as a documented non-implementation rather than as a translation.</li>
     *   <li>It does not throw, because the legacy invocation completes normally and the interest run
     *       continues.</li>
     *   <li>It logs nothing that would imply a defect, because an unimplemented paragraph that the
     *       program intends to invoke is not a runtime problem.</li>
     *   <li>It is not deprecated, because nothing supersedes it.</li>
     * </ul>
     *
     * <p>If fees are ever required, they belong in a change that states the requirement, not in a
     * migration whose contract is to reproduce existing behaviour exactly.
     */
    private void computeFees() {
        // Intentionally empty: the legacy paragraph at lines 518 to 520 has no body. See the contract
        // above - inventing fee logic here would be feature expansion and would change run output.
    }

    /**
     * Paragraph {@code 9000-TCATBALF-CLOSE}, lines 522 to 538: closes the category-balance master.
     * Retained as a named, invoked method for the reason given on {@code tcatbalfOpen}.
     *
     * @return the normalised coarse outcome
     */
    private ApplResult tcatbalfClose() {
        LOG.debug("close resource={} owner=container", RESOURCE_TCATBALF);
        return ApplResult.OK;
    }

    /**
     * Paragraph {@code 9100-XREFFILE-CLOSE}, lines 541 to 557: closes the cross-reference. Retained as
     * a named, invoked method for the reason given on {@code tcatbalfOpen}.
     *
     * @return the normalised coarse outcome
     */
    private ApplResult xreffileClose() {
        LOG.debug("close resource={} owner=container", RESOURCE_XREFFILE);
        return ApplResult.OK;
    }

    /**
     * Paragraph {@code 9200-DISCGRP-CLOSE}, lines 559 to 575: closes the disclosure group. Retained as
     * a named, invoked method for the reason given on {@code tcatbalfOpen}.
     *
     * @return the normalised coarse outcome
     */
    private ApplResult discgrpClose() {
        LOG.debug("close resource={} owner=container", RESOURCE_DISCGRP);
        return ApplResult.OK;
    }

    /**
     * Paragraph {@code 9300-ACCTFILE-CLOSE}, lines 577 to 593: closes the account master. Retained as a
     * named, invoked method for the reason given on {@code tcatbalfOpen}.
     *
     * @return the normalised coarse outcome
     */
    private ApplResult acctfileClose() {
        LOG.debug("close resource={} owner=container", RESOURCE_ACCTFILE);
        return ApplResult.OK;
    }

    /**
     * Paragraph {@code 9400-TRANFILE-CLOSE}, lines 595 to 611: closes the transaction file. Retained as
     * a named, invoked method for the reason given on {@code tcatbalfOpen}.
     *
     * @return the normalised coarse outcome
     */
    private ApplResult tranfileClose() {
        LOG.debug("close resource={} owner=container", RESOURCE_TRANSACT);
        return ApplResult.OK;
    }

    /**
     * Paragraph {@code Z-GET-DB2-FORMAT-TIMESTAMP}, lines 613 to 626: builds the
     * <strong>batch</strong> 26-character timestamp from the current instant.
     *
     * <p>The instant comes from the injected clock, which is what makes the assembled value assertable
     * in a test rather than a moving target.
     *
     * @return the 26-character batch timestamp
     */
    private String zGetDb2FormatTimestamp() {
        // Line 614 reads the current date and time; lines 615 to 624 lay the parts out and drop the
        // separators in. The assembly itself is below, so that the paragraph stays one statement long
        // and the format stays in one place.
        return formatBatchTimestamp(LocalDateTime.now(this.clock));
    }

    /**
     * Paragraph {@code 9999-ABEND-PROGRAM}, line 628, whose language-environment abort call is at line
     * 632: reports the failure and terminates the run.
     *
     * <p><strong>Emit, then abend - in that order.</strong> Every call site logs its own diagnostic and
     * then the raw two-byte status through the shared status reporter before reaching this method, and
     * the abend collaborator logs the abend line before it throws. Nothing about a failure is lost to a
     * throw that outran its own diagnostic.
     *
     * <p>The collaborator always throws, so control never returns from the call below. The exception is
     * <em>returned</em> rather than thrown from here purely so that every call site can be written as a
     * throw statement and satisfy the compiler's definite-return analysis at one cost rather than one
     * per site.
     *
     * @param rawFileStatus the raw two-byte file status, which the abend carries as its reason
     * @param operation     the operation that failed, for the diagnostic
     * @param resourceName  the dataset the operation was against, for the diagnostic
     * @return never returns normally; the declared return exists only so call sites can throw it
     */
    private RuntimeException abendProgram(final String rawFileStatus, final String operation,
            final String resourceName) {
        // Line 629 reports the abend, lines 630 to 631 set the abort code, and line 632 issues the
        // language-environment abort call. The collaborator does all three and then throws.
        this.abendService.abendOnFileStatus(PROGRAM_NAME, rawFileStatus, operation, resourceName);
        return new IllegalStateException("the abend collaborator returned instead of terminating"
                + " the run for program " + PROGRAM_NAME + " on file status " + rawFileStatus);
    }

    /**
     * Paragraph {@code 9910-DISPLAY-IO-STATUS}, lines 635 to 648: reports the raw two-byte file status.
     *
     * <p>The legacy paragraph renders the status into a four-character display field - taking one branch
     * for a numeric status and another for a status whose first byte marks it as implementation defined,
     * at lines 636 to 646 - and writes it with a fixed prefix. Both branches, the prefix and the
     * rendering live in the shared collaborator, so every program in the estate reports a status the
     * same way; this method is the paragraph's own entry point into it.
     *
     * <p>This is never used for end of file. End of file is not a failure, and the collaborator's own
     * abend entry point refuses that status outright.
     *
     * @param rawFileStatus the raw two-byte file status
     * @param operation     the operation that produced it
     * @param resourceName  the dataset the operation was against
     */
    private void displayIoStatus(final String rawFileStatus, final String operation,
            final String resourceName) {
        this.abendService.displayIoStatus(rawFileStatus, operation, resourceName);
    }

    /* ======================================================================================== */
    /* PROCEDURE-DIVISION INLINE CODE AND SHARED PRIMITIVES                                      */
    /* ======================================================================================== */

    /**
     * Lines 210 to 217 for one category-balance row: resolve the rate, then gate the computation and the
     * fee invocation on it.
     *
     * <p><strong>The rate gate encloses both.</strong> Line 214 tests that the rate is not zero and the
     * matching end of the condition is at line 217, so lines 215 <em>and</em> 216 are both inside it.
     * When the rate is zero, neither the computation nor the fee invocation happens. The fee call is
     * never hoisted out of the gate and never made unconditional.
     *
     * <p>The comparison is by value rather than by scale, so a rate stored with a different number of
     * decimal places still compares equal to zero. A scale-sensitive equality test would treat a rate of
     * zero written at a different scale as non-zero and would accrue interest of zero for it, minting a
     * transaction the legacy never writes.
     *
     * @param row                  the category-balance row being accrued
     * @param context              the group's account, cross-reference and parameter date
     * @param group                the group's per-invocation state
     * @param interestTransactions the group's transaction list, appended to when a transaction is written
     * @return what this row decided
     */
    private CategoryInterest accrueCategoryRow(final TransactionCategoryBalance row,
            final GroupContext context, final GroupState group,
            final List<Transaction> interestTransactions) {
        // Lines 210 to 213.
        final ResolvedRate resolved = getInterestRate(context.account().getAcctGroupId(),
                row.getTrancatTypeCd(), row.getTrancatCd());
        if (resolved.defaultGroupUsed()) {
            group.markDefaultGroupUsed();
        }
        final BigDecimal disclosedRate =
                ZonedDecimalCodec.toMonetaryScale(resolved.disclosureGroup().getDisIntRate());
        final BigDecimal categoryBalance = ZonedDecimalCodec.toMonetaryScale(row.getTranCatBal());

        // Line 214 to line 217: the gate around BOTH line 215 and line 216.
        if (disclosedRate.signum() == 0) {
            group.markRateGateSkipped();
            LOG.debug("rate gate skipped type={} category={} reason=ZERO_RATE",
                    row.getTrancatTypeCd(), row.getTrancatCd());
            LOG.debug("rate gate skipped accountRef={} type={} category={} rate=0",
                    SensitiveLogRedactor.redact(row.getTrancatAcctId()),
                    row.getTrancatTypeCd(), row.getTrancatCd());
            return new CategoryInterest(row.getTrancatAcctId(),
                    row.getTrancatTypeCd(),
                    row.getTrancatCd(),
                    categoryBalance,
                    disclosedRate,
                    ZERO_MONETARY,
                    true,
                    resolved.defaultGroupUsed(),
                    null);
        }

        final ComputedInterest computed =
                computeInterest(categoryBalance, disclosedRate, context, group);   // Line 215.
        computeFees();                                                             // Line 216.
        interestTransactions.add(computed.interestTransaction());

        return new CategoryInterest(row.getTrancatAcctId(),
                row.getTrancatTypeCd(),
                row.getTrancatCd(),
                categoryBalance,
                disclosedRate,
                computed.monthlyInterest(),
                false,
                resolved.defaultGroupUsed(),
                computed.interestTransaction());
    }

    /**
     * Closes one buffered account group and folds its outcome back into the run.
     *
     * <p>The group boundary is entered through {@link InterestGroupTransactionBoundary}, even when this
     * whole-file driver reaches it on the same service instance. Every closed account therefore commits
     * independently; no encompassing driver transaction can absorb the control break.
     *
     * <p>The identifier suffix is threaded through here: the group is told where to continue from and
     * reports back where it finished, because the suffix at line 173 belongs to the run and not to any
     * one group.
     *
     * @param parameterDate the ten-character date
     * @param group         the buffered rows of one account, in record-key order and never empty
     * @param run           the run's per-invocation state
     * @return what the group produced
     */
    private GroupInterestResult closeGroup(final String parameterDate,
            final List<TransactionCategoryBalance> group, final RunState run) {
        final GroupInterestResult closed = calculateGroupInterest(parameterDate,
                group.get(0).getTrancatAcctId(), group, run.tranIdSuffix());
        run.rememberTranIdSuffix(closed.lastTranIdSuffix());
        run.mergeGroupFlags(closed.rateGateSkipped(), closed.defaultGroupUsed());
        return closed;
    }

    /**
     * Reads the whole category-balance master in record-key order, which is the sequential access the
     * file declaration at lines 28 to 32 specifies.
     *
     * <p>The ordering is stated explicitly because the repository imposes none. Without it the rows would
     * arrive in whatever order the store found convenient, the control break at line 194 would fire on
     * almost every row, and an account's interest would be posted in fragments - one rewrite per
     * fragment - instead of once.
     *
     * @return every category-balance row, in record-key order
     */
    private Iterable<TransactionCategoryBalance> readCategoryBalanceMasterInKeyOrder() {
        return () -> new BoundedKeysetIterator<>("", KEYSET_PAGE_SIZE,
                this::loadCategoryBalancePage,
                InterestCalculationService::categoryBalanceKey,
                Comparator.naturalOrder());
    }

    private List<TransactionCategoryBalance> loadCategoryBalancePage(
            final String cursor, final Integer pageSize) {
        try {
            final String accountId = keyPart(cursor, 0, TRAN_CAT_BAL_ACCOUNT_KEY_LENGTH);
            final int typeOffset = TRAN_CAT_BAL_ACCOUNT_KEY_LENGTH;
            final String typeCode = keyPart(cursor, typeOffset, TRAN_CAT_BAL_TYPE_KEY_LENGTH);
            final int categoryOffset = typeOffset + TRAN_CAT_BAL_TYPE_KEY_LENGTH;
            final String categoryCode = cursor.length() <= categoryOffset
                    ? ""
                    : cursor.substring(categoryOffset);
            return this.transactionCategoryBalanceRepository.findAfterKey(
                    accountId, typeCode, categoryCode,
                    PageRequest.of(0, pageSize.intValue()));
        } catch (final DataAccessException unreadable) {
            LOG.error("{} failureChain={}", ERROR_READING_TCATBAL,
                    FailureDiagnostics.failureChainOf(unreadable));
            final String rawFileStatus = FileStatus.PERMANENT_ERROR.getCode();
            displayIoStatus(rawFileStatus, OPERATION_READ, RESOURCE_TCATBALF);
            throw abendProgram(rawFileStatus, OPERATION_READ, RESOURCE_TCATBALF);
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

    /**
     * Returns the account identifier the control break at line 194 keys on, refusing a row that cannot
     * supply one.
     *
     * <p>The key is checked at the point the control break consumes it rather than left to fail later,
     * because a row without an account identifier would either group with whatever preceded it - posting
     * one account's interest to another - or fail with a bare reference error that says nothing about why
     * it mattered. The stored column is declared not-null, so this only ever fires on a row assembled
     * outside the store.
     *
     * @param row the record just read
     * @return the row's account identifier
     */
    private static String requiredAccountKey(final TransactionCategoryBalance row) {
        Objects.requireNonNull(row, "a category-balance row must not be null");
        return Objects.requireNonNull(row.getTrancatAcctId(),
                "a category-balance row must carry its account identifier, because the control break"
                        + " at line 194 is driven by that key");
    }

    /**
     * Line 193: reports the record just read.
     *
     * <p>The legacy statement writes the whole 50-byte record image to the console for every row. The
     * account identifier and balance form protected financial telemetry and are deliberately withheld
     * from the exported log. The two reference codes are retained because they identify the decision
     * table branch without identifying an account or disclosing a value.
     *
     * @param row the record just read
     */
    private static void logCategoryBalanceRecord(final TransactionCategoryBalance row) {
        LOG.debug("category balance read type={} category={}",
                row.getTrancatTypeCd(), row.getTrancatCd());
        LOG.debug("category balance read accountRef={} type={} category={}",
                SensitiveLogRedactor.redact(row.getTrancatAcctId()),
                row.getTrancatTypeCd(), row.getTrancatCd());
    }

    /**
     * Reads one disclosure-group row by its three-part key, mapping absence to an empty result and a
     * data-access failure to the error arm.
     *
     * <p>Absence is deliberately expressed as an empty result rather than as a thrown not-found, so that
     * the caller's own status branch decides what it means: at lines 415 to 440 a miss is
     * <em>acceptable</em> and triggers the single fallback, while at lines 443 to 460 the very same miss
     * is the error arm. Signalling absence by exception would force one of those two call sites to catch
     * and continue, which is exactly the shape that makes a one-retry rule quietly become a two-retry
     * rule.
     *
     * @param key          the three-part key, ordered group, type, category
     * @param diagnostic   the calling paragraph's own failure text, which differs between the two
     *                     paragraphs that read this dataset
     * @param resourceName the dataset name for the diagnostic
     * @return the row, or empty when the key resolves to nothing
     */
    private Optional<DisclosureGroup> probeDisclosureGroup(final DisclosureGroupId key,
            final String diagnostic, final String resourceName) {
        try {
            return this.disclosureGroupRepository.findById(key);
        } catch (final DataAccessException unreadable) {
            LOG.error("{} failureChain={}", diagnostic,
                    FailureDiagnostics.failureChainOf(unreadable));
            final String rawFileStatus = FileStatus.PERMANENT_ERROR.getCode();
            displayIoStatus(rawFileStatus, OPERATION_READ, resourceName);
            throw abendProgram(rawFileStatus, OPERATION_READ, resourceName);
        }
    }

    /**
     * Lines 485 to 489: the interest transaction's description.
     *
     * <p>The literal's <strong>trailing space is part of the literal</strong> and separates it from the
     * account identifier. The identifier is rendered at its full eleven digits, zero-filled on the left,
     * because the sending field is an eleven-digit numeric item and a shorter rendering would shift every
     * following character. The concatenation is not padded out to the description field's full width,
     * because the source assembles it with a statement that leaves the remainder of the receiving field
     * untouched.
     *
     * @param accountId the account identifier
     * @return the description
     */
    private static String interestDescriptionFor(final String accountId) {
        return INTEREST_DESCRIPTION_PREFIX
                + CobolStringUtils.rightJustifyZeroFill(accountId, ACCT_ID_WIDTH);
    }

    /**
     * Assembles the <strong>batch</strong> 26-character timestamp from one instant.
     *
     * <p>This is a private helper on purpose. The estate carries <strong>two different</strong>
     * 26-character timestamp forms and they must never be unified or swapped. The batch form assembled
     * here separates the date from the time with a <em>hyphen</em>, separates the time's parts with
     * <em>dots</em>, carries a <em>two-digit</em> hundredths field and ends with a literal four-character
     * tail. The online tier's form carries a <em>space</em> before the time, <em>colons</em> inside it and
     * a six-digit fraction. Emitting the online form from a batch run produces a value of exactly the
     * right length that is wrong in four character positions - which breaks byte parity without breaking
     * anything that would notice.
     *
     * <p>Keeping the assembly private rather than promoting it to a shared type is what stops the two
     * forms from being consolidated later by someone who sees two 26-character formatters and assumes
     * one is redundant.
     *
     * <p>The width follows arithmetically from the pattern once the year fits in four characters, so the
     * year is the only part that is range-checked.
     *
     * @param moment the instant to render
     * @return the 26-character batch timestamp
     * @throws IllegalArgumentException when the year cannot be held in the four-character year field
     */
    private static String formatBatchTimestamp(final LocalDateTime moment) {
        final int year = moment.getYear();
        if (year < MIN_REPRESENTABLE_YEAR || year > MAX_REPRESENTABLE_YEAR) {
            throw new IllegalArgumentException("year " + year + " cannot be held in the legacy"
                    + " four-character year field; expected " + MIN_REPRESENTABLE_YEAR + " to "
                    + MAX_REPRESENTABLE_YEAR);
        }
        // The root locale is mandatory: a locale with non-Latin digits would render digits this
        // fixed-width external contract cannot carry.
        return String.format(Locale.ROOT, BATCH_TIMESTAMP_PATTERN,
                year,
                moment.getMonthValue(),
                moment.getDayOfMonth(),
                moment.getHour(),
                moment.getMinute(),
                moment.getSecond(),
                moment.getNano() / NANOS_PER_HUNDREDTH,
                BATCH_TIMESTAMP_TAIL);
    }

    /**
     * The alphanumeric move rule: the sender is placed at the <strong>left</strong> of the receiver, any
     * excess is lost from the right, and a short sender leaves the right of the receiver space-filled.
     *
     * <p>This is not the same rule as the right-justified zero fill the shared string utilities provide,
     * and the difference is load bearing here. The default group literal is moved into a ten-character
     * field and must become the literal followed by three spaces; passing it through a right-justified
     * zero fill would instead produce three zeros followed by the literal, which resolves nothing. The
     * shared utilities carry no left-justifying primitive, so the rule is stated here rather than
     * borrowed from one that means something else.
     *
     * @param value the sending value, which may be empty to fill the receiver with spaces
     * @param width the receiving field's character-position count
     * @return the receiver's contents, exactly {@code width} characters
     */
    private static String alphanumericMove(final String value, final int width) {
        Objects.requireNonNull(value, "value must not be null: an absent field is not a blank field");
        if (width <= 0) {
            throw new IllegalArgumentException(
                    "width must be a positive character-position count but was " + width);
        }
        if (value.length() >= width) {
            // A longer sender is truncated on the right, which is where an alphanumeric move loses it.
            return value.substring(0, width);
        }
        final StringBuilder receiver = new StringBuilder(width).append(value);
        while (receiver.length() < width) {
            receiver.append(' ');
        }
        return receiver.toString();
    }

    /**
     * The three-way status normalisation of the sequential read paragraph, lines 327 to 335: success
     * becomes the success value, end of file becomes the end-of-file value, and
     * <strong>anything else</strong> becomes the error value.
     *
     * <p>This is the normaliser that keeps end of file distinct from error. Folding the two together
     * would turn the ordinary termination of a sequential scan into an abend.
     *
     * @param rawFileStatus the raw two-byte file status
     * @return the normalised coarse outcome
     */
    private static ApplResult sequentialReadApplResult(final String rawFileStatus) {
        if (FileStatus.SUCCESS.getCode().equals(rawFileStatus)) {
            return ApplResult.OK;
        }
        if (FileStatus.END_OF_FILE.getCode().equals(rawFileStatus)) {
            return ApplResult.END_OF_FILE;
        }
        return ApplResult.ERROR;
    }

    /**
     * The <strong>two-way</strong> status normalisation the keyed paragraphs use - the account read at
     * lines 378 to 382, the cross-reference read at lines 400 to 404, the account rewrite at lines 357 to
     * 361 and the transaction write at lines 501 to 505: success becomes the success value and
     * <em>everything else</em>, including a not-found, becomes the error value.
     *
     * <p>It is a separate normaliser from the sequential one on purpose. These paragraphs have no
     * end-of-file arm at all, and giving them one would let a keyed read that found nothing be mistaken
     * for the end of a scan.
     *
     * @param rawFileStatus the raw two-byte file status
     * @return the normalised coarse outcome
     */
    private static ApplResult keyedReadApplResult(final String rawFileStatus) {
        if (FileStatus.SUCCESS.getCode().equals(rawFileStatus)) {
            return ApplResult.OK;
        }
        return ApplResult.ERROR;
    }

    /**
     * Validates the ten-character date the linkage area at lines 175 to 180 supplies.
     *
     * <p>It stays a <strong>ten-character string</strong> and is never converted to a temporal type,
     * because it is not consumed as a date anywhere in this member: it is the first ten characters of
     * every identifier the run mints, and converting and reformatting it would risk changing those
     * characters. Only its presence and its width are checked - the width because an identifier is
     * assembled from it positionally and a value of any other length would produce an identifier of the
     * wrong size.
     *
     * @param parameterDate the supplied value
     * @return the same value, once validated
     * @throws IllegalArgumentException when the value is absent or is not exactly ten characters
     */
    private static String validatedParameterDate(final String parameterDate) {
        if (parameterDate == null) {
            throw new IllegalArgumentException("the run date job parameter is required: it supplies the"
                    + " first " + PARM_DATE_WIDTH + " characters of every interest transaction"
                    + " identifier");
        }
        if (parameterDate.length() != PARM_DATE_WIDTH) {
            throw new IllegalArgumentException("the run date job parameter must be exactly "
                    + PARM_DATE_WIDTH + " characters, matching the legacy linkage field, but \""
                    + parameterDate + "\" has length " + parameterDate.length());
        }
        return parameterDate;
    }

    /* ======================================================================================== */
    /* THE COARSE STATUS MODEL, AND PER-INVOCATION STATE                                         */
    /* ======================================================================================== */

    /**
     * The coarse application result at lines 133 to 135, and the two level-88 condition names declared
     * on it: the success condition at line 134 and the end-of-file condition at line 135 become the
     * predicate methods below, and a move of a normalised value becomes an assignment of a constant.
     *
     * <p>This is the <strong>second</strong> level of the estate's two-level status model. A raw two-byte
     * file status is normalised into one of these three values first, and the program then branches on
     * the normalised value rather than on the raw code - which is why a single flat enumeration of raw
     * codes cannot express what the batch tier actually tests. The raw vocabulary lives in the shared
     * file-status enumeration; this is the coarse layer above it.
     *
     * <p>It is nested and private on purpose. The batch tier's step template declares its own equivalent,
     * and neither may be promoted to a shared top-level type: the coarse value is a property of how one
     * program normalises its own statuses, and a shared type would invite a fourth value or a shared
     * default that no program actually has.
     */
    private enum ApplResult {

        /** The value the success condition at line 134 tests for. */
        OK(APPL_RESULT_AOK),

        /** The value the end-of-file condition at line 135 tests for. */
        END_OF_FILE(APPL_RESULT_EOF),

        /** The value moved on any status the program treats as a failure. */
        ERROR(APPL_RESULT_ERROR);

        private final int normalisedResult;

        ApplResult(final int normalisedValue) {
            this.normalisedResult = normalisedValue;
        }

        /**
         * Returns the exact normalised value the legacy field would hold, which is what makes the
         * correspondence to the source checkable rather than asserted.
         *
         * @return the normalised value
         */
        int normalisedResult() {
            return this.normalisedResult;
        }

        /**
         * The success condition at line 134.
         *
         * @return {@code true} when the operation succeeded
         */
        boolean isAok() {
            return this == OK;
        }

        /**
         * The end-of-file condition at line 135, which is never the error condition.
         *
         * @return {@code true} when the sequential scan is exhausted
         */
        boolean isEof() {
            return this == END_OF_FILE;
        }
    }

    /**
     * What the rate lookup resolved, and whether the single fallback supplied it.
     *
     * @param disclosureGroup  the row that supplied the rate
     * @param defaultGroupUsed {@code true} when the first probe missed and the padded default group
     *                         literal supplied the rate
     */
    private record ResolvedRate(DisclosureGroup disclosureGroup, boolean defaultGroupUsed) {
    }

    /**
     * What the interest computation produced: the amount, and the transaction that carries it.
     *
     * @param monthlyInterest     the truncated interest for one category-balance row
     * @param interestTransaction the transaction written for it
     */
    private record ComputedInterest(BigDecimal monthlyInterest, Transaction interestTransaction) {
    }

    /**
     * The values a group reads once and then holds for every row of the group: the run date, the account
     * read at line 203 and the cross-reference read at line 205. Immutable, so no row of a group can
     * change what a later row sees.
     *
     * @param parameterDate  the ten-character run date
     * @param account        the account every row of the group posts to
     * @param crossReference the cross-reference row that supplies the card number
     */
    private record GroupContext(String parameterDate, Account account,
            CardCrossReference crossReference) {
    }

    /**
     * The run's working storage, lines 166 to 173, as <strong>per-invocation</strong> state.
     *
     * <p>Every field here would be a field on the service if this class were stateful, and none of them
     * is: a new instance is created inside each public entry point, so two concurrent runs cannot see
     * each other's record count, group key or identifier suffix. That is the whole reason the service
     * itself holds no mutable field.
     */
    private static final class RunState {

        /** {@code END-OF-FILE} at line 137, which the read paragraph raises at line 340. */
        private boolean endOfFile;

        /** {@code WS-FIRST-TIME} at line 170, whose initial value is the affirmative. */
        private boolean firstTime = true;

        /** {@code WS-LAST-ACCT-NUM} at line 167, whose initial value is spaces. */
        private String lastAccountNumber = "";

        /** {@code WS-RECORD-COUNT} at line 172, whose initial value is zero. */
        private int recordCount;

        /** {@code WS-TRANID-SUFFIX} at line 173, whose initial value is zero. Per run, not per group. */
        private long tranIdSuffix;

        /** Whether any row anywhere in the run had a zero rate. */
        private boolean rateGateSkipped;

        /** Whether any row anywhere in the run fell back to the padded default group literal. */
        private boolean defaultGroupUsed;

        private RunState() {
            // Every field carries the initial value its working-storage counterpart declares.
        }

        private boolean isEndOfFile() {
            return this.endOfFile;
        }

        private void markEndOfFile() {
            this.endOfFile = true;
        }

        private boolean isFirstTime() {
            return this.firstTime;
        }

        private void clearFirstTime() {
            this.firstTime = false;
        }

        private String lastAccountNumber() {
            return this.lastAccountNumber;
        }

        private void rememberAccountNumber(final String accountNumber) {
            this.lastAccountNumber = accountNumber;
        }

        private int recordCount() {
            return this.recordCount;
        }

        private void countRecord() {
            this.recordCount++;
        }

        private long tranIdSuffix() {
            return this.tranIdSuffix;
        }

        private void rememberTranIdSuffix(final long suffix) {
            this.tranIdSuffix = suffix;
        }

        private boolean isRateGateSkipped() {
            return this.rateGateSkipped;
        }

        private boolean isDefaultGroupUsed() {
            return this.defaultGroupUsed;
        }

        private void mergeGroupFlags(final boolean groupRateGateSkipped,
                final boolean groupDefaultGroupUsed) {
            this.rateGateSkipped = this.rateGateSkipped || groupRateGateSkipped;
            this.defaultGroupUsed = this.defaultGroupUsed || groupDefaultGroupUsed;
        }
    }

    /**
     * One group's working storage: the running interest total reset at line 200, the identifier suffix
     * the group continues and advances, and the two observations the result reports.
     *
     * <p>Per-invocation, for the same reason the run's state is. The running total in particular must
     * never be a field on the service: one run's total leaking into another would post interest to the
     * wrong account.
     */
    private static final class GroupState {

        /** {@code WS-TOTAL-INT} at line 169, reset to zero for every group at line 200. */
        private BigDecimal totalInterest = ZERO_MONETARY;

        /** {@code WS-TRANID-SUFFIX} at line 173, continued from where the previous group finished. */
        private long tranIdSuffix;

        /** Whether any row of this group had a zero rate. */
        private boolean rateGateSkipped;

        /** Whether any row of this group fell back to the padded default group literal. */
        private boolean defaultGroupUsed;

        private GroupState(final long initialTranIdSuffix) {
            this.tranIdSuffix = initialTranIdSuffix;
        }

        private BigDecimal totalInterest() {
            return this.totalInterest;
        }

        /**
         * Line 467: adds one row's interest to the running total. The receiving field carries two
         * decimals, so the sum is taken to the monetary scale through the codec.
         *
         * @param monthlyInterest the row's interest
         */
        private void addToTotalInterest(final BigDecimal monthlyInterest) {
            this.totalInterest =
                    ZonedDecimalCodec.toMonetaryScale(this.totalInterest.add(monthlyInterest));
        }

        /**
         * Line 474: advances the six-digit suffix and returns the advanced value.
         *
         * @return the suffix to render into the identifier being minted
         */
        private long nextTranIdSuffix() {
            this.tranIdSuffix++;
            return this.tranIdSuffix;
        }

        private long tranIdSuffix() {
            return this.tranIdSuffix;
        }

        private boolean isRateGateSkipped() {
            return this.rateGateSkipped;
        }

        private void markRateGateSkipped() {
            this.rateGateSkipped = true;
        }

        private boolean isDefaultGroupUsed() {
            return this.defaultGroupUsed;
        }

        private void markDefaultGroupUsed() {
            this.defaultGroupUsed = true;
        }
    }

    /**
     * Selects the row a keyed read of the non-unique cross-reference path would have returned: the one
     * with the lowest card number.
     *
     * <p>A keyed {@code READ} of a duplicate-bearing VSAM alternate index returns the first record in
     * ascending <em>base</em>-key order, and the base key of the cross-reference cluster is the card
     * number. That is a property of the read being reproduced rather than of the index, so
     * {@code CardCrossReferenceRepository} returns every matching row and the selection is made here,
     * at the site whose behaviour depends on it. An empty result is the legacy not-found condition.
     *
     * <p>The comparison is on the raw sixteen-character value, neither trimmed nor numeric: every
     * stored card number is exactly sixteen zero-padded digits, so lexicographic and numeric order
     * coincide.
     *
     * @param candidates every row the account path resolved, possibly empty
     * @return the row with the lowest card number, or an empty result when the account has none
     */
    private static Optional<CardCrossReference> firstXrefByBaseKey(
            final List<CardCrossReference> candidates) {
        return candidates.stream().min(Comparator.comparing(CardCrossReference::getXrefCardNum));
    }
}
