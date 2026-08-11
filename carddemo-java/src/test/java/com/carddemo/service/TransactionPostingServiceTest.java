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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Limit;

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
import com.carddemo.service.TransactionPostingService.PostingResult;
import com.carddemo.service.TransactionPostingService.PostingRunSummary;
import com.carddemo.support.SensitiveValues;
import com.carddemo.support.TestDataFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TransactionPostingService}, the daily-transaction posting program.
 *
 * <p>The class under test translates {@code app/cbl/CBTRN02C.cbl} - 731 lines and 27 procedure units as
 * measured. It validates each landing record through an order-dependent reject cascade, posts the survivors
 * onto the category balance, the account and the transaction file, and hands every rejected record on for a
 * 430-byte reject record to be written. No legacy source text is transcribed here; widths, offsets,
 * paragraph names, reason codes and contract literals are metadata.
 *
 * <h2>The eight properties these tests exist to defend</h2>
 * <ol>
 *   <li><strong>103 overwrites 102.</strong> The two limit tests are consecutive <em>unguarded</em>
 *       blocks at source lines 407-413 and 414-420, so a record that is both over limit and past
 *       expiry ends as 103. An implementation that guarded the second block would pass every
 *       single-failure test and fail only the doubly-invalid one, which is why that case is asserted
 *       first and alongside both controls.</li>
 *   <li><strong>100 short-circuits the account lookup.</strong> The account stage at line 373 is
 *       guarded by the reason code still being zero, so an unresolved card number means the account
 *       is never read. The interaction check, not the reason code, is what proves the guard.</li>
 *   <li><strong>101 and 109 are distinct reasons carrying identical text.</strong> 101 arises on the
 *       account-read invalid-key arm at line 397 and 109 on the account-rewrite invalid-key arm at
 *       line 556. Collapsing them would erase a diagnostic distinction; the shared text is asserted
 *       so the sharing is visibly deliberate.</li>
 *   <li><strong>109 is inert.</strong> It is set after the mainline has already committed to posting,
 *       so it produces no reject record and the transaction-file write still happens. On unmodified
 *       fixtures it is not emitted at all; every test that reaches it below says so and uses a
 *       constructed path.</li>
 *   <li><strong>The overlimit basis is evaluated strictly left to right and truncates.</strong>
 *       Cycle credit less cycle debit plus the amount, stored into a two-decimal field. The estate
 *       carries no rounding phrase anywhere, so the store truncates toward zero; a rounding-to-
 *       nearest translation differs by one cent on the boundary and one cent decides the rejection.</li>
 *   <li><strong>The expiry test reads the ORIGINATION timestamp.</strong> Not the processing
 *       timestamp, which is twenty-six blanks on every delivered record. Two mirrored fixtures below
 *       are built so that reading the wrong field yields the wrong verdict.</li>
 *   <li><strong>A negative amount joins the debit accumulator unchanged.</strong> Neither negated nor
 *       made absolute, so the accumulator holds a negative total and is meant to.</li>
 *   <li><strong>The three persistence stages have one order.</strong> Category balance, then account,
 *       then transaction file.</li>
 * </ol>
 *
 * <h2>Reject reachability, and why every reject fixture here is constructed</h2>
 * On the unmodified delivered fixtures only reason code 0102 is producible: 0100, 0101 and 0103 are
 * data-unreachable, and 0109 is control-flow unreachable. Every reject case below therefore uses a
 * <strong>constructed</strong> record or a constructed store state, and each says so in its display
 * name or its comment so that a reader never mistakes a constructed case for a seeded one.
 *
 * <h2>Independent oracle</h2>
 * No expected value in this file is produced by the code it judges. The five reject descriptions are
 * declared here as literals with their blank padding written out, the four-character reason images
 * are declared as literals, the expected 430-byte reject image is assembled here from its three
 * segments, expected amounts are {@link BigDecimal} literals with their scale visible, and the
 * expected processing timestamp is written out character by character rather than formatted through
 * the production helper. Neither the service, nor the reason enumeration, nor the decimal codec, nor
 * any record mapper supplies an expectation.
 *
 * <h2>Harness</h2>
 * A surefire unit test: no container, no Spring context, no Spring Batch wiring, no database and no
 * network. Every repository, the create-only write boundary and the abend collaborator are Mockito
 * doubles; the per-record transaction boundary is the genuine object because its entire contribution
 * is to run the callback that every assertion here depends on having run. Time is a fixed clock, so
 * the regenerated processing timestamp is an exact expected string rather than a pattern.
 *
 * <p>Stub arrangement is deliberately granular - one helper per collaborator answer, composed per
 * test - because strict stubbing makes an unused arrangement a failure, and because a test that
 * arranges only what it consumes documents which collaborators each path actually touches.
 *
 * <h2>The two-level status model, and why the coarse level is exercised indirectly</h2>
 * The raw two-byte vocabulary lives in {@link FileStatus}; the coarse tri-state that the batch
 * members branch on is a nested, non-public type inside its owner. It is therefore exercised here
 * through the owner's observable behaviour rather than referenced: success posts the record,
 * record-not-found normalises to success and creates the missing row, end of file ends the run with
 * the success return code, and any other status abends. The legacy also arms a pre-operation
 * sentinel before each open, read and close so that a path setting no result is never mistaken for
 * success; its observable consequence - a failed operation always abends rather than silently
 * succeeding - is asserted on every failure arm below. The only status literals compared anywhere in
 * the estate are {@code 00}, {@code 10} and {@code 23}, and no test in this file depends on
 * {@code 22} or {@code 35}.
 *
 * <h2>Procedure-unit coverage: 27 units, each traceable to a test</h2>
 *
 * <p>Each of the 27 procedure units is named in the test group that exercises it. The unit-to-test
 * inventory is held once in {@code docs/traceability-matrix.md} and is not restated here.
 */
@DisplayName("Transaction posting service: the daily-transaction posting program, 27 units")
@ExtendWith(MockitoExtension.class)
class TransactionPostingServiceTest {

    /**
     * The bound the two alternate-key finders now require, generous enough that these specifications
     * measure the finder's shape rather than its bound.
     *
     * <p>The bound itself is measured against a real server in the repository specifications, where a
     * fixture can hold two rows under one account identifier; a mock cannot establish it.
     */
    private static final Limit ALTERNATE_KEY_ROWS = Limit.of(100);

    // =================================================================================================
    // KEYS AND RECORD VALUES
    // =================================================================================================

    /** A card number the cross-reference resolves. */
    private static final String CARD = "4111111111111111";

    /** A card number no cross-reference row carries, which is the constructed route to reason 100. */
    private static final String UNKNOWN_CARD = "9999999999999999";

    /** The eleven-digit account identifier the resolved cross-reference names. */
    private static final String ACCOUNT_ID = "00000000011";

    /** An eleven-digit account identifier no account row carries. */
    private static final String UNKNOWN_ACCOUNT_ID = "99999999999";

    /** The nine-digit customer identifier the cross-reference carries. */
    private static final String CUSTOMER_ID = "000000001";

    /** The two-character transaction type code. */
    private static final String TYPE_CODE = "01";

    /** The four-digit transaction category code. */
    private static final String CATEGORY_CODE = "0005";

    /** A twenty-six character origination timestamp whose first ten characters are 2022-07-19. */
    private static final String ORIGINATION_TIMESTAMP = "2022-07-19-20.00.00.000000";

    /** A twenty-six character origination timestamp whose first ten characters are 2099-12-31. */
    private static final String LATE_ORIGINATION_TIMESTAMP = "2099-12-31-23.59.59.000000";

    /** A twenty-six character origination timestamp whose first ten characters are 2020-01-01. */
    private static final String EARLY_ORIGINATION_TIMESTAMP = "2020-01-01-00.00.00.000000";

    /**
     * The processing timestamp every delivered landing record carries: twenty-six blanks. Written as
     * a repetition so the width is visible rather than counted by eye.
     */
    private static final String BLANK_TIMESTAMP = " ".repeat(26);

    /** An account expiration date later than every origination timestamp used below. */
    private static final String LATE_EXPIRY = "2099-01-01";

    /** An account expiration date earlier than {@link #ORIGINATION_TIMESTAMP}. */
    private static final String EARLY_EXPIRY = "2021-01-01";

    /** A credit limit no amount used below reaches. */
    private static final String AMPLE_LIMIT = "99999.00";

    // =================================================================================================
    // TIME
    // =================================================================================================

    /** The instant the injected clock is pinned to; every expected timestamp below derives from it. */
    private static final String FIXED_INSTANT = "2022-07-19T23:12:32.457Z";

    /**
     * The processing timestamp the pinned instant must produce, written out character by character:
     * four year digits, hyphen, two month, hyphen, two day, <strong>hyphen</strong> where a
     * conventional timestamp would carry a space, two hour, dot, two minute, dot, two second, dot,
     * the two hundredths digits of 457 milliseconds, and the literal four-character tail. Twenty-six
     * characters, and deliberately not produced by asking the production helper.
     */
    private static final String EXPECTED_PROCESSING_TIMESTAMP = "2022-07-19-23.12.32.450000";

    /** The twenty-six character width both timestamp fields carry. */
    private static final int TIMESTAMP_WIDTH = 26;

    // =================================================================================================
    // THE FIVE REJECT REASONS, AS THE 430-BYTE TRAILER CARRIES THEM
    //
    // Each description is declared twice: once as the text the service reports, and once blank-padded
    // to the seventy-six character field the trailer declares. The padding is written as a repetition
    // so its width is stated rather than inferred, and no padded form is obtained from production code.
    // =================================================================================================

    private static final String DESCRIPTION_100 = "INVALID CARD NUMBER FOUND";

    private static final String DESCRIPTION_101 = "ACCOUNT RECORD NOT FOUND";

    private static final String DESCRIPTION_102 = "OVERLIMIT TRANSACTION";

    private static final String DESCRIPTION_103 = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";

    /** Reason 109 carries reason 101's text verbatim; the two remain distinct reasons regardless. */
    private static final String DESCRIPTION_109 = "ACCOUNT RECORD NOT FOUND";

    private static final String PADDED_DESCRIPTION_100 = DESCRIPTION_100 + " ".repeat(51);

    private static final String PADDED_DESCRIPTION_101 = DESCRIPTION_101 + " ".repeat(52);

    private static final String PADDED_DESCRIPTION_102 = DESCRIPTION_102 + " ".repeat(55);

    private static final String PADDED_DESCRIPTION_103 = DESCRIPTION_103 + " ".repeat(34);

    private static final String PADDED_DESCRIPTION_109 = DESCRIPTION_109 + " ".repeat(52);

    /** The four-character zero-filled numeric images of the five reason codes. */
    private static final String REASON_IMAGE_100 = "0100";

    private static final String REASON_IMAGE_101 = "0101";

    private static final String REASON_IMAGE_102 = "0102";

    private static final String REASON_IMAGE_103 = "0103";

    private static final String REASON_IMAGE_109 = "0109";

    /** Widths the reject record is composed of: 350 + 4 + 76 = 430. */
    private static final int SOURCE_IMAGE_WIDTH = 350;

    private static final int REASON_IMAGE_WIDTH = 4;

    private static final int DESCRIPTION_WIDTH = 76;

    private static final int REJECT_RECORD_WIDTH = 430;

    // =================================================================================================
    // FILE STATUS
    // =================================================================================================

    /** The raw status the batch tier normalises to success. */
    private static final String RAW_STATUS_SUCCESS = "00";

    /** The raw status the batch tier normalises to end of file, which is never an error. */
    private static final String RAW_STATUS_END_OF_FILE = "10";

    /** The raw status a missing keyed record reports, which the category-balance read treats as success. */
    private static final String RAW_STATUS_RECORD_NOT_FOUND = "23";

    /** The raw status a failed operation reports here, and the one every abend below carries. */
    private static final String RAW_STATUS_PERMANENT_ERROR = "31";

    /** The rendered status field a permanent error produces, appended to the display literal. */
    private static final String PERMANENT_ERROR_DISPLAY_LINE = "FILE STATUS IS: NNNN0031";

    /** The banner the abend paragraph emits immediately before it aborts. */
    private static final String ABENDING_BANNER = "ABENDING PROGRAM";

    // =================================================================================================
    // THE SEVEN LEGACY FAILURE LITERALS AND THE PROGRAM NAME
    // =================================================================================================

    private static final String PROGRAM = "CBTRN02C";

    private static final String OPEN_DALYTRAN_FAILURE = "ERROR OPENING DALYTRAN";

    private static final String OPEN_DALYREJS_FAILURE = "ERROR OPENING DALY REJECTS FILE";

    private static final String READ_DALYTRAN_FAILURE = "ERROR READING DALYTRAN FILE";

    private static final String READ_TCATBALF_FAILURE = "ERROR READING TRANSACTION BALANCE FILE";

    private static final String WRITE_TCATBALF_FAILURE = "ERROR WRITING TRANSACTION BALANCE FILE";

    private static final String REWRITE_TCATBALF_FAILURE = "ERROR REWRITING TRANSACTION BALANCE FILE";

    private static final String WRITE_TRANFILE_FAILURE = "ERROR WRITING TO TRANSACTION FILE";

    /** The six legacy DD names the failure arms name. */
    private static final String DALYTRAN_DD = "DALYTRAN";

    private static final String TRANFILE_DD = "TRANFILE";

    private static final String XREFFILE_DD = "XREFFILE";

    private static final String DALYREJS_DD = "DALYREJS";

    private static final String ACCTFILE_DD = "ACCTFILE";

    private static final String TCATBALF_DD = "TCATBALF";

    // =================================================================================================
    // COLLABORATORS
    // =================================================================================================

    private DailyTransactionRepository dailyTransactionRepository;

    private TransactionRepository transactionRepository;

    private AccountRepository accountRepository;

    private CardCrossReferenceRepository cardCrossReferenceRepository;

    private TransactionCategoryBalanceRepository categoryBalanceRepository;

    private RecordWriter recordWriter;

    private AbendService abendService;

    /**
     * The genuine per-act transaction boundary. A mock would be the wrong choice: the boundary's whole
     * contribution is that it runs the callback, every assertion here depends on the callback having run,
     * and the object carries no collaborator of its own to isolate. Where the number of units opened is
     * itself the property under test, the tests below wrap this same genuine object in a spy rather than
     * replacing it.
     */
    private PostingStageTransactionBoundary transactionBoundary;

    private TransactionPostingService service;

    private Logger serviceLogger;

    private Level previousLogLevel;

    private ListAppender<ILoggingEvent> logCapture;

    /**
     * Diagnostics the log already held at the instant the abend collaborator was reached. Populated by
     * the answer the abend tests install, which is the only way to observe that the emission preceded
     * the raise rather than merely accompanied it.
     */
    private final List<String> diagnosticsAtRaise = new ArrayList<>();

    @BeforeEach
    void createServiceOverDoubles() {
        this.dailyTransactionRepository = mock(DailyTransactionRepository.class);
        this.transactionRepository = mock(TransactionRepository.class);
        this.accountRepository = mock(AccountRepository.class);
        this.cardCrossReferenceRepository = mock(CardCrossReferenceRepository.class);
        this.categoryBalanceRepository = mock(TransactionCategoryBalanceRepository.class);
        this.recordWriter = mock(RecordWriter.class);
        this.abendService = mock(AbendService.class);
        this.transactionBoundary = new PostingStageTransactionBoundary();
        this.service = serviceWith(this.transactionBoundary);

        this.serviceLogger = (Logger) LoggerFactory.getLogger(TransactionPostingService.class);
        this.previousLogLevel = this.serviceLogger.getLevel();
        this.serviceLogger.setLevel(Level.TRACE);
        this.logCapture = new ListAppender<>();
        this.logCapture.start();
        this.serviceLogger.addAppender(this.logCapture);
    }

    @AfterEach
    void detachLogCapture() {
        this.serviceLogger.detachAppender(this.logCapture);
        this.serviceLogger.setLevel(this.previousLogLevel);
        this.logCapture.stop();
    }

    /** Builds the service over the shared doubles, the pinned clock and the supplied boundary. */
    private TransactionPostingService serviceWith(final PostingStageTransactionBoundary boundary) {
        return new TransactionPostingService(this.dailyTransactionRepository,
                this.transactionRepository, this.accountRepository, this.cardCrossReferenceRepository,
                this.categoryBalanceRepository, this.recordWriter, this.abendService, boundary,
                pinnedClock());
    }

