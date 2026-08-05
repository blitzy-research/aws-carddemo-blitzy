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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

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

    private static Transaction transaction(final String tranId) {
        return new Transaction(tranId, "01", "0005", "System    ", "insert-only unit fixture",
                new BigDecimal("1.00"), "000000001", "merchant", "city", "zip       ",
                "0500024453765740", "2022-07-18 00:00:00.000000",
                "2022-07-18 00:00:00.000000");
    }
}