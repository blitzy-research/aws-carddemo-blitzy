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
package com.carddemo.domain.enums;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link UserType}, the role code that decides which menu a sign-on reaches.
 *
 * <p><strong>What the legacy authority is.</strong> The field is
 * {@code SEC-USR-TYPE PIC X(01)}, the fifth of six fields in the eighty-byte user-security record
 * declared in {@code app/cpy/CSUSR01Y.cpy}: an eight-character sign-on identifier, a twenty-character
 * given name, a twenty-character family name, an eight-character password, this one-byte role code and
 * twenty-three bytes of trailing filler. The cluster definition in {@code app/jcl/DUSRSECJ.jcl}
 * declares the geometry independently as {@code KEYS(8,0)} over {@code RECORDSIZE(80,80)}.
 *
 * <p><strong>Why this one byte is a security decision.</strong> The sign-on program branches on this
 * field alone to choose between the administrative menu and the ordinary menu. There is no second
 * check, no group membership and no permission table: the byte <em>is</em> the authorisation. A
 * lookup that silently folded case, trimmed padding or fell back to a default on an unrecognised byte
 * would therefore be a privilege decision made by accident. This class pins the opposite behaviour:
 * an unrecognised byte resolves to nothing at all, and nothing is ever treated as administrative.
 *
 * <p><strong>How the seeded data anchors the assertions.</strong> The provisioning job carries all ten
 * users in-stream as card images at {@code app/jcl/DUSRSECJ.jcl} lines 35 to 44. Five carry the
 * administrative code and five carry the ordinary code, giving an exact five-and-five split that this
 * class reproduces. Each card is fifty-seven characters of populated data &mdash; eight plus twenty
 * plus twenty plus eight plus one &mdash; with the record's remaining twenty-three bytes supplied as
 * filler when the record is written.
 *
 * <p><strong>Deliberately not asserted.</strong> There is no third role. The legacy record admits no
 * other code, and the four administrative transactions plus the administrative menu are gated on the
 * administrative code alone, so introducing a supervisor or read-only role would be feature expansion.
 * Nothing here asserts anything about the password field: it is a separate concern handled by the
 * entity that stores a digest in place of the legacy cleartext.
 */
@DisplayName("UserType — the one-byte role code behind the menu split")
class UserTypeTest {

    /** Width of the role code field, from the copybook. */
    private static final int ROLE_CODE_WIDTH = 1;

    /** The six declared field widths of the user-security record, in copybook order. */
    private static final int[] USER_RECORD_FIELD_WIDTHS = {8, 20, 20, 8, 1, 23};

    /** The record width the cluster definition declares. */
    private static final int DECLARED_RECORD_WIDTH = 80;

    /** The one-based offset at which the role code begins. */
    private static final int ROLE_CODE_ONE_BASED_OFFSET = 57;

    /** The role codes the ten seeded users carry, in the order the provisioning job lists them. */
    private static final List<String> SEEDED_ROLE_CODES =
            List.of("A", "A", "A", "A", "A", "U", "U", "U", "U", "U");

    // =================================================================================================
    // VOCABULARY
    // =================================================================================================

    /**
     * Verifies the two-value vocabulary the record admits.
     */
    @Nested
    @DisplayName("vocabulary")
    class Vocabulary {

        @Test
        @DisplayName("exactly two roles exist, because the sign-on program branches exactly two ways")
        void exactlyTwoRolesExist() {
            assertThat(UserType.values()).hasSize(2);
        }

        @Test
        @DisplayName("the administrative role carries A and the ordinary role carries U")
        void theRolesCarryTheirCodes() {
            assertThat(UserType.ADMIN.getCode()).isEqualTo("A");
            assertThat(UserType.USER.getCode()).isEqualTo("U");
        }

        @Test
        @DisplayName("the administrative role is declared first, matching the order the provisioning "
                + "job writes its records")
        void theAdministrativeRoleIsDeclaredFirst() {
            assertThat(UserType.values()).containsExactly(UserType.ADMIN, UserType.USER);
        }

        @Test
        @DisplayName("the two codes are distinct, so no record byte resolves ambiguously")
        void theTwoCodesAreDistinct() {
            assertThat(UserType.ADMIN.getCode()).isNotEqualTo(UserType.USER.getCode());
        }

