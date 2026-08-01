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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Exercises the accumulator that replaces the thirty-nine macro expansions of the legacy screen.
 *
 * <h2>What is under test</h2>
 * {@link FieldErrorDecorator} stands in for {@code app/cpy/CSSETATY.cpy}, a parameterised procedure
 * macro with three substitution tokens that account maintenance expands thirty-nine times against
 * the same map. Each expansion changed one field's display attribute when that field's validation
 * flag was unsatisfied, and additionally wrote a marker character when the flag was specifically
 * blank rather than merely unsatisfied. This record collapses all thirty-nine expansions into one
 * method invoked thirty-nine times, and preserves the two-state distinction that the marker encoded.
 *
 * <h2>Why the two flag states map to two different field states</h2>
 * The macro's inner structure is what makes the distinction observable: it changed the colour
 * whenever the flag was not satisfied, but wrote the marker only in the blank case. A caller of the
 * REST contract must be able to tell a field that was never filled in from a field that was filled
 * in and rejected, because the legacy screen told a terminal operator exactly that. The mapping is
 * therefore asserted state by state rather than as a single boolean.
 *
 * <h2>Why accumulation is asserted to be non-destructive</h2>
 * The legacy expansions ran in sequence over a shared screen buffer, each adding its own decoration
 * without disturbing the ones before it, and a validation pass could mark many fields. This record is
 * immutable, so each mark returns a new accumulator; the tests assert both that the new one carries
 * every earlier entry in order and that the earlier one is left untouched, because a mutable
 * shortcut would pass an order assertion while quietly sharing state between two validation passes.
 *
 * <p>Provenance: the legacy authority is {@code app/cpy/CSSETATY.cpy}, expanded at
 * {@code app/cbl/COACTUPC.cbl} lines 3208 to 3432, at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced.</p>
 */
@DisplayName("FieldErrorDecorator - the CSSETATY macro collapsed into one accumulator")
class FieldErrorDecoratorBoundaryTest {

    /** Java-side name of a validated screen field. */
    private static final String FIELD = "acctStatus";

    /** Screen field identifier of the same field, from the account-maintenance mapset. */
    private static final String SCREEN_FIELD = "ACSTTUS";

    /** Number of times account maintenance expands the legacy macro. */
    private static final int EXPANSION_COUNT = 39;

    @Nested
    @DisplayName("empty accumulator")
    class EmptyAccumulator {

        @Test
        @DisplayName("the published empty accumulator carries nothing")
        void thePublishedEmptyAccumulatorCarriesNothing() {
            FieldErrorDecorator decorator = FieldErrorDecorator.none();

            assertThat(decorator.isEmpty()).isTrue();
            assertThat(decorator.fieldErrors()).isEmpty();
        }

        @Test
        @DisplayName("an accumulator built from no entries is equal to the published empty one")
        void anAccumulatorBuiltFromNoEntriesEqualsTheEmptyOne() {
            assertThat(new FieldErrorDecorator(List.of()))
                    .isEqualTo(FieldErrorDecorator.none());
        }

        @Test
        @DisplayName("an absent entry list is treated as no entries, not as a defect")
        void anAbsentEntryListIsTreatedAsNoEntries() {
            FieldErrorDecorator decorator = new FieldErrorDecorator(null);

            assertThat(decorator.isEmpty()).isTrue();
            assertThat(decorator.fieldErrors()).isEmpty();
        }
    }

    @Nested
    @DisplayName("marking a field")
    class MarkingAField {

