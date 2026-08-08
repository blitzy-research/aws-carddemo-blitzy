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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for the sign-on identity entity, the Java translation of the {@code SEC-USER-DATA}
 * layout in {@code app/cpy/CSUSR01Y.cpy} (80 bytes).
 *
 * <h2>What these tests pin</h2>
 * This entity is the only credential-bearing type in the module, and it is deliberately a plain
 * carrier for its four record-layout fields: it normalises none of them, because a legacy
 * fixed-width field must round-trip byte for byte. It hashes nothing either - credential logic lives
 * in {@code com.carddemo.service.CredentialDigestService} and the dependency direction forbids the
 * reverse - but it does <strong>refuse</strong>: the credential column is the one field whose width
 * departs from the layout, and the entity accepts nothing there that is not structurally a BCrypt
 * digest. That guard and the boundary service are complementary rather than alternatives; the
 * service is the only producer of a digest, and the guard is what closes every other route into the
 * column. That makes four of its properties worth asserting rather than assuming.
 *
 * <ol>
 *   <li><strong>It carries a sixty-character digest intact.</strong> The legacy field is eight
 *       characters wide; the migrated column is sixty. A mapping that silently truncated to the
 *       legacy width would make every stored digest unverifiable.</li>
 *   <li><strong>It normalises no record-layout field.</strong> No trimming, no padding and no
 *       upper-casing - so a value arrives and leaves byte for byte, and a role code outside the
 *       declared pair round-trips instead of failing. That tolerance is required: legacy sign-on
 *       tests only the administrator condition and routes every other value to the main menu through
 *       an unconditional alternative, so an unrecognised code must not fail here.</li>
 *   <li><strong>It refuses a credential that is not a digest.</strong> The one shape check on this
 *       class guards the one column whose content is not legacy data, so an eight-character
 *       cleartext value cannot be written where a sixty-character digest belongs.</li>
 *   <li><strong>Identity never depends on credential material.</strong> Equality and hashing use the
 *       assigned business key alone, so a credential change cannot move an instance between hash
 *       buckets, and no rendering of an instance exposes the digest, the personal names or the
 *       authorization code.</li>
 * </ol>
 *
 * <h2>Test data discipline</h2>
 * The digest values below are obviously synthetic strings of the right shape. The eight-character
 * literal carried in-stream by {@code app/jcl/DUSRSECJ.jcl} appears here only as a value the entity
 * must refuse, and no assertion depends on its content. The identifiers, names and role codes used here are the non-secret seed
 * values from that member.
 */
@DisplayName("UserSecurity")
class UserSecurityBoundaryTest {

    /** A synthetic value shaped exactly like a BCrypt digest: seven-character header plus 53. */
    private static final String DIGEST = "$2a$10$" + "a".repeat(53);

    /** A second synthetic digest, so a credential change can be observed. */
    private static final String OTHER_DIGEST = "$2b$12$" + "b".repeat(53);

    private static final String ADMIN_ID = "ADMIN001";
    private static final String USER_ID = "USER0001";

