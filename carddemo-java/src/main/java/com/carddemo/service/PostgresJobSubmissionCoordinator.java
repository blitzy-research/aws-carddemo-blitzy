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

import java.sql.PreparedStatement;
import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL-backed deployment-wide serialization of the seventeen-card submission stream.
 *
 * <p>A transaction-scoped advisory lock is used because every application replica already shares the
 * configured PostgreSQL database and the lock needs no table, row, migration or cleanup job. The two
 * integer lock keys form a namespace owned by this module; they identify the {@code CARD/JOBS}
 * boundary and are never derived from a request. A prepared statement supplies them as parameters, so
 * no SQL text is assembled from data.
 *
 * <p>The lock is acquired inside a {@link TransactionDefinition#PROPAGATION_REQUIRES_NEW new
 * transaction}. PostgreSQL releases a transaction-scoped advisory lock on both commit and rollback,
 * which means an application crash, an unexpected exception or a normal return all release it without
 * a second unlock statement. The new transaction also prevents an unrelated caller transaction from
 * extending the lock beyond the card stream.
 *
 * <p>This coordinator persists no business data. It provides the cross-replica exclusion needed to
 * preserve the legacy queue's append ordering while leaving schema ownership with Flyway.
 */
@Component
public final class PostgresJobSubmissionCoordinator implements JobSubmissionCoordinator {

    /** Parameterized acquisition of one transaction-scoped, two-part PostgreSQL advisory lock. */
    static final String ACQUIRE_LOCK_SQL = "SELECT pg_advisory_xact_lock(?, ?)";

    /** ASCII {@code CARD}, the module namespace of the advisory lock. */
    static final int LOCK_NAMESPACE = 0x43415244;

    /** ASCII {@code JOBS}, the legacy queue resource guarded by the lock. */
    static final int LOCK_RESOURCE = 0x4A4F4253;

    /** Executes the parameterized advisory-lock statement on the transaction-bound connection. */
    private final JdbcTemplate jdbcTemplate;

    /** Opens the short, independent transaction whose lifetime is the lock lifetime. */
    private final TransactionTemplate transactionTemplate;

    /**
     * @param jdbcTemplate       access to the transaction-bound PostgreSQL connection
     * @param transactionManager transaction manager shared by every replica
     */
    public PostgresJobSubmissionCoordinator(final JdbcTemplate jdbcTemplate,
            final PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public JobSubmissionService.SubmissionResult serialize(
            final Supplier<JobSubmissionService.SubmissionResult> submission) {
        Objects.requireNonNull(submission, "submission must not be null");
        try {
            final JobSubmissionService.SubmissionResult result =
                    this.transactionTemplate.execute(status -> {
                        acquireAdvisoryLock();
                        return submission.get();
                    });
            return Objects.requireNonNull(result,
                    "the serialized job-submission work must return an outcome");
        } catch (final DataAccessException | TransactionException failure) {
            throw new CoordinationFailure(
                    "the deployment-wide job-submission guard could not serialize the card stream",
                    failure);
        }
    }

    /**
     * Acquires the fixed transaction-scoped lock on the connection bound to the current transaction.
     */
    private void acquireAdvisoryLock() {
        this.jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(ACQUIRE_LOCK_SQL)) {
                statement.setInt(1, LOCK_NAMESPACE);
                statement.setInt(2, LOCK_RESOURCE);
                statement.execute();
            }
            return null;
        });
    }
}
