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

import java.util.List;

import com.carddemo.domain.enums.UserType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link NavigationContext}, the replacement for the state the legacy programs carried between
 * pseudo-conversation turns.
 *
 * <p><strong>What it replaces.</strong> {@code app/cpy/COCOM01Y.cpy} declares a communication area that
 * all seventeen online programs included textually and passed to one another on every transfer of control.
 * Its sixteen fields are reproduced here as sixteen record components at the same widths, so a client
 * echoing this object back carries exactly the state the terminal session carried.
 *
 * <p><strong>Two fields decide behaviour rather than describe it.</strong> The user-type byte decides
 * whether sign-on routes to the administrative menu or the main one, and the program-context digit decides
 * whether a screen is being shown for the first time or re-shown after a failed submission. The second of
 * those is the gate on field-level error decoration: the legacy macro that reddens a field and writes a
 * marker fires only on re-entry, so a context that reported first entry when it should report re-entry
 * would silently suppress every field error. Both are asserted here, including their behaviour when the
 * field is absent.
 *
 * <p><strong>Why the absent program context means first entry.</strong> The legacy field is a single digit
 * whose condition names give zero the meaning "entering" and one the meaning "re-entering". A freshly
 * initialised digit field therefore reads as entering, and an absent context has to behave the same way or
 * a first request would be treated as a re-submission. The suite asserts the two predicates are exact
 * complements and that an absent context reports first entry.
 *
 * <p><strong>Deliberately not asserted.</strong> Nothing here validates the declared size constraints,
 * because bean validation is exercised where a validator is actually run; this suite establishes the widths
 * agree with the copybook and that the behavioural methods are correct.
 */
@DisplayName("NavigationContext — the carried-over communication area")
class NavigationContextTest {

    /** The sixteen copybook widths in declaration order; the program-context digit counts as one. */
    private static final List<Integer> COPYBOOK_WIDTHS =
            List.of(4, 8, 4, 8, 8, 1, 1, 9, 25, 25, 25, 11, 1, 16, 7, 7);

    /** Summed width of the whole communication area. */
    private static final int COMMAREA_WIDTH = 160;

    /** The legacy condition-name value meaning the program is being entered. */
    private static final int LEGACY_ENTER_VALUE = 0;

    /** The legacy condition-name value meaning the program is being re-entered. */
    private static final int LEGACY_REENTER_VALUE = 1;

    /**
     * Builds a fully populated context, so a transformation can be shown to preserve every component it
     * is not meant to change.
     *
     * @param userType      the one-byte user-type code
     * @param context       the program context
     * @return a context with every component populated
     */
    private static NavigationContext populated(final String userType,
            final NavigationContext.ProgramContext context) {
        return new NavigationContext(
                "CC00",
                "COSGN00C",
                "CAUP",
                "COACTUPC",
                "ADMIN001",
                userType,
                context,
                "000000011",
                "MARGARET",
                "ANN",
                "GOLD",
                "00000000011",
                "Y",
                "4111111111111111",
                "CACTUPA",
                "COACTUP");
    }

    // =================================================================================================
    // COPYBOOK GEOMETRY
    // =================================================================================================

    /**
     * Verifies the declared widths against the communication area they reproduce.
     */
    @Nested
    @DisplayName("copybook geometry")
    class CopybookGeometry {

