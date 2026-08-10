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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import com.carddemo.domain.Customer;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.SeededRecordFixture;
import com.carddemo.util.SensitiveFieldCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import java.util.Locale;

/**
 * Verifies that the government-issued identifier every seeded customer row carries is a real,
 * openable protected value - not cleartext wearing a comment that explains why cleartext is
 * acceptable.
 *
 * <h2>Why this test exists</h2>
 * Three separate parts of the module state that {@code customer.govt_issued_id} holds an
 * authenticated ciphertext envelope and never the twenty cleartext characters the legacy record
 * carries at offset 288: {@code V1__create_schema.sql} says so in the table's own header and widens
 * the column to {@code VARCHAR(255)} to make room for it; {@link Customer} refuses any value that is
 * not shaped like one, in its constructor and in its mutator; and
 * {@link SensitiveFieldEncryptionService} exists to produce and read exactly that shape. All three
 * held, and an earlier revision of the seed nonetheless loaded fifty cleartext identifiers - because
 * Hibernate hydrates fields directly rather than through the constructor, so a seeded row loads
 * silently and only fails at the moment something actually decrypts it. Nothing in the build noticed,
 * because nothing in the build looked. (The delivered seed embeds sealed envelopes; that is DL-103.
 * The failure mode is described in the past tense deliberately, because the same blindness recurs for
 * a mis-keyed envelope, which loads just as silently.)
 *
 * <p>This test is what looks. It reads the seeded rows out of a real migrated database and, for every
 * one of them, opens the stored value through the application's own service and compares the recovered
 * cleartext against the fixture record it was sealed from.</p>
 *
 * <h2>Why the key is read from the profile rather than written here</h2>
 * An AES-GCM envelope opens under exactly one key. The fifty envelopes in
 * {@code V3__seed_reference_data.sql} are fixed literals - a fresh initialisation vector per call means
 * they cannot be regenerated identically - so the seed and the configured key are bound together, and a
 * rotation of one without the other is a defect that shows up only when something decrypts. So the key
 * used below is loaded from {@code application-test.yml} rather than restated as a literal, and is then
 * checked against the value this test documents. That closes the rotation gap in both directions: change
 * the profile and the envelopes stop opening here; change only this test's expectation and the
 * agreement check fails. {@code ConfigurationProfileBaselineTest} separately holds the three
 * non-production documents to one shared key value, which is the invariant that lets a single seeded
 * envelope open under both the local and the test profile.
 *
 * <h2>What is asserted, and why each assertion catches something the others cannot</h2>
 * <ol>
 *   <li><b>Shape, in the database.</b> Every stored value carries the scheme marker, measures the length
 *       the codec predicts for a twenty-character payload, and none is a bare run of digits. This is the
 *       assertion that would have failed against the defective seed, and it is deliberately made here as
 *       well as in the migration's own self-check, because the self-check holds no key and can only
 *       inspect shape.</li>
 *   <li><b>Recovery, through the application.</b> Every stored value opens to the exact twenty
 *       characters that {@code custdata.txt} holds at offset 288 of the corresponding record. Shape
 *       alone would be satisfied by fifty envelopes sealing the wrong values, or the same value fifty
 *       times; this is the assertion that ties each row back to the legacy authority.</li>
 *   <li><b>Authentication.</b> A different key and an altered character are both refused rather than
 *       yielding something plausible, which is the property that makes the stored value trustworthy
 *       rather than merely opaque.</li>
 *   <li><b>The domain contract.</b> Every seeded row can be constructed as a {@link Customer}, and the
 *       cleartext the fixture holds cannot. That is the contract the defective seed violated, asserted
 *       against the entity that declares it rather than against a restatement of its rule.</li>
 * </ol>
 *
 * <p>Provenance: this test has no legacy antecedent; the legacy estate carries no test harness and
 * stores this identifier as twenty cleartext characters at offset 288 of the 500-byte customer record.
 * Checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("seeded government-issued identifiers, opened against a real database")
class SeededProtectedIdentifierIT extends AbstractPostgresIT {

    /** The profile document the fixture key is read from. */
    private static final String TEST_PROFILE_DOCUMENT = "application-test.yml";

    /** The property every profile binds the field-encryption key from. */
    private static final String KEY_PROPERTY =
            SensitiveFieldEncryptionService.FIELD_ENCRYPTION_KEY_PROPERTY;

    /**
     * The one non-production fixture key the fifty seeded envelopes were sealed under, restated so that
     * silently rotating the profile cannot silently invalidate the seed. Checked against the profile
     * rather than used in its place.
     */
    private static final String DOCUMENTED_FIXTURE_KEY =
            "Y2FyZGRlbW8tbm9ucHJvZC1maXh0dXJlLWtleSEhISE=";

    /** A second key of the same length, used to prove that recovery is key-bound. */
    private static final String FOREIGN_KEY = Base64.getEncoder().encodeToString(
            "carddemo-second-key-for-test!!!!".getBytes(StandardCharsets.US_ASCII));

    /** The legacy dataset the seeded identifiers were taken from. */
    private static final String FIXTURE_FILE = "custdata.txt";

    /** Width of one legacy customer record. */
    private static final int RECORD_WIDTH = 500;

    /** Zero-based offset of the government-issued identifier within the record. */
    private static final int IDENTIFIER_OFFSET = 288;

    /** Width of the government-issued identifier within the record. */
    private static final int IDENTIFIER_WIDTH = 20;

    /** Zero-based offset of the national identifier within the record. */
    private static final int NATIONAL_IDENTIFIER_OFFSET = 279;

    /** Width of the national identifier within the record. */
    private static final int NATIONAL_IDENTIFIER_WIDTH = 9;

    /**
     * The binding name every value in this column is sealed under, and the one this test opens with.
     *
     * <p>Not incidental. Every reader of the column in the module opens it through the field-bound
     * reveal, so a test that opened with the unbound form would pass over a seed no reader could use -
     * which is exactly how an unbound seed once reached a delivered database and made the account view
     * transaction fail on every row.
     */
    private static final String IDENTIFIER_FIELD =
            SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD;

    /**
     * Width of the payload a column-bound seal of one identifier produces: the binding name, the
     * one-character separator and the identifier.
     */
    private static final int BOUND_PAYLOAD_WIDTH =
            IDENTIFIER_FIELD.length() + 1 + IDENTIFIER_WIDTH;

    /**
     * The binding name the national identifier column is sealed under.
     *
     * <p>A separate binding from its sibling's, and that is the point: an envelope written for one
     * protected column must not open as the other's, which is what makes the two columns independent
     * rather than interchangeable.
     */
    private static final String NATIONAL_IDENTIFIER_FIELD =
            SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD;

    /** Width of the payload a column-bound seal of one national identifier produces. */
    private static final int NATIONAL_BOUND_PAYLOAD_WIDTH =
            NATIONAL_IDENTIFIER_FIELD.length() + 1 + NATIONAL_IDENTIFIER_WIDTH;

    /** Number of customer rows {@code V3__seed_reference_data.sql} loads. */
    private static final int SEEDED_CUSTOMERS = 50;

    /** Lowest seeded primary key. */
    private static final String FIRST_SEEDED_KEY = "000000001";

    /** Highest seeded primary key. */
    private static final String LAST_SEEDED_KEY = "000000050";

    /** Number of mapped columns on the customer table. */
    private static final int MAPPED_COLUMNS = 18;

    /** Position of {@code cust_ssn} in the projection below, zero-based. */
    private static final int SSN_COLUMN = 12;

    /** Position of {@code govt_issued_id} in the projection below, zero-based. */
    private static final int IDENTIFIER_COLUMN = 13;

    /**
     * All eighteen mapped columns in schema order, restricted to the range the seed occupies. The range
     * restriction matters: integration tests share one server, and {@code CustomerSsnEncryptionIT}
     * writes its own customer rows under a disjoint key prefix. Scoping here keeps every count below
     * exact regardless of execution order, and keeps this test from ever depending on another test's
     * cleanup.
     */
    private static final String SELECT_SEEDED_CUSTOMERS = String.format(Locale.ROOT, """
            SELECT cust_id, first_name, middle_name, last_name,
                   addr_line_1, addr_line_2, addr_line_3,
                   addr_state_cd, addr_country_cd, addr_zip,
                   phone_num_1, phone_num_2,
                   cust_ssn, govt_issued_id, cust_dob, eft_account_id,
                   pri_card_holder_ind, fico_credit_score
              FROM customer
             WHERE cust_id BETWEEN '%s' AND '%s'
             ORDER BY cust_id
            """, FIRST_SEEDED_KEY, LAST_SEEDED_KEY);

    /** The legacy records the seeded identifiers must open back to. */
    private static final SeededRecordFixture FIXTURE =
            SeededRecordFixture.load(FIXTURE_FILE, RECORD_WIDTH);

    /** The fixture key, as the test profile actually declares it. */
    private static final String CONFIGURED_KEY = fixtureKeyFromTestProfile();

    /** The service configured exactly as the test profile configures it. */
    private static final SensitiveFieldEncryptionService SERVICE =
            new SensitiveFieldEncryptionService(CONFIGURED_KEY);

    /** A service holding different key material of the same length. */
    private static final SensitiveFieldEncryptionService FOREIGN_SERVICE =
            new SensitiveFieldEncryptionService(FOREIGN_KEY);

    /** The seeded rows, read once because nothing here mutates them. */
    private static final List<SeededCustomer> SEEDED = readSeededCustomers();

    /**
     * One seeded customer row, as eighteen column values in schema order.
     *
     * @param ordinal the one-based position, taken from the numeric primary key so it indexes the
     *                fixture directly
     * @param columns the eighteen mapped column values, in schema order; both protected columns
     *                hold an {@code ENC1} envelope rather than cleartext
     */
    private record SeededCustomer(int ordinal, List<String> columns) {

        /**
         * Returns one column value.
         *
         * @param index the zero-based position in the projection
         * @return the value, possibly {@code null}
         */
        String column(final int index) {
            return columns.get(index);
        }

        /**
         * Returns the primary key.
         *
         * @return the customer identifier
         */
        String custId() {
            return column(0);
        }

        /**
         * Returns the stored, protected government-issued identifier.
         *
         * @return the stored envelope
         */
        String storedIdentifier() {
            return column(IDENTIFIER_COLUMN);
        }

        /**
         * Returns the sealed national identifier as the database holds it.
         *
         * @return the {@code ENC1} envelope, or {@code null} if a row carries none
         */
        String storedNationalIdentifier() {
            return column(SSN_COLUMN);
        }

        /**
         * Builds the domain entity from this row, optionally substituting the protected identifier.
         *
         * @param identifier the value to place in the protected attribute
         * @return the constructed entity
         */
        Customer toEntity(final String identifier) {
            return new Customer(
                    column(0), column(1), column(2), column(3),
                    column(4), column(5), column(6),
                    column(7), column(8), column(9),
                    column(10), column(11),
                    column(SSN_COLUMN), identifier, column(14), column(15),
                    column(16), column(17));
        }
    }

    /**
     * Reads the field-encryption key out of the test profile on this class path.
     *
     * <p>Read rather than restated so that rotating the profile without resealing the seed fails here
     * instead of at the first read of a protected column.</p>
     *
     * @return the Base64 key the test profile declares
     * @throws IllegalStateException if the document or the property is absent
     */
    private static String fixtureKeyFromTestProfile() {
        final Resource resource = new ClassPathResource(TEST_PROFILE_DOCUMENT);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    TEST_PROFILE_DOCUMENT + " is not on the class path, so the fixture key cannot be"
                            + " read; the seeded envelopes can only be opened under the key the test"
                            + " profile declares");
        }
        final List<PropertySource<?>> documents;
        try {
            documents = new YamlPropertySourceLoader().load(TEST_PROFILE_DOCUMENT, resource);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(TEST_PROFILE_DOCUMENT + " could not be read", unreadable);
        }
        for (final PropertySource<?> document : documents) {
            if (document instanceof EnumerablePropertySource<?> enumerable) {
                final Object value = enumerable.getProperty(KEY_PROPERTY);
                if (value != null) {
                    return value.toString();
                }
            }
        }
        throw new IllegalStateException(TEST_PROFILE_DOCUMENT + " declares no " + KEY_PROPERTY);
    }

    /**
     * Reads every seeded customer row from the migrated database.
     *
     * @return the seeded rows, ordered by primary key
     * @throws IllegalStateException if the rows cannot be read
     */
    private static List<SeededCustomer> readSeededCustomers() {
        final List<SeededCustomer> rows = new ArrayList<>();
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(SELECT_SEEDED_CUSTOMERS)) {
            while (resultSet.next()) {
                final List<String> values = new ArrayList<>(MAPPED_COLUMNS);
                for (int column = 1; column <= MAPPED_COLUMNS; column++) {
                    values.add(resultSet.getString(column));
                }
                rows.add(new SeededCustomer(
                        Integer.parseInt(values.get(0)), Collections.unmodifiableList(values)));
            }
        } catch (final SQLException unreadable) {
            throw new IllegalStateException("the seeded customer rows could not be read", unreadable);
        }
        return List.copyOf(rows);
    }

    /**
     * Counts customer rows in the seeded key range whose stored identifier matches a SQL pattern.
     *
     * @param predicate a SQL boolean expression over {@code govt_issued_id}
     * @return the matching row count
     * @throws SQLException if the count cannot be read
     */
    private static long countSeededWhere(final String predicate) throws SQLException {
        final String sql = "SELECT count(*) FROM customer WHERE cust_id BETWEEN '"
                + FIRST_SEEDED_KEY + "' AND '" + LAST_SEEDED_KEY + "' AND " + predicate;
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    /**
     * Returns the cleartext the legacy record holds for one seeded row.
     *
     * @param row the seeded row
     * @return the twenty characters at the identifier offset
     */
    private static String fixtureIdentifier(final SeededCustomer row) {
        return FIXTURE.field(row.ordinal(), IDENTIFIER_OFFSET, IDENTIFIER_WIDTH);
    }

    /**
     * Returns the national identifier the legacy record holds for one seeded row.
     *
     * @param row the seeded row
     * @return the nine characters at the national-identifier offset of that record
     */
    private static String fixtureNationalIdentifier(final SeededCustomer row) {
        return FIXTURE.field(row.ordinal(), NATIONAL_IDENTIFIER_OFFSET, NATIONAL_IDENTIFIER_WIDTH);
    }

    @Nested
    @DisplayName("the key the envelopes were sealed under")
    class TheConfiguredKey {

        @Test
        @DisplayName("is the one value the test profile declares, and it is what this test uses")
        void theConfiguredKeyIsTheDocumentedFixtureKey() {
            // Compared as FINGERPRINTS. An isEqualTo failure prints both operands, and both operands
            // here are AES-256 key material - the very value the fifty seeded envelopes are sealed
            // under. Equal values fingerprint equally and unequal values do not, so the comparison
            // loses no strength; what it loses is the ability to publish the key to a build log at
            // the moment a rotation goes wrong.
            assertThat(SensitiveValues.fingerprint(CONFIGURED_KEY))
                    .describedAs("%s declares %s; the fifty seeded envelopes in"
                            + " V3__seed_reference_data.sql were sealed under this exact value and"
                            + " cannot be regenerated, so rotating one without resealing the other"
                            + " leaves the seed unreadable. Configured %s, documented %s",
                            TEST_PROFILE_DOCUMENT, KEY_PROPERTY, SensitiveValues.describe(CONFIGURED_KEY),
                            SensitiveValues.describe(DOCUMENTED_FIXTURE_KEY))
                    .isEqualTo(SensitiveValues.fingerprint(DOCUMENTED_FIXTURE_KEY));
        }

        @Test
        @DisplayName("decodes to the thirty-two bytes AES-256 requires")
        void theConfiguredKeyIsThirtyTwoBytes() {
            assertThat(Base64.getDecoder().decode(CONFIGURED_KEY))
                    .describedAs("the field-encryption key must decode to exactly %d bytes",
                            SensitiveFieldCodec.KEY_LENGTH_BYTES)
                    .hasSize(SensitiveFieldCodec.KEY_LENGTH_BYTES);
        }
    }

    @Nested
    @DisplayName("what the database actually holds")
    class WhatIsStored {

        @Test
        @DisplayName("is fifty rows, one per fixture record, keyed 000000001 through 000000050")
        void theSeedLoadsFiftyRowsInFixtureOrder() {
            final List<Integer> everyOrdinal = new ArrayList<>(SEEDED_CUSTOMERS);
            for (int ordinal = 1; ordinal <= SEEDED_CUSTOMERS; ordinal++) {
                everyOrdinal.add(ordinal);
            }

            assertThat(SEEDED).hasSize(SEEDED_CUSTOMERS);
            assertThat(SEEDED).extracting(SeededCustomer::ordinal)
                    .describedAs("the seeded keys index the fixture directly")
                    .containsExactlyElementsOf(everyOrdinal);
            assertThat(SEEDED.getFirst().custId()).isEqualTo(FIRST_SEEDED_KEY);
            assertThat(SEEDED.getLast().custId()).isEqualTo(LAST_SEEDED_KEY);
            assertThat(FIXTURE.recordCount()).isEqualTo(SEEDED_CUSTOMERS);
        }

        @Test
        @DisplayName("is an envelope in every row, and a cleartext identifier in none")
        void everyStoredIdentifierIsSealedAndNoneIsCleartext() throws SQLException {
            assertThat(countSeededWhere("govt_issued_id LIKE 'ENC1:%'"))
                    .describedAs("every seeded row must hold a value carrying the %s marker",
                            SensitiveFieldCodec.ENVELOPE_PREFIX)
                    .isEqualTo(SEEDED_CUSTOMERS);
            assertThat(countSeededWhere("govt_issued_id ~ '^[0-9]{1,20}$'"))
                    .describedAs("no seeded row may hold a bare run of digits - that is exactly what"
                            + " the defective seed stored")
                    .isZero();
        }

        @Test
        @DisplayName("is accepted as protected by the same predicate the entity applies")
        void everyStoredIdentifierHasTheEnvelopeShape() {
            for (final SeededCustomer row : SEEDED) {
                assertThat(SERVICE.isProtected(row.storedIdentifier()))
                        .describedAs("customer %s must hold a protected value", row.custId())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("is exactly the length the codec predicts for a column-bound twenty-character "
                + "payload")
        void everyStoredIdentifierMeasuresThePredictedLength() {
            final int predicted = SensitiveFieldCodec.envelopeLengthFor(BOUND_PAYLOAD_WIDTH);
            for (final SeededCustomer row : SEEDED) {
                // The LENGTH is asserted, never the envelope. hasSize prints the whole subject on
                // failure, and the subject is a sealed identifier.
                assertThat(row.storedIdentifier().length())
                        .describedAs("customer %s: a different length means the literal was altered,"
                                + " truncated by a wrapped line, or sealed over the wrong payload."
                                + " Stored %s", row.custId(),
                                SensitiveValues.describe(row.storedIdentifier()))
                        .isEqualTo(predicted);
            }
        }

        @Test
        @DisplayName("is fifty different envelopes, never one value repeated")
        void theStoredIdentifiersAreAllDistinct() {
            // Distinctness proven over FINGERPRINTS. doesNotHaveDuplicates names the duplicated
            // member in its failure, which would have printed a sealed envelope - and printed it
            // twice, since a duplicate is by definition two of them.
            final List<String> sealed = SEEDED.stream()
                    .map(SeededCustomer::storedIdentifier)
                    .toList();
            assertThat(SensitiveValues.fingerprints(sealed))
                    .describedAs("a repeated envelope would give two customers the same identifier,"
                            + " and a fresh initialisation vector per call makes repetition impossible"
                            + " unless a row was filled by copying its neighbour")
                    .hasSize(SEEDED_CUSTOMERS)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("seals the national identifier too, in every row, at the length its own bound "
                + "payload predicts")
        void theNationalIdentifierIsSeededSealedAndDistinct() throws SQLException {
            // THIS TEST ASSERTED THE OPPOSITE, and the reasoning it carried was that sealing the
            // value "under a committed fixture key would make it recoverable from the repository".
            // The premise is true; the conclusion never followed. The same nine bytes are already
            // committed in cleartext at app/data/ASCII/custdata.txt - the read-only parity baseline -
            // and again at src/test/resources/fixtures/input/custdata.txt, which this module committed
            // itself and which THIS TEST READS to build its expectation. Anyone holding the repository
            // holds the values without a key, so absence protected nothing; what it did cost is that
            // no seeded row exercised a stored national identifier at all.
            assertThat(countSeededWhere("cust_ssn IS NULL"))
                    .describedAs("no seeded row may leave the column empty now that the seed carries a"
                            + " value for every one of them")
                    .isZero();
            assertThat(countSeededWhere("cust_ssn LIKE 'ENC1:%'"))
                    .describedAs("every row must hold an envelope, never the cleartext the fixture"
                            + " record holds at offset %d", NATIONAL_IDENTIFIER_OFFSET)
                    .isEqualTo(SEEDED_CUSTOMERS);
            assertThat(countSeededWhere("cust_ssn ~ '^[0-9]{1,9}$'"))
                    .describedAs("a bare run of digits in this column is the defect the envelope exists"
                            + " to prevent")
                    .isZero();

            final int predicted = SensitiveFieldCodec.envelopeLengthFor(NATIONAL_BOUND_PAYLOAD_WIDTH);
            final List<String> sealed = new ArrayList<>(SEEDED_CUSTOMERS);
            for (final SeededCustomer row : SEEDED) {
                // The LENGTH is asserted, never the envelope, for the reason the sibling test states.
                assertThat(row.storedNationalIdentifier().length())
                        .describedAs("customer %s: a different length means the literal was altered,"
                                + " truncated by a wrapped line, or sealed over the wrong payload."
                                + " Stored %s", row.custId(),
                                SensitiveValues.describe(row.storedNationalIdentifier()))
                        .isEqualTo(predicted);
                sealed.add(row.storedNationalIdentifier());
            }
            assertThat(SensitiveValues.fingerprints(sealed))
                    .describedAs("a repeated envelope would give two customers the same national"
                            + " identifier")
                    .hasSize(SEEDED_CUSTOMERS)
                    .doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("opening what the database holds")
    class Recovery {

        @Test
        @DisplayName("returns, for every row, the twenty characters the fixture record holds")
        void everyStoredIdentifierOpensToItsFixtureValue() {
            for (final SeededCustomer row : SEEDED) {
                final String expected = fixtureIdentifier(row);
                final String revealed = SERVICE.reveal(IDENTIFIER_FIELD, row.storedIdentifier());
                // Fingerprints again, and here the value at stake is the DECRYPTED national
                // identifier - the one thing this whole file exists to keep sealed. A failing
                // isEqualTo on the cleartext would have printed both the value that was recovered and
                // the fixture value it should have matched.
                assertThat(SensitiveValues.fingerprint(revealed))
                        .describedAs("customer %s must open to %s[%d] offset %d width %d; opened to %s,"
                                + " fixture holds %s", row.custId(), FIXTURE_FILE, row.ordinal(),
                                IDENTIFIER_OFFSET, IDENTIFIER_WIDTH, SensitiveValues.describe(revealed),
                                SensitiveValues.describe(expected))
                        .isEqualTo(SensitiveValues.fingerprint(expected));
            }
        }

        @Test
        @DisplayName("returns, for every row, the nine characters the fixture record holds as the "
                + "national identifier")
        void everyStoredNationalIdentifierOpensToItsFixtureValue() {
            for (final SeededCustomer row : SEEDED) {
                final String expected = fixtureNationalIdentifier(row);
                final String revealed =
                        SERVICE.reveal(NATIONAL_IDENTIFIER_FIELD, row.storedNationalIdentifier());
                // Fingerprints, for the reason the sibling test states: a failing isEqualTo on the
                // cleartext would print the recovered national identifier and the fixture value both.
                assertThat(SensitiveValues.fingerprint(revealed))
                        .describedAs("customer %s must open to %s[%d] offset %d width %d; opened to %s,"
                                + " fixture holds %s", row.custId(), FIXTURE_FILE, row.ordinal(),
                                NATIONAL_IDENTIFIER_OFFSET, NATIONAL_IDENTIFIER_WIDTH,
                                SensitiveValues.describe(revealed),
                                SensitiveValues.describe(expected))
                        .isEqualTo(SensitiveValues.fingerprint(expected));
            }
        }

        @Test
        @DisplayName("refuses to open a national identifier under the sibling column's binding")
        void openingTheNationalIdentifierUnderTheSiblingBindingIsRefused() {
            final String stored = SEEDED.getFirst().storedNationalIdentifier();

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("the two protected columns are independent, and an envelope written for one"
                            + " must not read as the other's")
                    .isThrownBy(() -> SERVICE.reveal(IDENTIFIER_FIELD, stored))
                    .withMessageContaining("field binding");
        }

        @Test
        @DisplayName("returns values of the legacy width, all fifty of them different")
        void theRecoveredIdentifiersKeepTheLegacyWidthAndRemainDistinct() {
            final List<String> recovered = new ArrayList<>(SEEDED_CUSTOMERS);
            for (final SeededCustomer row : SEEDED) {
                recovered.add(SERVICE.reveal(IDENTIFIER_FIELD, row.storedIdentifier()));
            }
            assertThat(SensitiveValues.fingerprints(recovered))
                    .describedAs("fifty rows must open to fifty different identifiers")
                    .hasSize(SEEDED_CUSTOMERS)
                    .doesNotHaveDuplicates();
            assertThat(recovered).allSatisfy(value -> assertThat(value.length())
                    .describedAs("the legacy field is %d characters wide; opened to %s",
                            IDENTIFIER_WIDTH, SensitiveValues.describe(value))
                    .isEqualTo(IDENTIFIER_WIDTH));
        }

        @Test
        @DisplayName("fails under different key material rather than returning something plausible")
        void openingUnderAForeignKeyIsRefused() {
            final String stored = SEEDED.getFirst().storedIdentifier();
            assertThatExceptionOfType(IllegalStateException.class)
                    .describedAs("authentication is key-bound, so a wrong key must fail loudly")
                    .isThrownBy(() -> FOREIGN_SERVICE.reveal(IDENTIFIER_FIELD, stored));
        }

        @Test
        @DisplayName("fails when a single character of a stored envelope is altered")
        void openingAnAlteredEnvelopeIsRefused() {
            final String stored = SEEDED.getFirst().storedIdentifier();
            final int bodyStart = SensitiveFieldCodec.ENVELOPE_PREFIX.length();
            final char first = stored.charAt(bodyStart);
            final char replacement = first == 'A' ? 'B' : 'A';
            final String altered = stored.substring(0, bodyStart) + replacement
                    + stored.substring(bodyStart + 1);

            assertThat(altered).hasSameSizeAs(stored).isNotEqualTo(stored);
            assertThatExceptionOfType(IllegalStateException.class)
                    .describedAs("the trailing tag authenticates the whole envelope, so one altered"
                            + " character must fail rather than yield corrupted cleartext")
                    .isThrownBy(() -> SERVICE.reveal(IDENTIFIER_FIELD, altered));
        }
    }

    @Nested
    @DisplayName("the domain contract the defective seed violated")
    class TheDomainContract {

        @Test
        @DisplayName("accepts every seeded row as a Customer")
        void everySeededRowConstructsAsADomainEntity() {
            for (final SeededCustomer row : SEEDED) {
                final Customer customer = row.toEntity(row.storedIdentifier());
                assertThat(SensitiveValues.fingerprint(customer.getGovtIssuedId()))
                        .describedAs("customer %s must survive its own entity's guard; entity holds %s,"
                                + " seed holds %s", row.custId(),
                                SensitiveValues.describe(customer.getGovtIssuedId()),
                                SensitiveValues.describe(row.storedIdentifier()))
                        .isEqualTo(SensitiveValues.fingerprint(row.storedIdentifier()));
                assertThat(SensitiveValues.fingerprint(
                                SERVICE.reveal(IDENTIFIER_FIELD, customer.getGovtIssuedId())))
                        .describedAs("and the entity's own value must still open to the fixture's, which"
                                + " is compared by fingerprint because both sides are cleartext")
                        .isEqualTo(SensitiveValues.fingerprint(fixtureIdentifier(row)));
            }
        }

        @Test
        @DisplayName("refuses the cleartext the fixture record holds, which is what the seed carried")
        void theFixtureCleartextIsRefusedByTheEntity() {
            final SeededCustomer row = SEEDED.getFirst();
            final String cleartext = fixtureIdentifier(row);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .describedAs("the entity has always refused cleartext here; the seed reached the"
                            + " column without passing through it")
                    .isThrownBy(() -> row.toEntity(cleartext))
                    .withMessageContaining("govtIssuedId");
        }

        @Test
        @DisplayName("accepts a value the application seals for itself under the same key")
        void aFreshlySealedValueIsAlsoAccepted() {
            final SeededCustomer row = SEEDED.getFirst();
            final String cleartext = fixtureIdentifier(row);
            final String resealed = SERVICE.protect(IDENTIFIER_FIELD, cleartext);

            assertThat(resealed)
                    .describedAs("a fresh initialisation vector makes every seal different, which is"
                            + " why the seeded literals cannot be regenerated")
                    .isNotEqualTo(row.storedIdentifier());
            assertThatCode(() -> row.toEntity(resealed)).doesNotThrowAnyException();
            assertThat(SERVICE.reveal(IDENTIFIER_FIELD, resealed)).isEqualTo(cleartext);
        }
    }
}
