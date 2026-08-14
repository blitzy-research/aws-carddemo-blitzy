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

import com.carddemo.support.SensitiveValues;
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
 * cleartext credential, and the entity documentation states the same. The guarantee now rests on three
 * independent layers, and the only way to show they hold is to write through a real PostgreSQL server,
 * migrated by the real Flyway migrations, and read the column back through plain JDBC without the service
 * in the path.
 *
 * <p>The three layers are {@link CredentialDigestService}, which refuses to hand a non-digest to a writer;
 * the entity's own credential check, which refuses one on assignment; and - since {@code V2_2} -
 * {@code ck_user_security_sec_usr_pwd_digest}, which refuses one at the column. The third is the one this
 * file's assertions changed for: a {@code VARCHAR(60)} column USED TO accept any string of sixty characters
 * or fewer, and this test used to prove that it did. It no longer does, and the assertion that used to
 * institutionalise the gap now proves the refusal instead. See {@code docs/decision-log.md} DL-349.
 *
 * <h2>What is asserted</h2>
 * <ol>
 *   <li>The column really is sixty characters wide and not nullable, and it is the only column in the
 *       table whose width diverges from its legacy picture width - eight, twenty, twenty, and one for
 *       the other four.</li>
 *   <li>The table arrives carrying the ten seeded sign-on identities, five administrative and five
 *       standard, and every one of their credentials is a distinct sixty-character digest that
 *       verification recognises as digest-shaped. This is the assertion that shows the credential
 *       seed is genuinely reachable from the single migration location, rather than sitting in a
 *       folder the migrator never looks at.</li>
 *   <li>A digest survives storage intact at the full sixty characters, and still verifies after the
 *       round trip. A column that had been left at the legacy width of eight would truncate it and
 *       every stored credential would become unverifiable.</li>
 *   <li>No fragment of the credential appears anywhere in the stored row.</li>
 *   <li>The database refuses a cleartext value on its own, naming its own constraint - so the guard is
 *       no longer the only thing between cleartext and storage - and a cleartext value that reached the
 *       column by any other route would still authenticate nobody, because verification declines a
 *       stored value that is not digest-shaped.</li>
 *   <li>The guard refuses a cleartext value before any statement is issued, leaving no row behind.</li>
 *   <li>An unrecognised role code stores successfully, preserving the tolerance the legacy sign-on
 *       has through its unconditional alternative branch.</li>
 * </ol>
 *
 * <h2>Test data discipline, and coexistence with the seeded identities</h2>
 * The credential used here is an obviously synthetic phrase. The eight-character literal carried
 * in-stream by {@code app/jcl/DUSRSECJ.jcl} appears nowhere in this file.
 *
 * <p>{@code src/main/resources/db/migration/seed/V4__seed_user_security.sql} applies under this profile, so
 * the table already holds the ten legacy sign-on identities when a test method begins. Every row this
 * test writes is therefore keyed inside a reserved range the seed never occupies, and the cleanup and
 * the emptiness assertions are both scoped to that range: nothing here deletes or counts a seeded row.
 * An unscoped delete or an assertion that the table starts empty would have made this test depend on
 * the seeds being absent, which they no longer are under this profile: the test overlay resolves the
 * seed location that production is refused, and lifts the version ceiling behind it.
 *
 * <p>One assertion deliberately looks at the seeded rows rather than around them: it reads all ten
 * credentials through plain JDBC and requires each to be a digest of the declared width, with no two
 * alike. That is the check that the seeding profile genuinely received V4 and that the migration wrote
 * digests rather than the cleartext the legacy record carried.
 */
@DisplayName("sign-on credential column, verified against a real database")
class UserSecurityCredentialIT extends AbstractPostgresIT {

    /** A synthetic credential, unlike anything the legacy seed carries. */
    private static final String CREDENTIAL = "synthetic-credential-for-integration-tests";

    /**
     * An eight-character non-secret literal, being the width the legacy record reserves for a cleartext
     * credential, used only to make the column refuse one.
     *
     * <p>Deliberately NOT {@link #CREDENTIAL}. A PostgreSQL CHECK violation echoes the failing row in its
     * DETAIL, and the driver folds that into the exception message, so a refusal probed with the credential
     * would put the credential into an assertion diagnostic - the exact disclosure the rest of this file
     * takes trouble to avoid. This literal verifies nothing and opens nothing, so echoing it costs nothing.
     */
    private static final String LEGACY_WIDTH_CLEARTEXT = "NOTREAL1";

    /** The constraint that refuses a non-digest at the column, added by V2_2. */
    private static final String DIGEST_CONSTRAINT = "ck_user_security_sec_usr_pwd_digest";