    /**
     * Builds an administrator identity from the non-secret seed values.
     *
     * @return a fully populated instance carrying {@link #DIGEST}
     */
    private static UserSecurity administrator() {
        return new UserSecurity(ADMIN_ID, "MARGARET", "GOLD", DIGEST, "A");
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("populates every field in record order")
        void populatesEveryField() {
            final UserSecurity identity = administrator();
            assertThat(identity.getSecUsrId()).isEqualTo(ADMIN_ID);
            assertThat(identity.getSecUsrFname()).isEqualTo("MARGARET");
            assertThat(identity.getSecUsrLname()).isEqualTo("GOLD");
            assertThat(SensitiveValues.fingerprint(identity.credentialDigest())).isEqualTo(SensitiveValues.fingerprint(DIGEST));
            assertThat(identity.getSecUsrType()).isEqualTo("A");
        }

        @Test
        @DisplayName("carries all sixty characters of a digest, not the legacy width of eight")
        void carriesTheFullDigestWidth() {
            assertThat(administrator().credentialDigest().length()).isEqualTo(60);
        }

        @Test
        @DisplayName("leaves the provider constructor with every field unassigned")
        void providerConstructorLeavesFieldsUnassigned() {
            final UserSecurity hydrating = new UserSecurity();
            assertThat(hydrating.getSecUsrId()).isNull();
            assertThat(hydrating.getSecUsrFname()).isNull();
            assertThat(hydrating.getSecUsrLname()).isNull();
            assertThat(hydrating.credentialDigest()).isNull();
            assertThat(hydrating.getSecUsrType()).isNull();
        }

        @Test
        @DisplayName("refuses a credential that is not a digest, and says so without echoing it")
        void refusesACredentialThatIsNotADigest() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new UserSecurity(
                            USER_ID, "LAWRENCE", "THOMAS", "not-a-digest", "U"))
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .doesNotContain("not-a-digest"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "PASSWORD",
            "$2a$10$short",
            "$2c$10$aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "$2a$04$aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        })
        @DisplayName("refuses a value of the wrong width, marker or cost alike")
        void refusesEveryMalformedCredential(final String candidate) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new UserSecurity(USER_ID, "LAWRENCE", "THOMAS", candidate, "U"));
        }
    }

    @Nested
    @DisplayName("accessors normalise nothing and only the credential is checked")
    class Accessors {

        @Test
        @DisplayName("replaces the digest without hashing it, checking only its shape")
        void replacesTheDigest() {
            final UserSecurity identity = administrator();
            identity.replaceCredentialDigest(OTHER_DIGEST);
            assertThat(identity.credentialDigest())
                    .as("no rehashing and no normalising: the value stored is the value supplied")
                    .isEqualTo(OTHER_DIGEST);
        }

        @Test
        @DisplayName("preserves the space padding the fixed-width record carries")
        void preservesPadding() {
            final UserSecurity identity = new UserSecurity();
            identity.setSecUsrId("ADMIN2  ");
            identity.setSecUsrFname("RUSSELL             ");
            identity.setSecUsrLname("RUSSELL             ");
            assertThat(identity.getSecUsrId()).isEqualTo("ADMIN2  ");
            assertThat(identity.getSecUsrFname()).hasSize(20);
            assertThat(identity.getSecUsrLname()).hasSize(20);
        }

        @Test
        @DisplayName("preserves case rather than folding it")
        void preservesCase() {
            final UserSecurity identity = new UserSecurity();
            identity.setSecUsrFname("Ajith");
            identity.setSecUsrLname("Kumar");
            assertThat(identity.getSecUsrFname()).isEqualTo("Ajith");
            assertThat(identity.getSecUsrLname()).isEqualTo("Kumar");
        }

        @ParameterizedTest
        @ValueSource(strings = {"A", "U", "X", "1", " ", "a", "u"})
        @DisplayName("accepts any role code, preserving the legacy unconditional routing tolerance")
        void acceptsAnyRoleCode(final String code) {
            final UserSecurity identity = administrator();
            identity.setSecUsrType(code);
            assertThat(identity.getSecUsrType()).isEqualTo(code);
        }

        @Test
        @DisplayName("accepts a null assignment, leaving nullability to the database")
        void acceptsANullAssignment() {
            final UserSecurity identity = administrator();
            identity.setSecUsrType(null);
            assertThat(identity.getSecUsrType()).isNull();
        }
    }

    @Nested
    @DisplayName("identity depends on the business key alone")
    class Identity {

        @Test
        @DisplayName("is reflexive")
        void isReflexive() {
            final UserSecurity identity = administrator();
            assertThat(identity).isEqualTo(identity);
        }

        @Test
        @DisplayName("treats two instances with the same key as equal despite different digests")
        void ignoresTheDigest() {
            final UserSecurity first = administrator();
            final UserSecurity second =
                    new UserSecurity(ADMIN_ID, "MARGARET", "GOLD", OTHER_DIGEST, "A");
            assertThat(first).isEqualTo(second);
            assertThat(first).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("treats two instances with different keys as unequal")
        void distinguishesByKey() {
            final UserSecurity first = administrator();
            final UserSecurity second =
                    new UserSecurity(USER_ID, "MARGARET", "GOLD", DIGEST, "A");
            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("is unequal to null and to an unrelated type")
        void isUnequalToOtherThings() {
            final UserSecurity identity = administrator();
            assertThat(identity).isNotEqualTo(null);
            assertThat(identity).isNotEqualTo(ADMIN_ID);
        }

        @Test
        @DisplayName("hashes an unassigned key without failing")
        void hashesAnUnassignedKey() {
            assertThat(new UserSecurity().hashCode()).isZero();
        }

        @Test
        @DisplayName("treats two unassigned keys as equal, as primary-key equality requires")
        void treatsUnassignedKeysAsEqual() {
            assertThat(new UserSecurity()).isEqualTo(new UserSecurity());
        }
    }

    @Nested
    @DisplayName("no rendering exposes sensitive material")
    class Rendering {

        @Test
        @DisplayName("does not render the digest")
        void doesNotRenderTheDigest() {
            assertThat(administrator().toString()).doesNotContain(DIGEST);
        }

        @Test
        @DisplayName("does not render the personal names or the authorization code")
        void doesNotRenderPersonalData() {
            final String rendered = administrator().toString();
            assertThat(rendered).doesNotContain("MARGARET");
            assertThat(rendered).doesNotContain("GOLD");
        }
    }
}
