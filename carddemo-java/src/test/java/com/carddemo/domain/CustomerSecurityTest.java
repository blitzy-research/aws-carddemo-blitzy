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
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.carddemo.util.SensitiveFieldCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Acceptance for the customer entity, with the persistence guard on its two regulated identifiers as
 * the principal subject.
 *
 * <p>The 500-byte legacy customer image carries a nine-digit national identifier at offset 279 and a
 * twenty-character government-issued identifier at offset 288, both in cleartext, protected on the
 * mainframe only by dataset-level access control. Carrying that arrangement into a relational store
 * would leave both readable to anyone holding a connection or a backup, so the entity refuses to hold
 * either one in cleartext. The refusal is a guard rather than a transformation: the entity does not
 * encrypt, it declines, which means the encryption must already have happened upstream and cannot be
 * forgotten silently.
 *
 * <p>Two classes of assertion matter here. The first is that the guard actually rejects every shape
 * of unprotected value a caller might reach the boundary with. The second is the behavioural pin in
 * {@link EnvelopeMarkerPin}: the entity declares its own copy of the scheme marker because the domain
 * layer may not depend on the utility layer, and a duplicated constant that drifts is worse than no
 * constant at all. Rather than compare the two constants reflectively, the pin seals a value with the
 * real codec and requires the entity to accept it, then marks a value differently and requires the
 * entity to refuse it - which is the property that actually needs to hold.
 *
 * <p>Every other attribute is asserted to be carried verbatim, because the record layout is the
 * contract and the entity is not entitled to normalise, trim or pad anything.
 */
@DisplayName("Customer - regulated identifiers cannot reach the column in cleartext")
class CustomerSecurityTest {

    /** A 32-byte key used only to manufacture well-formed envelopes for these assertions. */
    private static final byte[] KEY =
            "carddemo-customer-test-key-0123!".getBytes(StandardCharsets.UTF_8);

    /** Synthetic nine-digit national identifier, of the shape the legacy field carries. */
    private static final String SSN_CLEARTEXT = "123456789";

    /** Synthetic twenty-character government-issued identifier. */
    private static final String GOVT_ID_CLEARTEXT = "GOVTID00000000000001";

    private static final String CUST_ID = "000000001";
    private static final String FIRST_NAME = "Jane                     ";
    private static final String MIDDLE_NAME = "Q                        ";
    private static final String LAST_NAME = "Public                   ";
    private static final String ADDR_LINE_1 = "1 Example Street                                  ";
    private static final String ADDR_LINE_2 = "Suite 100                                         ";
    private static final String ADDR_LINE_3 = "Example City                                      ";
    private static final String STATE_CD = "NY";
    private static final String COUNTRY_CD = "USA";
    private static final String ZIP = "10001     ";
    private static final String PHONE_1 = "(212)5550100   ";
    private static final String PHONE_2 = "(212)5550101   ";
    private static final String DOB = "1980-01-01";
    private static final String EFT_ACCOUNT_ID = "0000000001";
    private static final String PRI_CARD_HOLDER_IND = "Y";
    private static final String FICO = "750";

    /**
     * Seals a value with the production codec, producing exactly the shape the entity must accept.
     *
     * @param cleartext the value to seal
     * @return a well-formed {@code ENC1} envelope
     */
    private static String sealed(final String cleartext) {
        return SensitiveFieldCodec.protect(cleartext, KEY);
    }

    /**
     * Builds a fully populated customer whose two regulated identifiers are properly sealed.
     *
     * @return a customer that the guard accepts in full
     */
    private static Customer populated() {
        return new Customer(CUST_ID, FIRST_NAME, MIDDLE_NAME, LAST_NAME,
                ADDR_LINE_1, ADDR_LINE_2, ADDR_LINE_3, STATE_CD, COUNTRY_CD, ZIP,
                PHONE_1, PHONE_2, sealed(SSN_CLEARTEXT), sealed(GOVT_ID_CLEARTEXT),
                DOB, EFT_ACCOUNT_ID, PRI_CARD_HOLDER_IND, FICO);
    }

    /**
     * Builds a customer with a caller-supplied national identifier, leaving every other attribute
     * well formed, so that a single attribute can be varied in isolation.
     *
     * @param ssn the value to place in the national-identifier attribute
     * @return the constructed customer
     */
    private static Customer withSsn(final String ssn) {
        return new Customer(CUST_ID, FIRST_NAME, MIDDLE_NAME, LAST_NAME,
                ADDR_LINE_1, ADDR_LINE_2, ADDR_LINE_3, STATE_CD, COUNTRY_CD, ZIP,
                PHONE_1, PHONE_2, ssn, sealed(GOVT_ID_CLEARTEXT),
                DOB, EFT_ACCOUNT_ID, PRI_CARD_HOLDER_IND, FICO);
    }

