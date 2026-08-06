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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
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
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.carddemo.domain.Transaction;
import com.carddemo.support.AbstractPostgresIT;

/**
 * Verifies, against a real PostgreSQL 16 server carrying the shipped migrations, the transaction
 * repository's two declared JPQL primitives, the identifier shape on which maximum-plus-one allocation
 * depends, and the inherited pageable browse used by the transaction-list screen.
 *
 * <h2>Why these need a server</h2>
 *
 * <p>Both declared queries are parsed by the persistence provider at bootstrap rather than by the Java
 * compiler, while the identifier check constraint is enforced by the server alone. The frozen repository
 * intentionally declares neither a native advisory-lock query nor keyset finders: allocation concurrency
 * policy belongs to the bill-payment service, where a duplicate insert follows the legacy already-exists
 * response, and list positioning is emulated by the service over inherited {@code findAll(Pageable)}
 * pages. This class therefore tests the actual two-method surface instead of preserving test-only
 * repository capabilities.
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
@DisplayName("Transaction repository: maximum query, identifier shape, pageable browse")
final class TransactionRepositoryIT extends AbstractPostgresIT {

    /** The screen row count of the transaction list, from the legacy loop bounds. */
    private static final int SCREEN_ROWS = 10;

    /**
     * Identifiers reserved for this class, chosen above every value any fixture or seed uses so that the
     * maximum this class inserts is always the table maximum while its rows exist.
     */
    private static final List<String> RESERVED_IDS = List.of(
            "9900000000000010", "9900000000000020", "9900000000000030", "9900000000000040",
            "9900000000000050", "9900000000000060", "9900000000000070", "9900000000000080",
            "9900000000000090", "9900000000000100", "9900000000000110", "9900000000000120");

    /**
     * The three identifiers the concurrency nest can create: the base row it seeds so that the table
     * maximum is known, and the two successors two allocators derive from it.
     *
     * <p>They are declared literally rather than computed so that the cleanup below removes exactly what
     * the nest can have created, whatever any individual test managed to commit before it failed. They sit
     * clear of the four identifiers the other nests reserve.
     */
    private static final List<String> CONCURRENCY_IDS = List.of(
            "9900000000000050", "9900000000000051", "9900000000000052");

    /**
     * How long a test waits before concluding that a blocked allocator really is blocked, in
     * milliseconds.
     *
     * <p>A bound is needed because a lock wait has no completion to observe. It cannot produce a false
     * failure: the assertion is that the wait <em>times out</em>, so a slow machine only makes the
     * conclusion safer, while a lock that failed to serialise completes immediately and fails the test.
     */
    private static final long LOCK_WAIT_PROBE_MILLIS = 400L;

    /** How long a test waits for a released lock to be handed over, in seconds. */
    private static final long LOCK_HANDOVER_TIMEOUT_SECONDS = 20L;

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

    /** Verifies the maximum primitive without adding a repository-level concurrency policy. */
    @Nested
    @DisplayName("identifier allocation")
    final class IdentifierAllocation {

        /** Creates the nest. */
        IdentifierAllocation() {
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
    @DisplayName("the allocation lock under genuine concurrency")
    final class ConcurrentAllocation {

        /** Creates the nest. */
        ConcurrentAllocation() {
        }

        @Test
        @DisplayName("the lock is EXCLUSIVE across sessions: while one transaction holds it, a second "
                + "session cannot take it, which is what admits one allocator at a time")
        void theLockIsExclusiveAcrossSessions() throws SQLException {
            try (Connection holder = connect(); Connection probe = connect()) {
                holder.setAutoCommit(false);
                probe.setAutoCommit(false);
                try {
                    takeAllocationLock(holder);

                    assertThat(tryTakeAllocationLock(probe))
                            .as("a second session is refused while the first transaction holds the lock")
                            .isFalse();

                    // Releasing is the commit; there is no explicit unlock to forget.
                    holder.commit();

                    assertThat(tryTakeAllocationLock(probe))
                            .as("the lock is released by the commit, so the next allocator is admitted")
                            .isTrue();
                } finally {
                    probe.rollback();
                    holder.rollback();
                }
            }
        }

        @Test
        @DisplayName("WITHOUT the lock two allocators genuinely collide: both read the same maximum, "
                + "both derive the same successor, and the second insert is refused on the primary key")
        void withoutTheLockTwoAllocatorsCollide() throws SQLException {
            try (Connection first = connect(); Connection second = connect()) {
                first.setAutoCommit(false);
                second.setAutoCommit(false);
                try {
                    seedConcurrencyBase();

                    // Both read before either writes, which is exactly what READ COMMITTED permits: an
                    // uncommitted insert is invisible, so a shared transaction is not enough on its own.
                    final String firstSeen = highestIdentifier(first);
                    final String secondSeen = highestIdentifier(second);
                    assertThat(firstSeen)
                            .as("the base row this nest seeded is the table maximum, which is what makes "
                                    + "every identifier it derives predictable and removable")
                            .isEqualTo(CONCURRENCY_IDS.get(0));
                    assertThat(secondSeen)
                            .as("neither allocator can see the other's pending work")
                            .isEqualTo(firstSeen);

                    insertTransaction(first, successorOf(firstSeen));
                    first.commit();

                    assertThatExceptionOfType(SQLException.class)
                            .as("the loser is refused for a reason that has nothing to do with its own "
                                    + "work, which is the defect the advisory lock closes")
                            .isThrownBy(() -> {
                                insertTransaction(second, successorOf(secondSeen));
                                second.commit();
                            })
                            .withMessageContaining("pk_transaction");
                } finally {
                    second.rollback();
                    first.rollback();
                    removeConcurrencyRows();
                }
            }
        }

        @Test
        @DisplayName("UNDER the lock the second allocator waits for the first to commit and then reads a "
                + "maximum that already includes the first row, so both succeed and neither collides")
        void underTheLockTheSecondAllocatorWaitsAndSeesTheFirstRow() throws Exception {
            final ExecutorService waiter = Executors.newSingleThreadExecutor();
            try (Connection first = connect()) {
                seedConcurrencyBase();
                first.setAutoCommit(false);
                takeAllocationLock(first);
                final String firstIdentifier = successorOf(highestIdentifier(first));
                insertTransaction(first, firstIdentifier);

                // The second allocator takes the lock before reading, exactly as the services do, so it
                // cannot proceed until the first transaction ends.
                final Future<String> secondIdentifier = waiter.submit(() -> {
                    try (Connection second = connect()) {
                        second.setAutoCommit(false);
                        takeAllocationLock(second);
                        final String minted = successorOf(highestIdentifier(second));
                        insertTransaction(second, minted);
                        second.commit();
                        return minted;
                    }
                });

                assertThatExceptionOfType(TimeoutException.class)
                        .as("the second allocator is still waiting on the lock, which is the "
                                + "serialisation the legacy region obtained from its held browse")
                        .isThrownBy(() -> secondIdentifier.get(LOCK_WAIT_PROBE_MILLIS,
                                TimeUnit.MILLISECONDS));

                first.commit();

                assertThat(secondIdentifier.get(LOCK_HANDOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("the re-read under the lock sees the committed row, so the successor is the "
                                + "next value and not a duplicate")
                        .isEqualTo(successorOf(firstIdentifier));
            } finally {
                waiter.shutdownNow();
                assertThat(waiter.awaitTermination(LOCK_HANDOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("no waiting thread outlives this test")
                        .isTrue();
                removeConcurrencyRows();
            }
        }
    }

    @Nested
    @DisplayName("insert-only assigned-key persistence")
    final class InsertOnlyPersistence {

        /** Creates the nest. */
        InsertOnlyPersistence() {
        }

        @Test
        @DisplayName("a duplicate assigned identifier is refused and the existing row is not merged")
        void aDuplicateAssignedIdentifierIsRefusedWithoutOverwriting() {
            runner().run(context -> {
                final TransactionRepository repository =
                        context.getBean(TransactionRepository.class);
                final String reservedId = RESERVED_IDS.get(0);
                final Transaction original = transaction(reservedId);
                final Transaction replacement = transaction(reservedId);
                replacement.setTranDesc("replacement that must never be merged");

                try {
                    assertThat(repository.insertAndFlush(original)).isSameAs(original);

                    assertThatExceptionOfType(DataIntegrityViolationException.class)
                            .isThrownBy(() -> repository.insertAndFlush(replacement));

                    assertThat(repository.findById(reservedId))
                            .get()
                            .extracting(Transaction::getTranDesc)
                            .isEqualTo("repository integration fixture");
                } finally {
                    removeReservedRows(repository);
                }
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

    /**
     * Verifies the bounded ordered reads the transaction-list browse and the batch archive scan share.
     *
     * <p>{@link TransactionScanRepository} publishes four reads - inclusive and exclusive, ascending and
     * descending - and no offset page at all. The pair matters: the inclusive form is the positioning
     * command, whose first row is the record the browse positions on, and the exclusive form is the
     * continuation, which must not repeat the row already handed out. Both are derived query names, so
     * only a started context proves they resolve, and only real SQL proves the bound is applied by the
     * store rather than by the caller.
     */
    @Nested
    @DisplayName("the bounded keyset reads of the list browse and the archive scan")
    final class KeysetBrowse {

        /** Creates the nest. */
        KeysetBrowse() {
        }

        @Test
        @DisplayName("the inclusive forward read opens on the boundary row and the exclusive read "
                + "continues past it, so successive windows neither repeat nor skip a row")
        void inclusiveOpenAndExclusiveContinuationTileTheSequence() {
            runner().run(context -> {
                final TransactionRepository repository = context.getBean(TransactionRepository.class);
                final TransactionScanRepository scan =
                        context.getBean(TransactionScanRepository.class);
                try {
                    seedReservedRows(repository);

                    final List<Transaction> opened =
                            scan.findByTranIdGreaterThanEqualOrderByTranIdAsc(
                                    RESERVED_IDS.get(0), Limit.of(SCREEN_ROWS));
                    final List<Transaction> continued =
                            scan.findByTranIdGreaterThanOrderByTranIdAsc(
                                    identifiersOf(opened).get(SCREEN_ROWS - 1), Limit.of(SCREEN_ROWS));

                    assertThat(identifiersOf(opened))
                            .as("the opening read is inclusive, so the boundary row is its first row")
                            .containsExactlyElementsOf(RESERVED_IDS.subList(0, SCREEN_ROWS));
                    assertThat(identifiersOf(continued))
                            .as("the continuation is strict, so it starts after the last row handed out")
                            .containsExactlyElementsOf(
                                    RESERVED_IDS.subList(SCREEN_ROWS, RESERVED_IDS.size()));
                } finally {
                    removeReservedRows(repository);
                }
            });
        }

        @Test
        @DisplayName("the backward reads are descending, and reversing the read order yields the "
                + "ascending page the screen presents")
        void theBackwardReadsAreDescendingAndReverseIntoScreenOrder() {
            runner().run(context -> {
                final TransactionRepository repository = context.getBean(TransactionRepository.class);
                final TransactionScanRepository scan =
                        context.getBean(TransactionScanRepository.class);
                try {
                    seedReservedRows(repository);
                    final String highestReserved = RESERVED_IDS.get(RESERVED_IDS.size() - 1);

                    final List<String> readOrder = identifiersOf(
                            scan.findByTranIdLessThanEqualOrderByTranIdDesc(
                                    highestReserved, Limit.of(SCREEN_ROWS)));
                    final List<String> strictlyBefore = identifiersOf(
                            scan.findByTranIdLessThanOrderByTranIdDesc(
                                    highestReserved, Limit.of(1)));

                    assertThat(readOrder)
                            .as("descending is the read order the legacy backward path uses")
                            .isSortedAccordingTo(Comparator.reverseOrder());
                    assertThat(readOrder.get(0))
                            .as("the inclusive backward open positions on the boundary row itself")
                            .isEqualTo(highestReserved);
                    assertThat(readOrder.reversed())
                            .as("reading the rows in reverse of the read order is what makes the "
                                    + "assembled page ascend, exactly like a forward page")
                            .isSorted();
                    assertThat(strictlyBefore)
                            .as("the strict backward continuation never repeats the boundary row")
                            .doesNotContain(highestReserved)
                            .hasSize(1);
                } finally {
                    removeReservedRows(repository);
                }
            });
        }

        @Test
        @DisplayName("every read honours its limit and a blank bound opens the sequence forwards while "
                + "positioning nowhere backwards")
        void boundsAndLimitsBehaveAtTheEndsOfTheSequence() {
            runner().run(context -> {
                final TransactionRepository repository = context.getBean(TransactionRepository.class);
                final TransactionScanRepository scan =
                        context.getBean(TransactionScanRepository.class);
                try {
                    seedReservedRows(repository);

                    assertThat(scan.findByTranIdGreaterThanEqualOrderByTranIdAsc("", Limit.of(3)))
                            .as("a blank bound is below every stored identifier, so a forward open "
                                    + "starts at the first row and the limit truncates")
                            .extracting(Transaction::getTranId)
                            .containsExactlyElementsOf(RESERVED_IDS.subList(0, 3));
                    assertThat(scan.findByTranIdLessThanEqualOrderByTranIdDesc("", Limit.of(3)))
                            .as("read backwards, no row lies at or before a blank bound")
                            .isEmpty();
                    assertThat(scan.findByTranIdGreaterThanOrderByTranIdAsc(
                                    RESERVED_IDS.get(RESERVED_IDS.size() - 1), Limit.of(SCREEN_ROWS)))
                            .as("nothing follows the highest identifier, which is the end-of-file arm")
                            .isEmpty();
                } finally {
                    removeReservedRows(repository);
                }
            });
        }
    }

    /**
     * Returns the identifiers of a read window in read order.
     *
     * @param window the rows the read returned
     * @return their identifiers, in the order the window presents them
     */
    private static List<String> identifiersOf(final List<Transaction> window) {
        return window.stream().map(Transaction::getTranId).toList();
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
     * Inserts the reserved rows and flushes them, so a subsequent query observes them.
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
     * Takes the allocation lock on the supplied session, through the very statement the repository
     * declares, so that what the concurrency tests exercise is the shipped lock and not a lookalike.
     *
     * @param connection the session to take the lock on, with autocommit already disabled
     * @throws SQLException if the statement fails
     */
    private static void takeAllocationLock(final Connection connection) throws SQLException {
        try (PreparedStatement lock = connection
                .prepareStatement("SELECT 1 FROM pg_advisory_xact_lock(?)")) {
            lock.setLong(1, TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY);
            try (ResultSet held = lock.executeQuery()) {
                assertThat(held.next())
                        .as("the projection exists only because a select must project something")
                        .isTrue();
            }
        }
    }

    /**
     * Attempts the allocation lock on the supplied session without waiting, which is how a second session
     * can report whether the lock is currently held elsewhere.
     *
     * @param connection the session to attempt the lock on, with autocommit already disabled
     * @return {@code true} when the lock was granted
     * @throws SQLException if the statement fails
     */
    private static boolean tryTakeAllocationLock(final Connection connection) throws SQLException {
        try (PreparedStatement attempt = connection
                .prepareStatement("SELECT pg_try_advisory_xact_lock(?)")) {
            attempt.setLong(1, TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY);
            try (ResultSet granted = attempt.executeQuery()) {
                assertThat(granted.next()).isTrue();
                return granted.getBoolean(1);
            }
        }
    }

    /**
     * Reads the highest stored identifier on the supplied session, which is the read half of the legacy
     * allocation rule expressed as the maximum over the key column.
     *
     * @param connection the session to read on
     * @return the highest stored identifier, or the sixteen-zero seed when the table holds no rows
     * @throws SQLException if the statement fails
     */
    private static String highestIdentifier(final Connection connection) throws SQLException {
        try (Statement maximum = connection.createStatement();
                ResultSet row = maximum.executeQuery("SELECT MAX(tran_id) FROM transaction")) {
            assertThat(row.next()).isTrue();
            final String highest = row.getString(1);
            return highest == null ? "0".repeat(16) : highest;
        }
    }

    /**
     * Inserts one transaction on the supplied session without committing, so a caller controls when the
     * row becomes visible to another session.
     *
     * @param connection the session to insert on
     * @param tranId     the identifier to store
     * @throws SQLException if the insert fails, which the collision test relies on
     */
    private static void insertTransaction(final Connection connection, final String tranId)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO transaction (tran_id, tran_type_cd, tran_cat_cd, tran_source, tran_desc,"
                        + " tran_amt, merchant_id, merchant_name, merchant_city, merchant_zip,"
                        + " tran_card_num, tran_orig_ts, tran_proc_ts) VALUES (?, '01', '0005',"
                        + " 'System    ', 'concurrency fixture', 1.00, '000000001', 'm', 'c',"
                        + " 'zip       ', ?, '2022-07-18 00:00:00.000000',"
                        + " '2022-07-18 00:00:00.000000')")) {
            insert.setString(1, tranId);
            insert.setString(2, SEEDED_CARD);
            insert.executeUpdate();
        }
    }

    /**
     * Commits the base row the concurrency nest derives its identifiers from, so that the table maximum is
     * a known value rather than whatever the run happens to have left behind.
     *
     * @throws SQLException if the insert fails
     */
    private static void seedConcurrencyBase() throws SQLException {
        removeConcurrencyRows();
        try (Connection connection = connect()) {
            insertTransaction(connection, CONCURRENCY_IDS.get(0));
        }
    }

    /**
     * Removes every row the concurrency nest can have created, restoring the empty table the seed leaves
     * behind so the maximum-over-an-empty-table assertion elsewhere still holds.
     *
     * @throws SQLException if the delete fails
     */
    private static void removeConcurrencyRows() throws SQLException {
        try (Connection connection = connect();
                PreparedStatement delete = connection
                        .prepareStatement("DELETE FROM transaction WHERE tran_id = ?")) {
            for (final String tranId : CONCURRENCY_IDS) {
                delete.setString(1, tranId);
                delete.executeUpdate();
            }
        }
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
