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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.carddemo.domain.Transaction;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Establishes the write primitives' contract: an insert is an insert, it reaches the store before the
 * method returns, and a refused key is recognised as such.
 *
 * <p>The entity manager is a double, which is the right level for these assertions: what is under test is
 * <em>which</em> persistence operation each primitive performs and <em>when</em> it flushes, not whether the
 * provider can talk to a database. The provider's own behaviour - that a persist of an existing key is
 * refused rather than merged - is exercised against a real PostgreSQL instance by the repository
 * integration tests.
 */
@DisplayName("RecordWriter - the module's write-point primitives")
final class RecordWriterTest {

    /** A record with an assigned key, which is the shape every record in this estate has. */
    private static final Transaction RECORD = new Transaction("0000000000000001", "01", "05",
            "System", "a description", new BigDecimal("1.23"), "000000000000001", "a merchant",
            "a city", "12345", "4111111111111111", "2022-07-19 00:00:00.000000",
            "2022-07-19 00:00:00.000000");

    private EntityManager entityManager;

    private EntityManagerFactory entityManagerFactory;

    private RecordWriter writer;

    @BeforeEach
    void createWriter() {
        this.entityManager = Mockito.mock(EntityManager.class);
        final EntityTransaction transaction = Mockito.mock(EntityTransaction.class);
        Mockito.when(this.entityManager.getTransaction()).thenReturn(transaction);
        Mockito.when(transaction.isActive()).thenReturn(true);
        this.entityManagerFactory = Mockito.mock(EntityManagerFactory.class);
        TransactionSynchronizationManager.bindResource(this.entityManagerFactory,
                new EntityManagerHolder(this.entityManager));
        this.writer = new RecordWriter(this.entityManagerFactory);
    }

    @AfterEach
    void releaseEntityManager() {
        TransactionSynchronizationManager.unbindResource(this.entityManagerFactory);
    }

    @Test
    @DisplayName("the factory is mandatory, because a writer without one could write nothing")
    void theFactoryIsMandatory() {
        assertThatNullPointerException().isThrownBy(() -> new RecordWriter(null));
    }

    @Nested
    @DisplayName("insert - the legacy WRITE verb")
    final class Insert {

        @Test
        @DisplayName("persists rather than merges, so an existing key is refused instead of updated")
        void persistsRatherThanMerges() {
            writer.insert(RECORD);

            Mockito.verify(entityManager).persist(RECORD);
            Mockito.verify(entityManager, Mockito.never()).merge(Mockito.any());
        }

        @Test
        @DisplayName("flushes after persisting, so the failure arrives where the legacy arm handles it")
        void flushesAfterPersisting() {
            writer.insert(RECORD);

            final InOrder order = Mockito.inOrder(entityManager);
            order.verify(entityManager).persist(RECORD);
            order.verify(entityManager).flush();
        }

        @Test
        @DisplayName("hands back the very record it was given, now managed")
        void handsBackTheRecordItWasGiven() {
            assertThat(writer.insert(RECORD)).isSameAs(RECORD);
        }

        @Test
        @DisplayName("refuses an absent record rather than flushing an empty unit of work")
        void refusesAnAbsentRecord() {
            assertThatNullPointerException().isThrownBy(() -> writer.insert(null));
            Mockito.verify(entityManager, Mockito.never()).flush();
        }
    }

    @Nested
    @DisplayName("insertIndependently - the same verb, in its own unit of work")
    final class InsertIndependently {

        @Test
        @DisplayName("persists and flushes exactly as the joined form does")
        void persistsAndFlushes() {
            assertThat(writer.insertIndependently(RECORD)).isSameAs(RECORD);

            final InOrder order = Mockito.inOrder(entityManager);
            order.verify(entityManager).persist(RECORD);
            order.verify(entityManager).flush();
            Mockito.verify(entityManager, Mockito.never()).merge(Mockito.any());
        }

        @Test
        @DisplayName("refuses an absent record")
        void refusesAnAbsentRecord() {
            assertThatNullPointerException().isThrownBy(() -> writer.insertIndependently(null));
        }
    }

