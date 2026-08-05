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
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.carddemo.domain.Account;
import com.carddemo.domain.Customer;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.support.AbstractPostgresIT;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that a customer-stage failure rolls the earlier account rewrite back in PostgreSQL.
 */
@DisplayName("Online transaction boundary: account and customer rewrite atomically")
final class OnlineTransactionBoundaryIT extends AbstractPostgresIT {

    private static final String RESERVED_ACCOUNT = "99000000051";
    private static final String RESERVED_CUSTOMER = "990000051";
    private static final BigDecimal ORIGINAL_BALANCE = new BigDecimal("100.00");
    private static final BigDecimal CHANGED_BALANCE = new BigDecimal("777.77");
    private static final SensitiveFieldEncryptionService ENCRYPTION =
            new SensitiveFieldEncryptionService(Base64.getEncoder().encodeToString(
                    "carddemo-boundary-test-key-00001".getBytes(StandardCharsets.UTF_8)));

    OnlineTransactionBoundaryIT() {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = AccountRepository.class)
    @EntityScan(basePackageClasses = Account.class)
    @EnableTransactionManagement
    static class BoundaryUnderTest {

        BoundaryUnderTest() {
        }

        @Bean
        OnlineTransactionBoundary onlineTransactionBoundary() {
            return new OnlineTransactionBoundary();
        }
    }

    @Test
    @DisplayName("a customer rewrite failure leaves the previously flushed account unchanged")
    void customerFailureRollsBackTheAccountRewrite() {
        runner().run(context -> {
            final AccountRepository accounts = context.getBean(AccountRepository.class);
            final CustomerRepository customers = context.getBean(CustomerRepository.class);
            final OnlineTransactionBoundary boundary =
                    context.getBean(OnlineTransactionBoundary.class);
            cleanup(accounts, customers);
            try {
                accounts.saveAndFlush(account());
                customers.saveAndFlush(customer());

                final Account changedAccount = accounts.findById(RESERVED_ACCOUNT).orElseThrow();
                changedAccount.setAcctCurrBal(CHANGED_BALANCE);
                final Customer invalidCustomer =
                        customers.findById(RESERVED_CUSTOMER).orElseThrow();
                invalidCustomer.setCustId("BAD");

                assertThatThrownBy(() -> boundary.execute(() -> {
                    accounts.saveAndFlush(changedAccount);
                    customers.saveAndFlush(invalidCustomer);
                    return Boolean.TRUE;
                })).isInstanceOf(RuntimeException.class);

                assertThat(accounts.findById(RESERVED_ACCOUNT))
                        .get()
                        .extracting(Account::getAcctCurrBal)
                        .isEqualTo(ORIGINAL_BALANCE);
                assertThat(customers.findById(RESERVED_CUSTOMER)).isPresent();
            } finally {
                cleanup(accounts, customers);
            }
        });
    }

    private static Account account() {
        return new Account(
                RESERVED_ACCOUNT,
                "Y",
                ORIGINAL_BALANCE,
                new BigDecimal("5000.00"),
                new BigDecimal("1000.00"),
                "2020-01-01",
                "2099-01-01",
                "2020-01-01",
                new BigDecimal("10.00"),
                new BigDecimal("0.00"),
                "12345",
                "GROUP01   ");
    }

    private static Customer customer() {
        return new Customer(
                RESERVED_CUSTOMER,
                "MARY",
                "Q",
                "JONES",
                "1 MAIN STREET",
                "",
                "DETROIT",
                "MI",
                "USA",
                "48226",
                "(313)555-0100",
                "(248)555-0199",
                null,
                ENCRYPTION.protect(
                        SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                        "BOUNDARY-TEST-ID-001"),
                "1985-07-04",
                "EFT0000001",
                "Y",
                "700");
    }

    private static void cleanup(final AccountRepository accounts,
                                final CustomerRepository customers) {
        accounts.deleteById(RESERVED_ACCOUNT);
        accounts.flush();
        customers.deleteById(RESERVED_CUSTOMER);
        customers.flush();
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class,
                        HibernateJpaAutoConfiguration.class,
                        TransactionAutoConfiguration.class))
                .withUserConfiguration(BoundaryUnderTest.class)
                .withPropertyValues(
                        "spring.datasource.url=" + jdbcUrl(),
                        "spring.datasource.username=" + databaseUser(),
                        "spring.datasource.password=" + databasePassword(),
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "spring.jpa.open-in-view=false");
    }
}