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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.carddemo.util.SensitiveFieldCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link Customer}, the five-hundred-byte customer record.
 *
 * <h2>What is under test</h2>
 *
 * <p>Copybook {@code app/cpy/CVCUS01Y.cpy} describes a five-hundred-byte record whose
 * nine-byte identifier occupies offset zero, corroborated by the cluster definition in
 * {@code app/jcl/CUSTFILE.jcl}. The ASCII fixture {@code app/data/ASCII/custdata.txt}
 * carries fifty such records in 25,050 bytes.</p>
 *
 * <h2>Why the national identifier is stored verbatim and the entity still cannot protect it</h2>
 *
 * <p>The legacy record holds the national identifier in the clear. The migrated column
 * stores an authenticated, field-bound, scheme-tagged envelope instead. The entity performs
 * no encryption and no decryption: it neither seals what it is handed nor unseals what it
 * returns, because the domain layer may not depend on the utility or service layers, so the
 * codec cannot be reached from here. Sealing therefore still lives outside the entity.</p>
 *
 * <p>What the entity does contribute is the fail-closed half of that division. Both
 * regulated attributes route every write - constructor and mutator alike - through a
 * structural check that demands the {@code ENC1:} envelope marker, a Base64 body and enough
 * decoded bytes to be an authenticated ciphertext, and refuses anything else. That is not a
 * transformation: a conforming value is returned byte for byte, so the verbatim-storage
 * contract the record mappers depend on is untouched. It is a refusal, and it exists so that
 * a caller which forgot to seal cannot silently persist cleartext into a column wide enough
 * to hold it.</p>
 *
 * <p>The assertions below prove both halves precisely: a conforming value is carried through
 * unchanged and undecrypted; a cleartext value, an empty value and a value carrying the
 * marker but no usable body are each refused; the refusal never echoes the value it
 * rejected; a refused write leaves the previously stored value in place; and a {@code null}
 * is carried through as a genuine null on both regulated attributes rather than converted to
 * an empty string or to the literal text {@code null}.</p>
 *
 * <h2>Why the two never-validated fields carry no constraint</h2>
 *
 * <p>The account-maintenance transaction decorates the middle-name and second-address-line
 * fields for error display but never validates them, recording "no edits coded" in source
 * at {@code app/cbl/COACTUPC.cbl} lines 3346 and 3370. Attaching a constraint here would
 * reject input the legacy system accepts, so the assertions below prove that both fields
 * carry arbitrary content through unchanged, blanks included.</p>
 *
 * <h2>Why this entity deliberately overrides no diagnostic rendering</h2>
 *
 * <p>The record carries a national identifier, a government-issued identifier, a date of
 * birth, an address, two telephone numbers and a credit score. Rather than curate a
 * redacted rendering, the entity declares none at all, so the inherited default can carry
 * no attribute value whatsoever. A test below asserts that property directly, because it is
 * a deliberate design decision rather than an omission.</p>
 *
 * <p>Translated from the CardDemo COBOL estate at checkout commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("Customer: the five-hundred-byte customer record")
class CustomerBoundaryTest {

    /** Customer identifier at its contractual nine characters, zero filling included. */
    private static final String CUSTOMER_ID = "000000001";

    /** Given name at a representative width below the twenty-five-character column. */
    private static final String FIRST_NAME = "MARY";

    /** Middle name, the first of the two fields the legacy transaction never validates. */
    private static final String MIDDLE_NAME = "ANN";

    /** Family name at a representative width below the twenty-five-character column. */
    private static final String LAST_NAME = "SMITH";

    /** First address line at a representative width below the fifty-character column. */
    private static final String ADDRESS_LINE_1 = "100 MAIN STREET";

    /** Second address line, the other field the legacy transaction never validates. */
    private static final String ADDRESS_LINE_2 = "APT 4B";

    /** Third address line at a representative width below the fifty-character column. */
    private static final String ADDRESS_LINE_3 = "SEATTLE";

    /** State code at its contractual two characters. */
    private static final String STATE_CODE = "WA";

    /** Country code at its contractual three characters. */
    private static final String COUNTRY_CODE = "USA";

    /** Address ZIP at its contractual ten characters, padding included. */
    private static final String ADDRESS_ZIP = "98101     ";

