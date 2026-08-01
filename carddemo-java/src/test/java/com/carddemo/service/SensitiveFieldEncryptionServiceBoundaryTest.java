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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.carddemo.util.SensitiveFieldCodec;

/**
 * Boundary suite for {@link SensitiveFieldEncryptionService}, covering the field-binding contract and
 * the persistence-boundary guard rather than the key-resolution diagnostics the companion suite owns.
 *
 * <p>The behaviours asserted here are the ones that make a protected column safe against something an
 * envelope alone cannot express. An envelope is self-describing about its scheme and its
 * authentication, and says nothing at all about <em>where</em> it belongs: a ciphertext lifted from one
 * protected column and written into another would authenticate cleanly and read back as though it had
 * always been there. Binding the column name into the authenticated payload is what closes that route,
 * and refusing an already-sealed value is what stops a second seal from turning a recoverable value
 * into an unrecoverable one. Both are enforced by the service rather than by its callers, so both are
 * asserted directly against it.</p>
 *
 * <p>Every value used here is fabricated. The key is the fixed development value the test overlay
 * declares, which is worth nothing outside this suite, and the identifiers are invented digit strings
 * rather than anything resembling a real national identifier.</p>
 */
@DisplayName("sensitive field encryption service, field binding and boundary guards")
class SensitiveFieldEncryptionServiceBoundaryTest {

    /** The fixed development key the test overlay declares: Base64 of exactly thirty-two bytes. */
    private static final String TEST_KEY = "Y2FyZGRlbW8tdGVzdC1vbmx5LWZpeGVkLWtleSEhISE=";

    /** A second key of the same length, used to prove that authentication is key-bound. */
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString(
            "carddemo-second-key-for-test!!!!".getBytes(StandardCharsets.US_ASCII));

    /** The one protected column the migrated schema has. */
    private static final String SSN_FIELD = SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD;

    /** A second, hypothetical protected column, used only to prove cross-column reads are refused. */
    private static final String OTHER_FIELD = "customer.other_column";

    /** A fabricated nine-digit identifier, the width the legacy record declares. */
    private static final String IDENTIFIER = "400500600";

    /** A fabricated identifier whose leading zeros must survive the round trip. */
    private static final String IDENTIFIER_WITH_LEADING_ZEROS = "000000042";

    /** Mapped width of the protected column, which every envelope must fit inside. */
    private static final int COLUMN_WIDTH = 255;

    /** The service under test, rebuilt for every test so no state can leak between them. */
    private SensitiveFieldEncryptionService service;

    @BeforeEach
    void createService() {
        this.service = new SensitiveFieldEncryptionService(TEST_KEY);
    }

    @Nested
    @DisplayName("published contract")
    class PublishedContract {

        @Test
        @DisplayName("names the configuration path the overlays declare")
        void namesTheConfigurationPath() {
            assertThat(SensitiveFieldEncryptionService.FIELD_ENCRYPTION_KEY_PROPERTY)
                    .isEqualTo("carddemo.security.field-encryption.key");
        }

        @Test
        @DisplayName("names the customer column it protects")
        void namesTheProtectedColumn() {
            assertThat(SSN_FIELD).isEqualTo("customer.cust_ssn");
        }

        @Test
        @DisplayName("accepts the key material the test overlay declares")
        void acceptsTheOverlayKey() {
            assertThat(Base64.getDecoder().decode(TEST_KEY))
                    .hasSize(SensitiveFieldCodec.KEY_LENGTH_BYTES);
        }
    }

    @Nested
    @DisplayName("field-bound round trip")
    class FieldBoundRoundTrip {

        @Test
        @DisplayName("returns the identifier character for character")
        void returnsTheIdentifierUnchanged() {
            final String sealed = service.protect(SSN_FIELD, IDENTIFIER);

            assertThat(service.reveal(SSN_FIELD, sealed)).isEqualTo(IDENTIFIER);
        }

        @Test
        @DisplayName("preserves leading zeros, which the legacy field carries")
        void preservesLeadingZeros() {
            final String sealed = service.protect(SSN_FIELD, IDENTIFIER_WITH_LEADING_ZEROS);

            assertThat(service.reveal(SSN_FIELD, sealed)).isEqualTo(IDENTIFIER_WITH_LEADING_ZEROS);
        }

        @Test
        @DisplayName("round-trips an empty value without substituting null")
        void roundTripsAnEmptyValue() {
            final String sealed = service.protect(SSN_FIELD, "");

            assertThat(service.reveal(SSN_FIELD, sealed)).isEmpty();
        }