    @Nested
    @DisplayName("insertAll - a group, in the order the caller supplied")
    final class InsertAll {

        @Test
        @DisplayName("persists every record in the given order, then flushes once")
        void persistsEveryRecordInOrderThenFlushesOnce() {
            final Transaction second = new Transaction("0000000000000002", "01", "05", "System",
                    "another description", new BigDecimal("2.34"), "000000000000002", "a merchant",
                    "a city", "12345", "4111111111111111", "2022-07-19 00:00:00.000000",
                    "2022-07-19 00:00:00.000000");

            writer.insertAll(List.of(RECORD, second));

            final InOrder order = Mockito.inOrder(entityManager);
            order.verify(entityManager).persist(RECORD);
            order.verify(entityManager).persist(second);
            order.verify(entityManager).flush();
            Mockito.verify(entityManager, Mockito.times(1)).flush();
        }

        @Test
        @DisplayName("an empty group does nothing at all, not even a flush")
        void anEmptyGroupDoesNothing() {
            writer.insertAll(List.of());

            Mockito.verifyNoInteractions(entityManager);
        }

        @Test
        @DisplayName("refuses an absent group")
        void refusesAnAbsentGroup() {
            assertThatNullPointerException().isThrownBy(() -> writer.insertAll(null));
        }
    }

    @Nested
    @DisplayName("flush - the write-point flush for a narrow repository")
    final class Flush {

        @Test
        @DisplayName("issues the enqueued statements and writes nothing of its own")
        void issuesTheEnqueuedStatements() {
            writer.flush();

            Mockito.verify(entityManager).flush();
            Mockito.verify(entityManager, Mockito.never()).persist(Mockito.any());
            Mockito.verify(entityManager, Mockito.never()).merge(Mockito.any());
        }
    }

    @Nested
    @DisplayName("isDuplicateKey - the refused-key classification the duplicate arms share")
    final class IsDuplicateKey {

        @Test
        @DisplayName("recognises the provider's own duplicate-entity failure")
        void recognisesTheProvidersDuplicateEntityFailure() {
            assertThat(RecordWriter.isDuplicateKey(new EntityExistsException("already there")))
                    .isTrue();
        }

        @Test
        @DisplayName("recognises the framework's duplicate-specific type, and only that one")
        void recognisesTheFrameworksDuplicateSpecificType() {
            assertThat(RecordWriter.isDuplicateKey(new DuplicateKeyException("refused")))
                    .as("DuplicateKeyException means this key is already present and nothing else")
                    .isTrue();
            assertThat(RecordWriter.isDuplicateKey(new DataIntegrityViolationException("refused")))
                    .as("its supertype is raised for a foreign-key, not-null or check refusal exactly "
                            + "as readily, so on its own it is NOT evidence of a duplicate")
                    .isFalse();
        }

        @Test
        @DisplayName("the complete unique-violation state is the refused key")
        void theCompleteUniqueViolationStateIsTheRefusedKey() {
            assertThat(RecordWriter.isDuplicateKey(new SQLException("refused", "23505"))).isTrue();
        }

        @ParameterizedTest(name = "SQL state {0} is not a refused key")
        @ValueSource(strings = {"23000", "23001", "23502", "23503", "23514", "22001", "22P02",
            "40001", "40P01"})
        @DisplayName("no other SQL state is a refused key, including the rest of the integrity family")
        void noOtherSqlStateIsARefusedKey(final String state) {
            assertThat(RecordWriter.isDuplicateKey(new SQLException("refused", state)))
                    .as("state %s reports a different condition, whose arm is the write paragraph's "
                            + "catch-all rather than the duplicate arm", state)
                    .isFalse();
        }

        @ParameterizedTest(name = "SQL state {0} beneath the translated type is not a duplicate")
        @ValueSource(strings = {"23000", "23001", "23502", "23503", "23514", "22001"})
        @DisplayName("the store's own report outranks the translated type, so a non-unique integrity "
                + "refusal is never reported as a duplicate")
        void theStoresReportOutranksTheTranslatedType(final String state) {
            final Throwable translated = new DataIntegrityViolationException("refused",
                    new SQLIntegrityConstraintViolationException("refused", state));

            assertThat(RecordWriter.isDuplicateKey(translated))
                    .as("the JDBC integrity-constraint TYPE is present and the state is %s, so the "
                            + "state decides", state)
                    .isFalse();
        }

