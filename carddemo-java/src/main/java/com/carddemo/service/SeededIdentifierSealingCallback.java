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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Guarantees, as the last act of a migration, that every seeded customer identity value is held as
 * the module's own authenticated envelope <em>and</em> opens under the key the running process holds.
 *
 * <h2>The two invariants this holds, and why one check cannot cover both</h2>
 *
 * <p>{@code V1__create_schema.sql} defines two protected customer columns and states the invariant
 * plainly: {@code govt_issued_id} is {@code NOT NULL}, and any row a seed migration inserts must carry
 * an envelope rather than a cleartext identifier. {@code V3__seed_reference_data.sql} honours that
 * directly, and for both columns: it inserts one hundred fixed {@code ENC1} envelopes, each produced
 * by {@link SensitiveFieldEncryptionService} under the one non-production key the seed-bearing
 * profiles commit - fifty over the twenty characters the fixture record holds at offset 288, and fifty
 * over the nine digits it holds at offset 279.
 *
 * <p>The national identifier was once seeded as {@code null} in every row, on the reasoning that a
 * value sealed under a committed key is recoverable by anyone holding the repository. That premise is
 * true and the conclusion did not follow: those same nine bytes are already committed in cleartext
 * twice, in the read-only parity baseline at {@code app/data/ASCII/custdata.txt} and in this module's
 * own fixture at {@code src/test/resources/fixtures/input/custdata.txt}. Absence protected nothing and
 * cost the only proof that mattered, because no delivered row then exercised a stored national
 * identifier at all - so the reveal and mask paths were verified against an empty column.
 *
 * <p>So on a delivered database this class converts nothing, and that is the intended outcome rather
 * than a sign it is idle. It exists for the two ways the invariant can still be broken, and it
 * enforces one thing for each:
 *
 * <ol>
 *   <li><strong>Shape.</strong> A future edit to a seed, or a row inserted by any other means, could
 *       leave a cleartext identifier in a protected column. {@code Customer}'s constructor and its
 *       setter both refuse such a value, but object-relational hydration assigns fields directly and
 *       consults neither, so nothing else would object. Every unsealed value found is sealed.</li>
 *   <li><strong>Key and column binding.</strong> An envelope opens under exactly one key and carries
 *       exactly one column binding. One hundred of them are fixed literals in a committed
 *       script, so a process whose configured key is not the key they were sealed under - or a
 *       literal sealed
 *       without the binding of the column it sits in - holds fifty rows of regulated data, in two
 * columns, that it cannot read. <strong>The marker check that decides the first invariant is blind to
 * both</strong>,
 *       because an envelope sealed under a foreign key, and one sealed for another column, are each
 *       still shaped like an envelope. Every stored value is therefore <em>opened under its column's
 *       own binding</em> rather than merely recognised, which is the same question every reader of
 *       these columns asks, and one that does not open that way fails the migration.</li>
 * </ol>
 *
 * <p>The second invariant is what gives this class teeth on a delivered database, and it is why the
 * seed-bearing profiles state the fixture key as a bare literal instead of as an environment-variable
 * default: an override would produce precisely that state. Between them, the literal removes the
 * ordinary way in and this check refuses the state however else it arose - turning a failure that
 * would otherwise surface only when something happened to decrypt a row into a refusal at start-up.
 *
 * <h2>Why a callback and not a fifth migration</h2>
 *
 * <p>A versioned {@code V5} would work and would be wrong in two ways. It would make the delivered
 * migration set end at a version no shipped script reaches - a claim the profile documents make and
 * a test asserts against the delivered scripts, so the ledger would have to be weakened to
 * accommodate it. And it would record a one-time application in the schema history, when what is
 * wanted is an invariant that holds after <em>every</em> migration of a seed-bearing profile,
 * including one that applied nothing because the seeds were already present.
 *
 * <p>An {@link Event#AFTER_MIGRATE} callback carries no version and fires once per migrate operation,
 * including one that applied nothing - which is what makes both invariants hold on every start-up
 * rather than only on a first run. What the event does <em>not</em> offer is atomicity with the seeds
 * themselves; {@link #canHandleInTransaction(Event, Context)} states that boundary rather than
 * claiming more than the event gives. {@link FlywayConfig} registers this for the local and test
 * profiles alone, so production - which lists no seed location and receives no row from either seed -
 * neither seeds an identifier nor carries the component that would seal one.
 *
 * <h2>Idempotence, and why it is a property rather than a precaution</h2>
 *
 * <p>Each value is examined before it is converted and one that already carries the envelope marker
 * is left exactly as it is. A second start-up therefore updates nothing, and an envelope is never
 * wrapped inside another envelope - which matters, because the encryption service refuses to protect
 * an already-protected value and would otherwise turn the second start of a local stack into a
 * failure.
 *
 * <h2>What is written, and what is never written</h2>
 *
 * <p>Each value is sealed through {@link SensitiveFieldEncryptionService} bound to the column it is
 * being stored in, so an envelope written for one column cannot later be read as another's. No
 * second encryption mechanism is introduced here and no key is handled here: this class holds the
 * one service and calls it.
 *
 * <p>The diagnostic records counts only. Neither a cleartext identifier, nor an envelope, nor a
 * customer identifier is ever logged, which is the same rule decision {@code DL-041} in
 * {@code docs/decision-log.md} applies to every rejection diagnostic in the module.
 *
 * <p>Provenance: the legacy record holds the government-issued identifier as twenty cleartext
 * characters at offset 288 of the 500-byte customer record and the national identifier as nine
 * cleartext digits at offset 279, per {@code app/cpy/CVCUS01Y.cpy} at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Protecting them at rest is a deliberate
 * divergence from the legacy design rather than a translation of it, recorded as decision
 * {@code DL-110} in {@code docs/decision-log.md}, which also records why this runs as a lifecycle
 * callback rather than as a fifth versioned migration. No legacy source text appears here.
 *
 * <h2>Why it registers itself, and why it lives in this package</h2>
 *
 * <p>It sits beside the collaborator it needs. Sealing an identifier is the module's field-encryption
 * concern, which {@link SensitiveFieldEncryptionService} owns and which lives here; a configuration
 * class publishing this bean would have made the configuration package depend on the service package,
 * and the service package must be free to be depended <em>upon</em> - the menu-option catalogue in
 * {@code com.carddemo.config} is injected into a service, so a configuration-to-service edge would
 * close a package cycle. Declaring the stereotype here removes the edge without changing a line of
 * behaviour.
 *
 * <p>Registration is not lost by moving: Spring Boot's Flyway auto-configuration collects every
 * {@link Callback} bean in the context, wherever it is declared, and hands them to the migration tool
 * in the order the context publishes them. The profile restriction is the whole of the reason this bean
 * is conditional - a production migration stops below both seed versions, so no row this callback would
 * act on can exist there, and registering it anyway would put a table-wide pass on a production
 * migration path for no purpose.
 */
@Component
@Profile({"local", "test"})
public final class SeededIdentifierSealingCallback implements Callback {

    /**
     * The name the migration tool reports for this callback. Held as a constant so a log line, a
     * test and the tool's own reporting cannot describe it differently.
     */
    static final String CALLBACK_NAME = "seeded customer identity sealing";

    /**
     * Reads the two protected columns of every customer row. No predicate is applied: recognising an
     * envelope is this class's own decision and is taken in Java against the one service that
     * produces them, rather than by pattern-matching a marker inside a statement.
     */
    private static final String SELECT_CUSTOMER_IDENTITIES =
            "SELECT cust_id, cust_ssn, govt_issued_id FROM customer";

    /** Replaces one row's two protected columns with the values this class sealed. */
    private static final String UPDATE_CUSTOMER_IDENTITIES =
            "UPDATE customer SET cust_ssn = ?, govt_issued_id = ? WHERE cust_id = ?";

    /** Column holding the customer's key, used to address the row being converted. */
    private static final String CUSTOMER_KEY_COLUMN = "cust_id";

    /** Column holding the national identifier, nullable and protected. */
    private static final String NATIONAL_IDENTIFIER_COLUMN = "cust_ssn";

    /** Column holding the government-issued identifier, required and protected. */
    private static final String GOVERNMENT_IDENTIFIER_COLUMN = "govt_issued_id";

    /** Parameter position of the sealed national identifier in the update. */
    private static final int NATIONAL_IDENTIFIER_PARAMETER = 1;

    /** Parameter position of the sealed government-issued identifier in the update. */
    private static final int GOVERNMENT_IDENTIFIER_PARAMETER = 2;

    /** Parameter position of the customer key in the update. */
    private static final int CUSTOMER_KEY_PARAMETER = 3;

    /** Diagnostic channel. It records counts and never a value. */
    private static final Logger LOGGER =
            LoggerFactory.getLogger(SeededIdentifierSealingCallback.class);

    /** The module's single field-encryption service; the only producer of an envelope. */
    private final SensitiveFieldEncryptionService encryption;

    /**
     * Creates the callback over the module's encryption service.
     *
     * @param encryption the one service that produces and reads an envelope; must not be
     *                   {@code null}
     */
    public SeededIdentifierSealingCallback(final SensitiveFieldEncryptionService encryption) {
        this.encryption = Objects.requireNonNull(encryption, "encryption must not be null");
    }

    /**
     * Selects the single event this callback acts on.
     *
     * <p>{@link Event#AFTER_MIGRATE} fires once per migrate operation, after the last script has
     * been applied and while the migration's connection is still open. It also fires when nothing
     * was pending, which is what makes the invariant hold on a restart rather than only on a first
     * run.
     *
     * @param event   the event being offered
     * @param context the migration context, unused in this decision
     * @return {@code true} only for the after-migrate event
     */
    @Override
    public boolean supports(final Event event, final Context context) {
        return event == Event.AFTER_MIGRATE;
    }

    /**
     * Elects to run the pass in a transaction of its own.
     *
     * <p>Returning {@code true} makes the conversion all-or-nothing <em>within itself</em>: either
     * every value this pass converts is written or none is, so it cannot leave some identifiers sealed
     * and some in cleartext.
     *
     * <p><strong>It does not make the pass atomic with the seeds it inspects, and no return value here
     * could.</strong> {@link Event#AFTER_MIGRATE} is raised after migration execution has completed
     * and the migration's own transaction has committed, so the seeded rows are already durable before
     * this class is called at all. Saying so plainly matters, because the opposite was once claimed
     * here and a reader relying on it would believe a rollback exists that does not.
     *
     * <p>What the boundary actually costs is bounded, and is why {@link Event#AFTER_MIGRATE} remains
     * the right event. A failure in this pass propagates out of {@code migrate()} and aborts the
     * start-up, so the application never runs over a database whose identity columns are unsealed or
     * unreadable, while the committed migration and the recorded history stay consistent with each
     * other rather than diverging. Both halves of the pass are idempotent - an already-sealed value is
     * left alone, and opening a value changes nothing - so the corrected start-up simply runs them
     * again.
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
     * Seals every unsealed protected identifier on the migration's own connection.
     *
     * @param event   the event being handled, already matched by {@link #supports(Event, Context)}
     * @param context the migration context supplying the connection
     * @throws FlywayException when the conversion cannot be completed, or when a stored value does not
     *                         open under the configured key, so the migration fails rather than
     *                         reporting success over a database holding cleartext or unreadable
     *                         regulated data
     */
    @Override
    public void handle(final Event event, final Context context) {
        final Connection connection = context.getConnection();
        try {
            final int converted = seal(connection);
            if (converted > 0) {
                LOGGER.info("Sealed {} seeded customer identity value(s) into the at-rest"
                        + " encryption envelope", converted);
            }
            // Sealing satisfies the shape invariant; only opening the values satisfies the key
            // invariant. It runs second because it must also inspect what sealing just wrote, and it
            // runs on the same connection, so it reads this pass's own writes.
            final int opened = verifyEveryStoredValueOpens(connection);
            LOGGER.info("Verified {} stored customer identity value(s) open under the configured"
                    + " field-encryption key", opened);
        } catch (final SQLException failure) {
            // The message names the operation and the columns, which are this class's own literals.
            // No row key and no value is named: a diagnostic that carried either would put the very
            // data this class exists to protect into a log, which is where it is hardest to remove.
            throw new FlywayException("unable to seal the seeded customer identity columns "
                    + NATIONAL_IDENTIFIER_COLUMN + " and " + GOVERNMENT_IDENTIFIER_COLUMN
                    + " into the at-rest encryption envelope; the migration is failed rather than"
                    + " reported successful over rows still holding cleartext", failure);
        }
    }

    /**
     * Opens every stored protected value under the configured key, and fails the migration on the
     * first one that will not open.
     *
     * <p>This is the check the envelope-marker test cannot make. {@link #needsSealing(String)} asks
     * whether a value <em>looks</em> like an envelope, which is the right question for the shape
     * invariant and the wrong one for the key invariant: an envelope sealed under some other key looks
     * exactly like an envelope sealed under this one. The one hundred envelopes in
     * {@code V3__seed_reference_data.sql} are fixed literals that no pass can re-key, so the only way
     * to know they are readable by the process that just migrated them is to read them.
     *
     * <p><strong>The check is authenticated decryption <em>under the column binding</em>, and the binding
     * half is the half that matters most.</strong> Only one form of envelope legitimately occupies these
     * columns: one bound to the column it is stored in. The hundred seeded literals carry that
     * binding and so does every value this class seals, because both are produced by
     * {@link SensitiveFieldEncryptionService#protect(String, String)}. Opening under the binding is
     * therefore the same question the application asks: every reader of these two columns - the account
     * view transaction, the account update transaction and the statement job - opens them through
     * {@link SensitiveFieldEncryptionService#reveal(String, String)}, which refuses an envelope written
     * for any other column.
     *
     * <p>Checking only authentication would leave the more likely defect invisible, and it once did. An
     * unbound envelope authenticates under the key and is refused by every one of those readers, so a
     * seed carrying unbound literals starts cleanly, reports a successful migration, and then fails every
     * request that reads a customer. Verifying here exactly what the application verifies is what turns
     * that from a runtime failure on the most-used read screen into a start-up failure naming the column.
     * Recorded as {@code DL-103}.
     *
     * <p>The recovered cleartext is deliberately discarded. Nothing is compared against an expected
     * value, because the expected values are regulated identifiers and this class must not hold them;
     * that a value opens at all is exactly the property being established. The stronger comparison
     * against the fixture record is made by {@code SeededProtectedIdentifierIT}, where a test fixture
     * may legitimately carry it.
     *
     * @param connection the migration's open connection
     * @return the number of stored values proved openable, one hundred on a delivered database
     * @throws SQLException    when the read fails
     * @throws FlywayException when a stored value does not open under the configured key
     */
    int verifyEveryStoredValueOpens(final Connection connection) throws SQLException {
        int opened = 0;
        try (Statement select = connection.createStatement();
                ResultSet rows = select.executeQuery(SELECT_CUSTOMER_IDENTITIES)) {
            while (rows.next()) {
                opened += verifyOpens(NATIONAL_IDENTIFIER_COLUMN,
                        rows.getString(NATIONAL_IDENTIFIER_COLUMN));
                opened += verifyOpens(GOVERNMENT_IDENTIFIER_COLUMN,
                        rows.getString(GOVERNMENT_IDENTIFIER_COLUMN));
            }
        }
        return opened;
    }

    /**
     * Opens one stored value, or fails the migration naming the column and the property and nothing
     * else.
     *
     * <p>An absent or blank value is not a failure and is not counted. The national identifier column
     * is nullable because the schema permits a customer with no identifier on file, and although the
     * delivered seed now fills all fifty rows, a row arriving by any other means may still leave it
     * empty. {@link #needsSealing(String)} leaves a blank alone for the same reason, so the two passes
     * agree about what is and is not a value.
     *
     * @param column the column the value was read from, one of this class's own literals
     * @param stored the value read from the column, possibly {@code null}
     * @return {@code 1} when a value was present and opened, {@code 0} when there was no value
     * @throws FlywayException when a present value does not open under the configured key, or does not
     *                         carry this column's binding
     */
    private int verifyOpens(final String column, final String stored) {
        if (stored == null || stored.isBlank()) {
            return 0;
        }
        try {
            this.encryption.reveal(bindingNameOf(column), stored);
            return 1;
        } catch (final RuntimeException unreadable) {
            // Names the column and the property key, both of which are literals of this module. The
            // value, the recovered cleartext and the key are all withheld - decision DL-041 - and the
            // chained cause carries only the service's own verdict, which names no value either.
            throw new FlywayException("a stored value in customer." + column + " does not open as"
                    + " a value bound to " + bindingNameOf(column) + " under the key configured by "
                    + SensitiveFieldEncryptionService.FIELD_ENCRYPTION_KEY_PROPERTY
                    + "; an envelope opens under exactly one key and carries exactly one column"
                    + " binding, and the seeded envelopes are fixed literals that no migration can"
                    + " re-key or re-bind, so an overridden or rotated key - or a literal sealed"
                    + " without this column's binding - leaves regulated data unreadable by every"
                    + " reader of the column. The migration is failed rather than reported successful"
                    + " over rows the application cannot read. Restore the key this profile declares,"
                    + " or rebuild the database from the migrations under the key now configured",
                    unreadable);
        }
    }

    /**
     * Names the binding one of this class's two columns seals its values under.
     *
     * <p>The mapping is stated once, here, and both halves of the class read it: the sealing pass binds a
     * value it writes, and the verifying pass opens a value it reads. A second spelling anywhere would
     * let one half write what the other cannot read, which is exactly the defect the verifying pass now
     * catches.
     *
     * @param column one of this class's own column literals
     * @return the canonical binding name the field-encryption service declares for that column
     * @throws IllegalStateException if asked for a column this class does not own, which is a wiring
     *                               fault rather than a data condition
     */
    private static String bindingNameOf(final String column) {
        if (NATIONAL_IDENTIFIER_COLUMN.equals(column)) {
            return SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD;
        }
        if (GOVERNMENT_IDENTIFIER_COLUMN.equals(column)) {
            return SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD;
        }
        throw new IllegalStateException("customer." + column + " is not one of the two protected"
                + " columns this callback owns, so it has no binding name here");
    }

    /**
     * Converts each unsealed value and returns how many values were written.
     *
     * <p>The rows are read first and updated afterwards rather than updated through the open result
     * set, so the read completes before the write begins and the two cannot interleave on one
     * connection. Fifty rows is the seeded volume, so materialising the work list costs nothing
     * worth optimising away.
     *
     * @param connection the migration's open connection
     * @return the number of column values sealed, which is zero on a database already converted
     * @throws SQLException when the read or the write fails
     */
    int seal(final Connection connection) throws SQLException {
        final List<SealedRow> pending = readUnsealedRows(connection);
        if (pending.isEmpty()) {
            return 0;
        }
        int converted = 0;
        try (PreparedStatement update = connection.prepareStatement(UPDATE_CUSTOMER_IDENTITIES)) {
            for (final SealedRow row : pending) {
                update.setString(NATIONAL_IDENTIFIER_PARAMETER, row.nationalIdentifier());
                update.setString(GOVERNMENT_IDENTIFIER_PARAMETER, row.governmentIdentifier());
                update.setString(CUSTOMER_KEY_PARAMETER, row.customerKey());
                update.addBatch();
                converted += row.sealedValueCount();
            }
            update.executeBatch();
        }
        return converted;
    }

    /**
     * Reads every customer row and returns the sealed form of those holding at least one cleartext
     * value.
     *
     * <p>A row already carrying an envelope in both columns produces no entry, which is what makes
     * the pass idempotent. A {@code null} national identifier is carried through as {@code null}:
     * the reference seed leaves that column unseeded precisely so that no cleartext identifier is
     * transcribed into a checked-in artifact, and an absent value needs no envelope.
     *
     * @param connection the migration's open connection
     * @return the rows to write, in read order; empty when nothing needs converting
     * @throws SQLException when the read fails
     */
    private List<SealedRow> readUnsealedRows(final Connection connection) throws SQLException {
        final List<SealedRow> pending = new ArrayList<>();
        try (Statement select = connection.createStatement();
                ResultSet rows = select.executeQuery(SELECT_CUSTOMER_IDENTITIES)) {
            while (rows.next()) {
                final String customerKey = rows.getString(CUSTOMER_KEY_COLUMN);
                final String nationalIdentifier = rows.getString(NATIONAL_IDENTIFIER_COLUMN);
                final String governmentIdentifier = rows.getString(GOVERNMENT_IDENTIFIER_COLUMN);
                final boolean sealNational = needsSealing(nationalIdentifier);
                final boolean sealGovernment = needsSealing(governmentIdentifier);
                if (!sealNational && !sealGovernment) {
                    continue;
                }
                pending.add(new SealedRow(
                        customerKey,
                        sealNational
                                ? this.encryption.protect(
                                        SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD,
                                        nationalIdentifier)
                                : nationalIdentifier,
                        sealGovernment
                                ? this.encryption.protect(
                                        SensitiveFieldEncryptionService
                                                .CUSTOMER_GOVT_ISSUED_ID_FIELD,
                                        governmentIdentifier)
                                : governmentIdentifier,
                        (sealNational ? 1 : 0) + (sealGovernment ? 1 : 0)));
            }
        }
        return pending;
    }

    /**
     * Reports whether a stored value has to be converted.
     *
     * <p>Absent and blank values are left alone - the column is nullable, and a blank would seal
     * nothing meaningful - and a value already shaped as an envelope is left alone because it
     * already is one.
     *
     * @param stored the value read from the column
     * @return {@code true} when the value is present, not blank and not already an envelope
     */
    private boolean needsSealing(final String stored) {
        return stored != null && !stored.isBlank() && !this.encryption.isProtected(stored);
    }

    /**
     * One row's key and the two values to be written to it, with how many of them this pass sealed.
     *
     * @param customerKey          the row's key, used only to address the update
     * @param nationalIdentifier   the value to store in the national-identifier column, sealed when
     *                             it needed sealing and carried through unchanged otherwise, which
     *                             includes {@code null}
     * @param governmentIdentifier the value to store in the government-identifier column, under the
     *                             same rule
     * @param sealedValueCount     how many of the two values this pass sealed, being one or two
     */
    private record SealedRow(String customerKey, String nationalIdentifier,
            String governmentIdentifier, int sealedValueCount) {
    }
}
