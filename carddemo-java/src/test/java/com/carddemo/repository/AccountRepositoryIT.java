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

package com.carddemo.repository;

import com.carddemo.domain.Account;
import com.carddemo.support.AbstractPostgresIT;
import jakarta.persistence.TransactionRequiredException;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies the account rewrite used by the posting program against PostgreSQL rather than against a
 * repository mock.
 *
 * <p>{@code CBTRN02C} paragraph {@code 2800-UPDATE-ACCOUNT-REC} distinguishes a successful rewrite
 * from its invalid-key arm by the write operation's status. The relational translation therefore has
 * to return the affected-row count from the update itself, update only the three balances that
 * paragraph changes, and advance the optimistic version in that same statement. Those properties
 * depend on JPQL parsing, provider execution and database row-count reporting, none of which a unit
 * test can prove.
 *
 * <p>The same paragraph asks a second question the update count cannot answer on its own: whether a
 * zero count means the row is gone or means the row is present at another version. That answer is
 * obtained beforehand from a keyed read that holds the row, and a hold is precisely the property a
 * repository mock cannot exhibit, so it is proven here too.
 *
 * <p>The reserved accounts are removed in {@code finally} blocks because the PostgreSQL container is
 * shared by the integration suite. Legacy authority is {@code app/cbl/CBTRN02C.cbl} lines 545 to 560,
 * read at checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}; no source statement is
 * copied here.
 */
@DisplayName("Account repository: posting rewrite status and version")
final class AccountRepositoryIT extends AbstractPostgresIT {

    /** Account key reserved for this integration test and absent from the delivered seed. */
    private static final String RESERVED_ACCOUNT = "99000000042";

    /** An absent key used to exercise the legacy invalid-key outcome. */
    private static final String ABSENT_ACCOUNT = "99000000043";

    /** Creates the test class. */
    AccountRepositoryIT() {
    }

