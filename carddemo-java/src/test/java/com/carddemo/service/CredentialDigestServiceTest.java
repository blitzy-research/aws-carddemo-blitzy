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

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for the module's single credential authority.
 *
 * <h2>What is under test and why it matters</h2>
 * The legacy user-security record stores the sign-on credential in the clear - {@code SEC-USR-PWD} is
 * eight alphanumeric characters at offset 48 of the 80-byte layout in {@code app/cpy/CSUSR01Y.cpy} -
 * and {@code app/cbl/COSGN00C.cbl} line 223 authenticates by comparing that field directly against
 * the value keyed at the terminal. The migration refuses to reproduce either decision: the column is
 * sized for a sixty-character digest and the comparison becomes an encoder verification. Widening a
 * column and writing a comment does not accomplish that on its own, and the class under test is the
 * mechanism that does. If it can be bypassed, or if a cleartext value stored in the column can still
 * authenticate somebody, then the schema comment asserting the guarantee is false.
 *
 * <h2>The five properties these tests exist to pin</h2>
 * <ol>
 *   <li><strong>Production.</strong> A digest is exactly sixty characters, structurally well formed,
 *       and freshly salted, so encoding one credential twice yields two different stored values and
 *       no equality test between digests is ever meaningful.</li>
 *   <li><strong>Verification.</strong> A credential verifies against a digest it produced and
 *       against no other, and the raw credential is never recoverable from the stored form.</li>
 *   <li><strong>Recognition.</strong> A digest is distinguishable from a cleartext value by structure
 *       alone, without holding a credential to try, which is what makes a raw database assertion
 *       possible.</li>
 *   <li><strong>Refusal at the boundary.</strong> Anything that is not a digest is refused before it
 *       can be stored, and the refusal never reports the value it refused, because a value is at its
 *       most sensitive at exactly the moment it turns out to be a credential in the clear.</li>
 *   <li><strong>Defence in depth.</strong> A cleartext value that reached the column by any route
 *       authenticates nobody, because verification declines a stored value that is not a digest
 *       before it consults the encoder at all.</li>
 * </ol>
 *
 * <h2>Test data discipline</h2>
 * Every credential used here is an obviously synthetic phrase. The eight-character literal carried
 * in-stream by {@code app/jcl/DUSRSECJ.jcl} appears nowhere in this file, in any form, and no
 * assertion depends on it.
 */
@DisplayName("CredentialDigestService")
class CredentialDigestServiceTest {

    /** A synthetic credential. Deliberately unlike anything the legacy seed carries. */
    private static final String CREDENTIAL = "synthetic-credential-for-unit-tests";

    /** A second synthetic credential, used to prove a digest verifies against one input only. */
    private static final String OTHER_CREDENTIAL = "a-different-synthetic-credential";

    /**
     * A well-formed seven-character digest header, used to build candidates whose shape can be asserted
     * without a random salt.
     *
     * <p>The cost factor in it is fixture data and is deliberately <em>not</em> the module's hashing
     * strength: what these shape assertions test is the recogniser, which accepts the whole declared cost
     * range rather than one value. The strength the service actually hashes at is asserted separately, off
     * a digest it produced, by {@link DigestProduction#hashesAtTheModulesOneStrength()}.</p>
     */
    private static final String SAMPLE_HEADER = "$2a$10$";

    /** Length of the combined salt and hash that follows the header. */
    private static final int REMAINDER_LENGTH = 53;

    /**
     * Index of the cost factor among a digest's dollar-delimited fields: an empty leading field, the
     * variant, the cost, then the remainder.
     */
    private static final int COST_FIELD_INDEX = 2;

    private CredentialDigestService service;

    @BeforeEach
    void createService() {
        service = new CredentialDigestService();
    }

    /**
     * Builds a structurally valid-looking digest from a seven-character header, so shape tests do not
     * depend on the encoder's random salt.
     *
     * @param header the version tag, cost factor and separator, seven characters in total
     * @return a sixty-character candidate whose remainder is entirely radix-64
     */
    private static String shaped(final String header) {
        return header + "a".repeat(REMAINDER_LENGTH);
    }

