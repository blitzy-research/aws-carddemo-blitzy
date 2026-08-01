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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies the credential column against a real database rather than against a mock.
 *
 * <h2>Why this test exists</h2>
 * The migrated schema states that {@code user_security.sec_usr_pwd} holds a BCrypt digest and never a
 * cleartext credential, and the entity documentation states the same. Neither statement is
 * self-verifying: a {@code VARCHAR(60)} column accepts any string of sixty characters or fewer, and
 * the entity's constructor and setter are plain assignments by contract. The guarantee therefore
 * rests entirely on {@link CredentialDigestService}, and the only way to show it holds is to write
 * through a real PostgreSQL server, migrated by the real Flyway migrations, and read the column back
 * through plain JDBC without the service in the path.
 *
 * <h2>What is asserted</h2>
 * <ol>
 *   <li>The column really is sixty characters wide and not nullable, and it is the only column in the
 *       table whose width diverges from its legacy picture width - eight, twenty, twenty, and one for
 *       the other four.</li>
 *   <li>A digest survives storage intact at the full sixty characters, and still verifies after the
 *       round trip. A column that had been left at the legacy width of eight would truncate it and
 *       every stored credential would become unverifiable.</li>
 *   <li>No fragment of the credential appears anywhere in the stored row.</li>
 *   <li>The database on its own would accept a cleartext value - which is exactly why the guard
 *       exists - and a cleartext value that reached the column that way authenticates nobody, because
 *       verification declines a stored value that is not digest-shaped.</li>
 *   <li>The guard refuses a cleartext value before any statement is issued, leaving no row behind.</li>
 *   <li>An unrecognised role code stores successfully, preserving the tolerance the legacy sign-on
 *       has through its unconditional alternative branch.</li>
 * </ol>
 *
 * <h2>Test data discipline</h2>
 * The credential used here is an obviously synthetic phrase. The eight-character literal carried
 * in-stream by {@code app/jcl/DUSRSECJ.jcl} appears nowhere in this file. The identifiers, names and
 * role codes are the non-secret seed values from that member.
 */
@DisplayName("sign-on credential column, verified against a real database")
class UserSecurityCredentialIT extends AbstractPostgresIT {

    /** A synthetic credential, unlike anything the legacy seed carries. */
    private static final String CREDENTIAL = "synthetic-credential-for-integration-tests";

    /** A non-secret seed identifier from the in-stream card images. */
    private static final String ADMIN_ID = "ADMIN001";

    /**
     * Insert covering all five mapped columns of the migrated table, in schema order. Written out in
     * full rather than assembled, so a column rename in the migration fails this test rather than
     * silently changing what is being verified.
     */
    private static final String INSERT_USER = """
            INSERT INTO user_security (
                sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type
            ) VALUES (?, ?, ?, ?, ?)
            """;

    /** The service under test. It needs no configuration: a digest carries its own salt and cost. */
    private CredentialDigestService service;

    @BeforeEach
    void createService() {
        this.service = new CredentialDigestService();
    }

