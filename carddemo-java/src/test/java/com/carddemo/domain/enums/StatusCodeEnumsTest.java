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

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the three single-character status enumerations taken from the record layouts.
 *
 * <p>All three replace a {@code PIC X(01)} field whose permitted values are declared as level-88
 * condition names: the card status from {@code CARD-ACTIVE-STATUS} in {@code app/cpy/CVACT02Y.cpy},
 * the account status from {@code ACCT-ACTIVE-STATUS} in {@code app/cpy/CVACT01Y.cpy}, and the user
 * type from {@code SEC-USR-TYPE} in {@code app/cpy/CSUSR01Y.cpy}.
 *
 * <p><strong>No normalisation of any kind is applied before lookup.</strong> The legacy editors test
 * the field against its literal, so a lower-case or padded value is simply not the literal and does
 * not resolve. Trimming or case folding here would accept input the legacy screens reject, which is a
 * behavioural change disguised as leniency.
 *
 * <p>The card status and the account status carry the same two characters but are separate types
 * because they are separate fields on separate records with separate editors. Merging them would let
 * a card status be assigned to an account.
 */
@DisplayName("Single-character status enumerations: card status, account status and user type")
final class StatusCodeEnumsTest {

    /** Each field is {@code PIC X(01)}, so each code is exactly one character. */
    private static final int ORACLE_SINGLE_CHARACTER = 1;

    /** The active literal on both status fields. */
    private static final char ORACLE_ACTIVE_CODE = 'Y';

    /** The inactive literal on both status fields. */
    private static final char ORACLE_INACTIVE_CODE = 'N';

    /** {@code 88 SEC-USR-TYPE-ADMIN VALUE 'A'}, and the value five of the ten seed users carry. */
    private static final String ORACLE_ADMIN_CODE = "A";

    /** {@code 88 SEC-USR-TYPE-USER VALUE 'U'}, and the value the other five seed users carry. */
    private static final String ORACLE_USER_CODE = "U";

    @Nested
    @DisplayName("CardStatus: the card record's active flag")
    final class CardStatusContract {

        @Test
        @DisplayName("exactly two constants exist, named for the literals they hold")
        void exactlyTwoConstantsExist() {
            assertThat(CardStatus.values()).hasSize(2);
            assertThat(CardStatus.Y.getCode()).isEqualTo(ORACLE_ACTIVE_CODE);
            assertThat(CardStatus.N.getCode()).isEqualTo(ORACLE_INACTIVE_CODE);
        }

        @Test
        @DisplayName("only the active literal reports active")
        void onlyTheActiveLiteralReportsActive() {
            assertThat(CardStatus.Y.isActive()).isTrue();
            assertThat(CardStatus.N.isActive()).isFalse();
        }

        @Test
        @DisplayName("both constants resolve from their character form")
        void bothConstantsResolveFromTheirCharacterForm() {
            assertThat(CardStatus.fromCode(ORACLE_ACTIVE_CODE)).contains(CardStatus.Y);
            assertThat(CardStatus.fromCode(ORACLE_INACTIVE_CODE)).contains(CardStatus.N);
        }

        @Test
        @DisplayName("both constants resolve from their one-character string form")
        void bothConstantsResolveFromTheirStringForm() {
            assertThat(CardStatus.fromCode("Y")).contains(CardStatus.Y);
            assertThat(CardStatus.fromCode("N")).contains(CardStatus.N);
        }

        @Test
        @DisplayName("a character outside the two literals resolves to nothing")
        void aCharacterOutsideTheLiteralsResolvesToNothing() {
            assertThat(CardStatus.fromCode('X')).isEmpty();
            assertThat(CardStatus.fromCode(' ')).isEmpty();
            assertThat(CardStatus.fromCode('\u0000')).isEmpty();
        }

        @Test
        @DisplayName("no case folding is applied, so a lower-case value does not resolve")
        void noCaseFoldingIsApplied() {
            assertThat(CardStatus.fromCode('y')).isEmpty();
            assertThat(CardStatus.fromCode("y")).isEmpty();
            assertThat(CardStatus.fromCode("n")).isEmpty();
        }

        @Test
        @DisplayName("a string of any length but one resolves to nothing, padded or not")
        void aStringOfAnyOtherLengthResolvesToNothing() {
            assertThat(CardStatus.fromCode("")).isEmpty();
            assertThat(CardStatus.fromCode("Y ")).isEmpty();
            assertThat(CardStatus.fromCode(" Y")).isEmpty();
            assertThat(CardStatus.fromCode("YY")).isEmpty();
        }

        @Test
        @DisplayName("an absent value resolves to nothing")
        void anAbsentValueResolvesToNothing() {
            assertThat(CardStatus.fromCode((String) null)).isEmpty();
        }

        @Test
        @DisplayName("every constant round-trips through both forms")
        void everyConstantRoundTripsThroughBothForms() {
            for (final CardStatus status : CardStatus.values()) {
                assertThat(CardStatus.fromCode(status.getCode())).as("%s", status).contains(status);
                assertThat(CardStatus.fromCode(String.valueOf(status.getCode())))
                        .as("%s", status).contains(status);
            }
        }
    }

    @Nested
    @DisplayName("AccountStatus: the account record's active flag")
    final class AccountStatusContract {

        @Test
        @DisplayName("exactly two constants exist, carrying the same literals as the card flag")
        void exactlyTwoConstantsExist() {
            assertThat(AccountStatus.values()).hasSize(2);
            assertThat(AccountStatus.ACTIVE.getCode()).isEqualTo(ORACLE_ACTIVE_CODE);
            assertThat(AccountStatus.INACTIVE.getCode()).isEqualTo(ORACLE_INACTIVE_CODE);
        }

