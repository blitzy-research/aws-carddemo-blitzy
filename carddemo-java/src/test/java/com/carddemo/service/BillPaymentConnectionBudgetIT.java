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

import com.carddemo.domain.Account;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.TestDataFactory;
import java.time.Clock;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.tracing.prometheus.PrometheusExemplarsAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Proves that one bill-payment turn costs <strong>one connection at a time</strong>, by running as many
 * simultaneous turns as the pool has connections.
 *
 * <h2>What this specification exists to catch</h2>
 *
 * <p>The confirmed turn performs two durable writes: it settles the held account row, and it stores the
 * transaction independently of that settlement. When the second write ran in a unit of work
 * <em>nested inside</em> the first, a thread held the settlement unit's connection while asking the pool
 * for a second one. At a pool sized to the number of simultaneous turns, every thread then held one
 * connection and waited for one that every other thread was holding, and no turn could finish: the pool
 * was exhausted by the shape of the choreography rather than by load.
 *
 * <p>The turns here are therefore run at <strong>exactly</strong> the pool size, on a pool that refuses
 * to wait long, so the failure this specification is written against would surface as a connection
 * timeout rather than as a slow pass. Nothing about the pool is enlarged to make it pass - the pool is
 * deliberately the smallest one that can serve the turns at all, and it is the choreography that makes
 * that sufficient.
 *
 * <h2>Why the pool figure here is not a performance statement</h2>
 *
 * <p>{@value #SIMULTANEOUS_TURNS} is the number of concurrent turns this specification starts and the
 * number of connections it allows them, and those two numbers are equal because the equality is the
 * property under test. It is not a tuning value, not a capacity claim and not a throughput figure: this
 * module states no performance target anywhere, and the delivered profiles carry no pool sizing of their
 * own.
 *
 * <p>Each turn settles its <em>own</em> account, because two turns on the same account are serialised by
 * the row lock by design and would prove nothing about connections.
 */
@SpringBootTest(classes = BillPaymentConnectionBudgetIT.PoolBudgetContext.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.batch.job.enabled=false",
    "spring.main.banner-mode=off",
    "management.endpoint.health.validate-group-membership=false",
    "management.tracing.enabled=false",
    // THE POOL IS THE SUBJECT. Exactly as many connections as there are simultaneous turns, and a wait
    // short enough that a turn which needs a second connection fails here instead of eventually passing.
    "spring.datasource.hikari.maximum-pool-size=" + BillPaymentConnectionBudgetIT.POOL_SIZE_PROPERTY,
    "spring.datasource.hikari.minimum-idle=" + BillPaymentConnectionBudgetIT.POOL_SIZE_PROPERTY,
    "spring.datasource.hikari.connection-timeout=" + BillPaymentConnectionBudgetIT.WAIT_LIMIT_PROPERTY})
@DisplayName("A bill-payment turn holds one connection at a time, so a pool sized to the number of "
        + "simultaneous turns serves all of them")
class BillPaymentConnectionBudgetIT extends AbstractPostgresIT {

    /**
     * Simultaneous turns, and therefore also the pool size.
     *
     * <p>Four rather than two so that the nested shape could not pass by luck: with four turns and four
     * connections, the nested shape needs eight and cannot complete a single turn once all four threads
     * have taken their first connection.
     */
    static final int SIMULTANEOUS_TURNS = 4;

    /** The same figure as a property literal, because an annotation value must be a constant. */
    static final String POOL_SIZE_PROPERTY = "4";

    /**
     * How long a turn may wait for a connection, in milliseconds, as a property literal.
     *
     * <p>Long enough that ordinary contention between four turns is not mistaken for starvation, and
     * short enough that genuine starvation ends this specification rather than stalling it. Hikari's own
     * floor for this setting is 250 ms.
     */
    static final String WAIT_LIMIT_PROPERTY = "4000";

