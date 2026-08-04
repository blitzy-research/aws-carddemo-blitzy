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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.DailyTransaction;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.domain.enums.RejectReason;
import com.carddemo.domain.id.TransactionCategoryBalanceId;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.DailyTransactionRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.AbendService;
import com.carddemo.service.TransactionPostingService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;

/**
 * Verifies the per-record contract of the posting step's verdict router: that the validation cascade's
 * refusals become reject items and its successes become nothing at all, that the counter-intuitive
 * behaviours of the legacy posting program survive the adapter, and that a refused record produces a
 * partial success rather than a failure.
 *
 * <h2>The cascade under test is the real one</h2>
 *
 * <p>The processor is exercised over a <strong>real</strong> {@code TransactionPostingService} built on
 * mocked repositories, a real abend collaborator and a fixed clock - not over a stubbed service. That is
 * deliberate and it is what makes most of the assertions below meaningful. A stubbed service would let
 * this suite assert only that the processor returns whatever it is handed, and every ordering property
 * that actually decides what the run produces - the short circuit, the overwrite precedence, the stage
 * order, the inertness of the account-rewrite reason - would go unverified through the path an operator
 * actually runs. Driving the genuine cascade means each of those is asserted end to end, at the seam
 * where a plausible, compiling, wrong adapter would break it.
 *
 * <p>Every expected reject code, description and timestamp image is a literal declared in this class, so
 * the oracle is independent of the code it judges: no expected value is obtained by calling the
 * processor, the service, the reject-reason enumeration or the decimal codec. Amounts are written as
 * decimal literals with their scale visible, because the scale is part of what is under test.
 *
 * <p>The clock is fixed, so the regenerated processing timestamp is compared against an exact expected
 * string rather than matched against a pattern. Nothing here uses reflection, mocks a static call, or
 * inspects a private member; the two package-visible fidelity checks are called directly because they
 * guard postconditions that the production path cannot be made to breach.
 *
 * <p>Provenance of the expectations: the legacy posting program, its four copybooks and its job stream,
 * at commit SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is transcribed here.
 */
@DisplayName("Transaction validation processor: the posting step's per-record verdict router")
class TransactionValidationProcessorTest {

    /** A card number the cross-reference resolves. */
    private static final String CARD = "4111111111111111";

    /** A card number the cross-reference does not resolve, which is reject code 100. */
    private static final String UNKNOWN_CARD = "9999999999999999";

    /** The account the resolving cross-reference names. */
    private static final String ACCT = "00000000011";

    /** An account identifier no account row exists for, which is reject code 101. */
    private static final String MISSING_ACCT = "00000000099";

    private static final String TYPE = "01";

    private static final String CAT = "0005";

    /** A 26-character origination timestamp whose first ten characters are {@code 2022-07-19}. */
    private static final String ORIG_TS = "2022-07-19-20.00.00.000000";

    /** A 26-character blank, which is how the processing timestamp arrives on input. */
    private static final String BLANK_TS = "                          ";

    /** The instant the fixed clock reports, from which the expected processing timestamp is derived. */
    private static final String FIXED_INSTANT = "2022-07-19T23:12:32.457Z";

    /**
     * The processing timestamp the fixed instant must produce, written out by hand: four year
     * characters, hyphen, month, hyphen, day, hyphen, hour, dot, minute, dot, second, dot, the two
     * hundredths digits of 457 milliseconds, and the literal four-character tail.
     */
    private static final String EXPECTED_PROC_TS = "2022-07-19-23.12.32.450000";

    /**
     * A twenty-six character timestamp in the <em>online</em> form: a space where the batch form has its
     * third separator, colons where the batch form has dots, and a six-digit fraction. Exactly the right
     * width and entirely wrong, which is the defect the form check exists to catch.
     */
    private static final String ONLINE_FORM_TS = "2022-07-19 23:12:32.457000";

    /** Width, in encoded bytes, of the batch processing timestamp. */
    private static final int BATCH_TIMESTAMP_WIDTH = 26;

    private static final String DESC_100 = "INVALID CARD NUMBER FOUND";

    private static final String DESC_101 = "ACCOUNT RECORD NOT FOUND";

    private static final String DESC_102 = "OVERLIMIT TRANSACTION";

    private static final String DESC_103 = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";

    private static final String DESC_109 = "ACCOUNT RECORD NOT FOUND";

    /** Metric recording per-record verdicts. */
    private static final String METRIC_RECORDS = "carddemo.batch.posting.records";

    /** Metric timing the per-record cascade call. */
    private static final String METRIC_RECORD_TIMER = "carddemo.batch.posting.record";

    private static final String TAG_OUTCOME = "outcome";

    private static final String TAG_REASON = "reason";

    private DailyTransactionRepository dailyTransactionRepository;

    private TransactionRepository transactionRepository;

    private AccountRepository accountRepository;

    private CardCrossReferenceRepository cardCrossReferenceRepository;

    private TransactionCategoryBalanceRepository categoryBalanceRepository;

    private TransactionPostingService postingService;

    private SimpleMeterRegistry meterRegistry;

    private TransactionValidationProcessor processor;

