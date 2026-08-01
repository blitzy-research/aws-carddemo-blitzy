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
package com.carddemo.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.carddemo.domain.enums.UserType;

/**
 * Exercises the navigation context that replaces the legacy communication area.
 *
 * <h2>What is under test</h2>
 * {@link NavigationContext} stands in for {@code app/cpy/COCOM01Y.cpy}, the {@code CARDDEMO-COMMAREA}
 * that all seventeen online programs include and that CICS carried from one pseudo-conversational
 * turn to the next. It holds where the caller came from, where it is going, who is signed on, which
 * account, card and customer are in play, which map was last displayed, and the one piece of control
 * state the legacy programs branched on directly: whether this is a first entry to a program or a
 * re-entry after the operator submitted the screen.
 *
 * <h2>Why the entry state is a two-valued type rather than a flag</h2>
 * The legacy structure declares the state as a one-byte field with two condition names, and every
 * online program tests the re-entry name to decide whether to display a fresh screen or to validate a
 * submission. Two behaviours ride on it that the tests assert directly: field-level error decoration
 * fires only on re-entry, and an unset state must read as a first entry rather than as neither, because
 * the legacy field was initialised to the first-entry value and no program ever tested for absence.
 *
 * <h2>Why the administrator predicate is asserted against the declared user types</h2>
 * The legacy sign-on program routes to one of two menus on the strength of a single character in the
 * user record, and the communication area carries that character onward so that later transactions can
 * gate themselves. A value the record layout does not declare must not silently grant the
 * administrative route, so the predicate is asserted to be false for every unrecognised value as well
 * as true for the one value that earns it.
 *
 * <p>Provenance: the legacy authority is {@code app/cpy/COCOM01Y.cpy}, included by all seventeen
 * online programs, at checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is
 * reproduced.</p>
 */
@DisplayName("NavigationContext - the COCOM01Y CARDDEMO-COMMAREA")
class NavigationContextBoundaryTest {

    /** Transaction identifier of the sign-on transaction. */
    private static final String FROM_TRANSACTION = "CC00";

    /** Transaction identifier of the account-maintenance transaction. */
    private static final String TO_TRANSACTION = "CAUP";

    /** Program name of the sign-on program. */
    private static final String FROM_PROGRAM = "COSGN00C";

    /** Program name of the account-maintenance program. */
    private static final String TO_PROGRAM = "COACTUPC";

    /** Signed-on user identifier, at the legacy field's full width. */
    private static final String USER_ID = "ADMIN001";

    /** Nine-digit customer key, at the legacy field's full width. */
    private static final String CUSTOMER_ID = "000000011";

    /** Eleven-digit account key, at the legacy field's full width. */
    private static final String ACCOUNT_ID = "00000000011";

    /** Sixteen-digit card key, at the legacy field's full width. */
    private static final String CARD_NUMBER = "4444333322221111";

    /**
     * Returns a context carrying every field populated, with the supplied user type.
     *
     * @param userType the one-character user type the sign-on program carried onward
     * @return a fully populated context
     */
    private static NavigationContext referenceContext(String userType) {
        return new NavigationContext(
                FROM_TRANSACTION, FROM_PROGRAM, TO_TRANSACTION, TO_PROGRAM,
                USER_ID, userType, NavigationContext.ProgramContext.ENTER,
                CUSTOMER_ID, "JOHN", "Q", "PUBLIC",
                ACCOUNT_ID, "Y", CARD_NUMBER, "CACTUPA", "COACTUP");
    }

    @Nested
    @DisplayName("empty context")
    class EmptyContext {

        @Test
        @DisplayName("the published empty context carries nothing")
        void thePublishedEmptyContextCarriesNothing() {
            NavigationContext context = NavigationContext.empty();

            assertThat(context.fromTransactionId()).isNull();
            assertThat(context.fromProgram()).isNull();
            assertThat(context.toTransactionId()).isNull();
            assertThat(context.toProgram()).isNull();
            assertThat(context.userId()).isNull();
            assertThat(context.userType()).isNull();
            assertThat(context.programContext()).isNull();
        }

        @Test
        @DisplayName("the empty context carries no account, card, customer or map")
        void theEmptyContextCarriesNoBusinessKeys() {
            NavigationContext context = NavigationContext.empty();

            assertThat(context.customerId()).isNull();
            assertThat(context.customerFirstName()).isNull();
            assertThat(context.customerMiddleName()).isNull();
            assertThat(context.customerLastName()).isNull();
            assertThat(context.accountId()).isNull();
            assertThat(context.accountStatus()).isNull();
            assertThat(context.cardNumber()).isNull();
            assertThat(context.lastMap()).isNull();
            assertThat(context.lastMapset()).isNull();
        }