    @Nested
    @DisplayName("published contract")
    class PublishedContract {

        @Test
        @DisplayName("names the one column that holds a credential digest")
        void namesTheCredentialColumn() {
            assertThat(CredentialDigestService.USER_SECURITY_PWD_FIELD)
                    .isEqualTo("user_security.sec_usr_pwd");
        }

        @Test
        @DisplayName("declares the digest width the schema column is sized for")
        void declaresTheDigestWidth() {
            assertThat(CredentialDigestService.DIGEST_LENGTH).isEqualTo(60);
        }

        @Test
        @DisplayName("declares the cost-factor range a digest may carry")
        void declaresTheCostRange() {
            assertThat(CredentialDigestService.MINIMUM_COST).isEqualTo(4);
            assertThat(CredentialDigestService.MAXIMUM_COST).isEqualTo(31);
        }

        @Test
        @DisplayName("publishes the module's one hashing strength, above the encoder library's default, "
                + "so that every encoder in the module can read one number instead of restating one")
        void publishesTheModulesOneHashingStrength() {
            assertThat(CredentialDigestService.HASHING_STRENGTH)
                    .as("the security configuration's encoder bean is built from this constant; two "
                            + "encoders at two strengths is a silent policy split in which the weaker "
                            + "becomes the module's real strength")
                    .isEqualTo(12);
            assertThat(CredentialDigestService.HASHING_STRENGTH)
                    .as("and it stays inside the range a digest may declare, or nothing it produced could "
                            + "be recognised as a digest")
                    .isBetween(CredentialDigestService.MINIMUM_COST,
                            CredentialDigestService.MAXIMUM_COST);
        }
    }

    @Nested
    @DisplayName("digest production")
    class DigestProduction {

        @Test
        @DisplayName("produces exactly the column width")
        void producesTheColumnWidth() {
            assertThat(service.encode(CREDENTIAL)).hasSize(CredentialDigestService.DIGEST_LENGTH);
        }

        @Test
        @DisplayName("produces a value it recognises as a digest")
        void producesARecognisedDigest() {
            assertThat(service.isDigest(service.encode(CREDENTIAL))).isTrue();
        }

        @Test
        @DisplayName("hashes at the module's one strength, read out of the digest it produced rather than "
                + "off the encoder it was built with")
        void hashesAtTheModulesOneStrength() {
            // Read from the produced value, so an encoder built at one strength and hashing at another
            // would be caught. Leaving this at the library's default was the defect: the administrative
            // write path hashes through the configuration's encoder at the published strength, so a digest
            // produced here would have been the weaker of two live policies.
            final String[] fields = service.encode(CREDENTIAL).split("\\$");

            assertThat(Integer.parseInt(fields[COST_FIELD_INDEX]))
                    .isEqualTo(CredentialDigestService.HASHING_STRENGTH);
        }

        @Test
        @DisplayName("salts every digest, so the same credential never yields the same stored value")
        void saltsEveryDigest() {
            final Set<String> digests = new HashSet<>();
            for (int attempt = 0; attempt < 8; attempt++) {
                digests.add(service.encode(CREDENTIAL));
            }
            assertThat(digests).hasSize(8);
        }

        @Test
        @DisplayName("never carries the credential inside the stored value")
        void neverCarriesTheCredential() {
            assertThat(service.encode(CREDENTIAL)).doesNotContain(CREDENTIAL);
        }

