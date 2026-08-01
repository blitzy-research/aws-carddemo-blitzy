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
package com.carddemo.domain;

import java.util.ArrayList;
import java.util.List;

import com.carddemo.support.SchemaColumnCatalog;
import com.carddemo.support.SeededRecordFixture;
import com.carddemo.util.SensitiveFieldCodec;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link Customer}, the five-hundred-byte customer record.
 *
 * <p><strong>The layout being preserved.</strong> {@code app/cpy/CVCUS01Y.cpy} declares nineteen fields
 * over five hundred bytes: a nine-digit identifier, three twenty-five-byte names, three fifty-byte
 * address lines, a two-byte state, a three-byte country, a ten-byte postal code, two fifteen-byte
 * telephone numbers, a nine-digit social security number, a twenty-byte government identifier, a
 * ten-byte date of birth, a ten-byte electronic-transfer account, a one-byte primary-cardholder
 * indicator, a three-digit credit score and a hundred-and-sixty-eight-byte filler. The cluster definition
 * at {@code app/jcl/CUSTFILE.jcl} agrees: {@code KEYS(9 0)} and {@code RECORDSIZE(500 500)}.
 *
 * <p><strong>Why the credit score is carried as text and not as a number.</strong> The copybook declares
 * it {@code PIC 9(03)}, which is a three-character field holding digits, so a score below one hundred is
 * stored with leading zeros. Seven of the fifty seeded customers have such a score, one of them as low as
 * {@code 001}. An integer field would render that score as a single character and the record would no
 * longer be five hundred bytes when written back out, so the text form is the faithful one and this suite
 * asserts the leading zeros survive.
 *
 * <p><strong>Why the score is not range-checked here.</strong> The account-update screen enforces the
 * three-hundred to eight-hundred-and-fifty band, but the record itself carries whatever the file holds
 * &mdash; and twenty-one of the fifty seeded customers sit below three hundred. An entity that rejected
 * those values could not read the estate's own data, so the entity validates nothing and this suite
 * proves it accepts the seeded values as they are.
 *
 * <p><strong>Why two fields are never validated anywhere.</strong> The account-update program decorates
 * the middle name and the second address line for error display but never checks them, saying so in its
 * own comments. Adding a constraint to either would reject input the legacy accepts, so neither carries
 * one. This suite asserts both accept a blank value.
 *
 * <p><strong>Two privacy departures, and one gap left open.</strong> The social security number is
 * cleartext in the legacy record. It is the one column the migration declares nullable and the one
 * declared far wider than its nine source bytes, because a ciphertext is longer than its plaintext and an
 * unset value must be distinguishable from an empty one. The suite asserts both properties. Separately,
 * the entity declares no diagnostic string at all, so the number cannot escape through one; the suite
 * proves that by asserting the inherited description carries no field value. The gap the legacy leaves
 * open &mdash; that the card number and verification code have no field-level protection anywhere in the
 * design &mdash; is recorded in the decision log and is not closed here, because closing it was not
 * asked for.
 *
 * <p><strong>Deliberately not asserted.</strong> Nothing here checks a state code, an area code or a
 * postal prefix against the permitted lists; those live in the lookup service and are verified there.
 * Nothing checks a date of birth, which the date-validation service owns.
 */
@DisplayName("Customer — the five-hundred-byte customer record")
class CustomerTest {

    /** Relational table the entity maps to. */
    private static final String TABLE = "customer";

    /** Fixture holding the fifty seeded customer records. */
    private static final String FIXTURE_FILE = "custdata.txt";

    /** {@code RECORDSIZE(500 500)} in the cluster definition. */
    private static final int RECORD_WIDTH = 500;

    /** {@code KEYS(9 0)} — key length. */
    private static final int KEY_WIDTH = 9;

    /** Width of the unmapped trailing filler. */
    private static final int FILLER_WIDTH = 168;

    /** Records the seeded customer fixture holds. */
    private static final int SEEDED_RECORDS = 50;

    /** Seeded customers whose credit score carries a leading zero. */
    private static final int SEEDED_SCORES_WITH_LEADING_ZERO = 7;

    /** Seeded customers whose credit score is below the screen's lower bound of three hundred. */
    private static final int SEEDED_SCORES_BELOW_SCREEN_MINIMUM = 21;

    /** Lower bound the account-update screen enforces, which the record itself does not. */
    private static final int SCREEN_SCORE_MINIMUM = 300;

    /** The nineteen copybook widths, in declaration order. */
    private static final List<Integer> COPYBOOK_WIDTHS = List.of(
            9, 25, 25, 25, 50, 50, 50, 2, 3, 10, 15, 15, 9, 20, 10, 10, 1, 3, 168);

