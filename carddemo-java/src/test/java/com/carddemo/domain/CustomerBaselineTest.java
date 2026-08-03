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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.carddemo.util.SensitiveFieldCodec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link Customer}, the entity form of the 500-byte customer record - the widest record
 * layout in the estate.
 *
 * <p><strong>What this test proves.</strong> Three independent legacy authorities fix the shape of
 * this entity, and this test asserts that the Java form honours all three:
 * <ul>
 *   <li>the copybook member {@code CVCUS01Y}, whose record is 500 bytes across eighteen mapped fields
 *       and a 168-byte trailing filler: a 9-digit identifier at offset 0, three 25-byte name fields,
 *       three 50-byte address lines, a 2-byte state code, a 3-byte country code, a 10-byte postal
 *       code, two 15-byte telephone fields, a 9-digit national identifier, a 20-byte
 *       government-issued identifier, a 10-byte date of birth, a 10-byte funds-transfer account
 *       identifier, a 1-byte primary-holder indicator and a 3-digit credit score;</li>
 *   <li>the {@code CUSTDATA} indexed cluster definition, which declares {@code KEYS(9 0)} with
 *       {@code RECORDSIZE(500 500)}, so the primary key is the leading nine bytes of the record image
 *       and no surrogate identifier exists; and</li>
 *   <li>the estate's own validation placement. Two of the eighteen fields - the middle name and the
 *       second address line - are decorated for error display by the account-update screen but are
 *       never actually validated, which the legacy source states in as many words. Neither the entity
 *       nor its request payload may attach a constraint to them, because doing so would reject input
 *       the legacy system accepts. This test asserts that both fields accept whatever they are
 *       handed.</li>
 * </ul>
 *
 * <p><strong>Why the credit score is text and why the entity does not range check it.</strong> The
 * account-update transaction enforces a credit score between 300 and 850, but that enforcement lives
 * in the service layer, not in the entity. The reference fixture proves the distinction is real: its
 * first row carries a score of 274, which is below the range the update transaction would accept. An
 * entity that range checked its own state could not carry that seeded row at all, so this test asserts
 * the out-of-range seeded value is stored unchanged.
 *
 * <p><strong>Two deliberate divergences from the copybook widths.</strong> The national identifier
 * and the government-issued identifier are widened well beyond their nine and twenty source
 * characters, because both values are protected at rest and ciphertext does not fit the plaintext
 * width. The national identifier is additionally the entity's one nullable column, because the seed
 * leaves it absent rather than committing a protected national identifier to a checked-in artifact;
 * the government-issued identifier is NOT NULL and its every seeded row carries a sealed envelope. The credit score stays three characters wide and text typed, so a
 * leading zero survives. Both are asserted here so that neither is mistaken for a mapping slip.
 *
 * <p><strong>No diagnostic representation is overridden.</strong> Every field of this record is
 * personal data, so the entity inherits the identity representation instead of rendering its state.
 * This test asserts that inherited form, which is what guarantees a log line cannot leak a name, a
 * date of birth, a national identifier or a credit score.
 *
 * <p><strong>Reference data.</strong> The customer fixture holds 50 rows of 500 bytes; its first row
 * carries the values asserted below. Only data values are reproduced here; no line of legacy source is
 * transcribed anywhere in this file.
 *
 * <p><strong>Scope.</strong> A pure in-process unit test. It starts no application context, opens no
 * database connection, reads no file, touches no network, runs no container and performs no
 * introspection. Every expected value is a literal typed out in this source.
 *
 * <p><strong>Provenance.</strong> Legacy estate read at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Cited as provenance only and never asserted
 * against a member.
 */
@DisplayName("Customer - the entity form of the 500-byte customer record")
class CustomerBaselineTest {

    /** Width of the identifier, and therefore the declared key length of the cluster. */
    private static final int ID_WIDTH = 9;

    /** Width of each of the three name fields. */
    private static final int NAME_WIDTH = 25;

    /** Width of each of the three address lines. */
    private static final int ADDRESS_LINE_WIDTH = 50;

    /** Width of the state code. */
    private static final int STATE_WIDTH = 2;

    /** Width of the country code. */
    private static final int COUNTRY_WIDTH = 3;

    /** Width of the postal code. */
    private static final int ZIP_WIDTH = 10;

    /** Width of each of the two telephone fields. */
    private static final int PHONE_WIDTH = 15;

    /** Width of the national identifier in the record image. */
    private static final int NATIONAL_ID_WIDTH = 9;

