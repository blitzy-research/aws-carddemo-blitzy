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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.domain.Account;
import com.carddemo.domain.Card;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionInsertRepository;
import com.carddemo.repository.TransactionInsertRepositoryImpl;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.TestDataFactory;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.tracing.prometheus.PrometheusExemplarsAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Behavioural specification of the two concurrency guarantees transaction {@code CB00} rests on, asserted
 * by running real turns of the shipped service concurrently against a real PostgreSQL 16 server.
 *
 * <h2>Why this class exists, and what it replaces</h2>
 *
 * <p>Two guarantees in the bill-payment translation are properties of <em>two</em> simultaneous callers, so
 * no single-threaded test can observe either of them:
 *
 * <ul>
 *   <li><strong>The account row is held exclusively from the confirmed read to the rewrite.</strong> The
 *       legacy read at line 343 takes {@code UPDATE} against a file the region defines with
 *       {@code UPDATEMODEL(LOCKING)}, so the record is held until the rewrite at line 235 releases it. Two
 *       operators confirming the same account could not interleave: the second waited at its own read, saw
 *       the settled balance and took the nothing-to-pay arm at lines 197 to 206. The legacy therefore
 *       posted exactly <em>one</em> transaction however many times the payment was submitted.</li>
 *   <li><strong>The identifier allocation is serialised.</strong> The legacy browsed backward from
 *       {@code HIGH-VALUES} to the highest stored key and added one, at lines 212 to 219. Under
 *       {@code READ COMMITTED} an uncommitted insert is invisible, so two allocators that share nothing
 *       would observe the same maximum and collide on the primary key. A transaction-scoped advisory lock
 *       is what reinstates the exclusion the legacy's held browse position had.</li>
 * </ul>
 *
 * <p>Both were previously evidenced by reading the production sources as text and asserting that a lock
 * call appeared before a read call. That establishes that a statement was written; it cannot establish that
 * a second caller actually waits, that the winner's work survives, or that the loser reaches the legacy's
 * own message rather than a conflict the legacy never had. This class asserts each of those by observation.
 *
 * <h2>How the observation is made</h2>
 *
 * <p>Every test drives {@link BillPaymentService#processBillPayment} - the shipped entry point - or the
 * shipped repository method, from two platform threads, against one row in one real table. Ordering is
 * imposed with latches so that each test observes one deterministic interleaving rather than whichever one
 * the scheduler happened to produce. Where a test needs to prove that a thread <em>blocked</em>, it does so
 * by having the waiting thread publish a nanosecond reading on acquisition and comparing it against the
 * reading the holder published on release: a lock that did not block would produce the opposite order.
 *
 * <p><strong>And where a test asserts that a caller has NOT got through, the caller is first observed at
 * the lock.</strong> Three such assertions previously ran a timed probe - sleep, then check that the
 * contender was unfinished - and that establishes nothing: a thread the scheduler has not run is also
 * unfinished, so on a loaded host the assertion passes with the locking removed. Each now waits for the
 * server's own bookkeeping to show a session whose lock request is not granted, through
 * {@link #awaitAContenderWaitingOn}, and only then asserts non-entry. The observation is of a state the
 * contender is in rather than of an interval the test hoped was long enough.
 *
 * <p>No COBOL source line is transcribed.
 */
@SpringBootTest(classes = BillPaymentConcurrencyIT.ConcurrencyContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            // The shared base migrated this server to the head of the delivered set before the first
            // subclass was constructed, so a second migration from this context would apply nothing.
            "spring.flyway.enabled=false",
            // validate, never none and never a generating value: a mapping that had drifted from the
            // migrated schema must fail at refresh rather than be silently reconciled.
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.batch.job.enabled=false",
            "spring.main.banner-mode=off",
            // This graph contributes no AWS health contributor, and the shipped readiness group names one.
            // Relaxed exactly as the other repository-slice specifications relax it.
            "management.endpoint.health.validate-group-membership=false",
            "management.tracing.enabled=false",
            // Small ON PURPOSE. An admitted turn needs two connections at its widest point - the outer
            // unit holding the account row plus the nested unit the independently durable insert opens -
            // and a queued turn must need none. A pool this size makes both properties observable: the
            // exhaustion specification puts a turn on every connection at once, and if a queued turn ever
            // holds one again the run fails here rather than in production. Kept in step with
            // DECLARED_POOL_SIZE.
            "spring.datasource.hikari.maximum-pool-size=8",
            // Fail fast rather than wait: a turn that cannot get a connection is the defect this class
            // guards against, and a long wait would report it as a slow test instead of a failing one.
            "spring.datasource.hikari.connection-timeout=5000"
        })
@DisplayName("Bill payment under concurrency: the held row and the serialised allocation, observed")
final class BillPaymentConcurrencyIT extends AbstractPostgresIT {

    /** How long a test waits for a thread it expects to complete before declaring the run wedged. */
    private static final long COMPLETION_TIMEOUT_SECONDS = 30L;

    /**
     * The connection pool this class declares, as a number rather than only as a property value.
     *
     * <p>Read by the pool-exhaustion specification, which puts exactly this many concurrent turns in
     * flight. The absolute figure does not matter and is deliberately not the shipped default: what the
     * specification is about is that as many concurrent payments as there are connections all complete,
     * which is a property of the design and not of the number.
     */
    private static final int DECLARED_POOL_SIZE = 8;

    /**
     * The {@code pg_locks} kinds a session waiting behind a held row appears under.
     *
     * <p>A blocked row read waits on the holding transaction's identifier, and takes a tuple lock while it
     * queues behind other waiters, so both kinds are counted. Neither is ever held by a session that is not
     * waiting for something another session has.
     */
    private static final List<String> ROW_LOCK_WAIT_TYPES = List.of("transactionid", "tuple");

    /** The {@code pg_locks} kind a session waiting for a transaction-scoped advisory lock appears under. */
    private static final List<String> ADVISORY_LOCK_WAIT_TYPES = List.of("advisory");

    /** The account the concurrent turns contend over. */
    private static final String CONTENDED_ACCOUNT_ID = "00000000901";

    /** The customer the contended account belongs to. */
    private static final String CONTENDED_CUSTOMER_ID = "000000901";

    /** The card the cross-reference resolves the contended account to. */
    private static final String CONTENDED_CARD_NUMBER = "4000000000000901";

    /** A second account, used where two turns must not contend. */
    private static final String SECOND_ACCOUNT_ID = "00000000902";

    /** The customer the second account belongs to. */
    private static final String SECOND_CUSTOMER_ID = "000000902";

    /** The card the second account's cross-reference resolves to. */
    private static final String SECOND_CARD_NUMBER = "4000000000000902";

    /** The payable balance both seeded accounts carry. */
    private static final BigDecimal PAYABLE_BALANCE = new BigDecimal("1250.75");

    /** The balance a settled account carries, which is what the whole-balance payment leaves behind. */
    private static final BigDecimal SETTLED_BALANCE = new BigDecimal("0.00");

    /** The nothing-to-pay text, reproduced verbatim from the legacy message site at line 201. */
    private static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

    /** The update-failure text, reproduced verbatim from the rewrite paragraph's catch-all at line 399. */
    private static final String MSG_UNABLE_TO_UPDATE_ACCOUNT = "Unable to Update Account...";

    /** The first identifier the allocation mints when the transaction master is empty. */
    private static final String FIRST_IDENTIFIER_ON_EMPTY_TABLE = "0000000000000001";

    /** The service under test, wired exactly as the application wires it. */
    @Autowired
    private BillPaymentService service;

    /** The transaction master, used to seed and to observe. */
    @Autowired
    private TransactionRepository transactionRepository;

    /**
     * The account master, spied so that one test can force the rewrite to fail.
     *
     * <p>A spy rather than a mock: every other call reaches the real repository and the real server, so the
     * only thing the test alters is the single store whose failure it needs to observe. That failure has no
     * data-driven trigger - the schema is what makes the rewrite succeed - so a seam is the only honest way
     * to reach the arm.
     */
    @MockitoSpyBean
    private AccountRepository accountRepository;

    /** The cross-reference path, used to seed. */
    @Autowired
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    /** The card master, used to seed the row the cross-reference and the transaction reference. */
    @Autowired
    private CardRepository cardRepository;

    /** The customer master, used to seed the row the account references. */
    @Autowired
    private CustomerRepository customerRepository;

    /** The transaction manager the raw allocation-lock tests open their own units through. */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** The factory the unproxied-fragment test builds its own persistence context from. */
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /** The data source the unproxied-fragment test builds its own template over. */
    @Autowired
    private javax.sql.DataSource dataSource;

    /** The threads two-caller tests run on. */
    private ExecutorService threads;

    /** Creates the test class. */
    BillPaymentConcurrencyIT() {
    }

    /**
     * Clears the application tables and seeds exactly the rows these tests contend over.
     *
     * @throws SQLException if the reset cannot be applied
     */
    @BeforeEach
    void seedContendedRows() throws SQLException {
        truncateApplicationTables();
        // One thread per pooled connection, so the pool-exhaustion specification can put a turn on every
        // connection at once. The two-caller specifications use two of them.
        this.threads = Executors.newFixedThreadPool(DECLARED_POOL_SIZE);

        seedAccountWithCard(CONTENDED_CUSTOMER_ID, CONTENDED_ACCOUNT_ID, CONTENDED_CARD_NUMBER);
        seedAccountWithCard(SECOND_CUSTOMER_ID, SECOND_ACCOUNT_ID, SECOND_CARD_NUMBER);
    }

    /**
     * Stops the threads and restores the seeded state the rest of the suite expects.
     *
     * @throws SQLException if the restore cannot be applied
     */
    @AfterEach
    void restoreSharedState() throws SQLException {
        this.threads.shutdownNow();
        restoreSeededState();
    }

    /**
     * Seeds one customer, one account, one card and one cross-reference, which is the minimum a
     * bill-payment turn reads.
     *
     * @param customerId the nine-digit customer key
     * @param accountId  the eleven-digit account key
     * @param cardNumber the sixteen-digit card number
     */
    private void seedAccountWithCard(final String customerId, final String accountId,
            final String cardNumber) {
        final Customer customer = TestDataFactory.customer().customerId(customerId).build();
        this.customerRepository.saveAndFlush(customer);

        final Account account = TestDataFactory.account()
                .acctId(accountId)
                .currentBalance(PAYABLE_BALANCE)
                .build();
        this.accountRepository.saveAndFlush(account);

        final Card card = TestDataFactory.card()
                .cardNumber(cardNumber)
                .accountId(accountId)
                .build();
        this.cardRepository.saveAndFlush(card);

        final CardCrossReference crossReference = TestDataFactory.cardCrossReference()
                .cardNumber(cardNumber)
                .customerId(customerId)
                .accountId(accountId)
                .build();
        this.cardCrossReferenceRepository.saveAndFlush(crossReference);
    }

    /**
     * One confirmed submission of the bill-payment screen.
     *
     * @param accountId the account key the operator transmitted
     * @return the screen input
     */
    private static BillPaymentService.BillPaymentScreenInput confirmedTurn(final String accountId) {
        return new BillPaymentService.BillPaymentScreenInput(accountId, "Y", KeyAction.ENTER,
                ScreenNavigationState.empty().withReEntry());
    }

    /**
     * Reads one account's stored balance through a connection of its own, so the reading is what the
     * server holds rather than what a session cached.
     *
     * @param accountId the account key
     * @return the stored balance
     * @throws SQLException if the read fails
     */
    private static BigDecimal storedBalanceOf(final String accountId) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT acct_curr_bal FROM account WHERE acct_id = ?")) {
            statement.setString(1, accountId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("the seeded account must be present").isTrue();
                return rows.getBigDecimal(1);
            }
        }
    }

    /**
     * Counts the rows in the transaction master through a connection of its own.
     *
     * @return the stored row count
     * @throws SQLException if the read fails
     */
    private static long storedTransactionCount() throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT count(*) FROM transaction");
                ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }

    /**
     * Lists the stored transaction identifiers in ascending order, through a connection of its own.
     *
     * @return the stored identifiers
     * @throws SQLException if the read fails
     */
    private static List<String> storedTransactionIds() throws SQLException {
        final List<String> identifiers = new ArrayList<>();
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT tran_id FROM transaction ORDER BY tran_id ASC");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                identifiers.add(rows.getString(1));
            }
        }
        return identifiers;
    }

    /**
     * Submits a task and returns its future, so a test reads as two named callers rather than as thread
     * plumbing.
     *
     * @param <T>  the task's result type
     * @param task the task
     * @return the future
     */
    private <T> Future<T> run(final Callable<T> task) {
        return this.threads.submit(task);
    }

    /**
     * Waits for one caller's result under the completion timeout.
     *
     * @param <T>    the result type
     * @param future the future to wait on
     * @return the result
     * @throws Exception if the caller failed or did not complete
     */
    private static <T> T await(final Future<T> future) throws Exception {
        return future.get(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Waits until the server reports a session waiting for a lock of the given kind, and fails if none
     * appears.
     *
     * <h3>Why this replaces a timed probe</h3>
     *
     * <p>Every proof of exclusion in this class needs two facts: the contender reached the lock, and it did
     * not get through. Sleeping and then finding the contender unfinished establishes only the second, and
     * only in the weak sense that nothing has happened yet - which is equally true of a thread the
     * scheduler has not run. On a loaded host that observation therefore passes with the locking deleted,
     * which is precisely the defect these tests exist to detect.
     *
     * <p>PostgreSQL publishes the wait itself. A session blocked behind a row another transaction holds
     * waits on that transaction's identifier, and a session blocked on a transaction-scoped advisory lock
     * waits on an advisory entry; either way {@code pg_locks} carries a row for the waiter with
     * {@code granted} false. Reading that row observes the contender <em>at</em> the lock.
     *
     * <p>The condition is monotone until the holder releases, so polling is deterministic in outcome: what
     * varies between machines is how quickly the state is seen, never whether it holds. A host on which the
     * contender never reaches the server fails here with a message that says so.
     *
     * @param  lockTypes  the {@code pg_locks} lock types that count as this test's boundary
     * @param  boundary   what the wait is being observed at, for the failure message
     * @throws SQLException         if the observer connection fails
     * @throws InterruptedException if the wait is interrupted
     */
    private static void awaitAContenderWaitingOn(final List<String> lockTypes, final String boundary)
            throws SQLException, InterruptedException {
        final long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(COMPLETION_TIMEOUT_SECONDS);
        while (blockedWaitersOn(lockTypes) == 0) {
            assertThat(System.nanoTime() < deadline)
                    .as("no session reached %s within %d seconds, so nothing about exclusion can be "
                            + "concluded from what follows", boundary, COMPLETION_TIMEOUT_SECONDS)
                    .isTrue();
            TimeUnit.MILLISECONDS.sleep(20L);
        }
    }

    /**
     * Counts the sessions currently waiting for a lock of one of the given kinds.
     *
     * @param  lockTypes the {@code pg_locks} lock types to count
     * @return how many such requests are outstanding
     * @throws SQLException if the read fails
     */
    private static int blockedWaitersOn(final List<String> lockTypes) throws SQLException {
        final String predicate = String.join(", ", lockTypes.stream().map(type -> "?").toList());
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM pg_locks WHERE NOT granted AND locktype IN ("
                                + predicate + ")")) {
            for (int index = 0; index < lockTypes.size(); index++) {
                statement.setString(index + 1, lockTypes.get(index));
            }
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
    }

    // ==============================================================================================
    // The exclusive hold on the account row, lines 343 to 235
    // ==============================================================================================

    @Nested
    @DisplayName("The exclusive hold on the account row: two operators confirming the same account")
    class TheHeldAccountRow {

        /** Creates the nest. */
        TheHeldAccountRow() {
        }

        @Test
        @DisplayName("two concurrent confirmed turns against ONE account post exactly ONE transaction and "
                + "apply exactly ONE debit, and the loser is told there is nothing to pay")
        void twoConcurrentTurnsPostOneTransactionAndOneDebit() throws Exception {
            final CountDownLatch bothReady = new CountDownLatch(2);

            final Future<BillPaymentService.BillPaymentResult> first = run(() -> {
                bothReady.countDown();
                bothReady.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return service.processBillPayment(confirmedTurn(CONTENDED_ACCOUNT_ID));
            });
            final Future<BillPaymentService.BillPaymentResult> second = run(() -> {
                bothReady.countDown();
                bothReady.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return service.processBillPayment(confirmedTurn(CONTENDED_ACCOUNT_ID));
            });

            final List<BillPaymentService.BillPaymentResult> outcomes =
                    List.of(await(first), await(second));

            final List<BillPaymentService.BillPaymentResult> accepted = outcomes.stream()
                    .filter(BillPaymentService.BillPaymentResult::paymentAccepted)
                    .toList();
            final List<BillPaymentService.BillPaymentResult> refused = outcomes.stream()
                    .filter(outcome -> !outcome.paymentAccepted())
                    .toList();

            assertThat(accepted)
                    .as("exactly one of the two submissions may be accepted, which is what the legacy's "
                            + "held record guaranteed")
                    .hasSize(1);
            assertThat(refused).hasSize(1);
            assertThat(refused.getFirst().message())
                    .as("the loser reaches the legacy's OWN nothing-to-pay message, not a conflict the "
                            + "legacy never had - it waited at its read and then saw a settled balance")
                    .isEqualTo(MSG_NOTHING_TO_PAY);
            assertThat(refused.getFirst().errorFlag()).isTrue();
            assertThat(refused.getFirst().transaction()).isNull();

            assertThat(storedTransactionCount())
                    .as("one debit means one posted transaction")
                    .isOne();
            assertThat(storedBalanceOf(CONTENDED_ACCOUNT_ID))
                    .as("the balance is settled once: a second debit would have driven it negative")
                    .isEqualByComparingTo(SETTLED_BALANCE);
        }

        @Test
        @DisplayName("the second caller BLOCKS on the held row rather than reading it: it cannot complete "
                + "while the first turn's unit is open")
        void theSecondCallerBlocksWhileTheRowIsHeld() throws Exception {
            final CountDownLatch rowIsHeld = new CountDownLatch(1);
            final CountDownLatch holderMayRelease = new CountDownLatch(1);
            final TransactionTemplate holdingUnit = new TransactionTemplate(transactionManager);

            // One unit of work that takes the same exclusive lock the confirmed span takes as its first
            // statement, and then holds it until this test says otherwise.
            final Future<Boolean> holder = run(() -> holdingUnit.execute(status -> {
                final Optional<Account> held =
                        accountRepository.findByIdForUpdate(CONTENDED_ACCOUNT_ID);
                assertThat(held).as("the holder must obtain the row").isPresent();
                rowIsHeld.countDown();
                try {
                    return holderMayRelease.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("the holder was interrupted", interrupted);
                }
            }));

            assertThat(rowIsHeld.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("the holder must reach the lock before the contender starts")
                    .isTrue();

            final Future<BillPaymentService.BillPaymentResult> contender =
                    run(() -> service.processBillPayment(confirmedTurn(CONTENDED_ACCOUNT_ID)));

            // The contender is observed AT the row lock, on the server's own bookkeeping, before its
            // non-entry is asserted. A blocked row read waits on the holding transaction's identifier, so
            // pg_locks carries an ungranted entry for it.
            awaitAContenderWaitingOn(ROW_LOCK_WAIT_TYPES, "the exclusive read of the held account row");
            assertThat(contender.isDone())
                    .as("the contender must still be waiting on the row the holder has locked")
                    .isFalse();
            assertThat(storedTransactionCount())
                    .as("nothing may be posted while the contender is blocked")
                    .isZero();

            holderMayRelease.countDown();
            assertThat(await(holder)).isTrue();

            final BillPaymentService.BillPaymentResult outcome = await(contender);
            assertThat(outcome.paymentAccepted())
                    .as("once the row is released the contender completes normally: the holder only read "
                            + "the row, so the balance it finds is still payable")
                    .isTrue();
            assertThat(storedBalanceOf(CONTENDED_ACCOUNT_ID)).isEqualByComparingTo(SETTLED_BALANCE);
        }

        @Test
        @DisplayName("two concurrent turns against DIFFERENT accounts both complete, because the hold is on "
                + "the row and not on the table")
        void twoTurnsAgainstDifferentAccountsBothComplete() throws Exception {
            final CountDownLatch bothReady = new CountDownLatch(2);

            final Future<BillPaymentService.BillPaymentResult> first = run(() -> {
                bothReady.countDown();
                bothReady.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return service.processBillPayment(confirmedTurn(CONTENDED_ACCOUNT_ID));
            });
            final Future<BillPaymentService.BillPaymentResult> second = run(() -> {
                bothReady.countDown();
                bothReady.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return service.processBillPayment(confirmedTurn(SECOND_ACCOUNT_ID));
            });

            assertThat(await(first).paymentAccepted()).isTrue();
            assertThat(await(second).paymentAccepted()).isTrue();

            assertThat(storedTransactionCount()).isEqualTo(2L);
            assertThat(storedBalanceOf(CONTENDED_ACCOUNT_ID)).isEqualByComparingTo(SETTLED_BALANCE);
            assertThat(storedBalanceOf(SECOND_ACCOUNT_ID)).isEqualByComparingTo(SETTLED_BALANCE);
            assertThat(storedTransactionIds())
                    .as("two accounts, two turns, two DISTINCT identifiers - the allocation serialised "
                            + "even though the rows did not contend")
                    .hasSize(2)
                    .doesNotHaveDuplicates();
        }
    }

    // ==============================================================================================
    // The serialised identifier allocation, lines 212 to 219
    // ==============================================================================================

    @Nested
    @DisplayName("The serialised identifier allocation: the transaction-scoped advisory lock")
    class TheSerialisedAllocation {

        /** Creates the nest. */
        TheSerialisedAllocation() {
        }

        @Test
        @DisplayName("a second caller BLOCKS on the allocation lock until the first caller's unit commits, "
                + "and the release is observably before the acquisition")
        void theSecondAllocatorBlocksUntilTheFirstUnitCommits() throws Exception {
            final CountDownLatch firstHoldsLock = new CountDownLatch(1);
            final CountDownLatch firstMayCommit = new CountDownLatch(1);
            final AtomicLong releasedAt = new AtomicLong();
            final AtomicLong acquiredAt = new AtomicLong();
            final TransactionTemplate unit = new TransactionTemplate(transactionManager);

            final Future<Boolean> holder = run(() -> {
                unit.execute(status -> {
                    transactionRepository.lockIdentifierAllocation(
                            TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY);
                    firstHoldsLock.countDown();
                    try {
                        firstMayCommit.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } catch (final InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("the holder was interrupted", interrupted);
                    }
                    // Read inside the unit and stamped before it commits, so the reading precedes the
                    // release the lock's transaction scope performs at commit.
                    releasedAt.set(System.nanoTime());
                    return null;
                });
                return Boolean.TRUE;
            });

            assertThat(firstHoldsLock.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            final Future<Boolean> contender = run(() -> {
                unit.execute(status -> {
                    transactionRepository.lockIdentifierAllocation(
                            TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY);
                    acquiredAt.set(System.nanoTime());
                    return null;
                });
                return Boolean.TRUE;
            });

            awaitAContenderWaitingOn(ADVISORY_LOCK_WAIT_TYPES, "the shared allocation lock");
            assertThat(contender.isDone())
                    .as("the contender must be waiting: an advisory lock that did not serialise would "
                            + "have let it through immediately")
                    .isFalse();
            assertThat(acquiredAt.get()).as("nothing may have been acquired yet").isZero();

            firstMayCommit.countDown();
            assertThat(await(holder)).isTrue();
            assertThat(await(contender)).isTrue();

            assertThat(acquiredAt.get())
                    .as("the contender acquired the lock only after the holder's unit released it, which "
                            + "is what the transaction-scoped lock guarantees")
                    .isGreaterThan(releasedAt.get());
        }

        @Test
        @DisplayName("a ROLLED-BACK unit releases the lock too, so a failed allocation does not wedge every "
                + "later one")
        void aRolledBackUnitReleasesTheLock() throws Exception {
            final CountDownLatch firstHoldsLock = new CountDownLatch(1);
            final CountDownLatch firstMayFail = new CountDownLatch(1);
            final TransactionTemplate unit = new TransactionTemplate(transactionManager);

            final Future<Boolean> failing = run(() -> {
                try {
                    unit.execute(status -> {
                        transactionRepository.lockIdentifierAllocation(
                                TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY);
                        firstHoldsLock.countDown();
                        try {
                            firstMayFail.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        } catch (final InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        throw new IllegalStateException("this unit rolls back");
                    });
                    return Boolean.FALSE;
                } catch (final IllegalStateException expected) {
                    return Boolean.TRUE;
                }
            });

            assertThat(firstHoldsLock.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            firstMayFail.countDown();
            assertThat(await(failing)).as("the first unit must have rolled back").isTrue();

            // The lock is transaction scoped, so a rollback releases it exactly as a commit does. A
            // subsequent turn therefore completes rather than waiting for a unit that no longer exists.
            final BillPaymentService.BillPaymentResult afterRollback =
                    service.processBillPayment(confirmedTurn(CONTENDED_ACCOUNT_ID));

            assertThat(afterRollback.paymentAccepted()).isTrue();
            assertThat(afterRollback.transaction().tranId())
                    .as("the rolled-back unit consumed no identifier, so the first key is still the one "
                            + "the allocation mints - which is why a database sequence is prohibited")
                    .isEqualTo(FIRST_IDENTIFIER_ON_EMPTY_TABLE);
        }

        @Test
        @DisplayName("two concurrent allocators mint DISTINCT successors of the same stored maximum, and "
                + "neither is refused for the other's key")
        void twoConcurrentAllocatorsMintDistinctSuccessors() throws Exception {
            // One stored row, so both callers would read the same maximum were they not serialised, derive
            // the same successor, and one of them would be refused on the primary key.
            this.seedOneStoredTransaction();

            final CountDownLatch bothReady = new CountDownLatch(2);
            final Future<BillPaymentService.BillPaymentResult> first = run(() -> {
                bothReady.countDown();
                bothReady.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return service.processBillPayment(confirmedTurn(CONTENDED_ACCOUNT_ID));
            });
            final Future<BillPaymentService.BillPaymentResult> second = run(() -> {
                bothReady.countDown();
                bothReady.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return service.processBillPayment(confirmedTurn(SECOND_ACCOUNT_ID));
            });

            final BillPaymentService.BillPaymentResult firstOutcome = await(first);
            final BillPaymentService.BillPaymentResult secondOutcome = await(second);

            assertThat(firstOutcome.paymentAccepted())
                    .as("neither caller may be refused: a refusal here would be the collision the lock "
                            + "exists to prevent")
                    .isTrue();
            assertThat(secondOutcome.paymentAccepted()).isTrue();
            assertThat(List.of(firstOutcome.transaction().tranId(),
                            secondOutcome.transaction().tranId()))
                    .as("the two minted identifiers are the two successors of the stored maximum")
                    .containsExactlyInAnyOrder("0000000000000501", "0000000000000502");
            assertThat(storedTransactionIds()).hasSize(3).doesNotHaveDuplicates();
        }

        /** Seeds one transaction so the stored maximum is non-empty and known. */
        private void seedOneStoredTransaction() {
            final Transaction stored = TestDataFactory.transaction()
                    .id("0000000000000500")
                    .cardNumber(CONTENDED_CARD_NUMBER)
                    .build();
            transactionRepository.saveAndFlush(stored);
        }

        @Test
        @DisplayName("a REAL bill-payment turn blocks on the SHARED allocation lock held by another "
                + "participant, which is what makes the serialisation a property of the whole set")
        void aRealTurnBlocksOnTheSharedAllocationLockHeldByAnotherParticipant() throws Exception {
            final CountDownLatch lockIsHeld = new CountDownLatch(1);
            final CountDownLatch holderMayCommit = new CountDownLatch(1);
            final TransactionTemplate unit = new TransactionTemplate(transactionManager);

            // Another participant - any caller that mints from the stored maximum - holding the shared lock.
            // The textual audit can show that a lock statement was written; only this shows that the shipped
            // service actually contends on the SAME lock as somebody else.
            final Future<Boolean> holder = run(() -> {
                unit.execute(status -> {
                    transactionRepository.lockIdentifierAllocation(
                            TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY);
                    lockIsHeld.countDown();
                    try {
                        holderMayCommit.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } catch (final InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
                return Boolean.TRUE;
            });

            assertThat(lockIsHeld.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            final Future<BillPaymentService.BillPaymentResult> turn =
                    run(() -> service.processBillPayment(confirmedTurn(CONTENDED_ACCOUNT_ID)));

            awaitAContenderWaitingOn(ADVISORY_LOCK_WAIT_TYPES, "the shared allocation lock");
            assertThat(turn.isDone())
                    .as("the turn must be waiting on the shared allocation lock; if it were not taking the "
                            + "same lock it would already have posted")
                    .isFalse();
            assertThat(storedTransactionCount()).isZero();

            holderMayCommit.countDown();
            assertThat(await(holder)).isTrue();
            assertThat(await(turn).paymentAccepted()).isTrue();
            assertThat(storedTransactionIds()).containsExactly(FIRST_IDENTIFIER_ON_EMPTY_TABLE);
        }

        @Test
        @DisplayName("the allocation lock REFUSES to be taken outside a transaction, because there it would "
                + "be released at once and would serialise nothing")
        void theAllocationLockRefusesToBeTakenOutsideATransaction() {
            // Autocommit. The advisory lock is transaction scoped, so acquiring it here would release it
            // the instant the statement completed - a lock that appears to be held and serialises nobody.
            // The repository refuses instead, and it refuses at the PROXY, which is the production path:
            // the declared mandatory propagation is what turns a silent non-serialisation into a failure.
            assertThatExceptionOfType(IllegalTransactionStateException.class)
                    .isThrownBy(() -> transactionRepository.lockIdentifierAllocation(
                            TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY))
                    .withMessageContaining("mandatory");
        }

        @Test
        @DisplayName("the fragment refuses on its OWN account too, so a caller that reached it without the "
                + "repository proxy is still refused rather than silently unserialised")
        void theFragmentItselfRefusesWithoutTheProxy() {
            // The declared propagation is enforced by the proxy; this check is enforced by the method. Both
            // are needed, because the failure mode - a lock that is taken, released at once and serialises
            // nobody - produces no error of its own and would be invisible in any wiring the proxy did not
            // cover.
            final TransactionInsertRepository fragment =
                    new TransactionInsertRepositoryImpl(entityManagerFactory.createEntityManager(),
                            new JdbcTemplate(dataSource));

            assertThatExceptionOfType(IllegalTransactionStateException.class)
                    .isThrownBy(() -> fragment.lockIdentifierAllocation(
                            TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY))
                    .withMessageContaining("transaction-scoped")
                    .withMessageContaining("serialise nothing");
        }
    }

    // ==============================================================================================
    // The connection pool: a queued turn must hold none of it
    // ==============================================================================================

    /**
     * As many concurrent confirmed payments as the pool has connections, over distinct accounts.
     *
     * <p>Distinct accounts on purpose: nothing here contends for a row. What every one of these turns
     * does contend for is the single global allocation lock, and the turn that holds it needs a SECOND
     * connection for the nested unit its independently durable insert opens.
     *
     * <p>The defect this guards against is specific. While the wait for that lock happened inside the
     * unit of work, a turn that was waiting held a connection - so filling the pool with waiting turns
     * left the one turn making progress unable to obtain the connection its nested insert required. Every
     * turn then waited out the pool's acquisition timeout and failed, and so did every other request in
     * the application on every unrelated feature. The fix admits one turn at a time BEFORE the unit
     * opens, so a queued turn holds nothing.
     */
    @Nested
    @DisplayName("The pool under load: a queued payment holds no connection")
    class ThePoolUnderLoad {

        /** Creates the nest. */
        ThePoolUnderLoad() {
        }

        @Test
        @DisplayName("AS MANY CONCURRENT PAYMENTS AS THERE ARE CONNECTIONS ALL COMPLETE: each over its "
                + "own account, all contending for the one allocation lock, none of them holding a "
                + "connection while it waits for it")
        void asManyConcurrentPaymentsAsConnectionsAllComplete() throws Exception {
            final List<String> accountIds = new ArrayList<>(DECLARED_POOL_SIZE);
            for (int index = 0; index < DECLARED_POOL_SIZE; index++) {
                final String suffix = String.format(Locale.ROOT, "%03d", Integer.valueOf(910 + index));
                final String accountId = "00000000" + suffix;
                seedAccountWithCard("000000" + suffix, accountId, "4000000000000" + suffix);
                accountIds.add(accountId);
            }
            final CountDownLatch allReady = new CountDownLatch(DECLARED_POOL_SIZE);

            final List<Future<BillPaymentService.BillPaymentResult>> turns =
                    new ArrayList<>(DECLARED_POOL_SIZE);
            for (final String accountId : accountIds) {
                turns.add(run(() -> {
                    allReady.countDown();
                    allReady.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    return service.processBillPayment(confirmedTurn(accountId));
                }));
            }

            final List<BillPaymentService.BillPaymentResult> outcomes =
                    new ArrayList<>(DECLARED_POOL_SIZE);
            for (final Future<BillPaymentService.BillPaymentResult> turn : turns) {
                outcomes.add(await(turn));
            }

            assertThat(outcomes)
                    .as("every turn returned an outcome rather than a connection-acquisition failure")
                    .hasSize(DECLARED_POOL_SIZE)
                    .allSatisfy(outcome -> assertThat(outcome.paymentAccepted())
                            .as("each account is settled by its own turn and by nothing else, so every "
                                    + "one of them must be accepted: a refusal here is the pool, not the "
                                    + "balance")
                            .isTrue());
            assertThat(storedTransactionCount())
                    .as("one posted transaction per account, so the serialised allocation minted a "
                            + "distinct identifier for each")
                    .isEqualTo(DECLARED_POOL_SIZE);
            for (final String accountId : accountIds) {
                assertThat(storedBalanceOf(accountId))
                        .as("account %s was settled exactly once", accountId)
                        .isEqualByComparingTo(SETTLED_BALANCE);
            }
        }
    }

    // ==============================================================================================
    // The independent durability of the transaction insert, lines 233 to 235
    // ==============================================================================================

    @Nested
    @DisplayName("The independent durability of the insert: a refused rewrite does not undo a stored record")
    class TheIndependentlyDurableInsert {

        /** Creates the nest. */
        TheIndependentlyDurableInsert() {
        }

        @Test
        @DisplayName("a rewrite that FAILS leaves the transaction row COMMITTED in the master and the "
                + "account unsettled, which is exactly what an unrecoverable file leaves behind")
        void aFailedRewriteLeavesTheStoredTransactionCommitted() throws Exception {
            org.mockito.Mockito.doThrow(new IllegalStateException("the rewrite failed"))
                    .when(accountRepository)
                    .saveAndFlush(org.mockito.ArgumentMatchers.any(Account.class));

            final BillPaymentService.BillPaymentResult outcome =
                    service.processBillPayment(confirmedTurn(CONTENDED_ACCOUNT_ID));

            assertThat(outcome.message())
                    .as("the rewrite's catch-all arm is the last text written, so it is what the operator "
                            + "sees")
                    .isEqualTo(MSG_UNABLE_TO_UPDATE_ACCOUNT);
            assertThat(outcome.errorFlag()).isTrue();
            assertThat(outcome.transaction())
                    .as("the insert's own arm ran first and named the record it stored")
                    .isNotNull();

            assertThat(storedTransactionIds())
                    .as("THE ROW IS STILL THERE. The legacy transaction file is defined RECOVERY(NONE) "
                            + "with JOURNAL(NO), so a WRITE that completed is durable and a later REWRITE "
                            + "failure cannot undo it. A shared unit of work would have discarded it, "
                            + "which no legacy mechanism does.")
                    .containsExactly(FIRST_IDENTIFIER_ON_EMPTY_TABLE);
            assertThat(storedBalanceOf(CONTENDED_ACCOUNT_ID))
                    .as("the account was not settled, which is why the operator is told so")
                    .isEqualByComparingTo(PAYABLE_BALANCE);
        }

        @Test
        @DisplayName("a successful turn commits BOTH: the transaction row and the settled balance")
        void aSuccessfulTurnCommitsBoth() throws Exception {
            final BillPaymentService.BillPaymentResult outcome =
                    service.processBillPayment(confirmedTurn(CONTENDED_ACCOUNT_ID));

            assertThat(outcome.paymentAccepted()).isTrue();
            assertThat(storedTransactionIds()).containsExactly(FIRST_IDENTIFIER_ON_EMPTY_TABLE);
            assertThat(storedBalanceOf(CONTENDED_ACCOUNT_ID)).isEqualByComparingTo(SETTLED_BALANCE);
        }
    }

    /**
     * The smallest graph that runs a real bill-payment turn: the service, its collaborators, the shipped
     * repositories and the pinned clock. No web layer and no security, because neither participates in the
     * two concurrency guarantees this class asserts.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import({BillPaymentService.class, NavigationService.class, MessageCatalogService.class,
        OnlineTransactionBoundary.class})
    @EnableJpaRepositories(basePackageClasses = AccountRepository.class)
    @EntityScan(basePackageClasses = Account.class)
    static class ConcurrencyContext {

        /** Creates the configuration. */
        ConcurrencyContext() {
            // Intentionally empty: this slice contributes beans, not state.
        }

        /**
         * The pinned clock every collaborator in the graph reads.
         *
         * @return the shared fixed clock
         */
        @Bean
        Clock fixedClock() {
            return FIXED_CLOCK;
        }
    }
}