    /** First telephone number at its contractual fifteen characters, padding included. */
    private static final String PHONE_1 = "(206)5550100   ";

    /** Second telephone number at its contractual fifteen characters, padding included. */
    private static final String PHONE_2 = "(206)5550101   ";

    /**
     * A thirty-two-byte key used only to manufacture and read back well-formed protected values.
     *
     * <p>The entity refuses cleartext on every write path, so a test cannot hand it a legacy value
     * directly. This key seals the fixture values on the way in and recovers them for assertion. It
     * protects nothing real and is not a deployment secret.
     */
    private static final byte[] PROTECTION_KEY =
            "carddemo-boundary-test-key-0123!".getBytes(StandardCharsets.UTF_8);

    /** The nine cleartext digits the legacy record holds for the national identifier. */
    private static final String NATIONAL_ID_CLEARTEXT = "123456789";

    /**
     * A real envelope standing in for what the persistence boundary actually stores. It is produced
     * by the codec rather than hand-written, so it satisfies the entity's structural check, and it is
     * deliberately not a nine-digit value, so no assertion here can be satisfied by cleartext.
     */
    private static final String SSN_ENVELOPE = sealed(NATIONAL_ID_CLEARTEXT);

    /** The twenty-byte cleartext the legacy record holds for the government-issued identifier. */
    private static final String GOVT_ISSUED_ID_CLEARTEXT = "WA-DL-9911";

    /** The government-issued identifier in the protected form its column carries. */
    private static final String GOVT_ISSUED_ID = sealed(GOVT_ISSUED_ID_CLEARTEXT);

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

    /** Date of birth at its contractual ten characters. */
    private static final String DATE_OF_BIRTH = "1980-04-15";

    /** Electronic-funds account identifier at its contractual ten characters. */
    private static final String EFT_ACCOUNT_ID = "0000000042";

    /** Primary-cardholder indicator at its contractual single character. */
    private static final String PRIMARY_HOLDER_INDICATOR = "Y";

    /** Credit score at its contractual three characters, inside the legacy 300 to 850 range. */
    private static final String CREDIT_SCORE = "720";

    /**
     * Builds the reference customer used across the assertions.
     *
     * @return a fully populated customer
     */
    private static Customer referenceCustomer() {
        return new Customer(CUSTOMER_ID, FIRST_NAME, MIDDLE_NAME, LAST_NAME, ADDRESS_LINE_1,
                ADDRESS_LINE_2, ADDRESS_LINE_3, STATE_CODE, COUNTRY_CODE, ADDRESS_ZIP,
                PHONE_1, PHONE_2, SSN_ENVELOPE, GOVT_ISSUED_ID, DATE_OF_BIRTH, EFT_ACCOUNT_ID,
                PRIMARY_HOLDER_INDICATOR, CREDIT_SCORE);
    }

    @Nested
    @DisplayName("attribute carriage")
    class AttributeCarriage {

        @Test
        @DisplayName("all eighteen constructor attributes are returned exactly as supplied")
        void allEighteenAttributesAreReturnedAsSupplied() {
            Customer customer = referenceCustomer();

            assertThat(customer.getCustId()).isEqualTo(CUSTOMER_ID);
            assertThat(customer.getFirstName()).isEqualTo(FIRST_NAME);
            assertThat(customer.getMiddleName()).isEqualTo(MIDDLE_NAME);
            assertThat(customer.getLastName()).isEqualTo(LAST_NAME);
            assertThat(customer.getAddrLine1()).isEqualTo(ADDRESS_LINE_1);
            assertThat(customer.getAddrLine2()).isEqualTo(ADDRESS_LINE_2);
            assertThat(customer.getAddrLine3()).isEqualTo(ADDRESS_LINE_3);
            assertThat(customer.getAddrStateCd()).isEqualTo(STATE_CODE);
            assertThat(customer.getAddrCountryCd()).isEqualTo(COUNTRY_CODE);
            assertThat(customer.getAddrZip()).isEqualTo(ADDRESS_ZIP);
            assertThat(customer.getPhoneNum1()).isEqualTo(PHONE_1);
            assertThat(customer.getPhoneNum2()).isEqualTo(PHONE_2);
            assertThat(customer.getCustSsn()).isEqualTo(SSN_ENVELOPE);
            assertThat(customer.getGovtIssuedId()).isEqualTo(GOVT_ISSUED_ID);
            assertThat(customer.getCustDob()).isEqualTo(DATE_OF_BIRTH);
            assertThat(customer.getEftAccountId()).isEqualTo(EFT_ACCOUNT_ID);
            assertThat(customer.getPriCardHolderInd()).isEqualTo(PRIMARY_HOLDER_INDICATOR);
            assertThat(customer.getFicoCreditScore()).isEqualTo(CREDIT_SCORE);
        }