    /** The account identifier prefix each turn's own account is keyed under. */
    private static final String ACCOUNT_PREFIX = "9910000000";

    /**
     * The seeded customer every turn's cross-reference row points at.
     *
     * <p>The reference seed's own first customer, because the cross-reference column is constrained to a
     * customer that exists and this specification asserts nothing about customers.
     */
    private static final String SEEDED_CUSTOMER_ID = "000000001";

    /** The card number prefix each turn's own card is keyed under. */
    private static final String CARD_PREFIX = "991000000000000";

    /** The balance every seeded account carries, and therefore the amount every turn pays. */
    private static final BigDecimal PAYABLE_BALANCE = new BigDecimal("125.50");

    /** The settled balance every account must carry once its turn has completed. */
    private static final BigDecimal SETTLED_BALANCE = new BigDecimal("0.00");

    /** How long the whole set of turns may take before this specification gives up on them. */
    private static final long COMPLETION_LIMIT_SECONDS = 60L;

    /** The subject. */
    @Autowired
    private BillPaymentService billPaymentService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    /** The transactions the turns stored, removed afterwards so the shared schema is left as found. */
    private final List<String> storedTransactionIds = new ArrayList<>();

    @Test
    @DisplayName("four simultaneous confirmed payments complete on a pool of four connections, each "
            + "settling its own account and storing its own transaction")
    void simultaneousTurnsCompleteOnAPoolSizedToThem() throws Exception {
        seedAccounts();
        final CountDownLatch ready = new CountDownLatch(SIMULTANEOUS_TURNS);
        final CountDownLatch go = new CountDownLatch(1);
        final List<Callable<BillPaymentService.BillPaymentResult>> turns = new ArrayList<>();
        for (int ordinal = 0; ordinal < SIMULTANEOUS_TURNS; ordinal++) {
            final String accountId = accountId(ordinal);
            turns.add(() -> {
                ready.countDown();
                // Every turn starts inside the same instant, which is what makes all four hold their first
                // connection at once - the state the nested shape could not get out of.
                go.await(COMPLETION_LIMIT_SECONDS, TimeUnit.SECONDS);
                return this.billPaymentService.processBillPayment(confirmedTurn(accountId));
            });
        }

        final List<BillPaymentService.BillPaymentResult> results;
        final ExecutorService operators = Executors.newFixedThreadPool(SIMULTANEOUS_TURNS);
        try {
            final List<Future<BillPaymentService.BillPaymentResult>> submitted = new ArrayList<>();
            for (final Callable<BillPaymentService.BillPaymentResult> turn : turns) {
                submitted.add(operators.submit(turn));
            }
            assertThat(ready.await(COMPLETION_LIMIT_SECONDS, TimeUnit.SECONDS))
                    .as("every turn must reach the starting line before any of them proceeds")
                    .isTrue();
            go.countDown();

            results = new ArrayList<>();
            for (final Future<BillPaymentService.BillPaymentResult> pending : submitted) {
                // A turn that needed a second connection while holding one fails here with the pool's own
                // timeout, which is exactly the defect this specification is written against.
                results.add(pending.get(COMPLETION_LIMIT_SECONDS, TimeUnit.SECONDS));
            }
        } finally {
            operators.shutdownNow();
        }

        assertThat(results).hasSize(SIMULTANEOUS_TURNS);
        for (int ordinal = 0; ordinal < SIMULTANEOUS_TURNS; ordinal++) {
            final BillPaymentService.BillPaymentResult result = results.get(ordinal);
            assertThat(result.errorFlag())
                    .as("turn %d completed without an operator-facing failure", ordinal)
                    .isFalse();
            assertThat(result.transaction())
                    .as("turn %d stored its transaction", ordinal)
                    .isNotNull();
            this.storedTransactionIds.add(result.transaction().tranId());
        }
        assertThat(this.storedTransactionIds)
                .as("each turn minted a distinct identifier, which is what the allocation lock guarantees")
                .doesNotHaveDuplicates();

        for (int ordinal = 0; ordinal < SIMULTANEOUS_TURNS; ordinal++) {
            assertThat(this.accountRepository.findById(accountId(ordinal)))
                    .as("account %d was settled by its own turn", ordinal)
                    .isPresent()
                    .get()
                    .satisfies(settled -> assertThat(settled.getAcctCurrBal())
                            .isEqualByComparingTo(SETTLED_BALANCE));
        }
    }

