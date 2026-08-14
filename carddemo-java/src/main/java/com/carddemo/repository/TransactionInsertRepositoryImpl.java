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
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * JPA implementation of the assigned-key, insert-only transaction fragment.
 *
 * <p>{@link EntityManager#persist(Object)} is used deliberately instead of merge semantics. The
 * immediate flush makes a duplicate key, referential-integrity failure or any other database refusal
 * occur while the translated write paragraph is still executing, so that paragraph can preserve its
 * own response ordering rather than discovering the failure at an unrelated transaction boundary.
 */
public class TransactionInsertRepositoryImpl implements TransactionInsertRepository {

    /**
     * Longest a caller will wait for the identifier-allocation lock, in seconds.
     *
     * <p>This is one application-wide key on the busiest online write path, so every concurrent
     * allocator queues on it and each waiter is an online request thread holding a pooled connection.
     * An unbounded wait therefore lets one holder that has stopped progressing consume a thread and a
     * connection per arriving payment until both pools are gone, and the request that started it never
     * receives an answer at all.
     *
     * <p>Ten seconds is chosen against the span the lock actually covers: the maximum is read, an
     * identifier is minted, one row is inserted and one account row is rewritten - all local database
     * work measured in milliseconds, repeated at most twice. A waiter that has queued for ten seconds is
     * therefore not behind a busy system but behind a stuck one, and it is better served by the
     * boundary's terminal answer than by waiting for a holder that will not release.
     *
     * <p>Expiry cancels the waiting statement, which surfaces at the online boundary as the same frozen
     * terminal literal the abend path already uses. No message text and no external contract changes;
     * what changes is that a task the system cannot complete ends instead of hanging, which is the
     * behaviour the legacy region's own transaction time-out gave the same situation.
     */
    static final int ALLOCATION_LOCK_TIMEOUT_SECONDS = 10;

    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param entityManager the persistence context that owns the insert; must not be {@code null}
     */
    public TransactionInsertRepositoryImpl(final EntityManager entityManager,
            final JdbcTemplate jdbcTemplate) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    /**
     * {@inheritDoc}
     *
     * <p>The guard is not redundant with the declared propagation. The propagation is enforced by the
     * repository proxy; this check is enforced by the method, so the invariant holds for a caller that
     * obtained the fragment directly and for any future wiring in which the proxy is absent. Without it the
     * statement would run in its own implicit transaction and release the lock immediately, which
     * serialises nothing while appearing to succeed - a silent failure is the one outcome this operation
     * cannot be allowed to have.
     *
     * <p>The wait is bounded by {@link #ALLOCATION_LOCK_TIMEOUT_SECONDS}. See
     * {@code docs/decision-log.md} entry DL-304 for why a coordination boundary on a request path is
     * always bounded, developed there for the two locks that guard external effects.
     */
    @Override
    public void lockIdentifierAllocation(final long lockKey) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("the identifier allocation lock is"
                    + " transaction-scoped and must be taken inside an existing transaction; taken"
                    + " outside one it would be released as soon as the statement completed and would"
                    + " serialise nothing");
        }
        this.jdbcTemplate.execute("SELECT pg_advisory_xact_lock(?)",
                (PreparedStatementCallback<Void>) statement -> {
            // Bounded, because the waiter is an online request thread holding a pooled connection and
            // this is one application-wide key. The bound is applied to the waiting statement rather
            // than written as a SET, so no value reaches SQL text.
            statement.setQueryTimeout(ALLOCATION_LOCK_TIMEOUT_SECONDS);
            statement.setLong(1, lockKey);
            statement.execute();
            return null;
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Transaction insertAndFlush(final Transaction transaction) {
        final Transaction required = Objects.requireNonNull(transaction, "transaction");
        this.entityManager.persist(required);
        this.entityManager.flush();
        return required;
    }
}
