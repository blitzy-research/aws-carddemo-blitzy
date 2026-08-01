/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.carddemo.api.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Verifies the field-error decorator that replaces the legacy attribute-setting macro.
 *
 * <p>The legacy account-update program did not have a field-error model. It had a parameterised procedural
 * macro with three substitution tokens, textually expanded 39 times against a single map, whose body did two
 * things and only two things: it recoloured a screen field when that field's validation flag was either
 * not-OK or blank, and it additionally wrote a single asterisk into the field when the flag was specifically
 * blank. Both actions were gated on the program being on a re-submission rather than a first entry. Roughly
 * 234 generated lines therefore encoded exactly one decision with two outcomes, and the migration collapses
 * them into one method invoked 39 times.</p>
 *
 * <p>What must survive that collapse is the two-outcome distinction, because it is the only thing the legacy
 * screen used to tell an operator apart a field left empty from a field filled in wrongly. A single boolean
 * "this field is in error" would lose it. The decorator therefore carries a blank flag onto a MISSING state
 * and a not-OK flag onto an INVALID state, and the flag vocabulary admits no third value - an acceptable
 * field was never decorated at all, so there is deliberately no OK constant to map.</p>
 *
 * <p>Scope: this exercises the record directly. No Spring application context is started, no HTTP request is
 * dispatched, no database, file, network or container is touched, and nothing is introspected reflectively.</p>
 *
 * <p>Expectations are derived, never echoed. The expansion-site count of 39, the two-outcome behaviour and
 * the re-submission gate are taken from the macro copybook {@code app/cpy/CSSETATY.cpy} and its expansion
 * sites in {@code app/cbl/COACTUPC.cbl} between lines 3208 and 3432; the state vocabulary is taken from the
 * attribute-byte behaviour the mapset defines. No expectation is read back out of the class under test.</p>
 *
 * <p>Provenance: legacy checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced here.</p>
 */
@DisplayName("FieldErrorDecorator - the collapsed form of 39 macro expansions")
final class FieldErrorDecoratorBaselineTest {

    /**
     * The number of times the macro was textually expanded in the account-update program. Every expansion
     * became one call to {@code mark}, so a decorator must be able to accumulate at least this many entries
     * while preserving their order.
     */
    private static final int MACRO_EXPANSION_SITES = 39;

    /** The number of flag states the macro could act upon. An acceptable field was never decorated. */
    private static final int DECORATABLE_FLAG_STATES = 2;

    /** A representative logical field name, as a request type would name it. */
    private static final String FIELD_NAME = "acctStatus";

    /** The corresponding screen field identifier from the account-update mapset. */
    private static final String SCREEN_FIELD_ID = "ACSTTUS";

    @Nested
    @DisplayName("Construction and the empty decorator")
    class ConstructionAndEmptiness {

