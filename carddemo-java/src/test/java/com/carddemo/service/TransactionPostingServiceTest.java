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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Sort;

import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.DailyTransaction;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.domain.enums.RejectReason;
import com.carddemo.domain.id.TransactionCategoryBalanceId;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.FileStatusException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.DailyTransactionRepository;
import com.carddemo.repository.RecordWriter;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.TransactionPostingService.PostingResult;
import com.carddemo.service.TransactionPostingService.PostingRunSummary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Unit tests for the daily-transaction posting service.
 *
 * <p>The class under test is the migrated form of {@code app/cbl/CBTRN02C.cbl} - 731 lines, 27
 * procedure units - and seven of its behaviours are counter-intuitive enough that a plausible,
 * compiling, wrong implementation would pass a casually written suite. Each of those seven has its own
 * nested group below, and each group is written to fail against the wrong implementation rather than
 * merely to exercise the right one.
 *
 * <p>Every expected reject description, reject code and timestamp image in this class is a literal
 * declared here, so the oracle is independent of the code it judges: no expected value is obtained by
 * calling the service, the reject-reason enumeration or the decimal codec. Amounts are written as
 * decimal literals with their scale visible, because the scale is the thing under test.
 *
 * <p>The clock is fixed, so the regenerated processing timestamp is an exact expected string rather
 * than a pattern match. The abend collaborator is the real one wherever an abend is asserted, so the
 * emit-then-abend ordering is exercised through the production path rather than through a stub that
 * cannot enforce it.
 */
@DisplayName("Transaction posting service: the daily-transaction posting program")
class TransactionPostingServiceTest {

    /** A card number the cross-reference resolves. */
    private static final String CARD = "4111111111111111";

    /** A card number the cross-reference does not resolve, which is reject code 100. */
    private static final String UNKNOWN_CARD = "9999999999999999";

    private static final String ACCT = "00000000011";

    private static final String TYPE = "01";

    private static final String CAT = "0005";

    /** A 26-character origination timestamp whose first ten characters are 2022-07-19. */
    private static final String ORIG_TS = "2022-07-19-20.00.00.000000";

    /** A 26-character blank, which is how the processing timestamp arrives on input. */
    private static final String BLANK_TS = "                          ";

    /** The clock instant every expected processing timestamp below is derived from by hand. */
    private static final String FIXED_INSTANT = "2022-07-19T23:12:32.457Z";

    /**
     * The processing timestamp the fixed instant must produce, written out by hand: four year
     * characters, hyphen, month, hyphen, day, hyphen, hour, dot, minute, dot, second, dot, the two
     * hundredths digits of 457 milliseconds, and the literal four-character tail.
     */
    private static final String EXPECTED_PROC_TS = "2022-07-19-23.12.32.450000";

    private static final String DESC_100 = "INVALID CARD NUMBER FOUND";

    private static final String DESC_101 = "ACCOUNT RECORD NOT FOUND";

    private static final String DESC_102 = "OVERLIMIT TRANSACTION";

    private static final String DESC_103 = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";

    private static final String DESC_109 = "ACCOUNT RECORD NOT FOUND";

    private DailyTransactionRepository dailyTransactionRepository;

    private TransactionRepository transactionRepository;

    private AccountRepository accountRepository;

    private CardCrossReferenceRepository cardCrossReferenceRepository;

    private TransactionCategoryBalanceRepository categoryBalanceRepository;

    private RecordWriter recordWriter;

    private TransactionPostingService service;

    private Logger serviceLogger;

    private Level previousLogLevel;

    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void setUp() {
        dailyTransactionRepository = Mockito.mock(DailyTransactionRepository.class, invocation -> {
            if (invocation.getMethod().getName()
                    .equals("findByDalytranIdGreaterThanOrderByDalytranIdAsc")) {
                final String cursor = invocation.getArgument(0);
                final org.springframework.data.domain.Limit limit = invocation.getArgument(1);
                return dailyTransactionRepository.findAll(
                                Sort.by(Sort.Direction.ASC, "dalytranId"))
                        .stream()
                        .filter(record -> record.getDalytranId().compareTo(cursor) > 0)
                        .limit(limit.max())
                        .toList();
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        transactionRepository = Mockito.mock(TransactionRepository.class);
        accountRepository = Mockito.mock(AccountRepository.class);
        cardCrossReferenceRepository = Mockito.mock(CardCrossReferenceRepository.class);
        categoryBalanceRepository = Mockito.mock(TransactionCategoryBalanceRepository.class);
        recordWriter = Mockito.mock(RecordWriter.class, invocation -> {
            if (invocation.getMethod().getName().equals("insert")) {
                return invocation.getArgument(0);
            }
            return null;
        });
        service = new TransactionPostingService(dailyTransactionRepository, transactionRepository,
                accountRepository, cardCrossReferenceRepository, categoryBalanceRepository,
                recordWriter, new AbendService(),
                Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC));

        serviceLogger = (Logger) LoggerFactory.getLogger(TransactionPostingService.class);
        previousLogLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.TRACE);
        logCapture = new ListAppender<>();
        logCapture.start();
        serviceLogger.addAppender(logCapture);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logCapture);
        serviceLogger.setLevel(previousLogLevel);
        logCapture.stop();
    }