    /**
     * Builds a customer with a caller-supplied government-issued identifier, leaving every other
     * attribute well formed.
     *
     * @param govtId the value to place in the government-issued-identifier attribute
     * @return the constructed customer
     */
    private static Customer withGovtId(final String govtId) {
        return new Customer(CUST_ID, FIRST_NAME, MIDDLE_NAME, LAST_NAME,
                ADDR_LINE_1, ADDR_LINE_2, ADDR_LINE_3, STATE_CD, COUNTRY_CD, ZIP,
                PHONE_1, PHONE_2, sealed(SSN_CLEARTEXT), govtId,
                DOB, EFT_ACCOUNT_ID, PRI_CARD_HOLDER_IND, FICO);
    }

    @Nested
    @DisplayName("a properly sealed customer is accepted whole")
    class AcceptsSealedValues {

        @Test
        @DisplayName("the constructor accepts envelopes the codec produced")
        void theConstructorAcceptsCodecEnvelopes() {
            Customer customer = populated();

            assertThat(customer.getCustSsn()).startsWith(SensitiveFieldCodec.ENVELOPE_PREFIX);
            assertThat(customer.getGovtIssuedId()).startsWith(SensitiveFieldCodec.ENVELOPE_PREFIX);
        }

        @Test
        @DisplayName("both protected attributes are stored verbatim, not re-encoded")
        void bothProtectedAttributesAreStoredVerbatim() {
            String ssnEnvelope = sealed(SSN_CLEARTEXT);
            String govtEnvelope = sealed(GOVT_ID_CLEARTEXT);

            Customer customer = new Customer(CUST_ID, FIRST_NAME, MIDDLE_NAME, LAST_NAME,
                    ADDR_LINE_1, ADDR_LINE_2, ADDR_LINE_3, STATE_CD, COUNTRY_CD, ZIP,
                    PHONE_1, PHONE_2, ssnEnvelope, govtEnvelope,
                    DOB, EFT_ACCOUNT_ID, PRI_CARD_HOLDER_IND, FICO);

            assertThat(customer.getCustSsn()).isEqualTo(ssnEnvelope);
            assertThat(customer.getGovtIssuedId()).isEqualTo(govtEnvelope);
        }

        @Test
        @DisplayName("what is stored still opens back to the original cleartext")
        void whatIsStoredStillOpensBackToTheOriginal() {
            Customer customer = populated();

            assertThat(SensitiveFieldCodec.reveal(customer.getCustSsn(), KEY))
                    .isEqualTo(SSN_CLEARTEXT);
            assertThat(SensitiveFieldCodec.reveal(customer.getGovtIssuedId(), KEY))
                    .isEqualTo(GOVT_ID_CLEARTEXT);
        }

        @Test
        @DisplayName("neither cleartext identifier appears anywhere in the stored attributes")
        void neitherCleartextIdentifierAppearsInTheStoredAttributes() {
            Customer customer = populated();

            assertThat(customer.getCustSsn()).doesNotContain(SSN_CLEARTEXT);
            assertThat(customer.getGovtIssuedId()).doesNotContain(GOVT_ID_CLEARTEXT);
        }

        @Test
        @DisplayName("an absent national identifier is permitted, because the column is nullable")
        void anAbsentNationalIdentifierIsPermitted() {
            Customer customer = withSsn(null);

            assertThat(customer.getCustSsn()).isNull();
        }

        @Test
        @DisplayName("a sealed empty value is accepted, because absence and blankness differ")
        void aSealedEmptyValueIsAccepted() {
            Customer customer = withSsn(sealed(""));

            assertThat(customer.getCustSsn()).isNotNull();
            assertThat(SensitiveFieldCodec.reveal(customer.getCustSsn(), KEY)).isEmpty();
        }
    }

    @Nested
    @DisplayName("cleartext in the national identifier is refused")
    class RefusesCleartextNationalIdentifier {

