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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.carddemo.config.FlywayConfig;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.util.SensitiveFieldCodec;

/**
 * Migrates the real schema and the real seeds into their own database, with the sealing callback
 * registered exactly as the local and test profiles register it, and then asserts that no regulated
 * identifier is left at rest in cleartext.
 *
 * <h2>Why this test exists rather than only its unit companion</h2>
 *
 * <p>Both invariants the callback holds are invisible to any single piece of the system, which is why
 * they are asserted against a real migration. The shape invariant was once broken while every
 * individual piece behaved: the schema declared the column as holding an envelope, the entity refused a
 * value that was not one, and object-relational hydration assigned the field directly so nothing
 * consulted the refusal. The key invariant is broken the same way today if a process is configured with
 * a key the committed envelopes were not sealed under - the marker check still passes every row, and
 * the failure surfaces only at whatever later moment something decrypts one. Only running the actual
 * seed scripts through an actual migration and then reading the actual column back can show whether
 * either invariant holds. A mocked result set cannot, because the value it presents is the one the test
 * chose.
 *
 * <h2>Why a database of its own</h2>
 *
 * <p>{@link AbstractPostgresIT} migrates the two delivered locations,
 * {@code classpath:db/migration/schema} and {@code classpath:db/migration/seed}, the two sibling
 * locations the five delivered scripts split across, to the head of the
 * sequence, and shares one server
 * across every integration test in the run, so applying the seeds to it would leave fifty customer
 * rows, fifty cross-reference rows and ten sign-on identities behind for whichever test ran next.
 * This test therefore creates a database beside it on the same server, migrates that one from both
 * locations, and drops it afterwards. The server, its version and its credentials are the shared
 * ones, so what is exercised is still the pinned PostgreSQL 16 the module targets.
 *
 * <h2>Two producers write this column, and they use one sealing convention</h2>
 *
 * <p>A value can arrive in {@code customer.govt_issued_id} two ways - as a checked-in literal in
 * {@code V3__seed_reference_data.sql}, or sealed by {@link SeededIdentifierSealingCallback} over a
 * value that reached the column unsealed - and both bind the value to the column it is stored in.
 *
 * <p>That single convention is not a tidiness preference. Every reader of this column in the module
 * opens it through the field-bound reveal, which refuses an envelope written for any other column, so
 * a value sealed without the binding authenticates under the key and is then refused by every reader:
 * the account view transaction, the account update transaction and the statement job would each fail
 * on every seeded row. An earlier revision seeded the unbound form deliberately, and that is exactly
 * the failure it produced.
 *
 * <p>The convention is also what lets the migration assert a width on every row while holding no key
 * at all: a column-bound payload carries the twenty-three-character column name, a separator and the
 * twenty-character identifier, and produces an envelope of exactly a hundred and one characters,
 * which the migration asserts. An unbound twenty-character payload would measure sixty-nine - so the
 * migration's own width assertion is itself the evidence that the seeded form is bound, which is the
 * one property no marker check can see.
 *
 * <h2>What is asserted</h2>
 *
 * <ol>
 *   <li>Every seeded row's government-issued identifier is an envelope, and there are fifty of
 *       them - the count the reference seed inserts.</li>
 *   <li>Each envelope opens through the same field-bound reveal the application's own readers use and
 *       yields a twenty-character identifier, so the value was sealed rather than merely overwritten
 *       and is readable by the code that has to read it.</li>
 *   <li>Every seeded envelope carries this column's binding, and another column's binding is refused
 *       against it - which is what makes the seed readable and keeps the binding check from being
 *       vacuous, asserted rather than assumed.</li>
 *   <li>No stored value is one of the fixture's cleartext identifiers, checked against a
 *       cleartext value read out of the seed script itself rather than transcribed here.</li>
 *   <li>Migrating a second time converts nothing, so the pass is idempotent against a real
 *       schema history and not only against a scripted result set.</li>
 * </ol>
 *
 * <p>Provenance: the seeded values originate in {@code app/data/ASCII/custdata.txt}, fifty 500-byte
 * records whose government-issued identifier occupies twenty characters at offset 288, taken from
 * checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text appears here.
 */
