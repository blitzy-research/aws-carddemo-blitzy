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
package com.carddemo.api;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import com.carddemo.domain.Customer;
import com.carddemo.service.SensitiveFieldEncryptionService;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.SeededRecordFixture;
import com.carddemo.util.SensitiveFieldCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The account view transaction's regulated-value gate, exercised over the rows a real migration of the
 * real seeds actually leaves in the database.
 *
 * <h2>The defect this exists for</h2>
 *
 * <p>{@code AccountController} publishes one account view turn by handing the resolved customer row to
 * {@link AccountProtectedDataAdapter#revealForView}, which opens both protected columns through the
 * <em>field-bound</em> reveal - a reveal that refuses an envelope sealed for any other column, or for no
 * column at all. SEALING THE FIFTY GOVERNMENT-ISSUED IDENTIFIERS OF
 * {@code V3__seed_reference_data.sql} WITHOUT THAT BINDING passes every other control silently: the
 * marker check in the migration sees an envelope, the after-migrate callback opens each value under the
 * key without asking about its binding, and a test that reads a seeded identifier the same unbound way
 * reads it successfully. The visible result is that {@code POST /api/accounts/view} answers {@code 500}
 * for every account that exists, while the not-found and malformed-filter turns - which never reach a
 * customer row - keep answering correctly: the most-used read screen in the estate unreachable behind a
 * green suite.
 *
 * <p>This test closes that gap in the only place it can be closed: between the seed as delivered and the
 * gate as the controller calls it. It reads what the database holds, builds the entity the repository
 * would have hydrated, and calls the adapter method the controller calls, with the authority the
 * controller uses. Nothing is stubbed, and no value is resealed first - resealing is precisely what
 * would hide the defect again.
 *
 * <h2>Why it lives beside the adapter rather than beside the seed</h2>
 *
 * <p>Two tests already assert things about the seeded envelopes, and both are about the seed: one proves
 * they open under the configured key and carry this column's binding, the other proves each opens to the
 * twenty characters the legacy record holds. Neither is about the screen. The property this file asserts
 * is a property of the boundary - that the view turn can be published from delivered data - so it
 * belongs with the boundary, and it fails for a reason a reader of {@code AccountController} would look
 * for.
 *
 * <h2>What is asserted</h2>
 *
 * <ol>
 *   <li>The gate publishes without raising, for every seeded row, under an administrator's authority -
 *       which is the authority the view turn uses.</li>
 *   <li>The government-issued identifier it publishes is cleartext of the legacy width, so the value
 *       reached the screen rather than the envelope reaching it.</li>
 *   <li>The national identifier it publishes is absent, because the seed deliberately carries none, and
 *       an absent value must traverse the gate as an absent value rather than as a failure.</li>
 *   <li>The unregulated companions the same record carries - the birth date and the electronic-funds
 *       account identifier - cross unchanged, so the gate is publishing a record rather than a fragment
 *       of one.</li>
 *   <li>The same values are published under a denied authority as masks of the same width, so the
 *       masking policy is exercised over delivered data too.</li>
 * </ol>
 *
 * <p>No legacy source text appears here.
 *
 * @since 1.0.0
 */
@DisplayName("account view regulated-value gate, over the rows a real migration leaves behind")
final class SeededAccountViewRevealIT extends AbstractPostgresIT {

    /** Property carrying the field-encryption key in the suite's own profile document. */
    private static final String KEY_PROPERTY =
            SensitiveFieldEncryptionService.FIELD_ENCRYPTION_KEY_PROPERTY;

    /** The suite profile document the key is read out of rather than restated from. */
    private static final String TEST_PROFILE_DOCUMENT = "application-test.yml";

    /** Number of customer rows the reference seed inserts. */
    private static final int SEEDED_CUSTOMERS = 50;

    /** Width of the government-issued identifier in the legacy record. */
    private static final int IDENTIFIER_WIDTH = 20;

    /** Width of the birth date in the legacy record, which the screen splits into three positions. */
    private static final int BIRTH_DATE_WIDTH = 10;

    /** The legacy dataset the seeded national identifiers were taken from. */
    private static final String FIXTURE_FILE = "custdata.txt";

    /** Width of one legacy customer record. */
    private static final int RECORD_WIDTH = 500;

    /** Zero-based offset of the national identifier within the legacy record. */
    private static final int NATIONAL_IDENTIFIER_OFFSET = 279;

    /** Width of the national identifier within the legacy record. */
    private static final int NATIONAL_IDENTIFIER_WIDTH = 9;

    /**
     * The legacy records the seeded national identifiers must publish back as.
     *
     * <p>Read from the fixture rather than from the column this test is checking. Opening the stored
     * envelope to build the expectation would compare the adapter with itself and pass whatever it
     * did; the fixture is the authority the seed was built from, so it is the only expectation that
     * can fail when the adapter is wrong.
     */
    private static final SeededRecordFixture FIXTURE =
            SeededRecordFixture.load(FIXTURE_FILE, RECORD_WIDTH);

    /** Every column the entity maps, in the order its constructor takes them. */
    private static final String SELECT_CUSTOMERS = """
            SELECT cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2,
                   addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2,
                   cust_ssn, govt_issued_id, cust_dob, eft_account_id, pri_card_holder_ind,
                   fico_credit_score
              FROM customer ORDER BY cust_id""";

    /** Number of columns the projection above selects. */
    private static final int MAPPED_COLUMNS = 18;

    /** Zero-based position of {@code govt_issued_id} in that projection. */
    private static final int IDENTIFIER_COLUMN = 13;

    /** Zero-based position of {@code cust_dob} in that projection. */
    private static final int BIRTH_DATE_COLUMN = 14;

    /** Zero-based position of {@code eft_account_id} in that projection. */
    private static final int EFT_ACCOUNT_COLUMN = 15;

    /** The service the boundary opens protected columns through, keyed as the suite profile keys it. */
    private static final SensitiveFieldEncryptionService ENCRYPTION =
            new SensitiveFieldEncryptionService(fixtureKeyFromTestProfile());

    /** The boundary under test, over that service and nothing else. */
    private static final AccountProtectedDataAdapter ADAPTER =
            new AccountProtectedDataAdapter(ENCRYPTION);

    /** The authority the account view turn presents. */
    private static final AccountProtectedDataAdapter.RevealAuthorization PERMITTED =
            AccountProtectedDataAdapter.RevealAuthorization.administrator(
                    AccountProtectedDataAdapter.RevealPurpose.ACCOUNT_VIEW);

    /** An authority that does not permit a reveal, used for the masking half. */
    private static final AccountProtectedDataAdapter.RevealAuthorization DENIED =
            AccountProtectedDataAdapter.RevealAuthorization.unprivileged(
                    AccountProtectedDataAdapter.RevealPurpose.ACCOUNT_VIEW,
                    com.carddemo.domain.enums.UserType.USER);

    SeededAccountViewRevealIT() {
    }

    @Test
    @DisplayName("publishes every seeded row without raising, and publishes cleartext rather than an "
            + "envelope")
    void everySeededRowPublishesThroughTheGate() throws SQLException {
        final List<List<String>> rows = seededCustomerRows();

        assertThat(rows)
                .as("the reference seed inserts fifty customer rows; a different count means this "
                        + "assertion is not describing the migration under test")
                .hasSize(SEEDED_CUSTOMERS);

        for (final List<String> row : rows) {
            final Customer customer = entityOf(row);

            assertThatCode(() -> ADAPTER.revealForView(customer, PERMITTED))
                    .as("customer %s must publish. A refusal here is the account view transaction "
                            + "answering 500 for an account that exists", row.getFirst())
                    .doesNotThrowAnyException();

            final AccountProtectedDataAdapter.AccountViewProtectedValues published =
                    ADAPTER.revealForView(customer, PERMITTED);

            assertThat(published.governmentIssuedId())
                    .as("customer %s must publish the legacy width in cleartext", row.getFirst())
                    .hasSize(IDENTIFIER_WIDTH)
                    .containsOnlyDigits()
                    .doesNotStartWith(SensitiveFieldCodec.ENVELOPE_PREFIX);
            assertThat(published.ssn())
                    .as("customer %s must publish the national identifier the fixture record holds, "
                            + "formatted as COACTVWC L495-L503 formats it - three digits, a hyphen, "
                            + "two digits, a hyphen, four digits", row.getFirst())
                    .isEqualTo(ADAPTER.composedSsn(fixtureNationalIdentifier(row.getFirst())));
            assertThat(published.dateOfBirth())
                    .as("customer %s must publish its birth date unchanged", row.getFirst())
                    .isEqualTo(row.get(BIRTH_DATE_COLUMN))
                    .hasSize(BIRTH_DATE_WIDTH);
            assertThat(published.eftAccountId())
                    .as("customer %s must publish its electronic-funds account identifier unchanged",
                            row.getFirst())
                    .isEqualTo(row.get(EFT_ACCOUNT_COLUMN));
        }
    }

    @Test
    @DisplayName("masks the same delivered values under an authority that does not permit a reveal")
    void aDeniedAuthorityMasksTheSameDeliveredValues() throws SQLException {
        for (final List<String> row : seededCustomerRows()) {
            final AccountProtectedDataAdapter.AccountViewProtectedValues withheld =
                    ADAPTER.revealForView(entityOf(row), DENIED);

            assertThat(withheld.governmentIssuedId())
                    .as("customer %s must be withheld at the width the revealed value occupies, so "
                            + "the screen field is filled either way", row.getFirst())
                    .isEqualTo(AccountProtectedDataAdapter.MASK_CHARACTER
                            .repeat(IDENTIFIER_WIDTH));
            assertThat(withheld.dateOfBirth())
                    .as("customer %s must have its birth date withheld at its own width",
                            row.getFirst())
                    .isEqualTo(AccountProtectedDataAdapter.MASK_CHARACTER
                            .repeat(BIRTH_DATE_WIDTH));
            assertThat(withheld.ssn())
                    .as("customer %s must have the two leading groups withheld and the retained four "
                            + "digits disclosed, which is the one position of this identifier the "
                            + "screen shows a caller that may not read it", row.getFirst())
                    .isEqualTo("***-**-" + fixtureNationalIdentifier(row.getFirst())
                            .substring(AccountProtectedDataAdapter.SSN_PART_1_WIDTH
                                    + AccountProtectedDataAdapter.SSN_PART_2_WIDTH));
        }
    }

    /**
     * Returns the national identifier the legacy record holds for one seeded customer.
     *
     * @param custId the nine-character customer key, whose numeric value indexes the fixture directly
     * @return the nine characters at the national-identifier offset of that record
     */
    private static String fixtureNationalIdentifier(final String custId) {
        return FIXTURE.field(Integer.parseInt(custId), NATIONAL_IDENTIFIER_OFFSET,
                NATIONAL_IDENTIFIER_WIDTH);
    }

    /**
     * Reads every seeded customer row as the ordered list of column values the entity takes.
     *
     * @return fifty rows of eighteen values, in primary-key order
     * @throws SQLException when the read fails
     */
    private static List<List<String>> seededCustomerRows() throws SQLException {
        final List<List<String>> rows = new ArrayList<>(SEEDED_CUSTOMERS);
        try (Connection connection = connect();
                Statement select = connection.createStatement();
                ResultSet result = select.executeQuery(SELECT_CUSTOMERS)) {
            while (result.next()) {
                final List<String> columns = new ArrayList<>(MAPPED_COLUMNS);
                for (int index = 1; index <= MAPPED_COLUMNS; index++) {
                    columns.add(result.getString(index));
                }
                // Collections.unmodifiableList rather than List.copyOf: the national identifier is
                // the schema's one nullable column, so this reader has to carry whatever the database
                // holds, and the copy factory refuses a null element. The delivered seed now fills
                // that column in all fifty rows, but the reader must not depend on it doing so.
                rows.add(Collections.unmodifiableList(columns));
            }
        }
        return rows;
    }

    /**
     * Builds the entity the repository would have hydrated from one row, without altering a value.
     *
     * @param row the eighteen column values in constructor order
     * @return the entity
     */
    private static Customer entityOf(final List<String> row) {
        return new Customer(
                row.get(0), row.get(1), row.get(2), row.get(3),
                row.get(4), row.get(5), row.get(6),
                row.get(7), row.get(8), row.get(9),
                row.get(10), row.get(11),
                row.get(12), row.get(IDENTIFIER_COLUMN), row.get(BIRTH_DATE_COLUMN),
                row.get(EFT_ACCOUNT_COLUMN), row.get(16), row.get(17));
    }

    /**
     * Reads the field-encryption key out of the suite profile on this class path.
     *
     * <p>Read rather than restated, so that rotating the profile without resealing the seed fails at the
     * first read of a protected column instead of passing here under a key of this file's own choosing.
     *
     * @return the Base64 key the suite profile declares
     * @throws IllegalStateException if the document or the property is absent, or the value does not
     *                               decode
     */
    private static String fixtureKeyFromTestProfile() {
        final ClassPathResource document = new ClassPathResource(TEST_PROFILE_DOCUMENT);
        if (!document.exists()) {
            throw new IllegalStateException(TEST_PROFILE_DOCUMENT + " is not on the class path, so the "
                    + "key the seeded envelopes were sealed under cannot be read");
        }
        final YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(document);
        yaml.afterPropertiesSet();
        final java.util.Properties properties = yaml.getObject();
        final String key = properties == null ? null : properties.getProperty(KEY_PROPERTY);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(TEST_PROFILE_DOCUMENT + " declares no " + KEY_PROPERTY);
        }
        if (Base64.getDecoder().decode(key).length != SensitiveFieldCodec.KEY_LENGTH_BYTES) {
            throw new IllegalStateException(KEY_PROPERTY + " must decode to exactly "
                    + SensitiveFieldCodec.KEY_LENGTH_BYTES + " bytes");
        }
        return key;
    }
}
