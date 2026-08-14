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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link UserType}, the {@code SEC-USR-TYPE} discriminator declared {@code PIC X(01)}
 * in copybook member {@code CSUSR01Y}.
 *
 * <h2>This one character decides an authorization outcome</h2>
 *
 * <p>{@code COSGN00C} L227-L236 branches on this field alone to route a signed-on operator either to
 * the administrative menu or to the main menu, and the target reproduces that split as a role
 * mapping in the security configuration. The consequence for this test is that a lookup miss must
 * <em>not</em> silently resolve to either type: a value outside the two-code vocabulary has to be
 * absent, so the caller is forced to decide rather than inheriting a default that might be
 * administrative.</p>
 *
 * <p>The {@link UserType#isAdmin()} predicate is asserted to hold for exactly one of the two
 * constants for the same reason. A predicate that answered true for both, or that acquired a third
 * constant answering true, would grant administrative access on the strength of an edit rather than
 * on the strength of the stored code.</p>
 */
@DisplayName("UserType - the SEC-USR-TYPE discriminator from CSUSR01Y")
class UserTypeSecurityTest {

    @Nested
    @DisplayName("Vocabulary recovered from the one-character discriminator")
    class Vocabulary {

        @Test
        @DisplayName("exactly two constants are declared, because the estate seeds exactly two kinds of operator")
        void exactlyTwoConstantsAreDeclared() {
            assertThat(UserType.values()).hasSize(2);
        }

        @Test
        @DisplayName("the administrative type is declared before the standard type, matching the order the sign-on "
                + "program tests them")
        void theAdministrativeTypeIsDeclaredFirst() {
            assertThat(Arrays.stream(UserType.values()).map(Enum::name).toList())
                    .containsExactly("ADMIN", "USER");
        }

        @Test
        @DisplayName("the two constants carry the raw codes A and U")
        void bothConstantsCarryTheirRawCode() {
            assertThat(UserType.ADMIN.getCode()).isEqualTo("A");
            assertThat(UserType.USER.getCode()).isEqualTo("U");
        }

        @ParameterizedTest
        @EnumSource(UserType.class)
        @DisplayName("each raw code is exactly one character and encodes to a single byte, matching PIC X(01)")
        void eachRawCodeIsASingleAsciiByte(final UserType type) {
            assertThat(type.getCode()).hasSize(1);
            assertThat(type.getCode().getBytes(StandardCharsets.US_ASCII)).hasSize(1);
        }

        @Test
        @DisplayName("no third constant exists for an unset or unrecognised discriminator, so no operator can be "
                + "routed on the strength of a synthetic type")
        void noThirdConstantExists() {
            assertThat(Arrays.stream(UserType.values()).map(Enum::name).toList())
                    .doesNotContain("UNKNOWN", "NONE", "GUEST", "ANONYMOUS", "OTHER", "DEFAULT");
        }

        @Test
        @DisplayName("the two codes are distinct, so the reverse index cannot collide")
        void theTwoCodesAreDistinct() {
            assertThat(UserType.ADMIN.getCode()).isNotEqualTo(UserType.USER.getCode());
        }
    }

    @Nested
    @DisplayName("Administrative predicate, which decides an authorization outcome")
    class AdminPredicate {

        @Test
        @DisplayName("the administrative type is administrative and the standard type is not")
        void onlyTheAdministrativeTypeIsAdministrative() {
            assertThat(UserType.ADMIN.isAdmin()).isTrue();
            assertThat(UserType.USER.isAdmin()).isFalse();
        }

        @Test
        @DisplayName("exactly one of the declared types reports itself administrative, so the predicate cannot have "
                + "been widened without this test failing")
        void exactlyOneTypeIsAdministrative() {
            assertThat(Arrays.stream(UserType.values()).filter(UserType::isAdmin).count())
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("the code A is administrative and the code U is not, answered straight from the raw column "
                + "value the way the sign-on routing does")
        void rawCodesAnswerThePredicateDirectly() {
            assertThat(UserType.fromCode("A").orElseThrow().isAdmin()).isTrue();
            assertThat(UserType.fromCode("U").orElseThrow().isAdmin()).isFalse();
        }
    }

    @Nested
    @DisplayName("Tolerant lookup from a raw discriminator")
    class CodeLookup {

        @ParameterizedTest
        @EnumSource(UserType.class)
        @DisplayName("both raw codes round-trip through the lookup back to their type")
        void bothRawCodesRoundTrip(final UserType type) {
            assertThat(UserType.fromCode(type.getCode())).contains(type);
        }

        @ParameterizedTest
        @NullSource
        @DisplayName("an absent discriminator yields an empty result rather than throwing, which is what makes the "
                + "lookup total against an index that rejects a null key")
        void anAbsentDiscriminatorYieldsAnEmptyResult(final String code) {
            assertThat(UserType.fromCode(code)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "  ", "a", "u", "A ", " A", "AA", "AU", "0", "1", "ADMIN", "USER", "*"})
        @DisplayName("a discriminator that is empty, blank, padded, over-length, lower case or outside the "
                + "vocabulary yields an empty result, so no unvalidated value can be routed")
        void aDiscriminatorOutsideTheVocabularyYieldsAnEmptyResult(final String code) {
            assertThat(UserType.fromCode(code)).isEmpty();
        }

        @Test
        @DisplayName("case folding is not applied, so a lowercase a does not resolve to the administrative type - "
                + "the single most consequential miss this lookup can make")
        void caseFoldingIsNotAppliedToTheAdministrativeCode() {
            assertThat(UserType.fromCode("a")).isEmpty();
            assertThat(UserType.fromCode("A")).contains(UserType.ADMIN);
        }

        @Test
        @DisplayName("a padded discriminator is rejected rather than trimmed into a match")
        void aPaddedDiscriminatorIsNotTrimmed() {
            assertThat(UserType.fromCode("A ")).isEmpty();
            assertThat(UserType.fromCode("U ")).isEmpty();
        }
    }
}