        @Test
        @DisplayName("each code fills the one-byte field exactly, so no role can displace the filler "
                + "that closes the record")
        void eachCodeFillsTheFieldExactly() {
            for (final UserType role : UserType.values()) {
                assertThat(role.getCode())
                        .as("declared width of the code carried by %s", role.name())
                        .hasSize(ROLE_CODE_WIDTH);
                assertThat(role.getCode().getBytes(StandardCharsets.US_ASCII))
                        .as("encoded width of the code carried by %s", role.name())
                        .hasSize(ROLE_CODE_WIDTH);
            }
        }
    }

    // =================================================================================================
    // BYTE GEOMETRY
    // =================================================================================================

    /**
     * Verifies the role code's position in the eighty-byte record.
     */
    @Nested
    @DisplayName("byte geometry inside the 80-byte user-security record")
    class ByteGeometry {

        @Test
        @DisplayName("the six declared field widths sum to the eighty bytes the cluster declares")
        void theDeclaredWidthsSumToTheRecordWidth() {
            int summed = 0;
            for (final int width : USER_RECORD_FIELD_WIDTHS) {
                summed += width;
            }

            assertThat(USER_RECORD_FIELD_WIDTHS).hasSize(6);
            assertThat(summed)
                    .as("summed declared field widths against RECORDSIZE(80,80)")
                    .isEqualTo(DECLARED_RECORD_WIDTH);
        }

        @Test
        @DisplayName("the role code begins at byte 57, immediately after the eight-byte password")
        void theRoleCodeBeginsAtByteFiftySeven() {
            int bytesPreceding = 0;
            for (int field = 0; field < 4; field++) {
                bytesPreceding += USER_RECORD_FIELD_WIDTHS[field];
            }

            assertThat(bytesPreceding + 1)
                    .as("one-based offset derived by summing the widths ahead of the role code")
                    .isEqualTo(ROLE_CODE_ONE_BASED_OFFSET);
            assertThat(USER_RECORD_FIELD_WIDTHS[4]).isEqualTo(ROLE_CODE_WIDTH);
        }

        @Test
        @DisplayName("the populated part of a provisioning card is fifty-seven characters, and the "
                + "role code is its last one")
        void thePopulatedCardIsFiftySevenCharacters() {
            final int populated = USER_RECORD_FIELD_WIDTHS[0] + USER_RECORD_FIELD_WIDTHS[1]
                    + USER_RECORD_FIELD_WIDTHS[2] + USER_RECORD_FIELD_WIDTHS[3]
                    + USER_RECORD_FIELD_WIDTHS[4];

            assertThat(populated).isEqualTo(ROLE_CODE_ONE_BASED_OFFSET);
            assertThat(populated + USER_RECORD_FIELD_WIDTHS[5])
                    .as("populated data plus filler closes the record")
                    .isEqualTo(DECLARED_RECORD_WIDTH);
        }

        @Test
        @DisplayName("the sign-on key is the leading eight bytes, so the role code is never part of "
                + "the key")
        void theRoleCodeIsNeverPartOfTheKey() {
            assertThat(USER_RECORD_FIELD_WIDTHS[0])
                    .as("KEYS(8,0) covers only the sign-on identifier")
                    .isEqualTo(8);
            assertThat(ROLE_CODE_ONE_BASED_OFFSET)
                    .as("the role code starts well beyond the key")
                    .isGreaterThan(USER_RECORD_FIELD_WIDTHS[0]);
        }
    }

    // =================================================================================================
    // ADMINISTRATIVE PREDICATE
    // =================================================================================================

    /**
     * Verifies the administrative predicate, which is the whole of the authorisation decision.
     */
    @Nested
    @DisplayName("administrative predicate")
    class AdministrativePredicate {

        @Test
        @DisplayName("only the administrative role reports itself administrative")
        void onlyTheAdministrativeRoleIsAdministrative() {
            assertThat(UserType.ADMIN.isAdmin()).isTrue();
            assertThat(UserType.USER.isAdmin()).isFalse();
        }

        @Test
        @DisplayName("the predicate partitions the vocabulary, so exactly one role is privileged")
        void thePredicatePartitionsTheVocabulary() {
            long privileged = 0;
            for (final UserType role : UserType.values()) {
                if (role.isAdmin()) {
                    privileged++;
                }
            }

            assertThat(privileged).isEqualTo(1);
        }
    }

    // =================================================================================================
    // LOOKUP
    // =================================================================================================