        @ParameterizedTest(name = "\"{0}\" is refused")
        @ValueSource(strings = {
            "123456789",
            "000000000",
            "123-45-6789",
            "         ",
            "",
            "null",
            "NOT-ENCRYPTED"})
        @DisplayName("any value without the scheme marker is refused")
        void anyValueWithoutTheMarkerIsRefused(String cleartext) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withSsn(cleartext))
                    .withMessageContaining("custSsn")
                    .withMessageContaining("storing cleartext in this attribute is not permitted");
        }

        @Test
        @DisplayName("the marker alone is not enough - the body must decode")
        void theMarkerAloneIsNotEnough() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withSsn(SensitiveFieldCodec.ENVELOPE_PREFIX + "not base64!!"))
                    .withMessageContaining("custSsn")
                    .withMessageContaining("its body is not valid Base64");
        }

        @Test
        @DisplayName("a decodable but too-short body is refused, so a stub cannot masquerade")
        void aDecodableButTooShortBodyIsRefused() {
            String tooShort = SensitiveFieldCodec.ENVELOPE_PREFIX
                    + Base64.getEncoder().encodeToString(new byte[27]);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withSsn(tooShort))
                    .withMessageContaining("custSsn")
                    .withMessageContaining("too short to be an authenticated ciphertext");
        }

        @Test
        @DisplayName("a body of exactly the minimum length is accepted")
        void aBodyOfExactlyTheMinimumLengthIsAccepted() {
            String minimal = SensitiveFieldCodec.ENVELOPE_PREFIX
                    + Base64.getEncoder().encodeToString(
                            new byte[SensitiveFieldCodec.IV_LENGTH_BYTES
                                    + SensitiveFieldCodec.TAG_LENGTH_BYTES]);

            assertThat(withSsn(minimal).getCustSsn()).isEqualTo(minimal);
        }

        @Test
        @DisplayName("no refusal message quotes the value it refused")
        void noRefusalMessageQuotesTheValueItRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withSsn(SSN_CLEARTEXT))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .as("a rejection log line must not become the leak it prevented")
                            .doesNotContain(SSN_CLEARTEXT));
        }
    }

    @Nested
    @DisplayName("cleartext in the government-issued identifier is refused")
    class RefusesCleartextGovernmentIdentifier {

        @ParameterizedTest(name = "\"{0}\" is refused")
        @ValueSource(strings = {
            "GOVTID00000000000001",
            "D1234567890         ",
            "                    ",
            ""})
        @DisplayName("any value without the scheme marker is refused")
        void anyValueWithoutTheMarkerIsRefused(String cleartext) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withGovtId(cleartext))
                    .withMessageContaining("govtIssuedId")
                    .withMessageContaining("storing cleartext in this attribute is not permitted");
        }

        @Test
        @DisplayName("an absent value is accepted, exactly as it is for the national identifier")
        void anAbsentValueIsAccepted() {
            assertThat(withGovtId(null).getGovtIssuedId())
                    .as("absence must be representable: static SQL cannot produce an envelope without"
                            + " committing key material, so a mandatory column would only invite"
                            + " cleartext")
                    .isNull();
        }

        @Test
        @DisplayName("no refusal message quotes the value it refused")
        void noRefusalMessageQuotesTheValueItRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withGovtId(GOVT_ID_CLEARTEXT))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .doesNotContain(GOVT_ID_CLEARTEXT));
        }
    }

    @Nested
    @DisplayName("the mutators are guarded identically to the constructor")
    class GuardedMutators {

        @Test
        @DisplayName("a sealed national identifier can be replaced")
        void aSealedNationalIdentifierCanBeReplaced() {
            Customer customer = populated();
            String replacement = sealed("987654321");

            customer.setCustSsn(replacement);

            assertThat(customer.getCustSsn()).isEqualTo(replacement);
        }

        @Test
        @DisplayName("a cleartext national identifier cannot be assigned through the mutator")
        void aCleartextNationalIdentifierCannotBeAssigned() {
            Customer customer = populated();
            String before = customer.getCustSsn();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> customer.setCustSsn(SSN_CLEARTEXT))
                    .withMessageContaining("custSsn");
            assertThat(customer.getCustSsn())
                    .as("a refused assignment must leave the previous value untouched")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("the national identifier can be cleared through the mutator")
        void theNationalIdentifierCanBeCleared() {
            Customer customer = populated();

            customer.setCustSsn(null);

            assertThat(customer.getCustSsn()).isNull();
        }

        @Test
        @DisplayName("a sealed government-issued identifier can be replaced")
        void aSealedGovernmentIdentifierCanBeReplaced() {
            Customer customer = populated();
            String replacement = sealed("D9999999999         ");

            customer.setGovtIssuedId(replacement);

            assertThat(customer.getGovtIssuedId()).isEqualTo(replacement);
        }

        @Test
        @DisplayName("a cleartext government-issued identifier cannot be assigned")
        void aCleartextGovernmentIdentifierCannotBeAssigned() {
            Customer customer = populated();
            String before = customer.getGovtIssuedId();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> customer.setGovtIssuedId(GOVT_ID_CLEARTEXT))
                    .withMessageContaining("govtIssuedId");
            assertThat(customer.getGovtIssuedId()).isEqualTo(before);
        }

        @Test
        @DisplayName("the government-issued identifier can be cleared through the mutator")
        void theGovernmentIdentifierCanBeCleared() {
            Customer customer = populated();

            customer.setGovtIssuedId(null);

            assertThat(customer.getGovtIssuedId())
                    .as("both regulated attributes clear identically; only cleartext is refused")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("the entity's marker matches the codec's, pinned behaviourally")
    class EnvelopeMarkerPin {

        @Test
        @DisplayName("a value sealed by the real codec is accepted by the entity")
        void aValueSealedByTheRealCodecIsAccepted() {
            String fromTheCodec = SensitiveFieldCodec.protect(SSN_CLEARTEXT, KEY);

            assertThat(withSsn(fromTheCodec).getCustSsn())
                    .as("the entity duplicates the scheme marker because the domain layer may not "
                            + "depend on the utility layer; this is the assertion that keeps the two "
                            + "copies from drifting apart")
                    .isEqualTo(fromTheCodec);
        }

        @Test
        @DisplayName("the same body under a different marker is refused by the entity")
        void theSameBodyUnderADifferentMarkerIsRefused() {
            String fromTheCodec = SensitiveFieldCodec.protect(SSN_CLEARTEXT, KEY);
            String body = fromTheCodec.substring(SensitiveFieldCodec.ENVELOPE_PREFIX.length());

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withSsn("ENC2:" + body))
                    .withMessageContaining("storing cleartext in this attribute is not permitted");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withSsn("enc1:" + body))
                    .withMessageContaining("storing cleartext in this attribute is not permitted");
        }

        @Test
        @DisplayName("the guard and the codec's structural check agree on every candidate")
        void theGuardAndTheCodecStructuralCheckAgree() {
            String[] candidates = {
                sealed(SSN_CLEARTEXT),
                sealed(""),
                SSN_CLEARTEXT,
                "",
                "ENC1:",
                "ENC1:not base64!!",
                "ENC1:" + Base64.getEncoder().encodeToString(new byte[27]),
                "ENC1:" + Base64.getEncoder().encodeToString(new byte[28])};

            for (String candidate : candidates) {
                boolean codecAccepts = SensitiveFieldCodec.hasEnvelopeShape(candidate);
                boolean guardAccepts = guardAccepts(candidate);

                assertThat(guardAccepts)
                        .as("the entity guard and the codec check must not diverge on \"%s\"",
                                candidate.length() > 16 ? candidate.substring(0, 16) + "..." : candidate)
                        .isEqualTo(codecAccepts);
            }
        }

        /**
         * Exercises the entity's guard and reports acceptance as a boolean, so that its verdict can be
         * compared against the codec's structural check over a shared list of candidates.
         *
         * @param candidate the value to offer the entity
         * @return {@code true} when the entity accepts the value
         */
        private boolean guardAccepts(final String candidate) {
            try {
                withSsn(candidate);
                return true;
            } catch (IllegalArgumentException refused) {
                return false;
            }
        }
    }

    @Nested
    @DisplayName("every unprotected attribute is carried verbatim")
    class VerbatimAttributes {

        @Test
        @DisplayName("the identifier and the name parts keep their padding")
        void theIdentifierAndNamePartsKeepTheirPadding() {
            Customer customer = populated();

            assertThat(customer.getCustId()).isEqualTo(CUST_ID);
            assertThat(customer.getFirstName()).isEqualTo(FIRST_NAME);
            assertThat(customer.getMiddleName()).isEqualTo(MIDDLE_NAME);
            assertThat(customer.getLastName()).isEqualTo(LAST_NAME);
        }

        @Test
        @DisplayName("the address block keeps its padding")
        void theAddressBlockKeepsItsPadding() {
            Customer customer = populated();

            assertThat(customer.getAddrLine1()).isEqualTo(ADDR_LINE_1);
            assertThat(customer.getAddrLine2()).isEqualTo(ADDR_LINE_2);
            assertThat(customer.getAddrLine3()).isEqualTo(ADDR_LINE_3);
            assertThat(customer.getAddrStateCd()).isEqualTo(STATE_CD);
            assertThat(customer.getAddrCountryCd()).isEqualTo(COUNTRY_CD);
            assertThat(customer.getAddrZip()).isEqualTo(ZIP);
        }

        @Test
        @DisplayName("the remaining attributes keep their exact values")
        void theRemainingAttributesKeepTheirExactValues() {
            Customer customer = populated();

            assertThat(customer.getPhoneNum1()).isEqualTo(PHONE_1);
            assertThat(customer.getPhoneNum2()).isEqualTo(PHONE_2);
            assertThat(customer.getCustDob()).isEqualTo(DOB);
            assertThat(customer.getEftAccountId()).isEqualTo(EFT_ACCOUNT_ID);
            assertThat(customer.getPriCardHolderInd()).isEqualTo(PRI_CARD_HOLDER_IND);
            assertThat(customer.getFicoCreditScore()).isEqualTo(FICO);
        }

        @Test
        @DisplayName("the unprotected mutators assign without validating, as the layout requires")
        void theUnprotectedMutatorsAssignWithoutValidating() {
            Customer customer = populated();

            customer.setCustId("000000002");
            customer.setFirstName("A");
            customer.setMiddleName("B");
            customer.setLastName("C");
            customer.setAddrLine1("D");
            customer.setAddrLine2("E");
            customer.setAddrLine3("F");
            customer.setAddrStateCd("CA");
            customer.setAddrCountryCd("USA");
            customer.setAddrZip("90001");
            customer.setPhoneNum1("(310)5550100");
            customer.setPhoneNum2("(310)5550101");
            customer.setCustDob("1999-12-31");
            customer.setEftAccountId("0000000002");
            customer.setPriCardHolderInd("N");
            customer.setFicoCreditScore("300");

            assertThat(customer.getCustId()).isEqualTo("000000002");
            assertThat(customer.getFirstName()).isEqualTo("A");
            assertThat(customer.getMiddleName()).isEqualTo("B");
            assertThat(customer.getLastName()).isEqualTo("C");
            assertThat(customer.getAddrLine1()).isEqualTo("D");
            assertThat(customer.getAddrLine2()).isEqualTo("E");
            assertThat(customer.getAddrLine3()).isEqualTo("F");
            assertThat(customer.getAddrStateCd()).isEqualTo("CA");
            assertThat(customer.getAddrCountryCd()).isEqualTo("USA");
            assertThat(customer.getAddrZip()).isEqualTo("90001");
            assertThat(customer.getPhoneNum1()).isEqualTo("(310)5550100");
            assertThat(customer.getPhoneNum2()).isEqualTo("(310)5550101");
            assertThat(customer.getCustDob()).isEqualTo("1999-12-31");
            assertThat(customer.getEftAccountId()).isEqualTo("0000000002");
            assertThat(customer.getPriCardHolderInd()).isEqualTo("N");
            assertThat(customer.getFicoCreditScore()).isEqualTo("300");
        }
    }

    @Nested
    @DisplayName("identity rests on the business key alone")
    class Identity {

        @Test
        @DisplayName("two customers with the same identifier are equal despite differing attributes")
        void twoCustomersWithTheSameIdentifierAreEqual() {
            Customer first = populated();
            Customer second = populated();
            second.setFirstName("Different");
            second.setCustSsn(sealed("999999999"));

            assertThat(first).isEqualTo(second);
            assertThat(first).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("two customers with different identifiers are not equal")
        void twoCustomersWithDifferentIdentifiersAreNotEqual() {
            Customer first = populated();
            Customer second = populated();
            second.setCustId("000000002");

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("an instance equals itself and nothing of another type")
        void anInstanceEqualsItselfAndNothingOfAnotherType() {
            Customer customer = populated();

            assertThat(customer).isEqualTo(customer)
                    .isNotEqualTo(null)
                    .isNotEqualTo(CUST_ID);
        }

        @Test
        @DisplayName("the hash is stable across an update to a non-key attribute")
        void theHashIsStableAcrossANonKeyUpdate() {
            Customer customer = populated();
            int before = customer.hashCode();

            customer.setFicoCreditScore("300");
            customer.setCustSsn(sealed("111111111"));

            assertThat(customer.hashCode()).isEqualTo(before);
        }
    }
}