    private List<String> loggedMessages() {
        return logCapture.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("rejected-record diagnostics use correlation tokens and publish no record content")
    void rejectedRecordDiagnosticsAreRedacted() {
        final String transactionId = "2022071900000001";
        Mockito.when(cardCrossReferenceRepository.findById(UNKNOWN_CARD))
                .thenReturn(Optional.empty());

        service.postAll(List.of(recordOn(transactionId, "765.43", UNKNOWN_CARD)), ignored -> {
            // The sink deliberately records nothing; this test observes only the service diagnostics.
        });

        assertThat(loggedMessages())
                .anyMatch(message -> message.matches(
                        "record rejected program=CBTRN02C "
                                + "transactionRef=\\[REDACTED] ref=[0-9a-f]{24} "
                                + "reasonCode=100 reason=INVALID CARD NUMBER FOUND"))
                .anyMatch(message -> message.matches(
                        "reject record queued program=CBTRN02C "
                                + "transactionRef=\\[REDACTED] ref=[0-9a-f]{24} "
                                + "reasonCode=100 reason=INVALID CARD NUMBER FOUND recordLength=430"))
                .noneMatch(message -> message.contains(transactionId))
                .noneMatch(message -> message.contains(UNKNOWN_CARD))
                .noneMatch(message -> message.contains("765.43"))
                .noneMatch(message -> message.contains("merchant"));
    }

    private static DailyTransaction record(final String id, final String amount) {
        return recordOn(id, amount, CARD);
    }

    private static DailyTransaction recordOn(final String id, final String amount,
            final String cardNumber) {
        return new DailyTransaction(id, TYPE, CAT, "POS TERM  ", "purchase",
                new BigDecimal(amount), "000000123", "MERCHANT NAME", "MERCHANT CITY", "12345",
                cardNumber, ORIG_TS, BLANK_TS);
    }

    private static Account account(final String currBal, final String creditLimit,
            final String cycCredit, final String cycDebit, final String expiry) {
        return new Account(ACCT, "Y", new BigDecimal(currBal), new BigDecimal(creditLimit),
                new BigDecimal("500.00"), "2020-01-01", expiry, "2020-01-01",
                new BigDecimal(cycCredit), new BigDecimal(cycDebit), "12345", "GROUP01   ");
    }

    /** Wires every collaborator for a record that posts, over the supplied account. */
    private void resolving(final Account acct) {
        Mockito.when(cardCrossReferenceRepository.findById(CARD))
                .thenReturn(Optional.of(new CardCrossReference(CARD, "000000001", ACCT)));
        Mockito.when(accountRepository.findById(ACCT)).thenReturn(Optional.of(acct));
        Mockito.when(accountRepository.rewritePostingBalances(
                        ArgumentMatchers.eq(ACCT),
                        ArgumentMatchers.any(BigDecimal.class),
                        ArgumentMatchers.any(BigDecimal.class),
                        ArgumentMatchers.any(BigDecimal.class)))
                .thenReturn(1);
        Mockito.when(categoryBalanceRepository
                        .findById(ArgumentMatchers.any(TransactionCategoryBalanceId.class)))
                .thenReturn(Optional.of(new TransactionCategoryBalance(ACCT, TYPE, CAT,
                        new BigDecimal("0.00"))));
        Mockito.when(categoryBalanceRepository
                        .saveAndFlush(ArgumentMatchers.any(TransactionCategoryBalance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.when(recordWriter.insert(
                        ArgumentMatchers.any(TransactionCategoryBalance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.when(transactionRepository.insertAndFlush(ArgumentMatchers.any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Transaction capturePostedTransaction() {
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        Mockito.verify(transactionRepository).insertAndFlush(captor.capture());
        return captor.getValue();
    }

    private Account captureSavedAccount() {
        ArgumentCaptor<BigDecimal> currentBalance = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> currentCycleCredit = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> currentCycleDebit = ArgumentCaptor.forClass(BigDecimal.class);
        Mockito.verify(accountRepository).rewritePostingBalances(ArgumentMatchers.eq(ACCT),
                currentBalance.capture(), currentCycleCredit.capture(), currentCycleDebit.capture());
        return account(currentBalance.getValue().toPlainString(), "99999.00",
                currentCycleCredit.getValue().toPlainString(),
                currentCycleDebit.getValue().toPlainString(), "2099-01-01");
    }

    private TransactionCategoryBalance captureSavedCategoryBalance() {
        ArgumentCaptor<TransactionCategoryBalance> captor =
                ArgumentCaptor.forClass(TransactionCategoryBalance.class);
        Mockito.verify(categoryBalanceRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private TransactionCategoryBalance captureInsertedCategoryBalance() {
        ArgumentCaptor<TransactionCategoryBalance> captor =
                ArgumentCaptor.forClass(TransactionCategoryBalance.class);
        Mockito.verify(recordWriter).insert(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("Reject 103 overwrites reject 102 when both conditions hold")
    class OverwritePrecedence {

        @Test
        @DisplayName("a record both over limit and past expiry is rejected with 103, never 102")
        void bothConditionsYield103() {
            resolving(account("0.00", "10.00", "0.00", "0.00", "2021-01-01"));

            PostingResult result = service.post(record("T1", "5000.00"));

            assertThat(result.reasonCode()).isEqualTo(103);
            assertThat(result.reasonDescription()).isEqualTo(DESC_103);
            assertThat(result.rejectReason())
                    .isEqualTo(RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION);
            assertThat(result.posted()).isFalse();
            assertThat(result.rejected()).isTrue();
        }

        @Test
        @DisplayName("over limit alone is 102, so the two blocks are not the same block")
        void overlimitAloneYields102() {
            resolving(account("0.00", "10.00", "0.00", "0.00", "2099-01-01"));

            PostingResult result = service.post(record("T1", "5000.00"));

            assertThat(result.reasonCode()).isEqualTo(102);
            assertThat(result.reasonDescription()).isEqualTo(DESC_102);
            assertThat(result.rejectReason()).isEqualTo(RejectReason.OVERLIMIT_TRANSACTION);
        }

        @Test
        @DisplayName("past expiry alone is 103, so the second block is genuinely unguarded")
        void expiredAloneYields103() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2021-01-01"));

            assertThat(service.post(record("T1", "10.00")).reasonCode()).isEqualTo(103);
        }

        @Test
        @DisplayName("neither condition leaves the record acceptable")
        void neitherConditionPosts() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2099-01-01"));

            PostingResult result = service.post(record("T1", "10.00"));

            assertThat(result.reasonCode()).isZero();
            assertThat(result.reasonDescription()).isEmpty();
            assertThat(result.rejectReason()).isNull();
            assertThat(result.posted()).isTrue();
        }
    }

    @Nested
    @DisplayName("Reject 100 short-circuits the account lookup entirely")
    class ShortCircuit {

        @Test
        @DisplayName("an unresolved card number is 100 and the account is never read")
        void invalidCardNumberNeverReadsTheAccount() {
            Mockito.when(cardCrossReferenceRepository.findById(CARD)).thenReturn(Optional.empty());

            PostingResult result = service.post(record("T1", "10.00"));

            assertThat(result.reasonCode()).isEqualTo(100);
            assertThat(result.reasonDescription()).isEqualTo(DESC_100);
            assertThat(result.rejectReason()).isEqualTo(RejectReason.INVALID_CARD_NUMBER);
            assertThat(result.posted()).isFalse();
            Mockito.verifyNoInteractions(accountRepository);
            Mockito.verifyNoInteractions(categoryBalanceRepository);
            Mockito.verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("a resolved card with no account is 101, which 100 can therefore never be")
        void missingAccountYields101() {
            Mockito.when(cardCrossReferenceRepository.findById(CARD))
                    .thenReturn(Optional.of(new CardCrossReference(CARD, "000000001", ACCT)));
            Mockito.when(accountRepository.findById(ACCT)).thenReturn(Optional.empty());

            PostingResult result = service.post(record("T1", "10.00"));

            assertThat(result.reasonCode()).isEqualTo(101);
            assertThat(result.reasonDescription()).isEqualTo(DESC_101);
            assertThat(result.rejectReason()).isEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_READ);
            Mockito.verifyNoInteractions(transactionRepository);
        }
    }

    @Nested
    @DisplayName("Reject 109 is inert: set, but producing no reject record")
    class InertReason109 {

        @Test
        @DisplayName("the record still posts and the transaction is still written")
        void theTransactionIsStillWritten() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2099-01-01"));
            Mockito.when(accountRepository.rewritePostingBalances(
                            ArgumentMatchers.eq(ACCT),
                            ArgumentMatchers.any(BigDecimal.class),
                            ArgumentMatchers.any(BigDecimal.class),
                            ArgumentMatchers.any(BigDecimal.class)))
                    .thenReturn(0);

            PostingResult result = service.post(record("T1", "10.00"));

            assertThat(result.reasonCode()).isEqualTo(109);
            assertThat(result.reasonDescription()).isEqualTo(DESC_109);
            assertThat(result.rejectReason())
                    .isEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE);
            assertThat(result.posted()).isTrue();
            assertThat(result.rejected()).isFalse();
            Mockito.verify(transactionRepository)
                    .insertAndFlush(ArgumentMatchers.any(Transaction.class));
            Mockito.verify(categoryBalanceRepository)
                    .saveAndFlush(ArgumentMatchers.any(TransactionCategoryBalance.class));
            Mockito.verify(accountRepository, Mockito.never())
                    .save(ArgumentMatchers.any(Account.class));
            assertThat(loggedMessages())
                    .anyMatch(message -> message.matches(
                            "account rewrite found no row program=CBTRN02C "
                                    + "accountRef=\\[REDACTED] ref=[0-9a-f]{24} "
                                    + "reasonCode=109 reason=ACCOUNT RECORD NOT FOUND effect=none"))
                    .noneMatch(message -> message.contains(ACCT));
        }

        @Test
        @DisplayName("a run containing one produces no reject record and returns zero")
        void aRunProducesNoRejectRecord() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2099-01-01"));
            Mockito.when(accountRepository.rewritePostingBalances(
                            ArgumentMatchers.eq(ACCT),
                            ArgumentMatchers.any(BigDecimal.class),
                            ArgumentMatchers.any(BigDecimal.class),
                            ArgumentMatchers.any(BigDecimal.class)))
                    .thenReturn(0);
            List<PostingResult> sink = new ArrayList<>();

            PostingRunSummary summary = service.postAll(List.of(record("T1", "10.00")), sink::add);

            assertThat(sink).isEmpty();
            assertThat(summary.transactionsProcessed()).isEqualTo(1L);
            assertThat(summary.transactionsPosted()).isEqualTo(1L);
            assertThat(summary.transactionsRejected()).isZero();
            assertThat(summary.returnCode()).isZero();
            assertThat(summary.partialSuccess()).isFalse();
        }

        @Test
        @DisplayName("101 and 109 share their description text but remain distinct reasons")
        void oneHundredAndOneAndOneHundredAndNineRemainDistinct() {
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getDescription())
                    .isEqualTo(DESC_101);
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getDescription())
                    .isEqualTo(DESC_109);
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getReasonCode()).isEqualTo(101);
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getReasonCode()).isEqualTo(109);
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ)
                    .isNotEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE);
        }
    }

    @Nested
    @DisplayName("The overlimit basis truncates and is evaluated left to right")
    class OverlimitBasis {

        @Test
        @DisplayName("a third decimal is truncated away, not rounded up into the second")
        void truncatesRatherThanRoundingToNearest() {
            // 10.005 - 0.00 + 0.00 truncates to 10.00, which a 10.00 limit allows. Rounding to
            // nearest would give 10.01 and reject the record as over limit.
            Account acct = new Account(ACCT, "Y", new BigDecimal("0.00"), new BigDecimal("10.00"),
                    new BigDecimal("500.00"), "2020-01-01", "2099-01-01", "2020-01-01",
                    new BigDecimal("10.005"), new BigDecimal("0.00"), "12345", "GROUP01   ");
            resolving(acct);

            PostingResult result = service.post(record("T1", "0.00"));

            assertThat(result.reasonCode()).isZero();
            assertThat(result.posted()).isTrue();
        }

        @Test
        @DisplayName("the basis is exact to the cent, and one cent decides the rejection")
        void isExactToTheCent() {
            // 900.00 - 100.00 + 200.55 is 1000.55, one cent above a 1000.54 limit.
            resolving(account("0.00", "1000.54", "900.00", "100.00", "2099-01-01"));

            assertThat(service.post(record("T1", "200.55")).reasonCode()).isEqualTo(102);
        }

        @Test
        @DisplayName("a limit exactly equal to the basis is allowed, because the test is >=")
        void equalityIsAllowed() {
            resolving(account("0.00", "1000.55", "900.00", "100.00", "2099-01-01"));

            assertThat(service.post(record("T1", "200.55")).reasonCode()).isZero();
        }

        @Test
        @DisplayName("the debit operand is subtracted, so a larger debit lowers the basis")
        void theDebitOperandIsSubtracted() {
            // 900.00 - 900.00 + 200.55 is 200.55, comfortably inside the same 1000.54 limit that
            // the zero-debit case exceeded, which pins the sign of the middle operand.
            resolving(account("0.00", "1000.54", "900.00", "900.00", "2099-01-01"));

            assertThat(service.post(record("T1", "200.55")).reasonCode()).isZero();
        }
    }

    @Nested
    @DisplayName("A negative amount is accumulated unchanged into the debit total")
    class NegativeAmounts {

        @Test
        @DisplayName("the cycle debit goes negative rather than being negated or made absolute")
        void theDebitAccumulatorGoesNegative() {
            resolving(account("100.00", "99999.00", "0.00", "0.00", "2099-01-01"));

            service.post(record("T1", "-50.00"));

            Account saved = captureSavedAccount();
            assertThat(saved.getAcctCurrCycDebit()).isEqualByComparingTo(new BigDecimal("-50.00"));
            assertThat(saved.getAcctCurrCycDebit().signum()).isNegative();
            assertThat(saved.getAcctCurrCycCredit()).isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(saved.getAcctCurrBal()).isEqualByComparingTo(new BigDecimal("50.00"));
        }

        @Test
        @DisplayName("a negative amount reduces the category balance too")
        void theCategoryBalanceIsReduced() {
            resolving(account("100.00", "99999.00", "0.00", "0.00", "2099-01-01"));
            Mockito.when(categoryBalanceRepository
                            .findById(ArgumentMatchers.any(TransactionCategoryBalanceId.class)))
                    .thenReturn(Optional.of(new TransactionCategoryBalance(ACCT, TYPE, CAT,
                            new BigDecimal("10.00"))));

            service.post(record("T1", "-50.00"));

            assertThat(captureSavedCategoryBalance().getTranCatBal())
                    .isEqualByComparingTo(new BigDecimal("-40.00"));
        }

        @Test
        @DisplayName("zero counts as non-negative and joins the credit total")
        void zeroJoinsTheCreditTotal() {
            resolving(account("100.00", "99999.00", "0.00", "0.00", "2099-01-01"));

            service.post(record("T1", "0.00"));

            Account saved = captureSavedAccount();
            assertThat(saved.getAcctCurrCycCredit()).isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(saved.getAcctCurrCycDebit()).isEqualByComparingTo(new BigDecimal("0.00"));
        }

        @Test
        @DisplayName("a positive amount joins the credit total and raises the current balance")
        void aPositiveAmountJoinsTheCreditTotal() {
            resolving(account("100.00", "99999.00", "5.00", "0.00", "2099-01-01"));

            service.post(record("T1", "25.00"));

            Account saved = captureSavedAccount();
            assertThat(saved.getAcctCurrCycCredit()).isEqualByComparingTo(new BigDecimal("30.00"));
            assertThat(saved.getAcctCurrCycDebit()).isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(saved.getAcctCurrBal()).isEqualByComparingTo(new BigDecimal("125.00"));
        }
    }

    @Nested
    @DisplayName("The three persistence stages run in the legacy order")
    class PersistenceOrder {

        @Test
        @DisplayName("category balance, then account, then transaction file")
        void categoryBalanceThenAccountThenTransaction() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2099-01-01"));

            service.post(record("T1", "10.00"));

            InOrder ordered = Mockito.inOrder(categoryBalanceRepository, accountRepository,
                    transactionRepository);
            ordered.verify(categoryBalanceRepository)
                    .saveAndFlush(ArgumentMatchers.any(TransactionCategoryBalance.class));
            ordered.verify(accountRepository).rewritePostingBalances(
                    ArgumentMatchers.eq(ACCT),
                    ArgumentMatchers.any(BigDecimal.class),
                    ArgumentMatchers.any(BigDecimal.class),
                    ArgumentMatchers.any(BigDecimal.class));
            ordered.verify(transactionRepository)
                    .insertAndFlush(ArgumentMatchers.any(Transaction.class));
            ordered.verifyNoMoreInteractions();
        }
    }

    @Nested
    @DisplayName("Timestamps: origination copied, processing regenerated")
    class Timestamps {

        @Test
        @DisplayName("the origination timestamp is byte-identical to the input")
        void originationIsCopied() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2099-01-01"));

            service.post(record("T1", "10.00"));

            assertThat(capturePostedTransaction().getTranOrigTs()).isEqualTo(ORIG_TS);
        }

        @Test
        @DisplayName("the processing timestamp is regenerated in the batch 26-character format")
        void processingIsRegeneratedInTheBatchFormat() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2099-01-01"));

            service.post(record("T1", "10.00"));

            String processing = capturePostedTransaction().getTranProcTs();
            assertThat(processing).isEqualTo(EXPECTED_PROC_TS);
            assertThat(processing).hasSize(26);
            assertThat(processing).isNotEqualTo(BLANK_TS);
            assertThat(processing.charAt(4)).isEqualTo('-');
            assertThat(processing.charAt(7)).isEqualTo('-');
            assertThat(processing.charAt(10)).isEqualTo('-');
            assertThat(processing.charAt(13)).isEqualTo('.');
            assertThat(processing.charAt(16)).isEqualTo('.');
            assertThat(processing.charAt(19)).isEqualTo('.');
            assertThat(processing.substring(20, 22)).isEqualTo("45");
            assertThat(processing.substring(22)).isEqualTo("0000");
        }

        @Test
        @DisplayName("the online timestamp form is never emitted: no colon, no space at position 11")
        void theOnlineFormIsNeverEmitted() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2099-01-01"));

            service.post(record("T1", "10.00"));

            String processing = capturePostedTransaction().getTranProcTs();
            assertThat(processing).doesNotContain(":");
            assertThat(processing.charAt(10)).isNotEqualTo(' ');
            assertThat(processing).doesNotContain("000000");
        }