        @ParameterizedTest
        @CsvSource({
            "BLANK,MISSING",
            "NOT_OK,INVALID"
        })
        @DisplayName("a flag state maps to the field state the legacy marker distinguished")
        void aFlagStateMapsToItsFieldState(FieldErrorDecorator.FlagState flagState,
                ErrorResponse.FieldState expected) {
            FieldErrorDecorator decorator =
                    FieldErrorDecorator.none().mark(FIELD, SCREEN_FIELD, flagState);

            assertThat(decorator.fieldErrors())
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.state()).isEqualTo(expected));
        }

        @Test
        @DisplayName("a marked field carries both its Java name and its screen identifier")
        void aMarkedFieldCarriesBothNames() {
            FieldErrorDecorator decorator = FieldErrorDecorator.none()
                    .mark(FIELD, SCREEN_FIELD, FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(decorator.fieldErrors())
                    .singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.fieldName()).isEqualTo(FIELD);
                        assertThat(entry.screenFieldId()).isEqualTo(SCREEN_FIELD);
                    });
        }

        @Test
        @DisplayName("a marked field carries no message, because the macro wrote no text")
        void aMarkedFieldCarriesNoMessage() {
            FieldErrorDecorator decorator = FieldErrorDecorator.none()
                    .mark(FIELD, SCREEN_FIELD, FieldErrorDecorator.FlagState.BLANK);

            assertThat(decorator.fieldErrors())
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.message()).isNull());
        }

        @Test
        @DisplayName("marking leaves the accumulator it was called on untouched")
        void markingLeavesTheOriginalUntouched() {
            FieldErrorDecorator original = FieldErrorDecorator.none();

            FieldErrorDecorator marked =
                    original.mark(FIELD, SCREEN_FIELD, FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(original.isEmpty()).isTrue();
            assertThat(marked.isEmpty()).isFalse();
            assertThat(marked).isNotSameAs(original);
        }

        @Test
        @DisplayName("successive marks accumulate in the order the macro expansions ran")
        void successiveMarksAccumulateInOrder() {
            FieldErrorDecorator decorator = FieldErrorDecorator.none()
                    .mark("acctStatus", "ACSTTUS", FieldErrorDecorator.FlagState.BLANK)
                    .mark("creditLimit", "ACRDLIM", FieldErrorDecorator.FlagState.NOT_OK)
                    .mark("ficoScore", "ACSTFCO", FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(decorator.fieldErrors())
                    .extracting(ErrorResponse.FieldError::fieldName)
                    .containsExactly("acctStatus", "creditLimit", "ficoScore");
        }

        @Test
        @DisplayName("the same field may be marked twice, because the legacy buffer allowed it")
        void theSameFieldMayBeMarkedTwice() {
            FieldErrorDecorator decorator = FieldErrorDecorator.none()
                    .mark(FIELD, SCREEN_FIELD, FieldErrorDecorator.FlagState.BLANK)
                    .mark(FIELD, SCREEN_FIELD, FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(decorator.fieldErrors())
                    .extracting(ErrorResponse.FieldError::state)
                    .containsExactly(
                            ErrorResponse.FieldState.MISSING,
                            ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("all thirty-nine legacy expansions can accumulate on one accumulator")
        void allThirtyNineExpansionsCanAccumulate() {
            FieldErrorDecorator decorator = FieldErrorDecorator.none();
            for (int expansion = 1; expansion <= EXPANSION_COUNT; expansion++) {
                decorator = decorator.mark(
                        "field" + expansion,
                        "SCRN" + expansion,
                        FieldErrorDecorator.FlagState.NOT_OK);
            }

            assertThat(decorator.fieldErrors()).hasSize(EXPANSION_COUNT);
        }

        @Test
        @DisplayName("an absent field name is a caller defect")
        void anAbsentFieldNameIsACallerDefect() {
            FieldErrorDecorator decorator = FieldErrorDecorator.none();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> decorator.mark(
                            null, SCREEN_FIELD, FieldErrorDecorator.FlagState.NOT_OK))
                    .withMessage("field must not be null");
        }

        @Test
        @DisplayName("an absent screen identifier is a caller defect")
        void anAbsentScreenIdentifierIsACallerDefect() {
            FieldErrorDecorator decorator = FieldErrorDecorator.none();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> decorator.mark(
                            FIELD, null, FieldErrorDecorator.FlagState.NOT_OK))
                    .withMessage("bmsFieldId must not be null");
        }

        @Test
        @DisplayName("an absent flag state is a caller defect, because there is no third state")
        void anAbsentFlagStateIsACallerDefect() {
            FieldErrorDecorator decorator = FieldErrorDecorator.none();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> decorator.mark(FIELD, SCREEN_FIELD, null))
                    .withMessage("flagState must not be null");
        }
    }

    @Nested
    @DisplayName("immutability")
    class Immutability {

        @Test
        @DisplayName("the carried entries cannot be modified by a caller")
        void theCarriedEntriesCannotBeModified() {
            FieldErrorDecorator decorator = FieldErrorDecorator.none()
                    .mark(FIELD, SCREEN_FIELD, FieldErrorDecorator.FlagState.NOT_OK);
            List<ErrorResponse.FieldError> entries = decorator.fieldErrors();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(entries::clear);
        }

        @Test
        @DisplayName("a later change to the supplied list does not reach the accumulator")
        void aLaterChangeToTheSuppliedListDoesNotReach() {
            List<FieldErrorDecorator.MarkedField> supplied = new ArrayList<>();
            supplied.add(new FieldErrorDecorator.MarkedField(
                    FIELD, SCREEN_FIELD, FieldErrorDecorator.FlagState.BLANK));
            FieldErrorDecorator decorator = new FieldErrorDecorator(supplied);

            supplied.clear();

            assertThat(decorator.fieldErrors()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("flag states - the two states the legacy marker distinguished")
    class FlagStates {

        @Test
        @DisplayName("exactly two flag states are declared, matching the macro's two outcomes")
        void exactlyTwoFlagStatesAreDeclared() {
            assertThat(FieldErrorDecorator.FlagState.values())
                    .containsExactly(
                            FieldErrorDecorator.FlagState.BLANK,
                            FieldErrorDecorator.FlagState.NOT_OK);
        }

        @ParameterizedTest
        @EnumSource(FieldErrorDecorator.FlagState.class)
        @DisplayName("every flag state produces a marked field")
        void everyFlagStateProducesAMarkedField(FieldErrorDecorator.FlagState flagState) {
            assertThat(FieldErrorDecorator.none()
                    .mark(FIELD, SCREEN_FIELD, flagState)
                    .isEmpty()).isFalse();
        }
    }
}