    /** Removes everything this specification wrote, so the shared schema is left as it was found. */
    @AfterEach
    void removeWhatThisSpecificationWrote() {
        for (final String tranId : this.storedTransactionIds) {
            this.transactionRepository.deleteById(tranId);
        }
        this.storedTransactionIds.clear();
        for (int ordinal = 0; ordinal < SIMULTANEOUS_TURNS; ordinal++) {
            this.cardCrossReferenceRepository.deleteById(cardNumber(ordinal));
            this.cardRepository.deleteById(cardNumber(ordinal));
            this.accountRepository.deleteById(accountId(ordinal));
        }
    }

    /**
     * Seeds one payable account, customer, card and cross-reference row per turn.
     *
     * <p>One set per turn, because the point of the specification is connections rather than row locks:
     * two turns on one account are serialised by that account's row lock by design.
     */
    private void seedAccounts() {
        for (int ordinal = 0; ordinal < SIMULTANEOUS_TURNS; ordinal++) {
            this.accountRepository.save(TestDataFactory.account()
                    .acctId(accountId(ordinal))
                    .currentBalance(PAYABLE_BALANCE)
                    .build());
            this.cardRepository.save(TestDataFactory.card()
                    .cardNumber(cardNumber(ordinal))
                    .accountId(accountId(ordinal))
                    .build());
            this.cardCrossReferenceRepository.save(TestDataFactory.cardCrossReference()
                    .cardNumber(cardNumber(ordinal))
                    .customerId(SEEDED_CUSTOMER_ID)
                    .accountId(accountId(ordinal))
                    .build());
        }
    }

    /**
     * One confirmed submission, which is the turn that performs both durable writes.
     *
     * @param  accountId the account being paid
     * @return the submission
     */
    private static BillPaymentService.BillPaymentScreenInput confirmedTurn(final String accountId) {
        return new BillPaymentService.BillPaymentScreenInput(accountId, "Y", KeyAction.ENTER,
                ScreenNavigationState.empty().withReEntry());
    }

    /** @param ordinal the turn's ordinal @return that turn's account identifier */
    private static String accountId(final int ordinal) {
        return ACCOUNT_PREFIX + ordinal;
    }

    /** @param ordinal the turn's ordinal @return that turn's card number */
    private static String cardNumber(final int ordinal) {
        return CARD_PREFIX + ordinal;
    }



    /**
     * The slice this specification runs: the bill-payment turn, its collaborators and the real
     * repositories, and nothing that would pull an unrelated screen's context into the same pool.
     *
     * <p>A slice rather than the whole application, for the reason every other integration
     * specification in this module uses one: the pool under test must serve exactly the turns this
     * specification starts, and a wider graph would put beans of other features on the same connections.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import({BillPaymentService.class, NavigationService.class, MessageCatalogService.class,
        OnlineTransactionBoundary.class})
    @EnableJpaRepositories(basePackageClasses = AccountRepository.class)
    @EntityScan(basePackageClasses = Account.class)
    static class PoolBudgetContext {

        /** Creates the configuration. */
        PoolBudgetContext() {
            // Intentionally empty: this slice contributes beans, not state.
        }

        /**
         * The clock the two stored timestamps are read from.
         *
         * @return the system clock in UTC, because no timestamp is asserted here
         */
        @Bean
        Clock poolBudgetClock() {
            return Clock.systemUTC();
        }
    }
}
