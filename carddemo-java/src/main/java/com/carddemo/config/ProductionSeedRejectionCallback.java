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
package com.carddemo.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refuses a production start-up against a database that has already been seeded, before a single
 * migration or application bean touches it.
 *
 * <h2>The gap this closes, which no configuration value can</h2>
 *
 * <p>{@link FlywayConfig} holds configuration controls that between them make a seed script
 * unresolvable by a production deployment: the resolved location list must be exactly
 * {@value FlywayConfig#SCHEMA_LOCATION}, so the sibling {@value FlywayConfig#SEED_LOCATION} is never
 * scanned, and their shared parent is refused because scanning is recursive. All of them act on
 * <em>this process's</em> configuration, and all of them are therefore blind to the one path that
 * matters most in practice: a database that was seeded <strong>before this process existed</strong>.
 *
 * <p>Re-pointing a connection string at a database a developer once ran under {@code local}, restoring
 * a development dump into a production instance, or promoting an environment that was previously used
 * for testing all produce the same state - fifty synthetic customer rows carrying regulated identity
 * data, and ten known sign-on identities of which five reach the administrative user-management
 * surface, whose stored credentials are all digests of one well-known value. Nothing this process
 * configures can undo that, and every configuration control will happily report success over it,
 * because from the configuration's point of view nothing is wrong: no seed was applied <em>by this
 * run</em>.
 *
 * <p>So this callback inspects the database instead of the configuration. It is registered for the
 * production profile alone, and it refuses rather than repairing: a deployment whose database holds
 * seeded credentials must stop and be corrected, not quietly continue while an operator decides
 * whether the rows matter.
 *
 * <h2>Three independent signals, any one of which is fatal</h2>
 *
 * <ol>
 *   <li><strong>The migration history.</strong> A successfully applied migration at or above version
 *       {@value #FIRST_SEED_VERSION} means a seed ran against this database. This is the authoritative
 *       signal and it covers both seeds at once. It is also the signal the reviewed defect named
 *       directly: an applied {@code V3} remains recorded when the same database is later opened under
 *       the production profile.</li>
 *   <li><strong>The seeded sign-on identities.</strong> Any of the ten fixture identifiers this module
 *       seeds present in {@code user_security}. This survives a rewritten or absent history - a dump
 *       restored without its history table, for instance - and it is the signal that speaks to the
 *       credential exposure specifically.</li>
 *   <li><strong>The seeded reference volumes, in conjunction.</strong> The exact seeded cardinality of
 *       <em>all five</em> of the tables named in {@link #SEEDED_VOLUMES} at once. Any one of them could
 *       legitimately match in a production database - the transaction types and categories are real
 *       legacy reference data a deployment would load operationally - but all five matching
 *       simultaneously, including fifty customers <em>and</em> three hundred unposted daily
 *       transactions, is the fingerprint of the reference seed and of nothing else. Requiring the
 *       conjunction is what makes the signal precise rather than merely suspicious.</li>
 * </ol>
 *
 * <p>Signals two and three both read tables that a fresh production database does not yet have, so
 * every read is guarded by an existence check and an absent table is simply not a signal. The history
 * table is guarded the same way, because on a first-ever migration it does not exist either.
 *
 * <h2>Why two events rather than one</h2>
 *
 * <p>Flyway raises {@link Event#BEFORE_VALIDATE} before {@link Event#BEFORE_MIGRATE} when
 * {@code validate-on-migrate} is enabled, which the production profile enables. Handling both means
 * this control runs at the earliest point either event offers, whichever the tool raises first, and it
 * costs one extra evaluation of three read-only signals.
 *
 * <p><strong>Validation is not a second line of defence here, and that was measured rather than
 * assumed.</strong> An already-seeded database opened under the production scope is <em>not</em>
 * rejected by {@code validate-on-migrate}: Flyway 11 ignores FUTURE migrations by default, so applied
 * versions 3 and 4 that the schema location cannot resolve are not a validation failure, and the
 * migration reports success over them. That is
 * asserted in {@code ProductionSeedRejectionCallbackIT}, and it is why this class is indispensable
 * rather than belt-and-braces: it is the only thing in the module, or in the migration tool, that sees
 * this state at all.
 *
 * <h2>What the refusal says, and what it deliberately does not</h2>
 *
 * <p>The message names this class's own literals - the profile, the seed version boundary, the tables
 * inspected and which signal fired - and never a row key, a credential, an identifier value or any
 * other content of the database. Decision {@code DL-041} in {@code docs/decision-log.md} governs that,
 * and it applies with particular force here: the whole point of the refusal is that this database
 * holds data that must not be exposed, and a diagnostic is the hardest place to remove it from.
 *
 * <p>Provenance: this control has no legacy antecedent. No legacy source text appears here.
 */
final class ProductionSeedRejectionCallback implements Callback {

    /** The name the migration tool reports for this callback. */
    static final String CALLBACK_NAME = "production seeded-database rejection";

    /**
     * The first version a seed script occupies. An applied migration at or above this version means a
     * seed ran, which is the whole of the first signal.
     */
    static final String FIRST_SEED_VERSION = "3";

    /**
     * The migration history table this control reads.
     *
     * <p>Held as a literal so the statement below is a complete constant and no identifier is
     * concatenated into SQL. The configured table name is compared against it before the read, and a
     * production deployment that renamed the history table is refused rather than silently skipped -
     * the same fail-closed posture {@link FlywayConfig} takes towards a widened location list.
     */
    static final String HISTORY_TABLE = "flyway_schema_history";

    /** The table carrying sign-on identities, read for the second signal. */
    static final String SIGN_ON_TABLE = "user_security";

    /**
     * The ten fixture sign-on identifiers {@code V4__seed_user_security.sql} inserts - five
     * administrative and five standard.
     *
     * <p>These are reserved by this module: they exist in the legacy provisioning stream and in this
     * module's seed, and nowhere else. A production deployment that genuinely wanted an identity of one
     * of these names would be refused, and that trade is deliberate and one-sided - renaming an
     * identity costs a moment, while a seeded production database is a credential incident.
     */
    static final List<String> SEEDED_SIGN_ON_IDENTIFIERS = List.of(
            "ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005",
            "USER0001", "USER0002", "USER0003", "USER0004", "USER0005");

    /**
     * The exact seeded cardinality of five tables, which must ALL match for the third signal to fire.
     *
     * <p>Read from {@code V3__seed_reference_data.sql}'s own documented volumes: fifty customers,
     * fifty-one disclosure-group rows, eighteen transaction categories, seven transaction types and
     * three hundred unposted daily transactions.
     */
    static final List<SeededVolume> SEEDED_VOLUMES = List.of(
            new SeededVolume("customer", 50L, "SELECT count(*) FROM customer"),
            new SeededVolume("disclosure_group", 51L, "SELECT count(*) FROM disclosure_group"),
            new SeededVolume("transaction_category", 18L,
                    "SELECT count(*) FROM transaction_category"),
            new SeededVolume("transaction_type", 7L, "SELECT count(*) FROM transaction_type"),
            new SeededVolume("daily_transaction", 300L,
                    "SELECT count(*) FROM daily_transaction"));

    /** Reads the versions of every successfully applied migration. A complete literal statement. */
    private static final String SELECT_APPLIED_VERSIONS =
            "SELECT version FROM flyway_schema_history WHERE success = TRUE AND version IS NOT NULL";

    /** Counts the seeded sign-on identifiers present. A complete literal statement. */
    private static final String COUNT_SEEDED_SIGN_ON_IDENTITIES =
            "SELECT count(*) FROM user_security WHERE sec_usr_id IN ("
                    + "'ADMIN001', 'ADMIN002', 'ADMIN003', 'ADMIN004', 'ADMIN005', "
                    + "'USER0001', 'USER0002', 'USER0003', 'USER0004', 'USER0005')";

    /** Asks whether one table exists in the connection's own schema. Bound, never concatenated. */
    private static final String TABLE_EXISTS =
            "SELECT count(*) FROM information_schema.tables"
                    + " WHERE table_schema = current_schema() AND table_name = ?";

    /** Parameter position of the table name in {@link #TABLE_EXISTS}. */
    private static final int TABLE_NAME_PARAMETER = 1;

    /** Column position of the single projected value in every count statement above. */
    private static final int FIRST_COLUMN = 1;

    /** Diagnostic channel. It records which signal was evaluated and never a value read. */
    private static final Logger LOGGER =
            LoggerFactory.getLogger(ProductionSeedRejectionCallback.class);

    /** Creates the callback. It holds no collaborator and no state: every answer comes from the database. */
    ProductionSeedRejectionCallback() {
    }

    /**
     * Selects the two events this callback acts on.
     *
     * <p>Both fire before any script is executed, which is what makes the refusal land before the
     * database is touched and long before the persistence provider, the messaging clients or any
     * {@code @ConfigurationProperties} consumer is built. Handling both is what keeps this class's own
     * diagnosis ahead of Flyway's more general validation message; see the class documentation.
     *
     * @param event   the event being offered
     * @param context the migration context, unused in this decision
     * @return {@code true} for the before-validate and before-migrate events
     */
    @Override
    public boolean supports(final Event event, final Context context) {
        return event == Event.BEFORE_VALIDATE || event == Event.BEFORE_MIGRATE;
    }

    /**
     * Elects to run inside whatever transaction the migration tool has open.
     *
     * <p>Every statement this callback issues is a read, so it neither needs nor creates a transaction
     * boundary of its own. Accepting the ambient one avoids opening a second connection during
     * start-up for no benefit.
     *
     * @param event   the event being handled, unused in this decision
     * @param context the migration context, unused in this decision
     * @return {@code true}, always
     */
    @Override
    public boolean canHandleInTransaction(final Event event, final Context context) {
        return true;
    }

    /**
     * Reports the name the migration tool uses for this callback.
     *
     * @return the callback's name, never {@code null}
     */
    @Override
    public String getCallbackName() {
        return CALLBACK_NAME;
    }

    /**
     * Evaluates the three signals and refuses the start-up when any of them fires.
     *
     * @param event   the event being handled, already matched by {@link #supports(Event, Context)}
     * @param context the migration context supplying the connection and the configured history table
     * @throws FlywayException when the database has already been seeded, or when it cannot be inspected
     */
    @Override
    public void handle(final Event event, final Context context) {
        requireKnownHistoryTable(context);
        try {
            inspect(context.getConnection());
        } catch (final SQLException failure) {
            // The message names the operation and this class's own literals. It carries no value read
            // from the database, because a diagnostic raised over a database suspected of holding
            // seeded personal data is the last place that data should be copied to.
            throw new FlywayException("unable to establish whether the production database has already"
                    + " been seeded; the start-up is refused rather than continued over a database"
                    + " whose state could not be read. Inspected the migration history table '"
                    + HISTORY_TABLE + "', the sign-on table '" + SIGN_ON_TABLE + "' and the seeded"
                    + " reference volumes", failure);
        }
    }

    /**
     * Refuses a configuration whose history table is not the one this control reads.
     *
     * <p>Fail-closed, and for the same reason {@link FlywayConfig} refuses a widened location list: a
     * renamed history table would make the first and most authoritative signal silently unevaluable,
     * and a control that quietly stops working is worse than one that is absent. This module declares
     * no {@code spring.flyway.table}, so reaching this refusal takes a deliberate override.
     *
     * @param context the migration context whose configuration is read
     * @throws FlywayException when the configured history table is not {@link #HISTORY_TABLE}
     */
    private void requireKnownHistoryTable(final Context context) {
        final String configured = context.getConfiguration().getTable();
        if (configured == null || !HISTORY_TABLE.equalsIgnoreCase(configured.strip())) {
            throw new FlywayException("the production migration-history table must be '"
                    + HISTORY_TABLE + "', because the control that refuses an already-seeded"
                    + " production database reads it by that name. A renamed history table would leave"
                    + " that control unable to see an applied seed migration at all, so the start-up is"
                    + " refused rather than continued with the control silently disabled. Remove the"
                    + " spring.flyway.table override");
        }
    }

    /**
     * Evaluates the three signals in order of authority.
     *
     * <p>Package-private rather than private because it has a second caller:
     * {@link FlywayConfig#requireUnseededProductionDatabase} runs the same inspection on every
     * production start-up, including one where the migration tool was switched off and this callback
     * is therefore never offered an event. The two callers share this method rather than each holding
     * their own copy of the queries, so the three signals cannot be widened in one path and left
     * narrow in the other.
     *
     * @param connection the connection to inspect
     * @throws SQLException      when a read fails
     * @throws FlywayException   when a signal fires
     */
    void inspect(final Connection connection) throws SQLException {
        rejectAppliedSeedVersions(connection);
        rejectSeededSignOnIdentities(connection);
        rejectSeededReferenceVolumes(connection);
        LOGGER.info("Production database inspected for seeded state before migration: no applied seed"
                + " version at or above {}, none of the {} reserved sign-on identifiers, and no match"
                + " on the seeded reference volumes", FIRST_SEED_VERSION,
                SEEDED_SIGN_ON_IDENTIFIERS.size());
    }

    /**
     * Signal one: refuses when the migration history records an applied seed migration.
     *
     * @param connection the connection to inspect
     * @throws SQLException    when the read fails
     * @throws FlywayException when a seed version has been applied
     */
    private void rejectAppliedSeedVersions(final Connection connection) throws SQLException {
        if (!tableExists(connection, HISTORY_TABLE)) {
            return;
        }
        final List<String> applied = new ArrayList<>();
        try (Statement select = connection.createStatement();
                ResultSet rows = select.executeQuery(SELECT_APPLIED_VERSIONS)) {
            while (rows.next()) {
                final String version = rows.getString(FIRST_COLUMN);
                if (reachesSeedVersions(version)) {
                    applied.add(version);
                }
            }
        }
        if (!applied.isEmpty()) {
            throw new FlywayException("this production database has already had "
                    + applied.size() + " seed migration(s) applied to it: the migration history table '"
                    + HISTORY_TABLE + "' records a successful migration at or above version '"
                    + FIRST_SEED_VERSION + "', which is where the reference-data seed and the"
                    + " sign-on-identity seed sit. Those insert fifty synthetic customer rows holding"
                    + " regulated identity data and ten known sign-on identities whose stored"
                    + " credentials are digests of one well-known value, so this database is not fit to"
                    + " serve as a production database. It was seeded before this process started, so"
                    + " no configuration change can correct it: point the deployment at a database that"
                    + " was never seeded, or remove the seeded rows and their history entries"
                    + " deliberately");
        }
    }

    /**
     * Signal two: refuses when any reserved fixture sign-on identifier is present.
     *
     * @param connection the connection to inspect
     * @throws SQLException    when the read fails
     * @throws FlywayException when a seeded identity is present
     */
    private void rejectSeededSignOnIdentities(final Connection connection) throws SQLException {
        if (!tableExists(connection, SIGN_ON_TABLE)) {
            return;
        }
        final long present = scalar(connection, COUNT_SEEDED_SIGN_ON_IDENTITIES);
        if (present > 0L) {
            throw new FlywayException("this production database holds " + present
                    + " of the " + SEEDED_SIGN_ON_IDENTIFIERS.size() + " sign-on identifiers reserved"
                    + " by this module's fixture seed, in table '" + SIGN_ON_TABLE + "'. Five of the"
                    + " ten carry the administrative user type and every one of them has a stored"
                    + " credential that is a digest of one well-known value, so a deployment reading"
                    + " this database would ship with predictable administrative access. The migration"
                    + " history may show no seed applied - a restored dump carries the rows without"
                    + " them - which is why the rows themselves are checked. Point the deployment at a"
                    + " database that was never seeded, or remove those identities deliberately");
        }
    }

    /**
     * Signal three: refuses when every seeded reference volume matches exactly.
     *
     * <p>The conjunction is the control. Each cardinality on its own is a coincidence a production
     * database could reach; all five at once is the reference seed's fingerprint.
     *
     * @param connection the connection to inspect
     * @throws SQLException    when a read fails
     * @throws FlywayException when every seeded volume matches
     */
    private void rejectSeededReferenceVolumes(final Connection connection) throws SQLException {
        for (final SeededVolume expected : SEEDED_VOLUMES) {
            if (!tableExists(connection, expected.table())) {
                return;
            }
            if (scalar(connection, expected.countStatement()) != expected.rowCount()) {
                return;
            }
        }
        throw new FlywayException("this production database matches the reference seed's row counts in"
                + " all " + SEEDED_VOLUMES.size() + " of the tables that seed populates, simultaneously"
                + " - including fifty customer rows carrying regulated identity data and three hundred"
                + " unposted daily transactions. Any one of those counts could be a coincidence; all of"
                + " them together is the fixture seed. The migration history may show no seed applied,"
                + " because a restored dump carries the rows without it. Point the deployment at a"
                + " database that was never seeded, or remove those rows deliberately");
    }

    /**
     * Reports whether one table exists in the connection's own schema.
     *
     * <p>The name is bound as a parameter rather than concatenated, and it is one of this class's own
     * literals in every call, so the statement is fixed and no identifier can be injected through it.
     * An absent table is not a signal: a production database on its very first migration has none of
     * these tables, and refusing that would refuse every first deployment.
     *
     * @param connection the connection to inspect
     * @param table      the unqualified, lower-case table name
     * @return {@code true} when the table exists
     * @throws SQLException when the catalogue read fails
     */
    private boolean tableExists(final Connection connection, final String table) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(TABLE_EXISTS)) {
            query.setString(TABLE_NAME_PARAMETER, table.toLowerCase(Locale.ROOT));
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() && rows.getLong(FIRST_COLUMN) > 0L;
            }
        }
    }

    /**
     * Runs one complete literal statement and returns its single numeric value.
     *
     * @param connection the connection to read on
     * @param sql        a complete literal statement declared as a constant of this class
     * @return the projected value, or zero when the statement returned no row
     * @throws SQLException when the read fails
     */
    private long scalar(final Connection connection, final String sql) throws SQLException {
        try (Statement select = connection.createStatement();
                ResultSet rows = select.executeQuery(sql)) {
            return rows.next() ? rows.getLong(FIRST_COLUMN) : 0L;
        }
    }

    /**
     * Reports whether a recorded version reaches the first seed script.
     *
     * <p>Fail-closed on an unreadable version: the migration tool's own parser raises an exception
     * carrying the offending text, so it is called inside a guard, and a version this method cannot
     * read is treated as reaching the seeds. A history row whose version cannot be parsed is not
     * something to continue past on a production database.
     *
     * <p>Package-private so its fail-closed behaviour can be asserted directly. Reaching it through a
     * migration would require a history row the migration tool itself refuses to load, so the only way
     * to prove the guard rather than assume it is to call it.
     *
     * @param version the version as recorded in the history table, which may be {@code null}
     * @return {@code true} when the version reaches a seed script
     */
    boolean reachesSeedVersions(final String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        try {
            return MigrationVersion.fromVersion(version.strip()).isAtLeast(FIRST_SEED_VERSION);
        } catch (final RuntimeException unreadable) {
            return true;
        }
    }

    /**
     * One table, the exact number of rows the reference seed puts in it, and the complete literal
     * statement that counts them.
     *
     * <p><strong>All three travel together deliberately.</strong> The three were three parallel lists
     * addressed by a shared index, so a table's name, its expected count and the statement that read it
     * were bound to one another by position alone - and inserting, removing or reordering one entry of one
     * list silently paired a table with another table's count. The failure mode is the worst kind for this
     * class: the check would still run, still pass, and still be reported as a seed-detection control while
     * comparing the wrong two numbers. As one record per table the pairing is structural and a reordering
     * cannot separate them.
     *
     * <p>The statement is a complete literal held here rather than composed from {@link #table()}. Nothing
     * about the seed detection needs dynamic SQL, and the audited property is that this class concatenates
     * no identifier into a statement - which a statement built from a field, however trusted the field,
     * would give up.
     *
     * @param table          the unqualified table name
     * @param rowCount       the seeded row count
     * @param countStatement the complete literal statement that counts this table's rows
     */
    record SeededVolume(String table, long rowCount, String countStatement) {
    }
}