    /** Registers the repository package and the domain package against the shared PostgreSQL server. */
    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = AccountRepository.class)
    @EntityScan(basePackageClasses = Account.class)
    static class RepositoryUnderTest {

        /** Creates the configuration. */
        RepositoryUnderTest() {
        }
    }

    @Test
    @DisplayName("the posting rewrite returns its row status, writes all three balances and increments "
            + "the version exactly once")
    void thePostingRewriteReportsItsOwnStatusAndAdvancesTheVersion() {
        runner().run(context -> {
            final AccountRepository repository = context.getBean(AccountRepository.class);
            try {
                final Account stored = repository.saveAndFlush(reservedAccount());
                final long versionBeforeRewrite = stored.getVersion();

                final int rewritten = repository.rewritePostingBalances(
                        RESERVED_ACCOUNT,
                        versionBeforeRewrite,
                        new BigDecimal("125.25"),
                        new BigDecimal("30.25"),
                        new BigDecimal("-5.00"));

                assertThat(rewritten).isOne();
                assertThat(repository.findById(RESERVED_ACCOUNT))
                        .get()
                        .satisfies(account -> {
                            assertThat(account.getAcctCurrBal())
                                    .isEqualByComparingTo(new BigDecimal("125.25"));
                            assertThat(account.getAcctCurrCycCredit())
                                    .isEqualByComparingTo(new BigDecimal("30.25"));
                            assertThat(account.getAcctCurrCycDebit())
                                    .isEqualByComparingTo(new BigDecimal("-5.00"));
                            assertThat(account.getVersion()).isEqualTo(versionBeforeRewrite + 1L);
                        });

                assertThat(repository.rewritePostingBalances(
                        ABSENT_ACCOUNT,
                        0L,
                        new BigDecimal("1.00"),
                        new BigDecimal("1.00"),
                        new BigDecimal("0.00")))
                        .as("zero rows is the REWRITE INVALID KEY outcome that becomes reason 109")
                        .isZero();

                // The version the first rewrite consumed is now stale. Offering it again must change
                // nothing: this is the compare-and-set that stops a concurrent online write from being
                // silently overwritten, and it is why a zero-row outcome no longer means "absent".
                assertThat(repository.rewritePostingBalances(
                        RESERVED_ACCOUNT,
                        versionBeforeRewrite,
                        new BigDecimal("999.99"),
                        new BigDecimal("999.99"),
                        new BigDecimal("-999.99")))
                        .as("a stale version rewrites no row even though the row is present")
                        .isZero();
                assertThat(repository.findById(RESERVED_ACCOUNT))
                        .get()
                        .satisfies(account -> {
                            assertThat(account.getAcctCurrBal())
                                    .as("the earlier write survives the stale attempt untouched")
                                    .isEqualByComparingTo(new BigDecimal("125.25"));
                            assertThat(account.getVersion())
                                    .as("and the counter did not advance a second time")
                                    .isEqualTo(versionBeforeRewrite + 1L);
                        });
                assertThat(repository.existsById(RESERVED_ACCOUNT))
                        .as("the row is present, which is how the caller tells a lost race from an "
                                + "absent account")
                        .isTrue();
            } finally {
                repository.deleteById(RESERVED_ACCOUNT);
                repository.flush();
            }
        });
    }

    /**
     * The held keyed read that settles the invalid-key question before the rewrite runs.
     *
     * <p>A lock annotation that silently did nothing would leave the very timing window it exists to
     * close, and would look identical to a working one in every unit test: the mock would still be
     * asked, and would still answer. So the hold is proven here against a real server - that it takes
     * the row, that it keeps it for the length of the unit of work, that it refuses rather than
     * downgrading when there is no unit of work to keep it for, and that a competing writer therefore
     * cannot invert the answer between the read and the rewrite that consumes it.
     */
    @Nested
    @DisplayName("the held read settles presence before the rewrite and keeps it settled")
    final class TheHeldReadBeforeTheRewrite {

        /** How long a competing writer is given to prove it is blocked. */
        private static final long BLOCKED_WINDOW_MILLIS = 750L;

        /** How long a thread is given to finish once it is no longer blocked. */
        private static final long RELEASED_WINDOW_SECONDS = 30L;

        /** Account key reserved for this nest, distinct from the one the rewrite test uses. */
        private static final String HELD_ACCOUNT = "99000000044";

        /** Creates the nest. */
        TheHeldReadBeforeTheRewrite() {
        }

        @Test
        @DisplayName("finds the row inside a unit of work and reports the version the rewrite will "
                + "have to match")
        void findsTheRowInsideAUnitOfWork() {
            runner().run(context -> {
                final AccountRepository repository = context.getBean(AccountRepository.class);
                try {
                    final long storedVersion =
                            repository.saveAndFlush(heldAccount()).getVersion();

                    final Optional<Account> held = transactionTemplate(context)
                            .execute(status -> repository.findByIdForUpdate(HELD_ACCOUNT));

                    assertThat(held).isPresent();
                    assertThat(held.orElseThrow().getAcctId()).isEqualTo(HELD_ACCOUNT);
                    assertThat(held.orElseThrow().getVersion()).isEqualTo(storedVersion);
                } finally {
                    repository.deleteById(HELD_ACCOUNT);
                    repository.flush();
                }
            });
        }

        @Test
        @DisplayName("answers empty for a key no row carries, so the legacy invalid-key condition "
                + "stays an empty result rather than becoming a raised failure")
        void answersEmptyForAnAbsentKey() {
            runner().run(context -> {
                final AccountRepository repository = context.getBean(AccountRepository.class);

                final Optional<Account> absent = transactionTemplate(context)
                        .execute(status -> repository.findByIdForUpdate(ABSENT_ACCOUNT));

                assertThat(absent).isEmpty();
            });
        }

        @Test
        @DisplayName("REFUSES to run outside a unit of work rather than reading without the lock, "
                + "because a silent downgrade would reopen the window it exists to close")
        void refusesToRunOutsideAUnitOfWork() {
            runner().run(context -> {
                final AccountRepository repository = context.getBean(AccountRepository.class);

                assertThatExceptionOfType(InvalidDataAccessApiUsageException.class)
                        .isThrownBy(() -> repository.findByIdForUpdate(ABSENT_ACCOUNT))
                        .withRootCauseInstanceOf(TransactionRequiredException.class);
            });
        }

        @Test
        @DisplayName("a competing DELETE cannot slip between the held read and the rewrite: its "
                + "statement is blocked while the row is held, and refused once it is released")
        void aCompetingDeleteCannotSlipBetweenTheReadAndTheRewrite() {
            runner().run(context -> {
                final AccountRepository repository = context.getBean(AccountRepository.class);
                final TransactionTemplate template = transactionTemplate(context);
                final CountDownLatch posterHasTheRow = new CountDownLatch(1);
                final CountDownLatch deleterHasIssuedIt = new CountDownLatch(1);
                final CountDownLatch posterMayFinish = new CountDownLatch(1);
                final ExecutorService threads = Executors.newFixedThreadPool(2);
                try {
                    final long storedVersion =
                            repository.saveAndFlush(heldAccount()).getVersion();

                    final Future<Integer> poster = threads.submit(() -> template.execute(status -> {
                        final boolean present =
                                repository.findByIdForUpdate(HELD_ACCOUNT).isPresent();
                        posterHasTheRow.countDown();
                        awaitOrFail(deleterHasIssuedIt);
                        awaitOrFail(posterMayFinish);
                        // Present under the hold, so the rewrite below must find exactly one row and a
                        // zero count could only mean a version mismatch. That is the whole property.
                        assertThat(present).isTrue();
                        return repository.rewritePostingBalances(HELD_ACCOUNT, storedVersion,
                                new BigDecimal("777.77"), new BigDecimal("77.77"),
                                new BigDecimal("-7.77"));
                    }));
                    assertThat(posterHasTheRow.await(RELEASED_WINDOW_SECONDS, TimeUnit.SECONDS))
                            .as("the posting thread must hold the row before the delete competes")
                            .isTrue();

                    // The competing unit reads the row unblocked - a snapshot read never waits - and is
                    // stopped at the DELETE statement itself, which is the statement that would have
                    // made the presence answer stale.
                    final Future<Boolean> deleter = threads.submit(() -> template.execute(status -> {
                        repository.findById(HELD_ACCOUNT).orElseThrow();
                        deleterHasIssuedIt.countDown();
                        repository.deleteById(HELD_ACCOUNT);
                        repository.flush();
                        return Boolean.TRUE;
                    }));
                    assertThat(deleterHasIssuedIt.await(RELEASED_WINDOW_SECONDS, TimeUnit.SECONDS))
                            .as("the delete must actually have been reached, or the timeout below "
                                    + "would pass for the wrong reason")
                            .isTrue();
                    assertThatExceptionOfType(TimeoutException.class)
                            .as("the delete cannot take effect while the row is held; if it could, "
                                    + "the presence answer would be stale by the time it is used")
                            .isThrownBy(() -> deleter.get(BLOCKED_WINDOW_MILLIS,
                                    TimeUnit.MILLISECONDS));

                    posterMayFinish.countDown();
                    assertThat(poster.get(RELEASED_WINDOW_SECONDS, TimeUnit.SECONDS))
                            .as("the rewrite ran against the row it had established was there")
                            .isOne();

                    // Released, the delete runs - and is refused, because it was decided against the
                    // image the rewrite has since superseded. So the row the poster classified as
                    // present neither vanished under it nor was silently removed behind it.
                    assertThatExceptionOfType(ExecutionException.class)
                            .isThrownBy(() -> deleter.get(RELEASED_WINDOW_SECONDS, TimeUnit.SECONDS))
                            .withCauseInstanceOf(ObjectOptimisticLockingFailureException.class);
                    assertThat(repository.findById(HELD_ACCOUNT))
                            .as("the rewrite the hold protected is the outcome that survives")
                            .get()
                            .satisfies(account -> assertThat(account.getAcctCurrBal())
                                    .isEqualByComparingTo(new BigDecimal("777.77")));
                } finally {
                    posterMayFinish.countDown();
                    deleterHasIssuedIt.countDown();
                    threads.shutdownNow();
                    assertThat(threads.awaitTermination(RELEASED_WINDOW_SECONDS, TimeUnit.SECONDS))
                            .as("no locking thread may outlive this test")
                            .isTrue();
                    if (repository.existsById(HELD_ACCOUNT)) {
                        repository.deleteById(HELD_ACCOUNT);
                        repository.flush();
                    }
                }
            });
        }

        /**
         * Waits for a latch, failing the calling thread rather than returning early on interruption.
         *
         * @param latch the latch to wait on
         */
        private static void awaitOrFail(final CountDownLatch latch) {
            try {
                if (!latch.await(RELEASED_WINDOW_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the release signal never arrived");
                }
            } catch (final InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting to proceed", interruption);
            }
        }

        /** Builds the account row this nest reserves. */
        private static Account heldAccount() {
            return new Account(
                    HELD_ACCOUNT,
                    "Y",
                    new BigDecimal("100.00"),
                    new BigDecimal("99999.00"),
                    new BigDecimal("500.00"),
                    "2020-01-01",
                    "2099-01-01",
                    "2020-01-01",
                    new BigDecimal("5.00"),
                    new BigDecimal("0.00"),
                    "12345",
                    "GROUP01   ");
        }
    }

    /**
     * Builds a transaction template over the context's transaction manager.
     *
     * @param  context the running repository context
     * @return a template that opens one unit of work per call
     */
    private static TransactionTemplate transactionTemplate(final ApplicationContext context) {
        return new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    /** Builds one complete, valid account row whose non-posting fields must remain untouched. */
    private static Account reservedAccount() {
        return new Account(
                RESERVED_ACCOUNT,
                "Y",
                new BigDecimal("100.00"),
                new BigDecimal("99999.00"),
                new BigDecimal("500.00"),
                "2020-01-01",
                "2099-01-01",
                "2020-01-01",
                new BigDecimal("5.00"),
                new BigDecimal("0.00"),
                "12345",
                "GROUP01   ");
    }

    /** Assembles a repository-only context against the shared migrated server. */
    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class,
                        HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(RepositoryUnderTest.class)
                .withPropertyValues(
                        "spring.datasource.url=" + jdbcUrl(),
                        "spring.datasource.username=" + databaseUser(),
                        "spring.datasource.password=" + databasePassword(),
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "spring.jpa.open-in-view=false");
    }
}