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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.carddemo.domain.Transaction;
import com.carddemo.support.AbstractPostgresIT;

/**
 * Verifies, against a real PostgreSQL 16 server carrying the shipped migrations, the three parts of the
 * transaction master's contract that cannot be proven anywhere else: that identifier allocation is
 * genuinely serialised, that the shape the allocation rule depends on is enforced by the database as
 * well as by the entity, and that the list screen's keyset browse resolves as declared.
 *
 * <h2>Why these need a server</h2>
 *
 * <p>The advisory lock is a function of the database session, so nothing about it is observable without
 * one - a unit test could only assert that a method exists. The keyset finders are derived queries, whose
 * names are resolved into SQL by the persistence provider at bootstrap and never by the compiler, so a
 * misspelled attribute in a method name is a start-up failure that only a context can surface. And a
 * check constraint is enforced by the server alone: the entity's own callback refuses a malformed
 * identifier first, and the point of the constraint is precisely to refuse one that arrived without an
 * entity, which means the only way to exercise it is to write around the entity.
 *
 * <h2>This class is the transaction table's only writer, and it leaves it as it found it</h2>
 *
 * <p>{@code V3__seed_reference_data.sql} seeds no row into this table, and the shared server described by
 * {@code AbstractPostgresIT} is shared by every integration test in the run. Every test here therefore
 * inserts only identifiers in a reserved range that no fixture uses, and removes them in a
 * {@code finally} block, so the table is empty again before the next test observes it. That is what lets
 * the first assertion below - that the maximum over an empty table is absent - hold no matter which
 * order the tests run in.
 *
 * <p>Provenance: the behaviour asserted here is that of {@code app/cbl/COBIL00C.cbl} (identifier
 * minting), {@code app/cbl/CBACT04C.cbl} (the batch identifier form) and {@code app/cbl/COTRN00C.cbl}
 * (the list browse), read as read-only reference at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is transcribed.
 */
@DisplayName("Transaction repository: serialised allocation, enforced identifier shape, keyset browse")
final class TransactionRepositoryIT extends AbstractPostgresIT {

    /** The screen row count of the transaction list, from the legacy loop bounds. */
    private static final int SCREEN_ROWS = 10;

    /**
     * Identifiers reserved for this class, chosen above every value any fixture or seed uses so that the
     * maximum this class inserts is always the table maximum while its rows exist.
     */
    private static final List<String> RESERVED_IDS = List.of(
            "9900000000000010", "9900000000000020", "9900000000000030", "9900000000000040");

    /**
     * A card number the reference seed carries, from {@code app/data/ASCII/carddata.txt}.
     *
     * <p>Every row inserted here names it, because {@code V2__create_indexes.sql} declares a foreign key
     * from this table's card number to the card master: an invented card number is refused by the
     * database, which is itself a small proof that the constraint is in force.
     */
    private static final String SEEDED_CARD = "0500024453765740";

    /** Creates the test class. */
    TransactionRepositoryIT() {
    }