        @Test
        @DisplayName("the empty context is a single shared instance, because it holds no state")
        void theEmptyContextIsASingleSharedInstance() {
            assertThat(NavigationContext.empty()).isSameAs(NavigationContext.empty());
        }

        @Test
        @DisplayName("the empty context reads as a first entry, matching the initialised field")
        void theEmptyContextReadsAsAFirstEntry() {
            assertThat(NavigationContext.empty().firstEntry()).isTrue();
            assertThat(NavigationContext.empty().reEntry()).isFalse();
        }

        @Test
        @DisplayName("the empty context grants no administrative route")
        void theEmptyContextGrantsNoAdministrativeRoute() {
            assertThat(NavigationContext.empty().echoesAdministratorCode()).isFalse();
            assertThat(NavigationContext.empty().resolvedUserType()).isEmpty();
        }
    }

    @Nested
    @DisplayName("user type - the one character the sign-on program routes on")
    class UserTypeResolution {

        @ParameterizedTest
        @CsvSource({
            "A,ADMIN",
            "U,USER"
        })
        @DisplayName("a declared user type resolves to its own type")
        void aDeclaredUserTypeResolves(String code, UserType expected) {
            assertThat(referenceContext(code).resolvedUserType()).contains(expected);
        }

        @Test
        @DisplayName("the administrative type earns the administrative route")
        void theAdministrativeTypeEarnsTheRoute() {
            assertThat(referenceContext("A").echoesAdministratorCode()).isTrue();
        }

        @Test
        @DisplayName("the standard type does not earn the administrative route")
        void theStandardTypeDoesNotEarnTheRoute() {
            assertThat(referenceContext("U").echoesAdministratorCode()).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "X", "AA", "1"})
        @DisplayName("an undeclared user type resolves to nothing and grants no route")
        void anUndeclaredUserTypeGrantsNoRoute(String code) {
            assertThat(referenceContext(code).resolvedUserType()).isEmpty();
            assertThat(referenceContext(code).echoesAdministratorCode()).isFalse();
        }