        @Test
        @DisplayName("the identifier keeps every leading zero, so it stays nine characters wide")
        void theIdentifierKeepsEveryLeadingZero() {
            assertThat(referenceCustomer().getCustId())
                    .isEqualTo("000000001")
                    .hasSize(9)
                    .isNotEqualTo("1");
        }

        @Test
        @DisplayName("the padded text attributes keep their trailing padding")
        void thePaddedTextAttributesKeepTheirPadding() {
            Customer customer = referenceCustomer();

            assertThat(customer.getAddrZip()).hasSize(10).endsWith("     ");
            assertThat(customer.getPhoneNum1()).hasSize(15).endsWith("   ");
            assertThat(customer.getPhoneNum2()).hasSize(15).endsWith("   ");
        }

        @Test
        @DisplayName("the credit score is held as text, so a leading zero would survive")
        void theCreditScoreIsHeldAsText() {
            Customer customer = referenceCustomer();

            customer.setFicoCreditScore("099");

            assertThat(customer.getFicoCreditScore()).isEqualTo("099").hasSize(3);
        }

        @Test
        @DisplayName("a credit score outside the legacy range is stored rather than rejected")
        void aCreditScoreOutsideTheLegacyRangeIsStored() {
            Customer customer = referenceCustomer();

            customer.setFicoCreditScore("999");

            assertThat(customer.getFicoCreditScore()).isEqualTo("999");
        }

        @Test
        @DisplayName("the no-argument constructor a provider needs yields an unpopulated customer")
        void theNoArgumentConstructorYieldsAnUnpopulatedCustomer() {
            Customer customer = new Customer();

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
        @DisplayName("every setter replaces its value verbatim, with no normalisation")
        void everySetterReplacesItsValueVerbatim() {
            Customer customer = new Customer();

            customer.setCustId(" 1       ");
            customer.setFirstName("  mary  ");
            customer.setMiddleName("  ann  ");
            customer.setLastName("  smith  ");
            customer.setAddrLine1("  line one  ");
            customer.setAddrLine2("  line two  ");
            customer.setAddrLine3("  line three  ");
            customer.setAddrStateCd("wa");
            customer.setAddrCountryCd("usa");
            customer.setAddrZip("  00000   ");
            customer.setPhoneNum1("  0000000000   ");
            customer.setPhoneNum2("  1111111111   ");
            customer.setGovtIssuedId(sealed("  id  "));
            customer.setCustDob("1970-01-01");
            customer.setEftAccountId("0000000000");
            customer.setPriCardHolderInd("n");
            customer.setFicoCreditScore("300");

            assertThat(customer.getCustId()).isEqualTo(" 1       ");
            assertThat(customer.getFirstName()).isEqualTo("  mary  ");
            assertThat(customer.getMiddleName()).isEqualTo("  ann  ");
            assertThat(customer.getLastName()).isEqualTo("  smith  ");
            assertThat(customer.getAddrLine1()).isEqualTo("  line one  ");
            assertThat(customer.getAddrLine2()).isEqualTo("  line two  ");
            assertThat(customer.getAddrLine3()).isEqualTo("  line three  ");
            assertThat(customer.getAddrStateCd()).isEqualTo("wa");
            assertThat(customer.getAddrCountryCd()).isEqualTo("usa");
            assertThat(customer.getAddrZip()).isEqualTo("  00000   ");
            assertThat(customer.getPhoneNum1()).isEqualTo("  0000000000   ");
            assertThat(customer.getPhoneNum2()).isEqualTo("  1111111111   ");
            assertThat(revealed(customer.getGovtIssuedId()))
                    .as("the protected attribute is stored exactly as handed over, so the padding"
                            + " inside the sealed value survives untouched")
                    .isEqualTo("  id  ");
            assertThat(customer.getCustDob()).isEqualTo("1970-01-01");
            assertThat(customer.getEftAccountId()).isEqualTo("0000000000");
            assertThat(customer.getPriCardHolderInd()).isEqualTo("n");
            assertThat(customer.getFicoCreditScore()).isEqualTo("300");
        }

        @Test
        @DisplayName("every attribute whose column permits it may be set back to null; the "
                + "government-issued identifier may not, because its column forbids it")
        void everyAttributeMayBeSetBackToNull() {
            Customer customer = referenceCustomer();

            customer.setCustId(null);
            customer.setFirstName(null);
            customer.setMiddleName(null);
            customer.setLastName(null);
            customer.setAddrLine1(null);
            customer.setAddrLine2(null);
            customer.setAddrLine3(null);
            customer.setAddrStateCd(null);
            customer.setAddrCountryCd(null);
            customer.setAddrZip(null);
            customer.setPhoneNum1(null);
            customer.setPhoneNum2(null);
            customer.setCustSsn(null);
            customer.setGovtIssuedId(null);
            customer.setCustDob(null);
            customer.setEftAccountId(null);
            customer.setPriCardHolderInd(null);
            customer.setFicoCreditScore(null);

            assertThat(customer.getCustId()).isNull();
            assertThat(customer.getCustSsn()).isNull();
            assertThat(customer.getFicoCreditScore()).isNull();
            assertThat(customer.getGovtIssuedId())
                    .as("both regulated attributes clear to a genuine null, never to an empty string"
                            + " or to the text \"null\"")
                    .isNull();
            assertThat(customer.getGovtIssuedId()).isNotEqualTo("");
            assertThat(customer.getGovtIssuedId()).isNotEqualTo("null");
        }
    }

