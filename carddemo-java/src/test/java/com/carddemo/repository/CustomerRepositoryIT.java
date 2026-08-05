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

import com.carddemo.domain.Customer;
import com.carddemo.support.AbstractPostgresIT;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies the customer master's unversioned compare-and-set against PostgreSQL.
 *
 * <p>The account-update transaction holds a complete before-image, rewrites the versioned account,
 * and then rewrites the customer only if that unversioned row still matches. A repository
 * {@code save} cannot preserve that last condition because it merges by identifier. These tests call
 * the real repository proxy so they prove the JPQL resolves, the nullable national-identifier
 * predicate matches, a moved row is not overwritten, and the default-method guard forbids a key
 * change.
 *
 * <p>Each database test copies a seeded customer into a reserved key, then removes only that row. The
 * delivered seed and its cross-reference children therefore remain untouched.
 */
@DisplayName("Customer repository: complete before-image compare-and-set")
final class CustomerRepositoryIT extends AbstractPostgresIT {

    /** A seeded customer whose protected government identifier can be copied safely. */
    private static final String SEEDED_CUSTOMER_ID = "000000050";

    /** A nine-digit key outside the delivered seed range and reserved for this class. */
    private static final String RESERVED_CUSTOMER_ID = "990000050";

    /** A second valid key used only to prove that compare-and-set cannot move a row. */
    private static final String DIFFERENT_CUSTOMER_ID = "990000051";

    /** The image an operator intends to write. */
    private static final String OPERATOR_FIRST_NAME = "OPERATOR IMAGE";

    /** The image committed independently before the operator reaches the write point. */
    private static final String CONCURRENT_FIRST_NAME = "CONCURRENT IMAGE";

    /** Creates the test class. */
    CustomerRepositoryIT() {
    }

    /**
     * Registers the customer repository and entity against the shared PostgreSQL schema.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = CustomerRepository.class)
    @EntityScan(basePackageClasses = Customer.class)
    static class RepositoryUnderTest {

        /** Creates the configuration. */
        RepositoryUnderTest() {
        }
    }

    @Test
    @DisplayName("a matching row is rewritten exactly once, including when the old SSN is SQL null")
    void aMatchingRowIsRewritten() {
        runner().run(context -> {
            final CustomerRepository repository = context.getBean(CustomerRepository.class);
            try {
                final Customer before = insertReservedCustomer(repository);
                assertThat(before.getCustSsn())
                        .as("the null-aware predicate is part of the production comparison")
                        .isNull();
                final Customer after = copyWithIdentityAndFirstName(
                        before, RESERVED_CUSTOMER_ID, OPERATOR_FIRST_NAME);

                assertThat(repository.compareAndSet(before, after)).isOne();
                assertThat(repository.findById(RESERVED_CUSTOMER_ID))
                        .get()
                        .extracting(Customer::getFirstName)
                        .isEqualTo(OPERATOR_FIRST_NAME);
            } finally {
                removeReservedCustomer();
            }
        });
    }

    @Test
    @DisplayName("a row that moves after the read returns zero and keeps the committed competing image")
    void aMovedRowIsNotOverwritten() {
        runner().run(context -> {
            final CustomerRepository repository = context.getBean(CustomerRepository.class);
            try {
                final Customer before = insertReservedCustomer(repository);
                moveReservedCustomer(CONCURRENT_FIRST_NAME);
                final Customer after = copyWithIdentityAndFirstName(
                        before, RESERVED_CUSTOMER_ID, OPERATOR_FIRST_NAME);

                assertThat(repository.compareAndSet(before, after)).isZero();
                assertThat(repository.findById(RESERVED_CUSTOMER_ID))
                        .get()
                        .extracting(Customer::getFirstName)
                        .isEqualTo(CONCURRENT_FIRST_NAME);
            } finally {
                removeReservedCustomer();
            }
        });
    }

    @Test
    @DisplayName("the default method refuses a replacement carrying a different business key")
    void aDifferentKeyIsRejectedBeforeTheUpdate() {
        runner().run(context -> {
            final CustomerRepository repository = context.getBean(CustomerRepository.class);
            final Customer seed = repository.findById(SEEDED_CUSTOMER_ID).orElseThrow();
            final Customer before = copyWithIdentityAndFirstName(
                    seed, RESERVED_CUSTOMER_ID, seed.getFirstName());
            final Customer after = copyWithIdentityAndFirstName(
                    seed, DIFFERENT_CUSTOMER_ID, OPERATOR_FIRST_NAME);

            assertThatExceptionOfType(InvalidDataAccessApiUsageException.class)
                    .isThrownBy(() -> repository.compareAndSet(before, after))
                    .withRootCauseInstanceOf(IllegalArgumentException.class)
                    .withMessage("customer compare-and-set cannot change the key");
        });
    }

    /**
     * Copies one delivered row to the reserved key and returns the detached image used by the compare.
     *
     * @param repository the repository under test
     * @return the stored reserved customer
     */
    private static Customer insertReservedCustomer(final CustomerRepository repository) {
        final Customer seed = repository.findById(SEEDED_CUSTOMER_ID).orElseThrow();
        return repository.saveAndFlush(copyWithIdentityAndFirstName(
                seed, RESERVED_CUSTOMER_ID, seed.getFirstName()));
    }

    /**
     * Builds an independent image while preserving every non-key field byte-for-byte.
     *
     * @param source    the image to copy
     * @param custId    the business key for the copy
     * @param firstName the first name for the copy
     * @return an independent customer image
     */
    private static Customer copyWithIdentityAndFirstName(
            final Customer source,
            final String custId,
            final String firstName) {
        return new Customer(
                custId,
                firstName,
                source.getMiddleName(),
                source.getLastName(),
                source.getAddrLine1(),
                source.getAddrLine2(),
                source.getAddrLine3(),
                source.getAddrStateCd(),
                source.getAddrCountryCd(),
                source.getAddrZip(),
                source.getPhoneNum1(),
                source.getPhoneNum2(),
                source.getCustSsn(),
                source.getGovtIssuedId(),
                source.getCustDob(),
                source.getEftAccountId(),
                source.getPriCardHolderInd(),
                source.getFicoCreditScore());
    }

    /**
     * Commits a competing first-name change over a separate connection.
     *
     * @param firstName the competing value
     * @throws SQLException if the update cannot be applied
     */
    private static void moveReservedCustomer(final String firstName) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE customer SET first_name = ? WHERE cust_id = ?")) {
            statement.setString(1, firstName);
            statement.setString(2, RESERVED_CUSTOMER_ID);
            assertThat(statement.executeUpdate()).isOne();
        }
    }

    /**
     * Removes the one row this class owns.
     *
     * @throws SQLException if cleanup cannot be applied
     */
    private static void removeReservedCustomer() throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM customer WHERE cust_id = ?")) {
            statement.setString(1, RESERVED_CUSTOMER_ID);
            statement.executeUpdate();
        }
    }

    /**
     * Assembles a repository context against the shared migrated server.
     *
     * @return the configured runner
     */
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
}