    /** Zero-based offset of each mapped field, derived from the copybook widths. */
    private static final int OFFSET_CUST_ID = 0;
    private static final int OFFSET_FIRST_NAME = 9;
    private static final int OFFSET_MIDDLE_NAME = 34;
    private static final int OFFSET_LAST_NAME = 59;
    private static final int OFFSET_ADDR_LINE_1 = 84;
    private static final int OFFSET_ADDR_LINE_2 = 134;
    private static final int OFFSET_ADDR_LINE_3 = 184;
    private static final int OFFSET_ADDR_STATE_CD = 234;
    private static final int OFFSET_ADDR_COUNTRY_CD = 236;
    private static final int OFFSET_ADDR_ZIP = 239;
    private static final int OFFSET_PHONE_NUM_1 = 249;
    private static final int OFFSET_PHONE_NUM_2 = 264;
    private static final int OFFSET_CUST_SSN = 279;
    private static final int OFFSET_GOVT_ISSUED_ID = 288;
    private static final int OFFSET_CUST_DOB = 308;
    private static final int OFFSET_EFT_ACCOUNT_ID = 318;
    private static final int OFFSET_PRI_CARD_HOLDER_IND = 328;
    private static final int OFFSET_FICO_CREDIT_SCORE = 329;
    private static final int OFFSET_FILLER = 332;

    /** The migration's customer table, parsed once. */
    private static final SchemaColumnCatalog SCHEMA = SchemaColumnCatalog.load();

    /** The seeded customer fixture, loaded once. */
    private static final SeededRecordFixture FIXTURE =
            SeededRecordFixture.load(FIXTURE_FILE, RECORD_WIDTH);

    /**
     * A thirty-two-byte key used only to manufacture and read back well-formed protected values.
     *
     * <p>The entity refuses any value on its two regulated attributes that does not carry the
     * module's protected-value envelope, so a test cannot hand it the legacy cleartext directly. The
     * key exists to seal the seeded bytes on the way in and to recover them for assertion; it
     * protects nothing real and is not a deployment secret.
     */
    private static final byte[] PROTECTION_KEY =
            "carddemo-customer-test-key-0123!".getBytes(StandardCharsets.UTF_8);

    /**
     * Seals a cleartext value into the envelope shape the entity accepts.
     *
     * @param cleartext the legacy cleartext value, or {@code null}
     * @return the sealed value, or {@code null} when nothing was supplied
     */
    private static String sealed(final String cleartext) {
        return cleartext == null ? null : SensitiveFieldCodec.protect(cleartext, PROTECTION_KEY);
    }

    /**
     * Recovers the cleartext a stored protected value carries.
     *
     * @param envelope the value read off the entity
     * @return the cleartext it protects
     */
    private static String revealed(final String envelope) {
        return SensitiveFieldCodec.reveal(envelope, PROTECTION_KEY);
    }

    /**
     * Builds a customer from one seeded record by slicing the copybook offsets.
     *
     * @param ordinal the one-based record position
     * @return the customer the record describes
     */
    private static Customer customerFromSeededRecord(final int ordinal) {
        return new Customer(
                FIXTURE.field(ordinal, OFFSET_CUST_ID, KEY_WIDTH),
                FIXTURE.field(ordinal, OFFSET_FIRST_NAME, 25),
                FIXTURE.field(ordinal, OFFSET_MIDDLE_NAME, 25),
                FIXTURE.field(ordinal, OFFSET_LAST_NAME, 25),
                FIXTURE.field(ordinal, OFFSET_ADDR_LINE_1, 50),
                FIXTURE.field(ordinal, OFFSET_ADDR_LINE_2, 50),
                FIXTURE.field(ordinal, OFFSET_ADDR_LINE_3, 50),
                FIXTURE.field(ordinal, OFFSET_ADDR_STATE_CD, 2),
                FIXTURE.field(ordinal, OFFSET_ADDR_COUNTRY_CD, 3),
                FIXTURE.field(ordinal, OFFSET_ADDR_ZIP, 10),
                FIXTURE.field(ordinal, OFFSET_PHONE_NUM_1, 15),
                FIXTURE.field(ordinal, OFFSET_PHONE_NUM_2, 15),
                sealed(FIXTURE.field(ordinal, OFFSET_CUST_SSN, 9)),
                sealed(FIXTURE.field(ordinal, OFFSET_GOVT_ISSUED_ID, 20)),
                FIXTURE.field(ordinal, OFFSET_CUST_DOB, 10),
                FIXTURE.field(ordinal, OFFSET_EFT_ACCOUNT_ID, 10),
                FIXTURE.field(ordinal, OFFSET_PRI_CARD_HOLDER_IND, 1),
                FIXTURE.field(ordinal, OFFSET_FICO_CREDIT_SCORE, 3));
    }

    // =================================================================================================
    // RECORD LAYOUT
    // =================================================================================================

    /**
     * Verifies the copybook geometry the entity has to honour.
     */
    @Nested
    @DisplayName("record layout")
    class RecordLayout {

