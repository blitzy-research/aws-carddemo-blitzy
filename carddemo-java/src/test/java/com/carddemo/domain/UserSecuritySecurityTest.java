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

import com.carddemo.support.SensitiveValues;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Acceptance for the credential entity, whose single security-bearing property is that the column
 * cannot hold anything but a BCrypt digest.
 *
 * <p>The legacy user-security record stores an eight-character cleartext password in
 * {@code SEC-USR-PWD PIC X(08)} and the sign-on program compares it directly. Hashing that credential
 * is a mandated parity exception recorded in the decision log, and the column is 60 characters wide to
 * hold the digest. A wide column, however, is an invitation as much as an accommodation: an
 * eight-character cleartext password fits in it perfectly well. What actually prevents cleartext from
 * being stored is the structural guard, so the guard is what this class tests, from both entry points.
 *
 * <p>The guard is validated against a digest produced by the encoder the module actually uses rather
 * than against a hand-written literal, because a guard that rejects real output would be worse than no
 * guard: it would push a caller towards disabling it. {@link EncoderCompatibility} makes that pin
 * explicit across every cost the encoder will be asked for.
 *
 * <p>Two further escape routes are closed and asserted here. The credential accessors are named
 * outside the JavaBeans convention, so a property-walking serializer, a projection or a bean mapper
 * does not discover the digest; {@link SerializationContract} proves that with a real object mapper.
 * And the textual form carries the sign-on identifier alone, so a log line naming the entity cannot
 * carry the digest with it.
 */
@DisplayName("UserSecurity - the credential column cannot hold anything but a BCrypt digest")
class UserSecuritySecurityTest {

    /** The encoder the application uses, at its default cost, so the pin is against real output. */
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    /** The literal password every seeded user carries in the legacy provisioning job. */
    private static final String LEGACY_PASSWORD = "PASSWORD";

    /** A genuine digest of that password, produced by the encoder rather than transcribed. */
    private static final String REAL_DIGEST = ENCODER.encode(LEGACY_PASSWORD);

    /**
     * A seeded administrator identifier. The names are deliberately chosen so that neither is a
     * substring of the class name or the identifier, which is what lets
     * {@link SerializationContract#theTextualFormDisclosesNeitherDigestNorName()} assert their absence
     * from the rendered form without the assertion succeeding or failing for an accidental reason.
     */
    private static final String USER_ID = "ADMIN001";
    private static final String FIRST_NAME = "Alberta             ";
    private static final String LAST_NAME = "Zimmerman           ";
    private static final String USER_TYPE = "A";

    /**
     * Builds a user with a caller-supplied credential value, leaving every other attribute well
     * formed, so a single attribute can be varied in isolation.
     *
     * @param credential the value to place in the credential attribute
     * @return the constructed entity
     */
    private static UserSecurity withCredential(final String credential) {
        return new UserSecurity(USER_ID, FIRST_NAME, LAST_NAME, credential, USER_TYPE);
    }

    /**
     * Builds a fully valid user carrying a genuine digest.
     *
     * @return the constructed entity
     */
    private static UserSecurity populated() {
        return withCredential(REAL_DIGEST);
    }

    @Nested
    @DisplayName("the guard accepts what the application's own encoder produces")
    class EncoderCompatibility {

        @Test
        @DisplayName("a digest from the default encoder is accepted")
        void aDigestFromTheDefaultEncoderIsAccepted() {
            assertThat(SensitiveValues.fingerprint(populated().credentialDigest())).isEqualTo(SensitiveValues.fingerprint(REAL_DIGEST));
        }

        @ParameterizedTest(name = "a digest at cost {0} is accepted")
        @ValueSource(ints = {10, 11, 12})
        @DisplayName("a digest at any cost the guard permits is accepted")
        void aDigestAtAnyPermittedCostIsAccepted(int cost) {
            String digest = new BCryptPasswordEncoder(cost).encode(LEGACY_PASSWORD);

            assertThat(SensitiveValues.fingerprint(withCredential(digest).credentialDigest())).isEqualTo(SensitiveValues.fingerprint(digest));
        }

        @Test
        @DisplayName("a stored digest still verifies the original password")
        void aStoredDigestStillVerifiesTheOriginalPassword() {
            UserSecurity user = populated();

            assertThat(ENCODER.matches(LEGACY_PASSWORD, user.credentialDigest()))
                    .as("the guard must not alter what it accepts, or authentication would break")
                    .isTrue();
            assertThat(ENCODER.matches("WRONGPWD", user.credentialDigest())).isFalse();
        }