    /**
     * Verifies the tolerant lookup from a raw record byte to a role.
     */
    @Nested
    @DisplayName("lookup from a raw record byte")
    class Lookup {

        @Test
        @DisplayName("both codes resolve to their role")
        void bothCodesResolve() {
            assertThat(UserType.fromCode("A")).contains(UserType.ADMIN);
            assertThat(UserType.fromCode("U")).contains(UserType.USER);
        }

        @Test
        @DisplayName("every role round-trips through its own code")
        void everyRoleRoundTrips() {
            for (final UserType role : UserType.values()) {
                assertThat(UserType.fromCode(role.getCode()))
                        .as("round trip of %s", role.name())
                        .contains(role);
            }
        }

        @Test
        @DisplayName("an unrecognised byte resolves to nothing, so no unknown value is ever treated as "
                + "administrative by default")
        void anUnrecognisedByteResolvesToNothing() {
            assertThat(UserType.fromCode("X")).isEmpty();
            assertThat(UserType.fromCode("S")).isEmpty();
            assertThat(UserType.fromCode(" ")).isEmpty();
            assertThat(UserType.fromCode("0")).isEmpty();
        }

        @Test
        @DisplayName("no case folding is applied, so a lowercase a is not an administrator")
        void noCaseFoldingIsApplied() {
            assertThat(UserType.fromCode("a")).isEmpty();
            assertThat(UserType.fromCode("u")).isEmpty();
        }

        @Test
        @DisplayName("no trimming is applied, so a padded byte is not an administrator")
        void noTrimmingIsApplied() {
            assertThat(UserType.fromCode("A ")).isEmpty();
            assertThat(UserType.fromCode(" A")).isEmpty();
            assertThat(UserType.fromCode("ADMIN")).isEmpty();
        }

        @Test
        @DisplayName("an empty or absent value resolves to nothing rather than throwing")
        void anEmptyOrAbsentValueResolvesToNothing() {
            assertThat(UserType.fromCode("")).isEmpty();
            assertThat(UserType.fromCode(null)).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("nothing outside the vocabulary can be coaxed into reporting itself administrative")
        void nothingOutsideTheVocabularyIsAdministrative() {
            final List<String> probes =
                    List.of("a", "u", "X", " ", "", "A ", "ADMIN", "0", "Y", "N");

            for (final String probe : probes) {
                assertThat(UserType.fromCode(probe).map(UserType::isAdmin).orElse(false))
                        .as("administrative claim for probe [%s]", probe)
                        .isFalse();
            }
        }
    }

    // =================================================================================================
    // SEEDED USERS
    // =================================================================================================

    /**
     * Verifies the five-and-five split the provisioning job establishes.
     */
    @Nested
    @DisplayName("the ten seeded users")
    class SeededUsers {

        @Test
        @DisplayName("ten users are seeded, five administrative and five ordinary")
        void tenUsersAreSeededInAFiveAndFiveSplit() {
            assertThat(SEEDED_ROLE_CODES).hasSize(10);
            assertThat(SEEDED_ROLE_CODES.stream().filter("A"::equals).count()).isEqualTo(5);
            assertThat(SEEDED_ROLE_CODES.stream().filter("U"::equals).count()).isEqualTo(5);
        }

        @Test
        @DisplayName("every seeded role code resolves, so the seed introduces no value the type cannot "
                + "represent")
        void everySeededCodeResolves() {
            for (final String code : SEEDED_ROLE_CODES) {
                assertThat(UserType.fromCode(code))
                        .as("seeded role code [%s]", code)
                        .isPresent();
            }
        }

        @Test
        @DisplayName("the seed grants administrative rights to exactly five of the ten users")
        void theSeedGrantsAdministrativeRightsToFive() {
            final long privileged = SEEDED_ROLE_CODES.stream()
                    .map(UserType::fromCode)
                    .filter(role -> role.map(UserType::isAdmin).orElse(false))
                    .count();

            assertThat(privileged).isEqualTo(5);
        }

        @Test
        @DisplayName("the administrative users are listed before the ordinary ones, matching the "
                + "provisioning card order")
        void theAdministrativeUsersAreListedFirst() {
            assertThat(SEEDED_ROLE_CODES.subList(0, 5)).containsOnly("A");
            assertThat(SEEDED_ROLE_CODES.subList(5, 10)).containsOnly("U");
        }
    }
}