    /** The PostgreSQL SQLSTATE for a violated CHECK constraint. */
    private static final String SQLSTATE_CHECK_VIOLATION = "23514";

    /**
     * Prefix of the identifier range this test reserves for itself.
     *
     * <p>V4__seed_user_security.sql now applies under this profile, so the table is NOT empty when a
     * test method starts: it holds the ten legacy sign-on identities. Every row this test writes is
     * therefore keyed inside a range the seed never occupies, and both the cleanup and the emptiness
     * assertions are scoped to that range. Nothing here deletes or counts a seeded row.
     */
    private static final String TEST_ID_PREFIX = "ITUSER";

    /** The primary identifier this test writes under, inside the reserved range. */
    private static final String TEST_ID = TEST_ID_PREFIX + "01";

    /** A second reserved identifier, so both role codes can be observed on distinct rows. */
    private static final String SECOND_TEST_ID = TEST_ID_PREFIX + "02";

    /** Sign-on identities the seed delivers: five administrative and five standard. */
    private static final int SEEDED_IDENTITY_COUNT = 10;

    /** How many of the seeded identities carry the administrative role code. */
    private static final int SEEDED_ADMINISTRATOR_COUNT = 5;

    /**
     * Every sign-on identity the seed delivers, written out in full as identifier and role code.
     *
     * <p>Held as literals rather than derived from the migration, so this list and the migration are
     * two independent statements of the same fact. A count alone cannot catch an identifier being
     * renamed or a role code being flipped while the total stays at ten, and those are precisely the
     * changes that would silently move which side of the role split a given operator lands on.
     */
    private static final List<String> SEEDED_IDENTITIES = List.of(
            "ADMIN001:A", "ADMIN002:A", "ADMIN003:A", "ADMIN004:A", "ADMIN005:A",
            "USER0001:U", "USER0002:U", "USER0003:U", "USER0004:U", "USER0005:U");

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

    /**
     * Removes only the rows this test wrote. The delete is scoped to the reserved identifier range so
     * the ten seeded identities survive every method, which matters because the seed is part of the
     * migrated state the rest of this suite asserts against.
     */
    @AfterEach
    void clearTestUsers() throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM user_security WHERE sec_usr_id LIKE ?")) {
            statement.setString(1, TEST_ID_PREFIX + "%");
            statement.executeUpdate();
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
    @DisplayName("holds no row in the reserved identifier range before this test writes one")
    void theReservedRangeStartsEmpty() throws SQLException {
        assertThat(countTestUsers())
                .as("the seeded identities are expected and are counted separately; what must be empty "
                        + "is the range this test owns, or a leftover row would satisfy an assertion "
                        + "this test believes it proved")
                .isZero();
    }

