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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.util.SensitiveFieldCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Raw-database verification that the customer national identifier is stored as ciphertext.
 *
 * <h2>Why this test is an integration test and not a unit test</h2>
 * The unit suite for {@link SensitiveFieldEncryptionService} proves that the service produces an
 * authenticated, field-bound envelope and recovers it again. That is necessary and not sufficient: it
 * asserts against the service's own return value, so it would pass unchanged even if the column that
 * value is destined for could not hold it, or if the schema's stated nullability were wrong, or if
 * some other path wrote cleartext. This test therefore asserts the state of a real PostgreSQL 16
 * database, migrated by the real Flyway migrations, read back through JDBC without going through the
 * service at all.
 *
 * <p>The claim being verified is the one written into
 * {@code src/main/resources/db/migration/V1__create_schema.sql} and into the field documentation of
 * {@code com.carddemo.domain.Customer}: that {@code customer.cust_ssn} holds an application-produced
 * authenticated envelope and never the nine cleartext digits the legacy record carries at offset 279
 * of the 500-byte layout in {@code app/cpy/CVCUS01Y.cpy}. A documented guarantee that no test
 * exercises is indistinguishable from a false one, which is exactly the defect this test closes.
 *
 * <h2>What is asserted</h2>
 * <ul>
 *   <li>The migrated column is {@code character varying(255)} and nullable, and is the
 *       <em>only</em> nullable column in the whole migrated schema - an invariant the migration
 *       states in prose. It is nullable for one reason: the reference seed declines to carry a
 *       national identifier at all, because a static migration cannot produce an envelope without
 *       committing key material and the cleartext is not an acceptable substitute.</li>
 *   <li>{@code customer.govt_issued_id} is regulated by the same service under the same key and is
 *       nevertheless <em>not</em> null, because the seed does carry it - sealed, as fifty
 *       pre-computed envelopes. The two regulated columns are therefore asserted by opposite tests,
 *       and that asymmetry is the design rather than an oversight: it is checked here so that a
 *       later relaxation of either column fails a test instead of passing unnoticed.</li>
 *   <li>A stored value carries the scheme tag, is short enough for the column, and contains neither
 *       the cleartext identifier nor the field name it is bound to.</li>
 *   <li>The cleartext appears nowhere in the stored row image, asserted against the whole row
 *       rendered as text rather than against the one column, so a value leaked into a neighbouring
 *       column would also be caught.</li>
 *   <li>The value read straight out of the database decrypts to exactly what was encrypted.</li>
 *   <li>A null round-trips as a genuine SQL null, never as an empty string or the text
 *       {@code "null"}.</li>
 *   <li>The persistence-boundary guard refuses cleartext, so nothing unencrypted reaches the insert
 *       in the first place.</li>
 * </ul>
 *
 * <h2>Fixtures</h2>
 * No identifier value from {@code app/data/ASCII/custdata.txt} is reproduced here. The identifiers
 * below are invented and share only the shape of the legacy field. The key material is the same
 * throwaway pair the test overlay declares.
 *
 * <h2>Coexistence with the seeded reference data</h2>
 * {@code src/main/resources/db/migration/V3__seed_reference_data.sql} loads the fifty fixture
 * customers under the zero-padded keys {@code 000000001} through {@code 000000050}, and loads fifty
 * {@code card_cross_reference} rows that point at them through {@code fk_card_xref_customer}. This
 * test therefore keys every row it writes under {@link #TEST_KEY_PREFIX}, a range the seed never
 * occupies, and scopes both its cleanup and its own row counting to that range. Nothing here deletes
 * or counts a seeded row, so the seed's referential integrity is never disturbed and no assertion
 * below depends on the customer table being empty.
 */
@DisplayName("customer national identifier, verified against a real database")
class CustomerSsnEncryptionIT extends AbstractPostgresIT {

    /**
     * The one non-production fixture key: Base64 of exactly thirty-two bytes, fixed so a sealed
     * fixture is reproducible, and worth nothing outside this suite. The same literal is declared by
     * {@code src/test/resources/application-test.yml}, by the packaged
     * {@code src/main/resources/application-test.yml} and by {@code application-local.yml}, because
     * {@code V3__seed_reference_data.sql} seeds sealed values that must open under every profile that
     * applies it.
     */
    private static final String TEST_KEY = "Y2FyZGRlbW8tbm9ucHJvZC1maXh0dXJlLWtleSEhISE=";

    /** An invented nine-digit identifier of the same shape as the legacy field. */
    private static final String IDENTIFIER = "400500600";

    /** An invented identifier whose leading zeros must survive storage. */
    private static final String IDENTIFIER_WITH_LEADING_ZEROS = "000000042";

    /**
     * Cleartext for the government-issued identifier, at the twenty characters the legacy record
     * carries at offset 288. Deliberately unrelated to {@link #IDENTIFIER}, so that a leak of either
     * regulated value into any column is attributable to the column it escaped from.
     */
    private static final String GOVT_IDENTIFIER = "70080090010020030040";

    /**
     * Primary-key prefix reserved for the rows this test writes. Every {@code cust_id} used below
     * begins with it, and no seeded row does: {@code V3__seed_reference_data.sql} loads its fifty
     * fixture customers under {@code 000000001} through {@code 000000050}, so the two ranges are
     * disjoint by construction. Cleanup and self-counting are scoped to this prefix rather than to the
     * whole table, which keeps every assertion about this test's own rows exact while leaving the
     * seeded reference data - and the {@code card_cross_reference} rows that depend on it - intact.
     */
    private static final String TEST_KEY_PREFIX = "1000000";

    /** The scheme tag every stored value carries, taken from the codec so the two cannot drift apart. */
    private static final String ENVELOPE_PREFIX = SensitiveFieldCodec.ENVELOPE_PREFIX;

    /**
     * Insert covering all eighteen mapped columns of the migrated customer table, in schema order.
     * Written out in full rather than assembled, so that a column rename in the migration fails this
     * test rather than silently changing what is being verified.
     */
    private static final String INSERT_CUSTOMER = """
            INSERT INTO customer (
                cust_id, first_name, middle_name, last_name,
                addr_line_1, addr_line_2, addr_line_3,
                addr_state_cd, addr_country_cd, addr_zip,
                phone_num_1, phone_num_2,
                cust_ssn, govt_issued_id, cust_dob, eft_account_id,
                pri_card_holder_ind, fico_credit_score
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /** The service under test, configured exactly as the test profile configures it. */
    private SensitiveFieldEncryptionService service;

    @BeforeEach
    void createService() {
        this.service = new SensitiveFieldEncryptionService(TEST_KEY);
    }

    /**
     * Removes only the rows this test wrote, identified by {@link #TEST_KEY_PREFIX}.
     *
     * <p>An unscoped delete would also remove the fifty seeded fixture customers, which the fifty
     * seeded cross-reference rows point at, and would therefore be rejected by
     * {@code fk_card_xref_customer}. Scoping the delete keeps the suite independent of the seed
     * without suppressing, deferring or weakening that constraint.
     *
     * @throws SQLException if the delete fails
     */
    @AfterEach
    void clearCustomers() throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM customer WHERE cust_id LIKE ?")) {
            statement.setString(1, TEST_KEY_PREFIX + "%");
            statement.executeUpdate();
        }
    }

    @Test
    @DisplayName("stores the migrated column as a nullable 255-character string")
    void columnIsWideAndNullable() throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT data_type, character_maximum_length, is_nullable
                       FROM information_schema.columns
                      WHERE table_name = 'customer' AND column_name = 'cust_ssn'
                     """);
             ResultSet resultSet = statement.executeQuery()) {

            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("data_type")).isEqualTo("character varying");
            assertThat(resultSet.getInt("character_maximum_length")).isEqualTo(255);
            assertThat(resultSet.getString("is_nullable")).isEqualTo("YES");
        }
    }

    @Test
    @DisplayName("is the one nullable column in the whole migrated schema, the government-issued"
            + " identifier it is protected alongside being not null")
    void theNationalIdentifierIsTheOnlyNullableColumn() throws SQLException {
        List<String> nullable = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT c.table_name, c.column_name
                       FROM information_schema.columns c
                       JOIN information_schema.tables t
                         ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                      WHERE c.table_schema = 'public'
                        AND t.table_type = 'BASE TABLE'
                        AND c.table_name NOT LIKE 'flyway%'
                        AND c.table_name NOT LIKE 'batch%'
                        AND c.table_name NOT LIKE 'BATCH%'
                        AND c.is_nullable = 'YES'
                      ORDER BY c.table_name, c.column_name
                     """);
             ResultSet resultSet = statement.executeQuery()) {

            while (resultSet.next()) {
                nullable.add(resultSet.getString(1) + "." + resultSet.getString(2));
            }
        }

        assertThat(nullable)
                .as("exactly one column permits a null - the national identifier, which the seed"
                        + " declines to carry at all; any second would be an unreviewed relaxation")
                .containsExactly("customer.cust_ssn");
        assertThat(nullabilityOf("govt_issued_id"))
                .as("the government-issued identifier is protected by the same service under the"
                        + " same key and is nevertheless not null, because the seed does carry it,"
                        + " sealed; asserting the asymmetry positively is what stops the column"
                        + " being quietly relaxed to match its neighbour")
                .isEqualTo("NO");
        assertThat(nullabilityOf("cust_ssn"))
                .as("and the national identifier is nullable, read from the same catalogue by the"
                        + " same query, so the two readings cannot disagree about their source")
                .isEqualTo("YES");
    }

    /**
     * Returns the catalogue's own nullability verdict for one column of the customer table.
     *
     * <p>Read as a scalar rather than inferred from the census above, so that the asymmetry between
     * the two regulated columns is stated by two independent readings of the same catalogue.
     *
     * @param columnName the column to read
     * @return {@code "YES"} or {@code "NO"}, exactly as {@code information_schema} reports it
     * @throws SQLException if the read fails
     */
    private static String nullabilityOf(final String columnName) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT c.is_nullable
                       FROM information_schema.columns c
                      WHERE c.table_schema = 'public'
                        AND c.table_name = 'customer'
                        AND c.column_name = ?
                     """)) {
            statement.setString(1, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next())
                        .as("the column %s must exist in the migrated schema", columnName)
                        .isTrue();
                return resultSet.getString(1);
            }
        }
    }

    @Test
    @DisplayName("holds a scheme-tagged envelope and never the cleartext identifier")
    void storesCiphertextRatherThanCleartext() throws SQLException {
        insertCustomer("100000001", service.requireProtectedOrNull(
                SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD,
                service.protect(SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD, IDENTIFIER)));

        String stored = readSsn("100000001");

        assertThat(stored)
                .isNotNull()
                .startsWith(ENVELOPE_PREFIX)
                .doesNotContain(IDENTIFIER)
                .doesNotContain(SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD)
                .hasSizeLessThanOrEqualTo(255);
    }

    @Test
    @DisplayName("leaks the cleartext into no column of the stored row")
    void leaksCleartextNowhereInTheRow() throws SQLException {
        insertCustomer("100000002",
                service.protect(SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD, IDENTIFIER));

        String rowImage;
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT customer::text FROM customer WHERE cust_id = ?")) {
            statement.setString(1, "100000002");
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                rowImage = resultSet.getString(1);
            }
        }

        assertThat(rowImage)
                .contains(ENVELOPE_PREFIX)
                .as("neither regulated cleartext may appear anywhere in the row, and the row now"
                        + " carries two sealed columns rather than one, so a value written into the"
                        + " wrong one of them is caught here too")
                .doesNotContain(IDENTIFIER)
                .doesNotContain(GOVT_IDENTIFIER);
    }

    @Test
    @DisplayName("decrypts the value read straight out of the database")
    void decryptsWhatTheDatabaseReturns() throws SQLException {
        insertCustomer("100000003", service.protect(
                SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD,
                IDENTIFIER_WITH_LEADING_ZEROS));

        String stored = readSsn("100000003");

        assertThat(service.reveal(SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD, stored))
                .isEqualTo(IDENTIFIER_WITH_LEADING_ZEROS);
    }

    @Test
    @DisplayName("refuses a value written for another field, even after a round trip through storage")
    void refusesAValueStoredUnderAnotherFieldBinding() throws SQLException {
        insertCustomer("100000004", service.protect("customer.other_column", IDENTIFIER));

        String stored = readSsn("100000004");

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> service.reveal(
                        SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD, stored));
    }

    @Test
    @DisplayName("round-trips a genuine SQL null, never an empty string or the text null")
    void roundTripsSqlNull() throws SQLException {
        insertCustomer("100000005", service.requireProtectedOrNull(
                SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD, null));

        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT cust_ssn, cust_ssn IS NULL FROM customer WHERE cust_id = ?")) {
            statement.setString(1, "100000005");
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isNull();
                assertThat(resultSet.getBoolean(2)).isTrue();
            }
        }
    }

    @Test
    @DisplayName("stops cleartext at the persistence boundary, so no row is written at all")
    void guardStopsCleartextBeforeItReachesTheDatabase() throws SQLException {
        final int rowsBefore = countCustomers();

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> insertCustomer("100000006", service.requireProtectedOrNull(
                        SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD, IDENTIFIER)))
                .withMessageNotContaining(IDENTIFIER);

        // Nothing was written under any key this test uses, and the table as a whole is unchanged.
        // The pair is strictly stronger than a bare emptiness check: it would also catch a row
        // written under some other key, which an assertion about this test's own range could miss.
        assertThat(countTestCustomers()).isZero();
        assertThat(countCustomers()).isEqualTo(rowsBefore);
    }

    /**
     * Inserts one customer row, supplying every mapped column at a width the schema accepts.
     *
     * @param custId  the primary key
     * @param custSsn the value to place in the national-identifier column, which may be {@code null};
     *                the government-issued identifier is always sealed, because its column is not
     *                null
     * @throws SQLException if the insert fails
     */
    private static void insertCustomer(final String custId, final String custSsn)
            throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(INSERT_CUSTOMER)) {
            statement.setString(1, custId);
            statement.setString(2, "GIVEN");
            statement.setString(3, "MIDDLE");
            statement.setString(4, "FAMILY");
            statement.setString(5, "ADDRESS LINE ONE");
            statement.setString(6, "ADDRESS LINE TWO");
            statement.setString(7, "ADDRESS LINE THREE");
            statement.setString(8, "NY");
            statement.setString(9, "USA");
            statement.setString(10, "10001");
            statement.setString(11, "2125550100");
            statement.setString(12, "2125550101");
            statement.setString(13, custSsn);
            // The government-issued identifier is regulated in exactly the same way, but its column
            // is not null: the reference seed carries a sealed envelope in every one of its fifty
            // rows, so absence is not an outcome a row is permitted to state. A fixed literal here
            // would be cleartext regulated data checked into version control, so the value is sealed
            // at insert time under its own field binding instead - which the static seed could not
            // do without committing key material and this test can, because the service is on the
            // classpath. Sealing is randomised, so each row receives a distinct envelope, exactly as
            // the seeded rows do.
            statement.setString(14, new SensitiveFieldEncryptionService(TEST_KEY).protect(
                    SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                    GOVT_IDENTIFIER));
            statement.setString(15, "1980-01-01");
            statement.setString(16, "EFT0000001");
            statement.setString(17, "Y");
            statement.setString(18, "001");
            statement.executeUpdate();
        }
    }

    /**
     * Reads the protected column straight out of the database, without involving the service.
     *
     * @param custId the primary key of the row to read
     * @return the stored value, which may be {@code null}
     * @throws SQLException if the read fails
     */
    private static String readSsn(final String custId) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT cust_ssn FROM customer WHERE cust_id = ?")) {
            statement.setString(1, custId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString(1);
            }
        }
    }

    /**
     * Counts every row currently present in the customer table, seeded rows included.
     *
     * @return the row count
     * @throws SQLException if the count fails
     */
    private static int countCustomers() throws SQLException {
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT count(*) FROM customer")) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    /**
     * Counts only the rows this test writes, identified by {@link #TEST_KEY_PREFIX}.
     *
     * @return the number of rows present under this test's reserved key range
     * @throws SQLException if the count fails
     */
    private static int countTestCustomers() throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM customer WHERE cust_id LIKE ?")) {
            statement.setString(1, TEST_KEY_PREFIX + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getInt(1);
            }
        }
    }
}