        @Test
        @DisplayName("two digests of the same password differ, because the salt is per-digest")
        void twoDigestsOfTheSamePasswordDiffer() {
            String first = ENCODER.encode(LEGACY_PASSWORD);
            String second = ENCODER.encode(LEGACY_PASSWORD);

            assertThat(first).isNotEqualTo(second);
            assertThat(SensitiveValues.fingerprint(withCredential(first).credentialDigest())).isEqualTo(SensitiveValues.fingerprint(first));
            assertThat(SensitiveValues.fingerprint(withCredential(second).credentialDigest())).isEqualTo(SensitiveValues.fingerprint(second));
        }
    }

    @Nested
    @DisplayName("cleartext is refused at construction")
    class RefusesCleartext {

        @Test
        @DisplayName("the legacy eight-character password is refused")
        void theLegacyEightCharacterPasswordIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withCredential(LEGACY_PASSWORD))
                    .withMessageContaining("must be a BCrypt digest")
                    .withMessageContaining("storing a cleartext credential is not permitted");
        }

        @ParameterizedTest(name = "\"{0}\" is refused")
        @ValueSource(strings = {"", " ", "pass", "PASSWORD", "P@ssw0rd", "12345678",
            "        ", "aVeryLongCleartextPasswordThatIsNotSixtyCharacters"})
        @DisplayName("no cleartext of any length is accepted")
        void noCleartextOfAnyLengthIsAccepted(String cleartext) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withCredential(cleartext))
                    .withMessageContaining("must be a BCrypt digest");
        }

        @Test
        @DisplayName("an absent credential is refused rather than stored as absent")
        void anAbsentCredentialIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withCredential(null))
                    .withMessageContaining("must not be null");
        }

        @Test
        @DisplayName("a sixty-character value that is not a digest is still refused")
        void aSixtyCharacterNonDigestIsRefused() {
            String sixtyCharacters = "x".repeat(60);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withCredential(sixtyCharacters))
                    .withMessageContaining("recognised version marker");
        }

        @Test
        @DisplayName("no refusal message quotes the credential it refused")
        void noRefusalMessageQuotesTheCredential() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withCredential(LEGACY_PASSWORD))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .as("a rejection must not itself disclose the credential")
                            .doesNotContain(LEGACY_PASSWORD));
        }
    }

    @Nested
    @DisplayName("a malformed digest is refused for the specific reason it is malformed")
    class RefusesMalformedDigests {

        @Test
        @DisplayName("a digest one character short is refused on length")
        void aDigestOneCharacterShortIsRefusedOnLength() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withCredential(REAL_DIGEST.substring(0, 59)))
                    .withMessageContaining("60 characters")
                    .withMessageContaining("length 59");
        }

        @Test
        @DisplayName("a digest one character long is refused on length")
        void aDigestOneCharacterLongIsRefusedOnLength() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withCredential(REAL_DIGEST + "A"))
                    .withMessageContaining("length 61");
        }

        @ParameterizedTest(name = "version marker {0} is refused")
        @ValueSource(strings = {"$2x$", "$2c$", "$1a$", "$2a?", "abcd"})
        @DisplayName("an unrecognised version marker is refused")
        void anUnrecognisedVersionMarkerIsRefused(String marker) {
            String tampered = marker + REAL_DIGEST.substring(4);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withCredential(tampered))
                    .withMessageContaining("recognised version marker");
        }

        @ParameterizedTest(name = "the recognised marker {0} is accepted")
        @ValueSource(strings = {"$2a$", "$2b$", "$2y$"})
        @DisplayName("all three recognised version markers are accepted")
        void allThreeRecognisedMarkersAreAccepted(String marker) {
            String remarked = marker + REAL_DIGEST.substring(4);

            assertThat(SensitiveValues.fingerprint(withCredential(remarked).credentialDigest())).isEqualTo(SensitiveValues.fingerprint(remarked));
        }

        @Test
        @DisplayName("a non-numeric cost is refused before it is interpreted")
        void aNonNumericCostIsRefused() {
            String tampered = REAL_DIGEST.substring(0, 4) + "AB" + REAL_DIGEST.substring(6);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withCredential(tampered))
                    .withMessageContaining("two-digit cost and a separator");
        }

        @Test
        @DisplayName("a missing separator after the cost is refused")
        void aMissingSeparatorAfterTheCostIsRefused() {
            String tampered = REAL_DIGEST.substring(0, 6) + "A" + REAL_DIGEST.substring(7);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withCredential(tampered))
                    .withMessageContaining("two-digit cost and a separator");
        }

        @ParameterizedTest(name = "cost {0} is refused as too weak")
        @ValueSource(strings = {"00", "01", "04", "08", "09"})
        @DisplayName("a cost below the floor is refused, so a weakened digest cannot be stored")
        void aCostBelowTheFloorIsRefused(String cost) {
            String weakened = REAL_DIGEST.substring(0, 4) + cost + REAL_DIGEST.substring(6);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withCredential(weakened))
                    .withMessageContaining("cost of at least 10");
        }

        @Test
        @DisplayName("a cost of exactly the floor is accepted")
        void aCostOfExactlyTheFloorIsAccepted() {
            String atTheFloor = REAL_DIGEST.substring(0, 4) + "10" + REAL_DIGEST.substring(6);

            assertThat(SensitiveValues.fingerprint(withCredential(atTheFloor).credentialDigest())).isEqualTo(SensitiveValues.fingerprint(atTheFloor));
        }

        @ParameterizedTest(name = "the character {0} in the tail is refused")
        @ValueSource(chars = {'$', '_', '!', ' ', '-', '+', '=', '\u00e9'})
        @DisplayName("a character outside the BCrypt radix-64 alphabet in the tail is refused")
        void aCharacterOutsideTheAlphabetIsRefused(char intruder) {
            String tampered = REAL_DIGEST.substring(0, 30) + intruder + REAL_DIGEST.substring(31);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withCredential(tampered))
                    .withMessageContaining("BCrypt radix-64 alphabet");
        }

        @Test
        @DisplayName("the alphabet check covers the final character of the digest")
        void theAlphabetCheckCoversTheFinalCharacter() {
            String tampered = REAL_DIGEST.substring(0, 59) + "$";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withCredential(tampered))
                    .withMessageContaining("BCrypt radix-64 alphabet");
        }
    }

    @Nested
    @DisplayName("the replacement mutator is guarded identically")
    class GuardedReplacement {

        @Test
        @DisplayName("a genuine digest can replace the stored one")
        void aGenuineDigestCanReplaceTheStoredOne() {
            UserSecurity user = populated();
            String replacement = ENCODER.encode("NEWPASSW");

            user.replaceCredentialDigest(replacement);

            assertThat(SensitiveValues.fingerprint(user.credentialDigest())).isEqualTo(SensitiveValues.fingerprint(replacement));
        }

        @Test
        @DisplayName("cleartext cannot be assigned through the mutator")
        void cleartextCannotBeAssignedThroughTheMutator() {
            UserSecurity user = populated();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> user.replaceCredentialDigest("NEWPASSW"))
                    .withMessageContaining("must be a BCrypt digest");
        }

        @Test
        @DisplayName("a refused replacement leaves the stored digest untouched")
        void aRefusedReplacementLeavesTheStoredDigestUntouched() {
            UserSecurity user = populated();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> user.replaceCredentialDigest("NEWPASSW"));

            assertThat(SensitiveValues.fingerprint(user.credentialDigest())).isEqualTo(SensitiveValues.fingerprint(REAL_DIGEST));
        }

        @Test
        @DisplayName("the credential cannot be cleared through the mutator")
        void theCredentialCannotBeClearedThroughTheMutator() {
            UserSecurity user = populated();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> user.replaceCredentialDigest(null))
                    .withMessageContaining("must not be null");
        }

        @Test
        @DisplayName("a weakened cost cannot be introduced by replacement")
        void aWeakenedCostCannotBeIntroducedByReplacement() {
            UserSecurity user = populated();
            String weakened = REAL_DIGEST.substring(0, 4) + "04" + REAL_DIGEST.substring(6);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> user.replaceCredentialDigest(weakened))
                    .withMessageContaining("cost of at least 10");
        }
    }

    @Nested
    @DisplayName("the digest does not escape through serialization or rendering")
    class SerializationContract {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("a serialized entity carries no credential property")
        void aSerializedEntityCarriesNoCredentialProperty() throws Exception {
            Map<String, Object> emitted = mapper.convertValue(populated(),
                    new TypeReference<Map<String, Object>>() { });

            assertThat(emitted)
                    .as("the credential accessor is named outside the JavaBeans convention precisely "
                            + "so that a property-walking serializer cannot discover it")
                    .doesNotContainKeys("secUsrPwd", "credentialDigest", "password", "pwd");
        }

        @Test
        @DisplayName("the serialized text contains the digest nowhere, whole or partial")
        void theSerializedTextContainsTheDigestNowhere() throws Exception {
            String json = mapper.writeValueAsString(populated());

            assertThat(json).doesNotContain(REAL_DIGEST);
            assertThat(json).doesNotContain(REAL_DIGEST.substring(7));
            assertThat(json).doesNotContain(REAL_DIGEST.substring(0, 7));
        }

        @Test
        @DisplayName("the properties that do survive serialization are the non-secret four")
        void thePropertiesThatSurviveAreTheNonSecretFour() throws Exception {
            Map<String, Object> emitted = mapper.convertValue(populated(),
                    new TypeReference<Map<String, Object>>() { });

            assertThat(emitted).containsOnlyKeys(
                    "secUsrId", "secUsrFname", "secUsrLname", "secUsrType");
        }

        @Test
        @DisplayName("the textual form carries the sign-on identifier alone")
        void theTextualFormCarriesTheIdentifierAlone() {
            assertThat(populated()).hasToString("UserSecurity[secUsrId=" + USER_ID + "]");
        }

        @Test
        @DisplayName("the textual form discloses neither the digest nor the operator's name")
        void theTextualFormDisclosesNeitherDigestNorName() {
            String rendered = populated().toString();

            assertThat(rendered).doesNotContain(REAL_DIGEST);
            assertThat(rendered).doesNotContain(REAL_DIGEST.substring(7));
            assertThat(rendered).doesNotContain(FIRST_NAME.trim());
            assertThat(rendered).doesNotContain(LAST_NAME.trim());
        }
    }

    @Nested
    @DisplayName("the non-credential attributes and identity behave as the layout requires")
    class RemainingContract {

        @Test
        @DisplayName("every non-credential attribute is carried verbatim")
        void everyNonCredentialAttributeIsCarriedVerbatim() {
            UserSecurity user = populated();

            assertThat(user.getSecUsrId()).isEqualTo(USER_ID);
            assertThat(user.getSecUsrFname()).isEqualTo(FIRST_NAME);
            assertThat(user.getSecUsrLname()).isEqualTo(LAST_NAME);
            assertThat(user.getSecUsrType()).isEqualTo(USER_TYPE);
        }

        @Test
        @DisplayName("the non-credential mutators assign without validating")
        void theNonCredentialMutatorsAssignWithoutValidating() {
            UserSecurity user = populated();

            user.setSecUsrId("USER0001");
            user.setSecUsrFname("Regular");
            user.setSecUsrLname("Person");
            user.setSecUsrType("U");

            assertThat(user.getSecUsrId()).isEqualTo("USER0001");
            assertThat(user.getSecUsrFname()).isEqualTo("Regular");
            assertThat(user.getSecUsrLname()).isEqualTo("Person");
            assertThat(user.getSecUsrType()).isEqualTo("U");
        }

        @Test
        @DisplayName("two users with the same identifier are equal despite differing credentials")
        void twoUsersWithTheSameIdentifierAreEqual() {
            UserSecurity first = populated();
            UserSecurity second = withCredential(ENCODER.encode("OTHERPWD"));

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("two users with different identifiers are not equal")
        void twoUsersWithDifferentIdentifiersAreNotEqual() {
            UserSecurity first = populated();
            UserSecurity second = populated();
            second.setSecUsrId("USER0001");

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("an instance equals itself and nothing of another type")
        void anInstanceEqualsItselfAndNothingOfAnotherType() {
            UserSecurity user = populated();

            assertThat(user).isEqualTo(user)
                    .isNotEqualTo(null)
                    .isNotEqualTo(USER_ID);
        }

        @Test
        @DisplayName("the hash is stable across a credential rotation")
        void theHashIsStableAcrossACredentialRotation() {
            UserSecurity user = populated();
            int before = user.hashCode();

            user.replaceCredentialDigest(ENCODER.encode("ROTATED1"));

            assertThat(user.hashCode()).isEqualTo(before);
        }
    }
}