    @Test
    @DisplayName("carries the ten seeded identities, every credential stored as a digest the "
            + "application's own guard recognises")
    void theSeedDeliversTenIdentitiesAllStoredAsDigests() throws SQLException {
        assertThat(countUsers())
                .as("the seeds are held back from production by the seed location it is refused and by "
                        + "the version ceiling behind that, so a profile resolving the location and "
                        + "lifting the ceiling must actually receive them: a count of zero means one of "
                        + "the two was left at the production value, and a count above %d means a second "
                        + "seed exists", SEEDED_IDENTITY_COUNT)
                .isEqualTo(SEEDED_IDENTITY_COUNT);

        assertThat(countAdministrators())
                .as("five of the ten carry the administrative role code, which is what makes both "
                        + "sides of the role split testable at all")
                .isEqualTo(SEEDED_ADMINISTRATOR_COUNT);

        assertThat(readSeededIdentities())
                .as("the seed must deliver exactly these identities with exactly these role codes; "
                        + "the counts above would still pass if an identifier were renamed or a role "
                        + "code flipped while the totals held, and either change would move an "
                        + "operator to the other side of the role split. Expected: %s",
                        describeSeededIdentities())
                .containsExactlyElementsOf(SEEDED_IDENTITIES);

        final List<String> credentials = readAllCredentials();
        for (final String credential : credentials) {
            // The LENGTH is asserted, not the digest. hasSize prints its whole subject on failure, and
            // a BCrypt digest published in a build log is an offline-attack target: the salt and the
            // cost are in it, so an attacker needs nothing else to start guessing.
            assertThat(credential.length())
                    .as("a seeded credential must be a digest of the declared width; the legacy record "
                            + "carried an eight-character cleartext password, and reproducing that "
                            + "would have satisfied parity and violated the credential constraint. "
                            + "Stored %s", SensitiveValues.describe(credential))
                    .isEqualTo(CredentialDigestService.DIGEST_LENGTH);
            assertThat(service.isDigest(credential))
                    .as("and it must be recognisable as a digest; asserted as a predicate so a failure "
                            + "reports false rather than the value that was not one")
                    .isTrue();
        }

        assertThat(SensitiveValues.fingerprints(credentials))
                .as("independent salts, so no two seeded identities share a stored value even where "
                        + "the source credential was identical")
                .hasSize(credentials.size())
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("stores all sixty characters of a digest, so it still verifies after the round trip")
    void storesTheFullDigest() throws SQLException {
        final String digest = service.encode(CREDENTIAL);
        insertUser(TEST_ID,
                service.requireDigest(CredentialDigestService.USER_SECURITY_PWD_FIELD, digest),
                "A");

        final String stored = readCredential(TEST_ID);
        assertThat(stored.length())
                .as("the column must keep every character; a truncating column would store a prefix that "
                        + "still looks like a digest. Stored %s", SensitiveValues.describe(stored))
                .isEqualTo(CredentialDigestService.DIGEST_LENGTH);
        assertThat(SensitiveValues.fingerprint(stored))
                .as("and it must be the digest that went in, compared by fingerprint because an "
                        + "isEqualTo failure would print both digests")
                .isEqualTo(SensitiveValues.fingerprint(digest));
        assertThat(service.isDigest(stored)).as("and it is still recognisable as a digest").isTrue();
        assertThat(service.matches(CREDENTIAL, stored))
                .as("and it still verifies the credential it was made from, which is the property the "
                        + "round trip exists to prove")
                .isTrue();
    }

    @Test
    @DisplayName("leaks no fragment of the credential anywhere in the stored row")
    void leaksNoFragmentOfTheCredential() throws SQLException {
        insertUser(TEST_ID, service.encode(CREDENTIAL), "A");

        final String row = readWholeRow(TEST_ID);
        for (int length = 4; length <= CREDENTIAL.length(); length++) {
            final String fragment = CREDENTIAL.substring(0, length);
            // A predicate, because a failing doesNotContain prints the fragment AND the row that
            // carries it - so the one assertion guarding against a credential fragment reaching
            // storage would have published the fragment itself, and progressively longer ones as the
            // loop went on.
            assertThat(SensitiveValues.absentFrom(row, fragment))
                    .as("no prefix of the credential may appear anywhere in the stored row; looked for a "
                            + "%d-character prefix, %s", length, SensitiveValues.describe(fragment))
                    .isTrue();
        }
    }

    @Test
    @DisplayName("refuses a cleartext value at the column itself, naming its own constraint, so the guard "
            + "is no longer the only thing between cleartext and storage")
    void theColumnItselfRefusesCleartext() throws SQLException {
        assertThatExceptionOfType(SQLException.class)
                .as("the column used to accept this row, and this assertion used to prove that it did. "
                        + "V2_2 requires a structural BCrypt digest, so a cleartext value is refused by "
                        + "the server rather than stored and later found unverifiable")
                .isThrownBy(() -> insertUser(TEST_ID, LEGACY_WIDTH_CLEARTEXT, "A"))
                .satisfies(refusal -> {
                    assertThat(refusal.getSQLState())
                            .as("a violated CHECK reports SQLSTATE %s; another state would mean something "
                                    + "else refused the row and the constraint under test was never "
                                    + "reached", SQLSTATE_CHECK_VIOLATION)
                            .isEqualTo(SQLSTATE_CHECK_VIOLATION);
                    assertThat(refusal.getMessage())
                            .as("and it must name %s, because asserting only that something was thrown "
                                    + "would pass with the constraint deleted", DIGEST_CONSTRAINT)
                            .contains(DIGEST_CONSTRAINT);
                });

        assertThat(countTestUsers())
                .as("and nothing is left behind: the row was refused rather than written and rolled back "
                        + "by a later cleanup. The seeded rows are irrelevant here and are deliberately "
                        + "not counted")
                .isZero();
    }

    @Test
    @DisplayName("authenticates nobody against a cleartext value, which is the layer that would still "
            + "hold if the column's own refusal were dropped")
    void aCleartextValueAuthenticatesNobody() {
        // Asserted against the service rather than against a stored row, because the column no longer
        // accepts the row this test used to write: the write is refused above by DIGEST_CONSTRAINT. The
        // property being asserted is unchanged and is the reason the three layers are independent - a
        // cleartext value reaching the column by ANY route, including a restored backup taken before V2_2,
        // still authenticates nobody.
        assertThat(service.matches(CREDENTIAL, CREDENTIAL))
                .as("verification declines a stored value that is not digest-shaped, whatever put it "
                        + "there")
                .isFalse();
        assertThat(service.isDigest(CREDENTIAL))
                .as("and the value is not digest-shaped, which is why it declines")
                .isFalse();
    }

    @Test
    @DisplayName("refuses a cleartext value before any statement is issued, leaving no row behind")
    void guardStopsCleartextBeforeItReachesTheDatabase() throws SQLException {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> insertUser(
                        TEST_ID,
                        service.requireDigest(
                                CredentialDigestService.USER_SECURITY_PWD_FIELD, CREDENTIAL),
                        "A"))
                .withMessageNotContaining(CREDENTIAL);

        assertThat(countTestUsers())
                .as("the guard must refuse before a statement is issued, so the reserved range stays "
                        + "empty; the seeded rows are irrelevant here and are deliberately not counted")
                .isZero();
    }