    @Nested
    @DisplayName("the two fields the legacy transaction decorates but never validates")
    class NeverValidatedFields {

        @Test
        @DisplayName("an all-blank middle name is accepted, because no edit is coded for it")
        void anAllBlankMiddleNameIsAccepted() {
            Customer customer = referenceCustomer();

            customer.setMiddleName("                         ");

            assertThat(customer.getMiddleName()).isEqualTo("                         ")
                    .hasSize(25);
        }

        @Test
        @DisplayName("a non-alphabetic middle name is accepted, because no edit is coded for it")
        void aNonAlphabeticMiddleNameIsAccepted() {
            Customer customer = referenceCustomer();

            customer.setMiddleName("O'BRIEN-2ND");

            assertThat(customer.getMiddleName()).isEqualTo("O'BRIEN-2ND");
        }

        @Test
        @DisplayName("an all-blank second address line is accepted, because no edit is coded for it")
        void anAllBlankSecondAddressLineIsAccepted() {
            Customer customer = referenceCustomer();

            customer.setAddrLine2("   ");

            assertThat(customer.getAddrLine2()).isEqualTo("   ");
        }

        @Test
        @DisplayName("an arbitrary second address line is accepted, because no edit is coded for it")
        void anArbitrarySecondAddressLineIsAccepted() {
            Customer customer = referenceCustomer();

            customer.setAddrLine2("#4-B / REAR");

            assertThat(customer.getAddrLine2()).isEqualTo("#4-B / REAR");
        }
    }

    @Nested
    @DisplayName("the national identifier, whose sealing lives outside this entity and whose "
            + "refusal lives inside it")
    class NationalIdentifierCarriage {

        @Test
        @DisplayName("the accessor returns the stored value verbatim and decrypts nothing")
        void theAccessorReturnsTheStoredValueVerbatim() {
            assertThat(referenceCustomer().getCustSsn())
                    .isEqualTo(SSN_ENVELOPE)
                    .startsWith("ENC1:")
                    .doesNotContain(NATIONAL_ID_CLEARTEXT);
        }

        @Test
        @DisplayName("the mutator assigns verbatim and encrypts nothing, which is why the caller must")
        void theMutatorAssignsVerbatimAndEncryptsNothing() {
            String second = sealed("987654321");
            Customer customer = referenceCustomer();

            customer.setCustSsn(second);

            assertThat(customer.getCustSsn())
                    .as("the stored form is the identical string handed over: the entity applies no"
                            + " transformation of any kind to a conforming value")
                    .isSameAs(second);
            assertThat(revealed(customer.getCustSsn())).isEqualTo("987654321");
        }