        @Test
        @DisplayName("the declared batch timestamp width is 26")
        void theDeclaredWidthIs26() {
            assertThat(TransactionPostingService.BATCH_TIMESTAMP_LENGTH).isEqualTo(26);
        }
    }

    @Nested
    @DisplayName("The eleven field moves, including the four merchant fields")
    class FieldMoves {

        @Test
        @DisplayName("prefixed merchant properties land on unprefixed ones, all four of them")
        void merchantFieldsCrossThePrefixBoundary() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2099-01-01"));

            service.post(record("T1", "10.00"));

            Transaction posted = capturePostedTransaction();
            assertThat(posted.getMerchantId()).isEqualTo("000000123");
            assertThat(posted.getMerchantName()).isEqualTo("MERCHANT NAME");
            assertThat(posted.getMerchantCity()).isEqualTo("MERCHANT CITY");
            assertThat(posted.getMerchantZip()).isEqualTo("12345");
        }

        @Test
        @DisplayName("the other seven moves arrive intact, trailing blanks included")
        void theRemainingMovesArriveIntact() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2099-01-01"));

            service.post(record("T1", "10.00"));

            Transaction posted = capturePostedTransaction();
            assertThat(posted.getTranId()).isEqualTo("T1");
            assertThat(posted.getTranTypeCd()).isEqualTo(TYPE);
            assertThat(posted.getTranCatCd()).isEqualTo(CAT);
            assertThat(posted.getTranSource()).isEqualTo("POS TERM  ");
            assertThat(posted.getTranDesc()).isEqualTo("purchase");
            assertThat(posted.getTranAmt()).isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(posted.getTranCardNum()).isEqualTo(CARD);
        }
    }

    @Nested
    @DisplayName("A missing category-balance row is created, not rejected")
    class CategoryBalanceCreation {

        @Test
        @DisplayName("the row is created with the amount as its whole balance")
        void theRowIsCreated() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2099-01-01"));
            Mockito.when(categoryBalanceRepository
                            .findById(ArgumentMatchers.any(TransactionCategoryBalanceId.class)))
                    .thenReturn(Optional.empty());

            PostingResult result = service.post(record("T1", "10.00"));

            assertThat(result.posted()).isTrue();
            assertThat(result.reasonCode()).isZero();
            TransactionCategoryBalance created = captureInsertedCategoryBalance();
            assertThat(created.getTranCatBal()).isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(created.getTrancatAcctId()).isEqualTo(ACCT);
            assertThat(created.getTrancatTypeCd()).isEqualTo(TYPE);
            assertThat(created.getTrancatCd()).isEqualTo(CAT);
            assertThat(loggedMessages())
                    .anyMatch(message -> message.matches(
                            "TCATBAL record not found for key : \\[REDACTED] "
                                    + "ref=[0-9a-f]{24}\\.\\. Creating\\."))
                    .noneMatch(message -> message.contains(ACCT));
        }

        @Test
        @DisplayName("an existing row accumulates instead of being replaced")
        void anExistingRowAccumulates() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2099-01-01"));
            Mockito.when(categoryBalanceRepository
                            .findById(ArgumentMatchers.any(TransactionCategoryBalanceId.class)))
                    .thenReturn(Optional.of(new TransactionCategoryBalance(ACCT, TYPE, CAT,
                            new BigDecimal("40.00"))));

            service.post(record("T1", "10.00"));

            assertThat(captureSavedCategoryBalance().getTranCatBal())
                    .isEqualByComparingTo(new BigDecimal("50.00"));
        }

        @Test
        @DisplayName("the key is built from the cross-reference account and the record's codes")
        void theKeyIsBuiltFromTheCrossReference() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2099-01-01"));

            service.post(record("T1", "10.00"));

            ArgumentCaptor<TransactionCategoryBalanceId> captor =
                    ArgumentCaptor.forClass(TransactionCategoryBalanceId.class);
            Mockito.verify(categoryBalanceRepository).findById(captor.capture());
            assertThat(captor.getValue().getTrancatAcctId()).isEqualTo(ACCT);
            assertThat(captor.getValue().getTrancatTypeCd()).isEqualTo(TYPE);
            assertThat(captor.getValue().getTrancatCd()).isEqualTo(CAT);
        }
    }

    @Nested
    @DisplayName("A reject makes the run a partial success with return code 4")
    class ReturnCode {

        @Test
        @DisplayName("one posted and one rejected gives counts 2, 1, 1 and return code 4")
        void aRejectYieldsFour() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2099-01-01"));
            Mockito.when(cardCrossReferenceRepository.findById(UNKNOWN_CARD))
                    .thenReturn(Optional.empty());
            DailyTransaction rejected = recordOn("T2", "1.00", UNKNOWN_CARD);
            List<PostingResult> sink = new ArrayList<>();

            PostingRunSummary summary =
                    service.postAll(List.of(record("T1", "10.00"), rejected), sink::add);

            assertThat(summary.transactionsProcessed()).isEqualTo(2L);
            assertThat(summary.transactionsPosted()).isEqualTo(1L);
            assertThat(summary.transactionsRejected()).isEqualTo(1L);
            assertThat(summary.returnCode()).isEqualTo(4);
            assertThat(summary.partialSuccess()).isTrue();
            assertThat(sink).hasSize(1);
            assertThat(sink.get(0).reasonCode()).isEqualTo(100);
            assertThat(sink.get(0).reasonDescription()).isEqualTo(DESC_100);
            assertThat(sink.get(0).sourceRecord()).isSameAs(rejected);
        }

        @Test
        @DisplayName("a run with no rejects returns zero and hands nothing to the sink")
        void noRejectsYieldsZero() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2099-01-01"));
            List<PostingResult> sink = new ArrayList<>();

            PostingRunSummary summary = service.postAll(List.of(record("T1", "10.00")), sink::add);

            assertThat(summary.returnCode()).isZero();
            assertThat(summary.partialSuccess()).isFalse();
            assertThat(sink).isEmpty();
        }

        @Test
        @DisplayName("nothing is thrown for a reject, so the run always completes")
        void aRejectNeverThrows() {
            Mockito.when(cardCrossReferenceRepository.findById(UNKNOWN_CARD))
                    .thenReturn(Optional.empty());
            List<PostingResult> sink = new ArrayList<>();

            PostingRunSummary summary =
                    service.postAll(List.of(recordOn("T1", "1.00", UNKNOWN_CARD)), sink::add);

            assertThat(summary.transactionsRejected()).isEqualTo(1L);
            assertThat(summary.returnCode()).isEqualTo(4);
        }

        @Test
        @DisplayName("the declared return codes are 0 and 4")
        void theDeclaredReturnCodesAreZeroAndFour() {
            assertThat(TransactionPostingService.RETURN_CODE_SUCCESS).isZero();
            assertThat(TransactionPostingService.RETURN_CODE_REJECTS_PRESENT).isEqualTo(4);
            assertThat(TransactionPostingService.NO_REJECT_REASON_CODE).isZero();
        }
    }

    @Nested
    @DisplayName("All five reject descriptions are byte-exact")
    class RejectLiterals {

        @Test
        @DisplayName("the five codes carry exactly the five legacy texts")
        void theFiveLiteralsMatch() {
            assertThat(RejectReason.INVALID_CARD_NUMBER.getReasonCode()).isEqualTo(100);
            assertThat(RejectReason.INVALID_CARD_NUMBER.getDescription()).isEqualTo(DESC_100);
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getReasonCode()).isEqualTo(101);
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getDescription())
                    .isEqualTo(DESC_101);
            assertThat(RejectReason.OVERLIMIT_TRANSACTION.getReasonCode()).isEqualTo(102);
            assertThat(RejectReason.OVERLIMIT_TRANSACTION.getDescription()).isEqualTo(DESC_102);
            assertThat(RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION.getReasonCode())
                    .isEqualTo(103);
            assertThat(RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION.getDescription())
                    .isEqualTo(DESC_103);
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getReasonCode()).isEqualTo(109);
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getDescription())
                    .isEqualTo(DESC_109);
        }

        @Test
        @DisplayName("the reject record is 430 bytes, being 350 plus 4 plus 76")
        void theRejectRecordIs430Bytes() {
            assertThat(TransactionPostingService.SOURCE_IMAGE_LENGTH).isEqualTo(350);
            assertThat(TransactionPostingService.FAIL_REASON_LENGTH).isEqualTo(4);
            assertThat(TransactionPostingService.FAIL_REASON_DESCRIPTION_LENGTH).isEqualTo(76);
            assertThat(TransactionPostingService.VALIDATION_TRAILER_LENGTH).isEqualTo(80);
            assertThat(TransactionPostingService.REJECT_RECORD_LENGTH).isEqualTo(430);
            assertThat(TransactionPostingService.SOURCE_IMAGE_LENGTH
                    + TransactionPostingService.FAIL_REASON_LENGTH
                    + TransactionPostingService.FAIL_REASON_DESCRIPTION_LENGTH).isEqualTo(430);
        }

        @Test
        @DisplayName("the create-flag values are the legacy N and Y")
        void theCreateFlagValuesAreNAndY() {
            assertThat(TransactionPostingService.CREATE_FLAG_UNSET).isEqualTo("N");
            assertThat(TransactionPostingService.CREATE_FLAG_SET).isEqualTo("Y");
            assertThat(TransactionPostingService.BLANK_FAIL_REASON_DESCRIPTION).isEmpty();
        }

        @Test
        @DisplayName("the six DD names are the legacy ones")
        void theSixDdNamesAreTheLegacyOnes() {
            assertThat(TransactionPostingService.DALYTRAN_DD).isEqualTo("DALYTRAN");
            assertThat(TransactionPostingService.TRANFILE_DD).isEqualTo("TRANFILE");
            assertThat(TransactionPostingService.XREFFILE_DD).isEqualTo("XREFFILE");
            assertThat(TransactionPostingService.DALYREJS_DD).isEqualTo("DALYREJS");
            assertThat(TransactionPostingService.ACCTFILE_DD).isEqualTo("ACCTFILE");
            assertThat(TransactionPostingService.TCATBALF_DD).isEqualTo("TCATBALF");
            assertThat(TransactionPostingService.PROGRAM_NAME).isEqualTo("CBTRN02C");
        }
    }

    @Nested
    @DisplayName("The expiry test is a lexicographic ten-character comparison")
    class ExpiryComparison {

        @Test
        @DisplayName("an expiry equal to the origination date is allowed")
        void equalityIsAllowed() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2022-07-19"));

            assertThat(service.post(record("T1", "10.00")).reasonCode()).isZero();
        }

        @Test
        @DisplayName("one day earlier is 103, and the difference is a single character")
        void oneDayEarlierIsRejected() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2022-07-18"));

            assertThat(service.post(record("T1", "10.00")).reasonCode()).isEqualTo(103);
        }

        @Test
        @DisplayName("only the first ten characters of the timestamp participate")
        void onlyTheFirstTenCharactersParticipate() {
            // The origination timestamp's time-of-day is 20.00.00 and the expiry has no time at
            // all, so a comparison reaching past character ten would make the expiry the smaller.
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2022-07-19"));

            assertThat(service.post(record("T1", "10.00")).reasonCode()).isZero();
        }
    }

    @Nested
    @DisplayName("Per-record reset, and no state between records")
    class PerRecordReset {

        @Test
        @DisplayName("a rejected record does not reject the record that follows it")
        void aRejectDoesNotLeakForward() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2099-01-01"));
            Mockito.when(cardCrossReferenceRepository.findById(UNKNOWN_CARD))
                    .thenReturn(Optional.empty());

            assertThat(service.post(recordOn("T1", "1.00", UNKNOWN_CARD)).reasonCode())
                    .isEqualTo(100);

            PostingResult second = service.post(record("T2", "10.00"));
            assertThat(second.reasonCode()).isZero();
            assertThat(second.reasonDescription()).isEmpty();
            assertThat(second.posted()).isTrue();
        }

        @Test
        @DisplayName("an absent record is refused at the per-record boundary")
        void anAbsentRecordIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.post(null));
        }
    }

    @Nested
    @DisplayName("End of file is not an error, and the error arm emits before it abends")
    class EndOfFileAndAbends {

        @Test
        @DisplayName("an exhausted source ends the run normally with return code zero")
        void anExhaustedSourceEndsNormally() {
            PostingRunSummary summary = service.postAll(List.of(), result -> { });

            assertThat(summary.transactionsProcessed()).isZero();
            assertThat(summary.transactionsPosted()).isZero();
            assertThat(summary.transactionsRejected()).isZero();
            assertThat(summary.returnCode()).isZero();
        }

        @Test
        @DisplayName("an absent source takes the open failure arm")
        void anAbsentSourceAbends() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.postAll(null, result -> { }));
        }

        @Test
        @DisplayName("an absent reject sink takes the open failure arm before any record is read")
        void anAbsentSinkAbends() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service.postAll(List.of(), null));
        }

        @Test
        @DisplayName("a failed transaction write abends carrying the legacy literal and status 31")
        void aFailedTransactionWriteAbends() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2099-01-01"));
            Mockito.when(transactionRepository.insertAndFlush(ArgumentMatchers.any(Transaction.class)))
                    .thenThrow(new DataAccessResourceFailureException("unreachable"));

            AbendException abend = catchThrowableOfType(AbendException.class,
                    () -> service.post(record("T1", "10.00")));

            assertThat(abend.culprit()).isEqualTo("CBTRN02C");
            assertThat(abend.getCause()).isInstanceOf(FileStatusException.class);
            FileStatusException carried = (FileStatusException) abend.getCause();
            assertThat(carried.code()).isEqualTo("31");
            assertThat(carried.operation()).isEqualTo("ERROR WRITING TO TRANSACTION FILE");
            assertThat(carried.resourceName()).isEqualTo("TRANFILE");
        }

        @Test
        @DisplayName("a failed category-balance read abends before the transaction is written")
        void aFailedCategoryBalanceReadAbends() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2099-01-01"));
            Mockito.when(categoryBalanceRepository
                            .findById(ArgumentMatchers.any(TransactionCategoryBalanceId.class)))
                    .thenThrow(new DataAccessResourceFailureException("unreachable"));

            AbendException abend = catchThrowableOfType(AbendException.class,
                    () -> service.post(record("T1", "10.00")));

            FileStatusException carried = (FileStatusException) abend.getCause();
            assertThat(carried.operation()).isEqualTo("ERROR READING TRANSACTION BALANCE FILE");
            assertThat(carried.resourceName()).isEqualTo("TCATBALF");
            Mockito.verify(transactionRepository, Mockito.never())
                    .insertAndFlush(ArgumentMatchers.any(Transaction.class));
        }

        @Test
        @DisplayName("a failed category-balance write abends with the write literal")
        void aFailedCategoryBalanceWriteAbends() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2099-01-01"));
            Mockito.when(categoryBalanceRepository
                            .findById(ArgumentMatchers.any(TransactionCategoryBalanceId.class)))
                    .thenReturn(Optional.empty());
            Mockito.when(recordWriter
                            .insert(ArgumentMatchers.any(TransactionCategoryBalance.class)))
                    .thenThrow(new DataAccessResourceFailureException("unreachable"));

            AbendException abend = catchThrowableOfType(AbendException.class,
                    () -> service.post(record("T1", "10.00")));

            FileStatusException carried = (FileStatusException) abend.getCause();
            assertThat(carried.operation()).isEqualTo("ERROR WRITING TRANSACTION BALANCE FILE");
        }

        @Test
        @DisplayName("a failed category-balance rewrite abends with the rewrite literal")
        void aFailedCategoryBalanceRewriteAbends() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2099-01-01"));
            Mockito.when(categoryBalanceRepository
                            .saveAndFlush(ArgumentMatchers.any(TransactionCategoryBalance.class)))
                    .thenThrow(new DataAccessResourceFailureException("unreachable"));

            AbendException abend = catchThrowableOfType(AbendException.class,
                    () -> service.post(record("T1", "10.00")));

            FileStatusException carried = (FileStatusException) abend.getCause();
            assertThat(carried.operation()).isEqualTo("ERROR REWRITING TRANSACTION BALANCE FILE");
        }

        @Test
        @DisplayName("a failed staged read abends with the read literal, never as end of file")
        void aFailedStagedReadAbends() {
            Mockito.when(dailyTransactionRepository.findAll(ArgumentMatchers.any(Sort.class)))
                    .thenThrow(new DataAccessResourceFailureException("unreachable"));

            AbendException abend = catchThrowableOfType(AbendException.class,
                    () -> service.postStagedDailyTransactions(result -> { }));

            FileStatusException carried = (FileStatusException) abend.getCause();
            assertThat(carried.code()).isEqualTo("31");
            assertThat(carried.operation()).isEqualTo("ERROR READING DALYTRAN FILE");
            assertThat(carried.resourceName()).isEqualTo("DALYTRAN");
        }
    }

    @Nested
    @DisplayName("The staged driver reads the table in business-key order")
    class StagedDriver {

        @Test
        @DisplayName("it delegates to the repository and posts what it finds")
        void itPostsWhatItFinds() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2099-01-01"));
            Mockito.when(dailyTransactionRepository.findAll(ArgumentMatchers.any(Sort.class)))
                    .thenReturn(List.of(record("T1", "10.00")));

            PostingRunSummary summary = service.postStagedDailyTransactions(result -> { });

            assertThat(summary.transactionsProcessed()).isEqualTo(1L);
            assertThat(summary.transactionsPosted()).isEqualTo(1L);
            Mockito.verify(transactionRepository, Mockito.times(1))
                    .insertAndFlush(ArgumentMatchers.any(Transaction.class));
        }

        @Test
        @DisplayName("the order it asks for is the business key ascending")
        void itAsksForBusinessKeyOrder() {
            Mockito.when(dailyTransactionRepository.findAll(ArgumentMatchers.any(Sort.class)))
                    .thenReturn(List.of());

            service.postStagedDailyTransactions(result -> { });

            ArgumentCaptor<Sort> captor = ArgumentCaptor.forClass(Sort.class);
            Mockito.verify(dailyTransactionRepository).findAll(captor.capture());
            assertThat(captor.getValue()).isEqualTo(Sort.by(Sort.Direction.ASC, "dalytranId"));
        }
    }

    @Nested
    @DisplayName("The result types enforce the invariants the mainline guarantees")
    class ResultInvariants {

        @Test
        @DisplayName("a rejected result without a reason is refused")
        void aRejectWithoutAReasonIsRefused() {
            DailyTransaction source = record("T1", "10.00");

            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> new PostingResult(100, DESC_100, null, false, source, null, null, null));
        }

        @Test
        @DisplayName("a result without a source record is refused")
        void aResultWithoutASourceRecordIsRefused() {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new PostingResult(0, "", null, true, null, null, null, null));
        }

        @Test
        @DisplayName("counters that do not sum, and a code that contradicts them, are refused")
        void inconsistentCountersAreRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PostingRunSummary(5L, 1L, 1L, 4));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PostingRunSummary(2L, 1L, 1L, 0));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PostingRunSummary(1L, 1L, 0L, 4));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PostingRunSummary(-1L, 0L, 0L, 0));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PostingRunSummary(0L, -1L, 0L, 0));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PostingRunSummary(0L, 0L, -1L, 0));
        }

        @Test
        @DisplayName("a consistent summary is accepted and reports its own partial success")
        void aConsistentSummaryIsAccepted() {
            assertThat(new PostingRunSummary(3L, 2L, 1L, 4).partialSuccess()).isTrue();
            assertThat(new PostingRunSummary(3L, 3L, 0L, 0).partialSuccess()).isFalse();
        }
    }

    @Nested
    @DisplayName("Fixed-width field views, which a COBOL field always has and a String may not")
    class FixedWidthViews {

        @Test
        @DisplayName("an expiry shorter than its ten-character field is blank-padded, not rejected")
        void aShortExpiryIsBlankPadded() {
            // A seven-character expiry becomes "2022-07   ", whose fourth character is a blank and
            // therefore sorts below the "2022-07-19" it is compared with, so the record is late.
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2022-07"));

            assertThat(service.post(record("T1", "10.00")).reasonCode()).isEqualTo(103);
        }

        @Test
        @DisplayName("an expiry longer than its field loses its excess, not compared whole")
        void aLongExpiryIsTruncated() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2099-01-01T00:00:00"));

            assertThat(service.post(record("T1", "10.00")).reasonCode()).isZero();
        }

        @Test
        @DisplayName("an origination timestamp shorter than ten characters is blank-padded too")
        void aShortOriginationTimestampIsBlankPadded() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2021-01-01"));
            DailyTransaction shortStamp = new DailyTransaction("T1", TYPE, CAT, "POS TERM  ",
                    "purchase", new BigDecimal("10.00"), "000000123", "MERCHANT NAME",
                    "MERCHANT CITY", "12345", CARD, "2022", BLANK_TS);

            // "2021-01-01" against the padded "2022      " differs at character four, so the expiry
            // is the earlier and the record is late. Reaching a verdict at all is the point: a
            // ten-character view of a four-character value must not fail.
            assertThat(service.post(shortStamp).reasonCode()).isEqualTo(103);
        }

        @Test
        @DisplayName("the status renderer pads a short status before it reads its two bytes")
        void theStatusRendererPadsAShortStatus() {
            assertThat(TransactionPostingService.ioStatusImage(null)).hasSize(4);
            assertThat(TransactionPostingService.ioStatusImage("")).hasSize(4);
        }
    }

    @Nested
    @DisplayName("The status display paragraph has two arms, and both render four characters")
    class StatusRendering {

        @Test
        @DisplayName("a numeric status is rendered as 00 followed by its two characters")
        void aNumericStatusUsesTheStandardArm() {
            assertThat(TransactionPostingService.ioStatusImage("31")).isEqualTo("0031");
            assertThat(TransactionPostingService.ioStatusImage("00")).isEqualTo("0000");
            assertThat(TransactionPostingService.ioStatusImage("23")).isEqualTo("0023");
            assertThat(TransactionPostingService.ioStatusImage("10")).isEqualTo("0010");
        }

        @Test
        @DisplayName("a first byte of 9 uses the non-standard arm even though it is numeric")
        void aFirstByteOfNineUsesTheNonStandardArm() {
            // The second byte's binary value is rendered as three digits: '0' is 48, '1' is 49.
            assertThat(TransactionPostingService.ioStatusImage("90")).isEqualTo("9048");
            assertThat(TransactionPostingService.ioStatusImage("91")).isEqualTo("9049");
        }

        @Test
        @DisplayName("a non-numeric status uses the non-standard arm and keeps its first byte")
        void aNonNumericStatusUsesTheNonStandardArm() {
            // 'A' is 65, so a status of "0A" renders as its first byte then 065.
            assertThat(TransactionPostingService.ioStatusImage("0A")).isEqualTo("0065");
            // A blank second byte is 32.
            assertThat(TransactionPostingService.ioStatusImage("3")).isEqualTo("3032");
        }

        @Test
        @DisplayName("every rendering is exactly four characters wide")
        void everyRenderingIsFourCharacters() {
            for (String status : List.of("00", "01", "02", "04", "05", "10", "12", "23", "31",
                    "90", "0A", "  ", "3", "")) {
                assertThat(TransactionPostingService.ioStatusImage(status)).hasSize(4);
            }
        }
    }

    @Nested
    @DisplayName("The read paragraph's error arm is distinct from its end-of-file arm")
    class ReadErrorArm {

        /** A source whose cursor reports a record and then delivers nothing. */
        private Iterable<DailyTransaction> yieldingNull() {
            return () -> new java.util.Iterator<>() {
                private boolean served;

                @Override
                public boolean hasNext() {
                    return !served;
                }

                @Override
                public DailyTransaction next() {
                    served = true;
                    return null;
                }
            };
        }

        /** A source whose cursor fails while delivering a record. */
        private Iterable<DailyTransaction> failing() {
            return () -> new java.util.Iterator<>() {
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

        @Test
        @DisplayName("a cursor that delivers nothing abends rather than ending the run")
        void aNullRecordAbends() {
            AbendException abend = catchThrowableOfType(AbendException.class,
                    () -> service.postAll(yieldingNull(), result -> { }));

            FileStatusException carried = (FileStatusException) abend.getCause();
            assertThat(carried.operation()).isEqualTo("ERROR READING DALYTRAN FILE");
            assertThat(carried.resourceName()).isEqualTo("DALYTRAN");
            assertThat(carried.code()).isEqualTo("31");
        }

        @Test
        @DisplayName("a cursor that fails abends with the read literal, never as end of file")
        void aFailingCursorAbends() {
            AbendException abend = catchThrowableOfType(AbendException.class,
                    () -> service.postAll(failing(), result -> { }));

            FileStatusException carried = (FileStatusException) abend.getCause();
            assertThat(carried.operation()).isEqualTo("ERROR READING DALYTRAN FILE");
            assertThat(carried.code()).isNotEqualTo("10");
            assertThat(carried.code()).isNotEqualTo("00");
        }
    }

    @Nested
    @DisplayName("The processing timestamp refuses a width it cannot hold")
    class TimestampWidthGuard {

        @Test
        @DisplayName("a year beyond four digits is refused rather than silently widening the field")
        void aFiveDigitYearIsRefused() {
            TransactionPostingService farFuture = new TransactionPostingService(
                    dailyTransactionRepository, transactionRepository, accountRepository,
                    cardCrossReferenceRepository, categoryBalanceRepository, recordWriter,
                    new AbendService(),
                    Clock.fixed(Instant.parse("+10000-01-01T00:00:00Z"), ZoneOffset.UTC));
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2099-01-01"));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> farFuture.post(record("T1", "10.00")));
        }
    }

    @Nested
    @DisplayName("Construction refuses an absent collaborator")
    class Construction {

        @Test
        @DisplayName("every one of the eight collaborators is mandatory")
        void everyCollaboratorIsMandatory() {
            Clock clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);
            AbendService abend = new AbendService();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionPostingService(null, transactionRepository,
                            accountRepository, cardCrossReferenceRepository,
                            categoryBalanceRepository, recordWriter, abend, clock));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionPostingService(dailyTransactionRepository,
                            null, accountRepository, cardCrossReferenceRepository,
                            categoryBalanceRepository, recordWriter, abend, clock));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionPostingService(dailyTransactionRepository,
                            transactionRepository, null, cardCrossReferenceRepository,
                            categoryBalanceRepository, recordWriter, abend, clock));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionPostingService(dailyTransactionRepository,
                            transactionRepository, accountRepository, null,
                            categoryBalanceRepository, recordWriter, abend, clock));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionPostingService(dailyTransactionRepository,
                            transactionRepository, accountRepository, cardCrossReferenceRepository,
                            null, recordWriter, abend, clock));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionPostingService(dailyTransactionRepository,
                            transactionRepository, accountRepository, cardCrossReferenceRepository,
                            categoryBalanceRepository, null, abend, clock));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionPostingService(dailyTransactionRepository,
                            transactionRepository, accountRepository, cardCrossReferenceRepository,
                            categoryBalanceRepository, recordWriter, null, clock));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionPostingService(dailyTransactionRepository,
                            transactionRepository, accountRepository, cardCrossReferenceRepository,
                            categoryBalanceRepository, recordWriter, abend, null));
        }
    }
}
