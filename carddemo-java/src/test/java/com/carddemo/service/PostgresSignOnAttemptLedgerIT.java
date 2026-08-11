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

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.service.SignOnAttemptLedger.FailureOutcome;
import com.carddemo.service.SignOnAttemptLedger.Policy;
import com.carddemo.support.AbstractPostgresIT;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Proves against a real PostgreSQL that the sign-on attempt allowance is counted once per deployment.
 *
 * <h2>Why this cannot be a unit test</h2>
 *
 * <p>The property under proof is that state is shared between processes and outlives all of them. Nothing
 * short of a real database can demonstrate either: a doubled store shares whatever the double is told to
 * share, and an in-process store cannot outlive the process by construction. So every case below builds
 * <em>two independently constructed stores over two independent data sources</em> - the shape two
 * application replicas actually have, sharing nothing but the database - and asserts across them.
 *
 * <h2>What the two headline cases correspond to</h2>
 *
 * <p>The finding this closes was that the throttle counted per process. Two consequences followed, and each
 * has a case here: a caller spread across N replicas got N times the allowance
 * ({@link #sharesOneAllowanceAcrossTwoInstances()}), and every restart returned every allowance to full
 * ({@link #keepsTheRefusalWhenEveryProcessIsReplaced()}). Both are asserted through
 * {@link SignOnAttemptGovernor}, not through the store alone, because the governor is what the sign-on path
 * actually calls and its subject derivation is part of what has to work.
 *
 * <p>Recorded in {@code docs/decision-log.md} entry DL-343. The per-process store's opposite behaviour is
 * asserted in {@code InMemorySignOnAttemptLedgerTest}, and the shared state machine both stores apply is
 * specified in {@code SignOnAttemptLedgerTest}.
 *
 * @since 1.0.0
 */
@DisplayName("the deployment-wide sign-on attempt store")
class PostgresSignOnAttemptLedgerIT extends AbstractPostgresIT {

    /** The window failures accumulate over. */
    private static final Duration WINDOW = Duration.ofMinutes(5);

    /** How long an exhausted subject is refused. */
    private static final Duration REFUSAL = Duration.ofMinutes(2);

    /** Failures that exhaust an allowance; small so a case reaches the boundary in four calls. */
    private static final int ALLOWANCE = 4;

    /** Room enough that the ceiling only enters the cases that are about the ceiling. */
    private static final int ROOMY_TRACKED_SUBJECTS = 64;

    /** A fixed instant, so every window and deadline is arithmetic rather than timing. */
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    /** An operator identifier under specification. Not a credential of anything. */
    private static final String OPERATOR = "USER0001";

    /** A caller address under specification. */
    private static final String SOURCE = "203.0.113.10";

    /**
     * The identity subject the governor derives from {@link #OPERATOR}.
     *
     * <p>Spelled out rather than obtained from the governor, so that a change to the derivation has to be
     * acknowledged here instead of silently making these assertions vacuous.
     */
    private static final String IDENTITY_SUBJECT = "i:" + OPERATOR;

    /** The source subject the governor derives from {@link #SOURCE}. */
    private static final String SOURCE_SUBJECT = "s:" + SOURCE;

    /** Longest a concurrency case waits before it is treated as hung. */
    private static final long WAIT_SECONDS = 30L;

    /** Creates the specification. */
    PostgresSignOnAttemptLedgerIT() {
        // Intentionally empty: each case builds its own replicas.
    }

    /**
     * Empties the ledger so a case's counts and ceiling are exact.
     *
     * @throws SQLException if the table cannot be emptied
     */
    @BeforeEach
    void clearTheLedger() throws SQLException {
        truncateOperationalTables();
    }

    /**
     * Builds one replica's store: its own data source, its own template, its own transaction manager.
     *
     * <p>A separate {@link DataSource} per replica rather than a shared one, because a shared object would
     * be a shared connection pool and the point of these cases is that the replicas share only the
     * database.
     *
     * @return a store equivalent to the one a separate application instance would hold
     */
    private static PostgresSignOnAttemptLedger replica() {
        final DataSource dataSource =
                new DriverManagerDataSource(jdbcUrl(), databaseUser(), databasePassword());
        return new PostgresSignOnAttemptLedger(new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource));
    }

    /**
     * Wraps a store in a governor reading the same thresholds every case uses.
     *
     * @param  ledger the store this governor counts into
     * @param  at     the instant this governor's clock reports
     * @return a governor configured with the case thresholds
     */
    private static SignOnAttemptGovernor governorOver(final SignOnAttemptLedger ledger,
            final Instant at) {
        return new SignOnAttemptGovernor(true, ALLOWANCE, WINDOW, REFUSAL, ROOMY_TRACKED_SUBJECTS,
                Clock.fixed(at, ZoneOffset.UTC), new SimpleMeterRegistry(), ledger);
    }

    /**
     * The thresholds a case applies when it drives a store directly rather than through a governor.
     *
     * @param  trackedSubjects the ceiling to apply
     * @return a policy with the case window and refusal period
     */
    private static Policy policyWithCeiling(final int trackedSubjects) {
        return new Policy(ALLOWANCE, WINDOW, REFUSAL, trackedSubjects);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("counts one allowance across two instances, so spreading attempts over replicas no "
            + "longer multiplies the attempts a caller gets")
    void sharesOneAllowanceAcrossTwoInstances() {
        final SignOnAttemptGovernor replicaA = governorOver(replica(), NOW);
        final SignOnAttemptGovernor replicaB = governorOver(replica(), NOW);

        // Half the allowance against each replica. Under a per-process store this is two failures each,
        // which refuses nothing: it took the whole allowance per instance, so two instances meant twice
        // the attempts and ten meant ten times.
        for (int attempt = 0; attempt < ALLOWANCE / 2; attempt++) {
            replicaA.recordFailure(OPERATOR, SOURCE);
            replicaB.recordFailure(OPERATOR, SOURCE);
        }

        assertThat(replicaA.isRefusing(OPERATOR, SOURCE))
                .as("the %d failures were spread evenly over two instances and still exhausted one "
                        + "allowance, which is the whole point of the shared store", ALLOWANCE)
                .isTrue();
        assertThat(replicaB.isRefusing(OPERATOR, SOURCE))
                .as("and the refusal is seen by the instance that recorded only half of them, so a "
                        + "caller cannot escape it by being load-balanced elsewhere")
                .isTrue();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("keeps a refusal when every process holding it is replaced, so a restart is no longer a "
            + "way to clear the allowance")
    void keepsTheRefusalWhenEveryProcessIsReplaced() {
        final SignOnAttemptGovernor before = governorOver(replica(), NOW);
        for (int attempt = 0; attempt < ALLOWANCE; attempt++) {
            before.recordFailure(OPERATOR, SOURCE);
        }
        assertThat(before.isRefusing(OPERATOR, SOURCE)).isTrue();

        // Nothing of the process survives: a new store, a new template, a new transaction manager, a new
        // governor. Only the table is the same, which is exactly what a release or a crash leaves behind.
        final SignOnAttemptGovernor after = governorOver(replica(), NOW.plus(Duration.ofSeconds(30)));

        assertThat(after.isRefusing(OPERATOR, SOURCE))
                .as("a per-process store returned every allowance to full on every restart, so a caller "
                        + "patient enough to wait for a deployment - or able to provoke one - reset the "
                        + "bound at will. The refusal now outlives the process that engaged it")
                .isTrue();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("lifts a refusal for every instance once its period has elapsed, so the release is by "
            + "time and needs no coordination")
    void liftsTheRefusalEverywhereOnceThePeriodHasElapsed() {
        final SignOnAttemptGovernor engaging = governorOver(replica(), NOW);
        for (int attempt = 0; attempt < ALLOWANCE; attempt++) {
            engaging.recordFailure(OPERATOR, SOURCE);
        }
        assertThat(engaging.isRefusing(OPERATOR, SOURCE)).isTrue();

        final SignOnAttemptGovernor later =
                governorOver(replica(), NOW.plus(REFUSAL).plus(Duration.ofSeconds(1)));

        assertThat(later.isRefusing(OPERATOR, SOURCE))
                .as("nothing had to run for the refusal to lapse - no clean-up job, no scheduled sweep. "
                        + "The deadline is a column and every instance reads it against its own clock")
                .isFalse();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("releases an admitted identity for every instance, and leaves the source history alone")
    void releasesAnAdmittedIdentityForEveryInstance() throws SQLException {
        final SignOnAttemptGovernor replicaA = governorOver(replica(), NOW);
        final SignOnAttemptGovernor replicaB = governorOver(replica(), NOW);
        for (int attempt = 0; attempt < ALLOWANCE - 1; attempt++) {
            replicaA.recordFailure(OPERATOR, SOURCE);
        }

        replicaA.recordSuccess(OPERATOR);

        assertThat(subjectsHeld())
                .as("the identity that authenticated is released from the shared store, so every "
                        + "instance sees the release and not only the one that admitted the sign-on")
                .doesNotContain(IDENTITY_SUBJECT)
                .as("and the source keeps its own history. An admitted sign-on releases the identity "
                        + "and nothing else, or a shared address could be laundered by signing in once "
                        + "with a valid account - see decision-log entry DL-342")
                .contains(SOURCE_SUBJECT);

        // A further failure for the same identity, from a different address so that only the identity's
        // count is under test. Had the release not been shared, replica B would still be holding three
        // for this identity and this failure would be the fourth, refusing an operator who has just
        // signed on successfully.
        final String elsewhere = "198.51.100.7";
        replicaB.recordFailure(OPERATOR, elsewhere);

        assertThat(replicaB.isRefusing(OPERATOR, elsewhere))
                .as("the identity's count restarted at one rather than resuming at four")
                .isFalse();
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("counts every concurrent failure for one subject even when the failures arrive at "
            + "different instances, so parallel attempts gain nothing")
    void countsEveryConcurrentFailureAcrossInstances() throws Exception {
        final int replicas = 4;
        final int perReplica = 5;
        final int total = replicas * perReplica;
        // The allowance is exactly the number of failures about to arrive, so the last one engages the
        // refusal. Any failure lost to a lost update leaves the subject unrefused.
        final Policy policy = new Policy(total, WINDOW, REFUSAL, ROOMY_TRACKED_SUBJECTS);
        final String subject = "i:contended";

        final List<FailureOutcome> outcomes = new ArrayList<>();
        final ExecutorService workers = Executors.newFixedThreadPool(replicas);
        try {
            final List<Future<List<FailureOutcome>>> runs = new ArrayList<>();
            for (int replica = 0; replica < replicas; replica++) {
                final PostgresSignOnAttemptLedger ledger = replica();
                runs.add(workers.submit(() -> {
                    final List<FailureOutcome> mine = new ArrayList<>();
                    for (int attempt = 0; attempt < perReplica; attempt++) {
                        mine.add(ledger.recordFailure(subject, NOW, policy));
                    }
                    return mine;
                }));
            }
            for (final Future<List<FailureOutcome>> run : runs) {
                outcomes.addAll(run.get(WAIT_SECONDS, TimeUnit.SECONDS));
            }
        } finally {
            workers.shutdown();
            assertThat(workers.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(outcomes).hasSize(total)
                .as("no failure was lost and none was reported as unreachable")
                .doesNotContain(FailureOutcome.STORE_UNAVAILABLE, FailureOutcome.DECLINED_AT_CEILING);
        assertThat(outcomes.stream().filter(FailureOutcome.REFUSAL_ENGAGED::equals).count())
                .as("all %d counted, so the last one exhausted the allowance - exactly once. A "
                        + "read-modify-write without the advisory lock would lose updates here and the "
                        + "subject would end unrefused", total)
                .isEqualTo(1L);
        assertThat(replica().isRefusing(subject, NOW)).isTrue();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("holds the tracked-subject ceiling across instances, so the bound is a property of the "
            + "deployment rather than of each process")
    void holdsTheCeilingExactlyAcrossInstances() {
        final int ceiling = 3;
        final Policy policy = policyWithCeiling(ceiling);
        final PostgresSignOnAttemptLedger replicaA = replica();
        final PostgresSignOnAttemptLedger replicaB = replica();

        assertThat(replicaA.recordFailure("i:one", NOW, policy)).isEqualTo(FailureOutcome.COUNTED);
        assertThat(replicaB.recordFailure("i:two", NOW, policy)).isEqualTo(FailureOutcome.COUNTED);
        assertThat(replicaA.recordFailure("i:three", NOW, policy)).isEqualTo(FailureOutcome.COUNTED);

        assertThat(replicaB.recordFailure("i:four", NOW, policy))
                .as("the ceiling counts rows and not process-local entries, so N instances no longer "
                        + "give the deployment N times the tracked subjects")
                .isEqualTo(FailureOutcome.DECLINED_AT_CEILING);
        assertThat(replicaA.trackedSubjectCount()).isEqualTo(ceiling);
        assertThat(replicaB.trackedSubjectCount())
                .as("and both instances read the same count")
                .isEqualTo(ceiling);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("frees room once windows have lapsed, so the ceiling bounds concurrent subjects and not "
            + "subjects ever seen")
    void freesRoomOnceWindowsHaveLapsed() {
        final Policy policy = policyWithCeiling(2);
        final PostgresSignOnAttemptLedger replicaA = replica();
        final PostgresSignOnAttemptLedger replicaB = replica();
        replicaA.recordFailure("i:long-gone-one", NOW, policy);
        replicaA.recordFailure("i:long-gone-two", NOW, policy);

        final Instant afterTheWindow = NOW.plus(WINDOW).plus(Duration.ofSeconds(1));

        assertThat(replicaB.recordFailure("i:newcomer", afterTheWindow, policy))
                .as("a spent row may go: the next failure for that subject would have restarted its "
                        + "count at one in any case. The sweep runs inside the guarded transaction of "
                        + "whichever instance next needs room, so no clean-up job is required")
                .isEqualTo(FailureOutcome.COUNTED);
        assertThat(replicaA.trackedSubjectCount()).isEqualTo(1);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("never drops a refusing row to make room, so a caller cannot clear the record of its "
            + "own abuse by generating subjects")
    void neverDropsARefusingRowToMakeRoom() {
        final Policy oneFailureRefuses = new Policy(1, WINDOW, REFUSAL, 2);
        final PostgresSignOnAttemptLedger ledger = replica();

        assertThat(ledger.recordFailure("i:abuser-one", NOW, oneFailureRefuses))
                .isEqualTo(FailureOutcome.REFUSAL_ENGAGED);
        assertThat(ledger.recordFailure("i:abuser-two", NOW, oneFailureRefuses))
                .isEqualTo(FailureOutcome.REFUSAL_ENGAGED);

        final Instant during = NOW.plus(Duration.ofSeconds(1));
        assertThat(ledger.recordFailure("i:pressure", during, oneFailureRefuses))
                .isEqualTo(FailureOutcome.DECLINED_AT_CEILING);
        assertThat(replica().isRefusing("i:abuser-one", during))
                .as("were a refusing row evicted under pressure, generating subjects would release "
                        + "one's own refusal - making the ceiling a bypass rather than a bound")
                .isTrue();
        assertThat(replica().isRefusing("i:abuser-two", during)).isTrue();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("stores every subject in one of the two namespaces the schema permits, and the schema "
            + "refuses anything else")
    void storesOnlyNamespacedSubjects() throws SQLException {
        governorOver(replica(), NOW).recordFailure(OPERATOR, SOURCE);

        assertThat(subjectsHeld())
                .as("the governor writes an identity subject and a source subject for one attempt")
                .hasSize(2)
                .allMatch(subject -> subject.startsWith("i:") || subject.startsWith("s:"))
                .as("and neither is longer than the column, which is the same bound the contract "
                        + "declares")
                .allMatch(subject -> subject.length() <= SignOnAttemptLedger.MAX_SUBJECT_LENGTH);

        assertThat(insertRejected("no-namespace-prefix"))
                .as("the namespace CHECK is enforced by the database rather than only by the code that "
                        + "writes through it, so a row that no governor would produce cannot appear by "
                        + "another route")
                .isTrue();
        assertThat(insertRejected("i:"))
                .as("and the minimum width refuses a namespace with nothing after it")
                .isTrue();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("declares itself deployment-wide, which is what the production start-up check reads")
    void declaresItselfDeploymentWide() {
        assertThat(replica().isDeploymentWide()).isTrue();
        assertThat(governorOver(replica(), NOW).isDeploymentWideState())
                .as("the governor republishes it, so the property can be asserted where the throttle "
                        + "is configured rather than only where the store is built")
                .isTrue();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("refuses nobody when the database cannot be reached, because no credential can be "
            + "verified then either")
    void refusesNobodyWhenTheDatabaseCannotBeReached() {
        final PostgresSignOnAttemptLedger unreachable = ledgerOverAnUnreachableDatabase();
        final Policy policy = policyWithCeiling(ROOMY_TRACKED_SUBJECTS);

        assertThat(unreachable.recordFailure("i:someone", NOW, policy))
                .as("the fault is reported as its own outcome rather than mistaken for a count or for "
                        + "saturation, so an operator sees that the protection is not working")
                .isEqualTo(FailureOutcome.STORE_UNAVAILABLE);
        assertThat(unreachable.isRefusing("i:someone", NOW))
                .as("refusing instead would turn one database fault into a deployment-wide sign-on "
                        + "outage lasting the whole refusal period beyond the fault, and would let "
                        + "anyone able to disturb the database lock every operator out. Nothing is "
                        + "given away by not refusing: this is the same database the credential store "
                        + "lives in, so while it is unreachable no attempt can be admitted anyway")
                .isFalse();
        assertThat(unreachable.canBeginTracking("i:someone", "s:somewhere", NOW, policy)).isTrue();
        assertThat(unreachable.trackedSubjectCount()).isZero();
    }

    /**
     * Reads back every subject the ledger currently holds.
     *
     * @return the stored subjects, in no particular order
     * @throws SQLException if the ledger cannot be read
     */
    private static List<String> subjectsHeld() throws SQLException {
        final List<String> subjects = new ArrayList<>();
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT subject FROM sign_on_attempt")) {
            while (rows.next()) {
                subjects.add(rows.getString(1));
            }
        }
        return subjects;
    }

    /**
     * Attempts to insert a subject directly and reports whether the database refused it.
     *
     * <p>Written through a plain statement rather than through the store, so that a rejection is
     * unambiguously the schema's and not the store's.
     *
     * @param  subject the subject to attempt
     * @return {@code true} when the insert was refused
     * @throws SQLException if the connection itself fails
     */
    private static boolean insertRejected(final String subject) throws SQLException {
        final String insert = "INSERT INTO sign_on_attempt"
                + " (subject, failures, window_started_at, refused_until) VALUES (?, 1, now(), NULL)";
        // The connection is acquired OUTSIDE the guarded block on purpose. Acquiring it inside would let
        // the catch below swallow a failure to connect and report it as a constraint rejection, so a
        // broken container would make this assertion pass rather than fail.
        try (Connection connection = connect()) {
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, subject);
                statement.executeUpdate();
                return false;
            } catch (final SQLException refusal) {
                assertThat(refusal.getSQLState())
                        .as("the rejection must be an integrity-constraint violation (SQL state class "
                                + "23) rather than any other database fault, or this case would pass "
                                + "for the wrong reason")
                        .startsWith("23");
                return true;
            }
        }
    }

    /**
     * Builds a store pointed at a port nothing listens on, so every call fails to connect.
     *
     * @return a store whose database cannot be reached
     */
    private static PostgresSignOnAttemptLedger ledgerOverAnUnreachableDatabase() {
        final DriverManagerDataSource unreachable = new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:1/nothing-listens-here", databaseUser(),
                databasePassword());
        unreachable.setDriverClassName(driverClassName());
        return new PostgresSignOnAttemptLedger(new JdbcTemplate(unreachable),
                new DataSourceTransactionManager(unreachable));
    }
}