        @Test
        @DisplayName("the unique violation is still found beneath the translated integrity type")
        void theUniqueViolationIsStillFoundBeneathTheTranslatedType() {
            final Throwable translated = new DataIntegrityViolationException("refused",
                    new SQLIntegrityConstraintViolationException("refused", "23505"));

            assertThat(RecordWriter.isDuplicateKey(translated)).isTrue();
        }

        @Test
        @DisplayName("the driver's OWN next-exception chain is searched, which is where PostgreSQL "
                + "hangs the refusal a batched flush met")
        void theDriversNextExceptionChainIsSearched() {
            final SQLException carrier = new SQLException("batch failed", (String) null);
            carrier.setNextException(new SQLException("refused", "23505"));

            assertThat(RecordWriter.isDuplicateKey(new DataIntegrityViolationException("wrapped",
                    carrier)))
                    .as("following only the cause chain stops at a carrier whose own state is null "
                            + "and misses the unique violation hanging off it")
                    .isTrue();
        }

        @Test
        @DisplayName("a next-exception chain reporting a different condition is not a refused key")
        void aNextExceptionChainReportingAnotherConditionIsNotARefusedKey() {
            final SQLException carrier = new SQLException("batch failed", (String) null);
            carrier.setNextException(new SQLException("refused", "23503"));

            assertThat(RecordWriter.isDuplicateKey(new DataIntegrityViolationException("wrapped",
                    carrier))).isFalse();
        }

        @Test
        @DisplayName("a stateless driver failure demotes nothing, so a duplicate-specific type still "
                + "decides")
        void aStatelessDriverFailureDemotesNothing() {
            final Throwable translated = new DuplicateKeyException("refused",
                    new SQLException("refused", (String) null));

            assertThat(RecordWriter.isDuplicateKey(translated)).isTrue();
        }

        @Test
        @DisplayName("a self-referencing next-exception chain terminates instead of looping forever")
        void aSelfReferencingNextExceptionChainTerminates() {
            final SQLException selfReferencing = new SQLException("loops", "23503") {
                private static final long serialVersionUID = 1L;

                @Override
                public SQLException getNextException() {
                    return this;
                }
            };

            assertThat(RecordWriter.isDuplicateKey(selfReferencing)).isFalse();
        }

        @Test
        @DisplayName("finds the condition beneath a wrapper, because a translated failure carries a cause")
        void findsTheConditionBeneathAWrapper() {
            final Throwable wrapped = new IllegalStateException("outer",
                    new IllegalStateException("middle", new EntityExistsException("already there")));

            assertThat(RecordWriter.isDuplicateKey(wrapped)).isTrue();
        }

        @Test
        @DisplayName("a conflict that is not a refused key is not reported as one")
        void aConflictThatIsNotARefusedKeyIsNotReportedAsOne() {
            assertThat(RecordWriter.isDuplicateKey(new OptimisticLockingFailureException("moved")))
                    .isFalse();
            assertThat(RecordWriter.isDuplicateKey(new IllegalStateException("unrelated"))).isFalse();
        }

        @Test
        @DisplayName("an absent failure is not a refused key")
        void anAbsentFailureIsNotARefusedKey() {
            assertThat(RecordWriter.isDuplicateKey(null)).isFalse();
        }

        @Test
        @DisplayName("a self-referencing cause chain terminates instead of looping forever")
        void aSelfReferencingCauseChainTerminates() {
            final RuntimeException selfReferencing = new RuntimeException("loops") {
                private static final long serialVersionUID = 1L;

                @Override
                public synchronized Throwable getCause() {
                    return this;
                }
            };

            assertThat(RecordWriter.isDuplicateKey(selfReferencing)).isFalse();
        }
    }
}