        @Test
        @DisplayName("the sixteen declared widths sum to the communication area's own width")
        void theWidthsSumToTheCommareaWidth() {
            assertThat(COPYBOOK_WIDTHS).hasSize(16);
            assertThat(COPYBOOK_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(COMMAREA_WIDTH);
        }

        @Test
        @DisplayName("every published width constant matches its copybook field")
        void everyPublishedWidthMatchesItsCopybookField() {
            assertThat(NavigationContext.TRANSACTION_ID_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(0));
            assertThat(NavigationContext.PROGRAM_NAME_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(1));
            assertThat(NavigationContext.USER_ID_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(4));
            assertThat(NavigationContext.USER_TYPE_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(5));
            assertThat(NavigationContext.CUSTOMER_ID_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(7));
            assertThat(NavigationContext.CUSTOMER_NAME_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(8));
            assertThat(NavigationContext.ACCOUNT_ID_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(11));
            assertThat(NavigationContext.ACCOUNT_STATUS_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(12));
            assertThat(NavigationContext.CARD_NUMBER_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(13));
            assertThat(NavigationContext.MAP_NAME_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(14));
        }

        @Test
        @DisplayName("the two transaction identifiers share a width and the two program names share "
                + "another, because each pair reproduces one copybook field twice")
        void thePairedFieldsShareTheirWidths() {
            assertThat(COPYBOOK_WIDTHS.get(0)).isEqualTo(COPYBOOK_WIDTHS.get(2));
            assertThat(COPYBOOK_WIDTHS.get(1)).isEqualTo(COPYBOOK_WIDTHS.get(3));
            assertThat(COPYBOOK_WIDTHS.get(14)).isEqualTo(COPYBOOK_WIDTHS.get(15));
            assertThat(NavigationContext.MAP_NAME_LENGTH)
                    .as("a map and a mapset name are both seven characters")
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("the three customer names share one width, so a forename cannot be distinguished "
                + "from a surname by length and must be distinguished by position")
        void theThreeCustomerNamesShareOneWidth() {
            assertThat(COPYBOOK_WIDTHS.get(8))
                    .isEqualTo(COPYBOOK_WIDTHS.get(9))
                    .isEqualTo(COPYBOOK_WIDTHS.get(10))
                    .isEqualTo(NavigationContext.CUSTOMER_NAME_LENGTH);
        }

        @Test
        @DisplayName("the user-type and account-status fields are one byte each, so each carries a single "
                + "decision character")
        void theSingleByteFieldsAreOneByteEach() {
            assertThat(NavigationContext.USER_TYPE_LENGTH).isOne();
            assertThat(NavigationContext.ACCOUNT_STATUS_LENGTH).isOne();
        }
    }

    // =================================================================================================
    // THE ABSENT CONTEXT
    // =================================================================================================

    /**
     * Verifies the shared empty context.
     */
    @Nested
    @DisplayName("the absent context")
    class AbsentContext {

        @Test
        @DisplayName("the empty context carries no component at all")
        void theEmptyContextCarriesNoComponent() {
            final NavigationContext empty = NavigationContext.empty();

            assertThat(empty.fromTransactionId()).isNull();
            assertThat(empty.fromProgram()).isNull();
            assertThat(empty.toTransactionId()).isNull();
            assertThat(empty.toProgram()).isNull();
            assertThat(empty.userId()).isNull();
            assertThat(empty.userType()).isNull();
            assertThat(empty.programContext()).isNull();
            assertThat(empty.customerId()).isNull();
            assertThat(empty.customerFirstName()).isNull();
            assertThat(empty.customerMiddleName()).isNull();
            assertThat(empty.customerLastName()).isNull();
            assertThat(empty.accountId()).isNull();
            assertThat(empty.accountStatus()).isNull();
            assertThat(empty.cardNumber()).isNull();
            assertThat(empty.lastMap()).isNull();
            assertThat(empty.lastMapset()).isNull();
        }

        @Test
        @DisplayName("the empty context is shared rather than rebuilt, which is safe because the record "
                + "is immutable")
        void theEmptyContextIsShared() {
            assertThat(NavigationContext.empty()).isSameAs(NavigationContext.empty());
        }

        @Test
        @DisplayName("the empty context resolves no user type and is not administrative, so an absent "
                + "context cannot be mistaken for an authorised one")
        void theEmptyContextIsNotAdministrative() {
            assertThat(NavigationContext.empty().resolvedUserType()).isEmpty();
            assertThat(NavigationContext.empty().administrator()).isFalse();
        }

        @Test
        @DisplayName("the empty context reports first entry, matching a freshly initialised digit field")
        void theEmptyContextReportsFirstEntry() {
            assertThat(NavigationContext.empty().firstEntry()).isTrue();
            assertThat(NavigationContext.empty().reEntry()).isFalse();
        }
    }

    // =================================================================================================
    // THE USER-TYPE DECISION
    // =================================================================================================

    /**
     * Verifies the one byte that decides which menu a signed-on user reaches.
     */
    @Nested
    @DisplayName("the user-type decision")
    class UserTypeDecision {

        @Test
        @DisplayName("the administrative code resolves administrative")
        void theAdministrativeCodeResolvesAdministrative() {
            final NavigationContext context =
                    populated("A", NavigationContext.ProgramContext.ENTER);

            assertThat(context.resolvedUserType()).contains(UserType.ADMIN);
            assertThat(context.administrator()).isTrue();
        }

        @Test
        @DisplayName("the ordinary code resolves ordinary and is not administrative")
        void theOrdinaryCodeResolvesOrdinary() {
            final NavigationContext context =
                    populated("U", NavigationContext.ProgramContext.ENTER);

            assertThat(context.resolvedUserType()).contains(UserType.USER);
            assertThat(context.administrator()).isFalse();
        }

        @Test
        @DisplayName("a code outside the vocabulary resolves nothing and is not administrative, so an "
                + "unexpected byte cannot escalate")
        void anUnknownCodeIsNotAdministrative() {
            for (final String code : List.of("X", "a", " ", "", "AA", "1")) {
                final NavigationContext context =
                        populated(code, NavigationContext.ProgramContext.ENTER);

                assertThat(context.resolvedUserType()).as("code %s", code).isEmpty();
                assertThat(context.administrator()).as("code %s", code).isFalse();
            }
        }

        @Test
        @DisplayName("an absent code resolves nothing and is not administrative")
        void anAbsentCodeIsNotAdministrative() {
            final NavigationContext context =
                    populated(null, NavigationContext.ProgramContext.ENTER);

            assertThat(context.resolvedUserType()).isEmpty();
            assertThat(context.administrator()).isFalse();
        }

        @Test
        @DisplayName("the two codes are the only administrative outcome, so exactly one of the two "
                + "declared roles is privileged")
        void exactlyOneDeclaredRoleIsPrivileged() {
            int privileged = 0;
            for (final UserType role : UserType.values()) {
                if (populated(role.getCode(), NavigationContext.ProgramContext.ENTER).administrator()) {
                    privileged++;
                }
            }

            assertThat(privileged).isOne();
        }
    }

    // =================================================================================================
    // THE ENTRY DECISION
    // =================================================================================================

    /**
     * Verifies the digit that gates field-level error decoration.
     */
    @Nested
    @DisplayName("the entry decision")
    class EntryDecision {

        @Test
        @DisplayName("the two predicates are exact complements for every possible context value")
        void thePredicatesAreExactComplements() {
            final List<NavigationContext> contexts = List.of(
                    populated("A", NavigationContext.ProgramContext.ENTER),
                    populated("A", NavigationContext.ProgramContext.REENTER),
                    populated("A", null));

            for (final NavigationContext context : contexts) {
                assertThat(context.firstEntry())
                        .as("context %s", context.programContext())
                        .isNotEqualTo(context.reEntry());
            }
        }

        @Test
        @DisplayName("an entering context reports first entry and a re-entering one reports re-entry")
        void eachContextValueReportsItsOwnEntryState() {
            assertThat(populated("A", NavigationContext.ProgramContext.ENTER).firstEntry()).isTrue();
            assertThat(populated("A", NavigationContext.ProgramContext.ENTER).reEntry()).isFalse();
            assertThat(populated("A", NavigationContext.ProgramContext.REENTER).reEntry()).isTrue();
            assertThat(populated("A", NavigationContext.ProgramContext.REENTER).firstEntry()).isFalse();
        }

        @Test
        @DisplayName("an absent context reports first entry rather than re-entry, so a first request is "
                + "never treated as a re-submission")
        void anAbsentContextReportsFirstEntry() {
            assertThat(populated("A", null).firstEntry()).isTrue();
            assertThat(populated("A", null).reEntry()).isFalse();
        }

        @Test
        @DisplayName("the two context values keep the legacy condition-name numbering, entering before "
                + "re-entering")
        void theContextValuesKeepTheLegacyNumbering() {
            assertThat(NavigationContext.ProgramContext.values()).hasSize(2);
            assertThat(NavigationContext.ProgramContext.ENTER.ordinal())
                    .isEqualTo(LEGACY_ENTER_VALUE);
            assertThat(NavigationContext.ProgramContext.REENTER.ordinal())
                    .isEqualTo(LEGACY_REENTER_VALUE);
        }
    }

    // =================================================================================================
    // ENTRY-STATE TRANSITIONS
    // =================================================================================================

    /**
     * Verifies that changing the entry state changes nothing else.
     */
    @Nested
    @DisplayName("entry-state transitions")
    class EntryStateTransitions {

        @Test
        @DisplayName("marking re-entry changes only the entry state")
        void markingReEntryChangesOnlyTheEntryState() {
            final NavigationContext before =
                    populated("A", NavigationContext.ProgramContext.ENTER);
            final NavigationContext after = before.withReEntry();

            assertThat(after.reEntry()).isTrue();
            assertThat(after.programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
            assertThat(after.withFirstEntry())
                    .as("every other component is carried through unchanged")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("marking first entry changes only the entry state")
        void markingFirstEntryChangesOnlyTheEntryState() {
            final NavigationContext before =
                    populated("A", NavigationContext.ProgramContext.REENTER);
            final NavigationContext after = before.withFirstEntry();

            assertThat(after.firstEntry()).isTrue();
            assertThat(after.programContext()).isEqualTo(NavigationContext.ProgramContext.ENTER);
            assertThat(after.withReEntry()).isEqualTo(before);
        }

        @Test
        @DisplayName("each transition preserves all fifteen other components individually")
        void eachTransitionPreservesTheOtherComponents() {
            final NavigationContext before =
                    populated("A", NavigationContext.ProgramContext.ENTER);
            final NavigationContext after = before.withReEntry();

            assertThat(after.fromTransactionId()).isEqualTo(before.fromTransactionId());
            assertThat(after.fromProgram()).isEqualTo(before.fromProgram());
            assertThat(after.toTransactionId()).isEqualTo(before.toTransactionId());
            assertThat(after.toProgram()).isEqualTo(before.toProgram());
            assertThat(after.userId()).isEqualTo(before.userId());
            assertThat(after.userType()).isEqualTo(before.userType());
            assertThat(after.customerId()).isEqualTo(before.customerId());
            assertThat(after.customerFirstName()).isEqualTo(before.customerFirstName());
            assertThat(after.customerMiddleName()).isEqualTo(before.customerMiddleName());
            assertThat(after.customerLastName()).isEqualTo(before.customerLastName());
            assertThat(after.accountId()).isEqualTo(before.accountId());
            assertThat(after.accountStatus()).isEqualTo(before.accountStatus());
            assertThat(after.cardNumber()).isEqualTo(before.cardNumber());
            assertThat(after.lastMap()).isEqualTo(before.lastMap());
            assertThat(after.lastMapset()).isEqualTo(before.lastMapset());
        }

        @Test
        @DisplayName("a transition leaves the original untouched, so a context can be safely shared")
        void aTransitionLeavesTheOriginalUntouched() {
            final NavigationContext before =
                    populated("A", NavigationContext.ProgramContext.ENTER);

            final NavigationContext ignoredResult = before.withReEntry();

            assertThat(ignoredResult).isNotSameAs(before);
            assertThat(before.programContext()).isEqualTo(NavigationContext.ProgramContext.ENTER);
            assertThat(before.firstEntry()).isTrue();
        }

        @Test
        @DisplayName("marking an already-marked state is idempotent in value")
        void markingAnAlreadyMarkedStateIsIdempotent() {
            final NavigationContext reEntering =
                    populated("A", NavigationContext.ProgramContext.REENTER);
            final NavigationContext entering =
                    populated("A", NavigationContext.ProgramContext.ENTER);

            assertThat(reEntering.withReEntry()).isEqualTo(reEntering);
            assertThat(entering.withFirstEntry()).isEqualTo(entering);
        }

        @Test
        @DisplayName("a transition applied to the absent context populates only the entry state")
        void aTransitionOnTheAbsentContextPopulatesOnlyTheEntryState() {
            final NavigationContext marked = NavigationContext.empty().withReEntry();

            assertThat(marked.reEntry()).isTrue();
            assertThat(marked.userId()).isNull();
            assertThat(marked.accountId()).isNull();
            assertThat(marked).isNotEqualTo(NavigationContext.empty());
            assertThat(marked.withFirstEntry().programContext())
                    .isEqualTo(NavigationContext.ProgramContext.ENTER);
        }
    }

    // =================================================================================================
    // VALUE SEMANTICS
    // =================================================================================================

    /**
     * Verifies that the context behaves as a value.
     */
    @Nested
    @DisplayName("value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("two contexts with the same components are equal")
        void twoContextsWithTheSameComponentsAreEqual() {
            assertThat(populated("A", NavigationContext.ProgramContext.ENTER))
                    .isEqualTo(populated("A", NavigationContext.ProgramContext.ENTER))
                    .hasSameHashCodeAs(populated("A", NavigationContext.ProgramContext.ENTER));
        }

        @Test
        @DisplayName("a difference in the user-type byte alone makes two contexts unequal")
        void aDifferenceInTheUserTypeByteMakesContextsUnequal() {
            assertThat(populated("A", NavigationContext.ProgramContext.ENTER))
                    .isNotEqualTo(populated("U", NavigationContext.ProgramContext.ENTER));
        }

        @Test
        @DisplayName("a difference in the entry state alone makes two contexts unequal")
        void aDifferenceInTheEntryStateMakesContextsUnequal() {
            assertThat(populated("A", NavigationContext.ProgramContext.ENTER))
                    .isNotEqualTo(populated("A", NavigationContext.ProgramContext.REENTER));
        }

        @Test
        @DisplayName("the rendered form names the record and its populated components")
        void theRenderedFormNamesTheRecordAndItsComponents() {
            assertThat(populated("A", NavigationContext.ProgramContext.ENTER).toString())
                    .startsWith("NavigationContext[")
                    .endsWith("]")
                    .contains("ADMIN001")
                    .contains("ENTER");
        }
    }
}