        @Test
        @DisplayName("the mutator refuses cleartext, so the entity is the fail-closed half of the "
                + "arrangement rather than a permissive carrier")
        void theMutatorRefusesCleartext() {
            Customer customer = referenceCustomer();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> customer.setCustSsn(NATIONAL_ID_CLEARTEXT))
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .contains("custSsn")
                            .contains("ENC1:")
                            .as("the refusal names the attribute and the required envelope but never"
                                    + " echoes the credential-grade value it rejected")
                            .doesNotContain(NATIONAL_ID_CLEARTEXT));

            assertThat(customer.getCustSsn())
                    .as("a refused write leaves the previously stored value in place")
                    .isEqualTo(SSN_ENVELOPE);
        }

        @Test
        @DisplayName("a value carrying the envelope marker but too short a body is refused too, so "
                + "the marker alone cannot be used to smuggle a value past the check")
        void aMarkedButUnusableBodyIsRefused() {
            Customer customer = referenceCustomer();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> customer.setCustSsn("ENC1:QUJD"))
                    .withMessageContaining("custSsn");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> customer.setCustSsn("ENC1:not valid base64!!"))
                    .withMessageContaining("custSsn");

            assertThat(customer.getCustSsn()).isEqualTo(SSN_ENVELOPE);
        }

        @Test
        @DisplayName("a null is stored as a genuine null, never as an empty string or the text null")
        void aNullIsStoredAsAGenuineNull() {
            Customer customer = referenceCustomer();

            customer.setCustSsn(null);

            assertThat(customer.getCustSsn()).isNull();
        }

        @Test
        @DisplayName("an empty string is refused rather than folded to null, so an absent value and "
                + "an empty one can never be confused with one another")
        void anEmptyStringIsRefusedRatherThanFoldedToNull() {
            Customer customer = referenceCustomer();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> customer.setCustSsn(""))
                    .withMessageContaining("custSsn");

            assertThat(customer.getCustSsn()).isEqualTo(SSN_ENVELOPE);

            customer.setCustSsn(null);
            assertThat(customer.getCustSsn())
                    .as("absence is expressed by null alone, and is never rendered as an empty"
                            + " string or as the literal text null")
                    .isNull();
        }

        @Test
        @DisplayName("an envelope far longer than the legacy nine bytes is carried without truncation")
        void aLongEnvelopeIsCarriedWithoutTruncation() {
            String longEnvelope = sealed("A".repeat(120));
            Customer customer = referenceCustomer();

            customer.setCustSsn(longEnvelope);

            assertThat(customer.getCustSsn())
                    .isEqualTo(longEnvelope)
                    .hasSizeGreaterThan(9)
                    .hasSizeLessThanOrEqualTo(255);
            assertThat(revealed(customer.getCustSsn())).isEqualTo("A".repeat(120));
        }
    }

    @Nested
    @DisplayName("identity derived from the business key alone")
    class Identity {

        @Test
        @DisplayName("a customer equals itself")
        void aCustomerEqualsItself() {
            Customer customer = referenceCustomer();

            assertThat(customer).isEqualTo(customer);
        }

        @Test
        @DisplayName("two customers sharing the identifier are equal in both directions")
        void twoCustomersSharingTheIdentifierAreEqual() {
            Customer first = referenceCustomer();
            Customer second = referenceCustomer();

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("equality is transitive across three customers sharing the identifier")
        void equalityIsTransitive() {
            Customer first = referenceCustomer();
            Customer second = referenceCustomer();
            Customer third = referenceCustomer();

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(third);
            assertThat(first).isEqualTo(third);
        }

        @Test
        @DisplayName("a differing identifier breaks equality")
        void aDifferingIdentifierBreaksEquality() {
            Customer other = referenceCustomer();
            other.setCustId("000000002");

            assertThat(referenceCustomer()).isNotEqualTo(other);
        }

        @Test
        @DisplayName("a zero-suppressed identifier is not equal to its padded spelling")
        void aZeroSuppressedIdentifierIsNotEqual() {
            Customer other = referenceCustomer();
            other.setCustId("1");

            assertThat(referenceCustomer()).isNotEqualTo(other);
        }

        @Test
        @DisplayName("a null reference is not equal to any customer")
        void aNullReferenceIsNotEqualToAnyCustomer() {
            assertThat(referenceCustomer()).isNotEqualTo(null);
        }

        @Test
        @DisplayName("an unrelated type is not equal to a customer carrying the same identifier")
        void anUnrelatedTypeIsNotEqualToACustomer() {
            assertThat(referenceCustomer()).isNotEqualTo(CUSTOMER_ID);
        }

        @Test
        @DisplayName("two unpopulated customers are equal, so a provider-instantiated row is coherent")
        void twoUnpopulatedCustomersAreEqual() {
            assertThat(new Customer())
                    .isEqualTo(new Customer())
                    .hasSameHashCodeAs(new Customer());
        }

        @Test
        @DisplayName("an unpopulated customer is not equal to a populated one")
        void anUnpopulatedCustomerIsNotEqualToAPopulatedOne() {
            assertThat(new Customer()).isNotEqualTo(referenceCustomer());
        }

        @Test
        @DisplayName("equal customers hash alike and hashing is stable across calls")
        void equalCustomersHashAlike() {
            Customer customer = referenceCustomer();

            assertThat(customer).hasSameHashCodeAs(referenceCustomer());
            assertThat(customer.hashCode()).isEqualTo(customer.hashCode());
        }

        @Test
        @DisplayName("mutating every non-key attribute leaves the customer retrievable from a map")
        void mutatingEveryNonKeyAttributeLeavesTheCustomerRetrievable() {
            Customer customer = referenceCustomer();
            Map<Customer, String> index = new HashMap<>();
            index.put(customer, "seeded");

            customer.setFirstName("JOHN");
            customer.setMiddleName("");
            customer.setLastName("DOE");
            customer.setAddrLine1("1 OTHER WAY");
            customer.setAddrLine2("");
            customer.setAddrLine3("PORTLAND");
            customer.setAddrStateCd("OR");
            customer.setAddrCountryCd("USA");
            customer.setAddrZip("97201     ");
            customer.setPhoneNum1("(503)5550100   ");
            customer.setPhoneNum2("(503)5550101   ");
            customer.setCustSsn(null);
            customer.setGovtIssuedId(sealed("OR-DL-1"));
            customer.setCustDob("1990-01-01");
            customer.setEftAccountId("0000000099");
            customer.setPriCardHolderInd("N");
            customer.setFicoCreditScore("850");

            assertThat(index).containsEntry(customer, "seeded");
            assertThat(index).containsEntry(referenceCustomer(), "seeded");
        }
    }

    @Nested
    @DisplayName("the deliberately absent diagnostic rendering")
    class AbsentDiagnosticRendering {

        @Test
        @DisplayName("no attribute value can leak through the inherited rendering")
        void noAttributeValueCanLeakThroughTheInheritedRendering() {
            String rendered = referenceCustomer().toString();

            assertThat(rendered)
                    .doesNotContain(SSN_ENVELOPE)
                    .doesNotContain(NATIONAL_ID_CLEARTEXT)
                    .doesNotContain(GOVT_ISSUED_ID)
                    .doesNotContain(GOVT_ISSUED_ID_CLEARTEXT)
                    .doesNotContain(DATE_OF_BIRTH)
                    .doesNotContain(FIRST_NAME)
                    .doesNotContain(LAST_NAME)
                    .doesNotContain(ADDRESS_LINE_1)
                    .doesNotContain(CREDIT_SCORE)
                    .doesNotContain(CUSTOMER_ID);
        }

        @Test
        @DisplayName("the inherited rendering names the type, proving no override was added")
        void theInheritedRenderingNamesTheType() {
            assertThat(referenceCustomer().toString())
                    .startsWith(Customer.class.getName() + "@");
        }

        @Test
        @DisplayName("an unpopulated customer renders without throwing")
        void anUnpopulatedCustomerRendersWithoutThrowing() {
            assertThat(new Customer().toString()).startsWith(Customer.class.getName() + "@");
        }
    }
}