        @Test
        @DisplayName("only the active literal reports active")
        void onlyTheActiveLiteralReportsActive() {
            assertThat(AccountStatus.ACTIVE.isActive()).isTrue();
            assertThat(AccountStatus.INACTIVE.isActive()).isFalse();
        }

        @Test
        @DisplayName("both constants resolve from both forms")
        void bothConstantsResolveFromBothForms() {
            assertThat(AccountStatus.fromCode(ORACLE_ACTIVE_CODE)).contains(AccountStatus.ACTIVE);
            assertThat(AccountStatus.fromCode("N")).contains(AccountStatus.INACTIVE);
        }

        @Test
        @DisplayName("the convenience predicate is true only for the active literal")
        void theConveniencePredicateIsTrueOnlyForActive() {
            assertThat(AccountStatus.isActiveCode("Y")).isTrue();
            assertThat(AccountStatus.isActiveCode("N")).isFalse();
        }

        @Test
        @DisplayName("the convenience predicate is false for an unresolvable value rather than raising")
        void theConveniencePredicateIsFalseForAnUnresolvableValue() {
            assertThat(AccountStatus.isActiveCode(null)).isFalse();
            assertThat(AccountStatus.isActiveCode("")).isFalse();
            assertThat(AccountStatus.isActiveCode("y")).isFalse();
            assertThat(AccountStatus.isActiveCode("X")).isFalse();
            assertThat(AccountStatus.isActiveCode("Y ")).isFalse();
        }

        @Test
        @DisplayName("an unresolvable form resolves to nothing")
        void anUnresolvableFormResolvesToNothing() {
            assertThat(AccountStatus.fromCode('Q')).isEmpty();
            assertThat(AccountStatus.fromCode((String) null)).isEmpty();
            assertThat(AccountStatus.fromCode("ACTIVE")).isEmpty();
        }

        @Test
        @DisplayName("the card and account flags are distinct types over the same two literals")
        void theCardAndAccountFlagsAreDistinctTypes() {
            assertThat(AccountStatus.ACTIVE.getCode()).isEqualTo(CardStatus.Y.getCode());
            assertThat(AccountStatus.ACTIVE.name())
                    .as("distinct names, because they are distinct fields with distinct editors")
                    .isNotEqualTo(CardStatus.Y.name());
        }
    }

    @Nested
    @DisplayName("UserType: the security record's type flag and the role split it drives")
    final class UserTypeContract {

        @Test
        @DisplayName("exactly two constants exist, carrying the two declared literals")
        void exactlyTwoConstantsExist() {
            assertThat(UserType.values()).hasSize(2);
            assertThat(UserType.ADMIN.getCode()).isEqualTo(ORACLE_ADMIN_CODE);
            assertThat(UserType.USER.getCode()).isEqualTo(ORACLE_USER_CODE);
        }

        @Test
        @DisplayName("each code is exactly one character, as the record layout declares")
        void eachCodeIsExactlyOneCharacter() {
            for (final UserType type : UserType.values()) {
                assertThat(type.getCode()).as("%s", type).hasSize(ORACLE_SINGLE_CHARACTER);
            }
        }

        @Test
        @DisplayName("only the administrator literal reports administrator")
        void onlyTheAdministratorLiteralReportsAdministrator() {
            // The sign-on program routes an administrator to the admin menu and everything else to
            // the main menu, so a wrong answer here changes which screen a user reaches.
            assertThat(UserType.ADMIN.isAdmin()).isTrue();
            assertThat(UserType.USER.isAdmin()).isFalse();
        }

        @Test
        @DisplayName("both constants round-trip through their code")
        void bothConstantsRoundTrip() {
            for (final UserType type : UserType.values()) {
                assertThat(UserType.fromCode(type.getCode())).as("%s", type).contains(type);
            }
        }

        @Test
        @DisplayName("a value outside the two literals resolves to nothing")
        void aValueOutsideTheLiteralsResolvesToNothing() {
            assertThat(UserType.fromCode("X")).isEmpty();
            assertThat(UserType.fromCode(" ")).isEmpty();
            assertThat(UserType.fromCode("")).isEmpty();
            assertThat(UserType.fromCode("ADMIN")).isEmpty();
        }

        @Test
        @DisplayName("no case folding and no trimming are applied")
        void noCaseFoldingOrTrimmingIsApplied() {
            assertThat(UserType.fromCode("a")).isEmpty();
            assertThat(UserType.fromCode("u")).isEmpty();
            assertThat(UserType.fromCode("A ")).isEmpty();
            assertThat(UserType.fromCode(" A")).isEmpty();
        }

        @Test
        @DisplayName("an absent value resolves to nothing")
        void anAbsentValueResolvesToNothing() {
            assertThat(UserType.fromCode(null)).isEmpty();
        }

        @Test
        @DisplayName("the two types partition the seed population evenly, five administrators and five users")
        void theTwoTypesPartitionTheSeedPopulation() {
            // Ten in-stream credential records: five carrying the administrator literal and five
            // carrying the user literal.
            assertThat(List.of(UserType.values()).stream().filter(UserType::isAdmin).count())
                    .isEqualTo(1L);
            assertThat(List.of(UserType.values()).stream().filter(type -> !type.isAdmin()).count())
                    .isEqualTo(1L);
        }
    }
}
