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
import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link UserType}, the one-character role vocabulary of the
 * credential record.
 *
 * <h2>What is under test</h2>
 *
 * <p>The credential copybook {@code app/cpy/CSUSR01Y.cpy} declares a
 * one-character type field at the end of its eighty-byte record. The sign-on
 * program {@code app/cbl/COSGN00C.cbl} branches on it at lines 227 through 236:
 * the administrator code routes to the administrative menu and anything else
 * routes to the main menu, which is why the type is modelled as a two-value
 * vocabulary with an administrator predicate rather than as a general role set.
 * The communication-area copybook {@code app/cpy/COCOM01Y.cpy} carries the same
 * split as two level-88 condition names, and the ten seeded credential records at
 * lines 35 through 44 of {@code app/jcl/DUSRSECJ.jcl} are five administrator
 * records and five standard-user records, so both codes are exercised by seed data
 * alone.</p>
 *
 * <h2>Why the branch is administrator-versus-everything-else</h2>
 *
 * <p>The legacy test is an equality comparison against the administrator code with
 * an else branch, not a two-way comparison, so an unrecognised code routes to the
 * main menu rather than being rejected. The predicate therefore answers only
 * whether the type is the administrator, and resolution of an unrecognised code
 * yields an empty result that the calling service treats as the else branch.</p>
 *
 * <p>Translated from the CardDemo COBOL estate at checkout commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("UserType: the one-character role vocabulary of the credential record")
class UserTypeBoundaryTest {

    /** Number of roles the credential record admits. */
    private static final int ROLE_COUNT = 2;

    /** Width of the type field in the eighty-byte credential record, in bytes. */
    private static final int TYPE_FIELD_WIDTH = 1;

    @Nested
    @DisplayName("the admitted roles")
    class AdmittedRoles {

        @Test
        @DisplayName("exactly two roles are defined, matching the two-way branch of the sign-on program")
        void exactlyTwoRolesAreDefined() {
            assertThat(UserType.values()).hasSize(ROLE_COUNT);
        }

        @ParameterizedTest(name = "{0} carries the code {1}")
        @CsvSource({"ADMIN, A", "USER, U"})
        @DisplayName("each role carries its legacy one-character code in upper case")
        void eachRoleCarriesItsLegacyCode(UserType userType, String expectedCode) {
            assertThat(userType.getCode()).isEqualTo(expectedCode);
        }

        @ParameterizedTest(name = "the code of {0} occupies exactly one byte")
        @ValueSource(strings = {"ADMIN", "USER"})
        @DisplayName("every code fits the one-character type field exactly")
        void everyCodeFitsTheTypeField(String constantName) {
            UserType userType = UserType.valueOf(constantName);

            assertThat(userType.getCode().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(TYPE_FIELD_WIDTH);
        }

        @Test
        @DisplayName("every code is distinct, so a stored type identifies one role")
        void everyCodeIsDistinct() {
            assertThat(Arrays.stream(UserType.values()).map(UserType::getCode))
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("no role beyond the two is defined, so no privilege level is invented")
        void noRoleBeyondTheTwoIsDefined() {
            assertThat(Arrays.stream(UserType.values()).map(Enum::name))
                    .containsExactlyInAnyOrder("ADMIN", "USER")
                    .doesNotContain("SUPERUSER", "OPERATOR", "AUDITOR", "GUEST", "SERVICE");
        }
    }

    @Nested
    @DisplayName("the administrator predicate")
    class AdministratorPredicate {

        @Test
        @DisplayName("the administrator role reports itself as an administrator")
        void theAdministratorRoleReportsItself() {
            assertThat(UserType.ADMIN.isAdmin()).isTrue();
        }

        @Test
        @DisplayName("the standard role does not report itself as an administrator")
        void theStandardRoleDoesNotReportItself() {
            assertThat(UserType.USER.isAdmin()).isFalse();
        }

        @Test
        @DisplayName("exactly one role satisfies the predicate, so the split is genuinely two-way")
        void exactlyOneRoleSatisfiesThePredicate() {
            assertThat(Arrays.stream(UserType.values()).filter(UserType::isAdmin))
                    .containsExactly(UserType.ADMIN);
        }
    }

    @Nested
    @DisplayName("resolution from a stored code")
    class ResolutionFromCode {

        @ParameterizedTest(name = "the code {0} resolves to the role carrying it")
        @ValueSource(strings = {"A", "U"})
        @DisplayName("every admitted code resolves to its own role")
        void everyAdmittedCodeResolves(String code) {
            Optional<UserType> resolved = UserType.fromCode(code);

            assertThat(resolved).isPresent();
            assertThat(resolved.orElseThrow().getCode()).isEqualTo(code);
        }

        @ParameterizedTest(name = "the unadmitted code [{0}] resolves to nothing")
        @ValueSource(strings = {
            "a", "u", " ", "  ", "AU", "UA", "ADMIN", "USER", "X", "0", "1", "-", "*", "A ", " A"})
        @DisplayName("an unadmitted code resolves to an empty result, lower case included")
        void anUnadmittedCodeResolvesToNothing(String code) {
            assertThat(UserType.fromCode(code)).isEmpty();
        }

        @ParameterizedTest
        @NullSource
        @DisplayName("a null code resolves to an empty result rather than throwing")
        void aNullCodeResolvesToNothing(String code) {
            assertThat(UserType.fromCode(code)).isEmpty();
        }

        @ParameterizedTest
        @EmptySource
        @DisplayName("an empty code resolves to an empty result rather than throwing")
        void anEmptyCodeResolvesToNothing(String code) {
            assertThat(UserType.fromCode(code)).isEmpty();
        }

        @Test
        @DisplayName("resolution covers every declared role, so no role is unreachable")
        void resolutionCoversEveryDeclaredRole() {
            for (UserType userType : UserType.values()) {
                assertThat(UserType.fromCode(userType.getCode())).containsSame(userType);
            }
        }

        @Test
        @DisplayName("the seeded administrator and standard codes both resolve, matching the seed split")
        void theSeededCodesBothResolve() {
            assertThat(UserType.fromCode("A")).containsSame(UserType.ADMIN);
            assertThat(UserType.fromCode("U")).containsSame(UserType.USER);
        }
    }
}
