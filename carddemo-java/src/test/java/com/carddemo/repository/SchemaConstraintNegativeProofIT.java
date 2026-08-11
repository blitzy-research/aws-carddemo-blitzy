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
package com.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.util.FixedWidthFieldReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves, by making the server refuse a row, that every declared guarantee of the shipped schema is a
 * guarantee the DATABASE enforces rather than one only the entity layer enforces.
 *
 * <h2>The two gaps this closes</h2>
 *
 * <p><strong>One. A character bound is not a byte bound.</strong> {@code VARCHAR(n)},
 * {@code information_schema.character_maximum_length} and {@code char_length} all count CHARACTERS. Under
 * UTF8 a single character occupies up to four bytes, so a value of {@code n} characters can occupy far more
 * than {@code n} bytes and still satisfy every one of those three. Every text column of this schema is a
 * field of a fixed-width record image whose width is contractual - the four delivered output formats at 80,
 * 100, 133 and 430 bytes are compared byte for byte - and
 * {@link FixedWidthFieldReader#encodedLength(String)} measures every field as US-ASCII and REFUSES a
 * character US-ASCII cannot represent rather than substituting one. A multibyte value therefore passes the
 * declared bound, passes every character-count assertion, and is then unwritable by the layer that has to
 * emit it: the failure surfaces at output time on a row accepted long before. {@code V1} closes that with
 * one {@code ck_<table>_single_byte_text} constraint per table, and the tests below make the server refuse
 * such a value on all eleven tables and name the constraint that did it.
 *
 * <p><strong>Three. A widened column is not a protected column.</strong> Three columns are widened
 * beyond their legacy record widths so they can hold a protected value - the two regulated customer
 * identifiers hold an {@code ENC1} envelope and the sign-on credential holds a BCrypt digest. Until
 * {@code V2_2} they were constrained for width and byte repertoire only, and both a nine-digit national
 * identifier and an eight-character password satisfy those. The entity layer refuses both, and only for a
 * writer that constructs an entity; a bulk load, a repair script or a native statement reaches the column
 * directly. {@code V2_2} adds one {@code CHECK} per column, and the tests below make the server refuse
 * cleartext, a short envelope, a body outside the basic Base64 alphabet, an unrecognised BCrypt version
 * marker and a digest below the accepted cost - each naming the constraint that did it. See
 * {@code docs/decision-log.md} DL-349.
 *
 * <p><strong>Two. A named constraint with no negative proof is a comment.</strong> {@code V1} declares nine
 * key-shape {@code CHECK} constraints. The entities enforce the same rules before a write, so a
 * specification that goes through the entity layer passes whether or not the database guard exists - which
 * means removing the guard would break nothing observable. Every one of the nine is therefore exercised
 * here through RAW JDBC, bypassing the entity layer entirely, and each assertion names the exact constraint
 * PostgreSQL reports.
 *
 * <h2>Why raw JDBC, and why the constraint NAME is asserted</h2>
 *
 * <p>Raw JDBC is the point: it is the shape of the writer these constraints exist to catch - a bulk load, a
 * migration script, or a future writer that never constructs a record image. Asserting only that "an
 * exception was thrown" would pass if a different constraint, a foreign key, or a type coercion refused the
 * row for an unrelated reason, and the test would then be green while the guard under test was gone. The
 * constraint name is what ties the refusal to the declaration.
 *
 * <p>Every insert runs inside a transaction that is rolled back, so this class leaves no row behind and
 * needs no reset. Parent rows for the six foreign keys are the seeded ones, which is why the fixtures below
 * name seeded keys rather than inventing parents.
 *
 * <p>No COBOL source line is transcribed.
 */
@DisplayName("Schema guards, proven by refusal: single-byte text, nine key shapes, protected values")
final class SchemaConstraintNegativeProofIT extends AbstractPostgresIT {

    /**
     * A character that occupies more than one byte under the server encoding.
     *
     * <p>U+00C9, LATIN CAPITAL LETTER E WITH ACUTE. One character, two bytes in UTF-8, and outside US-ASCII
     * so {@link FixedWidthFieldReader#encodedLength(String)} refuses to measure it. It is the smallest
     * possible counterexample: anything that admits this admits every wider one.
     */
    private static final String MULTIBYTE_CHARACTER = "\u00C9";

    /** The PostgreSQL SQLSTATE for a violated CHECK constraint. */
    private static final String SQLSTATE_CHECK_VIOLATION = "23514";

    /**
     * A protected-value envelope of exactly the shape the codec produces, and nothing else.
     *
     * <p>Forty-five characters: the five-character marker plus a forty-character padded body decoding to
     * twenty-eight bytes, which is the initialisation vector and authentication tag with no ciphertext at
     * all. The body decodes to the ASCII text {@code SYNTHETIC-TEST-ENVELOPE-0001}, so it is self-evidently
     * not sealed regulated data; it exists to be shaped correctly rather than to be decryptable, and no key
     * in this module would open it.
     */
    private static final String PROTECTED_ENVELOPE = "ENC1:U1lOVEhFVElDLVRFU1QtRU5WRUxPUEUtMDAwMQ==";

    /**
     * A structurally valid BCrypt digest at the lowest cost the module accepts.
     *
     * <p>Sixty characters: version marker, the two-digit cost 10, the separator, then fifty-three
     * characters of BCrypt radix-64. It verifies nothing - no password produces it - which is exactly what
     * a schema fixture needs, since the column's rule is structural and the constraint neither hashes nor
     * verifies.
     */
    private static final String PROTECTED_DIGEST =
            "$2a$10$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    /** A seeded account key, used as the parent of the card and category-balance foreign keys. */
    private static final String SEEDED_ACCOUNT_ID = "00000000001";

    /** A seeded customer key, used as the parent of the cross-reference customer foreign key. */
    private static final String SEEDED_CUSTOMER_ID = "000000001";

    /** A seeded card number, used as the parent of the transaction foreign key. */
    private static final String SEEDED_CARD_NUMBER = "0500024453765740";

    /** A card number this class creates itself, because every seeded card already has a cross-reference. */
    private static final String PROOF_CARD_NUMBER = "9990000000000002";

    /** Creates the specification. */
    SchemaConstraintNegativeProofIT() {
    }

    // ==============================================================================================
    // One row per table, valid, so that a refusal below can only be the perturbation
    // ==============================================================================================

    /**
     * One insertable row per application table.
     *
     * <p>Each fixture carries the table, an ordered column-to-value map that satisfies every declared
     * constraint, and the name of that table's single-byte-text constraint. A test perturbs exactly one
     * value and asserts which constraint refused the result; the fixture inserting cleanly beforehand is
     * what makes the perturbation the only variable.
     *
     * @param table                 the table name
     * @param values                column name to value, in insertion order
     * @param byteRepertoireCheck   the name of this table's single-byte-text constraint
     * @param perturbableTextColumn a text column whose value a multibyte test may replace, chosen so that
     *                              no OTHER constraint on the same column can refuse the perturbation
     *                              first - otherwise the test would prove the wrong guard
     * @param parent                a row that must exist first for this one to satisfy a foreign key, or
     *                              {@code null} when the seeded rows already supply every parent
     */
    private record TableFixture(String table, Map<String, Object> values, String byteRepertoireCheck,
            String perturbableTextColumn, TableFixture parent) {

        /**
         * Renders the parameterised insert for this fixture.
         *
         * @return the insert statement
         */
        String insertStatement() {
            final String columns = String.join(", ", this.values.keySet());
            final String placeholders = String.join(", ", this.values.keySet().stream()
                    .map(ignored -> "?")
                    .toList());
            return "INSERT INTO " + this.table + " (" + columns + ") VALUES (" + placeholders + ")";
        }

        /**
         * Returns this fixture's values with one column replaced.
         *
         * @param column      the column to replace
         * @param replacement the replacement value
         * @return a copy carrying the replacement
         */
        List<Object> valuesWith(final String column, final Object replacement) {
            final List<Object> ordered = new ArrayList<>();
            this.values.forEach((name, value) ->
                    ordered.add(name.equals(column) ? replacement : value));
            return ordered;
        }

        @Override
        public String toString() {
            return this.table;
        }
    }

    /**
     * Builds an ordered column-to-value map.
     *
     * @param pairs alternating column name and value
     * @return the ordered map
     */
    private static Map<String, Object> row(final Object... pairs) {
        final Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put((String) pairs[index], pairs[index + 1]);
        }
        return values;
    }

    /**
     * The eleven fixtures, one per application table.
     *
     * @return the fixtures
     */
    private static List<TableFixture> tableFixtures() {
        return List.of(
                new TableFixture("account", row(
                        "acct_id", "99900000001",
                        "acct_active_status", "Y",
                        "acct_curr_bal", new BigDecimal("100.00"),
                        "acct_credit_limit", new BigDecimal("5000.00"),
                        "acct_cash_credit_limit", new BigDecimal("1000.00"),
                        "acct_open_date", "2020-01-01",
                        "acct_expiration_date", "2030-01-01",
                        "acct_reissue_date", "2025-01-01",
                        "acct_curr_cyc_credit", new BigDecimal("0.00"),
                        "acct_curr_cyc_debit", new BigDecimal("0.00"),
                        "acct_addr_zip", "0000012345",
                        "acct_group_id", "ZEROAPR   "),
                        "ck_account_single_byte_text", "acct_group_id", null),
                new TableFixture("card", row(
                        "card_num", "9990000000000001",
                        "card_acct_id", SEEDED_ACCOUNT_ID,
                        "card_cvv_cd", "123",
                        "card_embossed_name", padded("NEGATIVE PROOF HOLDER", 50),
                        "card_expiration_date", "2030-01-01",
                        "card_active_status", "Y"),
                        "ck_card_single_byte_text", "card_embossed_name", null),
                new TableFixture("customer", row(
                        "cust_id", "999000001",
                        "first_name", padded("PROOF", 25),
                        "middle_name", padded("N", 25),
                        "last_name", padded("SUBJECT", 25),
                        "addr_line_1", padded("1 TEST WAY", 50),
                        "addr_line_2", padded(" ", 50),
                        "addr_line_3", padded("TESTVILLE", 50),
                        "addr_state_cd", "TX",
                        "addr_country_cd", "USA",
                        "addr_zip", "0000012345",
                        "phone_num_1", padded("(555)1234567", 15),
                        "phone_num_2", padded("(555)7654321", 15),
                        // Both regulated identifiers carry a correctly shaped envelope, and cust_ssn
                        // is listed rather than left to default to NULL so the nest below can perturb it.
                        // // A two-colon value of thirty-eight characters is NOT an envelope by the
                        // // codec's own rule, and the schema would accept one if nothing required
                        // // otherwise.
                        "cust_ssn", PROTECTED_ENVELOPE,
                        "govt_issued_id", PROTECTED_ENVELOPE,
                        "cust_dob", "1980-01-01",
                        "eft_account_id", "0000000001",
                        "pri_card_holder_ind", "Y",
                        "fico_credit_score", "700"),
                        "ck_customer_single_byte_text", "last_name", null),
                new TableFixture("card_cross_reference", row(
                        // A card number of its OWN rather than a seeded one: every seeded card already
                        // carries a cross-reference row, so a seeded key would be refused by the primary
                        // key before any constraint under test was reached. The card it names is created by
                        // the parent fixture below, inside the same rolled-back transaction.
                        "xref_card_num", PROOF_CARD_NUMBER,
                        "xref_cust_id", SEEDED_CUSTOMER_ID,
                        "xref_acct_id", SEEDED_ACCOUNT_ID),
                        // The card number carries only a WIDTH rule, so a same-length multibyte value
                        // satisfies it and only the repertoire rule can refuse the row. The other two
                        // columns carry digit classes, which would refuse the perturbation first and prove
                        // the wrong constraint.
                        "ck_card_xref_single_byte_text", "xref_card_num", proofCardFixture()),
                new TableFixture("transaction", row(
                        "tran_id", "9990000000000001",
                        "tran_type_cd", "01",
                        "tran_cat_cd", "0001",
                        "tran_source", padded("POS TERM", 10),
                        "tran_desc", padded("NEGATIVE PROOF", 100),
                        "tran_amt", new BigDecimal("12.34"),
                        "merchant_id", "000000001",
                        "merchant_name", padded("TEST MERCHANT", 50),
                        "merchant_city", padded("TESTVILLE", 50),
                        "merchant_zip", "0000012345",
                        "tran_card_num", SEEDED_CARD_NUMBER,
                        "tran_orig_ts", "2022-06-10 19:27:53.000000",
                        "tran_proc_ts", "2022-06-10 19:27:53.000000"),
                        "ck_transaction_single_byte_text", "merchant_name", null),
                new TableFixture("daily_transaction", row(
                        "dalytran_id", "9990000000000001",
                        "dalytran_type_cd", "01",
                        "dalytran_cat_cd", "0001",
                        "dalytran_source", padded("POS TERM", 10),
                        "dalytran_desc", padded("NEGATIVE PROOF", 100),
                        "dalytran_amt", new BigDecimal("12.34"),
                        "dalytran_merchant_id", "000000001",
                        "dalytran_merchant_name", padded("TEST MERCHANT", 50),
                        "dalytran_merchant_city", padded("TESTVILLE", 50),
                        "dalytran_merchant_zip", "0000012345",
                        "dalytran_card_num", SEEDED_CARD_NUMBER,
                        "dalytran_orig_ts", "2022-06-10 19:27:53.000000",
                        "dalytran_proc_ts", padded(" ", 26)),
                        "ck_daily_transaction_single_byte_text", "dalytran_merchant_city", null),
                new TableFixture("transaction_category_balance", row(
                        "trancat_acct_id", SEEDED_ACCOUNT_ID,
                        "trancat_type_cd", "99",
                        "trancat_cd", "9999",
                        "tran_cat_bal", new BigDecimal("50.00")),
                        "ck_transaction_category_balance_single_byte_text", "trancat_cd", null),
                new TableFixture("disclosure_group", row(
                        "dis_acct_group_id", "PROOFGRP  ",
                        "dis_tran_type_cd", "99",
                        "dis_tran_cat_cd", "9999",
                        "dis_int_rate", new BigDecimal("12.34")),
                        "ck_disclosure_group_single_byte_text", "dis_acct_group_id", null),
                new TableFixture("transaction_type", row(
                        "tran_type", "99",
                        "tran_type_desc", padded("NEGATIVE PROOF TYPE", 50)),
                        "ck_transaction_type_single_byte_text", "tran_type_desc", null),
                new TableFixture("transaction_category", row(
                        "tran_type_cd", "99",
                        "tran_cat_cd", "9999",
                        "tran_cat_type_desc", padded("NEGATIVE PROOF CATEGORY", 50)),
                        "ck_transaction_category_single_byte_text", "tran_cat_type_desc", null),
                new TableFixture("user_security", row(
                        "sec_usr_id", "PROOF001",
                        "sec_usr_fname", padded("PROOF", 20),
                        "sec_usr_lname", padded("SUBJECT", 20),
                        "sec_usr_pwd", PROTECTED_DIGEST,
                        "sec_usr_type", "U"),
                        "ck_user_security_single_byte_text", "sec_usr_lname", null));
    }

    /**
     * The card row the cross-reference fixture needs as its foreign-key parent.
     *
     * @return a card fixture carrying {@link #PROOF_CARD_NUMBER}
     */
    private static TableFixture proofCardFixture() {
        return new TableFixture("card", row(
                "card_num", PROOF_CARD_NUMBER,
                "card_acct_id", SEEDED_ACCOUNT_ID,
                "card_cvv_cd", "321",
                "card_embossed_name", padded("CROSS REFERENCE PARENT", 50),
                "card_expiration_date", "2030-01-01",
                "card_active_status", "Y"),
                "ck_card_single_byte_text", "card_embossed_name", null);
    }

    /**
     * Blank-pads a value to the width its record field occupies.
     *
     * @param value the value
     * @param width the field width
     * @return the padded value
     */
    private static String padded(final String value, final int width) {
        return value.length() >= width ? value.substring(0, width)
                : value + " ".repeat(width - value.length());
    }

    // ==============================================================================================
    // Execution helpers - raw JDBC, always rolled back
    // ==============================================================================================

    /**
     * Inserts one fixture row - preceded by its parent, when it has one - inside a transaction, and rolls
     * the transaction back.
     *
     * <p>Nothing this class writes survives it, whether the statement succeeded or was refused, which is
     * why no reset hook is needed and why the class can run beside every other specification on the shared
     * server.
     *
     * @param fixture     the fixture to insert
     * @param column      the column to replace, or {@code "__none__"} to insert the fixture unchanged
     * @param replacement the replacement value
     * @throws SQLException if the insert is refused, which is what most tests here assert
     */
    private static void insertAndRollBack(final TableFixture fixture, final String column,
            final Object replacement) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                if (fixture.parent() != null) {
                    // The parent is never perturbed: it exists only so the row under test has a foreign-key
                    // target, and it disappears with the same rollback.
                    execute(connection, fixture.parent().insertStatement(),
                            fixture.parent().valuesWith("__none__", null));
                }
                execute(connection, fixture.insertStatement(), fixture.valuesWith(column, replacement));
            } finally {
                connection.rollback();
            }
        }
    }

    /**
     * Runs one parameterised statement on the supplied connection.
     *
     * @param connection    the open connection
     * @param statementText the statement
     * @param values        the bound values, in order
     * @throws SQLException if the statement is refused
     */
    private static void execute(final Connection connection, final String statementText,
            final List<Object> values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(statementText)) {
            for (int index = 0; index < values.size(); index++) {
                statement.setObject(index + 1, values.get(index));
            }
            statement.executeUpdate();
        }
    }

    /**
     * Asserts that a perturbed fixture row is refused by a CHECK constraint of the given name.
     *
     * @param fixture        the fixture
     * @param column         the column to perturb
     * @param replacement    the perturbed value
     * @param constraintName the constraint expected to refuse it
     */
    private static void assertRefusedBy(final TableFixture fixture, final String column,
            final Object replacement, final String constraintName) {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insertAndRollBack(fixture, column, replacement))
                .satisfies(refusal -> {
                    assertThat(refusal.getSQLState())
                            .as("a violated CHECK reports SQLSTATE %s; another state would mean something "
                                    + "else refused the row and the guard under test was never reached",
                                    SQLSTATE_CHECK_VIOLATION)
                            .isEqualTo(SQLSTATE_CHECK_VIOLATION);
                    assertThat(refusal.getMessage())
                            .as("the refusal must name %s: asserting only that something was thrown would "
                                    + "pass with this constraint deleted", constraintName)
                            .contains(constraintName);
                });
    }

    // ==============================================================================================
    // DB-6: the byte repertoire, proven by refusal on all eleven tables
    // ==============================================================================================

    @Nested
    @DisplayName("Single-byte text: a multibyte value is refused on every table")
    class SingleByteText {

        /** Creates the nest. */
        SingleByteText() {
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.repository.SchemaConstraintNegativeProofIT#tableFixtures")
        @DisplayName("the fixture row inserts cleanly, so any refusal below is the perturbation and not the "
                + "fixture")
        void theFixtureRowInsertsCleanly(final TableFixture fixture) throws SQLException {
            insertAndRollBack(fixture, "__none__", null);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.repository.SchemaConstraintNegativeProofIT#tableFixtures")
        @DisplayName("a value carrying ONE multibyte character is refused, naming the table's own "
                + "single-byte-text constraint")
        void aMultibyteValueIsRefused(final TableFixture fixture) {
            final String column = fixture.perturbableTextColumn();
            final String valid = (String) fixture.values().get(column);
            // Same CHARACTER count, one more byte. The declared VARCHAR bound is satisfied and so is every
            // char_length assertion; only the octet-length rule can see the difference.
            final String multibyte = MULTIBYTE_CHARACTER + valid.substring(1);

            assertThat(multibyte.length())
                    .as("the perturbation changes no character count, which is exactly why a character "
                            + "bound cannot catch it")
                    .isEqualTo(valid.length());
            assertThat(multibyte.getBytes(StandardCharsets.UTF_8).length)
                    .as("but it does change the encoded byte count, which is the contractual width")
                    .isGreaterThan(multibyte.length());

            assertRefusedBy(fixture, column, multibyte, fixture.byteRepertoireCheck());
        }

        @Test
        @DisplayName("the value the schema refuses is exactly the value the fixed-width layer cannot "
                + "measure, so the two authorities now agree")
        void theSchemaAndTheFixedWidthLayerRefuseTheSameValue() {
            // This is the reconciliation the finding asked for. Before the constraint existed, the schema
            // admitted a value the emitting layer refuses, and the disagreement surfaced at output time on a
            // row accepted long before.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the fixed-width layer measures US-ASCII and refuses to measure anything else "
                            + "rather than substituting a replacement character and reporting a plausible "
                            + "but wrong width")
                    .isThrownBy(() -> FixedWidthFieldReader.encodedLength(MULTIBYTE_CHARACTER));

            assertThat(FixedWidthFieldReader.encodedLength("ABC"))
                    .as("and for a value the schema admits, the encoded byte length and the character "
                            + "count are the same number - which is what the constraint states")
                    .isEqualTo("ABC".length());
        }
    }

    // ==============================================================================================
    // DB-6 coverage: the constraint set is closed over the catalogue, not over a list someone wrote
    // ==============================================================================================

    @Nested
    @DisplayName("Single-byte text coverage: every character column of every table is named")
    class SingleByteTextCoverage {

        /** Creates the nest. */
        SingleByteTextCoverage() {
        }

        @Test
        @DisplayName("EVERY character column of EVERY application table is named in its table's "
                + "single-byte-text constraint, so a column added later cannot escape the rule silently")
        void everyCharacterColumnIsNamedInItsConstraint() throws SQLException {
            final List<String> unprotected = new ArrayList<>();
            int columnsChecked = 0;

            for (final String table : APPLICATION_TABLES) {
                final String definition = checkConstraintDefinition(table, "single_byte_text");
                assertThat(definition)
                        .as("table %s must declare a single-byte-text constraint", table)
                        .isNotNull();

                for (final String column : characterColumnsOf(table)) {
                    columnsChecked++;
                    // The server renders a bounded-text column inside a constraint definition as
                    // (name)::text, so the rendered form is what has to be matched rather than the bare
                    // name. Both sides are required: a definition naming the column only once would be
                    // comparing it against something other than its own length.
                    final String rendered = "(" + column + ")::text";
                    if (!definition.contains("octet_length(" + rendered + ")")
                            || !definition.contains("char_length(" + rendered + ")")) {
                        unprotected.add(table + "." + column);
                    }
                }
            }

            assertThat(columnsChecked)
                    .as("the catalogue must have yielded the module's text columns; a count of zero would "
                            + "make this assertion vacuous")
                    .isEqualTo(74);
            assertThat(unprotected)
                    .as("every character column must appear on both sides of an octet-length equality; a "
                            + "column listed here was added to a table without being added to its "
                            + "constraint, and it can now hold a value the emitting layer cannot write")
                    .isEmpty();
        }

        @Test
        @DisplayName("the seeded rows all satisfy the rule, so the constraint describes the delivered data "
                + "rather than contradicting it")
        void theSeededRowsSatisfyTheRule() throws SQLException {
            for (final String table : APPLICATION_TABLES) {
                final List<String> columns = characterColumnsOf(table);
                if (columns.isEmpty()) {
                    continue;
                }
                final String predicate = String.join(" OR ", columns.stream()
                        .map(column -> "octet_length(" + column + ") <> char_length(" + column + ")")
                        .toList());

                try (Connection connection = connect();
                        PreparedStatement statement = connection.prepareStatement(
                                "SELECT count(*) FROM " + table + " WHERE " + predicate);
                        ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1))
                            .as("no seeded row of %s may carry a multibyte value", table)
                            .isZero();
                }
            }
        }
    }

    // ==============================================================================================
    // DB-7: each of the nine named key-shape constraints, proven by refusal
    // ==============================================================================================

    @Nested
    @DisplayName("Key shapes: each of the nine named constraints refuses a malformed key")
    class KeyShapes {

        /** Creates the nest. */
        KeyShapes() {
        }

        @Test
        @DisplayName("an eleven-digit account key is required: a shorter one is refused by "
                + "ck_account_acct_id_digits")
        void aShortAccountKeyIsRefused() {
            final TableFixture account = fixtureFor("account");
            assertRefusedBy(account, "acct_id", "1", "ck_account_acct_id_digits");
        }

        @Test
        @DisplayName("a NON-DIGIT account key is refused by the same constraint, because the rule is a "
                + "digit class and not only a width")
        void aNonDigitAccountKeyIsRefused() {
            final TableFixture account = fixtureFor("account");
            assertRefusedBy(account, "acct_id", "9990000000A", "ck_account_acct_id_digits");
        }

        @Test
        @DisplayName("a card number of the wrong width is refused by ck_card_card_num_width")
        void aShortCardNumberIsRefused() {
            final TableFixture card = fixtureFor("card");
            assertRefusedBy(card, "card_num", "999000000000000", "ck_card_card_num_width");
        }

        @Test
        @DisplayName("a card number carrying a LETTER is accepted, because the legacy field is declared "
                + "alphanumeric and only its width is contractual")
        void aLetterInTheCardNumberIsAccepted() throws SQLException {
            final TableFixture card = fixtureFor("card");
            insertAndRollBack(card, "card_num", "999000000000000A");
        }

        @Test
        @DisplayName("a non-digit owning-account identifier on a card is refused by "
                + "ck_card_card_acct_id_digits")
        void aNonDigitCardAccountIdentifierIsRefused() {
            final TableFixture card = fixtureFor("card");
            assertRefusedBy(card, "card_acct_id", "0000000000A", "ck_card_card_acct_id_digits");
        }

        @Test
        @DisplayName("a nine-digit customer key is required: a shorter one is refused by "
                + "ck_customer_cust_id_digits")
        void aShortCustomerKeyIsRefused() {
            final TableFixture customer = fixtureFor("customer");
            assertRefusedBy(customer, "cust_id", "1", "ck_customer_cust_id_digits");
        }

        @Test
        @DisplayName("a cross-reference card number of the wrong width is refused by "
                + "ck_card_xref_card_num_width")
        void aShortCrossReferenceCardNumberIsRefused() {
            final TableFixture crossReference = fixtureFor("card_cross_reference");
            // Sixteen characters is the rule, so fifteen is refused before the foreign key is considered:
            // a CHECK is evaluated on the row, and the row is what is malformed.
            assertRefusedBy(crossReference, "xref_card_num", "999000000000000",
                    "ck_card_xref_card_num_width");
        }

        @Test
        @DisplayName("a non-digit cross-reference customer identifier is refused by "
                + "ck_card_xref_cust_id_digits")
        void aNonDigitCrossReferenceCustomerIdentifierIsRefused() {
            final TableFixture crossReference = fixtureFor("card_cross_reference");
            assertRefusedBy(crossReference, "xref_cust_id", "00000000A",
                    "ck_card_xref_cust_id_digits");
        }

        @Test
        @DisplayName("a short cross-reference account identifier is refused by "
                + "ck_card_xref_acct_id_digits")
        void aShortCrossReferenceAccountIdentifierIsRefused() {
            final TableFixture crossReference = fixtureFor("card_cross_reference");
            assertRefusedBy(crossReference, "xref_acct_id", "1", "ck_card_xref_acct_id_digits");
        }

        @Test
        @DisplayName("a transaction identifier that is not sixteen digits is refused by "
                + "ck_transaction_tran_id_digits, which is what keeps the maximum-plus-one allocator from "
                + "freezing")
        void aMalformedTransactionIdentifierIsRefused() {
            final TableFixture transaction = fixtureFor("transaction");
            // A single '9' would sort ABOVE every well-formed sixteen-digit identifier under character
            // ordering, so the allocator's next value would be its successor forever. That is the failure
            // this constraint exists to prevent, and it is why the rule is a digit class of exact width.
            assertRefusedBy(transaction, "tran_id", "9", "ck_transaction_tran_id_digits");
            assertRefusedBy(transaction, "tran_id", "999000000000000A",
                    "ck_transaction_tran_id_digits");
        }

        @Test
        @DisplayName("a sign-on identifier of the wrong width is refused by "
                + "ck_user_security_sec_usr_id_width")
        void aShortSignOnIdentifierIsRefused() {
            final TableFixture user = fixtureFor("user_security");
            assertRefusedBy(user, "sec_usr_id", "PROOF", "ck_user_security_sec_usr_id_width");
        }

        @Test
        @DisplayName("a sign-on identifier carrying LETTERS is accepted at the right width, because the "
                + "legacy field is alphanumeric and every seeded identity carries letters")
        void lettersInTheSignOnIdentifierAreAcceptedAtTheRightWidth() throws SQLException {
            final TableFixture user = fixtureFor("user_security");
            insertAndRollBack(user, "sec_usr_id", "PROOFXYZ");
        }

        @Test
        @DisplayName("all NINE named key-shape constraints exist in the catalogue, so this nest cannot "
                + "silently stop covering one")
        void allNineNamedKeyShapeConstraintsExist() throws SQLException {
            final List<String> expected = List.of(
                    "ck_account_acct_id_digits",
                    "ck_card_card_num_width",
                    "ck_card_card_acct_id_digits",
                    "ck_customer_cust_id_digits",
                    "ck_card_xref_card_num_width",
                    "ck_card_xref_cust_id_digits",
                    "ck_card_xref_acct_id_digits",
                    "ck_transaction_tran_id_digits",
                    "ck_user_security_sec_usr_id_width");

            assertThat(namedCheckConstraints())
                    .as("every key-shape constraint this nest asserts on must be declared; one missing "
                            + "would make its assertion fail for the wrong reason")
                    .containsAll(expected);
        }
    }

    // ==============================================================================================
    // The image widths of the five tables whose keys were bounded by a MAXIMUM alone
    // ==============================================================================================

    @Nested
    @DisplayName("Image widths: the fourteen exact-width rules that a bounded maximum did not express")
    class ImageWidths {

        /** Creates the nest. */
        ImageWidths() {
        }

        @Test
        @DisplayName("an account group identifier shorter than ten characters is refused - the one column "
                + "on the account table that is a key SOMEWHERE ELSE")
        void aShortAccountGroupIdentifierIsRefused() {
            final TableFixture account = fixtureFor("account");

            // The seven-character spelling is the whole point. The seeded groups DEFAULT and ZEROAPR are
            // seven characters followed by three spaces, so the unpadded form is the value a caller would
            // most plausibly send and the one that resolves nothing - it matches no disclosure row, the
            // rate lookup takes its default-group fallback, and a zero-rate account accrues at the default
            // rate. Nothing else in the schema would have caught it: the group identifier alone is a
            // nonunique leading part of the disclosure key, so it cannot be a foreign key.
            assertRefusedBy(account, "acct_group_id", "ZEROAPR",
                    "ck_account_acct_group_id_width");
        }

        @Test
        @DisplayName("and the PADDED ten-character form of the same identifier is accepted, so the rule is "
                + "a width and not a vocabulary")
        void thePaddedAccountGroupIdentifierIsAccepted() throws SQLException {
            final TableFixture account = fixtureFor("account");
            insertAndRollBack(account, "acct_group_id", "DEFAULT   ");
        }

        @Test
        @DisplayName("each of the landing table's four image-critical fields is refused at the wrong width")
        void theLandingTableRefusesEveryWrongImageWidth() {
            final TableFixture landing = fixtureFor("daily_transaction");

            assertRefusedBy(landing, "dalytran_id", "999000000000001",
                    "ck_daily_transaction_dalytran_id_width");
            assertRefusedBy(landing, "dalytran_type_cd", "1",
                    "ck_daily_transaction_type_cd_width");
            assertRefusedBy(landing, "dalytran_cat_cd", "1",
                    "ck_daily_transaction_cat_cd_width");
            assertRefusedBy(landing, "dalytran_card_num", "999000000000001",
                    "ck_daily_transaction_card_num_width");
        }

        @Test
        @DisplayName("but the landing table still accepts a non-numeric CONTENT at the right width, "
                + "because content is the reject dataset's to report and not the schema's to refuse")
        void theLandingTableAcceptsMalformedContentAtTheRightWidth() throws SQLException {
            final TableFixture landing = fixtureFor("daily_transaction");

            // A category code whose picture clause is PIC 9(04) and whose final byte is an overpunched
            // sign - the zoned-decimal form app/jcl/PRTCATBL.jcl declares for the corresponding field.
            insertAndRollBack(landing, "dalytran_cat_cd", "000J");
            // A card number that is sixteen characters and is not a card. Reason code 100 exists to
            // report exactly this, and it can only do so if the row is allowed to land.
            insertAndRollBack(landing, "dalytran_card_num", "NOTACARDNUMBER01");
        }

        @Test
        @DisplayName("each of the category-balance key's three parts is refused at the wrong width")
        void theCategoryBalanceKeyRefusesEveryWrongPartWidth() {
            final TableFixture balance = fixtureFor("transaction_category_balance");

            assertRefusedBy(balance, "trancat_acct_id", "1", "ck_tran_cat_bal_acct_id_width");
            assertRefusedBy(balance, "trancat_type_cd", "9", "ck_tran_cat_bal_type_cd_width");
            assertRefusedBy(balance, "trancat_cd", "9", "ck_tran_cat_bal_cat_cd_width");
        }

        @Test
        @DisplayName("and the category-balance key accepts an OVERPUNCHED category code at the right "
                + "width, because its own sort specification declares that field zoned decimal")
        void theCategoryBalanceKeyAcceptsAnOverpunchedCategoryCode() throws SQLException {
            final TableFixture balance = fixtureFor("transaction_category_balance");

            // THE ASSERTION THAT FIXES THE RULE'S SHAPE. app/jcl/PRTCATBL.jcl declares this very key as
            // TRANCAT-ACCT-ID,1,11,ZD - TRANCAT-TYPE-CD,12,2,CH - TRANCAT-CD,14,4,ZD. Zoned decimal folds
            // the sign into the final byte, so 000J reads as minus one and is a legitimate value of a
            // PIC 9(04) field in this estate. A digit class here would have refused it - and the
            // category-balance report's own ordering fixture is built from it.
            insertAndRollBack(balance, "trancat_cd", "000J");
        }

        @Test
        @DisplayName("each of the disclosure key's three parts is refused at the wrong width, the group "
                + "identifier included")
        void theDisclosureKeyRefusesEveryWrongPartWidth() {
            final TableFixture group = fixtureFor("disclosure_group");

            assertRefusedBy(group, "dis_acct_group_id", "PROOFGRP",
                    "ck_disclosure_group_acct_group_id_width");
            assertRefusedBy(group, "dis_tran_type_cd", "9",
                    "ck_disclosure_group_tran_type_cd_width");
            assertRefusedBy(group, "dis_tran_cat_cd", "9",
                    "ck_disclosure_group_tran_cat_cd_width");
        }

        @Test
        @DisplayName("the two reference tables refuse a key part at the wrong width, which is what stops "
                + "'1' and '01' claiming the same two bytes of one record")
        void theReferenceTablesRefuseEveryWrongKeyWidth() {
            final TableFixture type = fixtureFor("transaction_type");
            final TableFixture category = fixtureFor("transaction_category");

            assertRefusedBy(type, "tran_type", "9", "ck_transaction_type_tran_type_width");
            assertRefusedBy(category, "tran_type_cd", "9",
                    "ck_transaction_category_tran_type_cd_width");
            assertRefusedBy(category, "tran_cat_cd", "9",
                    "ck_transaction_category_tran_cat_cd_width");
        }

        @Test
        @DisplayName("all FOURTEEN named image-width constraints exist in the catalogue, and not one of "
                + "them is a digit class")
        void allFourteenNamedImageWidthConstraintsExist() throws SQLException {
            final List<String> expected = List.of(
                    "ck_account_acct_group_id_width",
                    "ck_daily_transaction_dalytran_id_width",
                    "ck_daily_transaction_type_cd_width",
                    "ck_daily_transaction_cat_cd_width",
                    "ck_daily_transaction_card_num_width",
                    "ck_tran_cat_bal_acct_id_width",
                    "ck_tran_cat_bal_type_cd_width",
                    "ck_tran_cat_bal_cat_cd_width",
                    "ck_disclosure_group_acct_group_id_width",
                    "ck_disclosure_group_tran_type_cd_width",
                    "ck_disclosure_group_tran_cat_cd_width",
                    "ck_transaction_type_tran_type_width",
                    "ck_transaction_category_tran_type_cd_width",
                    "ck_transaction_category_tran_cat_cd_width");

            assertThat(namedCheckConstraints())
                    .as("every width constraint this nest asserts on must be declared; one missing would "
                            + "make its assertion fail for the wrong reason")
                    .containsAll(expected);
            assertThat(expected)
                    .as("and every one of them is named a width rather than a digit class, which is the "
                            + "naming convention this file already uses to tell the two rules apart - the "
                            + "reason each is a width is recorded per table in V1__create_schema.sql")
                    .allSatisfy(name -> assertThat(name).endsWith("_width"));
        }
    }

    // ==============================================================================================
    // DB-8: the three protected-value invariants V2_2 adds, proven by refusal
    // ==============================================================================================

    @Nested
    @DisplayName("Protected values: the three columns widened for ciphertext or a digest refuse anything "
            + "else")
    class ProtectedValues {

        /** The constraint on the nullable regulated identifier. */
        private static final String SSN_CONSTRAINT = "ck_customer_cust_ssn_protected";

        /** The constraint on the mandatory regulated identifier. */
        private static final String GOVT_CONSTRAINT = "ck_customer_govt_issued_id_protected";

        /** The constraint on the stored sign-on credential. */
        private static final String DIGEST_CONSTRAINT = "ck_user_security_sec_usr_pwd_digest";

        /** Creates the nest. */
        ProtectedValues() {
        }

        @Test
        @DisplayName("all three constraints are declared, so every refusal below fails for the reason it "
                + "claims rather than because the guard is missing")
        void allThreeConstraintsAreDeclared() throws SQLException {
            assertThat(namedCheckConstraints())
                    .as("one absent constraint would turn every assertion in this nest into a test of "
                            + "something else")
                    .contains(SSN_CONSTRAINT, GOVT_CONSTRAINT, DIGEST_CONSTRAINT);
        }

        @Test
        @DisplayName("a nine-digit national identifier - the legacy record's own cleartext width - is "
                + "refused")
        void aCleartextNationalIdentifierIsRefused() {
            // The exact shape the finding named: US-ASCII, well inside VARCHAR(255), and accepted by every
            // constraint the schema carried before V2_2.
            assertRefusedBy(fixtureFor("customer"), "cust_ssn", "123456789", SSN_CONSTRAINT);
        }

        @Test
        @DisplayName("and a twenty-character government-issued identifier is refused on the same grounds")
        void aCleartextGovernmentIdentifierIsRefused() {
            assertRefusedBy(fixtureFor("customer"), "govt_issued_id", "VA-DL-9999999999999",
                    GOVT_CONSTRAINT);
        }

        @Test
        @DisplayName("an envelope one byte short of an authenticated envelope is refused, so the floor is "
                + "a real minimum and not a token length")
        void anEnvelopeShortOfTheAuthenticatedMinimumIsRefused() {
            // Forty-one characters: a thirty-six character body decoding to twenty-seven bytes, one byte
            // below the twelve-byte initialisation vector plus sixteen-byte tag that any envelope must
            // carry before it holds a single byte of ciphertext.
            assertRefusedBy(fixtureFor("customer"), "cust_ssn",
                    "ENC1:" + "A".repeat(36), SSN_CONSTRAINT);
        }

        @Test
        @DisplayName("an envelope AT the floor is accepted, so the constraint states the codec's reading "
                + "rule rather than its padding habit")
        void anEnvelopeAtTheFloorIsAccepted() throws SQLException {
            // Forty-three characters: a thirty-eight character UNPADDED body, which is the fewest basic
            // Base64 characters that decode to twenty-eight bytes. This module's encoder always pads, so it
            // would never emit this value - and the codec's reader accepts it, which is why the constraint
            // must too. A floor of forty-five would have been true of everything written and still wrong.
            insertAndRollBack(fixtureFor("customer"), "cust_ssn", "ENC1:" + "A".repeat(38));
        }

        @Test
        @DisplayName("a body drawn from the URL-safe alphabet is refused, because the codec decodes with "
                + "the BASIC one")
        void aBodyOutsideTheBasicAlphabetIsRefused() {
            // Long enough to clear the floor, so the only thing wrong with it is the two characters that
            // belong to Base64's URL-safe variant rather than to the basic alphabet the codec uses.
            assertRefusedBy(fixtureFor("customer"), "cust_ssn",
                    "ENC1:" + "A".repeat(36) + "-_", SSN_CONSTRAINT);
        }

        @Test
        @DisplayName("a well-formed body with no scheme marker is refused, so the marker is part of the "
                + "rule and not decoration")
        void aBodyWithNoMarkerIsRefused() {
            assertRefusedBy(fixtureFor("customer"), "govt_issued_id",
                    PROTECTED_ENVELOPE.substring("ENC1:".length()), GOVT_CONSTRAINT);
        }

        @Test
        @DisplayName("an eight-character cleartext credential - the legacy record's own width - is refused")
        void aCleartextCredentialIsRefused() {
            // Eight characters is what the legacy record reserves and what the column would have accepted
            // before V2_2, VARCHAR(60) stating only a maximum. The value is a synthetic literal and opens
            // nothing.
            assertRefusedBy(fixtureFor("user_security"), "sec_usr_pwd", "NOTREAL1", DIGEST_CONSTRAINT);
        }

        @Test
        @DisplayName("a digest below the accepted cost is refused, so the work factor is part of the "
                + "column's rule")
        void aDigestBelowTheAcceptedCostIsRefused() {
            // Structurally a BCrypt digest in every other respect, and orders of magnitude cheaper to
            // attack: the work factor is a power of two, so cost 4 is 256 times cheaper than cost 12.
            assertRefusedBy(fixtureFor("user_security"), "sec_usr_pwd",
                    "$2a$04$" + "A".repeat(53), DIGEST_CONSTRAINT);
        }

        @Test
        @DisplayName("an unrecognised version marker is refused, so the accepted variants are the three "
                + "the credential check names")
        void anUnrecognisedVersionMarkerIsRefused() {
            assertRefusedBy(fixtureFor("user_security"), "sec_usr_pwd",
                    "$2c$12$" + "A".repeat(53), DIGEST_CONSTRAINT);
        }

        @Test
        @DisplayName("a tail character outside BCrypt's radix-64 alphabet is refused, even though it is "
                + "single-byte and the width is exact")
        void aTailOutsideTheRadix64AlphabetIsRefused() {
            // Sixty characters and pure US-ASCII, so the width bound and the byte-repertoire rule both
            // pass; the alphabet clause is the only thing that refuses it. BCrypt's radix-64 is
            // ./A-Za-z0-9 and is deliberately not standard Base64 - different ordering, no pad character.
            assertRefusedBy(fixtureFor("user_security"), "sec_usr_pwd",
                    "$2a$12$" + "A".repeat(52) + "!", DIGEST_CONSTRAINT);
        }

        @Test
        @DisplayName("the accepted digest shape is accepted at both ends of the cost window, so the rule "
                + "is a window and not a single value")
        void theAcceptedCostWindowIsAcceptedAtBothEnds() throws SQLException {
            insertAndRollBack(fixtureFor("user_security"), "sec_usr_pwd", "$2b$10$" + "A".repeat(53));
            insertAndRollBack(fixtureFor("user_security"), "sec_usr_pwd", "$2y$31$" + "A".repeat(53));
        }

        @Test
        @DisplayName("every seeded row already satisfies all three rules, so the constraints describe the "
                + "delivered data rather than contradicting it")
        void theSeededRowsSatisfyTheProtectedValueRules() throws SQLException {
            assertThat(countMatching("customer",
                    "govt_issued_id NOT LIKE 'ENC1:%' OR char_length(govt_issued_id) < 43"))
                    .as("every seeded customer row must carry a sealed government-issued identifier; a "
                            + "row listed here would hold regulated data in the clear")
                    .isZero();
            assertThat(countMatching("customer",
                    "cust_ssn IS NOT NULL AND (cust_ssn NOT LIKE 'ENC1:%' OR char_length(cust_ssn) < 43)"))
                    .as("and any seeded national identifier that is present must be sealed too; absence is "
                            + "the one state this column may hold unsealed, because it is the state the "
                            + "reference seed leaves it in")
                    .isZero();
            assertThat(countMatching("user_security", "char_length(sec_usr_pwd) <> 60"))
                    .as("and every seeded credential must be a full-width digest")
                    .isZero();
        }
    }


    // ==============================================================================================
    // Catalogue readers
    // ==============================================================================================

    /**
     * Counts the rows of one table that satisfy a predicate.
     *
     * <p>The predicate is a literal written in this file rather than a bound parameter, because a predicate
     * is not a value: a parameter marker cannot carry one. The table name and the predicate both come from
     * constants and test literals here and never from input, so no caller-supplied text reaches the
     * statement.
     *
     * @param table     the table to count
     * @param predicate the SQL predicate rows must satisfy to be counted
     * @return the number of matching rows
     * @throws SQLException if the query cannot be run
     */
    private static long countMatching(final String table, final String predicate) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM " + table + " WHERE " + predicate);
                ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }

    /**
     * Returns the fixture for one table.
     *
     * @param table the table name
     * @return the fixture
     */
    private static TableFixture fixtureFor(final String table) {
        return tableFixtures().stream()
                .filter(fixture -> fixture.table().equals(table))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no fixture for " + table));
    }

    /**
     * Returns the character columns of one table, in ordinal order.
     *
     * @param table the table name
     * @return the character column names
     * @throws SQLException if the catalogue cannot be read
     */
    private static List<String> characterColumnsOf(final String table) throws SQLException {
        final List<String> columns = new ArrayList<>();
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT column_name
                          FROM information_schema.columns
                         WHERE table_schema = 'public'
                           AND table_name = ?
                           AND data_type IN ('character varying', 'character', 'text')
                         ORDER BY ordinal_position
                        """)) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.add(rows.getString(1));
                }
            }
        }
        return columns;
    }

    /**
     * Returns the rendered definition of one table's CHECK constraint whose name ends with the supplied
     * suffix.
     *
     * @param table  the table name
     * @param suffix the constraint-name suffix
     * @return the rendered definition, or {@code null} when no such constraint exists
     * @throws SQLException if the catalogue cannot be read
     */
    private static String checkConstraintDefinition(final String table, final String suffix)
            throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT pg_get_constraintdef(con.oid)
                          FROM pg_constraint con
                          JOIN pg_class rel ON rel.oid = con.conrelid
                          JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
                         WHERE nsp.nspname = 'public'
                           AND rel.relname = ?
                           AND con.contype = 'c'
                           AND con.conname LIKE ?
                        """)) {
            statement.setString(1, table);
            statement.setString(2, "%" + suffix);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    /**
     * Returns every named CHECK constraint in the application schema.
     *
     * @return the constraint names
     * @throws SQLException if the catalogue cannot be read
     */
    private static List<String> namedCheckConstraints() throws SQLException {
        final List<String> names = new ArrayList<>();
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT con.conname
                          FROM pg_constraint con
                          JOIN pg_namespace nsp ON nsp.oid = con.connamespace
                         WHERE nsp.nspname = 'public'
                           AND con.contype = 'c'
                           AND con.conname LIKE 'ck\\_%'
                         ORDER BY con.conname
                        """);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return names;
    }
}
