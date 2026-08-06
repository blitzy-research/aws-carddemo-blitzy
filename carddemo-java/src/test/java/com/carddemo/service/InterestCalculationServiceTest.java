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
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Sort;

import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.DisclosureGroup;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.domain.id.DisclosureGroupId;
import com.carddemo.exception.AbendException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.DisclosureGroupRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;


/**
 * Parity tests for {@link InterestCalculationService}, the translation of the batch interest
 * calculator {@code app/cbl/CBACT04C.cbl} at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p>Each nested group targets one of the behaviours a plausible translation of this member gets
 * wrong. The expected values are derived from the source's own field widths and expression, not from
 * the implementation, so a test here fails when the implementation drifts rather than agreeing with it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterestCalculationService: the interest run, truncating and operand-ordered")
final class InterestCalculationServiceTest {

    /** The ten-character run date the legacy linkage field at line 178 carries. */
    private static final String ORACLE_RUN_DATE = "2022-07-19";

    /** The account the fixtures post to; eleven digits, as the key field declares. */
    private static final String ORACLE_ACCOUNT_ID = "00000000011";

    /** A second account, used to prove the final group is still closed at end of file. */
    private static final String ORACLE_SECOND_ACCOUNT_ID = "00000000022";

    /** A group identifier that resolves directly; ten characters, as the key field declares. */
    private static final String ORACLE_DIRECT_GROUP_ID = "A000000000";

    /**
     * The group identifier every seeded account actually carries: ten spaces, which matches none of the
     * seeded disclosure keys and is therefore what drives the estate onto the default fallback.
     */
    private static final String ORACLE_BLANK_GROUP_ID = "          ";

    /**
     * The padded default group literal. Ten characters: the seven-character literal the source moves at
     * line 437 followed by three spaces, because the receiving field is ten characters wide.
     */
    private static final String ORACLE_PADDED_DEFAULT_GROUP_ID = "DEFAULT" + "   ";

    /** The unpadded literal, which resolves nothing and must never be used as a probe key. */
    private static final String ORACLE_UNPADDED_DEFAULT_GROUP_ID = "DEFAULT";

    /**
     * A type code deliberately different from the category code, so that a key built in the wrong order
     * cannot accidentally pass.
     */
    private static final String ORACLE_TRAN_TYPE_CD = "03";

    /** A category code deliberately four characters and different from the type code. */
    private static final String ORACLE_TRAN_CAT_CD = "0007";

    /** Line 482: the interest transaction's type code. */
    private static final String ORACLE_INTEREST_TYPE_CD = "01";

    /** Line 483: a two-character literal moved into a four-digit field stores as four characters. */
    private static final String ORACLE_INTEREST_CAT_CD = "0005";

    /** Line 484: a six-character literal moved into a ten-character field carries four trailing spaces. */
    private static final String ORACLE_INTEREST_SOURCE = "System" + "    ";

    /** Line 491: numeric zero moved into a nine-digit field stores as nine zero characters. */
    private static final String ORACLE_MERCHANT_ID = "000000000";

    /** Lines 485 to 489: the description literal, whose trailing space is part of it. */
    private static final String ORACLE_DESCRIPTION_PREFIX = "Int. for a/c ";

    /** The identifier's total width: a ten-character date plus a six-digit suffix. */
    private static final int ORACLE_TRAN_ID_WIDTH = 16;

    /** The width of both timestamps. */
    private static final int ORACLE_TIMESTAMP_WIDTH = 26;

    /** The full widths the merchant name, city and postal code fields are moved spaces into. */
    private static final int ORACLE_MERCHANT_NAME_WIDTH = 50;

    private static final int ORACLE_MERCHANT_CITY_WIDTH = 50;

    private static final int ORACLE_MERCHANT_ZIP_WIDTH = 10;

    /**
     * The <strong>batch</strong> timestamp shape: a hyphen between the date and the time, dots inside
     * the time, a two-digit hundredths field and a literal four-character tail.
     */
    private static final String ORACLE_BATCH_TIMESTAMP_PATTERN =
            "\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{2}0000";

    /** The instant the fixed clock reports: hundredths of seven, which renders as two digits. */
    private static final Instant ORACLE_INSTANT = Instant.parse("2022-07-19T23:12:31.070Z");

    /** The timestamp that instant must assemble to, character for character. */
    private static final String ORACLE_BATCH_TIMESTAMP = "2022-07-19-23.12.31.070000";

    /** The divisor of the interest expression at lines 464 to 465. */
    private static final BigDecimal ORACLE_DIVISOR = new BigDecimal("1200");

    @Mock
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    @Mock
    private DisclosureGroupRepository disclosureGroupRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    private AbendService abendService;

    private InterestGroupTransactionBoundary groupTransactionBoundary;

    private InterestCalculationService service;

    /**
     * Every record the service handed to the writer it was given, in the order it handed them over.
     *
     * <p>The service writes each synthesized record at the point {@code app/cbl/CBACT04C.cbl:L500}
     * writes it, which is inside the group's unit of work and ahead of that group's account rewrite. This
     * list is where those writes land, so a test can assert both what was written and when relative to
     * the rewrite. It is cleared for every test by the fixture below.
     */
    private final List<Transaction> writtenRecords = new ArrayList<>();

    private Logger serviceLogger;

    private Level originalServiceLevel;

    private ListAppender<ILoggingEvent> logCapture;

    /**
     * The writer the group operation is given, which records what it was handed and in what order.
     *
     * @return a writer appending to {@link #writtenRecords}
     */
    private Consumer<Transaction> recordSink() {
        return this.writtenRecords::add;
    }

    /**
     * The closed account groups a whole-run operation handed over, in the order it closed them.
     *
     * <p>The run offers each group to a destination as its control break closes it and carries none of
     * them in its result, so collecting them is the <em>test's</em> choice; see
     * {@code docs/decision-log.md} entry DL-176.
     */
    private final List<InterestCalculationService.GroupInterestResult> closedGroups =
            new ArrayList<>();

    /**
     * The group destination a whole-run operation is given.
     *
     * @return a destination appending to {@link #closedGroups}
     */
    private Consumer<InterestCalculationService.GroupInterestResult> groupSink() {
        return this.closedGroups::add;
    }

    @BeforeEach
    void setUp() {
        this.writtenRecords.clear();
        this.closedGroups.clear();
        this.transactionCategoryBalanceRepository = org.mockito.Mockito.mock(
                TransactionCategoryBalanceRepository.class, invocation -> {
                    if (invocation.getMethod().getName().equals("findAfterKey")) {
                        final String cursor = invocation.<String>getArgument(0)
                                + invocation.getArgument(1) + invocation.getArgument(2);
                        final org.springframework.data.domain.Pageable page =
                                invocation.getArgument(3);
                        return this.transactionCategoryBalanceRepository.findAll(Sort.by(
                                        Sort.Order.asc("trancatAcctId"),
                                        Sort.Order.asc("trancatTypeCd"),
                                        Sort.Order.asc("trancatCd")))
                                .stream()
                                .filter(row -> (row.getTrancatAcctId() + row.getTrancatTypeCd()
                                        + row.getTrancatCd()).compareTo(cursor) > 0)
                                .sorted(java.util.Comparator.comparing(row ->
                                        row.getTrancatAcctId() + row.getTrancatTypeCd()
                                                + row.getTrancatCd()))
                                .limit(page.getPageSize())
                                .toList();
                    }
                    return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
                });
        // The real collaborator, so an abend genuinely terminates the run, wrapped in a spy so the
        // emit-then-abend ordering can be asserted rather than assumed.
        this.abendService = spy(new AbendService());
        this.groupTransactionBoundary = new InterestGroupTransactionBoundary();
        this.service = new InterestCalculationService(this.transactionCategoryBalanceRepository,
                this.disclosureGroupRepository,
                this.accountRepository,
                this.cardCrossReferenceRepository,
                this.groupTransactionBoundary,
                this.abendService,
                Clock.fixed(ORACLE_INSTANT, ZoneOffset.UTC));
        this.serviceLogger =
                (Logger) org.slf4j.LoggerFactory.getLogger(InterestCalculationService.class);
        this.originalServiceLevel = this.serviceLogger.getLevel();
        this.serviceLogger.setLevel(Level.DEBUG);
        this.logCapture = new ListAppender<>();
        this.logCapture.setContext(this.serviceLogger.getLoggerContext());
        this.logCapture.start();
        this.serviceLogger.addAppender(this.logCapture);
    }

    @AfterEach
    void detachLogCapture() {
        this.serviceLogger.detachAppender(this.logCapture);
        this.logCapture.stop();
        this.serviceLogger.setLevel(this.originalServiceLevel);
    }

    /* ------------------------------------------------------------------------------------------ */
    /* Fixtures                                                                                    */
    /* ------------------------------------------------------------------------------------------ */

    private static TransactionCategoryBalance categoryBalance(final String accountId,
            final String balance) {
        return new TransactionCategoryBalance(accountId, ORACLE_TRAN_TYPE_CD, ORACLE_TRAN_CAT_CD,
                new BigDecimal(balance));
    }

    private static Account account(final String accountId, final String groupId,
            final String currentBalance) {
        return new Account(accountId,
                "Y",
                new BigDecimal(currentBalance),
                new BigDecimal("5000.00"),
                new BigDecimal("1000.00"),
                "2020-01-01",
                "2030-01-01",
                "2025-01-01",
                new BigDecimal("123.45"),
                new BigDecimal("678.90"),
                "12345",
                groupId);
    }

    @Test
    @DisplayName("debug diagnostics retain reference codes but no account identifier or balance")
    void debugDiagnosticsWithholdProtectedFinancialData() {
        givenGroupReadsResolve(ORACLE_ACCOUNT_ID,
                account(ORACLE_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "500.00"));
        givenAccountRewriteEchoes();
        when(this.disclosureGroupRepository.findById(any()))
                .thenReturn(Optional.of(disclosureGroup(ORACLE_DIRECT_GROUP_ID, "0.00")));

        this.service.calculateInterest(ORACLE_RUN_DATE,
                List.of(categoryBalance(ORACLE_ACCOUNT_ID, "987654321.99")), recordSink(),
                groupSink());

        final List<String> messages =
                this.logCapture.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages)
                .contains(
                        "category balance read type=03 category=0007",
                        "rate gate skipped type=03 category=0007 reason=ZERO_RATE")
                .allSatisfy(message -> assertThat(message)
                        .doesNotContain(ORACLE_ACCOUNT_ID, "987654321.99", "account=", "balance="));
    }

    private static CardCrossReference crossReference(final String accountId) {
        return new CardCrossReference("4111111111111111", "000000011", accountId);
    }

    private static DisclosureGroup disclosureGroup(final String groupId, final String rate) {
        return new DisclosureGroup(groupId, ORACLE_TRAN_TYPE_CD, ORACLE_TRAN_CAT_CD,
                new BigDecimal(rate));
    }

    private List<String> loggedMessages() {
        return this.logCapture.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("account identifiers and category balances are redacted from accrual diagnostics")
    void sensitiveAccrualValuesAreRedacted() {
        givenGroupReadsResolve(ORACLE_ACCOUNT_ID,
                account(ORACLE_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "500.00"));
        givenAccountRewriteEchoes();
        when(this.disclosureGroupRepository.findById(any()))
                .thenReturn(Optional.of(disclosureGroup(ORACLE_DIRECT_GROUP_ID, "0.00")));

        this.service.calculateInterest(ORACLE_RUN_DATE,
                List.of(categoryBalance(ORACLE_ACCOUNT_ID, "98765.43")), recordSink(), groupSink());

        assertThat(loggedMessages())
                .anyMatch(message -> message.matches(
                        "category balance read accountRef=\\[REDACTED] ref=[0-9a-f]{24} "
                                + "type=03 category=0007"))
                .anyMatch(message -> message.matches(
                        "rate gate skipped accountRef=\\[REDACTED] ref=[0-9a-f]{24} "
                                + "type=03 category=0007 rate=0"))
                .noneMatch(message -> message.contains(ORACLE_ACCOUNT_ID))
                .noneMatch(message -> message.contains("98765.43"));
    }

    /**
     * Wires only the two keyed reads a group performs once, before any row of it. The account rewrite is
     * wired separately, because the paths that abend never reach it and a stub they do not use would be
     * reported as dead test code.
     */
    private void givenGroupReadsResolve(final String accountId, final Account existing) {
        when(this.accountRepository.findById(accountId)).thenReturn(Optional.of(existing));
        when(this.cardCrossReferenceRepository
                .findFirstByXrefAcctIdOrderByXrefCardNumAsc(accountId))
                .thenReturn(Optional.of(crossReference(accountId)));
    }

    /** Wires the account rewrite the control break performs, echoing back what was saved. */
    private void givenAccountRewriteEchoes() {
        when(this.accountRepository.save(any(Account.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /* ========================================================================================== */

    @Nested
    @DisplayName("(a0) every synthesized record is written before the group's account rewrite")
    class TheRecordsAreWrittenBeforeTheRewrite {

        /** Creates the nest. */
        TheRecordsAreWrittenBeforeTheRewrite() {
        }

        @Test
        @DisplayName("the writer receives every record of the group, in synthesis order, and receives "
                + "them all before the account rewrite is issued")
        void everyRecordIsWrittenBeforeTheRewrite() {
            final List<String> sequence = new ArrayList<>();
            givenGroupReadsResolve(ORACLE_ACCOUNT_ID,
                    account(ORACLE_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "100.00"));
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.of(disclosureGroup(ORACLE_DIRECT_GROUP_ID, "12.00")));
            when(InterestCalculationServiceTest.this.accountRepository.save(any(Account.class)))
                    .thenAnswer(invocation -> {
                        sequence.add("rewrite");
                        return invocation.getArgument(0);
                    });

            InterestCalculationServiceTest.this.service.calculateGroupInterest(ORACLE_RUN_DATE,
                    ORACLE_ACCOUNT_ID,
                    List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.00"),
                            categoryBalance(ORACLE_ACCOUNT_ID, "200.00")),
                    0L,
                    synthesized -> sequence.add("write:" + synthesized.getTranId()));

            assertThat(sequence)
                    .as("both writes happen first, in synthesis order, and the rewrite happens last -"
                            + " which is CBACT04C L468 inside the loop against L353 at the break")
                    .containsExactly("write:" + ORACLE_RUN_DATE + "000001",
                            "write:" + ORACLE_RUN_DATE + "000002",
                            "rewrite");
        }

        @Test
        @DisplayName("a writer that fails stops the group before the account is rewritten, which is "
                + "the legacy's write-error arm abending with the balance untouched")
        void aFailingWriterPreventsTheRewrite() {
            givenGroupReadsResolve(ORACLE_ACCOUNT_ID,
                    account(ORACLE_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "100.00"));
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.of(disclosureGroup(ORACLE_DIRECT_GROUP_ID, "12.00")));
            final AbendException writeFailure = new AbendException("CBACT04C",
                    "ERROR WRITING TRANSACTION RECORD");

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> InterestCalculationServiceTest.this.service
                            .calculateGroupInterest(ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID,
                                    List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.00")), 0L,
                                    synthesized -> {
                                        throw writeFailure;
                                    }))
                    .isSameAs(writeFailure);

            verify(InterestCalculationServiceTest.this.accountRepository, never())
                    .save(any(Account.class));
        }

        @Test
        @DisplayName("a row the zero-rate gate skipped writes nothing, so a group of only skipped rows "
                + "still rewrites its account and hands the writer nothing at all")
        void aSkippedRowWritesNothing() {
            givenGroupReadsResolve(ORACLE_ACCOUNT_ID,
                    account(ORACLE_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "100.00"));
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.of(disclosureGroup(ORACLE_DIRECT_GROUP_ID, "0.00")));
            givenAccountRewriteEchoes();

            InterestCalculationServiceTest.this.service.calculateGroupInterest(ORACLE_RUN_DATE,
                    ORACLE_ACCOUNT_ID,
                    List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.00")), 0L, recordSink());

            assertThat(InterestCalculationServiceTest.this.writtenRecords).isEmpty();
            verify(InterestCalculationServiceTest.this.accountRepository).save(any(Account.class));
        }

        @Test
        @DisplayName("the writer is mandatory, because the legacy has no path that synthesizes a record "
                + "with nowhere to write it")
        void theWriterIsMandatory() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> InterestCalculationServiceTest.this.service
                            .calculateGroupInterest(ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID,
                                    List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.00")), 0L, null))
                    .withMessageContaining("synthesizedWriter");
        }

        @Test
        @DisplayName("the whole-file driver's run result carries exactly what its groups wrote, in the "
                + "same order, because the result is filled by the same writer")
        void theRunResultIsFilledByTheWriter() {
            givenGroupReadsResolve(ORACLE_ACCOUNT_ID,
                    account(ORACLE_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "100.00"));
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.of(disclosureGroup(ORACLE_DIRECT_GROUP_ID, "12.00")));
            givenAccountRewriteEchoes();

            final InterestCalculationService.InterestRunResult run =
                    InterestCalculationServiceTest.this.service.calculateInterest(ORACLE_RUN_DATE,
                            List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.00"),
                                    categoryBalance(ORACLE_ACCOUNT_ID, "200.00")),
                            recordSink(), groupSink());

            assertThat(InterestCalculationServiceTest.this.writtenRecords)
                    .extracting(Transaction::getTranId)
                    .containsExactly(ORACLE_RUN_DATE + "000001", ORACLE_RUN_DATE + "000002");
            assertThat(run.transactionCount()).isEqualTo(2);
        }
    }
    @Nested
    @DisplayName("(a) the interest expression: multiply first, then divide, then truncate")
    final class InterestArithmetic {

        @Test
        @DisplayName("truncates rather than rounding, on a case where a half-even store differs")
        void truncatesWhereHalfEvenWouldRoundUp() {
            // 100.70 * 12.00 = 1208.4000; 1208.4000 / 1200 = 1.007 exactly.
            // Truncating toward zero at two decimals gives 1.00; a half-even or half-up store gives
            // 1.01. The estate contains no rounding clause, so 1.00 is the only correct answer.
            final BigDecimal balance = new BigDecimal("100.70");
            final BigDecimal rate = new BigDecimal("12.00");
            final BigDecimal exactQuotient =
                    balance.multiply(rate).divide(ORACLE_DIVISOR, 10, RoundingMode.DOWN);
            assertThat(exactQuotient.setScale(2, RoundingMode.HALF_EVEN))
                    .as("the fixture must be one where a half-even store genuinely differs")
                    .isEqualByComparingTo("1.01");

            final BigDecimal produced = accrueOneRow(balance, rate);

            assertThat(produced).isEqualByComparingTo("1.00");
            assertThat(produced.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("multiplies before dividing, on a case where dividing the rate first collapses"
                + " the result to zero")
        void multipliesBeforeDividing() {
            // 999999.99 * 0.01 = 9999.9999; / 1200 = 8.33333325, truncating to 8.33.
            // Dividing the rate by 1200 first truncates 0.0000083333 away entirely, so a rearranged
            // expression yields 0.00 - a difference of the whole amount, not of one cent.
            final BigDecimal balance = new BigDecimal("999999.99");
            final BigDecimal rate = new BigDecimal("0.01");
            final BigDecimal divideFirst = rate.divide(ORACLE_DIVISOR, 4, RoundingMode.DOWN)
                    .multiply(balance)
                    .setScale(2, RoundingMode.DOWN);
            assertThat(divideFirst)
                    .as("the fixture must be one where a rearranged expression genuinely differs")
                    .isEqualByComparingTo("0.00");

            final BigDecimal produced = accrueOneRow(balance, rate);

            assertThat(produced).isEqualByComparingTo("8.33");
        }

        @Test
        @DisplayName("never fails on a quotient with no terminating decimal expansion")
        void survivesNonTerminatingQuotient() {
            // 100.00 * 19.00 / 1200 = 1.5833... recurring. A division taken without a scale would
            // throw; the intermediate scale is what makes this safe.
            final BigDecimal produced = accrueOneRow(new BigDecimal("100.00"), new BigDecimal("19.00"));

            assertThat(produced).isEqualByComparingTo("1.58");
        }

        @Test
        @DisplayName("the running total is the sum of the truncated rows, and reaches the account")
        void runningTotalSumsTruncatedRows() {
            givenGroupReadsResolve(ORACLE_ACCOUNT_ID,
                    account(ORACLE_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "500.00"));
            givenAccountRewriteEchoes();
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.of(disclosureGroup(ORACLE_DIRECT_GROUP_ID, "12.00")));

            final InterestCalculationService.GroupInterestResult result =
                    InterestCalculationServiceTest.this.service.calculateGroupInterest(
                            ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID,
                            List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.70"),
                                    categoryBalance(ORACLE_ACCOUNT_ID, "100.70")),
                            0L, recordSink());

            // Two rows at 1.00 each: the truncation happens per row, not once on the sum.
            assertThat(result.totalInterest()).isEqualByComparingTo("2.00");
            assertThat(result.updatedAccount().getAcctCurrBal()).isEqualByComparingTo("502.00");
        }
    }

    @Nested
    @DisplayName("(b) and (c) the rate gate encloses both the computation and the fee no-op")
    final class RateGate {

        @Test
        @DisplayName("a non-zero rate computes, writes, and produces no other effect - the fee"
                + " paragraph contributes nothing")
        void nonZeroRateComputesAndTheFeeParagraphDoesNothing() {
            givenGroupReadsResolve(ORACLE_ACCOUNT_ID,
                    account(ORACLE_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "500.00"));
            givenAccountRewriteEchoes();
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.of(disclosureGroup(ORACLE_DIRECT_GROUP_ID, "12.00")));

            final InterestCalculationService.GroupInterestResult result =
                    InterestCalculationServiceTest.this.service.calculateGroupInterest(
                            ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID,
                            List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.70")), 0L, recordSink());

            assertThat(result.rateGateSkipped()).isFalse();
            assertThat(result.interestTransactions()).hasSize(1);
            assertThat(result.categoryInterests()).singleElement()
                    .satisfies(row -> assertThat(row.producedTransaction()).isTrue());

            // The fee paragraph is invoked here, between the computation and the next row. Its body is
            // empty in the source and must stay empty, so its only assertable property is that it has
            // no effect at all. Because it is a private method and this module's reflection budget is
            // zero, that is asserted by proving the run's complete interaction set: exactly one keyed
            // read of each input and one account rewrite. The synthesized transaction is returned for
            // the batch generation writer and this service has no live-master output collaborator.
            // Any invented fee logic would have to touch a repository or change a balance, and either
            // would break this assertion.
            verify(InterestCalculationServiceTest.this.accountRepository)
                    .findById(ORACLE_ACCOUNT_ID);
            verify(InterestCalculationServiceTest.this.cardCrossReferenceRepository)
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ORACLE_ACCOUNT_ID);
            verify(InterestCalculationServiceTest.this.disclosureGroupRepository, times(1))
                    .findById(any());
            verify(InterestCalculationServiceTest.this.accountRepository, times(1))
                    .save(any(Account.class));
            verifyNoMoreInteractions(InterestCalculationServiceTest.this.accountRepository,
                    InterestCalculationServiceTest.this.cardCrossReferenceRepository,
                    InterestCalculationServiceTest.this.disclosureGroupRepository,
                    InterestCalculationServiceTest.this.transactionCategoryBalanceRepository);
            // No abend, and no status report, on the happy path.
            verifyNoMoreInteractions(InterestCalculationServiceTest.this.abendService);
        }

        @Test
        @DisplayName("a zero rate skips BOTH the computation and the fee invocation, writing nothing")
        void zeroRateSkipsBoth() {
            givenGroupReadsResolve(ORACLE_ACCOUNT_ID,
                    account(ORACLE_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "500.00"));
            givenAccountRewriteEchoes();
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.of(disclosureGroup(ORACLE_DIRECT_GROUP_ID, "0.00")));

            final InterestCalculationService.GroupInterestResult result =
                    InterestCalculationServiceTest.this.service.calculateGroupInterest(
                            ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID,
                            List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.70")), 0L, recordSink());

            assertThat(result.rateGateSkipped()).isTrue();
            assertThat(result.totalInterest()).isEqualByComparingTo("0.00");
            assertThat(result.interestTransactions()).isEmpty();
            assertThat(result.categoryInterests()).singleElement().satisfies(row -> {
                assertThat(row.rateGateSkipped()).isTrue();
                assertThat(row.producedTransaction()).isFalse();
                assertThat(row.interestTransaction()).isNull();
            });
            // No generation record was synthesized, and the identifier suffix did not advance - which
            // proves the whole gated block was skipped rather than only its arithmetic.
            assertThat(result.lastTranIdSuffix()).isZero();
        }

        @Test
        @DisplayName("a rate of zero written at another scale still reads as zero")
        void zeroRateIsComparedByValueNotByScale() {
            givenGroupReadsResolve(ORACLE_ACCOUNT_ID,
                    account(ORACLE_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "500.00"));
            givenAccountRewriteEchoes();
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.of(disclosureGroup(ORACLE_DIRECT_GROUP_ID, "0.0000")));

            final InterestCalculationService.GroupInterestResult result =
                    InterestCalculationServiceTest.this.service.calculateGroupInterest(
                            ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID,
                            List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.70")), 0L, recordSink());

            assertThat(result.rateGateSkipped()).isTrue();
            assertThat(result.interestTransactions()).isEmpty();
        }
    }

    @Nested
    @DisplayName("(d), (e) and (f) the disclosure key and its single fallback")
    final class DisclosureLookup {

        @Test
        @DisplayName("the composite key is built group, TYPE, CATEGORY - never the source's move order")
        void keyIsBuiltInGroupTypeCategoryOrder() {
            givenGroupReadsResolve(ORACLE_ACCOUNT_ID,
                    account(ORACLE_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "500.00"));
            givenAccountRewriteEchoes();
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.of(disclosureGroup(ORACLE_DIRECT_GROUP_ID, "12.00")));

            InterestCalculationServiceTest.this.service.calculateGroupInterest(ORACLE_RUN_DATE,
                    ORACLE_ACCOUNT_ID, List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.70")), 0L, recordSink());

            final ArgumentCaptor<DisclosureGroupId> key =
                    ArgumentCaptor.forClass(DisclosureGroupId.class);
            verify(InterestCalculationServiceTest.this.disclosureGroupRepository)
                    .findById(key.capture());

            // The source moves group, then CATEGORY, then TYPE at lines 210 to 212. The key is ordered
            // group, then TYPE, then CATEGORY. The two fixture codes are deliberately different, so a
            // key built in the move order fails here instead of passing by coincidence.
            assertThat(key.getValue().getDisAcctGroupId()).isEqualTo(ORACLE_DIRECT_GROUP_ID);
            assertThat(key.getValue().getDisTranTypeCd()).isEqualTo(ORACLE_TRAN_TYPE_CD);
            assertThat(key.getValue().getDisTranCatCd()).isEqualTo(ORACLE_TRAN_CAT_CD);
        }

        @Test
        @DisplayName("a miss falls back exactly once, using the PADDED ten-character literal")
        void fallbackProbesOnceWithThePaddedLiteral() {
            givenGroupReadsResolve(ORACLE_ACCOUNT_ID,
                    account(ORACLE_ACCOUNT_ID, ORACLE_BLANK_GROUP_ID, "500.00"));
            givenAccountRewriteEchoes();
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(
                            disclosureGroup(ORACLE_PADDED_DEFAULT_GROUP_ID, "12.00")));

            final InterestCalculationService.GroupInterestResult result =
                    InterestCalculationServiceTest.this.service.calculateGroupInterest(
                            ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID,
                            List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.70")), 0L, recordSink());

            assertThat(result.defaultGroupUsed()).isTrue();
            assertThat(result.categoryInterests()).singleElement()
                    .satisfies(row -> assertThat(row.defaultGroupUsed()).isTrue());

            final ArgumentCaptor<DisclosureGroupId> keys =
                    ArgumentCaptor.forClass(DisclosureGroupId.class);
            // EXACTLY two probes: the direct one and the single fallback. Never a third.
            verify(InterestCalculationServiceTest.this.disclosureGroupRepository, times(2))
                    .findById(keys.capture());
            final List<DisclosureGroupId> probed = keys.getAllValues();

            assertThat(probed).hasSize(2);
            assertThat(probed.get(0).getDisAcctGroupId()).isEqualTo(ORACLE_BLANK_GROUP_ID);
            // The padded form, ten characters, never trimmed. The seven-character form resolves
            // nothing, which is why it must not appear.
            assertThat(probed.get(1).getDisAcctGroupId())
                    .isEqualTo(ORACLE_PADDED_DEFAULT_GROUP_ID)
                    .hasSize(ORACLE_PADDED_DEFAULT_GROUP_ID.length())
                    .isNotEqualTo(ORACLE_UNPADDED_DEFAULT_GROUP_ID);
            // The fallback replaces only the group identifier; the other two key parts carry over.
            assertThat(probed.get(1).getDisTranTypeCd()).isEqualTo(ORACLE_TRAN_TYPE_CD);
            assertThat(probed.get(1).getDisTranCatCd()).isEqualTo(ORACLE_TRAN_CAT_CD);
        }

        @Test
        @DisplayName("a second miss abends: there is no third attempt")
        void secondMissAbendsWithNoThirdAttempt() {
            givenGroupReadsResolve(ORACLE_ACCOUNT_ID,
                    account(ORACLE_ACCOUNT_ID, ORACLE_BLANK_GROUP_ID, "500.00"));
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.empty());

            assertThatExceptionOfType(AbendException.class).isThrownBy(() ->
                    InterestCalculationServiceTest.this.service.calculateGroupInterest(
                            ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID,
                            List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.70")), 0L, recordSink()))
                    .satisfies(abend -> {
                        assertThat(abend.culprit()).isEqualTo("CBACT04C");
                        assertThat(abend.reason()).contains("23");
                    });

            // Two probes and no more. A loop, a retry template or a third fallback would show here.
            verify(InterestCalculationServiceTest.this.disclosureGroupRepository, times(2))
                    .findById(any());
            verifyNoMoreInteractions(InterestCalculationServiceTest.this.disclosureGroupRepository);
            verify(InterestCalculationServiceTest.this.accountRepository, never())
                    .save(any(Account.class));
        }

        @Test
        @DisplayName("emit then abend: the status is reported before the run is terminated")
        void reportsTheStatusBeforeAbending() {
            givenGroupReadsResolve(ORACLE_ACCOUNT_ID,
                    account(ORACLE_ACCOUNT_ID, ORACLE_BLANK_GROUP_ID, "500.00"));
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.empty());

            assertThatExceptionOfType(AbendException.class).isThrownBy(() ->
                    InterestCalculationServiceTest.this.service.calculateGroupInterest(
                            ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID,
                            List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.70")), 0L, recordSink()));

            final InOrder ordered = inOrder(InterestCalculationServiceTest.this.abendService);
            ordered.verify(InterestCalculationServiceTest.this.abendService)
                    .displayIoStatus("23", "READ", "DISCGRP");
            ordered.verify(InterestCalculationServiceTest.this.abendService)
                    .abendOnFileStatus("CBACT04C", "23", "READ", "DISCGRP");
        }

        @Test
        @DisplayName("an unreadable disclosure group is the error arm, not the fallback arm")
        void unreadableDisclosureGroupAbendsWithoutFallingBack() {
            givenGroupReadsResolve(ORACLE_ACCOUNT_ID,
                    account(ORACLE_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "500.00"));
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenThrow(new QueryTimeoutException("the store did not answer"));

            assertThatExceptionOfType(AbendException.class).isThrownBy(() ->
                    InterestCalculationServiceTest.this.service.calculateGroupInterest(
                            ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID,
                            List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.70")), 0L, recordSink()));

            // A failure is not a miss: exactly one probe, and no fallback attempt.
            verify(InterestCalculationServiceTest.this.disclosureGroupRepository, times(1))
                    .findById(any());
            verify(InterestCalculationServiceTest.this.abendService)
                    .displayIoStatus("31", "READ", "DISCGRP");
            assertThat(InterestCalculationServiceTest.this.loggedMessages())
                    .anyMatch(message -> message.contains("failureChain=QueryTimeoutException"))
                    .noneMatch(message -> message.contains("the store did not answer"));
        }
    }

    @Nested
    @DisplayName("(i) and (j) the synthesized interest transaction, field by field")
    final class SynthesizedTransaction {

        @Test
        @DisplayName("every field carries the width and the literal the source assigns it")
        void everyFieldMatchesTheSource() {
            givenGroupReadsResolve(ORACLE_ACCOUNT_ID,
                    account(ORACLE_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "500.00"));
            givenAccountRewriteEchoes();
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.of(disclosureGroup(ORACLE_DIRECT_GROUP_ID, "12.00")));

            final InterestCalculationService.GroupInterestResult result =
                    InterestCalculationServiceTest.this.service.calculateGroupInterest(
                            ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID,
                            List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.70")), 0L, recordSink());

            final Transaction written = result.interestTransactions().get(0);

            // (j) the identifier: ten-character date then a six-digit zero-padded suffix.
            assertThat(written.getTranId()).hasSize(ORACLE_TRAN_ID_WIDTH)
                    .startsWith(ORACLE_RUN_DATE)
                    .endsWith("000001")
                    .isEqualTo(ORACLE_RUN_DATE + "000001");

            assertThat(written.getTranTypeCd()).isEqualTo(ORACLE_INTEREST_TYPE_CD);
            // A two-character literal moved into a four-digit field: four characters, not one or two.
            assertThat(written.getTranCatCd()).isEqualTo(ORACLE_INTEREST_CAT_CD).hasSize(4);
            // Ten characters, never trimmed.
            assertThat(written.getTranSource()).isEqualTo(ORACLE_INTEREST_SOURCE).hasSize(10);
            // The literal's trailing space survives, and the account identifier keeps all eleven digits.
            assertThat(written.getTranDesc())
                    .isEqualTo(ORACLE_DESCRIPTION_PREFIX + ORACLE_ACCOUNT_ID)
                    .startsWith(ORACLE_DESCRIPTION_PREFIX)
                    .endsWith(ORACLE_ACCOUNT_ID);
            assertThat(written.getTranAmt()).isEqualByComparingTo("1.00");
            // Nine zero characters as a string, never the number zero.
            assertThat(written.getMerchantId()).isEqualTo(ORACLE_MERCHANT_ID).hasSize(9);
            // All four merchant attributes are unprefixed, and each is checked individually.
            assertThat(written.getMerchantName()).isBlank().hasSize(ORACLE_MERCHANT_NAME_WIDTH);
            assertThat(written.getMerchantCity()).isBlank().hasSize(ORACLE_MERCHANT_CITY_WIDTH);
            assertThat(written.getMerchantZip()).isBlank().hasSize(ORACLE_MERCHANT_ZIP_WIDTH);
            assertThat(written.getTranCardNum()).isEqualTo("4111111111111111");
        }

        @Test
        @DisplayName("the two timestamps are byte-identical and carry the BATCH form, not the online one")
        void bothTimestampsAreIdenticalAndInTheBatchForm() {
            givenGroupReadsResolve(ORACLE_ACCOUNT_ID,
                    account(ORACLE_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "500.00"));
            givenAccountRewriteEchoes();
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.of(disclosureGroup(ORACLE_DIRECT_GROUP_ID, "12.00")));

            final Transaction written = InterestCalculationServiceTest.this.service
                    .calculateGroupInterest(ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID,
                            List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.70")), 0L, recordSink())
                    .interestTransactions()
                    .get(0);

            assertThat(written.getTranOrigTs()).isEqualTo(ORACLE_BATCH_TIMESTAMP)
                    .hasSize(ORACLE_TIMESTAMP_WIDTH)
                    .matches(ORACLE_BATCH_TIMESTAMP_PATTERN);
            // Byte-identical, because the source builds the value once and moves it into both fields.
            assertThat(written.getTranProcTs()).isEqualTo(written.getTranOrigTs());

            // The online form would put a space at position 11 and colons inside the time. Neither
            // appears, which is what keeps the two 26-character forms from being confused.
            assertThat(written.getTranOrigTs().charAt(10)).isEqualTo('-');
            assertThat(written.getTranOrigTs()).doesNotContain(":").doesNotContain(" ");
            assertThat(written.getTranOrigTs()).endsWith("0000");
        }

        @Test
        @DisplayName("the six-digit suffix continues across groups, because it belongs to the run")
        void suffixIsPerRunAndZeroPadded() {
            givenGroupReadsResolve(ORACLE_ACCOUNT_ID,
                    account(ORACLE_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "500.00"));
            givenAccountRewriteEchoes();
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.of(disclosureGroup(ORACLE_DIRECT_GROUP_ID, "12.00")));

            final InterestCalculationService.GroupInterestResult result =
                    InterestCalculationServiceTest.this.service.calculateGroupInterest(
                            ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID,
                            List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.70"),
                                    categoryBalance(ORACLE_ACCOUNT_ID, "100.70")),
                            41L, recordSink());

            assertThat(result.lastTranIdSuffix()).isEqualTo(43L);
            assertThat(result.interestTransactions())
                    .extracting(Transaction::getTranId)
                    .containsExactly(ORACLE_RUN_DATE + "000042", ORACLE_RUN_DATE + "000043");
        }
    }

    @Nested
    @DisplayName("(g) and (h) the control break, and the final group at end of file")
    final class ControlBreak {

        @Test
        @DisplayName("BOTH cycle accumulators are zeroed, and the total reaches the balance")
        void zeroesBothCycleAccumulators() {
            final Account existing =
                    account(ORACLE_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "500.00");
            // Both accumulators start non-zero, so zeroing only one would leave a detectable residue.
            assertThat(existing.getAcctCurrCycCredit()).isNotEqualByComparingTo("0.00");
            assertThat(existing.getAcctCurrCycDebit()).isNotEqualByComparingTo("0.00");
            givenGroupReadsResolve(ORACLE_ACCOUNT_ID, existing);
            givenAccountRewriteEchoes();
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.of(disclosureGroup(ORACLE_DIRECT_GROUP_ID, "12.00")));

            final Account updated = InterestCalculationServiceTest.this.service
                    .calculateGroupInterest(ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID,
                            List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.70")), 0L, recordSink())
                    .updatedAccount();

            assertThat(updated.getAcctCurrBal()).isEqualByComparingTo("501.00");
            assertThat(updated.getAcctCurrCycCredit()).isEqualByComparingTo("0.00");
            assertThat(updated.getAcctCurrCycDebit()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("the final group at end of file is still closed, not dropped")
        void finalGroupAtEndOfFileIsStillProcessed() {
            givenGroupReadsResolve(ORACLE_ACCOUNT_ID,
                    account(ORACLE_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "500.00"));
            givenGroupReadsResolve(ORACLE_SECOND_ACCOUNT_ID,
                    account(ORACLE_SECOND_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "700.00"));
            givenAccountRewriteEchoes();
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.of(disclosureGroup(ORACLE_DIRECT_GROUP_ID, "12.00")));

            final InterestCalculationService.InterestRunResult result =
                    InterestCalculationServiceTest.this.service.calculateInterest(ORACLE_RUN_DATE,
                            List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.70"),
                                    categoryBalance(ORACLE_ACCOUNT_ID, "100.70"),
                                    categoryBalance(ORACLE_SECOND_ACCOUNT_ID, "100.70")),
                            recordSink(), groupSink());

            // Two groups, both closed. A loop that only breaks on a key change would report one.
            assertThat(result.groupCount()).isEqualTo(2);
            assertThat(result.recordCount()).isEqualTo(3);
            assertThat(InterestCalculationServiceTest.this.closedGroups).extracting(
                            InterestCalculationService.GroupInterestResult::accountId)
                    .containsExactly(ORACLE_ACCOUNT_ID, ORACLE_SECOND_ACCOUNT_ID);
            // The last account's interest was genuinely posted, which is the point of the flush.
            assertThat(InterestCalculationServiceTest.this.closedGroups.get(1).updatedAccount().getAcctCurrBal())
                    .isEqualByComparingTo("701.00");
            assertThat(InterestCalculationServiceTest.this.writtenRecords).hasSize(3);
            assertThat(result.transactionCount()).isEqualTo(3);
            assertThat(result.lastTranIdSuffix()).isEqualTo(3L);
            verify(InterestCalculationServiceTest.this.accountRepository, times(2))
                    .save(any(Account.class));
        }

        @Test
        @DisplayName("an empty source reads nothing, closes no group and touches no account")
        void emptySourceClosesNoGroup() {
            final InterestCalculationService.InterestRunResult result =
                    InterestCalculationServiceTest.this.service.calculateInterest(ORACLE_RUN_DATE,
                            List.of(), recordSink(), groupSink());

            assertThat(result.groupCount()).isZero();
            assertThat(result.recordCount()).isZero();
            assertThat(InterestCalculationServiceTest.this.closedGroups).isEmpty();
            assertThat(InterestCalculationServiceTest.this.writtenRecords).isEmpty();
            assertThat(result.transactionCount()).isZero();
            assertThat(result.rateGateSkipped()).isFalse();
            assertThat(result.defaultGroupUsed()).isFalse();
            verify(InterestCalculationServiceTest.this.accountRepository, never())
                    .save(any(Account.class));
        }

        @Test
        @DisplayName("a concurrent modification of the account propagates untranslated")
        void concurrentModificationIsNotTranslatedIntoAnAbend() {
            when(InterestCalculationServiceTest.this.accountRepository.findById(ORACLE_ACCOUNT_ID))
                    .thenReturn(Optional.of(
                            account(ORACLE_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "500.00")));
            when(InterestCalculationServiceTest.this.cardCrossReferenceRepository
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ORACLE_ACCOUNT_ID))
                    .thenReturn(Optional.of(crossReference(ORACLE_ACCOUNT_ID)));
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.of(disclosureGroup(ORACLE_DIRECT_GROUP_ID, "12.00")));
            when(InterestCalculationServiceTest.this.accountRepository.save(any(Account.class)))
                    .thenThrow(new OptimisticLockingFailureException("another writer won"));

            assertThatExceptionOfType(OptimisticLockingFailureException.class).isThrownBy(() ->
                    InterestCalculationServiceTest.this.service.calculateGroupInterest(
                            ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID,
                            List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.70")), 0L, recordSink()));

            // Deliberately not reported as a file status: this member has no conflict-handling arm.
            verify(InterestCalculationServiceTest.this.abendService, never())
                    .displayIoStatus(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("the sequential scan, the keyed reads and the job parameter")
    final class ReadsAndParameters {

        @Test
        @DisplayName("the whole-master run asks for the record-key order explicitly")
        void wholeMasterRunSuppliesTheRecordKeyOrder() {
            when(InterestCalculationServiceTest.this.transactionCategoryBalanceRepository
                    .findAll(any(Sort.class))).thenReturn(List.of());

            InterestCalculationServiceTest.this.service.calculateInterest(ORACLE_RUN_DATE,
                    recordSink(), groupSink());

            final ArgumentCaptor<Sort> order = ArgumentCaptor.forClass(Sort.class);
            verify(InterestCalculationServiceTest.this.transactionCategoryBalanceRepository)
                    .findAll(order.capture());
            assertThat(order.getValue().stream())
                    .extracting(Sort.Order::getProperty)
                    .containsExactly("trancatAcctId", "trancatTypeCd", "trancatCd");
            assertThat(order.getValue().stream()).allMatch(Sort.Order::isAscending);
        }

        @Test
        @DisplayName("an unreadable master is the error arm and abends")
        void unreadableMasterAbends() {
            when(InterestCalculationServiceTest.this.transactionCategoryBalanceRepository
                    .findAll(any(Sort.class)))
                    .thenThrow(new QueryTimeoutException("the store did not answer"));

            assertThatExceptionOfType(AbendException.class).isThrownBy(() ->
                    InterestCalculationServiceTest.this.service.calculateInterest(ORACLE_RUN_DATE,
                            recordSink(), groupSink()));

            verify(InterestCalculationServiceTest.this.abendService)
                    .displayIoStatus("31", "READ", "TCATBALF");
        }

        @Test
        @DisplayName("a missing account abends rather than being skipped")
        void missingAccountAbends() {
            when(InterestCalculationServiceTest.this.accountRepository.findById(ORACLE_ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            assertThatExceptionOfType(AbendException.class).isThrownBy(() ->
                    InterestCalculationServiceTest.this.service.calculateGroupInterest(
                            ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID,
                            List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.70")), 0L, recordSink()));

            verify(InterestCalculationServiceTest.this.abendService)
                    .displayIoStatus("23", "READ", "ACCTFILE");
            verify(InterestCalculationServiceTest.this.cardCrossReferenceRepository, never())
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(any());
        }

        @Test
        @DisplayName("a missing cross-reference abends rather than being skipped")
        void missingCrossReferenceAbends() {
            when(InterestCalculationServiceTest.this.accountRepository.findById(ORACLE_ACCOUNT_ID))
                    .thenReturn(Optional.of(
                            account(ORACLE_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "500.00")));
            when(InterestCalculationServiceTest.this.cardCrossReferenceRepository
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ORACLE_ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            assertThatExceptionOfType(AbendException.class).isThrownBy(() ->
                    InterestCalculationServiceTest.this.service.calculateGroupInterest(
                            ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID,
                            List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.70")), 0L, recordSink()));

            verify(InterestCalculationServiceTest.this.abendService)
                    .displayIoStatus("23", "READ", "XREFFILE");
        }

        @Test
        @DisplayName("an unwritable account master is the rewrite error arm and abends")
        void unwritableAccountMasterAbends() {
            when(InterestCalculationServiceTest.this.accountRepository.findById(ORACLE_ACCOUNT_ID))
                    .thenReturn(Optional.of(
                            account(ORACLE_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "500.00")));
            when(InterestCalculationServiceTest.this.cardCrossReferenceRepository
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ORACLE_ACCOUNT_ID))
                    .thenReturn(Optional.of(crossReference(ORACLE_ACCOUNT_ID)));
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.of(disclosureGroup(ORACLE_DIRECT_GROUP_ID, "12.00")));
            when(InterestCalculationServiceTest.this.accountRepository.save(any(Account.class)))
                    .thenThrow(new QueryTimeoutException("the store did not answer"));

            assertThatExceptionOfType(AbendException.class).isThrownBy(() ->
                    InterestCalculationServiceTest.this.service.calculateGroupInterest(
                            ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID,
                            List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.70")), 0L, recordSink()));

            verify(InterestCalculationServiceTest.this.abendService)
                    .displayIoStatus("31", "REWRITE", "ACCTFILE");
        }

        @Test
        @DisplayName("the run date must be present and exactly ten characters")
        void runDateIsValidated() {
            assertThatIllegalArgumentException().isThrownBy(() ->
                            InterestCalculationServiceTest.this.service.calculateInterest(null,
                                    recordSink(), groupSink()))
                    .withMessageContaining("required");
            assertThatIllegalArgumentException().isThrownBy(() ->
                            InterestCalculationServiceTest.this.service.calculateInterest("2022-07",
                                    recordSink(), groupSink()))
                    .withMessageContaining("exactly 10");
        }

        @Test
        @DisplayName("a group must be non-empty, non-negative in suffix, and homogeneous in its key")
        void groupArgumentsAreValidated() {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    InterestCalculationServiceTest.this.service.calculateGroupInterest(
                            ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID, List.of(), 0L, recordSink()))
                    .withMessageContaining("at least one");

            assertThatIllegalArgumentException().isThrownBy(() ->
                    InterestCalculationServiceTest.this.service.calculateGroupInterest(
                            ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID,
                            List.of(categoryBalance(ORACLE_ACCOUNT_ID, "1.00")), -1L, recordSink()))
                    .withMessageContaining("counts upward");

            assertThatIllegalArgumentException().isThrownBy(() ->
                    InterestCalculationServiceTest.this.service.calculateGroupInterest(
                            ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID,
                            List.of(categoryBalance(ORACLE_SECOND_ACCOUNT_ID, "1.00")), 0L, recordSink()))
                    .withMessageContaining("account identifier");
        }

        @Test
        @DisplayName("a whole run carries no collection at all, so there is nothing for a later caller "
                + "to mutate, and each group it hands over defends its own")
        void aRunCarriesNoCollectionAndEachGroupDefendsItsOwn() {
            givenGroupReadsResolve(ORACLE_ACCOUNT_ID,
                    account(ORACLE_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "500.00"));
            givenAccountRewriteEchoes();
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.of(disclosureGroup(ORACLE_DIRECT_GROUP_ID, "12.00")));

            final InterestCalculationService.InterestRunResult run =
                    InterestCalculationServiceTest.this.service.calculateInterest(ORACLE_RUN_DATE,
                            List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.70")),
                            recordSink(), groupSink());

            assertThat(InterestCalculationService.InterestRunResult.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .containsExactly("groupCount", "transactionCount", "recordCount",
                            "rateGateSkipped", "defaultGroupUsed", "lastTranIdSuffix");
            assertThat(run.groupCount()).isOne();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> InterestCalculationServiceTest.this.closedGroups.get(0).categoryInterests().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> InterestCalculationServiceTest.this.closedGroups.get(0).interestTransactions().clear());
        }

        @Test
        @DisplayName("a whole run refuses to start without a destination for its groups and its "
                + "transactions")
        void aRunRefusesToStartWithoutItsDestinations() {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                            InterestCalculationServiceTest.this.service.calculateInterest(
                                    ORACLE_RUN_DATE, List.of(), null, groupSink()))
                    .withMessageContaining("transactionSink");
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                            InterestCalculationServiceTest.this.service.calculateInterest(
                                    ORACLE_RUN_DATE, List.of(), recordSink(), null))
                    .withMessageContaining("groupSink");
        }
    }

    @Nested
    @DisplayName("the error arm of every read and write, and the two-level status model")
    final class FailureArms {

        @Test
        @DisplayName("a sequential read that fails is the error arm, never mistaken for end of file")
        void failingSequentialReadIsTheErrorArmNotEndOfFile() {
            assertThatExceptionOfType(AbendException.class).isThrownBy(() ->
                    InterestCalculationServiceTest.this.service.calculateInterest(ORACLE_RUN_DATE,
                            new FailingSource(), recordSink(), groupSink()));

            // The failure is reported as the permanent-error code and abends. Had it been folded into
            // the end-of-file arm the run would have ended quietly, reporting zero records.
            verify(InterestCalculationServiceTest.this.abendService)
                    .displayIoStatus("31", "READ", "TCATBALF");
            verify(InterestCalculationServiceTest.this.accountRepository, never())
                    .findById(any());
        }

        @Test
        @DisplayName("an unreadable account master is the error arm and abends")
        void unreadableAccountMasterAbends() {
            when(InterestCalculationServiceTest.this.accountRepository.findById(ORACLE_ACCOUNT_ID))
                    .thenThrow(new QueryTimeoutException("the store did not answer"));

            assertThatExceptionOfType(AbendException.class).isThrownBy(() ->
                    InterestCalculationServiceTest.this.service.calculateGroupInterest(
                            ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID,
                            List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.70")), 0L, recordSink()));

            verify(InterestCalculationServiceTest.this.abendService)
                    .displayIoStatus("31", "READ", "ACCTFILE");
        }

        @Test
        @DisplayName("an unreadable cross-reference is the error arm and abends")
        void unreadableCrossReferenceAbends() {
            when(InterestCalculationServiceTest.this.accountRepository.findById(ORACLE_ACCOUNT_ID))
                    .thenReturn(Optional.of(
                            account(ORACLE_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "500.00")));
            when(InterestCalculationServiceTest.this.cardCrossReferenceRepository
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ORACLE_ACCOUNT_ID))
                    .thenThrow(new QueryTimeoutException("the store did not answer"));

            assertThatExceptionOfType(AbendException.class).isThrownBy(() ->
                    InterestCalculationServiceTest.this.service.calculateGroupInterest(
                            ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID,
                            List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.70")), 0L, recordSink()));

            verify(InterestCalculationServiceTest.this.abendService)
                    .displayIoStatus("31", "READ", "XREFFILE");
        }

        @Test
        @DisplayName("a year the four-character field cannot hold is refused rather than truncated")
        void yearOutsideTheLegacyFieldIsRefused() {
            final InterestCalculationService farFuture = new InterestCalculationService(
                    InterestCalculationServiceTest.this.transactionCategoryBalanceRepository,
                    InterestCalculationServiceTest.this.disclosureGroupRepository,
                    InterestCalculationServiceTest.this.accountRepository,
                    InterestCalculationServiceTest.this.cardCrossReferenceRepository,
                    InterestCalculationServiceTest.this.groupTransactionBoundary,
                    InterestCalculationServiceTest.this.abendService,
                    Clock.fixed(LocalDateTime.of(10_000, 1, 1, 0, 0).toInstant(ZoneOffset.UTC),
                            ZoneOffset.UTC));
            givenGroupReadsResolve(ORACLE_ACCOUNT_ID,
                    account(ORACLE_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "500.00"));
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.of(disclosureGroup(ORACLE_DIRECT_GROUP_ID, "12.00")));

            // A five-digit year would silently widen the 26-character contract, so it is refused
            // outright rather than rendered.
            assertThatIllegalArgumentException().isThrownBy(() ->
                            farFuture.calculateGroupInterest(ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID,
                                    List.of(categoryBalance(ORACLE_ACCOUNT_ID, "100.70")), 0L, recordSink()))
                    .withMessageContaining("four-character year field");
        }
    }

    /**
     * A source whose sequential read fails rather than returning a record or reporting exhaustion, so
     * that the error arm of the read paragraph is reachable from a test. A plain list cannot express
     * this, and the distinction between a failed read and an exhausted one is the whole point of the
     * estate's two-level status model.
     */
    private static final class FailingSource extends AbstractList<TransactionCategoryBalance> {

        @Override
        public Iterator<TransactionCategoryBalance> iterator() {
            return new Iterator<>() {

                @Override
                public boolean hasNext() {
                    throw new QueryTimeoutException("the store did not answer");
                }

                @Override
                public TransactionCategoryBalance next() {
                    throw new NoSuchElementException("the store did not answer");
                }
            };
        }

        @Override
        public TransactionCategoryBalance get(final int index) {
            throw new IndexOutOfBoundsException(index);
        }

        @Override
        public int size() {
            return 1;
        }
    }

    /* ------------------------------------------------------------------------------------------ */

    /**
     * Accrues a single row at a given balance and rate and returns the interest produced, so an
     * arithmetic assertion reads as one line rather than as a wiring exercise.
     *
     * @param balance the category balance
     * @param rate    the disclosed rate
     * @return the monthly interest the service produced
     */
    private BigDecimal accrueOneRow(final BigDecimal balance, final BigDecimal rate) {
        when(this.accountRepository.findById(ORACLE_ACCOUNT_ID)).thenReturn(
                Optional.of(account(ORACLE_ACCOUNT_ID, ORACLE_DIRECT_GROUP_ID, "500.00")));
        when(this.cardCrossReferenceRepository
                .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ORACLE_ACCOUNT_ID))
                .thenReturn(Optional.of(crossReference(ORACLE_ACCOUNT_ID)));
        when(this.accountRepository.save(any(Account.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(this.disclosureGroupRepository.findById(any())).thenReturn(Optional.of(
                new DisclosureGroup(ORACLE_DIRECT_GROUP_ID, ORACLE_TRAN_TYPE_CD,
                        ORACLE_TRAN_CAT_CD, rate)));

        final InterestCalculationService.GroupInterestResult result =
                this.service.calculateGroupInterest(ORACLE_RUN_DATE, ORACLE_ACCOUNT_ID,
                        List.of(new TransactionCategoryBalance(ORACLE_ACCOUNT_ID,
                                ORACLE_TRAN_TYPE_CD, ORACLE_TRAN_CAT_CD, balance)),
                        0L, recordSink());

        return result.categoryInterests().get(0).monthlyInterest();
    }
}