    /** The pinned clock. Never a system clock, so no assertion here depends on when it runs. */
    private static Clock pinnedClock() {
        return Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);
    }

    /** The formatted messages the service emitted, in emission order. */
    private List<String> loggedMessages() {
        return this.logCapture.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    // =================================================================================================
    // FIXTURES - every one constructed, none seeded
    // =================================================================================================

    /** A landing record on the resolvable card, with the blank processing timestamp of the fixture. */
    private static DailyTransaction record(final String id, final String amount) {
        return recordOn(id, amount, CARD, ORIGINATION_TIMESTAMP, BLANK_TIMESTAMP);
    }

    /** A landing record with every field the tests vary stated explicitly. */
    private static DailyTransaction recordOn(final String id, final String amount,
            final String cardNumber, final String originationTimestamp,
            final String processingTimestamp) {
        return new DailyTransaction(id, TYPE_CODE, CATEGORY_CODE, "POS TERM  ", "purchase",
                amount == null ? null : new BigDecimal(amount), "000000123", "MERCHANT NAME",
                "MERCHANT CITY", "12345", cardNumber, originationTimestamp, processingTimestamp);
    }

    /** An account whose five money fields and expiration date the caller states. */
    private static Account accountWith(final String currentBalance, final String creditLimit,
            final String cycleCredit, final String cycleDebit, final String expirationDate) {
        return new Account(ACCOUNT_ID, "Y", new BigDecimal(currentBalance),
                new BigDecimal(creditLimit), new BigDecimal("500.00"), "2020-01-01", expirationDate,
                "2020-01-01", new BigDecimal(cycleCredit), new BigDecimal(cycleDebit), "12345",
                "GROUP01   ");
    }

    /** An account that rejects nothing: an ample limit, zero cycle totals and a late expiry. */
    private static Account postableAccount() {
        return accountWith("0.00", AMPLE_LIMIT, "0.00", "0.00", LATE_EXPIRY);
    }

    /** An account carrying no expiration date at all, which the ten-character view blanks. */
    private static Account accountWithoutExpiry() {
        return new Account(ACCOUNT_ID, "Y", new BigDecimal("0.00"), new BigDecimal(AMPLE_LIMIT),
                new BigDecimal("500.00"), "2020-01-01", null, "2020-01-01", new BigDecimal("0.00"),
                new BigDecimal("0.00"), "12345", "GROUP01   ");
    }

    // =================================================================================================
    // GRANULAR STUB ARRANGEMENT
    //
    // One helper per collaborator answer, composed per test. Strict stubbing makes an arrangement that
    // a test does not consume a failure, which is the property that keeps each test honest about which
    // collaborators its path actually reaches.
    // =================================================================================================

    /** The cross-reference resolves the card to {@link #ACCOUNT_ID}. */
    private void cardResolves() {
        cardResolves(CARD, ACCOUNT_ID);
    }

    /** The cross-reference resolves the given card to the given account. */
    private void cardResolves(final String cardNumber, final String accountId) {
        when(this.cardCrossReferenceRepository.findById(cardNumber))
                .thenReturn(Optional.of(new CardCrossReference(cardNumber, CUSTOMER_ID, accountId)));
    }

    /** The cross-reference read takes its invalid-key arm, which is the constructed route to 100. */
    private void cardDoesNotResolve(final String cardNumber) {
        when(this.cardCrossReferenceRepository.findById(cardNumber)).thenReturn(Optional.empty());
    }

    /** The account read finds the row. */
    private void accountResolves(final Account account) {
        when(this.accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
    }

    /** The account read takes its invalid-key arm, which is the constructed route to 101. */
    private void accountDoesNotResolve() {
        when(this.accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());
    }

    /** The pre-rewrite held read finds the row, so a zero rewrite count is a version race. */
    private void heldReadFinds(final Account account) {
        when(this.accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
    }

    /** The pre-rewrite held read finds nothing, which is the constructed route to inert 109. */
    private void heldReadFindsNothing() {
        when(this.accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.empty());
    }

    /** The account rewrite reports the given affected-row count, which is its file status. */
    private void rewriteAffects(final int rows) {
        when(this.accountRepository.rewritePostingBalances(eq(ACCOUNT_ID),
                ArgumentMatchers.anyLong(), any(BigDecimal.class), any(BigDecimal.class),
                any(BigDecimal.class))).thenReturn(Integer.valueOf(rows));
    }

    /** The category-balance read finds a row carrying the given balance. */
    private void categoryBalanceFound(final String balance) {
        when(this.categoryBalanceRepository.findById(any(TransactionCategoryBalanceId.class)))
                .thenReturn(Optional.of(new TransactionCategoryBalance(ACCOUNT_ID, TYPE_CODE,
                        CATEGORY_CODE, new BigDecimal(balance))));
    }

    /** The category-balance read finds nothing, which the legacy treats as success and creates. */
    private void categoryBalanceMissing() {
        when(this.categoryBalanceRepository.findById(any(TransactionCategoryBalanceId.class)))
                .thenReturn(Optional.empty());
    }

    /** The category-balance rewrite echoes what it was given. */
    private void categoryBalanceRewriteEchoes() {
        when(this.categoryBalanceRepository.saveAndFlush(any(TransactionCategoryBalance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** The create-only write boundary echoes what it was given. */
    private void categoryBalanceInsertEchoes() {
        when(this.recordWriter.insert(any(TransactionCategoryBalance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** The transaction-file write echoes what it was given. */
    private void transactionWriteEchoes() {
        when(this.transactionRepository.insertAndFlush(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** Enough for the validation cascade to reach a verdict over the supplied account. */
    private void arrangeValidation(final Account account) {
        cardResolves();
        accountResolves(account);
    }

    /** Validation plus the first posting stage, over an existing zero-balance category row. */
    private void arrangeThroughCategoryBalance(final Account account) {
        arrangeValidation(account);
        categoryBalanceFound("0.00");
        categoryBalanceRewriteEchoes();
    }

    /** Validation plus the first two posting stages, with the rewrite reporting one row. */
    private void arrangeThroughAccount(final Account account) {
        arrangeThroughCategoryBalance(account);
        heldReadFinds(account);
        rewriteAffects(1);
    }

    /** The complete posting path: all three stages arranged to succeed. */
    private void arrangePosting(final Account account) {
        arrangeThroughAccount(account);
        transactionWriteEchoes();
    }

    /**
     * Installs the answer that snapshots the log at the instant the abend collaborator is reached.
     * Everything the service emitted before the raise is in the snapshot; anything it emits afterwards
     * is not, which is exactly the distinction the ordering assertion needs.
     */
    private void snapshotDiagnosticsWhenAbendIsRaised() {
        Mockito.doAnswer(invocation -> {
            this.diagnosticsAtRaise.addAll(loggedMessages());
            return null;
        }).when(this.abendService).abendOnFileStatus(ArgumentMatchers.anyString(),
                any(FileStatusException.class));
    }

    // =================================================================================================
    // CAPTURE HELPERS
    // =================================================================================================

    /** The transaction the third posting stage was handed. */
    private Transaction capturePostedTransaction() {
        final ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(this.transactionRepository).insertAndFlush(captor.capture());
        return captor.getValue();
    }

    /** The three balances the account rewrite was asked to store, in the order it declares them. */
    private List<BigDecimal> captureRewrittenBalances() {
        final ArgumentCaptor<BigDecimal> currentBalance = ArgumentCaptor.forClass(BigDecimal.class);
        final ArgumentCaptor<BigDecimal> cycleCredit = ArgumentCaptor.forClass(BigDecimal.class);
        final ArgumentCaptor<BigDecimal> cycleDebit = ArgumentCaptor.forClass(BigDecimal.class);
        verify(this.accountRepository).rewritePostingBalances(eq(ACCOUNT_ID),
                ArgumentMatchers.anyLong(), currentBalance.capture(), cycleCredit.capture(),
                cycleDebit.capture());
        return List.of(currentBalance.getValue(), cycleCredit.getValue(), cycleDebit.getValue());
    }

    /** The category-balance row the rewrite path was handed. */
    private TransactionCategoryBalance captureRewrittenCategoryBalance() {
        final ArgumentCaptor<TransactionCategoryBalance> captor =
                ArgumentCaptor.forClass(TransactionCategoryBalance.class);
        verify(this.categoryBalanceRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    /** The category-balance row the create path was handed. */
    private TransactionCategoryBalance captureInsertedCategoryBalance() {
        final ArgumentCaptor<TransactionCategoryBalance> captor =
                ArgumentCaptor.forClass(TransactionCategoryBalance.class);
        verify(this.recordWriter).insert(captor.capture());
        return captor.getValue();
    }

    /** The file status the abend collaborator was handed. */
    private FileStatusException captureCarriedFileStatus() {
        final ArgumentCaptor<FileStatusException> captor =
                ArgumentCaptor.forClass(FileStatusException.class);
        verify(this.abendService).abendOnFileStatus(eq(PROGRAM), captor.capture());
        return captor.getValue();
    }

    /** The number of encoded bytes a fixed-width value occupies, which is never its character count. */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    // =================================================================================================
    // 1500-B-LOOKUP-ACCT, lines 393 to 422 - two consecutive UNGUARDED blocks
    // =================================================================================================

    @Nested
    @DisplayName("The reject cascade guards: 103 overwrites 102, and 100 stops the cascade")
    class TheRejectCascadeGuards {

        @Test
        @DisplayName("THE DECISIVE CASE: a constructed record that is both over limit and past expiry "
                + "is rejected with 103, never 102, because the second block is not an else of the first")
        void bothConditionsResolveTo103() {
            arrangeValidation(accountWith("0.00", "10.00", "0.00", "0.00", EARLY_EXPIRY));

            final PostingResult result = service.post(record("T1", "5000.00"));

            assertAll(
                    () -> assertThat(result.reasonCode())
                            .as("the second unguarded block overwrites the first")
                            .isEqualTo(103),
                    () -> assertThat(result.reasonDescription()).isEqualTo(DESCRIPTION_103),
                    () -> assertThat(result.rejectReason())
                            .isEqualTo(RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION),
                    () -> assertThat(result.posted()).isFalse(),
                    () -> assertThat(result.rejected()).isTrue(),
                    () -> assertThat(result.postedTransaction()).isNull(),
                    () -> assertThat(result.updatedAccount()).isNull(),
                    () -> assertThat(result.updatedCategoryBalance()).isNull());
        }

        @Test
        @DisplayName("CONTROL ONE: over limit alone on a constructed record is 102, so the first block "
                + "genuinely fires on its own")
        void overLimitAloneResolvesTo102() {
            arrangeValidation(accountWith("0.00", "10.00", "0.00", "0.00", LATE_EXPIRY));

            final PostingResult result = service.post(record("T1", "5000.00"));

            assertAll(
                    () -> assertThat(result.reasonCode()).isEqualTo(102),
                    () -> assertThat(result.reasonDescription()).isEqualTo(DESCRIPTION_102),
                    () -> assertThat(result.rejectReason())
                            .isEqualTo(RejectReason.OVERLIMIT_TRANSACTION),
                    () -> assertThat(result.posted()).isFalse());
        }

        @Test
        @DisplayName("CONTROL TWO: past expiry alone on a constructed record is 103, so the second "
                + "block does not depend on the first having fired")
        void pastExpiryAloneResolvesTo103() {
            arrangeValidation(accountWith("0.00", AMPLE_LIMIT, "0.00", "0.00", EARLY_EXPIRY));

            final PostingResult result = service.post(record("T1", "10.00"));

            assertAll(
                    () -> assertThat(result.reasonCode()).isEqualTo(103),
                    () -> assertThat(result.reasonDescription()).isEqualTo(DESCRIPTION_103),
                    () -> assertThat(result.posted()).isFalse());
        }

        @Test
        @DisplayName("neither condition leaves the record acceptable, with a zero reason and a blank "
                + "description")
        void neitherConditionLeavesTheRecordAcceptable() {
            arrangePosting(postableAccount());

            final PostingResult result = service.post(record("T1", "10.00"));

            assertAll(
                    () -> assertThat(result.reasonCode())
                            .isEqualTo(TransactionPostingService.NO_REJECT_REASON_CODE),
                    () -> assertThat(result.reasonDescription())
                            .isEqualTo(TransactionPostingService.BLANK_FAIL_REASON_DESCRIPTION),
                    () -> assertThat(result.rejectReason()).isNull(),
                    () -> assertThat(result.posted()).isTrue(),
                    () -> assertThat(result.rejected()).isFalse());
        }

        @Test
        @DisplayName("a constructed unresolvable card number is 100 and the account lookup is NEVER "
                + "invoked, which is what proves the account stage is guarded")
        void anUnresolvableCardNumberNeverReachesTheAccountLookup() {
            cardDoesNotResolve(CARD);

            final PostingResult result = service.post(record("T1", "10.00"));

            assertAll(
                    () -> assertThat(result.reasonCode()).isEqualTo(100),
                    () -> assertThat(result.reasonDescription()).isEqualTo(DESCRIPTION_100),
                    () -> assertThat(encodedWidth(PADDED_DESCRIPTION_100))
                            .as("the trailer field the description is written into")
                            .isEqualTo(DESCRIPTION_WIDTH),
                    () -> assertThat(PADDED_DESCRIPTION_100).startsWith(DESCRIPTION_100),
                    () -> assertThat(result.rejectReason())
                            .isEqualTo(RejectReason.INVALID_CARD_NUMBER),
                    () -> assertThat(result.posted()).isFalse());
            verify(accountRepository, never()).findById(any());
            verifyNoInteractions(accountRepository, categoryBalanceRepository, transactionRepository,
                    recordWriter);
        }

        @Test
        @DisplayName("a resolvable card whose constructed account row is absent is 101, which 100 can "
                + "therefore never also be")
        void aResolvableCardWithNoAccountRowIs101() {
            cardResolves();
            accountDoesNotResolve();

            final PostingResult result = service.post(record("T1", "10.00"));

            assertAll(
                    () -> assertThat(result.reasonCode()).isEqualTo(101),
                    () -> assertThat(result.reasonDescription()).isEqualTo(DESCRIPTION_101),
                    () -> assertThat(result.rejectReason())
                            .isEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_READ),
                    () -> assertThat(result.posted()).isFalse());
            verifyNoInteractions(categoryBalanceRepository, transactionRepository, recordWriter);
        }

        @Test
        @DisplayName("a rejected record never reaches any of the three posting stages")
        void aRejectedRecordReachesNoPostingStage() {
            arrangeValidation(accountWith("0.00", "1.00", "0.00", "0.00", LATE_EXPIRY));

            assertThat(service.post(record("T1", "9999.00")).reasonCode()).isEqualTo(102);

            verify(cardCrossReferenceRepository).findById(CARD);
            verify(accountRepository).findById(ACCOUNT_ID);
            verifyNoMoreInteractions(cardCrossReferenceRepository, accountRepository);
            verifyNoInteractions(categoryBalanceRepository, transactionRepository, recordWriter,
                    abendService);
        }
    }

    // =================================================================================================
    // 1500-VALIDATE-TRAN, lines 370 to 378 - and the invitation at line 377 that stays unaccepted
    // =================================================================================================

    @Nested
    @DisplayName("The validation cascade performs exactly the legacy stages and no more")
    class TheValidationCascade {

        @Test
        @DisplayName("a clean record touches seven collaborator methods and nothing else, so a "
                + "validation added at the invitation comment would fail this test")
        void aCleanRecordTouchesExactlyTheLegacyCollaborators() {
            arrangePosting(postableAccount());

            assertThat(service.post(record("T1", "10.00")).posted()).isTrue();

            verify(cardCrossReferenceRepository).findById(CARD);
            verify(accountRepository).findById(ACCOUNT_ID);
            verify(accountRepository).findByIdForUpdate(ACCOUNT_ID);
            verify(accountRepository).rewritePostingBalances(eq(ACCOUNT_ID),
                    ArgumentMatchers.anyLong(), any(BigDecimal.class), any(BigDecimal.class),
                    any(BigDecimal.class));
            verify(categoryBalanceRepository).findById(any(TransactionCategoryBalanceId.class));
            verify(categoryBalanceRepository).saveAndFlush(any(TransactionCategoryBalance.class));
            verify(transactionRepository).insertAndFlush(any(Transaction.class));
            verifyNoMoreInteractions(cardCrossReferenceRepository, accountRepository,
                    categoryBalanceRepository, transactionRepository);
            verifyNoInteractions(recordWriter, dailyTransactionRepository, abendService);
        }

        @Test
        @DisplayName("the cascade reads the cross-reference before the account, never the other way "
                + "round, because the account key comes out of the cross-reference")
        void theCrossReferenceIsReadBeforeTheAccount() {
            arrangePosting(postableAccount());

            service.post(record("T1", "10.00"));

            final InOrder ordered = inOrder(cardCrossReferenceRepository, accountRepository);
            ordered.verify(cardCrossReferenceRepository).findById(CARD);
            ordered.verify(accountRepository).findById(ACCOUNT_ID);
        }

        @Test
        @DisplayName("the account key is the identifier the cross-reference row carries, not the card "
                + "number and not a constant")
        void theAccountKeyComesFromTheCrossReferenceRow() {
            cardResolves(CARD, UNKNOWN_ACCOUNT_ID);
            when(accountRepository.findById(UNKNOWN_ACCOUNT_ID)).thenReturn(Optional.empty());

            assertThat(service.post(record("T1", "10.00")).reasonCode()).isEqualTo(101);

            verify(accountRepository).findById(UNKNOWN_ACCOUNT_ID);
            verify(accountRepository, never()).findById(CARD);
        }
    }

    // =================================================================================================
    // 1500-A-LOOKUP-XREF, lines 380 to 392 - a keyed read on the card number
    // =================================================================================================

    @Nested
    @DisplayName("The cross-reference lookup: keyed on the card number, empty is the not-found arm")
    class TheCrossReferenceLookup {

        @Test
        @DisplayName("an empty keyed read is the not-found arm and reaches a verdict, so no absent-value "
                + "or index failure escapes")
        void anEmptyKeyedReadReachesAVerdict() {
            cardDoesNotResolve(CARD);

            // Reaching the assertion at all is the proof that nothing escaped: an unhandled absent
            // value or an index overrun would fail this test as an error rather than a failure.
            final PostingResult result = service.post(record("T1", "10.00"));

            assertAll(
                    () -> assertThat(result.rejectReason())
                            .isEqualTo(RejectReason.INVALID_CARD_NUMBER),
                    () -> assertThat(result.sourceRecord().getDalytranCardNum()).isEqualTo(CARD));
        }

        @Test
        @DisplayName("the read is keyed on the record's own card number, so a different card number "
                + "resolves through a different key")
        void theReadIsKeyedOnTheRecordsCardNumber() {
            cardDoesNotResolve(UNKNOWN_CARD);

            assertThat(service.post(
                    recordOn("T1", "10.00", UNKNOWN_CARD, ORIGINATION_TIMESTAMP, BLANK_TIMESTAMP))
                    .reasonCode()).isEqualTo(100);

            verify(cardCrossReferenceRepository).findById(UNKNOWN_CARD);
            verify(cardCrossReferenceRepository, never()).findById(CARD);
        }

        @Test
        @DisplayName("the account-scoped finder returns a LIST, not an optional: an empty list is its "
                + "not-found value and the first element as supplied is the one that wins, unsorted")
        void theAccountScopedFinderReturnsAList() {
            final CardCrossReference firstAsSupplied =
                    new CardCrossReference("4111111111111112", CUSTOMER_ID, ACCOUNT_ID);
            final CardCrossReference secondAsSupplied =
                    new CardCrossReference("4111111111111110", CUSTOMER_ID, ACCOUNT_ID);
            when(cardCrossReferenceRepository.findByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID, ALTERNATE_KEY_ROWS))
                    .thenReturn(List.of(firstAsSupplied, secondAsSupplied));
            when(cardCrossReferenceRepository.findByXrefAcctIdOrderByXrefCardNumAsc(UNKNOWN_ACCOUNT_ID, ALTERNATE_KEY_ROWS))
                    .thenReturn(List.of());

            // The declared types below are the assertion: a List, never an Optional. A signature
            // returning an optional would not compile against these two declarations.
            final List<CardCrossReference> resolved =
                    cardCrossReferenceRepository.findByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID, ALTERNATE_KEY_ROWS);
            final List<CardCrossReference> unresolved =
                    cardCrossReferenceRepository.findByXrefAcctIdOrderByXrefCardNumAsc(UNKNOWN_ACCOUNT_ID, ALTERNATE_KEY_ROWS);

            assertAll(
                    () -> assertThat(unresolved)
                            .as("an empty list is the not-found path, never an empty optional")
                            .isEmpty(),
                    () -> assertThat(resolved).hasSize(2),
                    () -> assertThat(resolved.get(0))
                            .as("the first element exactly as supplied, with no re-sorting")
                            .isSameAs(firstAsSupplied),
                    () -> assertThat(resolved.get(1)).isSameAs(secondAsSupplied),
                    () -> assertThat(resolved.get(0).getXrefCardNum())
                            .isEqualTo("4111111111111112"),
                    () -> assertThat(resolved.get(0).getXrefAcctId()).isEqualTo(ACCOUNT_ID),
                    () -> assertThat(resolved.get(0).getXrefCustId()).isEqualTo(CUSTOMER_ID));
        }
    }

    // =================================================================================================
    // Lines 403 to 413 - the overlimit basis: strictly left to right, truncating, inclusive limit
    // =================================================================================================

    @Nested
    @DisplayName("The overlimit basis: cycle credit less cycle debit plus the amount, truncated")
    class TheOverlimitBasis {

        /**
         * The three operands every case in this group uses. A left-to-right evaluation of
         * 900.00 - 100.00 + 200.55 gives 1000.55. Three plausible rearrangements give something else:
         * moving the parenthesis gives 900.00 - (100.00 + 200.55) = 599.45, swapping the first two
         * operands gives 100.00 - 900.00 + 200.55 = -399.45, and losing the minus sign gives
         * 900.00 + 100.00 + 200.55 = 1200.55. All four expected values are literals here.
         */
        private static final String CYCLE_CREDIT = "900.00";

        private static final String CYCLE_DEBIT = "100.00";

        private static final String AMOUNT = "200.55";

        private static final BigDecimal LEFT_TO_RIGHT_BASIS = new BigDecimal("1000.55");

        private static final BigDecimal MISPLACED_PARENTHESIS_BASIS = new BigDecimal("599.45");

        private static final BigDecimal SWAPPED_OPERANDS_BASIS = new BigDecimal("-399.45");

        private static final BigDecimal LOST_MINUS_SIGN_BASIS = new BigDecimal("1200.55");

        /**
         * What a store of 10.015 into a two-decimal field produces under each policy, written as
         * literals rather than computed, so neither the production codec nor a rounding enumeration
         * supplies the expectation. The legacy truncates toward zero because the estate carries no
         * rounding phrase at all; a translation rounding to nearest would produce the second value.
         */
        private static final BigDecimal TRUNCATED_THIRD_DECIMAL = new BigDecimal("10.01");

        private static final BigDecimal ROUNDED_THIRD_DECIMAL = new BigDecimal("10.02");

        @Test
        @DisplayName("a limit one cent below the left-to-right basis rejects, which no rearrangement "
                + "of the same three operands reproduces")
        void aLimitOneCentBelowTheLeftToRightBasisRejects() {
            // 1000.54 sits below 1000.55 and below 1200.55, but above 599.45 and above -399.45.
            arrangeValidation(accountWith("0.00", "1000.54", CYCLE_CREDIT, CYCLE_DEBIT, LATE_EXPIRY));

            assertAll(
                    () -> assertThat(service.post(record("T1", AMOUNT)).reasonCode())
                            .as("only an evaluation reaching 1000.55 or more rejects here")
                            .isEqualTo(102),
                    () -> assertThat(new BigDecimal("1000.54"))
                            .isLessThan(LEFT_TO_RIGHT_BASIS)
                            .isGreaterThan(MISPLACED_PARENTHESIS_BASIS)
                            .isGreaterThan(SWAPPED_OPERANDS_BASIS));
        }

        @Test
        @DisplayName("a limit between the left-to-right basis and the lost-minus-sign basis accepts, "
                + "which pins the sign of the middle operand")
        void aLimitAboveTheLeftToRightBasisAccepts() {
            // 1100.00 sits above 1000.55 and below 1200.55, so an implementation that added the debit
            // instead of subtracting it would reject this record and this one accepts it.
            arrangePosting(accountWith("0.00", "1100.00", CYCLE_CREDIT, CYCLE_DEBIT, LATE_EXPIRY));

            assertAll(
                    () -> assertThat(service.post(record("T1", AMOUNT)).reasonCode()).isZero(),
                    () -> assertThat(new BigDecimal("1100.00"))
                            .isGreaterThan(LEFT_TO_RIGHT_BASIS)
                            .isLessThan(LOST_MINUS_SIGN_BASIS));
        }

        @Test
        @DisplayName("a limit exactly equal to the basis is ACCEPTED: the comparison is inclusive on "
                + "the limit side, so equality is not a rejection")
        void aLimitExactlyEqualToTheBasisIsAccepted() {
            arrangePosting(accountWith("0.00", "1000.55", CYCLE_CREDIT, CYCLE_DEBIT, LATE_EXPIRY));

            assertAll(
                    () -> assertThat(service.post(record("T1", AMOUNT)).reasonCode())
                            .as("the inclusive side is the limit's")
                            .isZero(),
                    () -> assertThat(new BigDecimal("1000.55")).isEqualTo(LEFT_TO_RIGHT_BASIS));
        }

        @Test
        @DisplayName("the basis TRUNCATES toward zero: a third decimal is dropped, not carried into "
                + "the second, so a rounding-to-nearest translation rejects where this one accepts")
        void theBasisTruncatesRatherThanRoundingToNearest() {
            // A cycle credit of 10.015 truncates to 10.01, which a limit of 10.01 allows. Rounding to
            // nearest - either half-even or half-up - gives 10.02 and rejects the record, because the
            // digit before the discarded half is odd. One cent decides it.
            arrangePosting(accountWith("0.00", "10.01", "10.015", "0.00", LATE_EXPIRY));

            assertAll(
                    () -> assertThat(service.post(record("T1", "0.00")).reasonCode())
                            .as("truncation gives 10.01; rounding to nearest would give 10.02")
                            .isZero(),
                    () -> assertThat(TRUNCATED_THIRD_DECIMAL)
                            .as("the value the legacy store produces, and the limit it does not exceed")
                            .isEqualTo(new BigDecimal("10.01"))
                            .isLessThanOrEqualTo(new BigDecimal("10.01")),
                    () -> assertThat(ROUNDED_THIRD_DECIMAL)
                            .as("the value a rounding-to-nearest translation would produce, which "
                                    + "exceeds the same limit and would reject the record")
                            .isGreaterThan(new BigDecimal("10.01"))
                            .isNotEqualTo(TRUNCATED_THIRD_DECIMAL));
        }

        @Test
        @DisplayName("the truncation is visible on a published value too: a three-decimal amount is "
                + "stored at scale two with its third decimal dropped")
        void aThreeDecimalAmountIsStoredTruncatedAtScaleTwo() {
            arrangePosting(postableAccount());

            service.post(record("T1", "10.019"));

            final BigDecimal storedAmount = capturePostedTransaction().getTranAmt();
            assertAll(
                    () -> assertThat(storedAmount).isEqualTo(new BigDecimal("10.01")),
                    () -> assertThat(storedAmount.scale())
                            .as("every monetary value is published at the two decimals its field holds")
                            .isEqualTo(2),
                    () -> assertThat(storedAmount).isNotEqualTo(new BigDecimal("10.02")));
        }

        @Test
        @DisplayName("every monetary value the posting stages publish carries scale two exactly")
        void everyPublishedMonetaryValueCarriesScaleTwo() {
            // The accumulator the sign branch does not touch is passed through exactly as the account
            // carried it, which is faithful: its legacy field is always two decimals wide, so the
            // fixture supplies it at that scale and the branch that does move a value truncates.
            arrangePosting(accountWith("100.005", AMPLE_LIMIT, "5.005", "0.00", LATE_EXPIRY));

            service.post(record("T1", "25.005"));

            final List<BigDecimal> rewritten = captureRewrittenBalances();
            assertAll(
                    () -> assertThat(rewritten.get(0).scale()).isEqualTo(2),
                    () -> assertThat(rewritten.get(1).scale()).isEqualTo(2),
                    () -> assertThat(rewritten.get(2).scale()).isEqualTo(2),
                    () -> assertThat(rewritten.get(0))
                            .as("100.005 plus 25.005 truncated, never rounded")
                            .isEqualTo(new BigDecimal("125.01")),
                    () -> assertThat(rewritten.get(1)).isEqualTo(new BigDecimal("30.01")),
                    () -> assertThat(rewritten.get(2)).isEqualTo(new BigDecimal("0.00")),
                    () -> assertThat(captureRewrittenCategoryBalance().getTranCatBal())
                            .isEqualTo(new BigDecimal("25.00")),
                    () -> assertThat(captureRewrittenCategoryBalance().getTranCatBal().scale())
                            .isEqualTo(2),
                    () -> assertThat(capturePostedTransaction().getTranAmt())
                            .isEqualTo(new BigDecimal("25.00")),
                    () -> assertThat(capturePostedTransaction().getTranAmt().scale()).isEqualTo(2));
        }
    }

    // =================================================================================================
    // Lines 414 to 420 - the expiry comparison reads the ORIGINATION timestamp
    // =================================================================================================

    @Nested
    @DisplayName("The expiry comparison reads the origination timestamp, never the processing one")
    class TheExpiryComparison {

        @Test
        @DisplayName("a constructed record whose ORIGINATION date is past the expiry is rejected even "
                + "though its processing timestamp is the twenty-six blanks every fixture record carries")
        void theOriginationTimestampDecidesWhenTheProcessingTimestampIsBlank() {
            arrangeValidation(accountWith("0.00", AMPLE_LIMIT, "0.00", "0.00", "2025-05-20"));
            final DailyTransaction late = recordOn("T1", "10.00", CARD, LATE_ORIGINATION_TIMESTAMP,
                    BLANK_TIMESTAMP);

            final PostingResult result = service.post(late);

            assertAll(
                    () -> assertThat(result.reasonCode())
                            .as("reading the blank processing timestamp instead would accept this")
                            .isEqualTo(103),
                    () -> assertThat(late.getDalytranProcTs()).isEqualTo(BLANK_TIMESTAMP),
                    () -> assertThat(encodedWidth(late.getDalytranProcTs()))
                            .isEqualTo(TIMESTAMP_WIDTH),
                    () -> assertThat(late.getDalytranProcTs().isBlank()).isTrue());
        }

        @Test
        @DisplayName("the mirror case: a constructed record whose ORIGINATION date is inside the expiry "
                + "but whose processing timestamp is far beyond it is ACCEPTED")
        void theProcessingTimestampIsIgnoredEvenWhenItIsPopulated() {
            arrangePosting(accountWith("0.00", AMPLE_LIMIT, "0.00", "0.00", "2025-05-20"));
            final DailyTransaction early = recordOn("T1", "10.00", CARD,
                    EARLY_ORIGINATION_TIMESTAMP, LATE_ORIGINATION_TIMESTAMP);

            final PostingResult result = service.post(early);

            assertAll(
                    () -> assertThat(result.reasonCode())
                            .as("reading the populated processing timestamp instead would reject this")
                            .isZero(),
                    () -> assertThat(result.posted()).isTrue());
        }

        @Test
        @DisplayName("an expiry equal to the origination date is accepted, so the comparison is "
                + "inclusive on the expiry side")
        void anExpiryEqualToTheOriginationDateIsAccepted() {
            arrangePosting(accountWith("0.00", AMPLE_LIMIT, "0.00", "0.00", "2022-07-19"));

            assertThat(service.post(record("T1", "10.00")).reasonCode()).isZero();
        }

        @Test
        @DisplayName("an expiry one day earlier is rejected, and the difference is a single character")
        void anExpiryOneDayEarlierIsRejected() {
            arrangeValidation(accountWith("0.00", AMPLE_LIMIT, "0.00", "0.00", "2022-07-18"));

            assertThat(service.post(record("T1", "10.00")).reasonCode()).isEqualTo(103);
        }

        @Test
        @DisplayName("only the first ten characters of the twenty-six character timestamp participate")
        void onlyTheFirstTenCharactersParticipate() {
            // The origination timestamp carries a time of day and the expiry date carries none, so a
            // comparison reaching past character ten would make the ten-character expiry the smaller
            // and reject a record the legacy accepts.
            arrangePosting(accountWith("0.00", AMPLE_LIMIT, "0.00", "0.00", "2022-07-19"));

            assertAll(
                    () -> assertThat(service.post(record("T1", "10.00")).reasonCode()).isZero(),
                    () -> assertThat(encodedWidth(ORIGINATION_TIMESTAMP)).isEqualTo(TIMESTAMP_WIDTH),
                    () -> assertThat(ORIGINATION_TIMESTAMP.substring(0, 10)).isEqualTo("2022-07-19"));
        }

        @Test
        @DisplayName("an expiration date shorter than its ten-character field is blank-padded rather "
                + "than refused, and the blanks sort below any digit")
        void aShortExpirationDateIsBlankPadded() {
            arrangeValidation(accountWith("0.00", AMPLE_LIMIT, "0.00", "0.00", "2022-07"));

            assertThat(service.post(record("T1", "10.00")).reasonCode()).isEqualTo(103);
        }

        @Test
        @DisplayName("an expiration date longer than its field loses its excess rather than being "
                + "compared whole")
        void aLongExpirationDateIsTruncatedToItsField() {
            arrangePosting(accountWith("0.00", AMPLE_LIMIT, "0.00", "0.00", "2099-01-01T00:00:00"));

            assertThat(service.post(record("T1", "10.00")).reasonCode()).isZero();
        }
    }

    // =================================================================================================
    // Line 556 - reason 109 is set, is distinct from 101, and is inert
    // =================================================================================================

    @Nested
    @DisplayName("Reason 109 is inert: set on a posted record, producing no reject record")
    class TheInertReason109 {

        @Test
        @DisplayName("on a CONSTRUCTED rewrite-absence path - 109 is not emitted at all on unmodified "
                + "fixtures - the record still posts and the transaction file is STILL written")
        void theTransactionIsStillWrittenAndTheRecordStillPosts() {
            final Account account = postableAccount();
            arrangeThroughCategoryBalance(account);
            heldReadFindsNothing();
            rewriteAffects(0);
            transactionWriteEchoes();

            final PostingResult result = service.post(record("T1", "10.00"));

            assertAll(
                    () -> assertThat(result.reasonCode()).isEqualTo(109),
                    () -> assertThat(result.reasonDescription()).isEqualTo(DESCRIPTION_109),
                    () -> assertThat(result.rejectReason())
                            .isEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE),
                    () -> assertThat(result.posted())
                            .as("a non-zero reason on a POSTED record: the two are not complements")
                            .isTrue(),
                    () -> assertThat(result.rejected()).isFalse(),
                    () -> assertThat(result.postedTransaction()).isNotNull());
            verify(transactionRepository).insertAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName("on that same CONSTRUCTED path a run hands NOTHING to the reject writer, counts the "
                + "record as posted, and returns the success code")
        void noRejectRecordIsWrittenAndTheRunReportsSuccess() {
            final Account account = postableAccount();
            arrangeThroughCategoryBalance(account);
            heldReadFindsNothing();
            rewriteAffects(0);
            transactionWriteEchoes();
            final List<PostingResult> rejectSink = new ArrayList<>();

            final PostingRunSummary summary =
                    service.postAll(List.of(record("T1", "10.00")), rejectSink::add);

            assertAll(
                    () -> assertThat(rejectSink)
                            .as("109 never produces a 430-byte reject record")
                            .isEmpty(),
                    () -> assertThat(summary.transactionsProcessed()).isEqualTo(1L),
                    () -> assertThat(summary.transactionsPosted()).isEqualTo(1L),
                    () -> assertThat(summary.transactionsRejected()).isZero(),
                    () -> assertThat(summary.returnCode())
                            .isEqualTo(TransactionPostingService.RETURN_CODE_SUCCESS),
                    () -> assertThat(summary.partialSuccess()).isFalse());
        }

        @Test
        @DisplayName("109 is declared as its own reason and carries reason 101's text verbatim, yet the "
                + "two remain distinct constants because they arise at different points")
        void theTwoAccountNotFoundReasonsShareTextAndStayDistinct() {
            assertAll(
                    () -> assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getReasonCode())
                            .isEqualTo(109),
                    () -> assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getReasonCode())
                            .isEqualTo(101),
                    () -> assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getDescription())
                            .as("the shared text is deliberate, not accidental")
                            .isEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getDescription())
                            .isEqualTo(DESCRIPTION_109),
                    () -> assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE)
                            .isNotEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_READ),
                    () -> assertThat(PADDED_DESCRIPTION_109).isEqualTo(PADDED_DESCRIPTION_101),
                    () -> assertThat(REASON_IMAGE_109).isNotEqualTo(REASON_IMAGE_101));
        }
    }

    // =================================================================================================
    // Lines 440 to 442 - the three persistence stages and their one order
    // =================================================================================================

    @Nested
    @DisplayName("The posting stage order: category balance, then account, then transaction file")
    class ThePostingStageOrder {

        @Test
        @DisplayName("the three stages run in exactly that order, and no fourth interaction happens")
        void theThreeStagesRunInTheLegacyOrder() {
            arrangePosting(postableAccount());

            service.post(record("T1", "10.00"));

            final InOrder ordered =
                    inOrder(categoryBalanceRepository, accountRepository, transactionRepository);
            ordered.verify(categoryBalanceRepository)
                    .findById(any(TransactionCategoryBalanceId.class));
            ordered.verify(categoryBalanceRepository)
                    .saveAndFlush(any(TransactionCategoryBalance.class));
            ordered.verify(accountRepository).findByIdForUpdate(ACCOUNT_ID);
            ordered.verify(accountRepository).rewritePostingBalances(eq(ACCOUNT_ID),
                    ArgumentMatchers.anyLong(), any(BigDecimal.class), any(BigDecimal.class),
                    any(BigDecimal.class));
            ordered.verify(transactionRepository).insertAndFlush(any(Transaction.class));
            ordered.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("each stage is verified once and no more, on each of the three repositories "
                + "separately, so a duplicated write would fail this test")
        void eachStageHappensExactlyOnce() {
            arrangePosting(postableAccount());

            service.post(record("T1", "10.00"));

            verify(categoryBalanceRepository, times(1))
                    .findById(any(TransactionCategoryBalanceId.class));
            verify(categoryBalanceRepository, times(1))
                    .saveAndFlush(any(TransactionCategoryBalance.class));
            verifyNoMoreInteractions(categoryBalanceRepository);
            verify(accountRepository, times(1)).findById(ACCOUNT_ID);
            verify(accountRepository, times(1)).findByIdForUpdate(ACCOUNT_ID);
            verify(accountRepository, times(1)).rewritePostingBalances(eq(ACCOUNT_ID),
                    ArgumentMatchers.anyLong(), any(BigDecimal.class), any(BigDecimal.class),
                    any(BigDecimal.class));
            verifyNoMoreInteractions(accountRepository);
            verify(transactionRepository, times(1)).insertAndFlush(any(Transaction.class));
            verifyNoMoreInteractions(transactionRepository);
        }

        @Test
        @DisplayName("the eleven field moves land intact, and the four merchant values cross from a "
                + "prefixed source property to an unprefixed target one")
        void theElevenFieldMovesLandIntact() {
            arrangePosting(postableAccount());
            final DailyTransaction source = record("T1", "10.00");

            service.post(source);

            final Transaction posted = capturePostedTransaction();
            assertAll(
                    () -> assertThat(posted.getTranId()).isEqualTo(source.getDalytranId()),
                    () -> assertThat(posted.getTranTypeCd()).isEqualTo(TYPE_CODE),
                    () -> assertThat(posted.getTranCatCd()).isEqualTo(CATEGORY_CODE),
                    () -> assertThat(posted.getTranSource())
                            .as("trailing blanks included, never trimmed")
                            .isEqualTo("POS TERM  "),
                    () -> assertThat(posted.getTranDesc()).isEqualTo("purchase"),
                    () -> assertThat(posted.getTranAmt()).isEqualTo(new BigDecimal("10.00")),
                    () -> assertThat(posted.getMerchantId())
                            .isEqualTo(source.getDalytranMerchantId()),
                    () -> assertThat(posted.getMerchantName())
                            .isEqualTo(source.getDalytranMerchantName()),
                    () -> assertThat(posted.getMerchantCity())
                            .isEqualTo(source.getDalytranMerchantCity()),
                    () -> assertThat(posted.getMerchantZip())
                            .isEqualTo(source.getDalytranMerchantZip()),
                    () -> assertThat(SensitiveValues.fingerprint(posted.getTranCardNum())).isEqualTo(SensitiveValues.fingerprint(CARD)));
        }
    }

    // =================================================================================================
    // 2800-UPDATE-ACCOUNT-REC, lines 545 to 560 - the sign branch and the rewrite
    // =================================================================================================

    @Nested
    @DisplayName("The account stage: a negative amount joins the debit total unchanged")
    class TheAccountStage {

        @Test
        @DisplayName("a negative amount leaves the debit accumulator NEGATIVE at scale two, neither "
                + "negated nor made absolute, and never diverted to a credit bucket")
        void aNegativeAmountLeavesTheDebitAccumulatorNegative() {
            arrangePosting(accountWith("100.00", AMPLE_LIMIT, "0.00", "0.00", LATE_EXPIRY));

            service.post(record("T1", "-50.00"));

            final List<BigDecimal> rewritten = captureRewrittenBalances();
            assertAll(
                    () -> assertThat(rewritten.get(2))
                            .as("the debit total holds a negative value, and is meant to")
                            .isEqualTo(new BigDecimal("-50.00")),
                    () -> assertThat(rewritten.get(2).signum()).isNegative(),
                    () -> assertThat(rewritten.get(2).scale()).isEqualTo(2),
                    () -> assertThat(rewritten.get(2))
                            .as("not the absolute value")
                            .isNotEqualTo(new BigDecimal("50.00")),
                    () -> assertThat(rewritten.get(1))
                            .as("the credit total is untouched by a negative amount")
                            .isEqualTo(new BigDecimal("0.00")),
                    () -> assertThat(rewritten.get(0))
                            .as("the current balance is reduced unconditionally")
                            .isEqualTo(new BigDecimal("50.00")));
        }

        @Test
        @DisplayName("zero counts as non-negative and joins the credit total, so the branch is on "
                + "greater-than-or-equal and not on strictly greater")
        void zeroJoinsTheCreditTotal() {
            arrangePosting(accountWith("100.00", AMPLE_LIMIT, "7.00", "3.00", LATE_EXPIRY));

            service.post(record("T1", "0.00"));

            final List<BigDecimal> rewritten = captureRewrittenBalances();
            assertAll(
                    () -> assertThat(rewritten.get(1)).isEqualTo(new BigDecimal("7.00")),
                    () -> assertThat(rewritten.get(2)).isEqualTo(new BigDecimal("3.00")),
                    () -> assertThat(rewritten.get(0)).isEqualTo(new BigDecimal("100.00")));
        }

        @Test
        @DisplayName("a positive amount joins the credit total and raises the current balance")
        void aPositiveAmountJoinsTheCreditTotal() {
            arrangePosting(accountWith("100.00", AMPLE_LIMIT, "5.00", "0.00", LATE_EXPIRY));

            service.post(record("T1", "25.00"));

            final List<BigDecimal> rewritten = captureRewrittenBalances();
            assertAll(
                    () -> assertThat(rewritten.get(0)).isEqualTo(new BigDecimal("125.00")),
                    () -> assertThat(rewritten.get(1)).isEqualTo(new BigDecimal("30.00")),
                    () -> assertThat(rewritten.get(2)).isEqualTo(new BigDecimal("0.00")));
        }

        @Test
        @DisplayName("the rewrite matches on the version the validation read observed, so a row another "
                + "writer has already moved cannot be overwritten unnoticed")
        void theRewriteMatchesOnTheVersionValidationRead() {
            final Account read = postableAccount();
            arrangePosting(read);
            final ArgumentCaptor<Long> matchedVersion = ArgumentCaptor.forClass(Long.class);

            service.post(record("T1", "10.00"));

            verify(accountRepository).rewritePostingBalances(eq(ACCOUNT_ID),
                    matchedVersion.capture(), any(BigDecimal.class), any(BigDecimal.class),
                    any(BigDecimal.class));
            assertThat(matchedVersion.getValue())
                    .as("taken from the image validation read, never a constant and never omitted")
                    .isEqualTo(read.getVersion());
        }

        @Test
        @DisplayName("the row is held before the rewrite, on the key the cross-reference resolved, so "
                + "the invalid-key answer cannot be inverted by a writer committing in between")
        void theHeldReadPrecedesTheRewrite() {
            arrangePosting(postableAccount());

            service.post(record("T1", "10.00"));

            final InOrder ordered = inOrder(accountRepository);
            ordered.verify(accountRepository).findByIdForUpdate(ACCOUNT_ID);
            ordered.verify(accountRepository).rewritePostingBalances(eq(ACCOUNT_ID),
                    ArgumentMatchers.anyLong(), any(BigDecimal.class), any(BigDecimal.class),
                    any(BigDecimal.class));
        }

        @Test
        @DisplayName("exactly one held read is taken per record, so the hold is neither re-acquired nor "
                + "the classification re-derived")
        void oneHeldReadIsTakenPerRecord() {
            arrangePosting(postableAccount());

            service.postAll(List.of(record("T1", "10.00"), record("T2", "20.00")), result -> {
                // No reject can occur on this arrangement; the sink exists because the run needs one.
            });

            verify(accountRepository, times(2)).findByIdForUpdate(ACCOUNT_ID);
        }

        @Test
        @DisplayName("no unprotected existence probe is issued, because an answer obtained after the "
                + "rewrite could have been inverted by a writer committing in between")
        void noUnprotectedExistenceProbeIsIssued() {
            final Account account = postableAccount();
            arrangeThroughCategoryBalance(account);
            heldReadFinds(account);
            rewriteAffects(0);

            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.post(record("T1", "10.00")));

            verify(accountRepository, never()).existsById(ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("a rewrite affecting no row while the held read found the row is a version race, "
                + "refused rather than reported as an absent account")
        void aHeldRowThatRewroteNothingIsAVersionRace() {
            final Account account = postableAccount();
            arrangeThroughCategoryBalance(account);
            heldReadFinds(account);
            rewriteAffects(0);

            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.post(record("T1", "10.00")))
                    .satisfies(conflict -> assertAll(
                            () -> assertThat(conflict.conflictKind()).isEqualTo(
                                    OptimisticLockConflictException.ConflictKind
                                            .RECORD_CHANGED_BEFORE_UPDATE),
                            () -> assertThat(conflict.entityName()).isEqualTo("Account"),
                            () -> assertThat(conflict.key()).isEqualTo(ACCOUNT_ID)));
            verify(transactionRepository, never()).insertAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName("a version race is a failure rather than a reject, so a run propagates it and "
                + "hands nothing to the reject writer")
        void aVersionRaceIsAFailureNotAReject() {
            final Account account = postableAccount();
            arrangeThroughCategoryBalance(account);
            heldReadFinds(account);
            rewriteAffects(0);
            final List<PostingResult> rejectSink = new ArrayList<>();

            assertThatExceptionOfType(OptimisticLockConflictException.class).isThrownBy(
                    () -> service.postAll(List.of(record("T1", "10.00")), rejectSink::add));

            assertThat(rejectSink).isEmpty();
        }

        @Test
        @DisplayName("the balances the stage computed are still carried on the result image when the "
                + "rewrite took its CONSTRUCTED invalid-key arm, because the moves ran before it")
        void theComputedBalancesSurviveTheInvalidKeyArm() {
            final Account account = accountWith("100.00", AMPLE_LIMIT, "0.00", "0.00", LATE_EXPIRY);
            arrangeThroughCategoryBalance(account);
            heldReadFindsNothing();
            rewriteAffects(0);
            transactionWriteEchoes();

            final PostingResult result = service.post(record("T1", "25.00"));

            assertAll(
                    () -> assertThat(result.updatedAccount().getAcctCurrBal())
                            .isEqualTo(new BigDecimal("125.00")),
                    () -> assertThat(result.updatedAccount().getAcctCurrCycCredit())
                            .isEqualTo(new BigDecimal("25.00")),
                    () -> assertThat(result.updatedAccount().getAcctCurrCycDebit())
                            .isEqualTo(new BigDecimal("0.00")));
        }

        @Test
        @DisplayName("a held read that fails technically abends rather than being read as an absence")
        void aFailingHeldReadAbendsRatherThanBeingReadAsAnAbsence() {
            final Account account = postableAccount();
            arrangeThroughCategoryBalance(account);
            when(accountRepository.findByIdForUpdate(ACCOUNT_ID))
                    .thenThrow(new DataAccessResourceFailureException("lock unavailable"));

            assertThatExceptionOfType(DataAccessResourceFailureException.class)
                    .isThrownBy(() -> service.post(record("T1", "10.00")));

            verify(transactionRepository, never()).insertAndFlush(any(Transaction.class));
        }
    }

    // =================================================================================================
    // 2700-UPDATE-TCATBAL and its two arms, lines 467 to 542 - a missing row is created, not rejected
    // =================================================================================================

    @Nested
    @DisplayName("The category-balance stage: a missing row is created, never rejected")
    class TheCategoryBalanceStage {

        @Test
        @DisplayName("a CONSTRUCTED missing row is created carrying the amount as its whole balance, "
                + "the record still posts, and NOTHING is rejected")
        void aMissingRowIsCreatedAndNothingIsRejected() {
            final Account account = postableAccount();
            arrangeValidation(account);
            categoryBalanceMissing();
            categoryBalanceInsertEchoes();
            heldReadFinds(account);
            rewriteAffects(1);
            transactionWriteEchoes();
            final List<PostingResult> rejectSink = new ArrayList<>();

            final PostingRunSummary summary =
                    service.postAll(List.of(record("T1", "10.00")), rejectSink::add);

            final TransactionCategoryBalance created = captureInsertedCategoryBalance();
            assertAll(
                    () -> assertThat(rejectSink)
                            .as("a missing keyed row normalises to success, so nothing is rejected")
                            .isEmpty(),
                    () -> assertThat(summary.transactionsRejected()).isZero(),
                    () -> assertThat(summary.returnCode())
                            .isEqualTo(TransactionPostingService.RETURN_CODE_SUCCESS),
                    () -> assertThat(created.getTranCatBal())
                            .as("the new row's balance is the amount and nothing else")
                            .isEqualTo(new BigDecimal("10.00")),
                    () -> assertThat(created.getTranCatBal().scale()).isEqualTo(2),
                    () -> assertThat(created.getTrancatAcctId()).isEqualTo(ACCOUNT_ID),
                    () -> assertThat(created.getTrancatTypeCd()).isEqualTo(TYPE_CODE),
                    () -> assertThat(created.getTrancatCd()).isEqualTo(CATEGORY_CODE));
            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("the composite key is built in the declared order - account, then TYPE, then "
                + "CATEGORY - from the cross-reference account and the record's own two codes")
        void theCompositeKeyIsBuiltInTheDeclaredOrder() {
            final Account account = postableAccount();
            arrangeValidation(account);
            categoryBalanceMissing();
            categoryBalanceInsertEchoes();
            heldReadFinds(account);
            rewriteAffects(1);
            transactionWriteEchoes();

            service.post(record("T1", "10.00"));

            final ArgumentCaptor<TransactionCategoryBalanceId> captor =
                    ArgumentCaptor.forClass(TransactionCategoryBalanceId.class);
            verify(categoryBalanceRepository).findById(captor.capture());
            final TransactionCategoryBalanceId key = captor.getValue();
            assertAll(
                    () -> assertThat(key.getTrancatAcctId()).isEqualTo(ACCOUNT_ID),
                    () -> assertThat(key.getTrancatTypeCd()).isEqualTo(TYPE_CODE),
                    () -> assertThat(key.getTrancatCd()).isEqualTo(CATEGORY_CODE),
                    () -> assertThat(key)
                            .as("the declared component order is what equality is built on")
                            .isEqualTo(new TransactionCategoryBalanceId(ACCOUNT_ID, TYPE_CODE,
                                    CATEGORY_CODE))
                            .isNotEqualTo(new TransactionCategoryBalanceId(ACCOUNT_ID,
                                    CATEGORY_CODE, TYPE_CODE)));
        }

        @Test
        @DisplayName("an existing row accumulates rather than being replaced")
        void anExistingRowAccumulates() {
            final Account account = postableAccount();
            arrangeValidation(account);
            categoryBalanceFound("40.00");
            categoryBalanceRewriteEchoes();
            heldReadFinds(account);
            rewriteAffects(1);
            transactionWriteEchoes();

            service.post(record("T1", "10.00"));

            assertThat(captureRewrittenCategoryBalance().getTranCatBal())
                    .isEqualTo(new BigDecimal("50.00"));
        }

        @Test
        @DisplayName("a negative amount reduces an existing balance, because the sign is not examined "
                + "in this stage at all")
        void aNegativeAmountReducesAnExistingBalance() {
            final Account account = accountWith("100.00", AMPLE_LIMIT, "0.00", "0.00", LATE_EXPIRY);
            arrangeValidation(account);
            categoryBalanceFound("10.00");
            categoryBalanceRewriteEchoes();
            heldReadFinds(account);
            rewriteAffects(1);
            transactionWriteEchoes();

            service.post(record("T1", "-50.00"));

            assertThat(captureRewrittenCategoryBalance().getTranCatBal())
                    .isEqualTo(new BigDecimal("-40.00"));
        }

        @Test
        @DisplayName("the create path uses the create-only write boundary and the update path uses the "
                + "rewrite, so the choice is made here and never delegated to an upsert")
        void thecreateAndUpdatePathsAreDistinctWrites() {
            final Account account = postableAccount();
            arrangeValidation(account);
            categoryBalanceMissing();
            categoryBalanceInsertEchoes();
            heldReadFinds(account);
            rewriteAffects(1);
            transactionWriteEchoes();

            service.post(record("T1", "10.00"));

            verify(recordWriter).insert(any(TransactionCategoryBalance.class));
            verify(categoryBalanceRepository, never())
                    .saveAndFlush(any(TransactionCategoryBalance.class));
        }
    }

    // =================================================================================================
    // Line 436 and Z-GET-DB2-FORMAT-TIMESTAMP, lines 692 to 705 - one timestamp copied, one rebuilt
    // =================================================================================================

    @Nested
    @DisplayName("The two timestamps: origination copied verbatim, processing regenerated")
    class TheTwoTimestamps {

        @Test
        @DisplayName("the origination timestamp is byte-identical to the input at exactly twenty-six "
                + "encoded bytes, trailing characters and all")
        void theOriginationTimestampIsByteIdenticalToTheInput() {
            arrangePosting(postableAccount());
            final DailyTransaction source = record("T1", "10.00");

            service.post(source);

            final String copied = capturePostedTransaction().getTranOrigTs();
            assertAll(
                    () -> assertThat(copied).isEqualTo(source.getDalytranOrigTs()),
                    () -> assertThat(copied).isEqualTo(ORIGINATION_TIMESTAMP),
                    () -> assertThat(encodedWidth(copied))
                            .as("measured on encoded bytes, never on a character count")
                            .isEqualTo(TIMESTAMP_WIDTH),
                    () -> assertThat(encodedWidth(source.getDalytranOrigTs()))
                            .isEqualTo(encodedWidth(copied)));
        }

        @Test
        @DisplayName("the processing timestamp is regenerated in the BATCH form: a hyphen before the "
                + "hour, dots between the time parts, two hundredths digits and a literal four zeros")
        void theProcessingTimestampCarriesTheBatchForm() {
            arrangePosting(postableAccount());

            service.post(record("T1", "10.00"));

            final String regenerated = capturePostedTransaction().getTranProcTs();
            assertAll(
                    () -> assertThat(regenerated).isEqualTo(EXPECTED_PROCESSING_TIMESTAMP),
                    () -> assertThat(encodedWidth(regenerated)).isEqualTo(TIMESTAMP_WIDTH),
                    () -> assertThat(regenerated.charAt(4)).isEqualTo('-'),
                    () -> assertThat(regenerated.charAt(7)).isEqualTo('-'),
                    () -> assertThat(regenerated.charAt(10))
                            .as("a hyphen where a conventional timestamp carries a separator")
                            .isEqualTo('-'),
                    () -> assertThat(regenerated.charAt(13)).isEqualTo('.'),
                    () -> assertThat(regenerated.charAt(16)).isEqualTo('.'),
                    () -> assertThat(regenerated.charAt(19)).isEqualTo('.'),
                    () -> assertThat(regenerated.substring(20, 22))
                            .as("the two hundredths digits of 457 milliseconds, truncated")
                            .isEqualTo("45"),
                    () -> assertThat(regenerated.substring(22))
                            .as("the literal tail, which is never anything else")
                            .isEqualTo("0000"));
        }

        @Test
        @DisplayName("the ONLINE form is never emitted here: no colon anywhere, no space at position "
                + "eleven, and no six-digit fraction")
        void theOnlineFormIsNeverEmitted() {
            arrangePosting(postableAccount());

            service.post(record("T1", "10.00"));

            final String regenerated = capturePostedTransaction().getTranProcTs();
            assertAll(
                    () -> assertThat(regenerated).doesNotContain(":"),
                    () -> assertThat(regenerated.charAt(10)).isNotEqualTo(' '),
                    () -> assertThat(regenerated)
                            .as("a six-digit fraction belongs to the other tier's form")
                            .doesNotContain("000000"),
                    () -> assertThat(regenerated).isNotEqualTo(BLANK_TIMESTAMP),
                    () -> assertThat(encodedWidth(regenerated))
                            .as("both forms are twenty-six bytes wide, which is why the form and not "
                                    + "the width is what must be asserted")
                            .isEqualTo(TIMESTAMP_WIDTH));
        }

        @Test
        @DisplayName("the two timestamps on the posted record differ, so neither field was filled from "
                + "the other")
        void theTwoTimestampsAreNotTheSameValue() {
            arrangePosting(postableAccount());

            service.post(record("T1", "10.00"));

            final Transaction posted = capturePostedTransaction();
            assertAll(
                    () -> assertThat(posted.getTranOrigTs()).isNotEqualTo(posted.getTranProcTs()),
                    () -> assertThat(posted.getTranOrigTs()).isEqualTo(ORIGINATION_TIMESTAMP),
                    () -> assertThat(posted.getTranProcTs())
                            .isEqualTo(EXPECTED_PROCESSING_TIMESTAMP));
        }

        @Test
        @DisplayName("the declared processing-timestamp width is twenty-six")
        void theDeclaredWidthIsTwentySix() {
            assertThat(TransactionPostingService.BATCH_TIMESTAMP_LENGTH).isEqualTo(TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("a year the four-digit field cannot hold is refused rather than silently widening "
                + "the twenty-six character contract")
        void aYearTheFieldCannotHoldIsRefused() {
            final TransactionPostingService farFuture = new TransactionPostingService(
                    dailyTransactionRepository, transactionRepository, accountRepository,
                    cardCrossReferenceRepository, categoryBalanceRepository, recordWriter,
                    abendService, transactionBoundary,
                    Clock.fixed(Instant.parse("+10000-01-01T00:00:00Z"), ZoneOffset.UTC));
            // The timestamp is built while the transaction record is assembled, which is before the
            // first persistence stage, so no stage needs arranging for this refusal to be reached.
            arrangeValidation(postableAccount());

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> farFuture.post(record("T1", "10.00")));

            verifyNoInteractions(categoryBalanceRepository, transactionRepository, recordWriter);
        }
    }

    // =================================================================================================
    // PROCEDURE DIVISION mainline, lines 193 to 234 - the counters and the return code
    // =================================================================================================

    @Nested
    @DisplayName("The run summary: any reject makes the run a partial success with return code 4")
    class TheRunSummary {

        @Test
        @DisplayName("a mixed batch of one survivor and one CONSTRUCTED reject reports two processed, "
                + "one posted, one rejected and return code 4")
        void aMixedBatchReportsExactCountsAndReturnCodeFour() {
            arrangePosting(postableAccount());
            cardDoesNotResolve(UNKNOWN_CARD);
            final DailyTransaction rejected = recordOn("T2", "1.00", UNKNOWN_CARD,
                    ORIGINATION_TIMESTAMP, BLANK_TIMESTAMP);
            final List<PostingResult> rejectSink = new ArrayList<>();

            final PostingRunSummary summary =
                    service.postAll(List.of(record("T1", "10.00"), rejected), rejectSink::add);

            assertAll(
                    () -> assertThat(summary.transactionsProcessed()).isEqualTo(2L),
                    () -> assertThat(summary.transactionsPosted()).isEqualTo(1L),
                    () -> assertThat(summary.transactionsRejected()).isEqualTo(1L),
                    () -> assertThat(summary.returnCode())
                            .isEqualTo(TransactionPostingService.RETURN_CODE_REJECTS_PRESENT)
                            .isEqualTo(4),
                    () -> assertThat(summary.partialSuccess()).isTrue(),
                    () -> assertThat(rejectSink).hasSize(1),
                    () -> assertThat(rejectSink.get(0).reasonCode()).isEqualTo(100),
                    () -> assertThat(rejectSink.get(0).reasonDescription())
                            .isEqualTo(DESCRIPTION_100),
                    () -> assertThat(rejectSink.get(0).sourceRecord())
                            .as("the reject writer needs the source record exactly as it arrived")
                            .isSameAs(rejected));
        }

        @Test
        @DisplayName("a clean batch returns the declared success code and hands nothing to the sink")
        void aCleanBatchReturnsTheDeclaredSuccessCode() {
            arrangePosting(postableAccount());
            final List<PostingResult> rejectSink = new ArrayList<>();

            final PostingRunSummary summary =
                    service.postAll(List.of(record("T1", "10.00")), rejectSink::add);

            assertAll(
                    () -> assertThat(summary.returnCode())
                            .isEqualTo(TransactionPostingService.RETURN_CODE_SUCCESS)
                            .isZero(),
                    () -> assertThat(summary.partialSuccess()).isFalse(),
                    () -> assertThat(summary.transactionsProcessed()).isEqualTo(1L),
                    () -> assertThat(summary.transactionsPosted()).isEqualTo(1L),
                    () -> assertThat(summary.transactionsRejected()).isZero(),
                    () -> assertThat(rejectSink).isEmpty());
        }

        @Test
        @DisplayName("a reject is never thrown, so a run carrying one still completes and still posts "
                + "every record that survived")
        void aRejectIsNeverThrown() {
            arrangePosting(postableAccount());
            cardDoesNotResolve(UNKNOWN_CARD);
            final List<PostingResult> rejectSink = new ArrayList<>();

            final PostingRunSummary summary = service.postAll(
                    List.of(recordOn("T0", "1.00", UNKNOWN_CARD, ORIGINATION_TIMESTAMP,
                                    BLANK_TIMESTAMP),
                            record("T1", "10.00")),
                    rejectSink::add);

            assertAll(
                    () -> assertThat(summary.transactionsRejected()).isEqualTo(1L),
                    () -> assertThat(summary.transactionsPosted())
                            .as("the record after the reject is still posted")
                            .isEqualTo(1L),
                    () -> assertThat(summary.returnCode()).isEqualTo(4));
            verify(transactionRepository, times(1)).insertAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName("the counters and the two counter diagnostics agree, and the run announces its "
                + "start and its end")
        void theCountersAndTheirDiagnosticsAgree() {
            arrangePosting(postableAccount());

            service.postAll(List.of(record("T1", "10.00")), result -> {
                // Unreachable on this arrangement; a run still has to be given a sink to open.
            });

            assertThat(loggedMessages())
                    .contains("START OF EXECUTION OF PROGRAM CBTRN02C",
                            "TRANSACTIONS PROCESSED :1",
                            "TRANSACTIONS REJECTED  :0",
                            "END OF EXECUTION OF PROGRAM CBTRN02C");
        }

        @Test
        @DisplayName("a rejected record does not reject the record that follows it, because the reason "
                + "code is reset per record rather than held as state")
        void aRejectDoesNotLeakForwardToTheNextRecord() {
            arrangePosting(postableAccount());
            cardDoesNotResolve(UNKNOWN_CARD);

            assertThat(service.post(recordOn("T1", "1.00", UNKNOWN_CARD, ORIGINATION_TIMESTAMP,
                    BLANK_TIMESTAMP)).reasonCode()).isEqualTo(100);

            final PostingResult second = service.post(record("T2", "10.00"));
            assertAll(
                    () -> assertThat(second.reasonCode()).isZero(),
                    () -> assertThat(second.reasonDescription()).isEmpty(),
                    () -> assertThat(second.posted()).isTrue());
        }

        @Test
        @DisplayName("the declared return codes and the acceptable reason code are 0, 4 and 0")
        void theDeclaredReturnCodesAreZeroAndFour() {
            assertAll(
                    () -> assertThat(TransactionPostingService.RETURN_CODE_SUCCESS).isZero(),
                    () -> assertThat(TransactionPostingService.RETURN_CODE_REJECTS_PRESENT)
                            .isEqualTo(4),
                    () -> assertThat(TransactionPostingService.NO_REJECT_REASON_CODE).isZero(),
                    () -> assertThat(TransactionPostingService.BLANK_FAIL_REASON_DESCRIPTION)
                            .isEmpty(),
                    () -> assertThat(TransactionPostingService.CREATE_FLAG_UNSET).isEqualTo("N"),
                    () -> assertThat(TransactionPostingService.CREATE_FLAG_SET).isEqualTo("Y"),
                    () -> assertThat(TransactionPostingService.PROGRAM_NAME).isEqualTo(PROGRAM));
        }
    }

    // =================================================================================================
    // 2500-WRITE-REJECT-REC, lines 446 to 465 - the 430-byte contract this service supplies content for
    // =================================================================================================

    @Nested
    @DisplayName("The reject record contract: 430 bytes as 350 plus 4 plus 76")
    class TheRejectRecordContract {

        @ParameterizedTest(name = "reason {0} carries its own 76-character description")
        @CsvSource({
            "100, INVALID CARD NUMBER FOUND",
            "101, ACCOUNT RECORD NOT FOUND",
            "102, OVERLIMIT TRANSACTION",
            "103, TRANSACTION RECEIVED AFTER ACCT EXPIRATION",
            "109, ACCOUNT RECORD NOT FOUND"
        })
        @DisplayName("each of the five reason codes carries its own description text, byte for byte")
        void eachReasonCarriesItsOwnDescription(final int reasonCode, final String description) {
            final RejectReason reason = RejectReason.byReasonCode(reasonCode).orElseThrow();

            assertAll(
                    () -> assertThat(reason.getReasonCode()).isEqualTo(reasonCode),
                    () -> assertThat(reason.getDescription()).isEqualTo(description),
                    () -> assertThat(paddedDescription(reasonCode)).startsWith(description),
                    () -> assertThat(encodedWidth(paddedDescription(reasonCode)))
                            .as("the description field is seventy-six bytes wide, untrimmed")
                            .isEqualTo(DESCRIPTION_WIDTH),
                    () -> assertThat(paddedDescription(reasonCode).substring(description.length()))
                            .as("everything past the text is blank, never a null and never a zero")
                            .isBlank());
        }

        @Test
        @DisplayName("all five padded descriptions are exactly seventy-six encoded bytes and none is "
                + "trimmed on comparison")
        void allFivePaddedDescriptionsAreSeventySixBytes() {
            assertAll(
                    () -> assertThat(encodedWidth(PADDED_DESCRIPTION_100))
                            .isEqualTo(DESCRIPTION_WIDTH),
                    () -> assertThat(encodedWidth(PADDED_DESCRIPTION_101))
                            .isEqualTo(DESCRIPTION_WIDTH),
                    () -> assertThat(encodedWidth(PADDED_DESCRIPTION_102))
                            .isEqualTo(DESCRIPTION_WIDTH),
                    () -> assertThat(encodedWidth(PADDED_DESCRIPTION_103))
                            .isEqualTo(DESCRIPTION_WIDTH),
                    () -> assertThat(encodedWidth(PADDED_DESCRIPTION_109))
                            .isEqualTo(DESCRIPTION_WIDTH),
                    () -> assertThat(PADDED_DESCRIPTION_100).isEqualTo(DESCRIPTION_100
                            + " ".repeat(DESCRIPTION_WIDTH - DESCRIPTION_100.length())),
                    () -> assertThat(PADDED_DESCRIPTION_103).isEqualTo(DESCRIPTION_103
                            + " ".repeat(DESCRIPTION_WIDTH - DESCRIPTION_103.length())));
        }

        @Test
        @DisplayName("the five reason images occupy exactly four zero-filled numeric characters")
        void theFiveReasonImagesAreFourZeroFilledCharacters() {
            assertAll(
                    () -> assertThat(encodedWidth(REASON_IMAGE_100)).isEqualTo(REASON_IMAGE_WIDTH),
                    () -> assertThat(encodedWidth(REASON_IMAGE_101)).isEqualTo(REASON_IMAGE_WIDTH),
                    () -> assertThat(encodedWidth(REASON_IMAGE_102)).isEqualTo(REASON_IMAGE_WIDTH),
                    () -> assertThat(encodedWidth(REASON_IMAGE_103)).isEqualTo(REASON_IMAGE_WIDTH),
                    () -> assertThat(encodedWidth(REASON_IMAGE_109)).isEqualTo(REASON_IMAGE_WIDTH),
                    () -> assertThat(REASON_IMAGE_100).isEqualTo("0100").startsWith("0"),
                    () -> assertThat(REASON_IMAGE_109).isEqualTo("0109").startsWith("0"),
                    () -> assertThat(REASON_IMAGE_102).containsOnlyDigits());
        }

        @Test
        @DisplayName("the assembled reject record measures exactly 430 encoded bytes, composed as the "
                + "350-byte source image, then the 4-character reason, then the 76-character description")
        void theAssembledRejectRecordMeasuresFourHundredAndThirtyBytes() {
            final TestDataFactory.DailyTransactionBuilder builder = TestDataFactory.dailyTransaction()
                    .cardNumber(UNKNOWN_CARD)
                    .originalTimestamp(ORIGINATION_TIMESTAMP)
                    .processingTimestamp(BLANK_TIMESTAMP);
            final String sourceImage = builder.image();
            final DailyTransaction source = builder.build();
            cardDoesNotResolve(UNKNOWN_CARD);
            final List<PostingResult> rejectSink = new ArrayList<>();

            service.postAll(List.of(source), rejectSink::add);

            // Assembled here from its three segments rather than by asking the writing step, so the
            // expectation cannot inherit a defect from the code that builds the real record.
            final String expected = sourceImage + REASON_IMAGE_100 + PADDED_DESCRIPTION_100;
            assertAll(
                    () -> assertThat(encodedWidth(sourceImage)).isEqualTo(SOURCE_IMAGE_WIDTH),
                    () -> assertThat(encodedWidth(expected)).isEqualTo(REJECT_RECORD_WIDTH),
                    () -> assertThat(SOURCE_IMAGE_WIDTH + REASON_IMAGE_WIDTH + DESCRIPTION_WIDTH)
                            .isEqualTo(REJECT_RECORD_WIDTH),
                    () -> assertThat(expected.substring(0, SOURCE_IMAGE_WIDTH))
                            .as("the source image is copied verbatim, never re-rendered or re-padded")
                            .isEqualTo(sourceImage),
                    () -> assertThat(expected.substring(SOURCE_IMAGE_WIDTH,
                            SOURCE_IMAGE_WIDTH + REASON_IMAGE_WIDTH)).isEqualTo(REASON_IMAGE_100),
                    () -> assertThat(expected.substring(SOURCE_IMAGE_WIDTH + REASON_IMAGE_WIDTH))
                            .isEqualTo(PADDED_DESCRIPTION_100),
                    () -> assertThat(rejectSink).hasSize(1),
                    () -> assertThat(rejectSink.get(0).sourceRecord()).isSameAs(source),
                    () -> assertThat(rejectSink.get(0).reasonCode()).isEqualTo(100),
                    () -> assertThat(rejectSink.get(0).reasonDescription())
                            .as("the text the trailer is padded from")
                            .isEqualTo(DESCRIPTION_100));
        }

        @Test
        @DisplayName("the widths the service publishes are the legacy contract widths and they sum to "
                + "the declared total")
        void thePublishedWidthsAreTheContractWidths() {
            assertAll(
                    () -> assertThat(TransactionPostingService.SOURCE_IMAGE_LENGTH)
                            .isEqualTo(SOURCE_IMAGE_WIDTH),
                    () -> assertThat(TransactionPostingService.FAIL_REASON_LENGTH)
                            .isEqualTo(REASON_IMAGE_WIDTH),
                    () -> assertThat(TransactionPostingService.FAIL_REASON_DESCRIPTION_LENGTH)
                            .isEqualTo(DESCRIPTION_WIDTH),
                    () -> assertThat(TransactionPostingService.VALIDATION_TRAILER_LENGTH)
                            .isEqualTo(REASON_IMAGE_WIDTH + DESCRIPTION_WIDTH),
                    () -> assertThat(TransactionPostingService.REJECT_RECORD_LENGTH)
                            .isEqualTo(REJECT_RECORD_WIDTH),
                    () -> assertThat(TransactionPostingService.SOURCE_IMAGE_LENGTH
                            + TransactionPostingService.VALIDATION_TRAILER_LENGTH)
                            .isEqualTo(REJECT_RECORD_WIDTH));
        }

        /** The seventy-six character trailer field for a reason code, from this file's own literals. */
        private static String paddedDescription(final int reasonCode) {
            return switch (reasonCode) {
                case 100 -> PADDED_DESCRIPTION_100;
                case 101 -> PADDED_DESCRIPTION_101;
                case 102 -> PADDED_DESCRIPTION_102;
                case 103 -> PADDED_DESCRIPTION_103;
                case 109 -> PADDED_DESCRIPTION_109;
                default -> throw new IllegalArgumentException(
                        "the member declares five reason codes and " + reasonCode + " is not one");
            };
        }
    }

    // =================================================================================================
    // The two-level status model - raw codes 00, 10 and 23 only, coarse level through its owner
    // =================================================================================================

    @Nested
    @DisplayName("The file-status model: two levels, and only three raw literals in the whole estate")
    class TheFileStatusModel {

        @Test
        @DisplayName("the three raw literals the estate compares are 00, 10 and 23, and each carries "
                + "its own coarse meaning")
        void theThreeComparedRawLiteralsCarryTheirOwnMeanings() {
            assertAll(
                    () -> assertThat(FileStatus.SUCCESS.getCode()).isEqualTo(RAW_STATUS_SUCCESS),
                    () -> assertThat(FileStatus.SUCCESS.isSuccess()).isTrue(),
                    () -> assertThat(FileStatus.SUCCESS.isEndOfFile()).isFalse(),
                    () -> assertThat(FileStatus.END_OF_FILE.getCode())
                            .isEqualTo(RAW_STATUS_END_OF_FILE),
                    () -> assertThat(FileStatus.END_OF_FILE.isEndOfFile())
                            .as("end of file is a normal outcome and never an error")
                            .isTrue(),
                    () -> assertThat(FileStatus.END_OF_FILE.isSuccess()).isFalse(),
                    () -> assertThat(FileStatus.RECORD_NOT_FOUND.getCode())
                            .isEqualTo(RAW_STATUS_RECORD_NOT_FOUND),
                    () -> assertThat(FileStatus.RECORD_NOT_FOUND.isSuccess()).isFalse(),
                    () -> assertThat(FileStatus.RECORD_NOT_FOUND.isEndOfFile()).isFalse(),
                    () -> assertThat(FileStatus.fromCode(RAW_STATUS_SUCCESS))
                            .contains(FileStatus.SUCCESS),
                    () -> assertThat(FileStatus.fromCode(RAW_STATUS_END_OF_FILE))
                            .contains(FileStatus.END_OF_FILE),
                    () -> assertThat(FileStatus.fromCode(RAW_STATUS_RECORD_NOT_FOUND))
                            .contains(FileStatus.RECORD_NOT_FOUND),
                    () -> assertThat(FileStatus.PERMANENT_ERROR.getCode())
                            .as("the synthesised status every failure arm here carries")
                            .isEqualTo(RAW_STATUS_PERMANENT_ERROR));
        }

        @Test
        @DisplayName("COARSE LEVEL, SUCCESS ARM, exercised through its owner: a successful record posts "
                + "and the abend path is never reached")
        void theCoarseSuccessArmPostsTheRecord() {
            arrangePosting(postableAccount());

            assertThat(service.post(record("T1", "10.00")).posted()).isTrue();

            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("COARSE LEVEL, RECORD-NOT-FOUND normalises to SUCCESS, exercised through its "
                + "owner: a missing keyed row creates rather than abending")
        void theCoarseRecordNotFoundArmNormalisesToSuccess() {
            final Account account = postableAccount();
            arrangeValidation(account);
            categoryBalanceMissing();
            categoryBalanceInsertEchoes();
            heldReadFinds(account);
            rewriteAffects(1);
            transactionWriteEchoes();

            assertThat(service.post(record("T1", "10.00")).posted()).isTrue();

            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("COARSE LEVEL, END-OF-FILE ARM, exercised through its owner: an exhausted source "
                + "ends the run with the success return code rather than abending")
        void theCoarseEndOfFileArmEndsTheRunNormally() {
            final PostingRunSummary summary = service.postAll(List.of(), result -> {
                // Unreachable: an exhausted source delivers no record to reject.
            });

            assertAll(
                    () -> assertThat(summary.transactionsProcessed()).isZero(),
                    () -> assertThat(summary.transactionsPosted()).isZero(),
                    () -> assertThat(summary.transactionsRejected()).isZero(),
                    () -> assertThat(summary.returnCode())
                            .isEqualTo(TransactionPostingService.RETURN_CODE_SUCCESS));
            verifyNoInteractions(abendService, cardCrossReferenceRepository, accountRepository,
                    categoryBalanceRepository, transactionRepository, recordWriter);
        }

        @Test
        @DisplayName("COARSE LEVEL, ERROR ARM, exercised through its owner: any other status abends, "
                + "which is what the pre-operation sentinel exists to guarantee")
        void theCoarseErrorArmAbends() {
            arrangeValidation(postableAccount());
            when(categoryBalanceRepository.findById(any(TransactionCategoryBalanceId.class)))
                    .thenThrow(new DataAccessResourceFailureException("unreachable"));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.post(record("T1", "10.00")));
        }

        @Test
        @DisplayName("the error-arm carrier refuses both success and end of file by construction, so an "
                + "exhausted read can never be dressed up as a failure")
        void theErrorArmCarrierRefusesSuccessAndEndOfFile() {
            assertAll(
                    () -> assertThat(FileStatusException.CODE_LENGTH).isEqualTo(2),
                    () -> assertThat(FileStatusException.STATUS_SUCCESS)
                            .isEqualTo(RAW_STATUS_SUCCESS),
                    () -> assertThat(FileStatusException.STATUS_END_OF_FILE)
                            .isEqualTo(RAW_STATUS_END_OF_FILE),
                    () -> assertThat(FileStatusException.DISPLAY_PREFIX)
                            .isEqualTo("FILE STATUS IS: NNNN"),
                    () -> assertThatExceptionOfType(IllegalArgumentException.class)
                            .isThrownBy(() -> new FileStatusException(RAW_STATUS_SUCCESS, "READ",
                                    DALYTRAN_DD)),
                    () -> assertThatExceptionOfType(IllegalArgumentException.class)
                            .isThrownBy(() -> new FileStatusException(RAW_STATUS_END_OF_FILE, "READ",
                                    DALYTRAN_DD)),
                    () -> assertThatExceptionOfType(IllegalArgumentException.class)
                            .isThrownBy(() -> new FileStatusException(null, "READ", DALYTRAN_DD)),
                    () -> assertThatExceptionOfType(IllegalArgumentException.class)
                            .as("a status is exactly two characters wide")
                            .isThrownBy(() -> new FileStatusException("3", "READ", DALYTRAN_DD)));
        }

        @Test
        @DisplayName("the error-arm carrier accepts an error status and keeps its two bytes and its "
                + "operation and resource uninterpreted")
        void theErrorArmCarrierAcceptsAnErrorStatus() {
            final FileStatusException carried =
                    new FileStatusException(RAW_STATUS_PERMANENT_ERROR, READ_DALYTRAN_FAILURE,
                            DALYTRAN_DD);

            assertAll(
                    () -> assertThat(carried.code()).isEqualTo(RAW_STATUS_PERMANENT_ERROR),
                    () -> assertThat(encodedWidth(carried.code()))
                            .isEqualTo(FileStatusException.CODE_LENGTH),
                    () -> assertThat(carried.firstByte()).isEqualTo('3'),
                    () -> assertThat(carried.secondByte()).isEqualTo('1'),
                    () -> assertThat(carried.operation()).isEqualTo(READ_DALYTRAN_FAILURE),
                    () -> assertThat(carried.resourceName()).isEqualTo(DALYTRAN_DD),
                    () -> assertThat(carried).isInstanceOf(RuntimeException.class));
        }
    }

    // =================================================================================================
    // 9910-DISPLAY-IO-STATUS then 9999-ABEND-PROGRAM, lines 707 to 727 - emit first, abend second
    // =================================================================================================

    @Nested
    @DisplayName("The abend path: the diagnostic is emitted BEFORE the abend is raised")
    class TheAbendPath {

        @Test
        @DisplayName("at the instant the abend collaborator is reached the log ALREADY holds the raw "
                + "status line and the abend banner, in that order")
        void theDiagnosticIsAlreadyLoggedWhenTheAbendIsRaised() {
            arrangeValidation(postableAccount());
            when(categoryBalanceRepository.findById(any(TransactionCategoryBalanceId.class)))
                    .thenThrow(new DataAccessResourceFailureException("unreachable"));
            snapshotDiagnosticsWhenAbendIsRaised();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.post(record("T1", "10.00")));

            assertAll(
                    () -> assertThat(diagnosticsAtRaise)
                            .as("captured at the raise, so nothing logged afterwards can satisfy it")
                            .contains(PERMANENT_ERROR_DISPLAY_LINE),
                    () -> assertThat(diagnosticsAtRaise)
                            .anyMatch(message -> message.startsWith(ABENDING_BANNER)),
                    () -> assertThat(diagnosticsAtRaise.indexOf(PERMANENT_ERROR_DISPLAY_LINE))
                            .as("the raw status precedes the banner, as every failure arm orders them")
                            .isLessThan(indexOfBanner(diagnosticsAtRaise)),
                    () -> assertThat(diagnosticsAtRaise)
                            .anyMatch(message -> message.contains("fileStatus="
                                    + RAW_STATUS_PERMANENT_ERROR)));
        }

        @Test
        @DisplayName("the raw status is displayed before the abend collaborator is asked to abort, "
                + "which a handler-based equivalent could not guarantee")
        void theStatusDisplayPrecedesTheAbortRequest() {
            arrangeValidation(postableAccount());
            when(categoryBalanceRepository.findById(any(TransactionCategoryBalanceId.class)))
                    .thenThrow(new DataAccessResourceFailureException("unreachable"));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.post(record("T1", "10.00")));

            final InOrder ordered = inOrder(abendService);
            ordered.verify(abendService).displayIoStatus(RAW_STATUS_PERMANENT_ERROR,
                    READ_TCATBALF_FAILURE, TCATBALF_DD);
            ordered.verify(abendService).abendOnFileStatus(eq(PROGRAM),
                    any(FileStatusException.class));
            ordered.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("the abend carries the program name as its culprit, the batch abend code, the "
                + "failing arm's own literal as its reason and the default operator message")
        void theAbendCarriesTheProgramNameAsItsCulprit() {
            arrangeThroughAccount(postableAccount());
            when(transactionRepository.insertAndFlush(any(Transaction.class)))
                    .thenThrow(new DataAccessResourceFailureException("unreachable"));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.post(record("T1", "10.00")))
                    .satisfies(abend -> assertAll(
                            () -> assertThat(abend.culprit())
                                    .as("the culprit is the program name, not a class name")
                                    .isEqualTo(PROGRAM),
                            () -> assertThat(encodedWidth(abend.culprit()))
                                    .isLessThanOrEqualTo(AbendException.CULPRIT_LENGTH),
                            () -> assertThat(abend.code())
                                    .isEqualTo(AbendException.BATCH_ABEND_CODE)
                                    .isEqualTo("999"),
                            () -> assertThat(abend.code())
                                    .as("never the online abend code")
                                    .isNotEqualTo(AbendException.ONLINE_ABEND_CODE),
                            () -> assertThat(abend.reason()).isEqualTo(WRITE_TRANFILE_FAILURE),
                            () -> assertThat(abend.getMessage())
                                    .isEqualTo(AbendException.DEFAULT_MESSAGE)
                                    .isEqualTo("UNEXPECTED ABEND OCCURRED."),
                            () -> assertThat(encodedWidth(abend.toFixedWidthContext()))
                                    .isEqualTo(AbendException.CONTEXT_LENGTH)));
        }

        @Test
        @DisplayName("the status handed to the abend collaborator carries the raw two-byte code, the "
                + "failing arm's literal and the DD name of the resource that failed")
        void theCarriedStatusNamesTheResourceAndTheOperation() {
            arrangeThroughAccount(postableAccount());
            when(transactionRepository.insertAndFlush(any(Transaction.class)))
                    .thenThrow(new DataAccessResourceFailureException("unreachable"));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.post(record("T1", "10.00")));

            final FileStatusException carried = captureCarriedFileStatus();
            assertAll(
                    () -> assertThat(carried.code()).isEqualTo(RAW_STATUS_PERMANENT_ERROR),
                    () -> assertThat(carried.code())
                            .as("never success and never end of file")
                            .isNotEqualTo(RAW_STATUS_SUCCESS)
                            .isNotEqualTo(RAW_STATUS_END_OF_FILE),
                    () -> assertThat(carried.operation()).isEqualTo(WRITE_TRANFILE_FAILURE),
                    () -> assertThat(carried.resourceName()).isEqualTo(TRANFILE_DD),
                    () -> assertThat(carried.getCause())
                            .isInstanceOf(DataAccessResourceFailureException.class));
        }

        @Test
        @DisplayName("a failed category-balance read abends with the read literal and before the "
                + "transaction is written")
        void aFailedCategoryBalanceReadAbendsBeforeTheTransactionIsWritten() {
            arrangeValidation(postableAccount());
            when(categoryBalanceRepository.findById(any(TransactionCategoryBalanceId.class)))
                    .thenThrow(new DataAccessResourceFailureException("unreachable"));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.post(record("T1", "10.00")))
                    .satisfies(abend -> assertThat(abend.reason())
                            .isEqualTo(READ_TCATBALF_FAILURE));

            assertThat(captureCarriedFileStatus().resourceName()).isEqualTo(TCATBALF_DD);
            verify(transactionRepository, never()).insertAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName("a failed category-balance create abends with the write literal")
        void aFailedCategoryBalanceCreateAbendsWithTheWriteLiteral() {
            arrangeValidation(postableAccount());
            categoryBalanceMissing();
            when(recordWriter.insert(any(TransactionCategoryBalance.class)))
                    .thenThrow(new DataAccessResourceFailureException("unreachable"));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.post(record("T1", "10.00")))
                    .satisfies(abend -> assertThat(abend.reason())
                            .isEqualTo(WRITE_TCATBALF_FAILURE));

            assertThat(captureCarriedFileStatus().resourceName()).isEqualTo(TCATBALF_DD);
        }

        @Test
        @DisplayName("a failed category-balance rewrite abends with the rewrite literal, which is a "
                + "different literal from the create")
        void aFailedCategoryBalanceRewriteAbendsWithTheRewriteLiteral() {
            arrangeValidation(postableAccount());
            categoryBalanceFound("0.00");
            when(categoryBalanceRepository.saveAndFlush(any(TransactionCategoryBalance.class)))
                    .thenThrow(new DataAccessResourceFailureException("unreachable"));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.post(record("T1", "10.00")))
                    .satisfies(abend -> assertAll(
                            () -> assertThat(abend.reason()).isEqualTo(REWRITE_TCATBALF_FAILURE),
                            () -> assertThat(abend.reason()).isNotEqualTo(WRITE_TCATBALF_FAILURE)));
        }

        /** The index of the abend banner in a captured diagnostic list. */
        private static int indexOfBanner(final List<String> messages) {
            for (int index = 0; index < messages.size(); index++) {
                if (messages.get(index).startsWith(ABENDING_BANNER)) {
                    return index;
                }
            }
            throw new IllegalStateException("the abend banner was never emitted");
        }
    }

    // =================================================================================================
    // 9910-DISPLAY-IO-STATUS, lines 714 to 727 - two arms, four characters either way
    // =================================================================================================

    @Nested
    @DisplayName("The status display renders four characters through either of its two arms")
    class TheStatusDisplay {

        @ParameterizedTest(name = "status {0} renders as {1}")
        @CsvSource({
            "00, 0000",
            "10, 0010",
            "23, 0023",
            "31, 0031",
            "90, 9048",
            "91, 9049",
            "0A, 0065"
        })
        @DisplayName("a numeric status takes the standard arm and a first byte of 9 or a non-numeric "
                + "byte takes the other, which renders the second byte's binary value as three digits")
        void bothArmsRenderFourCharacters(final String rawStatus, final String expectedImage) {
            assertAll(
                    () -> assertThat(TransactionPostingService.ioStatusImage(rawStatus))
                            .isEqualTo(expectedImage),
                    () -> assertThat(encodedWidth(TransactionPostingService.ioStatusImage(rawStatus)))
                            .isEqualTo(REASON_IMAGE_WIDTH));
        }

        @Test
        @DisplayName("an absent or short status is widened to its two-byte field before its bytes are "
                + "read, so neither an absent value nor an index overrun escapes")
        void anAbsentOrShortStatusIsWidenedFirst() {
            assertAll(
                    () -> assertThat(TransactionPostingService.ioStatusImage(null))
                            .as("two blanks render through the non-numeric arm")
                            .isEqualTo(" 032"),
                    () -> assertThat(TransactionPostingService.ioStatusImage("")).isEqualTo(" 032"),
                    () -> assertThat(TransactionPostingService.ioStatusImage("3")).isEqualTo("3032"),
                    () -> assertThat(encodedWidth(TransactionPostingService.ioStatusImage(null)))
                            .isEqualTo(REASON_IMAGE_WIDTH));
        }

        @Test
        @DisplayName("the display line the failure arms emit is the shared prefix followed by the "
                + "rendered four characters")
        void theDisplayLineIsThePrefixFollowedByTheImage() {
            assertThat(FileStatusException.DISPLAY_PREFIX
                    + TransactionPostingService.ioStatusImage(RAW_STATUS_PERMANENT_ERROR))
                    .isEqualTo(PERMANENT_ERROR_DISPLAY_LINE);
        }
    }

    // =================================================================================================
    // 1000-DALYTRAN-GET-NEXT, lines 345 to 369 - three arms, and the middle one is not an error
    // =================================================================================================

    @Nested
    @DisplayName("The read paragraph: end of file is its own arm, distinct from its error arm")
    class TheReadParagraph {

        @Test
        @DisplayName("an exhausted source ends the run rather than abending, which is the arm a "
                + "translation folding end of file into the error arm would break on every run")
        void anExhaustedSourceEndsTheRun() {
            final PostingRunSummary summary = service.postAll(List.of(), result -> {
                // Unreachable: an exhausted source delivers no record to reject.
            });

            assertThat(summary.transactionsProcessed()).isZero();
            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("a cursor that announces a record and then delivers nothing takes the ERROR arm, "
                + "because the legacy read either filled its record area or reported a status")
        void aCursorDeliveringNothingTakesTheErrorArm() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.postAll(cursorDeliveringNothing(), result -> {
                        // Unreachable: the run fails on the read, before any verdict.
                    }))
                    .satisfies(abend -> assertThat(abend.reason())
                            .isEqualTo(READ_DALYTRAN_FAILURE));

            final FileStatusException carried = captureCarriedFileStatus();
            assertAll(
                    () -> assertThat(carried.resourceName()).isEqualTo(DALYTRAN_DD),
                    () -> assertThat(carried.code())
                            .as("never the end-of-file status, which is not an error")
                            .isEqualTo(RAW_STATUS_PERMANENT_ERROR)
                            .isNotEqualTo(RAW_STATUS_END_OF_FILE));
        }

        @Test
        @DisplayName("a cursor that fails while delivering takes the ERROR arm with the read literal, "
                + "never the end-of-file arm")
        void aFailingCursorTakesTheErrorArm() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.postAll(failingCursor(), result -> {
                        // Unreachable: the run fails on the read, before any verdict.
                    }))
                    .satisfies(abend -> assertThat(abend.reason())
                            .isEqualTo(READ_DALYTRAN_FAILURE));

            assertThat(captureCarriedFileStatus().code())
                    .isNotEqualTo(RAW_STATUS_END_OF_FILE)
                    .isNotEqualTo(RAW_STATUS_SUCCESS);
        }

        /** A source whose cursor announces one record and then hands over nothing. */
        private static Iterable<DailyTransaction> cursorDeliveringNothing() {
            return () -> new Iterator<>() {

                private boolean delivered;

                @Override
                public boolean hasNext() {
                    return !this.delivered;
                }

                @Override
                public DailyTransaction next() {
                    this.delivered = true;
                    return null;
                }
            };
        }

        /** A source whose cursor fails while delivering a record. */
        private static Iterable<DailyTransaction> failingCursor() {
            return () -> new Iterator<>() {

                @Override
                public boolean hasNext() {
                    return true;
                }

                @Override
                public DailyTransaction next() {
                    throw new DataAccessResourceFailureException("unreachable");
                }
            };
        }
    }

    // =================================================================================================
    // The twelve acquisition and release paragraphs, lines 236 to 343 and 582 to 690
    // =================================================================================================

    @Nested
    @DisplayName("The six opens and six closes: each announces its own resource and its own mode")
    class TheSixOpensAndSixCloses {

        @Test
        @DisplayName("a run announces all six acquisitions with the mode each paragraph opens in, and "
                + "all six releases, so every one of the twelve paragraphs is observable")
        void allSixOpensAndAllSixClosesAreAnnounced() {
            arrangePosting(postableAccount());

            service.postAll(List.of(record("T1", "10.00")), result -> {
                // Unreachable on this arrangement; a run still has to be given a sink to open.
            });

            assertThat(loggedMessages()).contains(
                    "CBTRN02C acquired resource=DALYTRAN mode=OPEN INPUT",
                    "CBTRN02C acquired resource=TRANFILE mode=OPEN OUTPUT",
                    "CBTRN02C acquired resource=XREFFILE mode=OPEN INPUT",
                    "CBTRN02C acquired resource=DALYREJS mode=OPEN OUTPUT",
                    "CBTRN02C acquired resource=ACCTFILE mode=OPEN I-O",
                    "CBTRN02C acquired resource=TCATBALF mode=OPEN I-O",
                    "CBTRN02C released resource=DALYTRAN mode=CLOSE",
                    "CBTRN02C released resource=TRANFILE mode=CLOSE",
                    "CBTRN02C released resource=XREFFILE mode=CLOSE",
                    "CBTRN02C released resource=DALYREJS mode=CLOSE",
                    "CBTRN02C released resource=ACCTFILE mode=CLOSE",
                    "CBTRN02C released resource=TCATBALF mode=CLOSE");
        }

        @Test
        @DisplayName("an absent source takes the daily-transaction open failure arm with that "
                + "paragraph's own literal")
        void anAbsentSourceTakesTheDailyTransactionOpenFailureArm() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.postAll(null, result -> {
                        // Unreachable: the run fails before a record can be read.
                    }))
                    .satisfies(abend -> assertThat(abend.reason())
                            .isEqualTo(OPEN_DALYTRAN_FAILURE));

            assertThat(captureCarriedFileStatus().resourceName()).isEqualTo(DALYTRAN_DD);
        }

        @Test
        @DisplayName("an absent reject sink takes the reject-file open failure arm BEFORE any record is "
                + "read, because a run that cannot record its rejects never starts")
        void anAbsentRejectSinkFailsBeforeAnyRecordIsRead() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.postAll(List.of(record("T1", "10.00")), null))
                    .satisfies(abend -> assertThat(abend.reason())
                            .isEqualTo(OPEN_DALYREJS_FAILURE));

            assertThat(captureCarriedFileStatus().resourceName()).isEqualTo(DALYREJS_DD);
            verifyNoInteractions(cardCrossReferenceRepository, accountRepository,
                    categoryBalanceRepository, transactionRepository, recordWriter);
        }

        @Test
        @DisplayName("the six DD names the paragraphs open are the legacy ones")
        void theSixDdNamesAreTheLegacyOnes() {
            assertAll(
                    () -> assertThat(TransactionPostingService.DALYTRAN_DD).isEqualTo(DALYTRAN_DD),
                    () -> assertThat(TransactionPostingService.TRANFILE_DD).isEqualTo(TRANFILE_DD),
                    () -> assertThat(TransactionPostingService.XREFFILE_DD).isEqualTo(XREFFILE_DD),
                    () -> assertThat(TransactionPostingService.DALYREJS_DD).isEqualTo(DALYREJS_DD),
                    () -> assertThat(TransactionPostingService.ACCTFILE_DD).isEqualTo(ACCTFILE_DD),
                    () -> assertThat(TransactionPostingService.TCATBALF_DD).isEqualTo(TCATBALF_DD));
        }
    }

    // =================================================================================================
    // Absent, blank and boundary input
    // =================================================================================================

    @Nested
    @DisplayName("Absent, blank and boundary input each behave as the class declares")
    class TheBoundaryInputs {

        @Test
        @DisplayName("an absent record is refused at the per-record entry point")
        void anAbsentRecordIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.post(null));

            verifyNoInteractions(cardCrossReferenceRepository, accountRepository,
                    categoryBalanceRepository, transactionRepository, recordWriter, abendService);
        }

        @Test
        @DisplayName("a blank card number resolves nothing and is 100, with no absent-value failure")
        void aBlankCardNumberIsRejectedAsAnInvalidCard() {
            cardDoesNotResolve("");

            final PostingResult result =
                    service.post(recordOn("T1", "10.00", "", ORIGINATION_TIMESTAMP,
                            BLANK_TIMESTAMP));

            assertAll(
                    () -> assertThat(result.reasonCode()).isEqualTo(100),
                    () -> assertThat(result.rejectReason())
                            .isEqualTo(RejectReason.INVALID_CARD_NUMBER));
            verify(accountRepository, never()).findById(any());
        }

        @Test
        @DisplayName("an absent card number resolves nothing and is 100 as well, so the keyed read "
                + "carries no absent-value assumption")
        void anAbsentCardNumberIsRejectedAsAnInvalidCard() {
            // Deliberately not stubbed: the keyed read is asked for an absent key and answers with its
            // not-found value, which is the arm reason 100 comes from.
            final PostingResult result =
                    service.post(recordOn("T1", "10.00", null, ORIGINATION_TIMESTAMP,
                            BLANK_TIMESTAMP));

            assertThat(result.reasonCode()).isEqualTo(100);
            verify(accountRepository, never()).findById(any());
        }

        @Test
        @DisplayName("a cross-reference carrying an absent account identifier resolves no account and "
                + "is 101, not a failure")
        void anAbsentAccountIdentifierOnTheCrossReferenceIs101() {
            when(cardCrossReferenceRepository.findById(CARD))
                    .thenReturn(Optional.of(new CardCrossReference(CARD, CUSTOMER_ID, null)));

            final PostingResult result = service.post(record("T1", "10.00"));

            assertAll(
                    () -> assertThat(result.reasonCode()).isEqualTo(101),
                    () -> assertThat(result.rejectReason())
                            .isEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_READ));
        }

        @Test
        @DisplayName("an absent amount reaches no verdict: the legacy field is two decimals wide and "
                + "cannot be absent, so the translation carries no arm for it and the failure surfaces")
        void anAbsentAmountSurfacesRatherThanBeingGuessedAt() {
            arrangeValidation(postableAccount());

            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> service.post(recordOn("T1", null, CARD, ORIGINATION_TIMESTAMP,
                            BLANK_TIMESTAMP)));

            verifyNoInteractions(categoryBalanceRepository, transactionRepository, recordWriter);
        }

        @Test
        @DisplayName("a zero amount posts, joining the credit total, with no rejection")
        void aZeroAmountPosts() {
            arrangePosting(postableAccount());

            final PostingResult result = service.post(record("T1", "0.00"));

            assertAll(
                    () -> assertThat(result.posted()).isTrue(),
                    () -> assertThat(result.reasonCode()).isZero(),
                    () -> assertThat(capturePostedTransaction().getTranAmt())
                            .isEqualTo(new BigDecimal("0.00")));
        }

        @Test
        @DisplayName("an absent origination timestamp becomes ten blanks rather than failing, and any "
                + "populated expiry then sorts above it")
        void anAbsentOriginationTimestampBecomesBlanks() {
            arrangePosting(postableAccount());

            final PostingResult result =
                    service.post(recordOn("T1", "10.00", CARD, null, BLANK_TIMESTAMP));

            assertAll(
                    () -> assertThat(result.reasonCode())
                            .as("blanks sort below any date, so the expiry is the later")
                            .isZero(),
                    () -> assertThat(result.posted()).isTrue(),
                    () -> assertThat(capturePostedTransaction().getTranOrigTs())
                            .as("the absent value is copied as it arrived, not substituted")
                            .isNull());
        }

        @Test
        @DisplayName("an absent expiration date becomes ten blanks rather than failing, and any "
                + "populated origination date then sorts above it, which is 103")
        void anAbsentExpirationDateBecomesBlanks() {
            arrangeValidation(accountWithoutExpiry());

            final PostingResult result = service.post(record("T1", "10.00"));

            assertAll(
                    () -> assertThat(result.reasonCode()).isEqualTo(103),
                    () -> assertThat(result.rejectReason())
                            .isEqualTo(RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION));
        }

        @Test
        @DisplayName("a run over a source with no records touches no repository and returns success")
        void aRunOverAnEmptySourceTouchesNothing() {
            final PostingRunSummary summary = service.postAll(List.of(), result -> {
                // Unreachable: an empty source delivers no record to reject.
            });

            assertThat(summary.returnCode())
                    .isEqualTo(TransactionPostingService.RETURN_CODE_SUCCESS);
            verifyNoInteractions(cardCrossReferenceRepository, accountRepository,
                    categoryBalanceRepository, transactionRepository, recordWriter, abendService,
                    dailyTransactionRepository);
        }
    }

    // =================================================================================================
    // Lines 440 to 442 - one unit of work per I/O act, never one per record and never one per run
    // =================================================================================================

    @Nested
    @DisplayName("Every I/O act obtains its own unit of work, so a late failure cannot undo an early "
            + "store")
    class ThePerActUnitsOfWork {

        /**
         * The five acts of a posted record: the cross-reference read and the account read of validation,
         * then the three stores of lines 440 to 442. Each takes a unit of its own, which is what makes a
         * completed store survive the failure of a store after it.
         */
        private static final int ACTS_PER_POSTED_RECORD = 5;

        /** The two keyed reads a rejected record still performs before its verdict. */
        private static final int ACTS_PER_REJECTED_RECORD = 2;

        @Test
        @DisplayName("a posted record opens one unit per act - the two keyed reads and the three stores - "
                + "rather than one unit around the record")
        void aPostedRecordOpensOneUnitPerAct() {
            final PostingStageTransactionBoundary observed =
                    Mockito.spy(new PostingStageTransactionBoundary());
            final TransactionPostingService observedService = serviceWith(observed);
            arrangePosting(postableAccount());

            assertThat(observedService.post(record("T1", "10.00")).posted()).isTrue();

            verify(observed, times(ACTS_PER_POSTED_RECORD)).execute(any());
            verifyNoMoreInteractions(observed);
        }

        @Test
        @DisplayName("a record rejected by validation opens only the units its reads needed, because no "
                + "store ran")
        void aRejectedRecordOpensOnlyItsReadUnits() {
            final PostingStageTransactionBoundary observed =
                    Mockito.spy(new PostingStageTransactionBoundary());
            final TransactionPostingService observedService = serviceWith(observed);
            arrangeValidation(accountWith("0.00", "10.00", "0.00", "0.00", LATE_EXPIRY));

            assertThat(observedService.post(record("T1", "500.00")).rejected()).isTrue();

            verify(observed, times(ACTS_PER_REJECTED_RECORD)).execute(any());
            verifyNoMoreInteractions(observed);
        }

        @Test
        @DisplayName("the mainline loop opens units per record and never one for the run - the property a "
                + "transactional method on the service itself could not have had, because its own call "
                + "bypasses a proxy")
        void theMainlineLoopOpensUnitsPerRecordAndNoneForTheRun() {
            final PostingStageTransactionBoundary observed =
                    Mockito.spy(new PostingStageTransactionBoundary());
            final TransactionPostingService observedService = serviceWith(observed);
            arrangePosting(postableAccount());

            final PostingRunSummary summary = observedService.postAll(
                    List.of(record("T1", "10.00"), record("T2", "20.00"), record("T3", "30.00")),
                    result -> {
                        // Unreachable on this arrangement; a run still has to be given a sink.
                    });

            assertThat(summary.transactionsProcessed()).isEqualTo(3L);
            verify(observed, times(3 * ACTS_PER_POSTED_RECORD)).execute(any());
            verifyNoMoreInteractions(observed);
        }

        @Test
        @DisplayName("the LAST store of lines 440 to 442 runs in a unit of its own, so the two stores "
                + "before it have already happened when it fails and nothing compensates them")
        void aFailingLastStoreLeavesTheTwoEarlierStoresDone() {
            final PostingStageTransactionBoundary observed =
                    Mockito.spy(new PostingStageTransactionBoundary());
            final TransactionPostingService observedService = serviceWith(observed);
            arrangeThroughAccount(postableAccount());
            when(transactionRepository.insertAndFlush(any(Transaction.class)))
                    .thenThrow(new DataAccessResourceFailureException("transaction file refused"));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> observedService.post(record("T1", "10.00")));

            assertAll(
                    () -> verify(categoryBalanceRepository, times(1))
                            .saveAndFlush(any(TransactionCategoryBalance.class)),
                    () -> verify(accountRepository, times(1)).rewritePostingBalances(eq(ACCOUNT_ID),
                            ArgumentMatchers.anyLong(), any(BigDecimal.class), any(BigDecimal.class),
                            any(BigDecimal.class)),
                    () -> verify(categoryBalanceRepository, never())
                            .delete(any(TransactionCategoryBalance.class)),
                    () -> verify(observed, times(ACTS_PER_POSTED_RECORD)).execute(any()));
        }
    }

    // =================================================================================================
    // The staged driver - the convenience entry point over the landing table
    // =================================================================================================

    @Nested
    @DisplayName("The staged driver reads the landing table in ascending business-key order")
    class TheStagedDriver {

        @Test
        @DisplayName("it posts what the landing table hands it and stops when a page comes back empty")
        void itPostsWhatTheLandingTableHandsIt() {
            arrangePosting(postableAccount());
            when(dailyTransactionRepository.findByDalytranIdGreaterThanOrderByDalytranIdAsc(
                    ArgumentMatchers.anyString(), any(Limit.class)))
                    .thenReturn(List.of(record("T1", "10.00")))
                    .thenReturn(List.of());

            final PostingRunSummary summary = service.postStagedDailyTransactions(result -> {
                // Unreachable on this arrangement; a run still has to be given a sink.
            });

            assertAll(
                    () -> assertThat(summary.transactionsProcessed()).isEqualTo(1L),
                    () -> assertThat(summary.transactionsPosted()).isEqualTo(1L),
                    () -> assertThat(summary.returnCode())
                            .isEqualTo(TransactionPostingService.RETURN_CODE_SUCCESS));
            verify(transactionRepository, times(1)).insertAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName("it asks the ascending-key finder for rows after an empty opening cursor, within a "
                + "bounded page whose size is an iteration detail this test deliberately does not pin")
        void itAsksTheAscendingKeyFinderFromAnEmptyOpeningCursor() {
            when(dailyTransactionRepository.findByDalytranIdGreaterThanOrderByDalytranIdAsc(
                    ArgumentMatchers.anyString(), any(Limit.class))).thenReturn(List.of());

            service.postStagedDailyTransactions(result -> {
                // Unreachable: the landing table hands over nothing.
            });

            final ArgumentCaptor<String> cursor = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<Limit> page = ArgumentCaptor.forClass(Limit.class);
            verify(dailyTransactionRepository)
                    .findByDalytranIdGreaterThanOrderByDalytranIdAsc(cursor.capture(),
                            page.capture());
            assertAll(
                    () -> assertThat(cursor.getValue())
                            .as("the opening cursor is exclusive and empty, so no row is skipped")
                            .isEmpty(),
                    () -> assertThat(page.getValue().isLimited()).isTrue(),
                    () -> assertThat(page.getValue().max()).isPositive());
        }

        @Test
        @DisplayName("a landing table that cannot be read abends with the read literal, never as end "
                + "of file")
        void aLandingTableThatCannotBeReadAbends() {
            when(dailyTransactionRepository.findByDalytranIdGreaterThanOrderByDalytranIdAsc(
                    ArgumentMatchers.anyString(), any(Limit.class)))
                    .thenThrow(new DataAccessResourceFailureException("unreachable"));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.postStagedDailyTransactions(result -> {
                        // Unreachable: the run fails on the read.
                    }))
                    .satisfies(abend -> assertThat(abend.reason())
                            .isEqualTo(READ_DALYTRAN_FAILURE));

            final FileStatusException carried = captureCarriedFileStatus();
            assertAll(
                    () -> assertThat(carried.resourceName()).isEqualTo(DALYTRAN_DD),
                    () -> assertThat(carried.code()).isNotEqualTo(RAW_STATUS_END_OF_FILE));
        }
    }

    // =================================================================================================
    // The two result types - the invariants the mainline guarantees
    // =================================================================================================

    @Nested
    @DisplayName("The result types enforce the invariants the mainline guarantees")
    class TheResultInvariants {

        @Test
        @DisplayName("a rejected result carrying no typed reason is refused, because a reject record "
                + "cannot be written without the reason that produced it")
        void aRejectedResultWithoutAReasonIsRefused() {
            final DailyTransaction source = record("T1", "10.00");

            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> new PostingResult(100, DESCRIPTION_100, null, false, source, null, null,
                            null));
        }

        @Test
        @DisplayName("a result carrying no source record is refused, and neither is an absent "
                + "description accepted")
        void aResultWithoutASourceRecordIsRefused() {
            final DailyTransaction source = record("T1", "10.00");

            assertAll(
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new PostingResult(0, "", null, true, null, null, null, null)),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new PostingResult(0, null, null, true, source, null, null, null)));
        }

        @Test
        @DisplayName("a posted result reports itself not rejected, and a rejected one the exact "
                + "complement, so nothing has to infer one from the reason code")
        void postedAndRejectedAreExactComplements() {
            final DailyTransaction source = record("T1", "10.00");
            final PostingResult posted =
                    new PostingResult(0, "", null, true, source, null, null, null);
            final PostingResult rejected = new PostingResult(100, DESCRIPTION_100,
                    RejectReason.INVALID_CARD_NUMBER, false, source, null, null, null);

            assertAll(
                    () -> assertThat(posted.rejected()).isFalse(),
                    () -> assertThat(posted.posted()).isTrue(),
                    () -> assertThat(rejected.rejected()).isTrue(),
                    () -> assertThat(rejected.posted()).isFalse(),
                    () -> assertThat(rejected.sourceRecord()).isSameAs(source));
        }

        @Test
        @DisplayName("counters that do not sum, a negative counter, and a return code contradicting the "
                + "reject count are each refused")
        void inconsistentCountersAreRefused() {
            assertAll(
                    () -> assertThatExceptionOfType(IllegalArgumentException.class)
                            .isThrownBy(() -> new PostingRunSummary(5L, 1L, 1L, 4)),
                    () -> assertThatExceptionOfType(IllegalArgumentException.class)
                            .isThrownBy(() -> new PostingRunSummary(2L, 1L, 1L, 0)),
                    () -> assertThatExceptionOfType(IllegalArgumentException.class)
                            .isThrownBy(() -> new PostingRunSummary(1L, 1L, 0L, 4)),
                    () -> assertThatExceptionOfType(IllegalArgumentException.class)
                            .isThrownBy(() -> new PostingRunSummary(-1L, 0L, 0L, 0)),
                    () -> assertThatExceptionOfType(IllegalArgumentException.class)
                            .isThrownBy(() -> new PostingRunSummary(0L, -1L, 0L, 0)),
                    () -> assertThatExceptionOfType(IllegalArgumentException.class)
                            .isThrownBy(() -> new PostingRunSummary(0L, 0L, -1L, 0)));
        }

        @Test
        @DisplayName("a consistent summary is accepted and reports its own partial success")
        void aConsistentSummaryReportsItsOwnPartialSuccess() {
            assertAll(
                    () -> assertThat(new PostingRunSummary(3L, 2L, 1L, 4).partialSuccess()).isTrue(),
                    () -> assertThat(new PostingRunSummary(3L, 3L, 0L, 0).partialSuccess()).isFalse(),
                    () -> assertThat(new PostingRunSummary(0L, 0L, 0L, 0).transactionsProcessed())
                            .isZero());
        }
    }

    // =================================================================================================
    // Construction - nine mandatory collaborators
    // =================================================================================================

    @Nested
    @DisplayName("Construction refuses an absent collaborator, all nine of them")
    class TheConstructionGuards {

        @Test
        @DisplayName("each of the nine constructor arguments is mandatory, the per-act boundary "
                + "included, because without it no I/O act of the cascade would have a unit at all")
        void eachOfTheNineArgumentsIsMandatory() {
            final Clock clock = pinnedClock();

            assertAll(
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new TransactionPostingService(null, transactionRepository,
                                    accountRepository, cardCrossReferenceRepository,
                                    categoryBalanceRepository, recordWriter, abendService,
                                    transactionBoundary, clock)),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new TransactionPostingService(dailyTransactionRepository, null,
                                    accountRepository, cardCrossReferenceRepository,
                                    categoryBalanceRepository, recordWriter, abendService,
                                    transactionBoundary, clock)),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new TransactionPostingService(dailyTransactionRepository,
                                    transactionRepository, null, cardCrossReferenceRepository,
                                    categoryBalanceRepository, recordWriter, abendService,
                                    transactionBoundary, clock)),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new TransactionPostingService(dailyTransactionRepository,
                                    transactionRepository, accountRepository, null,
                                    categoryBalanceRepository, recordWriter, abendService,
                                    transactionBoundary, clock)),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new TransactionPostingService(dailyTransactionRepository,
                                    transactionRepository, accountRepository,
                                    cardCrossReferenceRepository, null, recordWriter, abendService,
                                    transactionBoundary, clock)),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new TransactionPostingService(dailyTransactionRepository,
                                    transactionRepository, accountRepository,
                                    cardCrossReferenceRepository, categoryBalanceRepository, null,
                                    abendService, transactionBoundary, clock)),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new TransactionPostingService(dailyTransactionRepository,
                                    transactionRepository, accountRepository,
                                    cardCrossReferenceRepository, categoryBalanceRepository,
                                    recordWriter, null, transactionBoundary, clock)),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new TransactionPostingService(dailyTransactionRepository,
                                    transactionRepository, accountRepository,
                                    cardCrossReferenceRepository, categoryBalanceRepository,
                                    recordWriter, abendService, null, clock)),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> new TransactionPostingService(dailyTransactionRepository,
                                    transactionRepository, accountRepository,
                                    cardCrossReferenceRepository, categoryBalanceRepository,
                                    recordWriter, abendService, transactionBoundary, null)));
        }
    }

    // =================================================================================================
    // Diagnostics - what the 217 legacy console writes became, and what they must never carry
    // =================================================================================================

    @Nested
    @DisplayName("Diagnostics carry correlation tokens and never record content")
    class TheDiagnostics {

        @Test
        @DisplayName("both rejected-record diagnostics name the program, a correlation token and the "
                + "reason, and publish no identifier, no card number and no amount")
        void rejectedRecordDiagnosticsCarryNoRecordContent() {
            final String transactionId = "2022071900000001";
            cardDoesNotResolve(UNKNOWN_CARD);

            service.postAll(List.of(recordOn(transactionId, "765.43", UNKNOWN_CARD,
                    ORIGINATION_TIMESTAMP, BLANK_TIMESTAMP)), result -> {
                        // The sink records nothing: this test observes only the service diagnostics.
                    });

            assertThat(loggedMessages())
                    .anyMatch(message -> message.matches("record rejected program=CBTRN02C "
                            + "transactionRef=\\[REDACTED] ref=[0-9a-f]{24} "
                            + "reasonCode=100 reason=INVALID CARD NUMBER FOUND"))
                    .anyMatch(message -> message.matches("reject record queued program=CBTRN02C "
                            + "transactionRef=\\[REDACTED] ref=[0-9a-f]{24} "
                            + "reasonCode=100 reason=INVALID CARD NUMBER FOUND recordLength=430"))
                    .noneMatch(message -> message.contains(transactionId))
                    .noneMatch(message -> message.contains(UNKNOWN_CARD))
                    .noneMatch(message -> message.contains("765.43"))
                    .noneMatch(message -> message.contains("MERCHANT"));
        }

        @Test
        @DisplayName("the missing-category-key diagnostic says which stage it is in without publishing "
                + "the key it could not find")
        void theMissingCategoryKeyDiagnosticPublishesNoKey() {
            final Account account = postableAccount();
            arrangeValidation(account);
            categoryBalanceMissing();
            categoryBalanceInsertEchoes();
            heldReadFinds(account);
            rewriteAffects(1);
            transactionWriteEchoes();

            service.post(record("T1", "10.00"));

            assertThat(loggedMessages())
                    .anyMatch(message -> message.matches(
                            "TCATBAL record not found for key : \\[REDACTED] "
                                    + "ref=[0-9a-f]{24}\\.\\. Creating\\."))
                    .noneMatch(message -> message.contains(ACCOUNT_ID + TYPE_CODE + CATEGORY_CODE));
        }

        @Test
        @DisplayName("the version-race diagnostic reports that nothing took effect and names no account")
        void theVersionRaceDiagnosticNamesNoAccount() {
            final Account account = postableAccount();
            arrangeThroughCategoryBalance(account);
            heldReadFinds(account);
            rewriteAffects(0);

            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.post(record("T1", "10.00")));

            assertThat(loggedMessages())
                    .anyMatch(message -> message.startsWith(
                            "account rewrite lost a version race program=CBTRN02C"))
                    .anyMatch(message -> message.endsWith("effect=none"))
                    .noneMatch(message -> message.contains(ACCOUNT_ID));
        }

        @Test
        @DisplayName("the inert-rewrite diagnostic reports the reason it set and that nothing took "
                + "effect, on the CONSTRUCTED absence path")
        void theInertRewriteDiagnosticReportsItsReason() {
            final Account account = postableAccount();
            arrangeThroughCategoryBalance(account);
            heldReadFindsNothing();
            rewriteAffects(0);
            transactionWriteEchoes();

            service.post(record("T1", "10.00"));

            assertThat(loggedMessages())
                    .anyMatch(message -> message.startsWith(
                            "account rewrite found no row program=CBTRN02C"))
                    .anyMatch(message -> message.contains("reasonCode=109"))
                    .anyMatch(message -> message.contains("reason=" + DESCRIPTION_109))
                    .noneMatch(message -> message.contains(ACCOUNT_ID));
        }
    }
}