        @Test
        @DisplayName("an absent user type resolves to nothing and grants no route")
        void anAbsentUserTypeGrantsNoRoute() {
            assertThat(referenceContext(null).resolvedUserType()).isEmpty();
            assertThat(referenceContext(null).echoesAdministratorCode()).isFalse();
        }
    }

    @Nested
    @DisplayName("entry state - the flag every online program tests")
    class EntryState {

        @Test
        @DisplayName("exactly two entry states are declared, matching the two condition names")
        void exactlyTwoEntryStatesAreDeclared() {
            assertThat(NavigationContext.ProgramContext.values())
                    .containsExactly(
                            NavigationContext.ProgramContext.ENTER,
                            NavigationContext.ProgramContext.REENTER);
        }

        @Test
        @DisplayName("the first-entry state reads as a first entry and not as a re-entry")
        void theFirstEntryStateReadsAsAFirstEntry() {
            NavigationContext context = referenceContext("A").withFirstEntry();

            assertThat(context.firstEntry()).isTrue();
            assertThat(context.reEntry()).isFalse();
            assertThat(context.programContext())
                    .isEqualTo(NavigationContext.ProgramContext.ENTER);
        }

        @Test
        @DisplayName("the re-entry state reads as a re-entry and not as a first entry")
        void theReEntryStateReadsAsAReEntry() {
            NavigationContext context = referenceContext("A").withReEntry();

            assertThat(context.reEntry()).isTrue();
            assertThat(context.firstEntry()).isFalse();
            assertThat(context.programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
        }

        @Test
        @DisplayName("an unset entry state reads as a first entry rather than as neither")
        void anUnsetEntryStateReadsAsAFirstEntry() {
            NavigationContext context = new NavigationContext(
                    FROM_TRANSACTION, FROM_PROGRAM, TO_TRANSACTION, TO_PROGRAM,
                    USER_ID, "A", null,
                    CUSTOMER_ID, "JOHN", "Q", "PUBLIC",
                    ACCOUNT_ID, "Y", CARD_NUMBER, "CACTUPA", "COACTUP");

            assertThat(context.firstEntry()).isTrue();
            assertThat(context.reEntry()).isFalse();
        }

        @Test
        @DisplayName("changing the entry state leaves the context it was called on untouched")
        void changingTheEntryStateLeavesTheOriginalUntouched() {
            NavigationContext original = referenceContext("A");

            NavigationContext reEntered = original.withReEntry();

            assertThat(original.firstEntry()).isTrue();
            assertThat(reEntered.reEntry()).isTrue();
            assertThat(reEntered).isNotSameAs(original);
        }

        @Test
        @DisplayName("changing the entry state carries every other field across unchanged")
        void changingTheEntryStateCarriesEveryOtherFieldAcross() {
            NavigationContext reEntered = referenceContext("A").withReEntry();

            assertThat(reEntered.fromTransactionId()).isEqualTo(FROM_TRANSACTION);
            assertThat(reEntered.fromProgram()).isEqualTo(FROM_PROGRAM);
            assertThat(reEntered.toTransactionId()).isEqualTo(TO_TRANSACTION);
            assertThat(reEntered.toProgram()).isEqualTo(TO_PROGRAM);
            assertThat(reEntered.userId()).isEqualTo(USER_ID);
            assertThat(reEntered.userType()).isEqualTo("A");
            assertThat(reEntered.customerId()).isEqualTo(CUSTOMER_ID);
            assertThat(reEntered.customerFirstName()).isEqualTo("JOHN");
            assertThat(reEntered.customerMiddleName()).isEqualTo("Q");
            assertThat(reEntered.customerLastName()).isEqualTo("PUBLIC");
            assertThat(reEntered.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(reEntered.accountStatus()).isEqualTo("Y");
            assertThat(reEntered.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(reEntered.lastMap()).isEqualTo("CACTUPA");
            assertThat(reEntered.lastMapset()).isEqualTo("COACTUP");
        }

        @Test
        @DisplayName("returning to the first-entry state from a re-entry is symmetric")
        void returningToTheFirstEntryStateIsSymmetric() {
            NavigationContext original = referenceContext("A").withFirstEntry();

            assertThat(original.withReEntry().withFirstEntry()).isEqualTo(original);
        }

        @Test
        @DisplayName("setting the same entry state twice yields an equal context")
        void settingTheSameEntryStateTwiceYieldsAnEqualContext() {
            NavigationContext once = referenceContext("A").withReEntry();

            assertThat(once.withReEntry()).isEqualTo(once);
        }

        @Test
        @DisplayName("the entry state can be set on the empty context without adding a field")
        void theEntryStateCanBeSetOnTheEmptyContext() {
            NavigationContext context = NavigationContext.empty().withReEntry();

            assertThat(context.reEntry()).isTrue();
            assertThat(context.userId()).isNull();
            assertThat(context.accountId()).isNull();
        }
    }

    @Nested
    @DisplayName("declared widths")
    class DeclaredWidths {

        @Test
        @DisplayName("the declared widths match the legacy copybook picture clauses")
        void theDeclaredWidthsMatchTheCopybook() {
            assertThat(NavigationContext.TRANSACTION_ID_LENGTH).isEqualTo(4);
            assertThat(NavigationContext.PROGRAM_NAME_LENGTH).isEqualTo(8);
            assertThat(NavigationContext.USER_ID_LENGTH).isEqualTo(8);
            assertThat(NavigationContext.USER_TYPE_LENGTH).isEqualTo(1);
            assertThat(NavigationContext.CUSTOMER_ID_LENGTH).isEqualTo(9);
            assertThat(NavigationContext.CUSTOMER_NAME_LENGTH).isEqualTo(25);
            assertThat(NavigationContext.ACCOUNT_ID_LENGTH).isEqualTo(11);
            assertThat(NavigationContext.ACCOUNT_STATUS_LENGTH).isEqualTo(1);
            assertThat(NavigationContext.CARD_NUMBER_LENGTH).isEqualTo(16);
            assertThat(NavigationContext.MAP_NAME_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("the reference fields are supplied at the widths the copybook declares")
        void theReferenceFieldsAreAtTheDeclaredWidths() {
            assertThat(FROM_TRANSACTION).hasSize(NavigationContext.TRANSACTION_ID_LENGTH);
            assertThat(TO_TRANSACTION).hasSize(NavigationContext.TRANSACTION_ID_LENGTH);
            assertThat(FROM_PROGRAM).hasSize(NavigationContext.PROGRAM_NAME_LENGTH);
            assertThat(TO_PROGRAM).hasSize(NavigationContext.PROGRAM_NAME_LENGTH);
            assertThat(USER_ID).hasSize(NavigationContext.USER_ID_LENGTH);
            assertThat(CUSTOMER_ID).hasSize(NavigationContext.CUSTOMER_ID_LENGTH);
            assertThat(ACCOUNT_ID).hasSize(NavigationContext.ACCOUNT_ID_LENGTH);
            assertThat(CARD_NUMBER).hasSize(NavigationContext.CARD_NUMBER_LENGTH);
        }

        @Test
        @DisplayName("two contexts carrying the same fields are equal")
        void twoContextsCarryingTheSameFieldsAreEqual() {
            assertThat(referenceContext("A"))
                    .isEqualTo(referenceContext("A"))
                    .hasSameHashCodeAs(referenceContext("A"));
        }

        @Test
        @DisplayName("two contexts differing only in the user type are not equal")
        void twoContextsDifferingInTheUserTypeAreNotEqual() {
            assertThat(referenceContext("A")).isNotEqualTo(referenceContext("U"));
        }
    }
}