        @Test
        @DisplayName("a null entry list becomes an empty list, so a decorator can never hold a null "
                + "collection and no caller needs a null check before iterating")
        void aNullEntryListBecomesAnEmptyList() {
            final FieldErrorDecorator decorator = new FieldErrorDecorator(null);

            assertThat(decorator.fieldErrors()).isNotNull().isEmpty();
            assertThat(decorator.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("the empty factory yields a decorator with no entries, which is the state of a first "
                + "entry before any validation has run")
        void theEmptyFactoryYieldsADecoratorWithNoEntries() {
            final FieldErrorDecorator decorator = FieldErrorDecorator.none();

            assertThat(decorator.fieldErrors()).isEmpty();
            assertThat(decorator.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("the empty factory and an explicitly empty list produce equal decorators, so the two "
                + "ways of expressing no errors cannot diverge")
        void theEmptyFactoryEqualsAnExplicitlyEmptyList() {
            assertThat(FieldErrorDecorator.none())
                    .isEqualTo(new FieldErrorDecorator(List.of()))
                    .isEqualTo(new FieldErrorDecorator(null))
                    .hasSameHashCodeAs(new FieldErrorDecorator(List.of()));
        }

        @Test
        @DisplayName("the supplied list is copied defensively, so a caller that mutates its own list "
                + "afterwards cannot change what the decorator reports")
        void theSuppliedListIsCopiedDefensively() {
            final List<ErrorResponse.FieldError> supplied = new ArrayList<>();
            supplied.add(new ErrorResponse.FieldError(
                    FIELD_NAME, SCREEN_FIELD_ID, ErrorResponse.FieldState.MISSING));

            final FieldErrorDecorator decorator = new FieldErrorDecorator(supplied);
            supplied.clear();
            supplied.add(new ErrorResponse.FieldError(
                    "somethingElse", "OTHER", ErrorResponse.FieldState.INVALID));

            assertThat(decorator.fieldErrors()).hasSize(1);
            assertThat(decorator.fieldErrors().get(0).fieldName()).isEqualTo(FIELD_NAME);
        }

        @Test
        @DisplayName("the entry list a decorator publishes cannot be modified, so no consumer can inject an "
                + "error the validation cascade never raised")
        void thePublishedEntryListCannotBeModified() {
            final FieldErrorDecorator decorator = FieldErrorDecorator.none()
                    .mark(FIELD_NAME, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.BLANK);

            assertThat(decorator.fieldErrors()).isUnmodifiable();
        }

        @Test
        @DisplayName("a list carrying a null entry is rejected outright, because a null entry would render as "
                + "a field error naming no field")
        void aListCarryingANullEntryIsRejected() {
            final List<ErrorResponse.FieldError> withNull = Arrays.asList(
                    new ErrorResponse.FieldError(
                            FIELD_NAME, SCREEN_FIELD_ID, ErrorResponse.FieldState.MISSING),
                    null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new FieldErrorDecorator(withNull));
        }
    }

    @Nested
    @DisplayName("Marking a field")
    class Marking {

        @Test
        @DisplayName("a blank flag becomes a MISSING state, which is the outcome the macro signalled by "
                + "writing an asterisk into the field as well as recolouring it")
        void aBlankFlagBecomesMissing() {
            final FieldErrorDecorator decorator = FieldErrorDecorator.none()
                    .mark(FIELD_NAME, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.BLANK);

            assertThat(decorator.fieldErrors()).hasSize(1);
            assertThat(decorator.fieldErrors().get(0).state())
                    .isEqualTo(ErrorResponse.FieldState.MISSING);
        }

        @Test
        @DisplayName("a not-OK flag becomes an INVALID state, which is the outcome the macro signalled by "
                + "recolouring the field only and leaving its content in place")
        void aNotOkFlagBecomesInvalid() {
            final FieldErrorDecorator decorator = FieldErrorDecorator.none()
                    .mark(FIELD_NAME, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(decorator.fieldErrors()).hasSize(1);
            assertThat(decorator.fieldErrors().get(0).state())
                    .isEqualTo(ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("the two flag states produce different response states, so an empty field and a wrongly "
                + "filled field remain distinguishable exactly as they were on the terminal")
        void theTwoFlagStatesProduceDifferentResponseStates() {
            final ErrorResponse.FieldState fromBlank = FieldErrorDecorator.none()
                    .mark(FIELD_NAME, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.BLANK)
                    .fieldErrors().get(0).state();
            final ErrorResponse.FieldState fromNotOk = FieldErrorDecorator.none()
                    .mark(FIELD_NAME, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.NOT_OK)
                    .fieldErrors().get(0).state();

            assertThat(fromBlank).isNotEqualTo(fromNotOk);
        }

        @ParameterizedTest(name = "flag state {0} carries the field name and screen field identifier through")
        @EnumSource(FieldErrorDecorator.FlagState.class)
        @DisplayName("both the logical field name and the screen field identifier are carried through "
                + "unchanged, because the response must name the field the client sent and the screen field "
                + "the mapset declared")
        void bothNamesAreCarriedThroughUnchanged(final FieldErrorDecorator.FlagState flagState) {
            final ErrorResponse.FieldError entry = FieldErrorDecorator.none()
                    .mark(FIELD_NAME, SCREEN_FIELD_ID, flagState)
                    .fieldErrors().get(0);

            assertThat(entry.fieldName()).isEqualTo(FIELD_NAME);
            assertThat(entry.screenFieldId()).isEqualTo(SCREEN_FIELD_ID);
        }

        @ParameterizedTest(name = "flag state {0} produces an entry carrying no per-field message")
        @EnumSource(FieldErrorDecorator.FlagState.class)
        @DisplayName("no per-field message text is invented, because the macro wrote no message - the state "
                + "itself was the whole signal and the screen carried one shared message line")
        void noPerFieldMessageTextIsInvented(final FieldErrorDecorator.FlagState flagState) {
            final ErrorResponse.FieldError entry = FieldErrorDecorator.none()
                    .mark(FIELD_NAME, SCREEN_FIELD_ID, flagState)
                    .fieldErrors().get(0);

            assertThat(entry.message()).isNull();
        }

        @Test
        @DisplayName("marking leaves the original decorator untouched and returns a new one, so a validation "
                + "cascade cannot accidentally share accumulated state between two requests")
        void markingLeavesTheOriginalUntouched() {
            final FieldErrorDecorator original = FieldErrorDecorator.none();

            final FieldErrorDecorator marked =
                    original.mark(FIELD_NAME, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.BLANK);

            assertThat(original.isEmpty()).isTrue();
            assertThat(original.fieldErrors()).isEmpty();
            assertThat(marked).isNotSameAs(original);
            assertThat(marked.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("successive marks append in call order, so the order the 39 expansion sites fired in is "
                + "the order the client sees")
        void successiveMarksAppendInCallOrder() {
            final FieldErrorDecorator decorator = FieldErrorDecorator.none()
                    .mark("acctId", "ACCTSID", FieldErrorDecorator.FlagState.BLANK)
                    .mark("acctStatus", "ACSTTUS", FieldErrorDecorator.FlagState.NOT_OK)
                    .mark("creditLimit", "ACRDLIM", FieldErrorDecorator.FlagState.BLANK);

            assertThat(decorator.fieldErrors())
                    .extracting(ErrorResponse.FieldError::fieldName)
                    .containsExactly("acctId", "acctStatus", "creditLimit");
            assertThat(decorator.fieldErrors())
                    .extracting(ErrorResponse.FieldError::screenFieldId)
                    .containsExactly("ACCTSID", "ACSTTUS", "ACRDLIM");
            assertThat(decorator.fieldErrors())
                    .extracting(ErrorResponse.FieldError::state)
                    .containsExactly(ErrorResponse.FieldState.MISSING,
                            ErrorResponse.FieldState.INVALID,
                            ErrorResponse.FieldState.MISSING);
        }

        @Test
        @DisplayName("the decorator accumulates one entry per expansion site without loss, so all 39 sites "
                + "of the account-update screen can report together on a single re-submission")
        void theDecoratorAccumulatesOneEntryPerExpansionSite() {
            FieldErrorDecorator decorator = FieldErrorDecorator.none();
            for (int site = 1; site <= MACRO_EXPANSION_SITES; site++) {
                decorator = decorator.mark(
                        "field" + site,
                        "SCRN" + site,
                        site % 2 == 0
                                ? FieldErrorDecorator.FlagState.NOT_OK
                                : FieldErrorDecorator.FlagState.BLANK);
            }

            assertThat(decorator.fieldErrors()).hasSize(MACRO_EXPANSION_SITES);
            assertThat(decorator.fieldErrors().get(0).fieldName()).isEqualTo("field1");
            assertThat(decorator.fieldErrors().get(MACRO_EXPANSION_SITES - 1).fieldName())
                    .isEqualTo("field" + MACRO_EXPANSION_SITES);
        }

        @Test
        @DisplayName("the same field may be marked more than once, because the decorator records what the "
                + "cascade did rather than de-duplicating it")
        void theSameFieldMayBeMarkedMoreThanOnce() {
            final FieldErrorDecorator decorator = FieldErrorDecorator.none()
                    .mark(FIELD_NAME, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.BLANK)
                    .mark(FIELD_NAME, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(decorator.fieldErrors()).hasSize(2);
            assertThat(decorator.fieldErrors())
                    .extracting(ErrorResponse.FieldError::state)
                    .containsExactly(ErrorResponse.FieldState.MISSING,
                            ErrorResponse.FieldState.INVALID);
        }
    }

    @Nested
    @DisplayName("Rejection of absent arguments")
    class RejectionOfAbsentArguments {

        @Test
        @DisplayName("a null logical field name is rejected, because a response entry naming no field tells a "
                + "client nothing")
        void aNullFieldNameIsRejected() {
            final FieldErrorDecorator decorator = FieldErrorDecorator.none();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> decorator.mark(
                            null, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.BLANK))
                    .withMessage("field must not be null");
        }

        @Test
        @DisplayName("a null screen field identifier is rejected, because the identifier is what a client "
                + "uses to place focus and there is no default worth guessing")
        void aNullScreenFieldIdentifierIsRejected() {
            final FieldErrorDecorator decorator = FieldErrorDecorator.none();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> decorator.mark(
                            FIELD_NAME, null, FieldErrorDecorator.FlagState.BLANK))
                    .withMessage("bmsFieldId must not be null");
        }

        @Test
        @DisplayName("a null flag state is rejected, because the macro fired only on a known flag value and "
                + "an unknown one is a caller defect rather than a third outcome")
        void aNullFlagStateIsRejected() {
            final FieldErrorDecorator decorator = FieldErrorDecorator.none();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> decorator.mark(FIELD_NAME, SCREEN_FIELD_ID, null))
                    .withMessage("flagState must not be null");
        }

        @Test
        @DisplayName("a rejected mark leaves the decorator unchanged, so a caller defect cannot half-apply")
        void aRejectedMarkLeavesTheDecoratorUnchanged() {
            final FieldErrorDecorator decorator = FieldErrorDecorator.none()
                    .mark(FIELD_NAME, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.BLANK);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> decorator.mark(null, null, null));

            assertThat(decorator.fieldErrors()).hasSize(1);
        }

        @Test
        @DisplayName("an empty field name or screen field identifier is accepted, because emptiness is a "
                + "value the fixed-width originals could genuinely hold whereas absence is not")
        void anEmptyNameIsAcceptedWhereAbsenceIsNot() {
            assertThatCode(() -> FieldErrorDecorator.none()
                    .mark("", "", FieldErrorDecorator.FlagState.NOT_OK))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("The flag state vocabulary")
    class FlagStateVocabulary {

        @Test
        @DisplayName("exactly two flag states are declared, because the macro acted on a not-OK flag and a "
                + "blank flag and on nothing else")
        void exactlyTwoFlagStatesAreDeclared() {
            assertThat(FieldErrorDecorator.FlagState.values()).hasSize(DECORATABLE_FLAG_STATES);
        }

        @Test
        @DisplayName("there is no acceptable state to mark, because an acceptable field was never decorated "
                + "at all and inventing an OK constant would let a caller record a non-error as an error")
        void thereIsNoAcceptableStateToMark() {
            assertThat(FieldErrorDecorator.FlagState.values())
                    .extracting(Enum::name)
                    .containsExactly("BLANK", "NOT_OK")
                    .doesNotContain("OK", "VALID", "ACCEPTABLE");
        }

        @ParameterizedTest(name = "{0} maps onto exactly one response state")
        @EnumSource(FieldErrorDecorator.FlagState.class)
        @DisplayName("every declared flag state maps onto a response state, so the mapping is total and no "
                + "flag can reach a client undescribed")
        void everyFlagStateMapsOntoAResponseState(final FieldErrorDecorator.FlagState flagState) {
            final ErrorResponse.FieldState mapped = FieldErrorDecorator.none()
                    .mark(FIELD_NAME, SCREEN_FIELD_ID, flagState)
                    .fieldErrors().get(0).state();

            assertThat(mapped).isIn(
                    ErrorResponse.FieldState.MISSING, ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("the mapping is injective, so the two flag states never collapse onto one response state")
        void theMappingIsInjective() {
            final List<ErrorResponse.FieldState> mapped =
                    Arrays.stream(FieldErrorDecorator.FlagState.values())
                            .map(flag -> FieldErrorDecorator.none()
                                    .mark(FIELD_NAME, SCREEN_FIELD_ID, flag)
                                    .fieldErrors().get(0).state())
                            .toList();

            assertThat(mapped).doesNotHaveDuplicates().hasSize(DECORATABLE_FLAG_STATES);
        }
    }

    @Nested
    @DisplayName("Value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("two decorators built the same way are equal and agree on hash code, so a decorator can "
                + "be compared as a value")
        void twoDecoratorsBuiltTheSameWayAreEqual() {
            final FieldErrorDecorator first = FieldErrorDecorator.none()
                    .mark(FIELD_NAME, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.BLANK);
            final FieldErrorDecorator second = FieldErrorDecorator.none()
                    .mark(FIELD_NAME, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.BLANK);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second).isNotSameAs(second);
        }

        @Test
        @DisplayName("decorators differing only in flag state are not equal, so the two-outcome distinction "
                + "survives comparison as well as rendering")
        void decoratorsDifferingOnlyInFlagStateAreNotEqual() {
            final FieldErrorDecorator missing = FieldErrorDecorator.none()
                    .mark(FIELD_NAME, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.BLANK);
            final FieldErrorDecorator invalid = FieldErrorDecorator.none()
                    .mark(FIELD_NAME, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.NOT_OK);

            assertThat(missing).isNotEqualTo(invalid);
        }

        @Test
        @DisplayName("decorators differing only in entry order are not equal, so ordering is part of the "
                + "value and not an incidental detail")
        void decoratorsDifferingOnlyInEntryOrderAreNotEqual() {
            final FieldErrorDecorator forwards = FieldErrorDecorator.none()
                    .mark("a", "A", FieldErrorDecorator.FlagState.BLANK)
                    .mark("b", "B", FieldErrorDecorator.FlagState.BLANK);
            final FieldErrorDecorator backwards = FieldErrorDecorator.none()
                    .mark("b", "B", FieldErrorDecorator.FlagState.BLANK)
                    .mark("a", "A", FieldErrorDecorator.FlagState.BLANK);

            assertThat(forwards).isNotEqualTo(backwards);
        }

        @Test
        @DisplayName("a decorator is not equal to its entry list, so it cannot be confused with the "
                + "collection it wraps")
        void aDecoratorIsNotEqualToItsEntryList() {
            final FieldErrorDecorator decorator = FieldErrorDecorator.none();

            assertThat(decorator).isNotEqualTo(List.of()).isNotEqualTo(null);
        }

        @Test
        @DisplayName("the rendering names the record and its entries, so a log line identifies which fields "
                + "were decorated")
        void theRenderingNamesTheRecordAndItsEntries() {
            final String rendered = FieldErrorDecorator.none()
                    .mark(FIELD_NAME, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.NOT_OK)
                    .toString();

            assertThat(rendered)
                    .startsWith("FieldErrorDecorator[")
                    .endsWith("]")
                    .contains(FIELD_NAME)
                    .contains(SCREEN_FIELD_ID)
                    .contains(ErrorResponse.FieldState.INVALID.name());
        }

        @Test
        @DisplayName("an empty decorator renders without any entry, so an unproblematic re-submission is "
                + "visibly clean in a log")
        void anEmptyDecoratorRendersWithoutAnyEntry() {
            assertThat(FieldErrorDecorator.none().toString())
                    .isEqualTo("FieldErrorDecorator[fieldErrors=[]]");
        }
    }
}
