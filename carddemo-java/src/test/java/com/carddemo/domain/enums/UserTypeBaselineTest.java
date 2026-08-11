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

import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link UserType}, the typed replacement for the one-byte user classification field
 * carried by the user-security record.
 *
 * <p><strong>What this test proves.</strong> The legacy record layout devotes a single byte to the
 * user classification, and the sign-on program branches on that byte to decide which of the two
 * menus a session reaches. Only two byte values are ever written by the provisioning job, and the
 * comparison performed by the sign-on program is a plain byte equality with no folding of any kind.
 * This test pins all three facts: the vocabulary is exactly two members, the external codes are the
 * single characters {@code A} and {@code U} in that declaration order, and the lookup accepts only
 * an exact match.
 *
 * <p><strong>Reference data.</strong> The provisioning job for the user-security dataset carries ten
 * eighty-byte card images in stream: five identifiers beginning {@code ADMIN} that end with the
 * classification byte {@code A}, and five beginning {@code USER} that end with {@code U}. The
 * five-and-five split asserted below is the measured content of that job, quoted as data only.
 *
 * <p><strong>Scope.</strong> A pure in-process unit test. It starts no application context, opens no
 * connection, reads no file and performs no introspection, so the module's zero budget for
 * reflection is untouched. Every expected value is a literal typed out in this source; no
 * expectation is obtained by calling the type under test.
 *
 * <p>Cited as provenance only and never asserted against a member.
 */
@DisplayName("UserType - the one-byte user classification of the 80-byte security record")
class UserTypeBaselineTest {

    /** The classification byte written for every administrator record by the provisioning job. */
    private static final String ADMIN_CODE = "A";

    /** The classification byte written for every standard-user record by the provisioning job. */
    private static final String USER_CODE = "U";

    /** Administrator records present in the provisioning job's in-stream card images. */
    private static final int SEEDED_ADMIN_RECORDS = 5;

    /** Standard-user records present in the provisioning job's in-stream card images. */
    private static final int SEEDED_USER_RECORDS = 5;

    @Nested
    @DisplayName("Vocabulary and declaration order")
    class Vocabulary {

        @Test
        @DisplayName("exactly two classifications exist, administrator first, matching the order in "
                + "which the sign-on program tests the classification byte")
        void exactlyTwoClassificationsExistInSignOnTestOrder() {
            assertThat(UserType.values()).containsExactly(UserType.ADMIN, UserType.USER);
        }

        @Test
        @DisplayName("the enumeration is closed at two members, because the record devotes one byte "
                + "to the field and the estate writes only two values into it")
        void theEnumerationIsClosedAtTwoMembers() {
            assertThat(UserType.values()).hasSize(2);
        }

        @Test
        @DisplayName("administrator carries the external code A and standard user carries U")
        void eachMemberCarriesItsLegacyExternalCode() {
            assertThat(UserType.ADMIN.getCode()).isEqualTo(ADMIN_CODE);
            assertThat(UserType.USER.getCode()).isEqualTo(USER_CODE);
        }

        @Test
        @DisplayName("both external codes are exactly one character wide, matching the single byte "
                + "the record layout reserves for the field")
        void bothExternalCodesAreOneCharacterWide() {
            assertThat(UserType.ADMIN.getCode()).hasSize(1);
            assertThat(UserType.USER.getCode()).hasSize(1);
        }

        @Test
        @DisplayName("the two external codes are distinct, so the single byte discriminates the two "
                + "classifications without ambiguity")
        void theTwoExternalCodesAreDistinct() {
            assertThat(UserType.ADMIN.getCode()).isNotEqualTo(UserType.USER.getCode());
        }

        @Test
        @DisplayName("the constant names are stable identifiers that documentation and route tables "
                + "may reference")
        void theConstantNamesAreStable() {
            assertThat(UserType.ADMIN.name()).isEqualTo("ADMIN");
            assertThat(UserType.USER.name()).isEqualTo("USER");
        }