    @AfterEach
    void clearUsers() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM user_security");
        }
    }

    @Test
    @DisplayName("declares the credential column at the digest width and not nullable")
    void credentialColumnIsSixtyWideAndNotNullable() throws SQLException {
        assertThat(describeColumns()).containsExactly(
                "sec_usr_id:character varying:8:NO",
                "sec_usr_fname:character varying:20:NO",
                "sec_usr_lname:character varying:20:NO",
                "sec_usr_pwd:character varying:60:NO",
                "sec_usr_type:character varying:1:NO");
    }

    @Test
    @DisplayName("holds no rows before a profile-scoped seed adds any")
    void tableStartsEmpty() throws SQLException {
        assertThat(countUsers()).isZero();
    }

    @Test
    @DisplayName("stores all sixty characters of a digest, so it still verifies after the round trip")
    void storesTheFullDigest() throws SQLException {
        final String digest = service.encode(CREDENTIAL);
        insertUser(ADMIN_ID,
                service.requireDigest(CredentialDigestService.USER_SECURITY_PWD_FIELD, digest),
                "A");

        final String stored = readCredential(ADMIN_ID);
        assertThat(stored).hasSize(CredentialDigestService.DIGEST_LENGTH);
        assertThat(stored).isEqualTo(digest);
        assertThat(service.isDigest(stored)).isTrue();
        assertThat(service.matches(CREDENTIAL, stored)).isTrue();
    }

    @Test
    @DisplayName("leaks no fragment of the credential anywhere in the stored row")
    void leaksNoFragmentOfTheCredential() throws SQLException {
        insertUser(ADMIN_ID, service.encode(CREDENTIAL), "A");

        final String row = readWholeRow(ADMIN_ID);
        for (int length = 4; length <= CREDENTIAL.length(); length++) {
            assertThat(row).doesNotContain(CREDENTIAL.substring(0, length));
        }
    }

    @Test
    @DisplayName("would accept a cleartext value on its own, which is why the guard exists")
    void theColumnAloneIsNotTheProtection() throws SQLException {
        insertUser(ADMIN_ID, CREDENTIAL, "A");

        assertThat(readCredential(ADMIN_ID)).isEqualTo(CREDENTIAL);
        assertThat(service.isDigest(readCredential(ADMIN_ID))).isFalse();
    }

    @Test
    @DisplayName("authenticates nobody against a cleartext value that reached the column")
    void aCleartextValueAuthenticatesNobody() throws SQLException {
        insertUser(ADMIN_ID, CREDENTIAL, "A");

        assertThat(service.matches(CREDENTIAL, readCredential(ADMIN_ID))).isFalse();
    }

    @Test
    @DisplayName("refuses a cleartext value before any statement is issued, leaving no row behind")
    void guardStopsCleartextBeforeItReachesTheDatabase() throws SQLException {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> insertUser(
                        ADMIN_ID,
                        service.requireDigest(
                                CredentialDigestService.USER_SECURITY_PWD_FIELD, CREDENTIAL),
                        "A"))
                .withMessageNotContaining(CREDENTIAL);

        assertThat(countUsers()).isZero();
    }

    @Test
    @DisplayName("rejects a value wider than the digest column")
    void rejectsAValueWiderThanTheColumn() {
        final String tooWide = service.encode(CREDENTIAL) + "a";
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insertUser(ADMIN_ID, tooWide, "A"));
    }

    @Test
    @DisplayName("rejects an absent credential, because the column is not nullable")
    void rejectsAnAbsentCredential() {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insertUser(ADMIN_ID, null, "A"));
    }

    @Test
    @DisplayName("round-trips both declared role codes as single characters")
    void roundTripsBothDeclaredRoleCodes() throws SQLException {
        insertUser(ADMIN_ID, service.encode(CREDENTIAL), "A");
        insertUser("USER0001", service.encode(CREDENTIAL), "U");

        assertThat(readRoleCode(ADMIN_ID)).isEqualTo("A");
        assertThat(readRoleCode("USER0001")).isEqualTo("U");
    }

    @Test
    @DisplayName("stores a role code outside the declared pair, preserving the legacy tolerance")
    void storesAnUnrecognisedRoleCode() throws SQLException {
        insertUser(ADMIN_ID, service.encode(CREDENTIAL), "X");

        assertThat(readRoleCode(ADMIN_ID)).isEqualTo("X");
    }

    /**
     * Inserts one sign-on identity, supplying every mapped column.
     *
     * @param secUsrId   the primary key
     * @param secUsrPwd  the value to place in the credential column
     * @param secUsrType the one-character role code
     * @throws SQLException if the insert fails, including when a constraint refuses the value
     */
    private static void insertUser(final String secUsrId,
                                   final String secUsrPwd,
                                   final String secUsrType) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(INSERT_USER)) {
            statement.setString(1, secUsrId);
            statement.setString(2, "MARGARET");
            statement.setString(3, "GOLD");
            statement.setString(4, secUsrPwd);
            statement.setString(5, secUsrType);
            statement.executeUpdate();
        }
    }

    /**
     * Reads the credential column straight out of the database, without involving the service.
     *
     * @param secUsrId the primary key of the row to read
     * @return the stored value
     * @throws SQLException if the read fails
     */
    private static String readCredential(final String secUsrId) throws SQLException {
        return readSingleValue(
                "SELECT sec_usr_pwd FROM user_security WHERE sec_usr_id = ?", secUsrId);
    }

    /**
     * Reads the role code straight out of the database.
     *
     * @param secUsrId the primary key of the row to read
     * @return the stored role code
     * @throws SQLException if the read fails
     */
    private static String readRoleCode(final String secUsrId) throws SQLException {
        return readSingleValue(
                "SELECT sec_usr_type FROM user_security WHERE sec_usr_id = ?", secUsrId);
    }

    /**
     * Reads a single value for one row. Every caller supplies a complete literal statement with a
     * bound parameter, so no value and no identifier is ever concatenated into SQL.
     *
     * @param sql      a single-column select over the table, parameterised on the primary key
     * @param secUsrId the primary key of the row to read
     * @return the stored value
     * @throws SQLException if the read fails
     */
    private static String readSingleValue(final String sql, final String secUsrId)
            throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, secUsrId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString(1);
            }
        }
    }

    /**
     * Renders the whole row as text so an assertion can search every column at once for a leak.
     *
     * @param secUsrId the primary key of the row to read
     * @return the row as PostgreSQL renders a composite value
     * @throws SQLException if the read fails
     */
    private static String readWholeRow(final String secUsrId) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT user_security::text FROM user_security WHERE sec_usr_id = ?")) {
            statement.setString(1, secUsrId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString(1);
            }
        }
    }

    /**
     * Describes every column of the table in ordinal order as
     * {@code name:type:maximum-length:nullability}.
     *
     * @return one descriptor per column
     * @throws SQLException if the catalogue read fails
     */
    private static List<String> describeColumns() throws SQLException {
        final List<String> descriptors = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT column_name, data_type, character_maximum_length, is_nullable
                       FROM information_schema.columns
                      WHERE table_name = 'user_security'
                      ORDER BY ordinal_position
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                descriptors.add(resultSet.getString(1) + ':' + resultSet.getString(2) + ':'
                        + resultSet.getInt(3) + ':' + resultSet.getString(4));
            }
        }
        return descriptors;
    }

    /**
     * Counts the rows currently present in the table.
     *
     * @return the row count
     * @throws SQLException if the count fails
     */
    private static int countUsers() throws SQLException {
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT count(*) FROM user_security")) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }
}
