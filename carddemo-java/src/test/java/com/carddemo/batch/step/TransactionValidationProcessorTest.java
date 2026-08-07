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
import com.carddemo.repository.RecordWriter;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.AbendService;
import com.carddemo.service.PostingRecordTransactionBoundary;
import com.carddemo.service.TransactionPostingService;
import com.carddemo.support.SeededRecordFixture;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
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
 * Verifies the per-record contract of {@link TransactionValidationProcessor}, the posting step's verdict
 * router: that the validation cascade's refusals become reject items and its successes become nothing at
 * all, that the counter-intuitive behaviours of the legacy posting program survive the adapter, and that
 * a refused record produces a partial success rather than a failure.
 *
 * <h2>The legacy authority</h2>
 *
 * <p>The behaviour asserted here is the validation cascade and posting path of {@code app/cbl/CBTRN02C.cbl}
 * - 731 lines and 26 procedure paragraphs - driven by {@code app/jcl/POSTTRAN.jcl}, whose single
 * application step is {@code STEP15}. Four copybooks supply the record layouts the cascade reads:
 * {@code app/cpy/CVTRA06Y.cpy} for the 350-byte daily-transaction record, {@code app/cpy/CVACT01Y.cpy} for
 * the 300-byte account record, {@code app/cpy/CVACT03Y.cpy} for the 50-byte card cross-reference and
 * {@code app/cpy/CVTRA01Y.cpy} for the 50-byte category balance. Every one of them is cited and none is
 * transcribed: no {@code PIC} clause, no paragraph body, no level-number line and no cluster definition
 * appears anywhere in this file. What does appear is metadata - reject codes, field widths, byte offsets,
 * record counts, member and step and DD names - and the five reject description texts, which are not
 * source text at all but contractual <em>output</em> an operator reads.
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
 * where a plausible, compiling, wrong adapter would break it. No collaborator is reimplemented here;
 * the repositories are Mockito doubles and the verdicts are read through the processor's own return
 * value and through interaction verification.
 *
 * <p>Every expected reject code, description, basis and timestamp image is a literal declared in this
 * class, so the oracle is independent of the code it judges: no expected value is obtained by calling the
 * processor, the posting service, the reject-reason enumeration, the decimal codec, the reject-record
 * writer, the batch timestamp helper or any record mapper. Amounts are written as decimal literals with
 * their scale visible, because the scale is part of what is under test, and no value in this file is
 * rescaled - scale-two truncation belongs to the module's decimal codec and to nowhere else.
 *
 * <p>The clock is fixed, so the regenerated processing timestamp is compared against an exact expected
 * string rather than matched against a pattern. Nothing here uses reflection, mocks a static call, or
 * inspects a private member; the two package-visible fidelity checks are called directly because they
 * guard postconditions that the production path cannot be made to breach.
 *
 * <h2>The parity decisions this suite exists to hold, each a decision-log candidate</h2>
 *
 * <ul>
 *   <li><strong>Arithmetic truncates toward zero, and is never rounded.</strong> A search for a rounding
 *       directive across every program and every copybook of the estate returns zero occurrences, so every
 *       arithmetic store truncates. The module therefore truncates with {@code RoundingMode.DOWN}, and
 *       half-even and half-up are both wrong here: either would differ by a cent on roughly half of all
 *       computations, and a cent decides whether a record is refused as over limit.</li>
 *   <li><strong>The overlimit basis is evaluated strictly left to right and is never rearranged.</strong>
 *       It is current-cycle credit, minus current-cycle debit, plus the transaction amount, stored into a
 *       field of nine integer digits and two decimals. Truncation on that store makes the arithmetic
 *       non-associative, so an algebraically identical reordering can move the result by a cent and change
 *       which records reject.</li>
 *   <li><strong>Equality passes the overlimit test.</strong> The legacy condition is "credit limit is
 *       greater than or equal to the basis", so a record whose basis lands exactly on the limit is
 *       admitted, not refused.</li>
 *   <li><strong>Reject 103 overwrites reject 102.</strong> The two tests are consecutive unguarded blocks
 *       with no {@code ELSE} between them and no early exit, so a record that is both over limit and past
 *       expiry ends carrying 103. Refactoring the pair into an else-if, a predicate list, a sorted rule
 *       registry, a stream of validators or a first-failure-wins short circuit would emit 102 for such a
 *       record and break the expected reject dataset byte for byte.</li>
 *   <li><strong>Reject 100 short-circuits the account lookup entirely.</strong> The account stage runs
 *       only while the reason is still zero, and the else-arm of that guard is a bare continuation, so an
 *       unresolvable card number never causes an account read. Asserted by verifying that the account
 *       repository is not touched at all, which is stronger than checking the emitted code.</li>
 *   <li><strong>The expiry comparison reads the ORIGINATION timestamp, not the processing timestamp, and
 *       compares characters rather than dates.</strong> Both operands are fixed-width ten-character views
 *       of zero-padded text, so their character order and their calendar order coincide; no date is
 *       parsed, no calendar type is constructed and no time zone can intrude.</li>
 *   <li><strong>The account expiration field is misspelled in the copybook</strong>, at
 *       {@code app/cpy/CVACT01Y.cpy} <strong>line 11</strong>. An earlier planning document cites line 10;
 *       line 11 is the verified position. The Java property spells the name correctly while the record
 *       layout keeps the original spelling, so the mapper's zero-based offset 58 and width 10 are
 *       untouched and only the name differs.</li>
 *   <li><strong>A negative amount is added to the debit accumulator unchanged, driving it negative.</strong>
 *       The legacy adds the amount to the current balance, then adds it to the cycle credit when it is
 *       non-negative and to the cycle debit otherwise - without negating it and without taking its
 *       magnitude. Correcting the sign "for readability" would silently change every cycle total the
 *       account reports.</li>
 *   <li><strong>The three posting stages run as category balance, then account, then transaction.</strong>
 *       The online bill-payment flow uses the opposite order; the two are deliberately not unified.</li>
 *   <li><strong>A missing category-balance row is not an error: it is created.</strong> The legacy status
 *       test accepts record-not-found alongside success, so the row is created and the record still posts.
 *       Record-not-found is one of only three raw file statuses compared anywhere in the estate; the
 *       compared set is exactly success, end-of-file and record-not-found. An earlier specification section
 *       cites two further statuses - a file-not-found code and a duplicate-key code - that are compared
 *       <em>nowhere</em> in the source, so no test in this file depends on either of them, and the
 *       discrepancy is recorded here rather than resolved by inventing a path for them.</li>
 *   <li><strong>Reject 109 is inert: set, and never emitted.</strong> It is assigned only on the failure
 *       arm of the account rewrite, which sits downstream of the mainline's decision to post, after the
 *       category balance has already been committed, and is followed by the transaction write proceeding
 *       anyway. So no reject record bearing 109 is ever produced. It keeps its own identity - and its own
 *       description, byte-identical to reject 101's - because the two arise from different operations at
 *       different points. No synthetic path that emits it is invented here.</li>
 *   <li><strong>A reject is a partial success.</strong> The legacy raises its completion code to the
 *       warning level when anything was refused, having already closed every file and reported both
 *       counters. The step and the job both complete, the exit status conveys that code, and nothing is
 *       thrown; a reject is never fatal and never marks the step failed.</li>
 *   <li><strong>The origination timestamp is copied verbatim and the processing timestamp is
 *       regenerated</strong>, in the batch 26-character form: four year digits, a hyphen, two month
 *       digits, a hyphen, two day digits, a <strong>hyphen at one-based position 11</strong>, two hour
 *       digits, a dot, two minute digits, a dot, two second digits, a dot, two hundredths digits, and a
 *       <strong>literal {@code 0000} at one-based positions 23 to 26</strong>. The online tier builds a
 *       twenty-six character timestamp too and it is a different format - a space where this has its third
 *       hyphen, colons where this has dots, and a six-digit fraction - so a value of exactly the right
 *       width can still be entirely wrong. Every timestamp asserted here is driven from a fixed clock.</li>
 *   <li><strong>The inbound processing timestamp arrives blank and stays blank.</strong> Every one of the
 *       three hundred seeded records carries twenty-six spaces in that field, so a copied-unchanged reject
 *       image must carry twenty-six spaces there - not an absent value, not an empty string, and not a
 *       rendered date.</li>
 * </ul>
 *
 * <h2>What the seeded fixtures can and cannot reach</h2>
 *
 * <p>Measured from the nine named fixtures rather than assumed: every seeded card resolves through the
 * cross-reference and every seeded cross-reference names an account that exists, so rejects 100 and 101
 * are <strong>data-unreachable</strong> from the seed; every seeded account expires later than the single
 * origination date all three hundred seeded records share, so reject 103 is data-unreachable too; and
 * reject 109 is <strong>control-flow unreachable</strong> for the reason above. Only reject 102 is
 * producible from unmodified fixtures, and only by constructing a balance-to-limit relationship that trips
 * the basis - which on seeded data reduces to the amount alone, because all fifty seeded accounts carry
 * both cycle accumulators at zero and all fifty seeded category balances decode to zero.
 *
 * <p>The three unreachable rejects are therefore reached here through separately constructed records. That
 * is legitimate rather than a contrivance: the daily-transaction table carries <strong>zero foreign
 * keys</strong>, deliberately and by documented design - six foreign keys exist in the schema and not one
 * of them is on that table - so nothing prevents a daily transaction from naming a card, an account or a
 * customer that does not exist, which is exactly the condition rejects 100 and 101 report. The reachability
 * group below asserts these facts against the fixtures themselves, read from the test classpath, so that a
 * future fixture change which silently made any of the three seed-reachable would be noticed here rather
 * than discovered as a byte difference one job away.
 *
 * <p>Provenance of the expectations, as a traceability-matrix header string and nothing more: the legacy
 * estate at commit SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. <strong>That stamp is not universal and must
 * never be asserted per member</strong>: seventy-eight members carry it, three carry later stamps, all
 * seventeen screen definitions differ from it, and twenty-five carry none at all. It identifies the
 * release the matrix as a whole was taken against, so it belongs in a matrix header and in prose such as
 * this - never in an assertion about any individual artefact.
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

    /**
     * The batch processing-timestamp form written out position by position, as an independent oracle.
     *
     * <p>{@code y} marks a year digit, {@code M} a month digit, {@code d} a day digit, {@code H} an hour
     * digit, {@code m} a minute digit, {@code s} a second digit and {@code S} a hundredths digit. Every
     * other character is the literal the form holds at that position, so the string carries the hyphen at
     * one-based position 11 and the literal {@code 0000} at one-based positions 23 to 26 explicitly.
     *
     * <p>Typed out here rather than obtained from the production formatter on purpose: an expectation
     * produced by the code it judges cannot detect a defect in that code.
     */
    private static final String BATCH_TIMESTAMP_SHAPE = "yyyy-MM-dd-HH.mm.ss.SS0000";

    /** The literal tail the batch form always holds at one-based positions 23 to 26. */
    private static final String BATCH_TIMESTAMP_TAIL = "0000";

    /** Zero-based index of the third separator, which the batch form spells as a hyphen. */
    private static final int BATCH_TIMESTAMP_THIRD_SEPARATOR_INDEX = 10;

    /** Zero-based index at which the literal tail begins. */
    private static final int BATCH_TIMESTAMP_TAIL_INDEX = 22;

    private static final String DESC_100 = "INVALID CARD NUMBER FOUND";

    private static final String DESC_101 = "ACCOUNT RECORD NOT FOUND";

    private static final String DESC_102 = "OVERLIMIT TRANSACTION";

    private static final String DESC_103 = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";

    private static final String DESC_109 = "ACCOUNT RECORD NOT FOUND";

    /** Encoded width of the reject 100 description text. */
    private static final int DESC_100_WIDTH = 25;

    /** Encoded width of the reject 101 description text. */
    private static final int DESC_101_WIDTH = 24;

    /** Encoded width of the reject 102 description text. */
    private static final int DESC_102_WIDTH = 21;

    /** Encoded width of the reject 103 description text. */
    private static final int DESC_103_WIDTH = 42;

    /** Encoded width of the reject 109 description text, equal to reject 101's by contract. */
    private static final int DESC_109_WIDTH = 24;

    /** Metric recording per-record verdicts. */
    private static final String METRIC_RECORDS = "carddemo.batch.posting.records";

    /** Metric timing the per-record cascade call. */
    private static final String METRIC_RECORD_TIMER = "carddemo.batch.posting.record";

    private static final String TAG_OUTCOME = "outcome";

    private static final String TAG_REASON = "reason";

    // ---------------------------------------------------------------------------------------------
    // The seeded fixtures, by name and by measured geometry.
    //
    // These are the nine files the plan names, in the copies that sit on the test classpath. They are
    // read here through the shared fixture reader, which resolves them as a classpath resource - no
    // repository path is named, and the legacy tree is never touched at run time. The widths are the
    // record lengths the copybooks declare, and the offsets are zero-based positions within those
    // records; both are metadata about a layout rather than layout code, which lives in the utility
    // package and is deliberately not called from here.
    // ---------------------------------------------------------------------------------------------

    /** The daily-transaction fixture, the primary posting input. */
    private static final String DAILY_TRANSACTION_FIXTURE = "dailytran.txt";

    /** The account fixture. */
    private static final String ACCOUNT_FIXTURE = "acctdata.txt";

    /** The card cross-reference fixture. */
    private static final String CARD_XREF_FIXTURE = "cardxref.txt";

    /** The category-balance fixture. */
    private static final String CATEGORY_BALANCE_FIXTURE = "tcatbal.txt";

    /** Record width of the daily-transaction layout. */
    private static final int DAILY_TRANSACTION_WIDTH = 350;

    /** Record width of the account layout. */
    private static final int ACCOUNT_WIDTH = 300;

    /** Data width of the card cross-reference layout, its 50 bytes less a 14-byte trailing filler. */
    private static final int CARD_XREF_DATA_WIDTH = 36;

    /** Record width of the category-balance layout. */
    private static final int CATEGORY_BALANCE_WIDTH = 50;

    /** Seeded record counts, one per fixture. */
    private static final int SEEDED_DAILY_TRANSACTIONS = 300;

    /** Seeded account, cross-reference and category-balance counts, which coincide at fifty. */
    private static final int SEEDED_ACCOUNTS = 50;

    /** Width of the daily-transaction business key, which is the posted transaction's identity. */
    private static final int DALYTRAN_ID_WIDTH = 16;

    /** Zero-based offset and width of the daily-transaction source field. */
    private static final int DALYTRAN_SOURCE_OFFSET = 22;

    private static final int DALYTRAN_SOURCE_WIDTH = 10;

    /** Zero-based offset and width of the daily-transaction amount, whose last byte carries the sign. */
    private static final int DALYTRAN_AMOUNT_OFFSET = 132;

    private static final int DALYTRAN_AMOUNT_WIDTH = 11;

    /** Zero-based offset and width of the daily-transaction card number. */
    private static final int DALYTRAN_CARD_OFFSET = 262;

    private static final int DALYTRAN_CARD_WIDTH = 16;

    /** Zero-based offset of the daily-transaction origination timestamp. */
    private static final int DALYTRAN_ORIG_TS_OFFSET = 278;

    /** Zero-based offset of the daily-transaction processing timestamp. */
    private static final int DALYTRAN_PROC_TS_OFFSET = 304;

    /** Zero-based offset and width of the account expiration date, the misspelled field of line 11. */
    private static final int ACCT_EXPIRATION_OFFSET = 58;

    private static final int ACCT_EXPIRATION_WIDTH = 10;

    /** Zero-based offsets and width of the two account cycle accumulators. */
    private static final int ACCT_CYCLE_CREDIT_OFFSET = 78;

    private static final int ACCT_CYCLE_DEBIT_OFFSET = 90;

    private static final int ACCT_MONEY_WIDTH = 12;

    /** Zero-based offset and width of the account business key. */
    private static final int ACCT_ID_OFFSET = 0;

    private static final int ACCT_ID_WIDTH = 11;

    /** Zero-based offset and width of the cross-reference card number. */
    private static final int XREF_CARD_OFFSET = 0;

    private static final int XREF_CARD_WIDTH = 16;

    /** Zero-based offset and width of the cross-reference account identifier. */
    private static final int XREF_ACCT_OFFSET = 25;

    private static final int XREF_ACCT_WIDTH = 11;

    /** Zero-based offset and width of the category balance. */
    private static final int TCATBAL_BALANCE_OFFSET = 17;

    private static final int TCATBAL_BALANCE_WIDTH = 11;

    /** The number of characters of the origination timestamp the expiry comparison reads. */
    private static final int EXPIRY_COMPARISON_WIDTH = 10;

    /**
     * The zoned-decimal image of positive zero at the width of an account money field.
     *
     * <p>Hand-encoded rather than obtained from the decimal codec. In the overpunched sign convention the
     * final byte carries both the low-order digit and the sign: for a positive value digit zero is written
     * as an opening brace and digits one through nine as the letters A through I, and for a negative value
     * digit zero is written as a closing brace and digits one through nine as the letters J through R. So
     * eleven leading zeros followed by an opening brace is a twelve-byte field holding positive zero, and
     * the two sign alphabets are declared in full below.
     */
    private static final String ZONED_POSITIVE_ZERO_MONEY = "00000000000{";

    /** The zoned-decimal image of positive zero at the width of a category-balance field. */
    private static final String ZONED_POSITIVE_ZERO_BALANCE = "0000000000{";

    /** The ten positive overpunch sign bytes, digit zero through digit nine in order. */
    private static final String POSITIVE_OVERPUNCH_BYTES = "{ABCDEFGHI";

    /** The ten negative overpunch sign bytes, digit zero through digit nine in order. */
    private static final String NEGATIVE_OVERPUNCH_BYTES = "}JKLMNOPQR";

    /** The point-of-sale source literal, space-padded to the width of the source field. */
    private static final String SOURCE_POINT_OF_SALE = "POS TERM  ";

    /** The operator-originated source literal, space-padded to the width of the source field. */
    private static final String SOURCE_OPERATOR = "OPERATOR  ";

    /** How many of the three hundred seeded records carry a positive amount. */
    private static final int SEEDED_POSITIVE_AMOUNTS = 250;

    /** How many of the three hundred seeded records carry a negative amount. */
    private static final int SEEDED_NEGATIVE_AMOUNTS = 50;

    private DailyTransactionRepository dailyTransactionRepository;

    private TransactionRepository transactionRepository;

    private AccountRepository accountRepository;

    private CardCrossReferenceRepository cardCrossReferenceRepository;

    private TransactionCategoryBalanceRepository categoryBalanceRepository;

    private RecordWriter recordWriter;

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
        recordWriter = Mockito.mock(RecordWriter.class, invocation -> {
            if (invocation.getMethod().getName().equals("insert")) {
                return invocation.getArgument(0);
            }
            return null;
        });
        postingService = new TransactionPostingService(dailyTransactionRepository,
                transactionRepository, accountRepository, cardCrossReferenceRepository,
                categoryBalanceRepository, recordWriter, new AbendService(),
                new PostingRecordTransactionBoundary(),
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
        Mockito.when(accountRepository.rewritePostingBalances(
                        ArgumentMatchers.eq(ACCT),
                        ArgumentMatchers.anyLong(),
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
        Mockito.verify(transactionRepository).insertAndFlush(captor.capture());
        return captor.getValue();
    }

    /**
     * Captures the account the cascade saved.
     *
     * @return the saved account
     */
    private Account captureSavedAccount() {
        ArgumentCaptor<BigDecimal> currentBalance = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> currentCycleCredit = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> currentCycleDebit = ArgumentCaptor.forClass(BigDecimal.class);
        Mockito.verify(accountRepository).rewritePostingBalances(ArgumentMatchers.eq(ACCT),
                ArgumentMatchers.anyLong(), currentBalance.capture(), currentCycleCredit.capture(),
                currentCycleDebit.capture());
        return account(currentBalance.getValue().toPlainString(), "99999.00",
                currentCycleCredit.getValue().toPlainString(),
                currentCycleDebit.getValue().toPlainString(), "2099-01-01");
    }

    /**
     * Captures the category balance the cascade saved.
     *
     * @return the saved category balance
     */
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
        @DisplayName("the overlimit boundary at EXACT EQUALITY passes: a basis equal to the limit is"
                + " admitted, never refused")
        void theOverlimitBoundaryAtExactEqualityPasses() {
            // 900.00 - 100.00 + 200.54 = 1000.54, which is exactly the limit. The legacy condition is
            // "limit is greater than OR EQUAL TO the basis", so equality is the passing side of the
            // boundary. A strict comparison would refuse this record and emit a reject 102 the legacy
            // never emits, and no other test in this file would notice.
            resolving(account("0.00", "1000.54", "900.00", "100.00", "2099-01-01"));

            assertThat(processor.process(record("200.54"))).isNull();
        }

        @Test
        @DisplayName("a record one cent under the limit is not reject 102")
        void oneCentUnderTheLimitPosts() {
            // 900.00 - 100.00 + 200.53 = 1000.53, one cent below the 1000.54 limit.
            resolving(account("0.00", "1000.54", "900.00", "100.00", "2099-01-01"));

            assertThat(processor.process(record("200.53"))).isNull();
        }

        // -----------------------------------------------------------------------------------------
        // The overlimit basis, pinned by bracketing rather than by inspection.
        //
        // The basis is hand-computed in each comment below and never obtained from the code that
        // computes it. Two tests bracket each figure - one at a limit exactly equal to it, which must
        // admit the record, and one a single cent lower, which must refuse it - and together they pin
        // the basis to one cent. That is what rules out every rearrangement at once, because each
        // rearrangement moves the basis and so breaks one side of the bracket.
        // -----------------------------------------------------------------------------------------

        @Test
        @DisplayName("the basis is cycle credit MINUS cycle debit PLUS amount: at a limit equal to it,"
                + " the record is admitted")
        void theBasisInLegacyOrderIsAdmittedAtAnEqualLimit() {
            // Three deliberately asymmetric operands - cycle credit 500.00, cycle debit 200.00, amount
            // 300.01 - evaluated in the legacy order:
            //     500.00 - 200.00 + 300.01 = 600.01
            // Adding the debit instead of subtracting it would give 1000.01 and refuse this record.
            resolving(account("0.00", "600.01", "500.00", "200.00", "2099-01-01"));

            assertThat(processor.process(record("300.01"))).isNull();
        }

        @Test
        @DisplayName("the basis is cycle credit MINUS cycle debit PLUS amount: one cent lower, the same"
                + " record is reject 102")
        void theBasisInLegacyOrderIsRefusedOneCentLower() {
            // The same three operands and the same basis of 600.01, against a limit of 600.00. Swapping
            // the two accumulators would give 0.01 and subtracting the amount instead of adding it would
            // give -0.01; either would admit this record and so break this assertion.
            resolving(account("0.00", "600.00", "500.00", "200.00", "2099-01-01"));

            RejectRecordWriter.RejectedTransaction refused = processor.process(record("300.01"));

            assertThat(refused).isNotNull();
            assertThat(refused.reason().getReasonCode()).isEqualTo(102);
            assertThat(refused.reason().getDescription()).isEqualTo(DESC_102);
        }

        @Test
        @DisplayName("truncation happens once at the end: at a limit equal to the single-truncation"
                + " basis, the record is admitted")
        void truncationHappensOnceAtTheEndAdmittingAtTheEqualLimit() {
            // A sub-cent operand is the only probe that can make the point of truncation observable,
            // because decimal arithmetic is otherwise exact and rearranging an exact expression cannot
            // change its value. Cycle credit 0.005, cycle debit 0.00 and an amount of 1000.539 sum
            // exactly to 1000.544, and truncating that once toward zero gives
            //     1000.54
            // An implementation that brought each intermediate result to two decimals as it went would
            // compute 0.00 for the accumulator difference and finish a cent lower, at 1000.53.
            resolving(account("0.00", "1000.54", "0.005", "0.00", "2099-01-01"));

            assertThat(processor.process(record("1000.539"))).isNull();
        }

        @Test
        @DisplayName("truncation happens once at the end: one cent lower the record is reject 102,"
                + " which a per-step truncation would have admitted")
        void truncationHappensOnceAtTheEndRefusingOneCentLower() {
            // The same operands, the same single-truncation basis of 1000.54, against a limit of
            // 1000.53. This is the assertion where the two implementations genuinely disagree about the
            // outcome and not merely about a figure: the legacy refuses the record, while a variant that
            // truncated after every step would have computed 1000.53, found it equal to the limit, and
            // admitted it.
            resolving(account("0.00", "1000.53", "0.005", "0.00", "2099-01-01"));

            RejectRecordWriter.RejectedTransaction refused = processor.process(record("1000.539"));

            assertThat(refused).isNotNull();
            assertThat(refused.reason().getReasonCode()).isEqualTo(102);
        }

        @Test
        @DisplayName("the basis truncates toward zero and is never rounded, half-up or half-even")
        void theBasisTruncatesTowardZeroAndIsNeverRounded() {
            // Cycle credit 0.005, cycle debit 0.00 and an amount of 1000.540 sum exactly to 1000.545 -
            // the midpoint, where truncation and rounding part company. Truncating toward zero gives
            //     1000.54
            // and either half-up or half-even would give 1000.55. At a limit of exactly 1000.54 the
            // truncating basis is admitted and a rounded one would be refused, so this single assertion
            // discriminates the mode. Truncation is the right mode because the estate specifies no
            // rounding directive anywhere: a search across every program and every copybook returns
            // none, and an arithmetic store without one truncates.
            resolving(account("0.00", "1000.54", "0.005", "0.00", "2099-01-01"));

            assertThat(processor.process(record("1000.540"))).isNull();
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
        @DisplayName("the expiry test reads the ORIGINATION timestamp: moving only the processing"
                + " timestamp changes nothing at all")
        void theExpiryTestIgnoresTheProcessingTimestamp() {
            // The account expires on the very day the record originated, which the comparison admits.
            // The processing timestamp is then set to a date decades earlier - a value that would refuse
            // the record outright if the comparison read that field instead - and the record is still
            // admitted. The same probe is repeated with a date decades later, so neither direction of
            // movement in the processing field can be mistaken for indifference to it.
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2022-07-19"));

            DailyTransaction farPast = record("10.00");
            farPast.setDalytranProcTs("1970-01-01-00.00.00.000000");
            assertThat(processor.process(farPast)).isNull();

            DailyTransaction farFuture = record("10.00");
            farFuture.setDalytranProcTs("2099-12-31-23.59.59.990000");
            assertThat(processor.process(farFuture)).isNull();
        }

        @Test
        @DisplayName("the expiry test reads the ORIGINATION timestamp: moving only that field flips the"
                + " outcome")
        void theExpiryTestIsDecidedByTheOriginationTimestamp() {
            // Against the same account expiring on 2022-07-19, one record originates that day and posts,
            // and one originates the day after and is refused with 103. Only the origination field
            // differs between the two, so it and nothing else decides the outcome.
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2022-07-19"));

            DailyTransaction onExpiryDay = record("10.00");
            onExpiryDay.setDalytranOrigTs("2022-07-19-20.00.00.000000");
            assertThat(processor.process(onExpiryDay)).isNull();

            DailyTransaction dayAfterExpiry = record("10.00");
            dayAfterExpiry.setDalytranOrigTs("2022-07-20-20.00.00.000000");
            RejectRecordWriter.RejectedTransaction refused = processor.process(dayAfterExpiry);

            assertThat(refused).isNotNull();
            assertThat(refused.reason().getReasonCode()).isEqualTo(103);
            assertThat(refused.reason().getDescription()).isEqualTo(DESC_103);
        }

        @Test
        @DisplayName("only the first ten characters of the origination timestamp are read")
        void onlyTheFirstTenCharactersOfTheOriginationTimestampAreRead() {
            // Two records originating on the same day at wildly different times of day, both admitted
            // against an account expiring that day. Everything past the tenth character is invisible to
            // the comparison, which is why the ten-character view is taken rather than the whole field.
            resolving(account("0.00", "99999.00", "0.00", "0.00", "2022-07-19"));

            DailyTransaction firstMoment = record("10.00");
            firstMoment.setDalytranOrigTs("2022-07-19-00.00.00.000000");
            assertThat(processor.process(firstMoment)).isNull();

            DailyTransaction lastMoment = record("10.00");
            lastMoment.setDalytranOrigTs("2022-07-19-23.59.59.990000");
            assertThat(processor.process(lastMoment)).isNull();

            assertThat(firstMoment.getDalytranOrigTs().substring(0, EXPIRY_COMPARISON_WIDTH))
                    .isEqualTo(lastMoment.getDalytranOrigTs().substring(0, EXPIRY_COMPARISON_WIDTH));
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
            Mockito.verify(transactionRepository)
                    .insertAndFlush(ArgumentMatchers.any(Transaction.class));
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
        @DisplayName("the regenerated processing timestamp matches the batch form position by position,"
                + " hyphen at 11 and literal 0000 at 23 to 26 included")
        void theProcessingTimestampMatchesTheBatchFormPositionByPosition() {
            resolving(postableAccount());

            processor.process(record("10.00"));
            String procTs = capturePostedTransaction().getTranProcTs();

            // Position by position against a shape written out by hand rather than rendered by the
            // production formatter. Where the shape names a field the value must hold an ASCII digit,
            // and where it holds a separator or the literal tail the value must hold the same character.
            assertThat(BATCH_TIMESTAMP_SHAPE.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(BATCH_TIMESTAMP_WIDTH);
            for (int index = 0; index < BATCH_TIMESTAMP_WIDTH; index++) {
                char shaped = BATCH_TIMESTAMP_SHAPE.charAt(index);
                char actual = procTs.charAt(index);
                if ("yMdHmsS".indexOf(shaped) >= 0) {
                    assertThat(actual)
                            .withFailMessage("position %d of \"%s\" must hold an ASCII digit but holds"
                                    + " '%s'", index, procTs, actual)
                            .isBetween('0', '9');
                } else {
                    assertThat(actual)
                            .withFailMessage("position %d of \"%s\" must hold '%s' but holds '%s'",
                                    index, procTs, shaped, actual)
                            .isEqualTo(shaped);
                }
            }

            // The three separator positions the batch form spells differently from the online form, and
            // the fixed tail, called out individually so a regression names itself.
            assertThat(procTs.charAt(BATCH_TIMESTAMP_THIRD_SEPARATOR_INDEX)).isEqualTo('-');
            assertThat(procTs.substring(BATCH_TIMESTAMP_TAIL_INDEX)).isEqualTo(BATCH_TIMESTAMP_TAIL);
            assertThat(procTs).doesNotContain(":").doesNotContain(" ");
        }

        @Test
        @DisplayName("the online timestamp form is ruled out even though it is exactly the same width")
        void theOnlineTimestampFormIsRuledOut() {
            resolving(postableAccount());

            processor.process(record("10.00"));
            String procTs = capturePostedTransaction().getTranProcTs();

            // Both values are twenty-six encoded bytes, so width alone cannot tell them apart. The
            // online form puts a space where the batch form puts its third hyphen and colons where the
            // batch form puts dots, and every one of those differences is asserted.
            assertThat(ONLINE_FORM_TS.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(BATCH_TIMESTAMP_WIDTH);
            assertThat(procTs.getBytes(StandardCharsets.US_ASCII)).hasSize(BATCH_TIMESTAMP_WIDTH);
            assertThat(procTs).isNotEqualTo(ONLINE_FORM_TS);
            assertThat(ONLINE_FORM_TS.charAt(BATCH_TIMESTAMP_THIRD_SEPARATOR_INDEX)).isEqualTo(' ');
            assertThat(procTs.charAt(BATCH_TIMESTAMP_THIRD_SEPARATOR_INDEX)).isEqualTo('-');
            assertThat(ONLINE_FORM_TS).contains(":");
            assertThat(procTs).doesNotContain(":");
        }

        @Test
        @DisplayName("the blank inbound processing timestamp survives as twenty-six spaces, on the"
                + " posted path and on the reject path alike")
        void theBlankInboundProcessingTimestampSurvivesAsTwentySixSpaces() {
            // Every one of the three hundred seeded records arrives with this field blank, so the value a
            // copied-unchanged image must carry there is twenty-six spaces - not an absent value, not an
            // empty string, and not a rendered date. Asserted on both arms of the mainline's branch.
            resolving(postableAccount());
            DailyTransaction posted = record("10.00");

            assertThat(processor.process(posted)).isNull();

            assertBlankTwentySixSpaces(posted.getDalytranProcTs());
            // Regenerating the posted row's processing timestamp does not write back into the record that
            // was read, so the source image the reject writer would have rendered is untouched.
            assertThat(capturePostedTransaction().getTranProcTs()).isEqualTo(EXPECTED_PROC_TS);

            Mockito.when(cardCrossReferenceRepository.findById(UNKNOWN_CARD))
                    .thenReturn(Optional.empty());
            DailyTransaction refusedRecord = recordOn("10.00", UNKNOWN_CARD);
            RejectRecordWriter.RejectedTransaction refused = processor.process(refusedRecord);

            assertThat(refused).isNotNull();
            assertBlankTwentySixSpaces(refused.sourceRecord().getDalytranProcTs());
        }

        /**
         * Asserts that a value is exactly twenty-six spaces, at twenty-six encoded bytes.
         *
         * <p>Nothing is trimmed and no character count stands in for the width, because the trailing
         * spaces of a fixed-width field are part of what was read and part of what must be written.
         *
         * @param value the value to check
         */
        private void assertBlankTwentySixSpaces(final String value) {
            assertThat(value).isNotNull().isNotEmpty().isEqualTo(BLANK_TS).isBlank();
            assertThat(value.getBytes(StandardCharsets.US_ASCII)).hasSize(BATCH_TIMESTAMP_WIDTH);
            assertThat(value.chars().allMatch(character -> character == ' ')).isTrue();
        }

        @Test
        @DisplayName("the posted record keeps the source record's own business identifier, so no"
                + " surrogate key is generated on this path")
        void theIdentifierIsCarriedAndNoSurrogateIsGenerated() {
            resolving(postableAccount());
            DailyTransaction read = record("10.00");

            processor.process(read);

            String postedId = capturePostedTransaction().getTranId();
            assertThat(postedId).isEqualTo(read.getDalytranId());
            // Identity is the sixteen-character business key of the source image, at its contractual
            // width. Nothing on this path asks for a generated identifier, and nothing could use one: a
            // posted row keyed by anything other than the record's own identifier could not be traced
            // back to the record that produced it. Where the module does generate an identifier - the
            // online bill-payment flow - it does so as highest-existing-key plus one inside the same
            // transaction, never from a database sequence.
            assertThat(postedId.getBytes(StandardCharsets.US_ASCII)).hasSize(DALYTRAN_ID_WIDTH);
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

            TransactionCategoryBalance created = captureInsertedCategoryBalance();
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
                    .saveAndFlush(ArgumentMatchers.any(TransactionCategoryBalance.class));
            inOrder.verify(accountRepository).rewritePostingBalances(
                    ArgumentMatchers.eq(ACCT),
                    ArgumentMatchers.anyLong(),
                    ArgumentMatchers.any(BigDecimal.class),
                    ArgumentMatchers.any(BigDecimal.class),
                    ArgumentMatchers.any(BigDecimal.class));
            inOrder.verify(transactionRepository)
                    .insertAndFlush(ArgumentMatchers.any(Transaction.class));
            inOrder.verifyNoMoreInteractions();
        }
    }

    @Nested
    @DisplayName("The five reject codes and their contractual description texts")
    class RejectCodeContract {

        @Test
        @DisplayName("reject 100 carries its verbatim text at twenty-five encoded bytes")
        void reject100CarriesItsVerbatimText() {
            assertThat(RejectReason.INVALID_CARD_NUMBER.getReasonCode()).isEqualTo(100);
            assertThat(RejectReason.INVALID_CARD_NUMBER.getDescription()).isEqualTo(DESC_100);
            assertThat(DESC_100.getBytes(StandardCharsets.US_ASCII)).hasSize(DESC_100_WIDTH);
        }

        @Test
        @DisplayName("reject 101 carries its verbatim text at twenty-four encoded bytes")
        void reject101CarriesItsVerbatimText() {
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getReasonCode()).isEqualTo(101);
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.getDescription()).isEqualTo(DESC_101);
            assertThat(DESC_101.getBytes(StandardCharsets.US_ASCII)).hasSize(DESC_101_WIDTH);
        }

        @Test
        @DisplayName("reject 102 carries its verbatim text at twenty-one encoded bytes")
        void reject102CarriesItsVerbatimText() {
            assertThat(RejectReason.OVERLIMIT_TRANSACTION.getReasonCode()).isEqualTo(102);
            assertThat(RejectReason.OVERLIMIT_TRANSACTION.getDescription()).isEqualTo(DESC_102);
            assertThat(DESC_102.getBytes(StandardCharsets.US_ASCII)).hasSize(DESC_102_WIDTH);
        }

        @Test
        @DisplayName("reject 103 carries its verbatim text at forty-two encoded bytes")
        void reject103CarriesItsVerbatimText() {
            assertThat(RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION.getReasonCode())
                    .isEqualTo(103);
            assertThat(RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION.getDescription())
                    .isEqualTo(DESC_103);
            assertThat(DESC_103.getBytes(StandardCharsets.US_ASCII)).hasSize(DESC_103_WIDTH);
        }

        @Test
        @DisplayName("reject 109 carries its verbatim text at twenty-four encoded bytes")
        void reject109CarriesItsVerbatimText() {
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getReasonCode()).isEqualTo(109);
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.getDescription()).isEqualTo(DESC_109);
            assertThat(DESC_109.getBytes(StandardCharsets.US_ASCII)).hasSize(DESC_109_WIDTH);
        }

        @Test
        @DisplayName("there are exactly five reject reasons, and every one fits the trailer's"
                + " seventy-six character description field")
        void thereAreExactlyFiveReasonsAndEachFitsTheTrailer() {
            assertThat(RejectReason.values()).hasSize(5);
            assertThat(RejectReason.values())
                    .extracting(RejectReason::getReasonCode)
                    .containsExactly(100, 101, 102, 103, 109);
            for (RejectReason reason : RejectReason.values()) {
                assertThat(reason.getDescription().getBytes(StandardCharsets.US_ASCII))
                        .withFailMessage("the description of reject %d must fit the seventy-six"
                                + " character field of the eighty-byte validation trailer",
                                reason.getReasonCode())
                        .hasSizeLessThanOrEqualTo(76);
            }
        }

        @Test
        @DisplayName("reason code zero is absence rather than a sixth reason")
        void reasonCodeZeroIsAbsence() {
            // The legacy initialises the reason to zero before validating each record and posts while it
            // is still zero, so zero is the absence of a reject reason and not a reason of its own.
            assertThat(RejectReason.byReasonCode(0)).isEmpty();
            assertThat(RejectReason.byReasonCode(100)).contains(RejectReason.INVALID_CARD_NUMBER);
            assertThat(RejectReason.byReasonCode(109))
                    .contains(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE);
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
            Mockito.when(accountRepository.rewritePostingBalances(
                            ArgumentMatchers.eq(ACCT),
                            ArgumentMatchers.anyLong(),
                            ArgumentMatchers.any(BigDecimal.class),
                            ArgumentMatchers.any(BigDecimal.class),
                            ArgumentMatchers.any(BigDecimal.class)))
                    .thenReturn(0);

            RejectRecordWriter.RejectedTransaction refused = processor.process(record("10.00"));

            // Filtered like any other posted record. The mainline decided to post before this stage
            // ran and never reconsiders, so the reject dataset hears nothing about this record.
            assertThat(refused).isNull();
        }

        @Test
        @DisplayName("reject 109 does not prevent the transaction write, and the earlier stage stands")
        void reason109DoesNotPreventTheTransactionWrite() {
            resolving(postableAccount());
            Mockito.when(accountRepository.rewritePostingBalances(
                            ArgumentMatchers.eq(ACCT),
                            ArgumentMatchers.anyLong(),
                            ArgumentMatchers.any(BigDecimal.class),
                            ArgumentMatchers.any(BigDecimal.class),
                            ArgumentMatchers.any(BigDecimal.class)))
                    .thenReturn(0);

            processor.process(record("10.00"));

            // The transaction is still written, third and last, exactly as the legacy does.
            Mockito.verify(transactionRepository)
                    .insertAndFlush(ArgumentMatchers.any(Transaction.class));
            // The category balance had already been committed before the rewrite was attempted.
            Mockito.verify(categoryBalanceRepository)
                    .saveAndFlush(ArgumentMatchers.any(TransactionCategoryBalance.class));
            // The account itself is not saved, because the rewrite took its invalid-key arm.
            Mockito.verify(accountRepository, Mockito.never())
                    .save(ArgumentMatchers.any(Account.class));
        }

        @Test
        @DisplayName("reject 109 is counted as a posted record, under its own reason label")
        void reason109IsCountedAsPosted() {
            resolving(postableAccount());
            Mockito.when(accountRepository.rewritePostingBalances(
                            ArgumentMatchers.eq(ACCT),
                            ArgumentMatchers.anyLong(),
                            ArgumentMatchers.any(BigDecimal.class),
                            ArgumentMatchers.any(BigDecimal.class),
                            ArgumentMatchers.any(BigDecimal.class)))
                    .thenReturn(0);

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

    @Nested
    @DisplayName("What the seeded fixtures can reach, and what they cannot")
    class SeededFixtureReachability {

        /**
         * The four named fixtures this cascade reads, loaded once from the <strong>test
         * classpath</strong>.
         *
         * <p>No repository path is named and the legacy tree is never touched at run time: these are the
         * copies that ship as test resources, resolved as classpath resources by the shared fixture
         * reader, which also fails immediately if any record does not measure its declared width.
         *
         * <p>Reading the fixtures rather than restating their contents as literals is the whole point of
         * this group. The reachability facts below are the reason rejects 100, 101 and 103 are reached
         * elsewhere in this suite through separately constructed records; if a future fixture change
         * quietly made any of them reachable from the seed, an expectation stated as a literal would
         * still pass while the fact it asserted had stopped being true. Read from the fixture, it fails.
         */
        private static final SeededRecordFixture DAILY_TRANSACTIONS =
                SeededRecordFixture.load(DAILY_TRANSACTION_FIXTURE, DAILY_TRANSACTION_WIDTH);

        private static final SeededRecordFixture ACCOUNTS =
                SeededRecordFixture.load(ACCOUNT_FIXTURE, ACCOUNT_WIDTH);

        private static final SeededRecordFixture CARD_CROSS_REFERENCES =
                SeededRecordFixture.load(CARD_XREF_FIXTURE, CARD_XREF_DATA_WIDTH);

        private static final SeededRecordFixture CATEGORY_BALANCES =
                SeededRecordFixture.load(CATEGORY_BALANCE_FIXTURE, CATEGORY_BALANCE_WIDTH);

        /**
         * The one origination date every seeded daily transaction carries.
         *
         * @return the ten-character origination date shared by all three hundred records
         */
        private String seededOriginationDate() {
            Set<String> dates = new LinkedHashSet<>();
            for (int ordinal = 1; ordinal <= DAILY_TRANSACTIONS.recordCount(); ordinal++) {
                dates.add(DAILY_TRANSACTIONS.field(ordinal, DALYTRAN_ORIG_TS_OFFSET,
                        EXPIRY_COMPARISON_WIDTH));
            }
            assertThat(dates)
                    .withFailMessage("every seeded daily transaction shares one origination date, so"
                            + " the seed cannot exercise a date window; found %s", dates)
                    .hasSize(1);
            return dates.iterator().next();
        }

        @Test
        @DisplayName("the seed is three hundred daily transactions against fifty accounts, fifty"
                + " cross-references and fifty category balances")
        void theSeedHasTheMeasuredGeometry() {
            assertThat(DAILY_TRANSACTIONS.recordCount()).isEqualTo(SEEDED_DAILY_TRANSACTIONS);
            assertThat(ACCOUNTS.recordCount()).isEqualTo(SEEDED_ACCOUNTS);
            assertThat(CARD_CROSS_REFERENCES.recordCount()).isEqualTo(SEEDED_ACCOUNTS);
            assertThat(CATEGORY_BALANCES.recordCount()).isEqualTo(SEEDED_ACCOUNTS);
        }

        @Test
        @DisplayName("all three hundred seeded records share one origination date and carry a blank"
                + " processing timestamp")
        void everySeededTimestampFieldIsAsMeasured() {
            assertThat(seededOriginationDate()).hasSize(EXPIRY_COMPARISON_WIDTH);

            for (int ordinal = 1; ordinal <= DAILY_TRANSACTIONS.recordCount(); ordinal++) {
                String processingTimestamp = DAILY_TRANSACTIONS.field(ordinal, DALYTRAN_PROC_TS_OFFSET,
                        BATCH_TIMESTAMP_WIDTH);
                assertThat(processingTimestamp)
                        .withFailMessage("record %d must carry a blank processing timestamp but carries"
                                + " \"%s\"", ordinal, processingTimestamp)
                        .isEqualTo(BLANK_TS);
                assertThat(processingTimestamp.getBytes(StandardCharsets.US_ASCII))
                        .hasSize(BATCH_TIMESTAMP_WIDTH);
            }
        }

        @Test
        @DisplayName("the seed is mixed-sign: two hundred and fifty point-of-sale purchases and fifty"
                + " operator returns")
        void theSeedIsMixedSign() {
            int positive = 0;
            int negative = 0;
            int pointOfSale = 0;
            int operator = 0;

            for (int ordinal = 1; ordinal <= DAILY_TRANSACTIONS.recordCount(); ordinal++) {
                // The sign travels in the final byte of the amount field, overpunched onto its
                // low-order digit. The two sign alphabets are declared in this class by hand.
                String amount = DAILY_TRANSACTIONS.field(ordinal, DALYTRAN_AMOUNT_OFFSET,
                        DALYTRAN_AMOUNT_WIDTH);
                char signByte = amount.charAt(DALYTRAN_AMOUNT_WIDTH - 1);
                if (POSITIVE_OVERPUNCH_BYTES.indexOf(signByte) >= 0) {
                    positive++;
                } else if (NEGATIVE_OVERPUNCH_BYTES.indexOf(signByte) >= 0) {
                    negative++;
                }

                String source = DAILY_TRANSACTIONS.field(ordinal, DALYTRAN_SOURCE_OFFSET,
                        DALYTRAN_SOURCE_WIDTH);
                if (SOURCE_POINT_OF_SALE.equals(source)) {
                    pointOfSale++;
                } else if (SOURCE_OPERATOR.equals(source)) {
                    operator++;
                }
            }

            // Both arms of the sign branch in the account update are therefore genuinely exercised by
            // the seed, which is what makes the negative debit accumulator a real path rather than a
            // theoretical one.
            assertThat(positive).isEqualTo(SEEDED_POSITIVE_AMOUNTS);
            assertThat(negative).isEqualTo(SEEDED_NEGATIVE_AMOUNTS);
            assertThat(pointOfSale).isEqualTo(SEEDED_POSITIVE_AMOUNTS);
            assertThat(operator).isEqualTo(SEEDED_NEGATIVE_AMOUNTS);
        }

        @Test
        @DisplayName("reject 100 is data-unreachable from the seed, because every seeded card resolves")
        void reject100IsDataUnreachableFromTheSeed() {
            Set<String> seededCards = new LinkedHashSet<>();
            for (int ordinal = 1; ordinal <= CARD_CROSS_REFERENCES.recordCount(); ordinal++) {
                seededCards.add(CARD_CROSS_REFERENCES.field(ordinal, XREF_CARD_OFFSET,
                        XREF_CARD_WIDTH));
            }

            for (int ordinal = 1; ordinal <= DAILY_TRANSACTIONS.recordCount(); ordinal++) {
                String card = DAILY_TRANSACTIONS.field(ordinal, DALYTRAN_CARD_OFFSET,
                        DALYTRAN_CARD_WIDTH);
                assertThat(seededCards)
                        .withFailMessage("seeded record %d names card %s, which the seeded"
                                + " cross-reference must resolve", ordinal, card)
                        .contains(card);
            }

            // So the probe that does reach reject 100 has to be a constructed record naming a card the
            // seed does not hold. Asserting that the probe is genuinely absent couples it to the
            // fixture: were the card ever seeded, this would fail rather than quietly stop probing.
            assertThat(seededCards).doesNotContain(UNKNOWN_CARD);
        }

        @Test
        @DisplayName("reject 101 is data-unreachable from the seed, because every cross-reference names"
                + " an account that exists")
        void reject101IsDataUnreachableFromTheSeed() {
            Set<String> seededAccounts = new LinkedHashSet<>();
            for (int ordinal = 1; ordinal <= ACCOUNTS.recordCount(); ordinal++) {
                seededAccounts.add(ACCOUNTS.field(ordinal, ACCT_ID_OFFSET, ACCT_ID_WIDTH));
            }

            for (int ordinal = 1; ordinal <= CARD_CROSS_REFERENCES.recordCount(); ordinal++) {
                String accountId = CARD_CROSS_REFERENCES.field(ordinal, XREF_ACCT_OFFSET,
                        XREF_ACCT_WIDTH);
                assertThat(seededAccounts)
                        .withFailMessage("seeded cross-reference %d names account %s, which the seeded"
                                + " account file must hold", ordinal, accountId)
                        .contains(accountId);
            }

            assertThat(seededAccounts).doesNotContain(MISSING_ACCT);
        }

        @Test
        @DisplayName("reject 103 is data-unreachable from the seed, because every seeded account expires"
                + " after the seeded origination date")
        void reject103IsDataUnreachableFromTheSeed() {
            String originationDate = seededOriginationDate();

            for (int ordinal = 1; ordinal <= ACCOUNTS.recordCount(); ordinal++) {
                // Offset 58 for ten bytes is the misspelled expiration field of copybook line 11; the
                // layout position is unchanged by the correction of the Java property's name.
                String expiry = ACCOUNTS.field(ordinal, ACCT_EXPIRATION_OFFSET,
                        ACCT_EXPIRATION_WIDTH);
                assertThat(expiry)
                        .withFailMessage("seeded account %d expires on %s, which must not precede the"
                                + " seeded origination date %s or reject 103 becomes seed-reachable and"
                                + " the constructed probe elsewhere in this suite stops being the only"
                                + " route to it", ordinal, expiry, originationDate)
                        .isGreaterThanOrEqualTo(originationDate);
            }
        }

        @Test
        @DisplayName("every seeded cycle accumulator and category balance is zero, so on seeded data the"
                + " basis reduces to the amount alone")
        void everySeededAccumulatorAndBalanceIsZero() {
            for (int ordinal = 1; ordinal <= ACCOUNTS.recordCount(); ordinal++) {
                assertThat(ACCOUNTS.field(ordinal, ACCT_CYCLE_CREDIT_OFFSET, ACCT_MONEY_WIDTH))
                        .isEqualTo(ZONED_POSITIVE_ZERO_MONEY);
                assertThat(ACCOUNTS.field(ordinal, ACCT_CYCLE_DEBIT_OFFSET, ACCT_MONEY_WIDTH))
                        .isEqualTo(ZONED_POSITIVE_ZERO_MONEY);
            }
            for (int ordinal = 1; ordinal <= CATEGORY_BALANCES.recordCount(); ordinal++) {
                assertThat(CATEGORY_BALANCES.field(ordinal, TCATBAL_BALANCE_OFFSET,
                        TCATBAL_BALANCE_WIDTH))
                        .isEqualTo(ZONED_POSITIVE_ZERO_BALANCE);
            }
        }

        @Test
        @DisplayName("only reject 102 is producible from seed-shaped data, and it is producible")
        void onlyReject102IsProducibleFromSeedShapedData() {
            // A seed-shaped pairing, with both operands taken from the fixtures rather than invented: the
            // record's whole twenty-six character origination field and the account's ten-character
            // expiration field, against zero cycle accumulators. With those held to their seeded values
            // the expiry test passes and the accumulators contribute nothing, so the limit alone decides
            // and the only reject the seed shape admits is 102.
            String seededOrigination = DAILY_TRANSACTIONS.field(1, DALYTRAN_ORIG_TS_OFFSET,
                    BATCH_TIMESTAMP_WIDTH);
            String seededExpiry = ACCOUNTS.field(1, ACCT_EXPIRATION_OFFSET, ACCT_EXPIRATION_WIDTH);

            resolving(account("0.00", "9.99", "0.00", "0.00", seededExpiry));
            DailyTransaction overTheLimit = record("10.00");
            overTheLimit.setDalytranOrigTs(seededOrigination);

            RejectRecordWriter.RejectedTransaction refused = processor.process(overTheLimit);

            assertThat(refused).isNotNull();
            assertThat(refused.reason()).isEqualTo(RejectReason.OVERLIMIT_TRANSACTION);
            assertThat(refused.reason().getDescription()).isEqualTo(DESC_102);
        }

        @Test
        @DisplayName("the same seed-shaped pairing at a limit that covers the amount posts, so 102 is"
                + " the limit's doing and not the seed shape's")
        void theSameSeedShapedPairingPostsWhenTheLimitCoversTheAmount() {
            String seededOrigination = DAILY_TRANSACTIONS.field(1, DALYTRAN_ORIG_TS_OFFSET,
                    BATCH_TIMESTAMP_WIDTH);
            String seededExpiry = ACCOUNTS.field(1, ACCT_EXPIRATION_OFFSET, ACCT_EXPIRATION_WIDTH);

            resolving(account("0.00", "10.00", "0.00", "0.00", seededExpiry));
            DailyTransaction withinTheLimit = record("10.00");
            withinTheLimit.setDalytranOrigTs(seededOrigination);

            assertThat(processor.process(withinTheLimit)).isNull();
        }

        @Test
        @DisplayName("an unresolvable daily transaction is a business refusal, never a technical"
                + " failure, which is what the table's absent foreign keys make possible")
        void anUnresolvableDailyTransactionIsABusinessRefusal() {
            // The daily-transaction table carries no foreign key at all - six exist in the schema and not
            // one of them is on that table - so a record naming a card the cross-reference does not hold
            // is a record the system genuinely has to cope with, and the cascade's answer is a reject
            // code rather than an exception. That is what makes constructing such a record here
            // legitimate rather than a contrivance, and it is asserted rather than merely asserted about.
            Mockito.when(cardCrossReferenceRepository.findById(UNKNOWN_CARD))
                    .thenReturn(Optional.empty());
            DailyTransaction unresolvable = recordOn("10.00", UNKNOWN_CARD);

            assertThatCode(() -> processor.process(unresolvable)).doesNotThrowAnyException();

            RejectRecordWriter.RejectedTransaction refused = processor.process(unresolvable);
            assertThat(refused).isNotNull();
            assertThat(refused.reason()).isEqualTo(RejectReason.INVALID_CARD_NUMBER);
            assertThat(refused.reason().getDescription()).isEqualTo(DESC_100);
        }
    }
}