    /** Width of the government-issued identifier. */
    private static final int GOVT_ID_WIDTH = 20;

    /** Width of the date of birth. */
    private static final int DOB_WIDTH = 10;

    /** Width of the funds-transfer account identifier. */
    private static final int EFT_ID_WIDTH = 10;

    /** Width of the primary-holder indicator. */
    private static final int PRIMARY_HOLDER_WIDTH = 1;

    /** Width of the credit score. */
    private static final int CREDIT_SCORE_WIDTH = 3;

    /** Width of the trailing filler that closes the record. */
    private static final int FILLER_WIDTH = 168;

    /** Record length declared by both the copybook and the cluster definition. */
    private static final int RECORD_LENGTH = 500;

    /** Mapped fields the record carries ahead of its filler. */
    private static final int MAPPED_FIELD_COUNT = 18;

    /** Rows measured in the customer reference fixture. */
    private static final int FIXTURE_ROWS = 50;

    /** Lowest credit score the account-update transaction accepts. */
    private static final int SERVICE_MINIMUM_CREDIT_SCORE = 300;

    /** Highest credit score the account-update transaction accepts. */
    private static final int SERVICE_MAXIMUM_CREDIT_SCORE = 850;

    private static final String FIRST_ID = "000000001";
    private static final String FIRST_NAME = rightPadded("Immanuel", NAME_WIDTH);
    private static final String MIDDLE_NAME = rightPadded("Madeline", NAME_WIDTH);
    private static final String LAST_NAME = rightPadded("Kessler", NAME_WIDTH);
    private static final String ADDRESS_LINE_1 = rightPadded("12 Main Street", ADDRESS_LINE_WIDTH);
    private static final String ADDRESS_LINE_2 = rightPadded("Apartment 4", ADDRESS_LINE_WIDTH);
    private static final String ADDRESS_LINE_3 = rightPadded("Raleigh", ADDRESS_LINE_WIDTH);
    private static final String STATE_CODE = "NC";
    private static final String COUNTRY_CODE = "USA";
    private static final String ZIP = "12546     ";
    private static final String PHONE_1 = "(919)555-0100  ";
    private static final String PHONE_2 = "(919)555-0101  ";
    /** The nine cleartext digits the record image carries at offset 279. */
    private static final String NATIONAL_ID_CLEARTEXT = "020973888";

    /** The twenty cleartext characters the record image carries at offset 288. */
    private static final String GOVT_ISSUED_ID_CLEARTEXT = "NC-DL-0000000000001 ";

    /**
     * A thirty-two-byte key used only to manufacture and read back well-formed protected values.
     *
     * <p>Both regulated attributes are stored as ciphertext and the entity refuses cleartext on
     * every write path, so a test cannot hand it the legacy value directly. This key seals the
     * fixture values on the way in and recovers them for assertion; it protects nothing real.
     */
    private static final byte[] PROTECTION_KEY =
            "carddemo-baseline-test-key-0123!".getBytes(StandardCharsets.UTF_8);

    private static final String NATIONAL_ID = sealed(NATIONAL_ID_CLEARTEXT);
    private static final String GOVT_ISSUED_ID = sealed(GOVT_ISSUED_ID_CLEARTEXT);
    private static final String DATE_OF_BIRTH = "1961-06-08";
    private static final String EFT_ACCOUNT_ID = "0053581756";
    private static final String PRIMARY_HOLDER = "Y";
    private static final String CREDIT_SCORE = "274";

    private Customer customer;

