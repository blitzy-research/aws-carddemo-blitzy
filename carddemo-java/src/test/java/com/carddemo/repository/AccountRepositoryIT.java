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
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import static org.assertj.core.api.Assertions.assertThat;

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
 * <p>The reserved account is removed in a {@code finally} block because the PostgreSQL container is
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