    /**
     * The identifier must be this test's own. Under a seeded identifier the insert would fail on the
     * primary key before the column width was ever reached, and this test would report success while
     * verifying nothing about the width it exists to assert.
     */
    @Test
    @DisplayName("rejects a value wider than the digest column")
    void rejectsAValueWiderThanTheColumn() {
        final String tooWide = service.encode(CREDENTIAL) + "a";
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insertUser(TEST_ID, tooWide, "A"))
                .withMessageContaining("value too long");
    }

    /**
     * As above, the identifier must be this test's own, so the refusal observed can only be the
     * not-null constraint and not a duplicate key.
     */
    @Test
    @DisplayName("rejects an absent credential, because the column is not nullable")
    void rejectsAnAbsentCredential() {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insertUser(TEST_ID, null, "A"))
                .withMessageContaining("sec_usr_pwd");
    }

    @Test
    @DisplayName("round-trips both declared role codes as single characters")
    void roundTripsBothDeclaredRoleCodes() throws SQLException {
        insertUser(TEST_ID, service.encode(CREDENTIAL), "A");
        insertUser(SECOND_TEST_ID, service.encode(CREDENTIAL), "U");

        assertThat(readRoleCode(TEST_ID)).isEqualTo("A");
        assertThat(readRoleCode(SECOND_TEST_ID)).isEqualTo("U");
    }

    @Test
    @DisplayName("stores a role code outside the declared pair, preserving the legacy tolerance")
    void storesAnUnrecognisedRoleCode() throws SQLException {
        insertUser(TEST_ID, service.encode(CREDENTIAL), "X");

        assertThat(readRoleCode(TEST_ID)).isEqualTo("X");
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
     * Counts every row currently present in the table, seeded and test-written alike.
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

    /**
     * Counts only the rows inside the identifier range this test reserves, ignoring the seeded ten.
     *
     * @return the number of rows this test currently owns
     * @throws SQLException if the count fails
     */
    private static int countTestUsers() throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM user_security WHERE sec_usr_id LIKE ?")) {
            statement.setString(1, TEST_ID_PREFIX + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getInt(1);
            }
        }
    }

    /**
     * Counts the seeded identities carrying the administrative role code.
     *
     * @return the administrative row count
     * @throws SQLException if the count fails
     */
    private static int countAdministrators() throws SQLException {
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT count(*) FROM user_security WHERE sec_usr_type = 'A'")) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    /**
     * Reads every stored credential, in identifier order, straight out of the database.
     *
     * <p>Used to inspect the seeded rows without the entity or the mapper in the path, so what is
     * asserted is the value the migration actually wrote rather than a value some Java code produced.
     *
     * @return the stored credential of every row in the table
     * @throws SQLException if the read fails
     */
    private static List<String> readAllCredentials() throws SQLException {
        final List<String> credentials = new ArrayList<>();
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT sec_usr_pwd FROM user_security ORDER BY sec_usr_id")) {
            while (resultSet.next()) {
                credentials.add(resultSet.getString(1));
            }
        }
        return credentials;
    }

    /**
     * Reads every identity in the table as {@code identifier:roleCode}, in identifier order.
     *
     * <p>Read straight out of the database with no entity and no mapper in the path, so what is
     * compared against {@link #SEEDED_IDENTITIES} is the pair the migration actually wrote.
     *
     * @return every stored identity, rendered as identifier and role code
     * @throws SQLException if the read fails
     */
    private static List<String> readSeededIdentities() throws SQLException {
        final List<String> identities = new ArrayList<>();
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT sec_usr_id, sec_usr_type FROM user_security ORDER BY sec_usr_id")) {
            while (resultSet.next()) {
                identities.add(resultSet.getString(1) + ":" + resultSet.getString(2));
            }
        }
        return identities;
    }

    /**
     * Renders the expected roster for a failure message.
     *
     * <p>Named in the assertion description so a failure states which roster was expected without a
     * reader having to open the migration to find out.
     *
     * @return the expected identities as a single readable line
     */
    private static String describeSeededIdentities() {
        return String.join(", ", SEEDED_IDENTITIES);
    }
}
