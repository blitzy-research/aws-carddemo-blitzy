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
import java.util.Objects;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.carddemo.service.SensitiveFieldEncryptionService;

/**
 * Converts every seeded customer identity value that is still at rest in cleartext into the
 * module's own authenticated envelope, as the last act of a migration.
 *
 * <h2>The defect this closes</h2>
 *
 * <p>{@code V1__create_schema.sql} defines two protected customer columns and states the invariant
 * plainly: {@code govt_issued_id} is {@code NOT NULL}, so any row a seed migration inserts must
 * carry an envelope produced under the deployment's own key and never a cleartext identifier.
 * {@code V3__seed_reference_data.sql} cannot honour that. It is static forward-only SQL, an envelope
 * is keyed, and committing key material to the repository to make a seed deterministic would be a
 * worse defect than the one it closed - so the reference seed writes the fixture's twenty-character
 * identifiers as they stand and records the divergence in its own header.
 *
 * <p>Nothing then objected. {@code Customer}'s constructor and its setter both refuse a value that
 * is not an envelope, but object-relational hydration assigns fields directly and consults neither,
 * so fifty regulated identifiers sat in cleartext in every local and test database while the code
 * that reads them was written as though they could not. That is the whole of the gap: not a missing
 * check, a check that the only writer of those rows never passed through.
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
 * <p>An {@link Event#AFTER_MIGRATE} callback carries no version, runs on the migration's own
 * connection inside the migration's own transaction, and is therefore complete before the
 * application's first read. {@link FlywayConfig} registers it for the local and test profiles alone,
 * so production - which lists no seed location and receives no row from either seed - neither seeds
 * an identifier nor carries the component that would seal one.
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
 */
final class SeededIdentifierSealingCallback implements Callback {

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
    SeededIdentifierSealingCallback(final SensitiveFieldEncryptionService encryption) {
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
     * Elects to run inside the migration's transaction.
     *
     * <p>The conversion must be atomic with the seeds it converts: a failure half-way through would
     * otherwise leave some identifiers sealed and some in cleartext, and the next start-up would
     * find a database no assertion describes.
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
     * @throws FlywayException when the conversion cannot be completed, so the migration fails rather
     *                         than reporting success over a database still holding cleartext
     */
    @Override
    public void handle(final Event event, final Context context) {
        try {
            final int converted = seal(context.getConnection());
            if (converted > 0) {
                LOGGER.info("Sealed {} seeded customer identity value(s) into the at-rest"
                        + " encryption envelope", converted);
            }
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
     * <p>Absent and blank values are left alone - the first is deliberately unseeded and the second
     * would seal nothing meaningful - and a value already shaped as an envelope is left alone
     * because it already is one.
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
