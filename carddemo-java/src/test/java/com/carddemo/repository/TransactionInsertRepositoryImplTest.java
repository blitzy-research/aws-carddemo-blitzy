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

import com.carddemo.domain.Transaction;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Verifies that the assigned-key repository fragment is persist-and-flush only, never merge.
 */
@DisplayName("TransactionInsertRepositoryImpl: assigned keys are inserted and flushed")
final class TransactionInsertRepositoryImplTest {

    @Test
    @DisplayName("the entity manager is mandatory")
    void theEntityManagerIsMandatory() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TransactionInsertRepositoryImpl(
                        null, Mockito.mock(JdbcTemplate.class)));
        assertThatNullPointerException()
                .isThrownBy(() -> new TransactionInsertRepositoryImpl(
                        Mockito.mock(EntityManager.class), null));
    }

    @Test
    @DisplayName("one transaction is persisted, flushed and returned without a merge")
    void oneTransactionIsPersistedFlushedAndReturned() {
        final EntityManager entityManager = Mockito.mock(EntityManager.class);
        final TransactionInsertRepositoryImpl repository =
                new TransactionInsertRepositoryImpl(entityManager, Mockito.mock(JdbcTemplate.class));
        final Transaction transaction = transaction("9900000000000091");

        assertThat(repository.insertAndFlush(transaction)).isSameAs(transaction);

        final InOrder order = Mockito.inOrder(entityManager);
        order.verify(entityManager).persist(transaction);
        order.verify(entityManager).flush();
        Mockito.verify(entityManager, Mockito.never()).merge(Mockito.any());
    }

    @Test
    @DisplayName("a null record is refused before persistence is touched")
    void aNullRecordIsRefusedBeforePersistence() {
        final EntityManager entityManager = Mockito.mock(EntityManager.class);
        final TransactionInsertRepositoryImpl repository =
                new TransactionInsertRepositoryImpl(entityManager, Mockito.mock(JdbcTemplate.class));

        assertThatNullPointerException().isThrownBy(() -> repository.insertAndFlush(null));

        Mockito.verifyNoInteractions(entityManager);
    }

    @Test
    @DisplayName("a flush failure is propagated at the insert boundary")
    void aFlushFailureIsPropagatedAtTheInsertBoundary() {
        final EntityManager entityManager = Mockito.mock(EntityManager.class);
        final TransactionInsertRepositoryImpl repository =
                new TransactionInsertRepositoryImpl(entityManager, Mockito.mock(JdbcTemplate.class));
        final Transaction transaction = transaction("9900000000000092");
        final IllegalStateException refused = new IllegalStateException("database refused insert");
        Mockito.doThrow(refused).when(entityManager).flush();

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> repository.insertAndFlush(transaction))
                .isSameAs(refused);

        Mockito.verify(entityManager).persist(transaction);
    }

    @Test
    @DisplayName("the allocation lock is refused outside a transaction, where it would serialise nothing")
    void theAllocationLockIsTransactionMandatory() {
        final JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        final TransactionInsertRepositoryImpl repository = new TransactionInsertRepositoryImpl(
                Mockito.mock(EntityManager.class), jdbcTemplate);

        assertThatExceptionOfType(IllegalTransactionStateException.class)
                .isThrownBy(() -> repository.lockIdentifierAllocation(
                        TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY))
                .withMessageContaining("transaction-scoped");

        Mockito.verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("the wait for the allocation lock is bounded, so one stuck holder cannot drain the pools")
    void theWaitForTheAllocationLockIsBounded() throws Exception {
        // This is ONE application-wide key on the busiest online write path, and every waiter is a
        // request thread holding a pooled connection. Waiting on it without a bound is what lets a
        // holder that has stopped progressing consume a thread and a connection per arriving payment,
        // and the request that started it never receives an answer at all. The bound is applied to the
        // waiting statement rather than written as a SET, so no value reaches SQL text.
        final JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        final PreparedStatement acquisition = Mockito.mock(PreparedStatement.class);
        Mockito.when(jdbcTemplate.execute(Mockito.eq("SELECT pg_advisory_xact_lock(?)"),
                        TransactionInsertRepositoryImplTest.<Void>anyPreparedStatementCallback()))
                .thenAnswer(invocation -> {
                    final PreparedStatementCallback<Void> callback = invocation.getArgument(1);
                    return callback.doInPreparedStatement(acquisition);
                });
        final TransactionInsertRepositoryImpl repository = new TransactionInsertRepositoryImpl(
                Mockito.mock(EntityManager.class), jdbcTemplate);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            repository.lockIdentifierAllocation(
                    TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        Mockito.verify(acquisition).setQueryTimeout(
                TransactionInsertRepositoryImpl.ALLOCATION_LOCK_TIMEOUT_SECONDS);
        Mockito.verify(acquisition).setLong(1,
                TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY);
        Mockito.verify(acquisition).execute();
        assertThat(TransactionInsertRepositoryImpl.ALLOCATION_LOCK_TIMEOUT_SECONDS)
                .as("the driver reads zero or less as unlimited, so such a value is no bound at all")
                .isPositive();
    }

    /**
     * A matcher for the acquisition callback that carries its type parameter.
     *
     * <p>Named rather than inlined so the generic {@code execute} invocation stays checked: a raw
     * {@code PreparedStatementCallback} would make it unchecked, and an unchecked operation is a build
     * failure here, so the type is carried rather than suppressed.
     *
     * @param  <T> the callback's result type
     * @return a matcher accepting any callback of that type
     */
    private static <T> PreparedStatementCallback<T> anyPreparedStatementCallback() {
        return Mockito.any();
    }

    private static Transaction transaction(final String tranId) {
        return new Transaction(tranId, "01", "0005", "System    ", "insert-only unit fixture",
                new BigDecimal("1.00"), "000000001", "merchant", "city", "zip       ",
                "0500024453765740", "2022-07-18 00:00:00.000000",
                "2022-07-18 00:00:00.000000");
    }
}