    @BeforeEach
    void setUp() {
        dailyTransactionRepository = Mockito.mock(DailyTransactionRepository.class);
        transactionRepository = Mockito.mock(TransactionRepository.class);
        accountRepository = Mockito.mock(AccountRepository.class);
        cardCrossReferenceRepository = Mockito.mock(CardCrossReferenceRepository.class);
        categoryBalanceRepository = Mockito.mock(TransactionCategoryBalanceRepository.class);
        postingService = new TransactionPostingService(dailyTransactionRepository,
                transactionRepository, accountRepository, cardCrossReferenceRepository,
                categoryBalanceRepository, new AbendService(),
                Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC));
        meterRegistry = new SimpleMeterRegistry();
        processor = new TransactionValidationProcessor(postingService, meterRegistry);
    }

    /**
     * A daily-transaction record on the resolving card.
     *
     * @param  amount the amount, as a decimal literal whose scale is visible
     * @return the record
     */
    private static DailyTransaction record(final String amount) {
        return recordOn(amount, CARD);
    }

    /**
     * A daily-transaction record on a nominated card.
     *
     * @param  amount     the amount, as a decimal literal whose scale is visible
     * @param  cardNumber the card number the cascade will resolve against
     * @return the record
     */
    private static DailyTransaction recordOn(final String amount, final String cardNumber) {
        return new DailyTransaction("0000000000000001", TYPE, CAT, "POS TERM  ", "purchase",
                new BigDecimal(amount), "000000123", "MERCHANT NAME", "MERCHANT CITY", "12345",
                cardNumber, ORIG_TS, BLANK_TS);
    }

    /**
     * An account with the four figures the two limit tests read.
     *
     * @param  currBal     the current balance
     * @param  creditLimit the credit limit the overlimit test measures against
     * @param  cycCredit   the current-cycle credit
     * @param  cycDebit    the current-cycle debit
     * @param  expiry      the ten-character expiration field the expiry test compares as characters
     * @return the account
     */
    private static Account account(final String currBal, final String creditLimit,
            final String cycCredit, final String cycDebit, final String expiry) {
        return new Account(ACCT, "Y", new BigDecimal(currBal), new BigDecimal(creditLimit),
                new BigDecimal("500.00"), "2020-01-01", expiry, "2020-01-01",
                new BigDecimal(cycCredit), new BigDecimal(cycDebit), "12345", "GROUP01   ");
    }

    /** Wires the cross-reference so the resolving card names the account. */
    private void resolvingCrossReference() {
        Mockito.when(cardCrossReferenceRepository.findById(CARD))
                .thenReturn(Optional.of(new CardCrossReference(CARD, "000000001", ACCT)));
    }

    /** Wires every collaborator for a record that posts over an existing category-balance row. */
    private void resolving(final Account acct) {
        resolvingCrossReference();
        Mockito.when(accountRepository.findById(ACCT)).thenReturn(Optional.of(acct));
        Mockito.when(accountRepository.existsById(ACCT)).thenReturn(Boolean.TRUE);
        Mockito.when(accountRepository.save(ArgumentMatchers.any(Account.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.when(categoryBalanceRepository
                        .findById(ArgumentMatchers.any(TransactionCategoryBalanceId.class)))
                .thenReturn(Optional.of(new TransactionCategoryBalance(ACCT, TYPE, CAT,
                        new BigDecimal("0.00"))));
        Mockito.when(categoryBalanceRepository
                        .save(ArgumentMatchers.any(TransactionCategoryBalance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.when(transactionRepository.save(ArgumentMatchers.any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** An account the cascade posts cleanly, with room under the limit and an expiry far ahead. */
    private static Account postableAccount() {
        return account("0.00", "99999.00", "0.00", "0.00", "2099-01-01");
    }

    /**
     * Captures the transaction the cascade wrote.
     *
     * @return the written transaction
     */
    private Transaction capturePostedTransaction() {
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        Mockito.verify(transactionRepository).save(captor.capture());
        return captor.getValue();
    }

    /**
     * Captures the account the cascade saved.
     *
     * @return the saved account
     */
    private Account captureSavedAccount() {
        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        Mockito.verify(accountRepository).save(captor.capture());
        return captor.getValue();
    }

    /**
     * Captures the category balance the cascade saved.
     *
     * @return the saved category balance
     */
    private TransactionCategoryBalance captureSavedCategoryBalance() {
        ArgumentCaptor<TransactionCategoryBalance> captor =
                ArgumentCaptor.forClass(TransactionCategoryBalance.class);
        Mockito.verify(categoryBalanceRepository).save(captor.capture());
        return captor.getValue();
    }

    /**
     * A step execution the completion-code rule can be evaluated against.
     *
     * @param  processed the count of records read
     * @param  rejected  the count of reject records written
     * @param  status    the batch status the step ended in
     * @return the step execution
     */
    private static StepExecution stepThatRead(final long processed, final long rejected,
            final BatchStatus status) {
        StepExecution stepExecution =
                new JobExecution(1L).createStepExecution(TransactionValidationProcessor.LEGACY_STEP);
        stepExecution.setReadCount(processed);
        stepExecution.setWriteCount(rejected);
        stepExecution.setStatus(status);
        return stepExecution;
    }

    /**
     * The value of one verdict counter.
     *
     * @param  outcome the outcome tag
     * @param  reason  the reason tag
     * @return the counter's value
     */
    private double verdictCount(final String outcome, final String reason) {
        return meterRegistry.get(METRIC_RECORDS)
                .tag(TAG_OUTCOME, outcome)
                .tag(TAG_REASON, reason)
                .counter()
                .count();
    }

    /**
     * One verdict counter, or {@code null} when no counter carries that pairing of tags.
     *
     * <p>Distinct from {@link #verdictCount(String, String)} because a pairing that cannot arise has no
     * meter at all rather than a meter reading zero, and asserting its absence is the stronger statement.
     *
     * @param  outcome the outcome tag
     * @param  reason  the reason tag
     * @return the counter, or {@code null} when none matches
     */
    private Counter verdictCounterOrNull(final String outcome, final String reason) {
        return meterRegistry.find(METRIC_RECORDS)
                .tag(TAG_OUTCOME, outcome)
                .tag(TAG_REASON, reason)
                .counter();
    }

    @Nested
    @DisplayName("The validation cascade, routed through the processor")
    class ValidationCascade {

        @Test
        @DisplayName("an unresolvable card is reject 100 and the account is never looked up")
        void reject100ShortCircuitsTheAccountLookup() {
            Mockito.when(cardCrossReferenceRepository.findById(UNKNOWN_CARD))
                    .thenReturn(Optional.empty());

            RejectRecordWriter.RejectedTransaction refused =
                    processor.process(recordOn("10.00", UNKNOWN_CARD));

            assertThat(refused).isNotNull();
            assertThat(refused.reason().getReasonCode()).isEqualTo(100);
            assertThat(refused.reason().getDescription()).isEqualTo(DESC_100);
            // The whole point of the short circuit: no account read is attempted for an unknown card.
            Mockito.verifyNoInteractions(accountRepository);
            // Nor does anything reach the persistence stages.
            Mockito.verifyNoInteractions(categoryBalanceRepository);
            Mockito.verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("a card that resolves to a missing account is reject 101")
        void reject101OnAccountMiss() {
            Mockito.when(cardCrossReferenceRepository.findById(CARD))
                    .thenReturn(Optional.of(new CardCrossReference(CARD, "000000001", MISSING_ACCT)));
            Mockito.when(accountRepository.findById(MISSING_ACCT)).thenReturn(Optional.empty());

            RejectRecordWriter.RejectedTransaction refused = processor.process(record("10.00"));

            assertThat(refused).isNotNull();
            assertThat(refused.reason().getReasonCode()).isEqualTo(101);
            assertThat(refused.reason().getDescription()).isEqualTo(DESC_101);
            Mockito.verifyNoInteractions(categoryBalanceRepository);
            Mockito.verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("a record over the limit but inside its expiry is reject 102")
        void reject102Alone() {
            // 900.00 - 100.00 + 200.55 = 1000.55, one cent above the 1000.54 limit.
            resolving(account("0.00", "1000.54", "900.00", "100.00", "2099-01-01"));

            RejectRecordWriter.RejectedTransaction refused = processor.process(record("200.55"));

            assertThat(refused).isNotNull();
            assertThat(refused.reason().getReasonCode()).isEqualTo(102);
            assertThat(refused.reason().getDescription()).isEqualTo(DESC_102);
        }

        @Test
        @DisplayName("a record one cent under the limit is not reject 102, so the basis is exact")
        void oneCentUnderTheLimitPosts() {
            // 900.00 - 100.00 + 200.54 = 1000.54, exactly the limit, which the test admits.
            resolving(account("0.00", "1000.54", "900.00", "100.00", "2099-01-01"));

            assertThat(processor.process(record("200.54"))).isNull();
        }

        @Test
        @DisplayName("a record both over the limit and past its expiry is reject 103, never 102")
        void reject103OverwritesReject102() {
            // Both conditions hold: the same overlimit basis as above, and an expiry in the past.
            resolving(account("0.00", "1000.54", "900.00", "100.00", "2021-01-01"));

            RejectRecordWriter.RejectedTransaction refused = processor.process(record("200.55"));

            assertThat(refused).isNotNull();
            // 102 was written first and overwritten by 103. Guarding the second test, or swapping the
            // two, would produce 102 here and break the expected reject dataset byte for byte.
            assertThat(refused.reason().getReasonCode()).isEqualTo(103);
            assertThat(refused.reason().getDescription()).isEqualTo(DESC_103);
            assertThat(refused.reason()).isEqualTo(RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION);
        }

        @Test
        @DisplayName("a record inside the limit but past its expiry is reject 103")
        void reject103Alone() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2021-01-01"));

            RejectRecordWriter.RejectedTransaction refused = processor.process(record("10.00"));

            assertThat(refused).isNotNull();
            assertThat(refused.reason().getReasonCode()).isEqualTo(103);
        }

        @Test
        @DisplayName("an expiry no date parser accepts still admits the record, so no date is parsed")
        void expiryComparisonIsCharacterwiseAndAdmits() {
            // A ten-character view of this value is 2099-01-01, which is later than 2022-07-19. A
            // parser handed the whole value as a date would reject it; a character comparison does not.
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2099-01-01T00:00:00"));

            assertThat(processor.process(record("10.00"))).isNull();
        }

        @Test
        @DisplayName("a short expiry blank-pads and compares as characters, refusing the record")
        void expiryComparisonIsCharacterwiseAndRefuses() {
            // Padded to ten characters this is "2022-07   ". At the eighth character a blank compares
            // below a hyphen, so the account reads as expired. A parser would throw instead.
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2022-07"));

            RejectRecordWriter.RejectedTransaction refused = processor.process(record("10.00"));

            assertThat(refused).isNotNull();
            assertThat(refused.reason().getReasonCode()).isEqualTo(103);
        }

        @Test
        @DisplayName("an expiry equal to the origination date is not expired")
        void anExpiryEqualToTheOriginationDatePosts() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2022-07-19"));

            assertThat(processor.process(record("10.00"))).isNull();
        }

        @Test
        @DisplayName("an expiry one day earlier than the origination date is expired")
        void anExpiryOneDayEarlierIsExpired() {
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2022-07-18"));

            RejectRecordWriter.RejectedTransaction refused = processor.process(record("10.00"));

            assertThat(refused).isNotNull();
            assertThat(refused.reason().getReasonCode()).isEqualTo(103);
        }

        @Test
        @DisplayName("a business reject is returned, never thrown, so the chunk is never failed")
        void aBusinessRejectIsNeverThrown() {
            // Two reject codes that need no conflicting stubs are asserted explicitly here: the
            // cross-reference miss, which is the earliest possible refusal, and the expiry refusal,
            // which is the latest. The account miss and the overlimit refusal are proved not to throw
            // by their own tests above, each of which asserts on a returned value and could not reach
            // that assertion if the call had thrown.
            Mockito.when(cardCrossReferenceRepository.findById(UNKNOWN_CARD))
                    .thenReturn(Optional.empty());
            assertThatCode(() -> processor.process(recordOn("10.00", UNKNOWN_CARD)))
                    .doesNotThrowAnyException();

            resolving(account("0.00", "99999.00", "0.00", "0.00", "2021-01-01"));
            assertThatCode(() -> processor.process(record("10.00"))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the reject item pairs the very record that was read with its typed reason")
        void theRejectItemCarriesTheSourceRecordItself() {
            Mockito.when(cardCrossReferenceRepository.findById(UNKNOWN_CARD))
                    .thenReturn(Optional.empty());
            DailyTransaction read = recordOn("10.00", UNKNOWN_CARD);

            RejectRecordWriter.RejectedTransaction refused = processor.process(read);

            assertThat(refused).isNotNull();
            // The same instance, so the reject writer renders the image that was actually read.
            assertThat(refused.sourceRecord()).isSameAs(read);
            assertThat(refused.reason()).isEqualTo(RejectReason.INVALID_CARD_NUMBER);
        }
    }

    @Nested
    @DisplayName("A posted record is filtered, and its fields survive the crossing")
    class PostedRecordRouting {

        @Test
        @DisplayName("a posted record returns null, so no reject record is ever emitted for it")
        void aPostedRecordIsFiltered() {
            resolving(postableAccount());

            assertThat(processor.process(record("10.00"))).isNull();
            // Filtered from the chunk, yet persisted: the cascade wrote it before process returned.
            Mockito.verify(transactionRepository).save(ArgumentMatchers.any(Transaction.class));
        }

        @Test
        @DisplayName("the origination timestamp is copied verbatim and never regenerated")
        void theOriginationTimestampIsCarriedVerbatim() {
            resolving(postableAccount());

            processor.process(record("10.00"));

            assertThat(capturePostedTransaction().getTranOrigTs()).isEqualTo(ORIG_TS);
        }

        @Test
        @DisplayName("the processing timestamp is regenerated in the batch form, at 26 encoded bytes")
        void theProcessingTimestampIsRegeneratedInTheBatchForm() {
            resolving(postableAccount());

            processor.process(record("10.00"));

            String procTs = capturePostedTransaction().getTranProcTs();
            assertThat(procTs).isEqualTo(EXPECTED_PROC_TS);
            assertThat(procTs.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(BATCH_TIMESTAMP_WIDTH);
            // Not the input value, which arrives blank, and not the online form.
            assertThat(procTs).isNotEqualTo(BLANK_TS).isNotEqualTo(ONLINE_FORM_TS);
        }

        @Test
        @DisplayName("the posted record keeps the source record's own business identifier")
        void theIdentifierIsCarriedAndNoSurrogateIsGenerated() {
            resolving(postableAccount());
            DailyTransaction read = record("10.00");

            processor.process(read);

            assertThat(capturePostedTransaction().getTranId()).isEqualTo(read.getDalytranId());
        }

        @Test
        @DisplayName("the posted amount is worth exactly what the source amount was worth")
        void theAmountIsCarriedUnchanged() {
            resolving(postableAccount());

            processor.process(record("10.00"));

            assertThat(capturePostedTransaction().getTranAmt())
                    .isEqualByComparingTo(new BigDecimal("10.00"));
        }

        @Test
        @DisplayName("a negative amount is added unchanged to the debit and to both balances")
        void negativeAmountsAreAddedUnchanged() {
            resolving(account("100.00", "99999.00", "0.00", "0.00", "2099-01-01"));

            assertThat(processor.process(record("-25.50"))).isNull();

            Account saved = captureSavedAccount();
            assertThat(saved.getAcctCurrBal()).isEqualByComparingTo(new BigDecimal("74.50"));
            // Unchanged means still negative: the debit accumulator is driven negative on purpose, and
            // is neither negated nor made absolute nor redirected to the credit accumulator.
            assertThat(saved.getAcctCurrCycDebit()).isEqualByComparingTo(new BigDecimal("-25.50"));
            assertThat(saved.getAcctCurrCycCredit()).isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(captureSavedCategoryBalance().getTranCatBal())
                    .isEqualByComparingTo(new BigDecimal("-25.50"));
            assertThat(capturePostedTransaction().getTranAmt())
                    .isEqualByComparingTo(new BigDecimal("-25.50"));
        }

        @Test
        @DisplayName("a non-negative amount joins the credit accumulator, not the debit")
        void nonNegativeAmountsJoinTheCreditAccumulator() {
            resolving(account("100.00", "99999.00", "0.00", "0.00", "2099-01-01"));

            processor.process(record("25.50"));

            Account saved = captureSavedAccount();
            assertThat(saved.getAcctCurrCycCredit()).isEqualByComparingTo(new BigDecimal("25.50"));
            assertThat(saved.getAcctCurrCycDebit()).isEqualByComparingTo(new BigDecimal("0.00"));
        }

        @Test
        @DisplayName("an absent category-balance row is created, and is not a technical failure")
        void anAbsentCategoryBalanceRowIsCreated() {
            resolving(postableAccount());
            Mockito.when(categoryBalanceRepository
                            .findById(ArgumentMatchers.any(TransactionCategoryBalanceId.class)))
                    .thenReturn(Optional.empty());

            // A missing row takes the create arm. Nothing is thrown, nothing abends, and the record
            // still posts: the legacy status test accepts record-not-found alongside success.
            assertThat(processor.process(record("10.00"))).isNull();

            TransactionCategoryBalance created = captureSavedCategoryBalance();
            assertThat(created.getTrancatAcctId()).isEqualTo(ACCT);
            assertThat(created.getTrancatTypeCd()).isEqualTo(TYPE);
            assertThat(created.getTrancatCd()).isEqualTo(CAT);
            // A created row starts from zero, so its balance is the amount and nothing else.
            assertThat(created.getTranCatBal()).isEqualByComparingTo(new BigDecimal("10.00"));
        }

        @Test
        @DisplayName("the three stages run as category balance, then account, then transaction")
        void theThreeStagesRunInSourceOrder() {
            resolving(postableAccount());

            processor.process(record("10.00"));

            InOrder inOrder = Mockito.inOrder(categoryBalanceRepository, accountRepository,
                    transactionRepository);
            inOrder.verify(categoryBalanceRepository)
                    .save(ArgumentMatchers.any(TransactionCategoryBalance.class));
            inOrder.verify(accountRepository).save(ArgumentMatchers.any(Account.class));
            inOrder.verify(transactionRepository).save(ArgumentMatchers.any(Transaction.class));
            inOrder.verifyNoMoreInteractions();
        }
    }

    @Nested
    @DisplayName("Reject 109 is recorded and inert")
    class InertReason109 {

        @Test
        @DisplayName("an account rewrite that finds no row records 109 but produces no reject record")
        void reason109NeverProducesARejectRecord() {
            resolving(postableAccount());
            // The account was there when it was read and gone when it was rewritten.
            Mockito.when(accountRepository.existsById(ACCT)).thenReturn(Boolean.FALSE);

            RejectRecordWriter.RejectedTransaction refused = processor.process(record("10.00"));

            // Filtered like any other posted record. The mainline decided to post before this stage
            // ran and never reconsiders, so the reject dataset hears nothing about this record.
            assertThat(refused).isNull();
        }

        @Test
        @DisplayName("reject 109 does not prevent the transaction write, and the earlier stage stands")
        void reason109DoesNotPreventTheTransactionWrite() {
            resolving(postableAccount());
            Mockito.when(accountRepository.existsById(ACCT)).thenReturn(Boolean.FALSE);

            processor.process(record("10.00"));

            // The transaction is still written, third and last, exactly as the legacy does.
            Mockito.verify(transactionRepository).save(ArgumentMatchers.any(Transaction.class));
            // The category balance had already been committed before the rewrite was attempted.
            Mockito.verify(categoryBalanceRepository)
                    .save(ArgumentMatchers.any(TransactionCategoryBalance.class));
            // The account itself is not saved, because the rewrite took its invalid-key arm.
            Mockito.verify(accountRepository, Mockito.never())
                    .save(ArgumentMatchers.any(Account.class));
        }

        @Test
        @DisplayName("reject 109 is counted as a posted record, under its own reason label")
        void reason109IsCountedAsPosted() {
            resolving(postableAccount());
            Mockito.when(accountRepository.existsById(ACCT)).thenReturn(Boolean.FALSE);

            processor.process(record("10.00"));

            assertThat(verdictCount("POSTED", "0109")).isEqualTo(1.0d);
            assertThat(verdictCount("POSTED", "NONE")).isEqualTo(0.0d);
            // There is no refused-109 counter at all, not a refused-109 counter reading zero. The
            // pairing cannot arise, and the metric's shape says so.
            assertThat(verdictCounterOrNull("REJECTED", "0109")).isNull();
        }

        @Test
        @DisplayName("109 and 101 stay distinct even though their description text is identical")
        void reason109AndReason101StayDistinct() {
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getDescription())
                    .isEqualTo(DESC_109)
                    .isEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getDescription());
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getReasonCode()).isEqualTo(109);
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getReasonCode()).isEqualTo(101);
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE)
                    .isNotEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_READ);
        }
    }

    @Nested
    @DisplayName("The completion code: a refusal is a partial success, never a failure")
    class CompletionCode {

        @Test
        @DisplayName("a completed step that refused nothing leaves its verdict alone")
        void aCleanRunContributesNoExitStatus() {
            StepExecution stepExecution = stepThatRead(300L, 0L, BatchStatus.COMPLETED);

            assertThat(processor.afterStep(stepExecution)).isNull();
            assertThat(stepExecution.getExecutionContext()
                    .getInt(TransactionValidationProcessor.EXECUTION_CONTEXT_RETURN_CODE))
                    .isZero();
        }

        @Test
        @DisplayName("a completed step that refused something reports the warning-level code")
        void aRunWithRefusalsContributesTheWarningLevelCode() {
            StepExecution stepExecution = stepThatRead(300L, 7L, BatchStatus.COMPLETED);

            ExitStatus contributed = processor.afterStep(stepExecution);

            assertThat(contributed).isNotNull();
            assertThat(contributed.getExitCode())
                    .isEqualTo(TransactionValidationProcessor.EXIT_CODE_REJECTS_PRESENT)
                    .isEqualTo("4");
            assertThat(contributed.getExitDescription()).contains("7");
        }

        @Test
        @DisplayName("the warning-level code outranks a clean completion, so it survives combination")
        void theWarningLevelCodeSurvivesCombinationWithACompletion() {
            ExitStatus contributed = processor.afterStep(stepThatRead(1L, 1L, BatchStatus.COMPLETED));

            assertThat(contributed).isNotNull();
            // This is why the code is contributed only to a step that genuinely completed: combined
            // with any framework status it wins, which is desirable against COMPLETED and disastrous
            // against FAILED.
            assertThat(ExitStatus.COMPLETED.and(contributed).getExitCode()).isEqualTo("4");
        }

        @Test
        @DisplayName("a step that did not complete keeps its own verdict, so a failure is never masked")
        void aFailedStepKeepsItsFailureVerdict() {
            StepExecution stepExecution = stepThatRead(300L, 7L, BatchStatus.FAILED);

            // Nothing is contributed, so whatever verdict the step already carries stands. The legacy
            // program reaches its completion-code line only after a normal end of run.
            assertThat(processor.afterStep(stepExecution)).isNull();
            // The counters are still published, because an operator investigating a failure needs them.
            assertThat(stepExecution.getExecutionContext()
                    .getLong(TransactionValidationProcessor.EXECUTION_CONTEXT_TRANSACTIONS_REJECTED))
                    .isEqualTo(7L);
        }

        @Test
        @DisplayName("a stopped step likewise keeps its own verdict")
        void aStoppedStepKeepsItsOwnVerdict() {
            assertThat(processor.afterStep(stepThatRead(300L, 7L, BatchStatus.STOPPED))).isNull();
        }

        @Test
        @DisplayName("the three run counters are read from the step execution and published")
        void theRunCountersArePublished() {
            StepExecution stepExecution = stepThatRead(300L, 7L, BatchStatus.COMPLETED);

            processor.afterStep(stepExecution);

            assertThat(stepExecution.getExecutionContext()
                    .getLong(TransactionValidationProcessor.EXECUTION_CONTEXT_TRANSACTIONS_PROCESSED))
                    .isEqualTo(300L);
            assertThat(stepExecution.getExecutionContext()
                    .getLong(TransactionValidationProcessor.EXECUTION_CONTEXT_TRANSACTIONS_REJECTED))
                    .isEqualTo(7L);
            assertThat(stepExecution.getExecutionContext()
                    .getLong(TransactionValidationProcessor.EXECUTION_CONTEXT_TRANSACTIONS_POSTED))
                    .isEqualTo(293L);
            assertThat(stepExecution.getExecutionContext()
                    .getInt(TransactionValidationProcessor.EXECUTION_CONTEXT_RETURN_CODE))
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("the posted count is floored at zero rather than published as a negative total")
        void thePostedCountIsFlooredAtZero() {
            StepExecution stepExecution = stepThatRead(1L, 3L, BatchStatus.COMPLETED);

            processor.afterStep(stepExecution);

            assertThat(stepExecution.getExecutionContext()
                    .getLong(TransactionValidationProcessor.EXECUTION_CONTEXT_TRANSACTIONS_POSTED))
                    .isZero();
        }

        @Test
        @DisplayName("evaluating the completion code twice changes nothing")
        void theCompletionCodeRuleIsIdempotent() {
            StepExecution stepExecution = stepThatRead(300L, 7L, BatchStatus.COMPLETED);

            ExitStatus first = processor.afterStep(stepExecution);
            ExitStatus second = processor.afterStep(stepExecution);

            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            assertThat(second.getExitCode()).isEqualTo(first.getExitCode());
            assertThat(stepExecution.getExecutionContext()
                    .getLong(TransactionValidationProcessor.EXECUTION_CONTEXT_TRANSACTIONS_REJECTED))
                    .isEqualTo(7L);
        }

        @Test
        @DisplayName("the counters live on the step execution, so a second execution starts clean")
        void countersAreNotHeldOnTheProcessor() {
            processor.afterStep(stepThatRead(300L, 7L, BatchStatus.COMPLETED));

            StepExecution nextExecution = stepThatRead(10L, 0L, BatchStatus.COMPLETED);
            assertThat(processor.afterStep(nextExecution)).isNull();
            assertThat(nextExecution.getExecutionContext()
                    .getLong(TransactionValidationProcessor.EXECUTION_CONTEXT_TRANSACTIONS_REJECTED))
                    .isZero();
        }

        @Test
        @DisplayName("the exit code is the decimal image of the legacy warning-level completion code")
        void theExitCodeIsDerivedFromTheCompletionCode() {
            assertThat(TransactionValidationProcessor.EXIT_CODE_REJECTS_PRESENT)
                    .isEqualTo(Integer.toString(
                            TransactionPostingService.RETURN_CODE_REJECTS_PRESENT));
        }
    }

    @Nested
    @DisplayName("The posted-record fidelity postconditions")
    class PostedRecordFidelity {

        /** A source record the postconditions are checked against. */
        private DailyTransaction source() {
            return record("10.00");
        }

        /**
         * A transaction that satisfies every postcondition.
         *
         * @param  from the source record it was built from
         * @return the faithful transaction
         */
        private Transaction faithful(final DailyTransaction from) {
            return new Transaction(from.getDalytranId(), TYPE, CAT, "POS TERM  ", "purchase",
                    new BigDecimal("10.00"), "000000123", "MERCHANT NAME", "MERCHANT CITY", "12345",
                    CARD, from.getDalytranOrigTs(), EXPECTED_PROC_TS);
        }

        @Test
        @DisplayName("a faithful record passes every postcondition")
        void aFaithfulRecordPasses() {
            DailyTransaction from = source();

            assertThatCode(() -> TransactionValidationProcessor
                    .requirePostedRecordFidelity(from, faithful(from)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a posted verdict without a transaction is a broken postcondition")
        void anAbsentTransactionIsRefused() {
            DailyTransaction from = source();

            assertThatThrownBy(() -> TransactionValidationProcessor
                    .requirePostedRecordFidelity(from, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("without building a transaction record");
        }

        @Test
        @DisplayName("an altered identifier is refused")
        void anAlteredIdentifierIsRefused() {
            DailyTransaction from = source();
            Transaction altered = faithful(from);
            altered.setTranId("0000000000000002");

            assertThatThrownBy(() -> TransactionValidationProcessor
                    .requirePostedRecordFidelity(from, altered))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TRAN-ID");
        }

        @Test
        @DisplayName("an origination timestamp that has been reformatted is refused")
        void anAlteredOriginationTimestampIsRefused() {
            DailyTransaction from = source();
            Transaction altered = faithful(from);
            altered.setTranOrigTs(ONLINE_FORM_TS);

            assertThatThrownBy(() -> TransactionValidationProcessor
                    .requirePostedRecordFidelity(from, altered))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TRAN-ORIG-TS");
        }

        @Test
        @DisplayName("an origination timestamp that has merely been trimmed is refused")
        void aTrimmedOriginationTimestampIsRefused() {
            DailyTransaction from = new DailyTransaction("0000000000000001", TYPE, CAT, "POS TERM  ",
                    "purchase", new BigDecimal("10.00"), "000000123", "MERCHANT NAME",
                    "MERCHANT CITY", "12345", CARD, "2022-07-19-20.00.00.00    ", BLANK_TS);
            Transaction altered = faithful(from);
            altered.setTranOrigTs("2022-07-19-20.00.00.00");

            assertThatThrownBy(() -> TransactionValidationProcessor
                    .requirePostedRecordFidelity(from, altered))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TRAN-ORIG-TS");
        }

        @Test
        @DisplayName("an amount that is not worth the source amount is refused")
        void anAlteredAmountIsRefused() {
            DailyTransaction from = source();
            Transaction altered = faithful(from);
            altered.setTranAmt(new BigDecimal("10.01"));

            assertThatThrownBy(() -> TransactionValidationProcessor
                    .requirePostedRecordFidelity(from, altered))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TRAN-AMT")
                    // The amount itself is financial data and must not appear in the message.
                    .hasMessageNotContaining("10.01");
        }

        @Test
        @DisplayName("a sign flip on the amount is refused")
        void aSignFlippedAmountIsRefused() {
            DailyTransaction from = source();
            Transaction altered = faithful(from);
            altered.setTranAmt(new BigDecimal("-10.00"));

            assertThatThrownBy(() -> TransactionValidationProcessor
                    .requirePostedRecordFidelity(from, altered))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TRAN-AMT");
        }

        @Test
        @DisplayName("an equal amount at a different scale passes, because the check asserts value")
        void anEqualAmountAtADifferentScalePasses() {
            DailyTransaction from = source();
            Transaction altered = faithful(from);
            altered.setTranAmt(new BigDecimal("10.0000"));

            assertThatCode(() -> TransactionValidationProcessor
                    .requirePostedRecordFidelity(from, altered))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an absent posted amount is refused")
        void anAbsentPostedAmountIsRefused() {
            DailyTransaction from = source();
            Transaction altered = faithful(from);
            altered.setTranAmt(null);

            assertThatThrownBy(() -> TransactionValidationProcessor
                    .requirePostedRecordFidelity(from, altered))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("absent amount");
        }

        @Test
        @DisplayName("an absent source amount is refused rather than handed to the decimal codec")
        void anAbsentSourceAmountIsRefused() {
            DailyTransaction from = source();
            from.setDalytranAmt(null);
            Transaction posted = faithful(from);

            // Checked before the codec is consulted, so the diagnostic names the record rather than
            // surfacing as a rescaling complaint about a value that was never there.
            assertThatThrownBy(() -> TransactionValidationProcessor
                    .requirePostedRecordFidelity(from, posted))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("absent amount");
        }
    }

    @Nested
    @DisplayName("The batch processing-timestamp form check")
    class BatchTimestampForm {

        @Test
        @DisplayName("a well-formed batch timestamp is accepted")
        void aWellFormedValueIsAccepted() {
            assertThatCode(() -> TransactionValidationProcessor
                    .requireBatchTimestampForm(EXPECTED_PROC_TS, "0000000000000001"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an absent value is refused")
        void anAbsentValueIsRefused() {
            assertThatThrownBy(() -> TransactionValidationProcessor
                    .requireBatchTimestampForm(null, "0000000000000001"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("without a processing timestamp");
        }

        @Test
        @DisplayName("a value one byte short is refused on width")
        void aTooShortValueIsRefused() {
            assertThatThrownBy(() -> TransactionValidationProcessor
                    .requireBatchTimestampForm("2022-07-19-23.12.32.45000", "0000000000000001"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("encoded bytes");
        }

        @Test
        @DisplayName("a value one byte long is refused on width")
        void aTooLongValueIsRefused() {
            assertThatThrownBy(() -> TransactionValidationProcessor
                    .requireBatchTimestampForm("2022-07-19-23.12.32.4500000", "0000000000000001"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("encoded bytes");
        }

        @Test
        @DisplayName("the online timestamp form is refused, though it is exactly the right width")
        void theOnlineFormIsRefused() {
            assertThat(ONLINE_FORM_TS.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(BATCH_TIMESTAMP_WIDTH);

            assertThatThrownBy(() -> TransactionValidationProcessor
                    .requireBatchTimestampForm(ONLINE_FORM_TS, "0000000000000001"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not in the batch form")
                    // The first divergence is the separator at index ten, where the batch form has a
                    // hyphen and the online form has a space.
                    .hasMessageContaining("position 10");
        }

        @Test
        @DisplayName("a colon substituted for a dot is refused")
        void aColonForADotIsRefused() {
            assertThatThrownBy(() -> TransactionValidationProcessor
                    .requireBatchTimestampForm("2022-07-19-23:12.32.450000", "0000000000000001"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("position 13");
        }

        @Test
        @DisplayName("a non-ASCII digit is refused, so no locale-aware digit test is in play")
        void aNonAsciiDigitIsRefused() {
            // A fullwidth digit, written as an escape so this file stays pure ASCII. It occupies one
            // character and encodes to one replacement byte, so it satisfies both width checks and is
            // caught only by the positional digit test.
            String fullwidthLeadingDigit = "\uFF12" + "022-07-19-23.12.32.450000";
            assertThat(fullwidthLeadingDigit.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(BATCH_TIMESTAMP_WIDTH);

            assertThatThrownBy(() -> TransactionValidationProcessor
                    .requireBatchTimestampForm(fullwidthLeadingDigit, "0000000000000001"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("position 0");
        }

        @Test
        @DisplayName("a value at the right byte width but the wrong character count is refused")
        void aRightWidthWrongCharacterCountValueIsRefused() {
            // A supplementary code point occupies two characters and encodes to a single replacement
            // byte, so this value satisfies the byte-width check and still holds twenty-seven
            // characters. The character-count check is what stands between such a value and a
            // positional comparison that would read past the end of the template.
            String supplementaryTailed = "022-07-19-23.12.32.450000" + "\uD83D\uDE00";
            assertThat(supplementaryTailed.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(BATCH_TIMESTAMP_WIDTH);
            assertThat(supplementaryTailed).hasSize(BATCH_TIMESTAMP_WIDTH + 1);

            assertThatThrownBy(() -> TransactionValidationProcessor
                    .requireBatchTimestampForm(supplementaryTailed, "0000000000000001"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("characters");
        }

        @Test
        @DisplayName("a letter where a digit belongs is refused")
        void aLetterForADigitIsRefused() {
            assertThatThrownBy(() -> TransactionValidationProcessor
                    .requireBatchTimestampForm("202X-07-19-23.12.32.450000", "0000000000000001"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("position 3");
        }

        @Test
        @DisplayName("any digits are accepted where the template holds digits")
        void anyDigitsAreAcceptedInTheDigitPositions() {
            assertThatCode(() -> TransactionValidationProcessor
                    .requireBatchTimestampForm("9999-99-99-99.99.99.990000", "0000000000000001"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Construction, framework contract and instrumentation")
    class ConstructionAndInstrumentation {

        @Test
        @DisplayName("an absent posting service is refused at construction")
        void anAbsentServiceIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionValidationProcessor(null, meterRegistry))
                    .withMessageContaining("posting service");
        }

        @Test
        @DisplayName("an absent meter registry is refused at construction")
        void anAbsentRegistryIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionValidationProcessor(postingService, null))
                    .withMessageContaining("meter registry");
        }

        @Test
        @DisplayName("an absent record is refused, since the framework contract forbids one")
        void anAbsentRecordIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> processor.process(null))
                    .withMessageContaining("null daily-transaction record");
        }

        @Test
        @DisplayName("an absent step execution is refused")
        void anAbsentStepExecutionIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> processor.afterStep(null))
                    .withMessageContaining("step execution");
        }

        @Test
        @DisplayName("beginning a step needs nothing, because nothing is reset")
        void beginningAStepIsANoOp() {
            StepExecution stepExecution = stepThatRead(0L, 0L, BatchStatus.STARTED);

            assertThatCode(() -> processor.beforeStep(stepExecution)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the legacy identifiers name the program, its job and its one application step")
        void theLegacyIdentifiersArepublished() {
            assertThat(TransactionValidationProcessor.LEGACY_PROGRAM).isEqualTo("CBTRN02C");
            assertThat(TransactionValidationProcessor.LEGACY_JOB).isEqualTo("POSTTRAN");
            assertThat(TransactionValidationProcessor.LEGACY_STEP).isEqualTo("STEP15");
        }

        @Test
        @DisplayName("a posted record is counted under no reason, and timed as posted")
        void aPostedRecordIsCountedAndTimed() {
            resolving(postableAccount());

            processor.process(record("10.00"));

            assertThat(verdictCount("POSTED", "NONE")).isEqualTo(1.0d);
            assertThat(meterRegistry.get(METRIC_RECORD_TIMER).tag(TAG_OUTCOME, "POSTED").timer()
                    .count()).isEqualTo(1L);
            assertThat(meterRegistry.get(METRIC_RECORD_TIMER).tag(TAG_OUTCOME, "REJECTED").timer()
                    .count()).isZero();
        }

        @Test
        @DisplayName("a refused record is counted under its four-digit reason, and timed as refused")
        void aRefusedRecordIsCountedAndTimed() {
            Mockito.when(cardCrossReferenceRepository.findById(UNKNOWN_CARD))
                    .thenReturn(Optional.empty());

            processor.process(recordOn("10.00", UNKNOWN_CARD));

            // The trailer's own four-digit rendering, so a dashboard label and a reject record agree.
            assertThat(verdictCount("REJECTED", "0100")).isEqualTo(1.0d);
            assertThat(verdictCount("POSTED", "NONE")).isZero();
            assertThat(meterRegistry.get(METRIC_RECORD_TIMER).tag(TAG_OUTCOME, "REJECTED").timer()
                    .count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("every verdict meter exists before the first record, so none is created per record")
        void everyVerdictMeterIsPreRegistered() {
            assertThat(verdictCount("POSTED", "NONE")).isZero();
            assertThat(verdictCount("POSTED", "0109")).isZero();
            assertThat(verdictCount("REJECTED", "0100")).isZero();
            assertThat(verdictCount("REJECTED", "0101")).isZero();
            assertThat(verdictCount("REJECTED", "0102")).isZero();
            assertThat(verdictCount("REJECTED", "0103")).isZero();
            assertThat(meterRegistry.get(METRIC_RECORD_TIMER).tag(TAG_OUTCOME, "POSTED").timer()
                    .count()).isZero();
            assertThat(meterRegistry.get(METRIC_RECORD_TIMER).tag(TAG_OUTCOME, "REJECTED").timer()
                    .count()).isZero();
            assertThat(meterRegistry.get(METRIC_RECORD_TIMER).tag(TAG_OUTCOME, "FAILED").timer()
                    .count()).isZero();
        }

        @Test
        @DisplayName("processing several records in a row keeps each verdict independent")
        void severalRecordsKeepIndependentVerdicts() {
            resolving(postableAccount());
            Mockito.when(cardCrossReferenceRepository.findById(UNKNOWN_CARD))
                    .thenReturn(Optional.empty());

            // A refusal must not leave a reason behind that decides the next record's verdict, which is
            // exactly what a reason held in a field would do.
            assertThat(processor.process(recordOn("10.00", UNKNOWN_CARD))).isNotNull();
            assertThat(processor.process(record("10.00"))).isNull();
            assertThat(processor.process(recordOn("10.00", UNKNOWN_CARD))).isNotNull();
            assertThat(processor.process(record("10.00"))).isNull();

            assertThat(verdictCount("REJECTED", "0100")).isEqualTo(2.0d);
            assertThat(verdictCount("POSTED", "NONE")).isEqualTo(2.0d);
        }
    }
}