        @Test
        @DisplayName("refuses an absent credential rather than substituting a default")
        void refusesAnAbsentCredential() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.encode(null))
                    .withMessageContaining(CredentialDigestService.USER_SECURITY_PWD_FIELD);
        }

        @Test
        @DisplayName("refuses an empty credential")
        void refusesAnEmptyCredential() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.encode(""))
                    .withMessageContaining("blank");
        }

        @ParameterizedTest
        @ValueSource(strings = {" ", "        ", "\t", "\n", " \t \n "})
        @DisplayName("refuses a credential that is only whitespace, matching the legacy prompt path")
        void refusesWhitespaceOnly(final String candidate) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.encode(candidate))
                    .withMessageContaining("blank");
        }

        @Test
        @DisplayName("accepts a credential whose padding is significant, without trimming it")
        void treatsPaddingAsSignificant() {
            final String padded = CREDENTIAL + "  ";
            final String digest = service.encode(padded);
            assertThat(service.matches(padded, digest)).isTrue();
            assertThat(service.matches(CREDENTIAL, digest)).isFalse();
        }

        @Test
        @DisplayName("accepts a credential supplied as a mutable character sequence")
        void acceptsACharSequence() {
            final StringBuilder builder = new StringBuilder(CREDENTIAL);
            assertThat(service.isDigest(service.encode(builder))).isTrue();
        }
    }

    @Nested
    @DisplayName("verification replaces the legacy direct comparison")
    class Verification {

        @Test
        @DisplayName("accepts the credential that produced the digest")
        void acceptsTheProducingCredential() {
            assertThat(service.matches(CREDENTIAL, service.encode(CREDENTIAL))).isTrue();
        }

        @Test
        @DisplayName("accepts against either of two independently salted digests")
        void acceptsAgainstEitherSalting() {
            assertThat(service.matches(CREDENTIAL, service.encode(CREDENTIAL))).isTrue();
            assertThat(service.matches(CREDENTIAL, service.encode(CREDENTIAL))).isTrue();
        }

        @Test
        @DisplayName("rejects a different credential")
        void rejectsADifferentCredential() {
            assertThat(service.matches(OTHER_CREDENTIAL, service.encode(CREDENTIAL))).isFalse();
        }

        @Test
        @DisplayName("is case sensitive")
        void isCaseSensitive() {
            final String digest = service.encode(CREDENTIAL);
            assertThat(service.matches(CREDENTIAL.toUpperCase(Locale.ROOT), digest)).isFalse();
        }

        @Test
        @DisplayName("rejects an absent credential without consulting the encoder")
        void rejectsAnAbsentCredential() {
            assertThat(service.matches(null, service.encode(CREDENTIAL))).isFalse();
        }

        @Test
        @DisplayName("rejects an absent stored value")
        void rejectsAnAbsentStoredValue() {
            assertThat(service.matches(CREDENTIAL, null)).isFalse();
        }

        @Test
        @DisplayName("rejects a blank stored value")
        void rejectsABlankStoredValue() {
            assertThat(service.matches(CREDENTIAL, "")).isFalse();
            assertThat(service.matches(CREDENTIAL, " ".repeat(60))).isFalse();
        }

        @Test
        @DisplayName("refuses to authenticate a cleartext stored value even when it is the credential")
        void refusesToAuthenticateACleartextStoredValue() {
            assertThat(service.matches(CREDENTIAL, CREDENTIAL)).isFalse();
        }

        @Test
        @DisplayName("refuses to authenticate a cleartext stored value padded to the column width")
        void refusesACleartextValueAtTheColumnWidth() {
            final String padded = CREDENTIAL + " ".repeat(60 - CREDENTIAL.length());
            assertThat(padded).hasSize(60);
            assertThat(service.matches(padded, padded)).isFalse();
        }

        @Test
        @DisplayName("refuses to authenticate against a value that only looks like a digest")
        void refusesAMerelyDigestLikeValue() {
            assertThat(service.matches(CREDENTIAL, shaped(SAMPLE_HEADER))).isFalse();
        }
    }

    @Nested
    @DisplayName("structural recognition")
    class StructuralRecognition {

        @ParameterizedTest
        @ValueSource(strings = {"$2a$", "$2b$", "$2y$"})
        @DisplayName("accepts every version tag a supported encoder produces")
        void acceptsEveryVersionTag(final String tag) {
            assertThat(service.isDigest(shaped(tag + "10$"))).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"04", "05", "10", "12", "30", "31"})
        @DisplayName("accepts every cost factor inside the range")
        void acceptsCostFactorsInRange(final String cost) {
            assertThat(service.isDigest(shaped("$2a$" + cost + "$"))).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"00", "01", "03", "32", "40", "99"})
        @DisplayName("rejects a cost factor outside the range")
        void rejectsCostFactorsOutOfRange(final String cost) {
            assertThat(service.isDigest(shaped("$2a$" + cost + "$"))).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"$2a$1a$", "$2a$a1$", "$2a$  $", "$2a$-1$", "$2a$10a", "$2a$1$a"})
        @DisplayName("rejects a malformed cost factor or separator")
        void rejectsAMalformedCostFactor(final String header) {
            assertThat(service.isDigest(shaped(header))).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"$2x$10$", "$2A$10$", "$1a$10$", "$3a$10$", "%2a$10$", "$2a-10$"})
        @DisplayName("rejects a version tag no supported encoder produces")
        void rejectsAnUnsupportedVersionTag(final String header) {
            assertThat(service.isDigest(shaped(header))).isFalse();
        }

        @Test
        @DisplayName("rejects a candidate one character short of the column width")
        void rejectsFiftyNineCharacters() {
            final String short59 = shaped(SAMPLE_HEADER).substring(0, 59);
            assertThat(short59).hasSize(59);
            assertThat(service.isDigest(short59)).isFalse();
        }

        @Test
        @DisplayName("rejects a candidate one character over the column width")
        void rejectsSixtyOneCharacters() {
            final String long61 = shaped(SAMPLE_HEADER) + "a";
            assertThat(long61).hasSize(61);
            assertThat(service.isDigest(long61)).isFalse();
        }

        @ParameterizedTest
        @ValueSource(chars = {'+', '=', '$', ' ', '-', '_', '!', '\u00e9', '\u0000'})
        @DisplayName("rejects a remainder character outside the BCrypt radix-64 alphabet")
        void rejectsANonRadix64Remainder(final char intruder) {
            final String tainted = shaped(SAMPLE_HEADER).substring(0, 59) + intruder;
            assertThat(tainted).hasSize(60);
            assertThat(service.isDigest(tainted)).isFalse();
        }

        @Test
        @DisplayName("accepts every character of the BCrypt radix-64 alphabet in the remainder")
        void acceptsEveryRadix64Character() {
            final String alphabet = "./0123456789"
                    + "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                    + "abcdefghijklmnopqrstuvwxyz";
            assertThat(alphabet).hasSize(64);
            assertThat(service.isDigest(SAMPLE_HEADER + alphabet.substring(0, REMAINDER_LENGTH)))
                    .isTrue();
            assertThat(service.isDigest(SAMPLE_HEADER + alphabet.substring(64 - REMAINDER_LENGTH)))
                    .isTrue();
        }

        @Test
        @DisplayName("rejects an absent value")
        void rejectsAnAbsentValue() {
            assertThat(service.isDigest(null)).isFalse();
        }

        @Test
        @DisplayName("rejects an empty value")
        void rejectsAnEmptyValue() {
            assertThat(service.isDigest("")).isFalse();
        }

        @Test
        @DisplayName("rejects a cleartext credential")
        void rejectsACleartextCredential() {
            assertThat(service.isDigest(CREDENTIAL)).isFalse();
        }

        @Test
        @DisplayName("rejects a base-64 style value of the column width, whose alphabet adds + and =")
        void rejectsABase64LookAlike() {
            final String base64Like = "QUJDREVGRw==".repeat(5);
            assertThat(base64Like).hasSize(60);
            assertThat(service.isDigest(base64Like)).isFalse();
        }

        @Test
        @DisplayName("rejects a purely numeric value of the column width")
        void rejectsANumericValueOfTheColumnWidth() {
            assertThat(service.isDigest("1".repeat(60))).isFalse();
        }
    }

    @Nested
    @DisplayName("persistence-boundary guard")
    class PersistenceBoundaryGuard {

        @Test
        @DisplayName("returns a genuine digest unchanged")
        void returnsAGenuineDigestUnchanged() {
            final String digest = service.encode(CREDENTIAL);
            assertThat(service.requireDigest(CredentialDigestService.USER_SECURITY_PWD_FIELD, digest))
                    .isSameAs(digest);
        }

        @Test
        @DisplayName("refuses a cleartext credential")
        void refusesACleartextCredential() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.requireDigest(
                            CredentialDigestService.USER_SECURITY_PWD_FIELD, CREDENTIAL))
                    .withMessageContaining(CredentialDigestService.USER_SECURITY_PWD_FIELD);
        }

        @Test
        @DisplayName("never reports the value it refused")
        void neverReportsTheRefusedValue() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.requireDigest(
                            CredentialDigestService.USER_SECURITY_PWD_FIELD, CREDENTIAL))
                    .withMessageNotContaining(CREDENTIAL);
        }

        @Test
        @DisplayName("refuses an absent value, because the column is not nullable")
        void refusesAnAbsentValue() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.requireDigest(
                            CredentialDigestService.USER_SECURITY_PWD_FIELD, null));
        }

        @Test
        @DisplayName("refuses a blank value")
        void refusesABlankValue() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.requireDigest(
                            CredentialDigestService.USER_SECURITY_PWD_FIELD, "   "));
        }

        @Test
        @DisplayName("refuses a value one character short of a digest")
        void refusesFiftyNineCharacters() {
            final String short59 = service.encode(CREDENTIAL).substring(0, 59);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.requireDigest(
                            CredentialDigestService.USER_SECURITY_PWD_FIELD, short59));
        }

        @Test
        @DisplayName("refuses a value one character over a digest")
        void refusesSixtyOneCharacters() {
            final String long61 = service.encode(CREDENTIAL) + "a";
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.requireDigest(
                            CredentialDigestService.USER_SECURITY_PWD_FIELD, long61));
        }

        @Test
        @DisplayName("states the required form so the caller knows what to supply")
        void statesTheRequiredForm() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.requireDigest(
                            CredentialDigestService.USER_SECURITY_PWD_FIELD, CREDENTIAL))
                    .withMessageContaining("BCrypt digest")
                    .withMessageContaining("encode");
        }

        @Test
        @DisplayName("requires a column name so a refusal is attributable")
        void requiresAColumnName() {
            final String digest = service.encode(CREDENTIAL);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.requireDigest(null, digest))
                    .withMessageContaining("column name");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.requireDigest("  ", digest))
                    .withMessageContaining("column name");
        }

        @Test
        @DisplayName("checks the column name before the value, so a nameless refusal is impossible")
        void checksTheColumnNameFirst() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.requireDigest(null, CREDENTIAL))
                    .withMessageContaining("column name")
                    .withMessageNotContaining(CREDENTIAL);
        }
    }

    @Nested
    @DisplayName("credential material never leaves the service")
    class NoCredentialLeakage {

        @Test
        @DisplayName("the refusal of a blank credential does not echo the argument")
        void blankRefusalDoesNotEcho() {
            final String whitespace = "\t\t\t";
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.encode(whitespace))
                    .withMessageNotContaining(whitespace);
        }

        @Test
        @DisplayName("a digest reveals no fragment of the credential that produced it")
        void digestRevealsNoFragment() {
            final String digest = service.encode(CREDENTIAL);
            for (int length = 4; length <= CREDENTIAL.length(); length++) {
                assertThat(digest).doesNotContain(CREDENTIAL.substring(0, length));
            }
        }

        @Test
        @DisplayName("what the factory produces is exactly what the boundary guard admits")
        void theFactoryAndTheGuardAgree() {
            final String digest = service.encode(CREDENTIAL);
            assertThat(service.requireDigest(CredentialDigestService.USER_SECURITY_PWD_FIELD, digest))
                    .isEqualTo(digest);
            assertThat(service.matches(CREDENTIAL, digest)).isTrue();
        }
    }
}