        @Test
        @DisplayName("keeps the unbound and bound forms separable, so neither reads the other's value")
        void keepsTheUnboundAndBoundFormsSeparable() {
            final String unbound = service.protect(IDENTIFIER);

            assertThat(service.reveal(unbound)).isEqualTo(IDENTIFIER);
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> service.reveal(SSN_FIELD, unbound))
                    .withMessageContaining("field binding other than");
        }
    }

    @Nested
    @DisplayName("null handling, because the column is legitimately nullable")
    class NullHandling {

        @Test
        @DisplayName("seals null to null rather than to text")
        void sealsNullToNull() {
            assertThat(service.protectNullable(SSN_FIELD, null)).isNull();
        }

        @Test
        @DisplayName("reads null back as null rather than as text")
        void readsNullBackAsNull() {
            assertThat(service.revealNullable(SSN_FIELD, null)).isNull();
        }

        @Test
        @DisplayName("accepts null at the persistence boundary")
        void acceptsNullAtThePersistenceBoundary() {
            assertThat(service.requireProtectedOrNull(SSN_FIELD, null)).isNull();
        }

        @Test
        @DisplayName("round-trips a present value through the nullable pair unchanged")
        void roundTripsAPresentValueThroughTheNullablePair() {
            final String sealed = service.protectNullable(SSN_FIELD, IDENTIFIER);

            assertThat(service.revealNullable(SSN_FIELD, sealed)).isEqualTo(IDENTIFIER);
        }
    }

    @Nested
    @DisplayName("stored form")
    class StoredForm {

        @Test
        @DisplayName("carries the scheme tag")
        void carriesTheSchemeTag() {
            assertThat(service.protect(SSN_FIELD, IDENTIFIER))
                    .startsWith(SensitiveFieldCodec.ENVELOPE_PREFIX);
        }

        @Test
        @DisplayName("never contains the cleartext it protects")
        void neverContainsTheCleartext() {
            assertThat(service.protect(SSN_FIELD, IDENTIFIER)).doesNotContain(IDENTIFIER);
        }

        @Test
        @DisplayName("never contains the field name it is bound to")
        void neverContainsTheFieldName() {
            assertThat(service.protect(SSN_FIELD, IDENTIFIER)).doesNotContain(SSN_FIELD)
                    .doesNotContain("cust_ssn");
        }

        @Test
        @DisplayName("fits the mapped column width with room to spare")
        void fitsTheMappedColumnWidth() {
            assertThat(service.protect(SSN_FIELD, IDENTIFIER).length()).isLessThan(COLUMN_WIDTH);
        }

        @Test
        @DisplayName("differs on every call for the same input, because the vector is random")
        void differsOnEveryCall() {
            assertThat(service.protect(SSN_FIELD, IDENTIFIER))
                    .isNotEqualTo(service.protect(SSN_FIELD, IDENTIFIER));
        }
    }

    @Nested
    @DisplayName("field specificity")
    class FieldSpecificity {

        @Test
        @DisplayName("refuses to read a value stored for another field")
        void refusesAValueStoredForAnotherField() {
            final String sealedElsewhere = service.protect(OTHER_FIELD, IDENTIFIER);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> service.reveal(SSN_FIELD, sealedElsewhere))
                    .withMessageContaining("was not written for this field");
        }

        @Test
        @DisplayName("reads a value under the field it was stored for")
        void readsAValueUnderItsOwnField() {
            final String sealedElsewhere = service.protect(OTHER_FIELD, IDENTIFIER);

            assertThat(service.reveal(OTHER_FIELD, sealedElsewhere)).isEqualTo(IDENTIFIER);
        }

        @Test
        @DisplayName("refuses a blank field name on every entry point")
        void refusesABlankFieldName() {
            final String sealed = service.protect(SSN_FIELD, IDENTIFIER);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.protect("   ", IDENTIFIER));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.reveal("   ", sealed));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.requireProtectedOrNull("   ", sealed));
        }

        @Test
        @DisplayName("refuses an absent field name on every entry point")
        void refusesAnAbsentFieldName() {
            final String sealed = service.protect(SSN_FIELD, IDENTIFIER);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.protect(null, IDENTIFIER));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.reveal(null, sealed));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.requireProtectedOrNull(null, sealed));
        }

        @Test
        @DisplayName("refuses a field name carrying the binding separator")
        void refusesAFieldNameCarryingTheBindingSeparator() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.protect("customer\u001Fcust_ssn", IDENTIFIER))
                    .withMessageContaining("field-binding separator");
        }
    }

    @Nested
    @DisplayName("authentication")
    class Authentication {

        @Test
        @DisplayName("refuses a value whose ciphertext was altered, and reports that as an "
                + "authentication failure rather than as a malformed value")
        void refusesAnAlteredValue() {
            // The alteration is made at the first body character rather than the last, and that choice
            // is load bearing. The body is padded Base64, so its final characters may be padding; a flip
            // there can turn "...X==" into "...X=A", which is not valid Base64 at all and is refused by
            // the structural guard before any key is applied. That would still be a refusal, but it
            // would be the wrong refusal to assert here, because it would pass whether or not the
            // authentication tag is ever checked. Flipping an interior character leaves the length and
            // the padding intact, so the value stays structurally well formed and only the tag can
            // reject it - which is exactly the property this test exists to prove.
            final String sealed = service.protect(SSN_FIELD, IDENTIFIER);
            final int firstBodyIndex = SensitiveFieldCodec.ENVELOPE_PREFIX.length();
            final char firstBodyCharacter = sealed.charAt(firstBodyIndex);
            final String altered = sealed.substring(0, firstBodyIndex)
                    + (firstBodyCharacter == 'A' ? 'B' : 'A')
                    + sealed.substring(firstBodyIndex + 1);

            assertThat(altered)
                    .as("the alteration must leave a structurally valid envelope, or the assertion "
                            + "below would be satisfied by the wrong guard")
                    .isNotEqualTo(sealed)
                    .hasSameSizeAs(sealed);
            assertThat(SensitiveFieldCodec.hasEnvelopeShape(altered))
                    .as("still shaped like an envelope this scheme produced")
                    .isTrue();

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> service.reveal(SSN_FIELD, altered))
                    .withMessageContaining("authenticated decryption failed")
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .as("no refusal quotes regulated data")
                            .doesNotContain(IDENTIFIER));
        }

        @Test
        @DisplayName("a value whose envelope is malformed is refused by the structural guard, which is a "
                + "different condition from a value that fails authentication")
        void aMalformedEnvelopeIsADistinctConditionFromAFailedAuthentication() {
            // The paired half of the test above: the codec checks structure before it applies a key, so
            // the two conditions are reported as different exception types. Asserting both keeps the
            // distinction from collapsing in either direction - a structural refusal cannot be mistaken
            // for a tag failure, and a tag failure cannot be satisfied by a structural refusal.
            final String sealed = service.protect(SSN_FIELD, IDENTIFIER);
            final String notBase64 = sealed.substring(0, sealed.length() - 1) + "!";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.reveal(SSN_FIELD, notBase64))
                    .satisfies(refusal -> assertThat(refusal)
                            .as("a structural refusal is not an authentication failure")
                            .isNotInstanceOf(IllegalStateException.class));
        }

        @Test
        @DisplayName("refuses a value sealed under different key material")
        void refusesAValueSealedUnderAnotherKey() {
            final String sealedElsewhere =
                    new SensitiveFieldEncryptionService(OTHER_KEY).protect(SSN_FIELD, IDENTIFIER);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> service.reveal(SSN_FIELD, sealedElsewhere));
        }

        @Test
        @DisplayName("refuses a truncated value before attempting to read it")
        void refusesATruncatedValue() {
            final String truncated = SensitiveFieldCodec.ENVELOPE_PREFIX + "AAAA";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.reveal(SSN_FIELD, truncated));
        }

        @Test
        @DisplayName("refuses cleartext presented for reading")
        void refusesCleartextPresentedForReading() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.reveal(SSN_FIELD, IDENTIFIER));
        }
    }

    @Nested
    @DisplayName("structural recognition")
    class StructuralRecognition {

        @Test
        @DisplayName("recognises its own output")
        void recognisesItsOwnOutput() {
            assertThat(service.isProtected(service.protect(SSN_FIELD, IDENTIFIER))).isTrue();
        }

        @Test
        @DisplayName("does not mistake a nine-digit identifier for a sealed value")
        void doesNotMistakeAnIdentifierForASealedValue() {
            assertThat(service.isProtected(IDENTIFIER)).isFalse();
        }

        @Test
        @DisplayName("rejects null")
        void rejectsNull() {
            assertThat(service.isProtected(null)).isFalse();
        }

        @Test
        @DisplayName("rejects a value this scheme could not have produced")
        void rejectsAForeignScheme() {
            assertThat(service.isProtected("v1:gcm:00112233445566778899aabbccddeeff")).isFalse();
        }
    }

    @Nested
    @DisplayName("persistence boundary guard")
    class PersistenceBoundaryGuard {

        @Test
        @DisplayName("returns a compliant value unchanged")
        void returnsACompliantValueUnchanged() {
            final String sealed = service.protect(SSN_FIELD, IDENTIFIER);

            assertThat(service.requireProtectedOrNull(SSN_FIELD, sealed)).isEqualTo(sealed);
        }

        @Test
        @DisplayName("refuses a cleartext identifier without echoing it")
        void refusesCleartextWithoutEchoingIt() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.requireProtectedOrNull(SSN_FIELD, IDENTIFIER))
                    .withMessageContaining(SSN_FIELD)
                    .withMessageNotContaining(IDENTIFIER);
        }

        @Test
        @DisplayName("refuses anything this scheme did not produce")
        void refusesAForeignScheme() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.requireProtectedOrNull(SSN_FIELD, "v1:gcm:deadbeef"));
        }
    }

    @Nested
    @DisplayName("double-seal guard")
    class DoubleSealGuard {

        @Test
        @DisplayName("refuses to seal a value that is already an envelope, on the bound form")
        void refusesToSealAnEnvelopeOnTheBoundForm() {
            final String sealed = service.protect(SSN_FIELD, IDENTIFIER);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.protect(SSN_FIELD, sealed))
                    .withMessageContaining("already a")
                    .withMessageContaining(SSN_FIELD);
        }

        @Test
        @DisplayName("refuses to seal a value that is already an envelope, on the unbound form")
        void refusesToSealAnEnvelopeOnTheUnboundForm() {
            final String sealed = service.protect(IDENTIFIER);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.protect(sealed))
                    .withMessageContaining("already a");
        }
    }
}