        @Test
        @DisplayName("the nineteen copybook widths sum to the five hundred bytes the cluster declares")
        void theWidthsSumToTheRecordSize() {
            assertThat(COPYBOOK_WIDTHS).hasSize(19);
            assertThat(COPYBOOK_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("the eighteen mapped fields end where the filler begins")
        void theMappedFieldsEndWhereTheFillerBegins() {
            assertThat(COPYBOOK_WIDTHS.stream().mapToInt(Integer::intValue).sum() - FILLER_WIDTH)
                    .isEqualTo(OFFSET_FILLER);
            assertThat(OFFSET_FILLER + FILLER_WIDTH).isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("each mapped field starts where the preceding widths leave off")
        void eachFieldStartsWhereThePrecedingWidthsLeaveOff() {
            final List<Integer> expectedOffsets = List.of(
                    OFFSET_CUST_ID, OFFSET_FIRST_NAME, OFFSET_MIDDLE_NAME, OFFSET_LAST_NAME,
                    OFFSET_ADDR_LINE_1, OFFSET_ADDR_LINE_2, OFFSET_ADDR_LINE_3, OFFSET_ADDR_STATE_CD,
                    OFFSET_ADDR_COUNTRY_CD, OFFSET_ADDR_ZIP, OFFSET_PHONE_NUM_1, OFFSET_PHONE_NUM_2,
                    OFFSET_CUST_SSN, OFFSET_GOVT_ISSUED_ID, OFFSET_CUST_DOB, OFFSET_EFT_ACCOUNT_ID,
                    OFFSET_PRI_CARD_HOLDER_IND, OFFSET_FICO_CREDIT_SCORE, OFFSET_FILLER);

            int running = 0;
            for (int index = 0; index < COPYBOOK_WIDTHS.size(); index++) {
                assertThat(running)
                        .as("offset of field %d", index + 1)
                        .isEqualTo(expectedOffsets.get(index));
                running += COPYBOOK_WIDTHS.get(index);
            }
            assertThat(running).isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("the key is the leading nine bytes, as the cluster's key clause states")
        void theKeyIsTheLeadingNineBytes() {
            assertThat(KEY_WIDTH).isEqualTo(COPYBOOK_WIDTHS.get(0));
            assertThat(OFFSET_CUST_ID).isZero();
            assertThat(OFFSET_CUST_ID + KEY_WIDTH).isEqualTo(OFFSET_FIRST_NAME);
        }

        @Test
        @DisplayName("every seeded record measures the declared five hundred bytes")
        void everySeededRecordMeasuresTheDeclaredWidth() {
            assertThat(FIXTURE.recordCount()).isEqualTo(SEEDED_RECORDS);
            assertThat(FIXTURE.recordWidth()).isEqualTo(RECORD_WIDTH);
            assertThat(FIXTURE.impliedByteCount()).isEqualTo(25_050);
            for (final String image : FIXTURE.records()) {
                assertThat(image).hasSize(RECORD_WIDTH);
            }
        }

        @Test
        @DisplayName("the trailing filler is blank in every seeded record")
        void theTrailingFillerIsBlankInEverySeededRecord() {
            for (int ordinal = 1; ordinal <= FIXTURE.recordCount(); ordinal++) {
                assertThat(FIXTURE.field(ordinal, OFFSET_FILLER, FILLER_WIDTH))
                        .as("filler of record %d", ordinal)
                        .isBlank()
                        .hasSize(FILLER_WIDTH);
            }
        }
    }

    // =================================================================================================
    // SCHEMA AGREEMENT
    // =================================================================================================

    /**
     * Verifies that the deployed migration describes the same layout the copybook does.
     */
    @Nested
    @DisplayName("schema agreement")
    class SchemaAgreement {

        @Test
        @DisplayName("the table declares the eighteen mapped columns in copybook order")
        void theTableDeclaresTheMappedColumnsInCopybookOrder() {
            assertThat(SCHEMA.columnNames(TABLE)).containsExactly(
                    "cust_id", "first_name", "middle_name", "last_name",
                    "addr_line_1", "addr_line_2", "addr_line_3", "addr_state_cd",
                    "addr_country_cd", "addr_zip", "phone_num_1", "phone_num_2",
                    "cust_ssn", "govt_issued_id", "cust_dob", "eft_account_id",
                    "pri_card_holder_ind", "fico_credit_score");
            assertThat(SCHEMA.columnNames(TABLE)).hasSize(COPYBOOK_WIDTHS.size() - 1);
        }

        @Test
        @DisplayName("every column except the two protected identifiers is declared at its copybook "
                + "width, those two being widened to hold ciphertext")
        void everyColumnExceptTheProtectedOnesMatchesItsCopybookWidth() {
            final List<String> columns = SCHEMA.columnNames(TABLE);

            for (int index = 0; index < columns.size(); index++) {
                final String column = columns.get(index);
                if ("cust_ssn".equals(column) || "govt_issued_id".equals(column)) {
                    continue;
                }
                assertThat(SCHEMA.declaredWidth(TABLE, column))
                        .as("declared width of %s", column)
                        .isEqualTo(COPYBOOK_WIDTHS.get(index));
            }
        }

        @Test
        @DisplayName("every column is a character type, because the record holds no binary or numeric "
                + "field")
        void everyColumnIsACharacterType() {
            for (final String column : SCHEMA.columnNames(TABLE)) {
                assertThat(SCHEMA.declaredType(TABLE, column))
                        .as("declared type of %s", column)
                        .startsWith("VARCHAR(");
            }
        }

        @Test
        @DisplayName("the credit score is a three-character column rather than a whole number, so a "
                + "leading zero survives a round trip")
        void theCreditScoreIsAThreeCharacterColumn() {
            assertThat(SCHEMA.declaredType(TABLE, "fico_credit_score")).isEqualTo("VARCHAR(3)");
            assertThat(SCHEMA.declaredWidth(TABLE, "fico_credit_score")).isEqualTo(3);
        }

        @Test
        @DisplayName("the primary key is the nine-byte identifier alone, so no surrogate is created")
        void thePrimaryKeyIsTheIdentifierAlone() {
            assertThat(SCHEMA.primaryKeyColumns(TABLE)).containsExactly("cust_id");
            assertThat(SCHEMA.columnNames(TABLE)).doesNotContain("id", "customer_id", "version");
        }

        @Test
        @DisplayName("the social security number is the only nullable column, so an unset value stays "
                + "distinguishable from an empty one")
        void theSsnIsTheOnlyNullableColumn() {
            final List<String> nullable = new ArrayList<>();
            for (final String column : SCHEMA.columnNames(TABLE)) {
                if (SCHEMA.isNullable(TABLE, column)) {
                    nullable.add(column);
                }
            }

            assertThat(nullable).containsExactly("cust_ssn");
        }

        @Test
        @DisplayName("both protected columns are far wider than their source bytes, because a protected "
                + "value is longer than the value it protects")
        void theProtectedColumnsAreWiderThanTheirSourceFields() {
            assertThat(SCHEMA.declaredWidth(TABLE, "cust_ssn"))
                    .isEqualTo(255)
                    .isGreaterThan(COPYBOOK_WIDTHS.get(12));
            assertThat(COPYBOOK_WIDTHS.get(12)).isEqualTo(9);

            assertThat(SCHEMA.declaredWidth(TABLE, "govt_issued_id"))
                    .as("the government-issued identifier is regulated in the same way and is"
                            + " protected in the same way, so its column is widened too")
                    .isEqualTo(255)
                    .isGreaterThan(COPYBOOK_WIDTHS.get(13));
            assertThat(COPYBOOK_WIDTHS.get(13)).isEqualTo(20);
        }
    }

    // =================================================================================================
    // CONSTRUCTION AND ACCESS
    // =================================================================================================

    /**
     * Verifies that every field the constructor takes is the field the accessor returns.
     */
    @Nested
    @DisplayName("construction and access")
    class ConstructionAndAccess {

        @Test
        @DisplayName("every constructor argument reaches its own accessor, with no leak between the "
                + "three same-width names or the three same-width address lines")
        void everyConstructorArgumentReachesItsAccessor() {
            final Customer customer = new Customer(
                    "000000001", "FIRST", "MIDDLE", "LAST",
                    "LINE ONE", "LINE TWO", "LINE THREE", "NC", "USA", "12546     ",
                    "(908)119-8310  ", "(373)693-8684  ", sealed("020973888"),
                    sealed("00000000000049368437"), "1961-06-08", "0053581756", "Y", "274");

            assertThat(customer.getCustId()).isEqualTo("000000001");
            assertThat(customer.getFirstName()).isEqualTo("FIRST");
            assertThat(customer.getMiddleName()).isEqualTo("MIDDLE");
            assertThat(customer.getLastName()).isEqualTo("LAST");
            assertThat(customer.getAddrLine1()).isEqualTo("LINE ONE");
            assertThat(customer.getAddrLine2()).isEqualTo("LINE TWO");
            assertThat(customer.getAddrLine3()).isEqualTo("LINE THREE");
            assertThat(customer.getAddrStateCd()).isEqualTo("NC");
            assertThat(customer.getAddrCountryCd()).isEqualTo("USA");
            assertThat(customer.getAddrZip()).isEqualTo("12546     ");
            assertThat(customer.getPhoneNum1()).isEqualTo("(908)119-8310  ");
            assertThat(customer.getPhoneNum2()).isEqualTo("(373)693-8684  ");
            assertThat(revealed(customer.getCustSsn())).isEqualTo("020973888");
            assertThat(revealed(customer.getGovtIssuedId())).isEqualTo("00000000000049368437");
            assertThat(customer.getCustSsn())
                    .as("what is stored is the protected form, never the value it protects")
                    .doesNotContain("020973888");
            assertThat(customer.getCustDob()).isEqualTo("1961-06-08");
            assertThat(customer.getEftAccountId()).isEqualTo("0053581756");
            assertThat(customer.getPriCardHolderInd()).isEqualTo("Y");
            assertThat(customer.getFicoCreditScore()).isEqualTo("274");
        }

        @Test
        @DisplayName("the two telephone numbers do not swap, which same-width adjacency would hide")
        void theTwoTelephoneNumbersDoNotSwap() {
            final Customer customer = customerFromSeededRecord(1);

            assertThat(customer.getPhoneNum1()).isEqualTo("(908)119-8310  ");
            assertThat(customer.getPhoneNum2()).isEqualTo("(373)693-8684  ");
            assertThat(customer.getPhoneNum1()).isNotEqualTo(customer.getPhoneNum2());
        }

        @Test
        @DisplayName("every mutator replaces exactly the field it names")
        void everyMutatorReplacesTheFieldItNames() {
            final Customer customer = customerFromSeededRecord(1);

            customer.setCustId("000000099");
            customer.setFirstName("A");
            customer.setMiddleName("B");
            customer.setLastName("C");
            customer.setAddrLine1("D");
            customer.setAddrLine2("E");
            customer.setAddrLine3("F");
            customer.setAddrStateCd("CA");
            customer.setAddrCountryCd("CAN");
            customer.setAddrZip("90210     ");
            customer.setPhoneNum1("(111)111-1111  ");
            customer.setPhoneNum2("(222)222-2222  ");
            customer.setCustSsn(sealed("999999999"));
            customer.setGovtIssuedId(sealed("G".repeat(20)));
            customer.setCustDob("2000-01-01");
            customer.setEftAccountId("EFT0000001");
            customer.setPriCardHolderInd("N");
            customer.setFicoCreditScore("850");

            assertThat(customer.getCustId()).isEqualTo("000000099");
            assertThat(customer.getFirstName()).isEqualTo("A");
            assertThat(customer.getMiddleName()).isEqualTo("B");
            assertThat(customer.getLastName()).isEqualTo("C");
            assertThat(customer.getAddrLine1()).isEqualTo("D");
            assertThat(customer.getAddrLine2()).isEqualTo("E");
            assertThat(customer.getAddrLine3()).isEqualTo("F");
            assertThat(customer.getAddrStateCd()).isEqualTo("CA");
            assertThat(customer.getAddrCountryCd()).isEqualTo("CAN");
            assertThat(customer.getAddrZip()).isEqualTo("90210     ");
            assertThat(customer.getPhoneNum1()).isEqualTo("(111)111-1111  ");
            assertThat(customer.getPhoneNum2()).isEqualTo("(222)222-2222  ");
            assertThat(revealed(customer.getCustSsn())).isEqualTo("999999999");
            assertThat(revealed(customer.getGovtIssuedId())).isEqualTo("G".repeat(20));
            assertThat(customer.getCustDob()).isEqualTo("2000-01-01");
            assertThat(customer.getEftAccountId()).isEqualTo("EFT0000001");
            assertThat(customer.getPriCardHolderInd()).isEqualTo("N");
            assertThat(customer.getFicoCreditScore()).isEqualTo("850");
        }

        @Test
        @DisplayName("the persistence constructor leaves every field absent")
        void thePersistenceConstructorLeavesEveryFieldAbsent() {
            final Customer customer = new Customer();

            assertThat(customer.getCustId()).isNull();
            assertThat(customer.getFirstName()).isNull();
            assertThat(customer.getMiddleName()).isNull();
            assertThat(customer.getLastName()).isNull();
            assertThat(customer.getAddrLine1()).isNull();
            assertThat(customer.getAddrLine2()).isNull();
            assertThat(customer.getAddrLine3()).isNull();
            assertThat(customer.getAddrStateCd()).isNull();
            assertThat(customer.getAddrCountryCd()).isNull();
            assertThat(customer.getAddrZip()).isNull();
            assertThat(customer.getPhoneNum1()).isNull();
            assertThat(customer.getPhoneNum2()).isNull();
            assertThat(customer.getCustSsn()).isNull();
            assertThat(customer.getGovtIssuedId()).isNull();
            assertThat(customer.getCustDob()).isNull();
            assertThat(customer.getEftAccountId()).isNull();
            assertThat(customer.getPriCardHolderInd()).isNull();
            assertThat(customer.getFicoCreditScore()).isNull();
        }

        @Test
        @DisplayName("the entity carries a value at every declared width without truncating it")
        void theEntityCarriesValuesAtTheDeclaredWidths() {
            final Customer customer = new Customer(
                    "9".repeat(9), "N".repeat(25), "M".repeat(25), "L".repeat(25),
                    "1".repeat(50), "2".repeat(50), "3".repeat(50), "ST", "CTY", "Z".repeat(10),
                    "P".repeat(15), "Q".repeat(15), sealed("8".repeat(9)),
                    sealed("G".repeat(20)), "D".repeat(10), "E".repeat(10), "Y", "999");

            assertThat(customer.getCustId()).hasSize(9);
            assertThat(customer.getFirstName()).hasSize(25);
            assertThat(customer.getMiddleName()).hasSize(25);
            assertThat(customer.getLastName()).hasSize(25);
            assertThat(customer.getAddrLine1()).hasSize(50);
            assertThat(customer.getAddrLine2()).hasSize(50);
            assertThat(customer.getAddrLine3()).hasSize(50);
            assertThat(customer.getAddrZip()).hasSize(10);
            assertThat(customer.getPhoneNum1()).hasSize(15);
            assertThat(customer.getPhoneNum2()).hasSize(15);
            assertThat(revealed(customer.getCustSsn())).hasSize(9);
            assertThat(revealed(customer.getGovtIssuedId())).hasSize(20);
            assertThat(customer.getFicoCreditScore()).hasSize(3);
        }
    }

    // =================================================================================================
    // THE TWO UNVALIDATED FIELDS
    // =================================================================================================

    /**
     * Verifies that the two fields the legacy decorates but never checks carry no constraint.
     */
    @Nested
    @DisplayName("the two unvalidated fields")
    class UnvalidatedFields {

        @Test
        @DisplayName("a blank middle name is accepted, because the legacy codes no edit for it")
        void aBlankMiddleNameIsAccepted() {
            final Customer customer = customerFromSeededRecord(1);
            customer.setMiddleName(" ".repeat(25));

            assertThat(customer.getMiddleName()).isBlank().hasSize(25);
        }

        @Test
        @DisplayName("a blank second address line is accepted, because the legacy codes no edit for it "
                + "either")
        void aBlankSecondAddressLineIsAccepted() {
            final Customer customer = customerFromSeededRecord(1);
            customer.setAddrLine2(" ".repeat(50));

            assertThat(customer.getAddrLine2()).isBlank().hasSize(50);
        }

        @Test
        @DisplayName("both unvalidated fields are also accepted as absent, so no constraint hides in the "
                + "constructor")
        void bothUnvalidatedFieldsAreAcceptedAsAbsent() {
            final Customer customer = new Customer(
                    "000000001", "FIRST", null, "LAST",
                    "LINE ONE", null, "LINE THREE", "NC", "USA", "12546     ",
                    "(908)119-8310  ", "(373)693-8684  ", sealed("020973888"),
                    sealed("00000000000049368437"), "1961-06-08", "0053581756", "Y", "274");

            assertThat(customer.getMiddleName()).isNull();
            assertThat(customer.getAddrLine2()).isNull();
            assertThat(customer.getCustId()).isEqualTo("000000001");
        }
    }

    // =================================================================================================
    // CREDIT SCORE FIDELITY
    // =================================================================================================

    /**
     * Verifies that the three-digit credit score is carried without numeric reinterpretation.
     */
    @Nested
    @DisplayName("credit score fidelity")
    class CreditScoreFidelity {

        @Test
        @DisplayName("a leading zero survives, which an integer field would have discarded")
        void aLeadingZeroSurvives() {
            final Customer customer = customerFromSeededRecord(1);
            customer.setFicoCreditScore("001");

            assertThat(customer.getFicoCreditScore()).isEqualTo("001").hasSize(3);
            assertThat(Integer.parseInt(customer.getFicoCreditScore())).isOne();
            assertThat(customer.getFicoCreditScore())
                    .as("the stored form differs from the numeric form, which is exactly what a whole "
                            + "number column would have lost")
                    .isNotEqualTo(Integer.toString(Integer.parseInt(customer.getFicoCreditScore())));
        }

        @Test
        @DisplayName("the seeded file carries scores with a leading zero, so the case is not "
                + "hypothetical")
        void theSeededFileCarriesScoresWithALeadingZero() {
            final List<String> withLeadingZero = new ArrayList<>();
            for (int ordinal = 1; ordinal <= FIXTURE.recordCount(); ordinal++) {
                final String score = customerFromSeededRecord(ordinal).getFicoCreditScore();
                if (score.startsWith("0")) {
                    withLeadingZero.add(score);
                }
            }

            assertThat(withLeadingZero).hasSize(SEEDED_SCORES_WITH_LEADING_ZERO).contains("001");
        }

        @Test
        @DisplayName("every seeded score is exactly three digits")
        void everySeededScoreIsThreeDigits() {
            for (int ordinal = 1; ordinal <= FIXTURE.recordCount(); ordinal++) {
                assertThat(customerFromSeededRecord(ordinal).getFicoCreditScore())
                        .as("score of record %d", ordinal)
                        .hasSize(3)
                        .containsOnlyDigits();
            }
        }

        @Test
        @DisplayName("the record accepts a score below the screen's lower bound, because the screen "
                + "enforces the band and the record does not")
        void theRecordAcceptsAScoreBelowTheScreenBound() {
            int belowBound = 0;
            for (int ordinal = 1; ordinal <= FIXTURE.recordCount(); ordinal++) {
                final int score =
                        Integer.parseInt(customerFromSeededRecord(ordinal).getFicoCreditScore());
                if (score < SCREEN_SCORE_MINIMUM) {
                    belowBound++;
                }
            }

            assertThat(belowBound)
                    .as("an entity that enforced the band could not read the estate's own data")
                    .isEqualTo(SEEDED_SCORES_BELOW_SCREEN_MINIMUM)
                    .isPositive();
        }
    }

    // =================================================================================================
    // SEEDED RECORD FIDELITY
    // =================================================================================================

    /**
     * Verifies that a real seeded record travels into the entity intact.
     */
    @Nested
    @DisplayName("seeded record fidelity")
    class SeededRecordFidelity {

        @Test
        @DisplayName("the first seeded record maps field for field onto the entity")
        void theFirstSeededRecordMapsFieldForField() {
            final Customer customer = customerFromSeededRecord(1);

            assertThat(customer.getCustId()).isEqualTo("000000001");
            assertThat(customer.getFirstName()).startsWith("Immanuel").hasSize(25);
            assertThat(customer.getMiddleName()).startsWith("Madeline").hasSize(25);
            assertThat(customer.getLastName()).startsWith("Kessler").hasSize(25);
            assertThat(customer.getAddrLine1()).startsWith("618 Deshaun Route").hasSize(50);
            assertThat(customer.getAddrLine2()).startsWith("Apt. 802").hasSize(50);
            assertThat(customer.getAddrLine3()).startsWith("Altenwerthshire").hasSize(50);
            assertThat(customer.getAddrStateCd()).isEqualTo("NC");
            assertThat(customer.getAddrCountryCd()).isEqualTo("USA");
            assertThat(customer.getAddrZip()).isEqualTo("12546     ");
            assertThat(revealed(customer.getCustSsn())).isEqualTo("020973888");
            assertThat(revealed(customer.getGovtIssuedId())).isEqualTo("00000000000049368437");
            assertThat(customer.getCustDob()).isEqualTo("1961-06-08");
            assertThat(customer.getEftAccountId()).isEqualTo("0053581756");
            assertThat(customer.getPriCardHolderInd()).isEqualTo("Y");
            assertThat(customer.getFicoCreditScore()).isEqualTo("274");
        }

        @Test
        @DisplayName("every seeded record maps without loss, and every identifier is distinct")
        void everySeededRecordMapsWithoutLoss() {
            final List<Customer> customers = new ArrayList<>();
            for (int ordinal = 1; ordinal <= FIXTURE.recordCount(); ordinal++) {
                customers.add(customerFromSeededRecord(ordinal));
            }

            assertThat(customers).hasSize(SEEDED_RECORDS);
            assertThat(customers.stream().map(Customer::getCustId).distinct().toList())
                    .hasSize(SEEDED_RECORDS);
            for (final Customer customer : customers) {
                assertThat(customer.getCustId()).hasSize(KEY_WIDTH).containsOnlyDigits();
                assertThat(revealed(customer.getCustSsn())).hasSize(9).containsOnlyDigits();
                assertThat(customer.getCustDob()).hasSize(10);
            }
        }

        @Test
        @DisplayName("a social security number keeps its leading zero, so the nine digits are carried as "
                + "text rather than as a number")
        void aSocialSecurityNumberKeepsItsLeadingZero() {
            assertThat(revealed(customerFromSeededRecord(1).getCustSsn())).isEqualTo("020973888")
                    .startsWith("0")
                    .hasSize(9);
        }

        @Test
        @DisplayName("every seeded customer is a primary cardholder in the same country, so those two "
                + "fields are not exercised by the seed")
        void everySeededCustomerIsAPrimaryCardholderInTheSameCountry() {
            for (int ordinal = 1; ordinal <= FIXTURE.recordCount(); ordinal++) {
                final Customer customer = customerFromSeededRecord(ordinal);

                assertThat(customer.getPriCardHolderInd())
                        .as("indicator of record %d", ordinal)
                        .isEqualTo("Y");
                assertThat(customer.getAddrCountryCd())
                        .as("country of record %d", ordinal)
                        .isEqualTo("USA");
            }
        }
    }

    // =================================================================================================
    // BUSINESS-KEY IDENTITY
    // =================================================================================================

    /**
     * Verifies that identity is the record's own key and nothing else.
     */
    @Nested
    @DisplayName("business-key identity")
    class BusinessKeyIdentity {

        @Test
        @DisplayName("a customer equals itself")
        void aCustomerEqualsItself() {
            final Customer customer = customerFromSeededRecord(1);

            assertThat(customer).isEqualTo(customer);
            assertThat(customer.hashCode()).isEqualTo(customer.hashCode());
        }

        @Test
        @DisplayName("two customers with the same identifier are equal even when every other field "
                + "differs")
        void sameIdentifierMeansEqualRegardlessOfTheRest() {
            final Customer left = customerFromSeededRecord(1);
            final Customer right = new Customer(
                    left.getCustId(), "OTHER", "OTHER", "OTHER",
                    "OTHER", "OTHER", "OTHER", "CA", "CAN", "OTHER     ",
                    "OTHER          ", "OTHER          ", sealed("111111111"),
                    sealed("OTHER"), "1900-01-01", "OTHER", "N", "850");

            assertThat(left).isEqualTo(right);
            assertThat(right).isEqualTo(left);
            assertThat(left).hasSameHashCodeAs(right);
        }

        @Test
        @DisplayName("two customers with different identifiers are unequal even when every other field "
                + "matches")
        void differentIdentifierMeansUnequal() {
            final Customer left = customerFromSeededRecord(1);
            final Customer right = customerFromSeededRecord(1);
            right.setCustId("000000002");

            assertThat(left).isNotEqualTo(right);
            assertThat(right).isNotEqualTo(left);
        }

        @Test
        @DisplayName("equality is transitive across three customers sharing an identifier")
        void equalityIsTransitive() {
            final Customer first = customerFromSeededRecord(1);
            final Customer second = customerFromSeededRecord(1);
            final Customer third = customerFromSeededRecord(1);

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(third);
            assertThat(first).isEqualTo(third);
            assertThat(first).hasSameHashCodeAs(third);
        }

        @Test
        @DisplayName("a customer is unequal to null and to an unrelated type")
        void aCustomerIsUnequalToNullAndToAnotherType() {
            final Customer customer = customerFromSeededRecord(1);

            assertThat(customer).isNotEqualTo(null);
            assertThat(customer.equals("000000001")).isFalse();
            assertThat(customer).isNotEqualTo(new Object());
        }

        @Test
        @DisplayName("two customers with an absent identifier are equal, because both keys are absent "
                + "rather than generated")
        void twoUnkeyedCustomersAreEqual() {
            assertThat(new Customer()).isEqualTo(new Customer());
            assertThat(new Customer()).hasSameHashCodeAs(new Customer());
            assertThat(new Customer().getCustId()).isNull();
        }

        @Test
        @DisplayName("an unkeyed customer is unequal to a keyed one")
        void anUnkeyedCustomerIsUnequalToAKeyedOne() {
            assertThat(new Customer()).isNotEqualTo(customerFromSeededRecord(1));
            assertThat(customerFromSeededRecord(1)).isNotEqualTo(new Customer());
        }
    }

    // =================================================================================================
    // PRIVACY POSTURE
    // =================================================================================================

    /**
     * Verifies the two privacy departures the migration makes and the leak it forecloses.
     */
    @Nested
    @DisplayName("privacy posture")
    class PrivacyPosture {

        @Test
        @DisplayName("the entity declares no diagnostic string, so no field value can escape through "
                + "one")
        void theEntityDeclaresNoDiagnosticString() {
            final Customer customer = customerFromSeededRecord(1);
            final String description = customer.toString();

            assertThat(description)
                    .startsWith(Customer.class.getName() + "@")
                    .doesNotContain("020973888")
                    .doesNotContain("Immanuel")
                    .doesNotContain("Kessler")
                    .doesNotContain("1961-06-08")
                    .doesNotContain("000000001");
        }

        @Test
        @DisplayName("the description is the inherited type-and-handle form, not a field-bearing "
                + "override")
        void theDescriptionIsTheInheritedOne() {
            final Customer customer = customerFromSeededRecord(1);

            assertThat(customer.toString())
                    .matches("com\\.carddemo\\.domain\\.Customer@[0-9a-f]+");
        }

        @Test
        @DisplayName("the social security number may be absent, which is what lets a deployment store "
                + "nothing rather than an empty protected value")
        void theSocialSecurityNumberMayBeAbsent() {
            final Customer customer = customerFromSeededRecord(1);
            customer.setCustSsn(null);

            assertThat(customer.getCustSsn()).isNull();
            assertThat(SCHEMA.isNullable(TABLE, "cust_ssn")).isTrue();
        }

        @Test
        @DisplayName("the number's column accommodates a value far longer than nine characters, which a "
                + "nine-character column could not have held")
        void theColumnAccommodatesALongerValue() {
            final Customer customer = customerFromSeededRecord(1);
            final String protectedValue = sealed("L".repeat(120));
            customer.setCustSsn(protectedValue);

            assertThat(customer.getCustSsn())
                    .hasSizeGreaterThan(COPYBOOK_WIDTHS.get(12))
                    .hasSizeLessThanOrEqualTo(SCHEMA.declaredWidth(TABLE, "cust_ssn"));
            assertThat(SCHEMA.declaredWidth(TABLE, "cust_ssn")).isGreaterThanOrEqualTo(200);
        }
    }
}