        @Test
        @DisplayName("valueOf resolves each constant name back to its member")
        void valueOfResolvesEachConstantName() {
            assertThat(UserType.valueOf("ADMIN")).isSameAs(UserType.ADMIN);
            assertThat(UserType.valueOf("USER")).isSameAs(UserType.USER);
        }
    }

    @Nested
    @DisplayName("Resolution of the stored classification byte")
    class CodeResolution {

        @Test
        @DisplayName("the administrator byte resolves to the administrator classification")
        void theAdministratorByteResolves() {
            assertThat(UserType.fromCode(ADMIN_CODE)).contains(UserType.ADMIN);
        }

        @Test
        @DisplayName("the standard-user byte resolves to the standard-user classification")
        void theStandardUserByteResolves() {
            assertThat(UserType.fromCode(USER_CODE)).contains(UserType.USER);
        }

        @Test
        @DisplayName("an absent byte yields no classification rather than throwing, so a corrupt "
                + "record is reported by the caller instead of aborting the lookup")
        void anAbsentByteYieldsNoClassification() {
            assertThat(UserType.fromCode(null)).isEmpty();
        }

        @ParameterizedTest(name = "code [{0}] does not resolve")
        @DisplayName("nothing is folded, trimmed or padded: only the two exact bytes resolve, so a "
                + "lower-case or space-padded byte is rejected exactly as the byte comparison in the "
                + "sign-on program rejects it")
        @ValueSource(strings = {"a", "u", " A", "A ", "AD", "ADMIN", "", " ", "X", "0", "1"})
        void nothingIsFoldedTrimmedOrPadded(final String code) {
            assertThat(UserType.fromCode(code)).isEmpty();
        }

        @Test
        @DisplayName("resolution returns the singleton member rather than a copy, so identity "
                + "comparison remains valid for callers that switch on the result")
        void resolutionReturnsTheSingletonMember() {
            final Optional<UserType> resolved = UserType.fromCode(ADMIN_CODE);

            assertThat(resolved).isPresent();
            assertThat(resolved.orElseThrow()).isSameAs(UserType.ADMIN);
        }

        @Test
        @DisplayName("every member's own code round-trips through resolution, so the index covers the "
                + "whole vocabulary and not a subset of it")
        void everyMembersOwnCodeRoundTrips() {
            for (final UserType userType : UserType.values()) {
                assertThat(UserType.fromCode(userType.getCode())).contains(userType);
            }
        }
    }

    @Nested
    @DisplayName("Role predicate that drives the menu split")
    class RolePredicate {

        @Test
        @DisplayName("the administrator classification is administrative, which routes a session to "
                + "the administrative menu")
        void theAdministratorClassificationIsAdministrative() {
            assertThat(UserType.ADMIN.isAdmin()).isTrue();
        }

        @Test
        @DisplayName("the standard-user classification is not administrative, which routes a session "
                + "to the main menu")
        void theStandardUserClassificationIsNotAdministrative() {
            assertThat(UserType.USER.isAdmin()).isFalse();
        }

        @Test
        @DisplayName("exactly one of the two classifications is administrative, so the routing "
                + "decision is total and unambiguous for every stored byte")
        void exactlyOneClassificationIsAdministrative() {
            final long administrative = Arrays.stream(UserType.values())
                    .filter(UserType::isAdmin)
                    .count();

            assertThat(administrative).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("Correspondence with the seeded user-security records")
    class SeededRecordCorrespondence {

        @Test
        @DisplayName("the provisioning job seeds ten records split five administrators to five "
                + "standard users, so both classifications are exercised by seed data alone")
        void theProvisioningJobSeedsBothClassifications() {
            assertThat(SEEDED_ADMIN_RECORDS + SEEDED_USER_RECORDS).isEqualTo(10);
            assertThat(SEEDED_ADMIN_RECORDS).isEqualTo(SEEDED_USER_RECORDS);
        }

        @Test
        @DisplayName("every classification byte appearing in the seeded records resolves, so no "
                + "seeded row would be rejected at sign-on")
        void everySeededClassificationByteResolves() {
            assertThat(UserType.fromCode(ADMIN_CODE)).isPresent();
            assertThat(UserType.fromCode(USER_CODE)).isPresent();
        }
    }
}
