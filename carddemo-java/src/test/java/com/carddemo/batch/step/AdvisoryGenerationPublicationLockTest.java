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
package com.carddemo.batch.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcOperations;

/**
 * Verifies the coordination contract of the advisory publication lock without a database.
 *
 * <p>The database's own behaviour - that two sessions cannot hold the same advisory key at once - is not
 * mocked and not asserted here, because a mock cannot demonstrate it. It is asserted against a real
 * PostgreSQL server in {@code AdvisoryGenerationPublicationLockIT}. What is asserted here is everything
 * this class decides: which keys it asks for, in which order, with which bound value, under which
 * timeout, and what it does with the transaction when the publication succeeds or fails.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("AdvisoryGenerationPublicationLock: ordered acquisition, bounded waiting, honest failure")
class AdvisoryGenerationPublicationLockTest {

    private JdbcOperations jdbcOperations;
    private Connection connection;
    private PreparedStatement acquisition;
    private List<String> acquiredKeys;
    private AdvisoryGenerationPublicationLock lock;

    @BeforeEach
    void setUp() throws SQLException {
        this.jdbcOperations = mock(JdbcOperations.class);
        this.connection = mock(Connection.class);
        this.acquisition = mock(PreparedStatement.class);
        this.acquiredKeys = new ArrayList<>();

        when(this.connection.getAutoCommit()).thenReturn(true);
        when(this.connection.prepareStatement(AdvisoryGenerationPublicationLock.ACQUIRE_LOCK_SQL))
                .thenReturn(this.acquisition);
        // Record every key in the order it is bound, which is the order the locks are taken.
        doThrowNothingButRecord();
        runCallbackOnTheMockConnection();

        this.lock = new AdvisoryGenerationPublicationLock(this.jdbcOperations);
    }

    /** Captures each bound key so acquisition order can be asserted. */
    private void doThrowNothingButRecord() throws SQLException {
        org.mockito.Mockito.doAnswer(invocation -> {
            this.acquiredKeys.add(invocation.getArgument(1, String.class));
            return null;
        }).when(this.acquisition).setString(eq(1), any(String.class));
    }

    /**
     * Makes the mocked operations run the production callback against the mocked connection.
     *
     * <p>The callback is extracted through the parameterized accessor rather than by naming the raw
     * class literal. A raw {@code ConnectionCallback} would make the generic {@code execute} invocation
     * unchecked, and an unchecked operation is a build failure here, so the type is carried rather than
     * suppressed.</p>
     */
    private void runCallbackOnTheMockConnection() {
        when(this.jdbcOperations.execute(anyConnectionCallback()))
                .thenAnswer(invocation -> {
                    final ConnectionCallback<Void> callback = invocation.getArgument(0);
                    return callback.doInConnection(this.connection);
                });
    }

    /**
     * A matcher for the coordinating callback that carries its type parameter.
     *
     * @return a matcher accepting any {@code ConnectionCallback<Void>}
     */
    private static ConnectionCallback<Void> anyConnectionCallback() {
        return org.mockito.ArgumentMatchers.any();
    }

    @Nested
    @DisplayName("acquisition")
    class Acquisition {

        @Test
        @DisplayName("namespaces every key so these locks cannot collide with the launch locks")
        void keysAreNamespaced() {
            lock.whileHolding(List.of("AWS.M2.CARDDEMO.TRANSACT.BKUP"), () -> { });

            assertThat(acquiredKeys).containsExactly(
                    AdvisoryGenerationPublicationLock.LOCK_NAMESPACE
                            + "AWS.M2.CARDDEMO.TRANSACT.BKUP");
            assertThat(AdvisoryGenerationPublicationLock.LOCK_NAMESPACE)
                    .isNotEqualTo("carddemo.batch.launch:");
        }

        @Test
        @DisplayName("sorts the bases, so two publications naming the same pair cannot deadlock")
        void acquisitionOrderNeverDependsOnTheCaller() {
            lock.whileHolding(List.of("ZZZ.LAST", "AAA.FIRST", "MMM.MIDDLE"), () -> { });
            final List<String> oneOrder = List.copyOf(acquiredKeys);
            acquiredKeys.clear();

            lock.whileHolding(List.of("MMM.MIDDLE", "ZZZ.LAST", "AAA.FIRST"), () -> { });

            assertThat(acquiredKeys).isEqualTo(oneOrder);
            assertThat(acquiredKeys).containsExactly(
                    AdvisoryGenerationPublicationLock.LOCK_NAMESPACE + "AAA.FIRST",
                    AdvisoryGenerationPublicationLock.LOCK_NAMESPACE + "MMM.MIDDLE",
                    AdvisoryGenerationPublicationLock.LOCK_NAMESPACE + "ZZZ.LAST");
        }

        @Test
        @DisplayName("acquires a repeated base once, so what is held matches what was asked for")
        void duplicateBasesCollapse() {
            lock.whileHolding(List.of("ONE.BASE", "ONE.BASE", "OTHER.BASE"), () -> { });

            assertThat(acquiredKeys).containsExactly(
                    AdvisoryGenerationPublicationLock.LOCK_NAMESPACE + "ONE.BASE",
                    AdvisoryGenerationPublicationLock.LOCK_NAMESPACE + "OTHER.BASE");
        }

        @Test
        @DisplayName("bounds the wait through the JDBC timeout rather than by writing a SET statement")
        void waitingIsBounded() throws SQLException {
            lock.whileHolding(List.of("ONE.BASE"), () -> { });

            verify(acquisition).setQueryTimeout(
                    AdvisoryGenerationPublicationLock.ACQUISITION_TIMEOUT_SECONDS);
            assertThat(AdvisoryGenerationPublicationLock.ACQUISITION_TIMEOUT_SECONDS).isPositive();
            // The statement is a constant with one placeholder: nothing is concatenated into SQL text,
            // which is what keeps the unsafe-code audit's raw-SQL count at zero.
            assertThat(AdvisoryGenerationPublicationLock.ACQUIRE_LOCK_SQL)
                    .isEqualTo("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
                    .containsOnlyOnce("?,");
        }

        @Test
        @DisplayName("takes no lock and opens no transaction when the publication touches no base")
        void anEmptyRequestStillRunsTheBody() {
            final List<String> ran = new ArrayList<>();

            lock.whileHolding(List.of(), () -> ran.add("ran"));

            assertThat(ran).containsExactly("ran");
            assertThat(acquiredKeys).isEmpty();
            verify(jdbcOperations, never()).execute(anyConnectionCallback());
        }

        @Test
        @DisplayName("refuses a null base rather than hashing the word null into a key")
        void aNullBaseIsRefused() {
            final List<String> withNull = new ArrayList<>();
            withNull.add("ONE.BASE");
            withNull.add(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> lock.whileHolding(withNull, () -> { }));
        }
    }

    @Nested
    @DisplayName("the transaction the locks live in")
    class TheTransaction {

        @Test
        @DisplayName("runs the publication inside the transaction and commits to release")
        void aSuccessfulPublicationCommits() throws SQLException {
            final List<String> order = new ArrayList<>();
            org.mockito.Mockito.doAnswer(invocation -> {
                order.add("commit");
                return null;
            }).when(connection).commit();

            lock.whileHolding(List.of("ONE.BASE"), () -> order.add("publication"));

            assertThat(order).containsExactly("publication", "commit");
            verify(connection).setAutoCommit(false);
            verify(connection, never()).rollback();
        }

        @Test
        @DisplayName("rolls back and propagates unchanged when the publication fails")
        void aFailedPublicationRollsBackAndPropagates() throws SQLException {
            final IllegalStateException publicationFailure =
                    new IllegalStateException("the artifact could not be uploaded");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> lock.whileHolding(List.of("ONE.BASE"), () -> {
                        throw publicationFailure;
                    }))
                    .isSameAs(publicationFailure);

            verify(connection).rollback();
            verify(connection, never()).commit();
        }

        @Test
        @DisplayName("restores auto-commit before the connection returns to the pool")
        void autoCommitIsRestored() throws SQLException {
            when(connection.getAutoCommit()).thenReturn(true, false);

            lock.whileHolding(List.of("ONE.BASE"), () -> { });

            verify(connection).setAutoCommit(false);
            verify(connection).setAutoCommit(true);
        }

        @Test
        @DisplayName("a failure to acquire fails the publication instead of proceeding unserialized")
        void acquisitionFailureIsNotAFallback() throws SQLException {
            final List<String> ran = new ArrayList<>();
            doThrow(new SQLException("canceling statement due to statement timeout"))
                    .when(acquisition).execute();

            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> lock.whileHolding(List.of("ONE.BASE"), () -> ran.add("ran")));

            assertThat(ran).isEmpty();
            verify(connection).rollback();
        }

        @Test
        @DisplayName("reports an unreachable database as a coordination failure, naming the bases")
        void anUnreachableDatabaseIsReported() {
            // Restubbed with doThrow rather than when, because the when form would call the already
            // stubbed method and run its answer against a matcher placeholder rather than a callback.
            doThrow(new CannotGetJdbcConnectionException("no connection"))
                    .when(jdbcOperations).execute(anyConnectionCallback());

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> lock.whileHolding(List.of("ONE.BASE"), () -> { }))
                    .withMessageContaining("ONE.BASE")
                    .withMessageContaining("could not be serialized")
                    .withCauseInstanceOf(CannotGetJdbcConnectionException.class);
        }

        @Test
        @DisplayName("does not set a query timeout it was never asked to apply to a second statement")
        void onlyTheAcquisitionIsTimed() throws SQLException {
            lock.whileHolding(List.of("ONE.BASE", "OTHER.BASE"), () -> { });

            // One prepared statement per base, each bounded. No other statement is issued at all: the
            // release is the commit, not a written UNLOCK.
            verify(acquisition, org.mockito.Mockito.times(2)).setQueryTimeout(anyInt());
            verify(connection, org.mockito.Mockito.times(2))
                    .prepareStatement(AdvisoryGenerationPublicationLock.ACQUIRE_LOCK_SQL);
            verify(connection, never()).createStatement();
        }
    }
}