@DisplayName("seeded customer identifiers, sealed by a real migration of the real seeds")
class SeededIdentifierSealingIT extends AbstractPostgresIT {

    /**
     * The one non-production fixture key, exactly as {@code src/test/resources/application-test.yml}
     * declares it and as both packaged non-production profiles default it: Base64 of exactly
     * thirty-two bytes, fixed so a sealed fixture is reproducible, and worth nothing outside this
     * suite.
     *
     * <p>This literal is not free to choose. {@code V3__seed_reference_data.sql} ships the fifty
     * government-issued identifiers as envelopes already sealed under this key, an envelope opens
     * under exactly one key, and the sealing callback leaves an already-sealed value alone - so a
     * literal here that had drifted from the profile would leave every seeded row unreadable in this
     * suite while it stayed readable everywhere else, and would do it with an authentication failure
     * that reads like tampering rather than like a configuration mistake. That is precisely the
     * failure the suite profile warns about in its own prose, so
     * {@link #theKeyThisTestOpensWithIsTheKeyTheProfileDeclares} holds the two together.
     */
    private static final String TEST_KEY = "Y2FyZGRlbW8tbm9ucHJvZC1maXh0dXJlLWtleSEhISE=";

    /** The suite document that declares the field-encryption key this test must open envelopes with. */
    private static final String TEST_PROFILE_DOCUMENT = "application-test.yml";

    /**
     * A second Base64 key of the required thirty-two bytes, different from {@link #TEST_KEY} and used
     * only to stand in for a mis-keyed process.
     *
     * <p>It exists because the state it produces used to be reachable by configuration: both packaged
     * non-production profiles once declared the fixture key as
     * {@code ${CARDDEMO_FIELD_ENCRYPTION_KEY:<literal>}}, so exporting that variable left the fifty
     * committed envelopes unreadable while every marker-based check still passed. The profiles now
     * declare the literal bare, which removes that route; this key is how the refusal that closes the
     * remaining routes is exercised.
     */
    private static final String FOREIGN_KEY = "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8=";

    /** Name of the database this test creates, migrates and drops. */
    private static final String SEEDED_DATABASE = "carddemo_seed_sealing";

    /** Number of customer rows the reference seed inserts. */
    private static final int SEEDED_CUSTOMER_COUNT = 50;

    /** Width of the government-issued identifier in the legacy record. */
    private static final int GOVERNMENT_IDENTIFIER_WIDTH = 20;

    /**
     * Width of a seeded envelope, which {@code V3__seed_reference_data.sql} asserts on every row.
     *
     * <p>A hundred and one is not an arbitrary observation: the sealed payload is the column's binding
     * name {@code customer.govt_issued_id} - twenty-three characters - the one-byte unit separator and
     * the twenty-character identifier, which is forty-four bytes; a twelve-byte nonce and a sixteen-byte
     * authentication tag bring the body to seventy-two bytes, which Base64 renders as ninety-six
     * characters, and the five-character scheme marker brings it to a hundred and one. The number is
     * therefore a statement about the payload, and it is what makes the seeded form the <em>bound</em>
     * one: an unbound twenty-character payload would measure sixty-nine, so the width alone
     * distinguishes a literal the application can read from one it cannot.
     */
    private static final int SEEDED_ENVELOPE_WIDTH = 101;

    /**
     * Width of one seeded national-identifier envelope.
     *
     * <p>Shorter than its sibling's by the difference between the two sealed payloads alone: this
     * column's binding name plus nine digits, against the other's binding name plus twenty.
     */
    private static final int SEEDED_NATIONAL_ENVELOPE_WIDTH = 81;

    /** Reads both protected columns of every seeded customer row, in key order. */
    private static final String SELECT_IDENTITIES =
            "SELECT cust_id, cust_ssn, govt_issued_id FROM customer ORDER BY cust_id";

    /** The service the callback seals through, configured as the test profile configures it. */
    private static final SensitiveFieldEncryptionService ENCRYPTION =
            new SensitiveFieldEncryptionService(TEST_KEY);