    /**
     * Registers the repository and the entity for a runner-assembled context, so a failure names this
     * interface rather than whichever bean happened to be constructed first.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = TransactionRepository.class)
    @EntityScan(basePackageClasses = Transaction.class)
    static class RepositoryUnderTest {

        /** Creates the configuration. */
        RepositoryUnderTest() {
        }
    }

    @Nested
    @DisplayName("identifier allocation")
    final class IdentifierAllocation {

        /** Creates the nest. */
        IdentifierAllocation() {
        }

        @Test
        @DisplayName("the advisory lock resolves against the server and is granted, which is what makes "
                + "the maximum safe to read for the length of the transaction that read it")
        void theAdvisoryLockIsGranted() {
            runner().run(context -> {
                final TransactionRepository repository = context.getBean(TransactionRepository.class);

                assertThat(repository.lockIdentifierAllocation(
                        TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY))
                        .as("the projection is a constant; the useful effect is that the lock is held")
                        .isOne();
                assertThatNoException()
                        .as("the same session may take the same lock again, so a caller that locks twice "
                                + "in one transaction is not deadlocked by its own lock")
                        .isThrownBy(() -> repository.lockIdentifierAllocation(
                                TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY));
            });
        }

        @Test
        @DisplayName("the maximum is absent over an empty table and is the highest stored identifier "
                + "once rows exist, so the successor of an empty table is the sixteen-digit one")
        void theMaximumFollowsTheLegacyRule() {
            runner().run(context -> {
                final TransactionRepository repository = context.getBean(TransactionRepository.class);

                assertThat(repository.findMaxId())
                        .as("the seed inserts no transaction, and every test here restores that")
                        .isEmpty();
                try {
                    repository.saveAll(List.of(transaction(RESERVED_IDS.get(0)),
                            transaction(RESERVED_IDS.get(1))));
                    repository.flush();

                    assertThat(repository.findMaxId()).contains(RESERVED_IDS.get(1));
                } finally {
                    removeReservedRows(repository);
                }

                assertThat(repository.findMaxId()).isEmpty();
                assertThat(successorOf(repository.findMaxId().orElse("0000000000000000")))
                        .as("an empty table seeds zero, so the first identifier is sixteen digits and "
                                + "never a bare one")
                        .isEqualTo("0000000000000001");
            });
        }
    }

    @Nested
    @DisplayName("the identifier shape the maximum depends on")
    final class IdentifierShape {

        /** Creates the nest. */
        IdentifierShape() {
        }

        @Test
        @DisplayName("the entity refuses a short identifier before any statement reaches the server")
        void theEntityRefusesAShortIdentifier() {
            runner().run(context -> {
                final TransactionRepository repository = context.getBean(TransactionRepository.class);

                assertThatExceptionOfType(InvalidDataAccessApiUsageException.class)
                        .as("the provider raises the guard's own failure and the framework translates it, "
                                + "so a service sees a data-access failure carrying the original cause")
                        .isThrownBy(() -> {
                            repository.save(transaction("123"));
                            repository.flush();
                        })
                        .withRootCauseInstanceOf(IllegalArgumentException.class)
                        .withMessageContaining("tranId")
                        .withMessageContaining("exactly 16 characters");
            });
        }

        @Test
        @DisplayName("the check constraint refuses a short identifier that arrives without an entity, "
                + "which is the case the entity callback cannot cover")
        void theCheckConstraintRefusesAShortIdentifier() throws SQLException {
            try (Connection connection = connect(); Statement statement = connection.createStatement()) {
                assertThatExceptionOfType(SQLException.class)
                        .isThrownBy(() -> statement.executeUpdate(
                                "INSERT INTO transaction (tran_id, tran_type_cd, tran_cat_cd,"
                                        + " tran_source, tran_desc, tran_amt, merchant_id,"
                                        + " merchant_name, merchant_city, merchant_zip, tran_card_num,"
                                        + " tran_orig_ts, tran_proc_ts) VALUES ('9', '01', '0005',"
                                        + " 'System    ', 'probe', 1.00, '000000001', 'm', 'c', 'z',"
                                        + " '" + SEEDED_CARD + "', '2022-07-18 00:00:00.000000',"
                                        + " '2022-07-18 00:00:00.000000')"))
                        .withMessageContaining("ck_transaction_tran_id_digits");
            }
        }
    }

    @Nested
    @DisplayName("the keyset browse of the list screen")
    final class KeysetBrowse {

        /** Creates the nest. */
        KeysetBrowse() {
        }

        @Test
        @DisplayName("a forward read resumes strictly after the cursor, ascends, and stops at the limit, "
                + "so one extra row answers the further-page question without a count")
        void aForwardReadResumesStrictlyAfterTheCursor() {
            runner().run(context -> {
                final TransactionRepository repository = context.getBean(TransactionRepository.class);
                try {
                    seedReservedRows(repository);

                    final List<Transaction> page = repository
                            .findByTranIdGreaterThanOrderByTranIdAsc(RESERVED_IDS.get(0), Limit.of(2));

                    assertThat(page).extracting(Transaction::getTranId)
                            .as("strictly after the cursor, in ascending order, limited to two")
                            .containsExactly(RESERVED_IDS.get(1), RESERVED_IDS.get(2));

                    assertThat(repository.findByTranIdGreaterThanOrderByTranIdAsc(
                            RESERVED_IDS.get(3), Limit.of(SCREEN_ROWS + 1)))
                            .as("no row follows the highest reserved identifier, which is how the last "
                                    + "page is recognised")
                            .isEmpty();
                } finally {
                    removeReservedRows(repository);
                }
            });
        }

        @Test
        @DisplayName("a backward read resumes strictly before the cursor and descends, because the legacy "
                + "path fills its bottom screen slot first and the service reverses the rows")
        void aBackwardReadResumesStrictlyBeforeTheCursorAndDescends() {
            runner().run(context -> {
                final TransactionRepository repository = context.getBean(TransactionRepository.class);
                try {
                    seedReservedRows(repository);

                    final List<Transaction> read = repository
                            .findByTranIdLessThanOrderByTranIdDesc(RESERVED_IDS.get(3), Limit.of(2));

                    assertThat(read).extracting(Transaction::getTranId)
                            .as("descending is the read order")
                            .containsExactly(RESERVED_IDS.get(2), RESERVED_IDS.get(1));
                    assertThat(read.reversed()).extracting(Transaction::getTranId)
                            .as("reversing is what the calling service does before building the page, so "
                                    + "the page the operator sees ascends like a forward page")
                            .containsExactly(RESERVED_IDS.get(1), RESERVED_IDS.get(2));

                    assertThat(repository.findByTranIdLessThanOrderByTranIdDesc(
                            RESERVED_IDS.get(0), Limit.of(SCREEN_ROWS + 1)))
                            .as("no row precedes the lowest reserved identifier, which is how the first "
                                    + "page is recognised")
                            .isEmpty();
                } finally {
                    removeReservedRows(repository);
                }
            });
        }

        @Test
        @DisplayName("the date-range slice still resolves, so adding the keyset finders did not disturb "
                + "the report's declared query")
        void theDateRangeSliceStillResolves() {
            runner().run(context -> {
                final TransactionRepository repository = context.getBean(TransactionRepository.class);
                try {
                    seedReservedRows(repository);

                    assertThat(repository.findByProcessingDateRange("2022-07-18", "2022-07-18",
                            org.springframework.data.domain.PageRequest.of(0, SCREEN_ROWS))
                            .getContent())
                            .as("all four reserved rows carry that processing date")
                            .hasSize(RESERVED_IDS.size());
                } finally {
                    removeReservedRows(repository);
                }
            });
        }
    }

    /**
     * Builds one transaction carrying the supplied identifier and otherwise legitimate fixed-width values.
     *
     * @param tranId the identifier to carry, which may deliberately be malformed
     * @return a transaction ready to be stored
     */
    private static Transaction transaction(final String tranId) {
        return new Transaction(tranId, "01", "0005", "System    ", "repository integration fixture",
                new BigDecimal("1.00"), "000000001", "merchant", "city", "zip       ",
                SEEDED_CARD, "2022-07-18 00:00:00.000000", "2022-07-18 00:00:00.000000");
    }

    /**
     * Inserts the four reserved rows and flushes them, so a subsequent query observes them.
     *
     * @param repository the repository under test
     */
    private static void seedReservedRows(final TransactionRepository repository) {
        repository.saveAll(RESERVED_IDS.stream().map(TransactionRepositoryIT::transaction).toList());
        repository.flush();
    }

    /**
     * Removes every reserved row, restoring the empty table the seed leaves behind.
     *
     * @param repository the repository under test
     */
    private static void removeReservedRows(final TransactionRepository repository) {
        RESERVED_IDS.forEach(repository::deleteById);
        repository.flush();
    }

    /**
     * Reproduces the legacy increment: read the sixteen-character maximum as a number, add one, and move
     * it back into a sixteen-character field, which zero-pads it.
     *
     * @param maximum the current maximum, or the zero seed when the table is empty
     * @return the next identifier, always sixteen digits
     */
    private static String successorOf(final String maximum) {
        return "%016d".formatted(Long.parseLong(maximum) + 1L);
    }

    /**
     * Assembles a context carrying only this repository, against the shared server.
     *
     * @return the configured runner
     */
    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
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
