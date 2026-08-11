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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The whole deployment's view of the sign-on attempt state, held in the shared PostgreSQL database.
 *
 * <h2>What this store is for</h2>
 *
 * <p>An allowance is only an allowance if it is counted once. Held in a process's memory it is counted once
 * <em>per process</em>: two replicas behind one address give a caller twice the attempts before a refusal,
 * ten give ten times, and every restart - a deployment, a crash, an autoscaler reclaiming an instance -
 * returns every allowance to full without anyone authenticating. This store removes both multipliers by
 * putting the state where every instance already looks and where it survives all of them.
 *
 * <p>It is the required store for production; see {@code config/SignOnThrottleConfig} for the selection and
 * {@code config/ProductionConfigurationValidator} for the refusal to start without it. Recorded in
 * {@code docs/decision-log.md} entry DL-343.
 *
 * <h2>How one subject's transition is made atomic across replicas</h2>
 *
 * <p>A failure is a read, a decision and a write, and the decision is only correct if nothing else writes
 * the same subject in between. Two replicas cannot see each other's locks in memory, so the exclusion is
 * taken in the database: a transaction-scoped advisory lock over a fixed two-part key, acquired inside a
 * {@link TransactionDefinition#PROPAGATION_REQUIRES_NEW new transaction}, exactly as the card-stream
 * coordinator does at {@link PostgresJobSubmissionCoordinator}. PostgreSQL releases such a lock on commit
 * and on rollback alike, so a crash mid-transition releases it with no second statement and no cleanup job.
 *
 * <p>The lock is <strong>ledger-wide rather than per subject</strong>, and that is a deliberate choice with
 * a cost. Per-subject locking would let unrelated subjects transition concurrently, but the ceiling on
 * tracked subjects is a property of the whole table rather than of one row: enforcing it under per-subject
 * locks means counting rows while other transactions insert them, which is the same read-then-insert race
 * that made the in-memory store need a separate reservation counter (DL-308). A single lock makes the
 * ceiling an exact bound by construction. The work it serializes is three short statements against a table
 * bounded at the configured ceiling, and it is taken only on a sign-on <em>failure</em> - never on an
 * admitted attempt, and never on the refusal probe that precedes a credential read - so the serialized path
 * is the one whose throughput a deployment has no interest in protecting.
 *
 * <p>Because the transition holds that lock, this store never answers
 * {@link FailureOutcome#DECLINED_BY_RACE}: there is no window for the race that outcome describes. The
 * outcome remains part of the contract because the in-memory store, which trades exact serialization for
 * per-key concurrency, can still produce it.
 *
 * <h2>Waiting is bounded, and a fault does not drive the throttle</h2>
 *
 * <p>Acquisition waits, because a concurrent failure means "take your turn" rather than "miscount". The wait
 * is bounded with {@link PreparedStatement#setQueryTimeout(int)} rather than with a written {@code SET}
 * statement, so no value is ever concatenated into SQL text and the unsafe-code audit's raw-SQL count stays
 * at zero by construction.
 *
 * <p>An expired wait, a lost connection or any other database fault is reported as
 * {@link FailureOutcome#STORE_UNAVAILABLE} and <strong>does not refuse the attempt</strong>. That enum
 * constant carries the full reasoning; in short, the only store that can be unreachable is this one, it
 * lives in the same database as the credential store, and while it is unreachable no credential can be
 * verified either - so counting nothing withholds no protection that was available, whereas refusing would
 * turn one database fault into a sign-on outage lasting a whole refusal period beyond it.
 *
 * <h2>Instants are stored to microsecond precision</h2>
 *
 * <p>{@code timestamp with time zone} resolves to microseconds and {@link Instant} to nanoseconds, so an
 * instant written and read back is not the instant that went in unless it is truncated first. Every instant
 * this store reasons with is therefore truncated to microseconds on the way in, which makes a round trip
 * exact and makes the same comparison give the same answer in SQL and in Java.
 *
 * <p>Safe for concurrent use: it holds no mutable state of its own, and every state change is applied inside
 * the serialized transaction described above.
 *
 * <p>Provenance: legacy estate checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Nothing here has a legacy
 * antecedent: the estate's sign-on transaction compared a credential and counted nothing.
 *
 * @since 1.0.0
 */
public final class PostgresSignOnAttemptLedger implements SignOnAttemptLedger {

    /** Parameterized acquisition of the transaction-scoped, two-part ledger-wide advisory lock. */
    static final String ACQUIRE_LOCK_SQL = "SELECT pg_advisory_xact_lock(?, ?)";

    /** ASCII {@code CARD}, the module namespace shared with the other advisory locks in this module. */
    static final int LOCK_NAMESPACE = 0x43415244;

    /** ASCII {@code SGON}, the sign-on throttle ledger as the guarded resource. */
    static final int LOCK_RESOURCE = 0x53474F4E;

    /** Reads one subject's accumulated state. */
    static final String SELECT_ENTRY_SQL = "SELECT failures, window_started_at, refused_until"
            + " FROM sign_on_attempt WHERE subject = ?";

    /** Answers the refusal probe without reading any other column. */
    static final String SELECT_REFUSING_SQL = "SELECT count(*) FROM sign_on_attempt"
            + " WHERE subject = ? AND refused_until IS NOT NULL AND refused_until > ?";

    /** Counts how many subjects the ledger holds, which is what the ceiling bounds. */
    static final String COUNT_SUBJECTS_SQL = "SELECT count(*) FROM sign_on_attempt";

    /** Counts how many of two named subjects are already tracked. */
    static final String COUNT_NAMED_SUBJECTS_SQL = "SELECT count(*) FROM sign_on_attempt"
            + " WHERE subject IN (?, ?)";

    /** Writes one subject's state, whether or not it had any. */
    static final String UPSERT_ENTRY_SQL = "INSERT INTO sign_on_attempt"
            + " (subject, failures, window_started_at, refused_until) VALUES (?, ?, ?, ?)"
            + " ON CONFLICT (subject) DO UPDATE SET failures = EXCLUDED.failures,"
            + " window_started_at = EXCLUDED.window_started_at,"
            + " refused_until = EXCLUDED.refused_until";

    /** Removes one subject's state. */
    static final String DELETE_ENTRY_SQL = "DELETE FROM sign_on_attempt WHERE subject = ?";

    /**
     * Removes every entry that is neither refusing nor inside its window.
     *
     * <p>The two predicates are {@link Entry#isSweepable(Instant, Policy)} expressed in SQL: a refusal that
     * is absent or lapsed, and a window whose start is at least one window-length in the past. A refusing
     * entry can never match, which is the property that stops a caller clearing the record of its own abuse
     * by generating subjects until the ceiling evicts it.
     */
    static final String SWEEP_EXPIRED_SQL = "DELETE FROM sign_on_attempt"
            + " WHERE (refused_until IS NULL OR refused_until <= ?) AND window_started_at <= ?";

    /**
     * Longest this store waits for the ledger-wide guard, in seconds.
     *
     * <p>Measured against the work the guard covers rather than chosen as a round number. A holder runs at
     * most a read, a bounded sweep, a count and one upsert against a table capped at the configured
     * tracked-subject ceiling, all on an indexed key - single-digit milliseconds on a healthy database, so a
     * failure arriving while another is being recorded normally waits for no measurable time at all. Five
     * seconds accommodates a database under real load and a queue of concurrent failures many times deeper
     * than the ceiling makes possible, without accommodating a database that has stopped answering.
     *
     * <p>It is deliberately shorter than the fifteen seconds the card-stream guard allows, because the two
     * guards protect work of very different lengths: that one covers up to seventeen network sends to an
     * external queue, this one covers four local statements. It is a ceiling on waiting rather than a
     * target, and reaching it always means something is wrong - at which point the attempt is reported as
     * uncounted rather than refused.
     */
    static final int ACQUISITION_TIMEOUT_SECONDS = 5;

    /** Reports store faults. Never names a subject: a subject is an operator identifier or an address. */
    private static final Logger LOGGER = LoggerFactory.getLogger(PostgresSignOnAttemptLedger.class);

    /** Executes the parameterized statements on the transaction-bound connection. */
    private final JdbcTemplate jdbcTemplate;

    /** Opens the short, independent transaction whose lifetime is the guard's lifetime. */
    private final TransactionTemplate transactionTemplate;

    /**
     * @param jdbcTemplate       access to the shared PostgreSQL database
     * @param transactionManager transaction manager shared by every replica
     * @throws NullPointerException if either argument is {@code null}
     */
    public PostgresSignOnAttemptLedger(final JdbcTemplate jdbcTemplate,
            final PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public boolean isRefusing(final String subject, final Instant now) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(now, "now must not be null");
        // No guard is taken here. The probe reads one indexed row and writes nothing, it runs on every
        // attempt including every admitted one, and a stale answer costs at most one attempt either way -
        // whereas serializing it would put the whole deployment's sign-on traffic through one lock.
        try {
            final Long refusing = this.jdbcTemplate.queryForObject(SELECT_REFUSING_SQL, Long.class,
                    subject, atUtc(now));
            return refusing != null && refusing > 0L;
        } catch (final DataAccessException failure) {
            reportUnavailable("the refusal probe", failure);
            // Fail-open, for the reason FailureOutcome.STORE_UNAVAILABLE records: this database is the
            // credential store too, so nothing can be admitted while it is unreachable either.
            return false;
        }
    }

    @Override
    public FailureOutcome recordFailure(final String subject, final Instant now, final Policy policy) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        final Instant at = truncated(now);
        try {
            final FailureOutcome outcome = this.transactionTemplate.execute(status -> {
                acquireLedgerGuard();
                return transitionUnderGuard(subject, at, policy);
            });
            return outcome == null ? FailureOutcome.STORE_UNAVAILABLE : outcome;
        } catch (final DataAccessException | TransactionException failure) {
            reportUnavailable("a failure transition", failure);
            return FailureOutcome.STORE_UNAVAILABLE;
        }
    }

    @Override
    public void release(final String subject) {
        Objects.requireNonNull(subject, "subject must not be null");
        // One statement, so no guard is needed: the delete is atomic on its own and it is idempotent, which
        // is what makes two replicas releasing the same identity harmless.
        try {
            this.jdbcTemplate.update(DELETE_ENTRY_SQL, subject);
        } catch (final DataAccessException failure) {
            // Not fatal to the sign-on that has already succeeded. The consequence of a failed release is
            // that an admitted identity keeps failures it should have shed, which expire on their own when
            // the window lapses.
            reportUnavailable("an identity release", failure);
        }
    }

    @Override
    public int trackedSubjectCount() {
        try {
            final Long tracked = this.jdbcTemplate.queryForObject(COUNT_SUBJECTS_SQL, Long.class);
            return tracked == null ? 0 : Math.toIntExact(tracked);
        } catch (final DataAccessException failure) {
            reportUnavailable("the tracked-subject count", failure);
            return 0;
        }
    }

    @Override
    public boolean canBeginTracking(final String identitySubject, final String sourceSubject,
            final Instant now, final Policy policy) {
        Objects.requireNonNull(identitySubject, "identitySubject must not be null");
        Objects.requireNonNull(sourceSubject, "sourceSubject must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        final Instant at = truncated(now);
        try {
            final Boolean admissible = this.transactionTemplate.execute(status -> {
                // Guarded, and for the same reason the transition is: the answer is about how full the
                // whole table is, and it is acted on by admitting an attempt that may then insert into it.
                acquireLedgerGuard();
                return Boolean.valueOf(hasRoomUnderGuard(identitySubject, sourceSubject, at, policy));
            });
            return admissible == null || admissible.booleanValue();
        } catch (final DataAccessException | TransactionException failure) {
            reportUnavailable("the tracking-admission probe", failure);
            // Fail-open for the same reason as the refusal probe, and with the same consequence: nothing
            // can be admitted while this database is unreachable, so nothing is being let through.
            return true;
        }
    }

    @Override
    public boolean isDeploymentWide() {
        return true;
    }

    /**
     * Reads, decides and writes one subject's state while the ledger-wide guard is held.
     *
     * @param  subject the prefixed subject
     * @param  at      the truncated instant of the failure
     * @param  policy  the thresholds to apply
     * @return what the transition did
     */
    private FailureOutcome transitionUnderGuard(final String subject, final Instant at,
            final Policy policy) {
        final Entry current = readEntry(subject);
        if (current == null && !hasRoomUnderGuard(subject, subject, at, policy)) {
            return FailureOutcome.DECLINED_AT_CEILING;
        }
        final Transition transition = SignOnAttemptLedger.nextAfterFailure(current, at, policy);
        writeEntry(subject, transition.entry());
        return transition.outcome();
    }

    /**
     * Decides whether a new subject may be inserted, sweeping spent entries first if it must.
     *
     * <p>Called with the same subject twice by {@link #transitionUnderGuard} - there the question is only
     * about one subject - and with two distinct subjects by {@link #canBeginTracking}. Either way an
     * already-tracked subject needs no room, because recording against it inserts nothing.
     *
     * @param  identitySubject one subject that would not need a new slot
     * @param  sourceSubject   the other subject that would not need a new slot
     * @param  at              the truncated instant to judge expiry against
     * @param  policy          the thresholds whose ceiling is the bound
     * @return {@code true} when a subject is already tracked or the ledger has room for a new one
     */
    private boolean hasRoomUnderGuard(final String identitySubject, final String sourceSubject,
            final Instant at, final Policy policy) {
        final Long tracked = this.jdbcTemplate.queryForObject(COUNT_NAMED_SUBJECTS_SQL, Long.class,
                identitySubject, sourceSubject);
        if (tracked != null && tracked > 0L) {
            return true;
        }
        if (subjectCountUnderGuard() < policy.trackedSubjects()) {
            return true;
        }
        // Only now, and only because the guard is held, is it safe to remove other subjects' entries.
        this.jdbcTemplate.update(SWEEP_EXPIRED_SQL, atUtc(at), atUtc(at.minus(policy.failureWindow())));
        return subjectCountUnderGuard() < policy.trackedSubjects();
    }

    /**
     * @return how many subjects the ledger holds, read inside the guarded transaction
     */
    private long subjectCountUnderGuard() {
        final Long tracked = this.jdbcTemplate.queryForObject(COUNT_SUBJECTS_SQL, Long.class);
        return tracked == null ? 0L : tracked.longValue();
    }

    /**
     * Reads one subject's state.
     *
     * @param  subject the prefixed subject
     * @return the state, or {@code null} when the subject has none
     */
    private Entry readEntry(final String subject) {
        final List<Entry> found =
                this.jdbcTemplate.query(SELECT_ENTRY_SQL, PostgresSignOnAttemptLedger::mapEntry, subject);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Writes one subject's state, inserting it or replacing it.
     *
     * <p>The refusal deadline is bound as a typed parameter rather than as a bare object, because it is the
     * one column that is legitimately absent - a subject that has failed but is not refused has no deadline
     * - and an untyped {@code null} leaves the driver to infer a type it cannot see. Declaring the type
     * makes the absent case as explicit as the present one.
     *
     * @param subject the prefixed subject
     * @param entry   the state to store
     */
    private void writeEntry(final String subject, final Entry entry) {
        final SqlParameterValue refusedUntil = new SqlParameterValue(Types.TIMESTAMP_WITH_TIMEZONE,
                entry.refusedUntil() == null ? null : atUtc(entry.refusedUntil()));
        this.jdbcTemplate.update(UPSERT_ENTRY_SQL, subject, Integer.valueOf(entry.failures()),
                atUtc(entry.windowStartedAt()), refusedUntil);
    }

    /**
     * Acquires the fixed transaction-scoped guard on the connection bound to the current transaction.
     *
     * <p>The wait is bounded by {@link PreparedStatement#setQueryTimeout(int)} rather than by a written
     * {@code SET} statement, so not even this class's own constants are concatenated into SQL text. An
     * expired wait cancels this statement, which raises, which the caller turns into
     * {@link FailureOutcome#STORE_UNAVAILABLE}.
     */
    private void acquireLedgerGuard() {
        this.jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(ACQUIRE_LOCK_SQL)) {
                statement.setQueryTimeout(ACQUISITION_TIMEOUT_SECONDS);
                statement.setInt(1, LOCK_NAMESPACE);
                statement.setInt(2, LOCK_RESOURCE);
                statement.execute();
            }
            return null;
        });
    }

    /**
     * Maps one ledger row.
     *
     * @param  row       the result set positioned on the row
     * @param  rowNumber the row's ordinal, unused because the key is unique
     * @return the state the row holds
     * @throws SQLException if a column cannot be read
     */
    private static Entry mapEntry(final ResultSet row, final int rowNumber) throws SQLException {
        final OffsetDateTime refusedUntil = row.getObject("refused_until", OffsetDateTime.class);
        return new Entry(row.getInt("failures"),
                row.getObject("window_started_at", OffsetDateTime.class).toInstant(),
                refusedUntil == null ? null : refusedUntil.toInstant());
    }

    /**
     * Renders an instant for a {@code timestamp with time zone} column.
     *
     * @param  instant the instant to store
     * @return the same instant at UTC, truncated to the column's microsecond resolution
     */
    private static OffsetDateTime atUtc(final Instant instant) {
        return OffsetDateTime.ofInstant(truncated(instant), ZoneOffset.UTC);
    }

    /**
     * Cuts an instant to the resolution the column can hold.
     *
     * @param  instant the instant to cut
     * @return the instant truncated to microseconds, so a round trip returns it unchanged
     */
    private static Instant truncated(final Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Records a store fault without naming the subject it concerned.
     *
     * <p>Error level, because it means the deployment-wide sign-on protection is not currently working -
     * which needs attention even though no individual attempt is harmed by it. The record carries the
     * failure's type and nothing else: a subject is an operator identifier or a caller address, and the
     * governor's own diagnostics deliberately name neither.
     *
     * @param operation which ledger operation could not be completed
     * @param failure   the fault, named by type only
     */
    private static void reportUnavailable(final String operation, final RuntimeException failure) {
        LOGGER.error("OPERATIONAL ALERT: the deployment-wide sign-on attempt ledger could not complete"
                        + " {}, so this attempt was neither counted nor refused. failureType={}",
                operation, failure.getClass().getSimpleName());
    }
}