    /**
     * Right pads a value with spaces to the exact width the record layout reserves for its field.
     * Used only to build test inputs at their declared widths; the width itself is always asserted
     * against the independently declared constant rather than against this helper's output.
     *
     * @param value the significant text of the field
     * @param width the declared field width from the copybook layout
     * @return the value padded on the right with spaces to exactly {@code width} characters
     */
    private static String rightPadded(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Seals a cleartext value into the envelope shape the entity accepts.
     *
     * @param cleartext the legacy cleartext value
     * @return the sealed value
     */
    private static String sealed(final String cleartext) {
        return SensitiveFieldCodec.protect(cleartext, PROTECTION_KEY);
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

    @BeforeEach
    void createFirstFixtureRow() {
        customer = new Customer(FIRST_ID, FIRST_NAME, MIDDLE_NAME, LAST_NAME, ADDRESS_LINE_1,
                ADDRESS_LINE_2, ADDRESS_LINE_3, STATE_CODE, COUNTRY_CODE, ZIP, PHONE_1, PHONE_2,
                NATIONAL_ID, GOVT_ISSUED_ID, DATE_OF_BIRTH, EFT_ACCOUNT_ID, PRIMARY_HOLDER,
                CREDIT_SCORE);
    }

    @Nested
    @DisplayName("Construction in copybook declaration order")
    class Construction {

        @Test
        @DisplayName("the eighteen-argument constructor binds every field in copybook declaration "
                + "order: identifier, three names, three address lines, state, country, postal code, "
                + "two telephones, national identifier, government identifier, date of birth, "
                + "funds-transfer identifier, primary-holder indicator, credit score")
        void theConstructorBindsEveryFieldInDeclarationOrder() {
            assertThat(customer.getCustId()).isEqualTo(FIRST_ID);
            assertThat(customer.getFirstName()).isEqualTo(FIRST_NAME);
            assertThat(customer.getMiddleName()).isEqualTo(MIDDLE_NAME);
            assertThat(customer.getLastName()).isEqualTo(LAST_NAME);
            assertThat(customer.getAddrLine1()).isEqualTo(ADDRESS_LINE_1);
            assertThat(customer.getAddrLine2()).isEqualTo(ADDRESS_LINE_2);
            assertThat(customer.getAddrLine3()).isEqualTo(ADDRESS_LINE_3);
            assertThat(customer.getAddrStateCd()).isEqualTo(STATE_CODE);
            assertThat(customer.getAddrCountryCd()).isEqualTo(COUNTRY_CODE);
            assertThat(customer.getAddrZip()).isEqualTo(ZIP);
            assertThat(customer.getPhoneNum1()).isEqualTo(PHONE_1);
            assertThat(customer.getPhoneNum2()).isEqualTo(PHONE_2);
            assertThat(customer.getCustSsn()).isEqualTo(NATIONAL_ID);
            assertThat(revealed(customer.getCustSsn())).isEqualTo(NATIONAL_ID_CLEARTEXT);
            assertThat(customer.getGovtIssuedId()).isEqualTo(GOVT_ISSUED_ID);
            assertThat(revealed(customer.getGovtIssuedId())).isEqualTo(GOVT_ISSUED_ID_CLEARTEXT);
            assertThat(customer.getCustDob()).isEqualTo(DATE_OF_BIRTH);
            assertThat(customer.getEftAccountId()).isEqualTo(EFT_ACCOUNT_ID);
            assertThat(customer.getPriCardHolderInd()).isEqualTo(PRIMARY_HOLDER);
            assertThat(customer.getFicoCreditScore()).isEqualTo(CREDIT_SCORE);
        }

        @Test
        @DisplayName("the no-arg constructor the provider requires leaves all eighteen fields absent, "
                + "because the provider populates state afterwards")
        void theNoArgConstructorLeavesEveryFieldAbsent() {
            final Customer empty = new Customer();

            assertThat(empty.getCustId()).isNull();
            assertThat(empty.getFirstName()).isNull();
            assertThat(empty.getMiddleName()).isNull();
            assertThat(empty.getLastName()).isNull();
            assertThat(empty.getAddrLine1()).isNull();
            assertThat(empty.getAddrLine2()).isNull();
            assertThat(empty.getAddrLine3()).isNull();
            assertThat(empty.getAddrStateCd()).isNull();
            assertThat(empty.getAddrCountryCd()).isNull();
            assertThat(empty.getAddrZip()).isNull();
            assertThat(empty.getPhoneNum1()).isNull();
            assertThat(empty.getPhoneNum2()).isNull();
            assertThat(empty.getCustSsn()).isNull();
            assertThat(empty.getGovtIssuedId()).isNull();
            assertThat(empty.getCustDob()).isNull();
            assertThat(empty.getEftAccountId()).isNull();
            assertThat(empty.getPriCardHolderInd()).isNull();
            assertThat(empty.getFicoCreditScore()).isNull();
        }

        @Test
        @DisplayName("the constructor stores every record-layout value verbatim - no trim, no pad and "
                + "no case fold - because validation belongs to the service layer")
        void theConstructorStoresEveryValueVerbatim() {
            final Customer raw = new Customer("  9  ", "mIxEd", "", "  ", "line one", "line two",
                    "line three", "nc", "usa", "1", "555", "1", sealed("ssn"), sealed("id"), "dob",
                    "eft", "y", "0");

            assertThat(raw.getCustId()).isEqualTo("  9  ");
            assertThat(raw.getFirstName()).isEqualTo("mIxEd");
            assertThat(raw.getMiddleName()).isEmpty();
            assertThat(raw.getLastName()).isEqualTo("  ");
            assertThat(raw.getAddrStateCd()).isEqualTo("nc");
            assertThat(raw.getAddrCountryCd()).isEqualTo("usa");
            assertThat(revealed(raw.getCustSsn()))
                    .as("a protected value is stored as supplied: the entity neither seals nor"
                            + " unseals, so what it holds reveals back byte for byte")
                    .isEqualTo("ssn");
            assertThat(revealed(raw.getGovtIssuedId())).isEqualTo("id");
            assertThat(raw.getPriCardHolderInd()).isEqualTo("y");
            assertThat(raw.getFicoCreditScore()).isEqualTo("0");
        }

        @Test
        @DisplayName("the two regulated identifiers are the exception to that tolerance: a cleartext "
                + "value is refused rather than stored, and the refusal does not echo it")
        void theTwoRegulatedIdentifiersRefuseCleartext() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new Customer("  9  ", "mIxEd", "", "  ", "line one",
                            "line two", "line three", "nc", "usa", "1", "555", "1",
                            NATIONAL_ID_CLEARTEXT, sealed("id"), "dob", "eft", "y", "0"))
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .contains("custSsn")
                            .doesNotContain(NATIONAL_ID_CLEARTEXT));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new Customer("  9  ", "mIxEd", "", "  ", "line one",
                            "line two", "line three", "nc", "usa", "1", "555", "1",
                            sealed("ssn"), GOVT_ISSUED_ID_CLEARTEXT, "dob", "eft", "y", "0"))
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .contains("govtIssuedId")
                            .doesNotContain(GOVT_ISSUED_ID_CLEARTEXT));
        }

        @Test
        @DisplayName("absent record-layout values are accepted and returned unchanged, because the "
                + "entity performs no validation of those and the database enforces their non-null "
                + "contract; both regulated identifiers may be absent in memory too, since a record "
                + "image read at a boundary need not carry a protected value yet")
        void absentValuesAreAcceptedAndReturnedUnchanged() {
            final Customer sparse = new Customer(FIRST_ID, null, null, null, null, null, null, null,
                    null, null, null, null, null, GOVT_ISSUED_ID, null, null, null, null);

            assertThat(sparse.getCustId()).isEqualTo(FIRST_ID);
            assertThat(sparse.getFirstName()).isNull();
            assertThat(sparse.getCustSsn()).isNull();
            assertThat(sparse.getFicoCreditScore()).isNull();

            final Customer whollyAbsent = new Customer(FIRST_ID, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null);

            assertThat(whollyAbsent.getCustSsn()).isNull();
            assertThat(whollyAbsent.getGovtIssuedId())
                    .as("absence is representable in memory for both regulated attributes, and"
                            + " only cleartext is refused here; the column constraint is the"
                            + " database's to enforce, not this class's")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("Byte geometry of the 500-byte record")
    class ByteGeometry {

        @Test
        @DisplayName("the eighteen field widths plus the 168-byte filler close the 500-byte record "
                + "exactly, so the copybook layout and the declared record size agree")
        void theFieldWidthsCloseTheRecordExactly() {
            final int summed = ID_WIDTH
                    + 3 * NAME_WIDTH
                    + 3 * ADDRESS_LINE_WIDTH
                    + STATE_WIDTH
                    + COUNTRY_WIDTH
                    + ZIP_WIDTH
                    + 2 * PHONE_WIDTH
                    + NATIONAL_ID_WIDTH
                    + GOVT_ID_WIDTH
                    + DOB_WIDTH
                    + EFT_ID_WIDTH
                    + PRIMARY_HOLDER_WIDTH
                    + CREDIT_SCORE_WIDTH
                    + FILLER_WIDTH;

            assertThat(summed).isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("eighteen mapped fields are declared, three of them repeated widths, which is why "
                + "the width sum groups them by multiplier")
        void eighteenMappedFieldsAreDeclared() {
            final int distinctlyCountedFields = 1 + 3 + 3 + 1 + 1 + 1 + 2 + 1 + 1 + 1 + 1 + 1 + 1;

            assertThat(distinctlyCountedFields).isEqualTo(MAPPED_FIELD_COUNT);
        }

        @Test
        @DisplayName("the key is the leading nine bytes of the record, matching the cluster's key "
                + "declaration, so the business key is the identifier and no surrogate is introduced")
        void theKeyIsTheLeadingNineBytes() {
            assertThat(customer.getCustId()).hasSize(ID_WIDTH);
            assertThat(RECORD_LENGTH - ID_WIDTH).isEqualTo(491);
        }

        @Test
        @DisplayName("this record is the widest in the estate: wider than the account record's 300 "
                + "bytes and wider than the 350-byte transaction record")
        void thisRecordIsTheWidestInTheEstate() {
            final int accountRecordLength = 300;
            final int transactionRecordLength = 350;

            assertThat(RECORD_LENGTH)
                    .isGreaterThan(accountRecordLength)
                    .isGreaterThan(transactionRecordLength);
        }

        @Test
        @DisplayName("every padded text field keeps the width its picture reserves, so a value survives "
                + "into the entity with its trailing spaces intact")
        void everyPaddedTextFieldKeepsItsWidth() {
            assertThat(customer.getFirstName()).hasSize(NAME_WIDTH);
            assertThat(customer.getMiddleName()).hasSize(NAME_WIDTH);
            assertThat(customer.getLastName()).hasSize(NAME_WIDTH);
            assertThat(customer.getAddrLine1()).hasSize(ADDRESS_LINE_WIDTH);
            assertThat(customer.getAddrLine2()).hasSize(ADDRESS_LINE_WIDTH);
            assertThat(customer.getAddrLine3()).hasSize(ADDRESS_LINE_WIDTH);
            assertThat(customer.getAddrZip()).hasSize(ZIP_WIDTH);
            assertThat(customer.getPhoneNum1()).hasSize(PHONE_WIDTH);
            assertThat(customer.getPhoneNum2()).hasSize(PHONE_WIDTH);
            assertThat(revealed(customer.getGovtIssuedId()))
                    .as("the record field is twenty bytes wide; the column holds the protected form"
                            + " of those bytes, so the width belongs to the cleartext")
                    .hasSize(GOVT_ID_WIDTH);
        }

        @Test
        @DisplayName("the short fixed fields are exactly as narrow as their pictures: two for the state, "
                + "three for the country, one for the primary-holder indicator and three for the score")
        void theShortFixedFieldsAreExactlyAsNarrowAsTheirPictures() {
            assertThat(customer.getAddrStateCd()).hasSize(STATE_WIDTH);
            assertThat(customer.getAddrCountryCd()).hasSize(COUNTRY_WIDTH);
            assertThat(customer.getPriCardHolderInd()).hasSize(PRIMARY_HOLDER_WIDTH);
            assertThat(customer.getFicoCreditScore()).hasSize(CREDIT_SCORE_WIDTH);
        }

        @Test
        @DisplayName("the date of birth is exactly ten characters and hyphenated in year-month-day "
                + "order, matching the field name the statement program relies on")
        void theDateOfBirthIsTenHyphenatedCharacters() {
            assertThat(customer.getCustDob()).hasSize(DOB_WIDTH);
            assertThat(customer.getCustDob().charAt(4)).isEqualTo('-');
            assertThat(customer.getCustDob().charAt(7)).isEqualTo('-');
            assertThat(customer.getCustDob()).isEqualTo("1961-06-08");
        }

        @Test
        @DisplayName("the funds-transfer account identifier is ten digits and keeps its leading zero, "
                + "so it is text rather than a number")
        void theFundsTransferIdentifierKeepsItsLeadingZero() {
            assertThat(customer.getEftAccountId()).hasSize(EFT_ID_WIDTH).startsWith("0");
        }
    }

    @Nested
    @DisplayName("The two fields the legacy screen decorates but never validates")
    class UnvalidatedFields {

        @ParameterizedTest(name = "middle name [{0}] is accepted")
        @DisplayName("the middle name accepts anything, because the legacy screen decorates it for "
                + "error display but codes no edit for it - attaching a constraint would reject input "
                + "the legacy system accepts")
        @ValueSource(strings = {"", " ", "  spaces  ", "digits123", "!@#$%", "MARY ANN", "x"})
        void theMiddleNameAcceptsAnything(final String middleName) {
            customer.setMiddleName(middleName);

            assertThat(customer.getMiddleName()).isEqualTo(middleName);
        }

        @ParameterizedTest(name = "second address line [{0}] is accepted")
        @DisplayName("the second address line accepts anything, for the same reason: the legacy screen "
                + "decorates it but codes no edit for it")
        @ValueSource(strings = {"", " ", "  spaces  ", "digits123", "!@#$%", "Apt 4B", "x"})
        void theSecondAddressLineAcceptsAnything(final String addressLine2) {
            customer.setAddrLine2(addressLine2);

            assertThat(customer.getAddrLine2()).isEqualTo(addressLine2);
        }

        @Test
        @DisplayName("both unvalidated fields accept an absent value, which is the strongest form of "
                + "the same statement")
        void bothUnvalidatedFieldsAcceptAnAbsentValue() {
            customer.setMiddleName(null);
            customer.setAddrLine2(null);

            assertThat(customer.getMiddleName()).isNull();
            assertThat(customer.getAddrLine2()).isNull();
        }
    }

    @Nested
    @DisplayName("Credit score held as text and range checked elsewhere")
    class CreditScore {

        @Test
        @DisplayName("the seeded first row carries a score below the range the update transaction "
                + "accepts, and the entity stores it unchanged - proof that range checking lives in the "
                + "service layer and not here")
        void theSeededScoreBelowTheServiceRangeIsStoredUnchanged() {
            assertThat(customer.getFicoCreditScore()).isEqualTo("274");
            assertThat(Integer.parseInt(customer.getFicoCreditScore()))
                    .isLessThan(SERVICE_MINIMUM_CREDIT_SCORE);
        }

        @Test
        @DisplayName("the service range is 300 through 850 inclusive, and both boundaries store "
                + "unchanged as three-character text")
        void bothServiceRangeBoundariesStoreUnchanged() {
            customer.setFicoCreditScore(String.valueOf(SERVICE_MINIMUM_CREDIT_SCORE));
            assertThat(customer.getFicoCreditScore()).isEqualTo("300").hasSize(CREDIT_SCORE_WIDTH);

            customer.setFicoCreditScore(String.valueOf(SERVICE_MAXIMUM_CREDIT_SCORE));
            assertThat(customer.getFicoCreditScore()).isEqualTo("850").hasSize(CREDIT_SCORE_WIDTH);
        }

        @Test
        @DisplayName("the score is text, so a value below one hundred keeps its leading zeros and stays "
                + "three characters wide, which a numeric field could not do")
        void theScoreKeepsItsLeadingZeros() {
            customer.setFicoCreditScore("007");

            assertThat(customer.getFicoCreditScore()).isEqualTo("007").hasSize(CREDIT_SCORE_WIDTH);
            assertThat(Integer.parseInt(customer.getFicoCreditScore())).isEqualTo(7);
        }

        @Test
        @DisplayName("a score above the service range is stored unchanged too, so the entity is a "
                + "faithful carrier in both directions")
        void aScoreAboveTheServiceRangeIsStoredUnchanged() {
            customer.setFicoCreditScore("999");

            assertThat(Integer.parseInt(customer.getFicoCreditScore()))
                    .isGreaterThan(SERVICE_MAXIMUM_CREDIT_SCORE);
            assertThat(customer.getFicoCreditScore()).isEqualTo("999");
        }
    }

    @Nested
    @DisplayName("National identifier held in the schema's one widened, nullable column")
    class NationalIdentifier {

        @Test
        @DisplayName("the seeded value is nine digits in the record image, so the source width is "
                + "reproduced by the seeded data even though the column is wider")
        void theSeededValueIsNineDigits() {
            assertThat(revealed(customer.getCustSsn())).hasSize(NATIONAL_ID_WIDTH);
            assertThat(revealed(customer.getCustSsn())).containsOnlyDigits();
            assertThat(customer.getCustSsn())
                    .as("what the column holds is the protected form, which is longer and is not"
                            + " the digits themselves")
                    .hasSizeGreaterThan(NATIONAL_ID_WIDTH)
                    .doesNotContain(NATIONAL_ID_CLEARTEXT);
        }

        @Test
        @DisplayName("a value far longer than the nine source digits is stored unchanged, which is what "
                + "the widened column exists for: protected values do not fit the plaintext width")
        void aValueLongerThanTheSourceWidthIsStoredUnchanged() {
            final String protectedValue = sealed("L".repeat(120));

            customer.setCustSsn(protectedValue);

            assertThat(customer.getCustSsn()).isEqualTo(protectedValue);
            assertThat(customer.getCustSsn().length()).isGreaterThan(NATIONAL_ID_WIDTH);
            assertThat(customer.getCustSsn().length()).isLessThanOrEqualTo(255);
        }

        @Test
        @DisplayName("the national identifier is the one field of this entity whose column permits an "
                + "absent value, so clearing it is a legitimate state rather than a defect")
        void theNationalIdentifierMayBeAbsent() {
            customer.setCustSsn(null);

            assertThat(customer.getCustSsn()).isNull();
            assertThat(customer.getCustId()).isEqualTo(FIRST_ID);
        }
    }

    @Nested
    @DisplayName("Mutability required by the account-update transaction")
    class Mutability {

        @Test
        @DisplayName("every mapped field round-trips through its setter, which the account-update "
                + "transaction needs in order to apply a screen submission field by field")
        void everyMappedFieldRoundTripsThroughItsSetter() {
            final Customer target = new Customer();

            target.setCustId("000000050");
            target.setFirstName("Ada");
            target.setMiddleName("Byron");
            target.setLastName("Lovelace");
            target.setAddrLine1("1 Analytical Way");
            target.setAddrLine2("Engine Wing");
            target.setAddrLine3("London");
            target.setAddrStateCd("VA");
            target.setAddrCountryCd("GBR");
            target.setAddrZip("00000-0001");
            target.setPhoneNum1("(000)000-0001  ");
            target.setPhoneNum2("(000)000-0002  ");
            target.setCustSsn(sealed("111223333"));
            target.setGovtIssuedId(sealed("VA-DL-99999999999999"));
            target.setCustDob("1815-12-10");
            target.setEftAccountId("0000000001");
            target.setPriCardHolderInd("N");
            target.setFicoCreditScore("850");

            assertThat(target.getCustId()).isEqualTo("000000050");
            assertThat(target.getFirstName()).isEqualTo("Ada");
            assertThat(target.getMiddleName()).isEqualTo("Byron");
            assertThat(target.getLastName()).isEqualTo("Lovelace");
            assertThat(target.getAddrLine1()).isEqualTo("1 Analytical Way");
            assertThat(target.getAddrLine2()).isEqualTo("Engine Wing");
            assertThat(target.getAddrLine3()).isEqualTo("London");
            assertThat(target.getAddrStateCd()).isEqualTo("VA");
            assertThat(target.getAddrCountryCd()).isEqualTo("GBR");
            assertThat(target.getAddrZip()).isEqualTo("00000-0001");
            assertThat(target.getPhoneNum1()).isEqualTo("(000)000-0001  ");
            assertThat(target.getPhoneNum2()).isEqualTo("(000)000-0002  ");
            assertThat(revealed(target.getCustSsn())).isEqualTo("111223333");
            assertThat(revealed(target.getGovtIssuedId())).isEqualTo("VA-DL-99999999999999");
            assertThat(target.getCustDob()).isEqualTo("1815-12-10");
            assertThat(target.getEftAccountId()).isEqualTo("0000000001");
            assertThat(target.getPriCardHolderInd()).isEqualTo("N");
            assertThat(target.getFicoCreditScore()).isEqualTo("850");
        }

        @Test
        @DisplayName("the identifier itself is mutable, because the provider assigns it after "
                + "instantiating the entity")
        void theIdentifierItselfIsMutable() {
            customer.setCustId("000000002");

            assertThat(customer.getCustId()).isEqualTo("000000002");
        }
    }

    @Nested
    @DisplayName("Identity founded on the business key alone")
    class Identity {

        @Test
        @DisplayName("two independently constructed customers with the same identifier are equal and "
                + "hash alike, even when every other attribute differs, because the key is the identity")
        void sameIdentifierMeansEqualEvenWhenEverythingElseDiffers() {
            final Customer other = new Customer(FIRST_ID, "Ada", "Byron", "Lovelace", "1 Way", "2 Way",
                    "3 Way", "VA", "GBR", "00000-0001", "(000)000-0001  ", "(000)000-0002  ",
                    sealed("111223333"), sealed("VA-DL-1"), "1815-12-10", "0000000001", "N", "850");

            assertThat(customer).isEqualTo(other);
            assertThat(customer).hasSameHashCodeAs(other);
        }

        @Test
        @DisplayName("a different identifier means a different customer, even when every other "
                + "attribute is identical")
        void aDifferentIdentifierMeansADifferentCustomer() {
            final Customer other = new Customer("000000002", FIRST_NAME, MIDDLE_NAME, LAST_NAME,
                    ADDRESS_LINE_1, ADDRESS_LINE_2, ADDRESS_LINE_3, STATE_CODE, COUNTRY_CODE, ZIP,
                    PHONE_1, PHONE_2, NATIONAL_ID, GOVT_ISSUED_ID, DATE_OF_BIRTH, EFT_ACCOUNT_ID,
                    PRIMARY_HOLDER, CREDIT_SCORE);

            assertThat(customer).isNotEqualTo(other);
        }

        @Test
        @DisplayName("identity is reflexive, symmetric and transitive across independently constructed "
                + "instances carrying the same identifier")
        void identityIsReflexiveSymmetricAndTransitive() {
            final Customer second = new Customer();
            final Customer third = new Customer();
            second.setCustId(FIRST_ID);
            third.setCustId(FIRST_ID);

            assertThat(customer.equals(customer)).isTrue();
            assertThat(customer.equals(second)).isTrue();
            assertThat(second.equals(customer)).isTrue();
            assertThat(second.equals(third)).isTrue();
            assertThat(customer.equals(third)).isTrue();
        }

        @Test
        @DisplayName("zero filling is significant: an identifier of nine digits is not equal to the same "
                + "value unpadded, because the key is text and not a number")
        void zeroFillingIsSignificant() {
            final Customer unpadded = new Customer();
            unpadded.setCustId("1");

            assertThat(customer).isNotEqualTo(unpadded);
        }

        @Test
        @DisplayName("equality rejects an absent operand and a foreign type through the type pattern "
                + "rather than throwing - including the account entity, whose key is also text")
        void equalityRejectsAbsentOperandAndForeignType() {
            assertThat(customer.equals(null)).isFalse();
            assertThat(customer.equals(FIRST_ID)).isFalse();
            assertThat(customer.equals(new Account())).isFalse();
        }

        @Test
        @DisplayName("two customers with absent identifiers are equal, so a provider may compare two "
                + "not-yet-populated instances without surprise")
        void twoCustomersWithAbsentIdentifiersAreEqual() {
            assertThat(new Customer()).isEqualTo(new Customer());
            assertThat(new Customer()).hasSameHashCodeAs(new Customer());
        }

        @Test
        @DisplayName("all fifty fixture identifiers stay distinct in a hash set, so no two seeded rows "
                + "collapse onto one customer")
        void allFixtureIdentifiersStayDistinctInAHashSet() {
            final Set<Customer> customers = new HashSet<>();

            for (int row = 1; row <= FIXTURE_ROWS; row++) {
                final Customer seeded = new Customer();
                seeded.setCustId(String.format(Locale.ROOT, "%09d", row));
                customers.add(seeded);
            }

            assertThat(customers).hasSize(FIXTURE_ROWS);
        }
    }

    @Nested
    @DisplayName("Deliberate absence of a state-rendering representation")
    class NoStateRendering {

        @Test
        @DisplayName("no representation is overridden, so the inherited identity form is used and the "
                + "rendering names the type followed by an at sign")
        void theInheritedIdentityFormIsUsed() {
            final String rendered = customer.toString();

            assertThat(rendered).startsWith("com.carddemo.domain.Customer@");
            assertThat(rendered).contains("@");
        }

        @Test
        @DisplayName("no personal datum can leak through the rendering: neither name, nor address, nor "
                + "national identifier, nor date of birth, nor credit score appears in it")
        void noPersonalDatumLeaksThroughTheRendering() {
            final String rendered = customer.toString();

            assertThat(rendered).doesNotContain("Immanuel");
            assertThat(rendered).doesNotContain("Madeline");
            assertThat(rendered).doesNotContain("Kessler");
            assertThat(rendered).doesNotContain("020973888");
            assertThat(rendered).doesNotContain("1961-06-08");
            assertThat(rendered).doesNotContain("274");
            assertThat(rendered).doesNotContain("12546");
        }

        @Test
        @DisplayName("the rendering of an empty customer does not fail and still leaks nothing, because "
                + "it never reads the entity's state at all")
        void theRenderingOfAnEmptyCustomerDoesNotFail() {
            assertThat(new Customer().toString()).startsWith("com.carddemo.domain.Customer@");
        }

        @Test
        @DisplayName("the rendering says nothing about business identity: two customers that are equal "
                + "by business key each render a form that names neither the key nor any other field, "
                + "which is the expected consequence of not overriding the representation")
        void theRenderingSaysNothingAboutBusinessIdentity() {
            final Customer twin = new Customer();
            twin.setCustId(FIRST_ID);

            assertThat(customer).isEqualTo(twin);
            assertThat(customer.toString()).doesNotContain(FIRST_ID);
            assertThat(twin.toString()).doesNotContain(FIRST_ID);
            assertThat(twin.toString()).startsWith("com.carddemo.domain.Customer@");
        }
    }
}
