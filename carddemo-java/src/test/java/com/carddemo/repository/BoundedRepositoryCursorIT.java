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

import java.math.BigDecimal;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.carddemo.domain.Account;
import com.carddemo.domain.Card;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.DailyTransaction;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.support.AbstractPostgresIT;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the bounded repository cursors against PostgreSQL rather than only against unit-test doubles.
 */
@DisplayName("bounded repository cursors: strict business-key progress with a caller-owned limit")
final class BoundedRepositoryCursorIT extends AbstractPostgresIT {

    private static final int ACCOUNT_KEY_WIDTH = 11;

    private static final int TYPE_KEY_WIDTH = 2;

    /** Registers the production entities and repository proxies against the migrated schema. */
    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = AccountRepository.class)
    @EntityScan(basePackageClasses = Account.class)
    static class RepositoryUnderTest {

        /** Creates the configuration. */
        RepositoryUnderTest() {
        }
    }

    @Test
    @DisplayName("all seven explicit full-pass finders exclude the cursor and stay bounded")
    void explicitFindersAreStrictAndBounded() {
        runner().run(context -> {
            final AccountScanRepository accounts = context.getBean(AccountScanRepository.class);
            final CardScanRepository cards = context.getBean(CardScanRepository.class);
            final CardCrossReferenceScanRepository crossReferences =
                    context.getBean(CardCrossReferenceScanRepository.class);
            final TransactionScanRepository transactions =
                    context.getBean(TransactionScanRepository.class);
            final TransactionRepository transactionStore =
                    context.getBean(TransactionRepository.class);
            final CustomerRepository customers = context.getBean(CustomerRepository.class);
            final DailyTransactionRepository dailyTransactions =
                    context.getBean(DailyTransactionRepository.class);
            final TransactionCategoryBalanceRepository categoryBalances =
                    context.getBean(TransactionCategoryBalanceRepository.class);

            assertStrictKeyset(
                    (cursor, size) -> accounts
                            .findByAcctIdGreaterThanOrderByAcctIdAsc(
                                    cursor, Limit.of(size)),
                    Account::getAcctId);
            assertStrictKeyset(
                    (cursor, size) -> cards
                            .findByCardNumGreaterThanOrderByCardNumAsc(
                                    cursor, Limit.of(size)),
                    Card::getCardNum);
            assertStrictKeyset(
                    (cursor, size) -> crossReferences
                            .findByXrefCardNumGreaterThanOrderByXrefCardNumAsc(
                                    cursor, Limit.of(size)),
                    CardCrossReference::getXrefCardNum);
            final List<Transaction> cursorFixtures = List.of(
                    transaction("0000000000000901"),
                    transaction("0000000000000902"),
                    transaction("0000000000000903"));
            cursorFixtures.forEach(transactionStore::insertAndFlush);
            try {
                assertStrictKeyset(
                        (cursor, size) -> transactions
                                .findByTranIdGreaterThanOrderByTranIdAsc(
                                        cursor, Limit.of(size)),
                        Transaction::getTranId);
            } finally {
                transactionStore.deleteAllByIdInBatch(
                        cursorFixtures.stream().map(Transaction::getTranId).toList());
            }
            assertStrictKeyset(
                    (cursor, size) -> customers.findByCustIdGreaterThanOrderByCustIdAsc(
                            cursor, Limit.of(size)),
                    Customer::getCustId);
            assertStrictKeyset(
                    (cursor, size) ->
                            dailyTransactions.findByDalytranIdGreaterThanOrderByDalytranIdAsc(
                                    cursor, Limit.of(size)),
                    DailyTransaction::getDalytranId);
            assertStrictKeyset(
                    (cursor, size) -> categoryBalances.findAfterKey(
                            keyPart(cursor, 0, ACCOUNT_KEY_WIDTH),
                            keyPart(cursor, ACCOUNT_KEY_WIDTH, TYPE_KEY_WIDTH),
                            cursor.length() <= ACCOUNT_KEY_WIDTH + TYPE_KEY_WIDTH
                                    ? ""
                                    : cursor.substring(ACCOUNT_KEY_WIDTH + TYPE_KEY_WIDTH),
                            PageRequest.of(0, size)),
                    BoundedRepositoryCursorIT::categoryBalanceKey);
        });
    }

    private static <T> void assertStrictKeyset(
            final BiFunction<String, Integer, List<T>> pageLoader,
            final Function<T, String> keyExtractor) {
        final List<T> firstPage = pageLoader.apply("", 3);
        assertThat(firstPage).hasSize(3);
        final List<String> firstKeys = firstPage.stream().map(keyExtractor).toList();
        assertThat(firstKeys).isSorted();

        final String cursor = firstKeys.get(0);
        final List<String> afterCursor = pageLoader.apply(cursor, 2).stream()
                .map(keyExtractor)
                .toList();

        assertThat(afterCursor).containsExactly(firstKeys.get(1), firstKeys.get(2));
        assertThat(afterCursor).allMatch(key -> key.compareTo(cursor) > 0);
    }

    private static String categoryBalanceKey(final TransactionCategoryBalance balance) {
        return balance.getTrancatAcctId() + balance.getTrancatTypeCd() + balance.getTrancatCd();
    }

    private static String keyPart(final String key, final int offset, final int width) {
        if (key.length() <= offset) {
            return "";
        }
        return key.substring(offset, Math.min(key.length(), offset + width));
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class,
                        HibernateJpaAutoConfiguration.class,
                        TransactionAutoConfiguration.class))
                .withUserConfiguration(RepositoryUnderTest.class)
                .withPropertyValues(
                        "spring.datasource.url=" + jdbcUrl(),
                        "spring.datasource.username=" + databaseUser(),
                        "spring.datasource.password=" + databasePassword(),
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "spring.jpa.open-in-view=false");
    }

    private static Transaction transaction(final String identifier) {
        return new Transaction(identifier, "01", "0005", "POS TERM  ",
                "CURSOR TEST" + " ".repeat(89), new BigDecimal("1.00"), "000000901",
                "CURSOR MERCHANT" + " ".repeat(35),
                "CURSOR CITY" + " ".repeat(39),
                "98101-0001", "0500024453765740",
                "2026-08-05-00.00.00.000000",
                "2026-08-05-00.00.00.000000");
    }
}