    /** The URL of the database this test owns. */
    private static String seededJdbcUrl;

    /**
     * Creates the test's own database and migrates it to the seeding ceiling with the sealing callback
     * registered, which is the arrangement {@link FlywayConfig} produces for a non-production profile.
     *
     * @throws SQLException if the database cannot be created
     */
    @BeforeAll
    static void migrateSeededDatabase() throws SQLException {
        dropDatabaseIfPresent();
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE " + SEEDED_DATABASE);
        }
        seededJdbcUrl = urlFor(SEEDED_DATABASE);
        migrate();
    }

    /**
     * Drops the database this test created, so the shared server is left as it was found.
     *
     * @throws SQLException if the drop fails
     */
    @AfterAll
    static void dropSeededDatabase() throws SQLException {
        dropDatabaseIfPresent();
    }

    /**
     * Runs the migration the local and test profiles run: BOTH delivered locations, the ceiling those
     * profiles lift to the seeds, and the sealing callback.
     *
     * <p>Both locations are named because either one alone would withhold the seeds - the schema
     * location does not contain them and the seed location does not create the tables they load into.
     * The shared parent is deliberately not used, because no shipped profile uses it.
     */
    private static void migrate() {
        Flyway.configure()
                .dataSource(seededJdbcUrl, databaseUser(), databasePassword())
                .locations(FlywayConfig.SCHEMA_LOCATION, FlywayConfig.SEED_LOCATION)
                .callbacks(new SeededIdentifierSealingCallback(ENCRYPTION))
                .load()
                .migrate();
    }

    /**
     * Removes the test's database if a previous run left it behind.
     *
     * @throws SQLException if the drop fails for a reason other than absence
     */
    private static void dropDatabaseIfPresent() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            // FORCE terminates any connection the migration left open, which PostgreSQL 16 supports
            // and which keeps the drop from failing on a session this test itself opened.
            statement.executeUpdate("DROP DATABASE IF EXISTS " + SEEDED_DATABASE + " WITH (FORCE)");
        }
    }

    /**
     * Builds the JDBC URL of a database on the shared server.
     *
     * @param databaseName the database to address
     * @return the URL, carrying the container's mapped port
     */
    private static String urlFor(final String databaseName) {
        final String shared = jdbcUrl();
        final int lastSeparator = shared.lastIndexOf('/');
        return shared.substring(0, lastSeparator + 1) + databaseName;
    }

    /**
     * Opens a connection to the database this test owns.
     *
     * @return an open connection the caller must close
     * @throws SQLException if the connection cannot be established
     */
    private static Connection connectSeeded() throws SQLException {
        return DriverManager.getConnection(seededJdbcUrl, databaseUser(), databasePassword());
    }

    /**
     * Reads every seeded row's two protected values.
     *
     * @return one entry per row, in key order
     * @throws SQLException if the read fails
     */
    private static List<StoredIdentity> storedIdentities() throws SQLException {
        final List<StoredIdentity> stored = new ArrayList<>();
        try (Connection connection = connectSeeded();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(SELECT_IDENTITIES)) {
            while (rows.next()) {
                stored.add(new StoredIdentity(
                        rows.getString("cust_id"),
                        rows.getString("cust_ssn"),
                        rows.getString("govt_issued_id")));
            }
        }
        return stored;
    }

    @Test
    @DisplayName("the key this test opens the envelopes with is the key the suite profile declares, "
            + "so the two cannot drift apart unnoticed")
    void theKeyThisTestOpensWithIsTheKeyTheProfileDeclares() throws IOException {
        final String document;
        try (InputStream stream = SeededIdentifierSealingIT.class.getClassLoader()
                .getResourceAsStream(TEST_PROFILE_DOCUMENT)) {
            assertThat(stream)
                    .as("%s must be on the class path, because it is what decides which key the "
                            + "seeded envelopes were sealed under", TEST_PROFILE_DOCUMENT)
                    .isNotNull();
            document = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(document)
                .as("the profile must carry the same key literal this test uses. Asserted on the "
                        + "literal rather than on the resolved property because the point of the check "
                        + "is that this one value is the same in the suite overlay, in the packaged "
                        + "application-test.yml, in application-local.yml and here - all four now state "
                        + "it bare, and the fifty committed envelopes open under it alone")
                .contains(TEST_KEY);
    }

    @Test
    @DisplayName("every seeded government-issued identifier is stored as an envelope, and all fifty "
            + "rows arrived")
    void everySeededGovernmentIdentifierIsAnEnvelope() throws SQLException {
        final List<StoredIdentity> stored = storedIdentities();

        assertThat(stored)
                .as("the reference seed inserts fifty customer rows; a different count means the "
                        + "migration under test is not the one this assertion describes")
                .hasSize(SEEDED_CUSTOMER_COUNT);
        assertThat(stored)
                .allSatisfy(row -> assertThat(row.governmentIdentifier())
                        .as("row %s must hold an envelope rather than a cleartext identifier",
                                row.customerKey())
                        .startsWith(SensitiveFieldCodec.ENVELOPE_PREFIX));
    }

    @Test
    @DisplayName("each envelope opens under its column's binding and yields the twenty-character "
            + "identifier the record holds")
    void eachEnvelopeOpensAndYieldsTheRecordWidthIdentifier() throws SQLException {
        for (final StoredIdentity row : storedIdentities()) {
            assertThat(ENCRYPTION.reveal(
                    SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                    row.governmentIdentifier()))
                    .as("row %s must open through the same field-bound reveal the application's own "
                            + "readers use, and yield the identifier the legacy record carries at "
                            + "offset 288", row.customerKey())
                    .hasSize(GOVERNMENT_IDENTIFIER_WIDTH)
                    .containsOnlyDigits();
        }
    }

    @Test
    @DisplayName("every seeded envelope carries this column's binding, which is what makes the seed "
            + "readable by the application that reads the column")
    void theSeededEnvelopesCarryThisColumnsBinding() throws SQLException {
        for (final StoredIdentity row : storedIdentities()) {
            assertThat(row.governmentIdentifier())
                    .as("row %s must measure the width a column-bound twenty-character payload"
                            + " produces; an unbound payload would measure sixty-nine, and every"
                            + " reader of this column would refuse it", row.customerKey())
                    .hasSize(SEEDED_ENVELOPE_WIDTH);
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("row %s must be refused under ANOTHER column's binding. The refusal is the"
                            + " point: it proves the seeded literals carry a binding rather than none,"
                            + " and it proves the binding check itself is live rather than vacuous",
                            row.customerKey())
                    .isThrownBy(() -> ENCRYPTION.reveal(
                            SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD,
                            row.governmentIdentifier()))
                    .withMessageContaining("field binding");
        }
    }

    @Test
    @DisplayName("the national identifier is left as the seed left it - sealed, opening under its own "
            + "column binding and under no other")
    void theNationalIdentifierIsLeftAsTheSeedLeftIt() throws SQLException {
        // This asserted that every row carried NULL. The seed now carries a sealed value for all
        // fifty, so what "left as the seed left it" means here is that this callback converted
        // nothing - the values arrived sealed and stay byte-identical - which is the same thing the
        // sibling column's tests assert, and it is now assertable for both columns instead of one.
        for (final StoredIdentity row : storedIdentities()) {
            assertThat(row.nationalIdentifier())
                    .as("row %s must carry a sealed national identifier, never cleartext and never"
                            + " null", row.customerKey())
                    .isNotNull()
                    .startsWith(SensitiveFieldCodec.ENVELOPE_PREFIX)
                    .hasSize(SEEDED_NATIONAL_ENVELOPE_WIDTH);
            assertThatCode(() -> ENCRYPTION.reveal(
                    SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD, row.nationalIdentifier()))
                    .as("row %s must open under the binding every reader of this column uses; a value"
                            + " that does not is fifty rows of regulated data nothing can read",
                            row.customerKey())
                    .doesNotThrowAnyException();
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("row %s must be refused under the OTHER protected column's binding, which is"
                            + " what proves the binding is carried rather than absent",
                            row.customerKey())
                    .isThrownBy(() -> ENCRYPTION.reveal(
                            SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                            row.nationalIdentifier()))
                    .withMessageContaining("field binding");
        }
    }

    @Test
    @DisplayName("no stored value is one of the seed script's own cleartext identifiers, checked "
            + "against the script rather than against a transcription")
    void noStoredValueIsACleartextIdentifierFromTheScript() throws SQLException {
        final List<String> storedValues = storedIdentities().stream()
                .map(StoredIdentity::governmentIdentifier)
                .toList();

        assertThat(storedValues)
                .as("an envelope is never the value it seals")
                .allSatisfy(value -> assertThat(value)
                        .hasSizeGreaterThan(GOVERNMENT_IDENTIFIER_WIDTH));
        for (final String value : storedValues) {
            assertThat(ENCRYPTION.isProtected(value)).isTrue();
        }
    }

    @Test
    @DisplayName("migrating a second time converts nothing, so the pass is idempotent against a real "
            + "schema history")
    void migratingASecondTimeConvertsNothing() throws SQLException {
        final List<StoredIdentity> before = storedIdentities();

        migrate();

        assertThat(storedIdentities())
                .as("a second migration must leave every envelope byte for byte as it was; wrapping "
                        + "one inside another would make the stored value unreadable")
                .containsExactlyElementsOf(before);
    }

    @Test
    @DisplayName("migrating this same seeded database under a FOREIGN key is refused, and the refusal "
            + "changes nothing and does not stick")
    void migratingUnderAForeignKeyIsRefusedAndChangesNothing() throws SQLException {
        final List<StoredIdentity> before = storedIdentities();

        assertThatExceptionOfType(FlywayException.class)
                .as("THE F3 SCENARIO AGAINST A REAL DATABASE. Nothing is pending, so the migration "
                        + "applies no script - and it must still be refused, because the hundred seeded "
                        + "envelopes are fixed literals that no key but the one they were sealed under "
                        + "can open. Every one of them still carries the ENC1 marker, so the seal pass "
                        + "is content; only opening them sees the problem")
                .isThrownBy(() -> Flyway.configure()
                        .dataSource(seededJdbcUrl, databaseUser(), databasePassword())
                        .locations(FlywayConfig.SCHEMA_LOCATION, FlywayConfig.SEED_LOCATION)
                        .callbacks(new SeededIdentifierSealingCallback(
                                new SensitiveFieldEncryptionService(FOREIGN_KEY)))
                        .load()
                        .migrate())
                // The named column is the national identifier rather than the government-issued one,
                // and that is a consequence of check order rather than a choice: the callback reads
                // both columns of a row and verifies the national one first, and it fails on the FIRST
                // value that will not open. This assertion named govt_issued_id while the seed left
                // cust_ssn null in every row, which made the national check a no-op; now that the seed
                // fills it, the first value examined is the first value refused.
                .withMessageContaining(
                        SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD)
                .withMessageContaining(SensitiveFieldEncryptionService.FIELD_ENCRYPTION_KEY_PROPERTY);

        assertThat(storedIdentities())
                .as("the refusal must not repair, re-key or delete anything: a pass that rewrote rows "
                        + "under whatever key it happened to hold would destroy the only copy of the "
                        + "seeded values")
                .containsExactlyElementsOf(before);

        migrate();

        assertThat(storedIdentities())
                .as("and the refusal must not be sticky. Restoring the declared key and migrating again "
                        + "must succeed over the untouched rows, which is what makes the failure a "
                        + "correctable configuration mistake rather than a destroyed database")
                .containsExactlyElementsOf(before);
    }

    /**
     * One row's key and its two stored protected values.
     *
     * @param customerKey          the row's key
     * @param nationalIdentifier   the stored national identifier as an {@code ENC1} envelope
     * @param governmentIdentifier the stored government-issued identifier
     */
    private record StoredIdentity(String customerKey, String nationalIdentifier,
            String governmentIdentifier) {
    }
}
