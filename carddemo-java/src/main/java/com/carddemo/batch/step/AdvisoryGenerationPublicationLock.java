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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

/**
 * Serializes generation publication across every application replica with PostgreSQL transaction-scoped
 * advisory locks.
 *
 * <h2>Why the database and not the JVM</h2>
 *
 * <p>An in-process lock would serialize two publications inside one JVM and nothing else, and this module
 * is a Spring Boot service that is expected to run with more than one replica. It would also fail to
 * serialize two publications inside a <em>single</em> JVM in one real case: {@code StagedGenerationStore}
 * is a component, but {@code BackupTransactionJobConfig} constructs its own instance rather than injecting
 * the shared bean, so a lock held in an instance field would not be the same lock. A database lock is
 * indifferent to how many stores, JVMs or replicas exist, because the thing it names is the base.
 *
 * <p>The pattern is the one {@code BatchLaunchCoordinator} already establishes for launch serialization -
 * a short transaction, an advisory lock keyed by a namespaced string, and release by transaction end -
 * with two deliberate differences. The lock is acquired in its blocking form rather than its
 * {@code try} form, because a busy base means "wait your turn", not "skip retention and leave the base
 * over-depth". And the acquisition is bounded through the JDBC statement timeout, so a stuck holder
 * cannot hold a batch job open indefinitely.
 *
 * <h2>Deadlock is prevented by ordering, not by detection</h2>
 *
 * <p>A publication may name several bases: the transaction-report job publishes a backup generation, a
 * filtered generation and a report generation in one pass. Two publications naming an overlapping pair
 * would deadlock if each acquired in its caller's order, so the bases are sorted before acquisition. Every
 * caller therefore acquires the same pair in the same order, and the cycle cannot form. Duplicate names
 * are collapsed first; PostgreSQL advisory locks are re-entrant within a session, so a duplicate would be
 * harmless, but acquiring a lock twice for one publication would misreport what is held.
 *
 * <h2>Failure to acquire fails the publication; failure to release does not</h2>
 *
 * <p>A timed-out or refused acquisition raises. Proceeding unserialized is precisely the defect this class
 * exists to close, so it is never the fallback. The boundary listener turns the raised failure into the
 * job's terminal verdict, and the store's compensation guarantees nothing was left externally visible.
 *
 * <p>The commit that <em>releases</em> the bases is a different matter, because it runs after the
 * publication has passed its own commit point. By then the objects are durable, the aliases name them,
 * and retention has irreversibly removed what rolled off, so a release failure is evidence about the
 * database and none at all about the publication. It is reported as an operational alert and the job's
 * verdict is left alone - the same treatment retention already receives, and for the same reason.
 * Developed on {@link #releaseHeldBases(Connection, List)} and recorded as {@code docs/decision-log.md}
 * entry DL-304.
 *
 * <h2>No SQL is assembled</h2>
 *
 * <p>The statement is a compile-time constant and its only variable is bound as a parameter. The
 * acquisition bound is applied with {@link PreparedStatement#setQueryTimeout(int)} rather than by writing
 * a {@code SET} statement, so no value - not even one of this class's own constants - is ever concatenated
 * into SQL text. That keeps the unsafe-code audit's raw-SQL count at zero by construction rather than by
 * inspection.
 *
 * <p>See {@code docs/decision-log.md} entry DL-181.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@Component
public final class AdvisoryGenerationPublicationLock implements GenerationPublicationLock {

    /** Namespace separating these locks from launch locks and from every unrelated advisory-lock user. */
    static final String LOCK_NAMESPACE = "carddemo.batch.generation:";

    /**
     * Blocking, transaction-scoped acquisition. The key is hashed by the server so a base of any length
     * maps onto the {@code bigint} advisory-lock space, and the base itself is bound as a parameter.
     */
    static final String ACQUIRE_LOCK_SQL =
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))";

    /**
     * Longest a publication will wait for a base, in seconds. Long enough that an ordinary concurrent
     * publication - an upload and a prune - completes and releases well inside it, short enough that a
     * stuck holder surfaces as a failed job rather than as a batch tier that never finishes.
     */
    static final int ACQUISITION_TIMEOUT_SECONDS = 30;

    /** Logger for acquisition outcomes; publication itself logs through the store. */
    private static final Logger LOGGER =
            LoggerFactory.getLogger(AdvisoryGenerationPublicationLock.class);

    /** Configured PostgreSQL access used for the coordinating transaction. */
    private final JdbcOperations jdbcOperations;

    /**
     * Creates the lock over the application's configured database access.
     *
     * @param jdbcOperations configured PostgreSQL access; must not be {@code null}
     */
    public AdvisoryGenerationPublicationLock(final JdbcOperations jdbcOperations) {
        this.jdbcOperations = Objects.requireNonNull(jdbcOperations, "jdbcOperations must not be null");
    }

    @Override
    public void whileHolding(final List<String> logicalBases, final Runnable publication) {
        final List<String> bases = orderedDistinctBases(logicalBases);
        final Runnable body = Objects.requireNonNull(publication, "publication must not be null");
        if (bases.isEmpty()) {
            body.run();
            return;
        }
        try {
            this.jdbcOperations.execute((ConnectionCallback<Void>) connection ->
                    holdAndRun(connection, bases, body));
        } catch (final DataAccessException failure) {
            throw new IllegalStateException("generation bases " + bases
                    + " could not be serialized for publication", failure);
        }
    }

    /**
     * Acquires every base on one connection, runs the publication, and commits to release.
     *
     * <p>The publication runs inside the coordinating transaction because the lock is
     * transaction-scoped, which is the form that cannot leak a held lock back into the connection pool.
     * The cost is that one connection is pinned for the duration of the publication; the alternative -
     * a session-scoped lock released by hand - leaks the lock onto a pooled connection whenever the
     * release does not run, and a leaked lock stops every later publication of that base rather than
     * one.
     *
     * @param connection the coordinating connection
     * @param bases sorted, distinct bases to hold
     * @param publication the publication to run while they are held
     * @return {@code null}; the callback's value is unused
     * @throws SQLException if acquisition, commit or rollback fails
     */
    private Void holdAndRun(final Connection connection, final List<String> bases,
            final Runnable publication) throws SQLException {
        final boolean restoreAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try {
                for (final String base : bases) {
                    acquire(connection, base);
                }
                publication.run();
            } catch (final SQLException | RuntimeException failure) {
                // Everything up to here is genuinely reversible from the job's point of view: either
                // nothing durable was published, or the store's own compensation has already emptied
                // every key the failed pass attempted. The failure is therefore the verdict.
                rollbackQuietly(connection, failure);
                throw failure;
            }
            // ---- The publication is complete and irreversible from this line. ----
            releaseHeldBases(connection, bases);
            return null;
        } finally {
            restoreAutoCommit(connection, restoreAutoCommit);
        }
    }

    /**
     * Ends the coordinating transaction to release every held base, and never fails the publication.
     *
     * <h2>Why a failure here is an alert rather than a verdict</h2>
     *
     * <p>This commit runs only after {@code publication.run()} has returned, and by then the publication
     * has passed its own commit point: every object is durable in the bucket, every fixed-name alias
     * names the generation it published, the registry has been cleared so a repeated callback cannot
     * publish twice, and retention has removed the generations that rolled off - a deletion that cannot
     * be undone. This transaction holds no business data of its own; its entire content is the advisory
     * locks. So a failure of this commit says nothing whatsoever about the publication, and raising it
     * would mark FAILED a job whose artifacts are durable, visible and correct, while the boundary
     * listener discarded the local generations that name them.
     *
     * <p>That is strictly the worse of the two available answers. A job reported FAILED is re-run, and the
     * re-run publishes a second generation of every base - so treating a release fault as a publication
     * fault does not protect anything; it duplicates output. The symmetry with retention is exact and
     * deliberate: retention also runs after the commit point, is also irreversible, and is also reported
     * rather than raised, for the same reason.
     *
     * <h2>Why declining to fail leaks nothing</h2>
     *
     * <p>The advisory locks are transaction-scoped, so they are released by the transaction ending in any
     * way at all. A commit that fails leaves the transaction aborted by the server; restoring auto-commit
     * on the way out ends it; and a connection returned to the pool in a broken state is reset or
     * discarded. There is no path on which the locks survive this method, which is exactly why the
     * blocking-and-transaction-scoped form was chosen over a session-scoped lock released by hand.
     *
     * @param connection the coordinating connection
     * @param bases      the bases held, named in the alert so an operator knows what was published
     */
    private static void releaseHeldBases(final Connection connection, final List<String> bases) {
        try {
            connection.commit();
        } catch (final SQLException releaseFailure) {
            LOGGER.error("OPERATIONAL ALERT: the generation-publication guard could not be released"
                            + " cleanly, but the publication had already completed and its generations"
                            + " are durable, so the job's verdict is unchanged. The coordinating"
                            + " transaction is abandoned, which is what releases the bases it held."
                            + " bases={} releaseFailureType={}", bases,
                    releaseFailure.getClass().getSimpleName());
        }
    }

    /** Acquires one base, bounded by the statement timeout rather than by a written SET statement. */
    private static void acquire(final Connection connection, final String base) throws SQLException {
        try (PreparedStatement acquisition = connection.prepareStatement(ACQUIRE_LOCK_SQL)) {
            acquisition.setQueryTimeout(ACQUISITION_TIMEOUT_SECONDS);
            acquisition.setString(1, LOCK_NAMESPACE + base);
            acquisition.execute();
        }
    }

    /**
     * Sorts and de-duplicates the requested bases so acquisition order never depends on the caller.
     *
     * @param logicalBases requested bases
     * @return the same bases, distinct and in ascending order
     */
    private static List<String> orderedDistinctBases(final List<String> logicalBases) {
        Objects.requireNonNull(logicalBases, "logicalBases must not be null");
        final List<String> ordered = new ArrayList<>(logicalBases.size());
        for (final String base : logicalBases) {
            final String required = Objects.requireNonNull(base, "logicalBases must not contain null");
            if (!ordered.contains(required)) {
                ordered.add(required);
            }
        }
        ordered.sort(String::compareTo);
        return List.copyOf(ordered);
    }

    /**
     * Rolls the coordinating transaction back, releasing every base, without masking the real failure.
     *
     * @param connection the coordinating connection
     * @param cause the failure being propagated
     */
    private static void rollbackQuietly(final Connection connection, final Exception cause) {
        try {
            connection.rollback();
        } catch (final SQLException rollbackFailure) {
            // Reported and dropped. Replacing the propagating failure with this one would hide why the
            // publication failed; the transaction is abandoned either way and the pool discards the
            // connection, which releases the advisory locks the transaction held.
            LOGGER.warn("Could not roll back the generation-lock transaction after {};"
                            + " rollbackFailure={}", cause.getClass().getSimpleName(),
                    rollbackFailure.getClass().getSimpleName());
        }
    }

    /**
     * Restores the connection's original auto-commit mode before it returns to the pool.
     *
     * @param connection the coordinating connection
     * @param original the mode observed on entry
     */
    private static void restoreAutoCommit(final Connection connection, final boolean original) {
        try {
            if (connection.getAutoCommit() != original) {
                connection.setAutoCommit(original);
            }
        } catch (final SQLException restoreFailure) {
            LOGGER.warn("Could not restore auto-commit on the generation-lock connection;"
                    + " restoreFailure={}", restoreFailure.getClass().getSimpleName());
        }
    }
}
