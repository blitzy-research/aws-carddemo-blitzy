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
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Unit test for {@link FieldErrorDecorator}, the single Java equivalent of the three-token
 * {@code COPY ... REPLACING} macro member {@code CSSETATY}, whose 39 textual expansions in the
 * account-update program collapse into one method invoked 39 times.
 *
 * <h2>The two-state contract is the whole point, and one direction of it is counter-intuitive</h2>
 *
 * <p>The macro applied <em>two</em> edits when a field's flag was specifically blank - it changed the
 * field's colour and additionally wrote a marker character - but only <em>one</em> when the flag was
 * merely not-OK. That is not cosmetic: the two cases carry different remedies for the operator. A field
 * never supplied needs a value; a field supplied wrongly needs correcting, and the operator's own
 * keystrokes are deliberately left in place to be corrected. So the blank state maps to MISSING and the
 * not-OK state maps to INVALID, and collapsing them into a single boolean "this field is wrong" would
 * discard information the legacy screen genuinely conveyed. Both directions of that mapping are pinned
 * below, individually.</p>
 *
 * <h2>What this type must NOT do, asserted rather than assumed</h2>
 *
 * <p>It evaluates no rule, de-duplicates nothing, re-orders nothing, and does not test the re-entry
 * gate. Each of those is a behaviour a well-meaning later edit might add - suppressing a duplicate
 * looks like tidiness - and each would be a divergence: the macro appended unconditionally, in source
 * order, and the gate lived in the calling program. The assertions therefore prove that marking the
 * same field twice yields two entries and that order is insertion order.</p>
 */
@DisplayName("FieldErrorDecorator - the CSSETATY macro collapsed into one method")
class FieldErrorDecoratorSecurityTest {

    private static final String FIELD = "acctStatus";
    private static final String SCREEN_FIELD_ID = "ACSTTUS";

    @Nested
    @DisplayName("The empty starting value, which is also the first-submission shape")
    class EmptyAccumulation {

        @Test
        @DisplayName("the named empty value holds no entries and reports itself empty")
        void theNamedEmptyValueHoldsNoEntries() {
            final FieldErrorDecorator subject = FieldErrorDecorator.none();
            assertThat(subject.fieldErrors()).isEmpty();
            assertThat(subject.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("two empty values are equal, because equality is by component even though a fresh instance is "
                + "returned on every call")
        void twoEmptyValuesAreEqual() {
            assertThat(FieldErrorDecorator.none()).isEqualTo(FieldErrorDecorator.none());
            assertThat(FieldErrorDecorator.none()).hasSameHashCodeAs(FieldErrorDecorator.none());
        }

        @Test
        @DisplayName("a fresh instance is returned on every call, so this class holds no static state")
        void aFreshInstanceIsReturnedOnEveryCall() {
            assertThat(FieldErrorDecorator.none()).isNotSameAs(FieldErrorDecorator.none());
        }

        @Test
        @DisplayName("a null collection becomes the empty list rather than being stored, so no caller has to test "
                + "for null")
        void aNullCollectionBecomesTheEmptyList() {
            final FieldErrorDecorator subject = new FieldErrorDecorator(null);
            assertThat(subject.fieldErrors()).isNotNull().isEmpty();
            assertThat(subject.isEmpty()).isTrue();
            assertThat(subject).isEqualTo(FieldErrorDecorator.none());
        }
    }

    @Nested
    @DisplayName("Defensive copying, which is what makes the accumulation safe to describe as pure")
    class DefensiveCopying {

        @Test
        @DisplayName("the stored list is detached from the caller's collection, so a later mutation by the caller "
                + "cannot alter an already-built accumulation")
        void theStoredListIsDetachedFromTheCallersCollection() {
            final List<FieldErrorDecorator.MarkedField> supplied = new ArrayList<>();
            supplied.add(marked(FIELD, FieldErrorDecorator.FlagState.BLANK));
            final FieldErrorDecorator subject = new FieldErrorDecorator(supplied);

            supplied.add(marked("smuggled", FieldErrorDecorator.FlagState.NOT_OK));

            assertThat(subject.fieldErrors()).hasSize(1);
            assertThat(subject.fieldErrors().get(0).fieldName()).isEqualTo(FIELD);
        }

        @Test
        @DisplayName("the stored list is unmodifiable, so a holder of the accumulation cannot append to it")
        void theStoredListIsUnmodifiable() {
            final FieldErrorDecorator subject = FieldErrorDecorator.none()
                    .mark(FIELD, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.BLANK);
            final List<ErrorResponse.FieldError> stored = subject.fieldErrors();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> stored.add(entry("smuggled", ErrorResponse.FieldState.INVALID)));
        }

        @Test
        @DisplayName("a null element is rejected outright, because an entry with no state is meaningless and "
                + "dropping it silently would hide an error the client has to show")
        void aNullElementIsRejected() {
            final List<FieldErrorDecorator.MarkedField> withNull = new ArrayList<>();
            withNull.add(null);
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new FieldErrorDecorator(withNull));
        }
    }

    @Nested
    @DisplayName("The two-state translation, pinned in both directions")
    class StateTranslation {

        @Test
        @DisplayName("the blank state becomes MISSING, which is the state in which the legacy macro applied BOTH of "
                + "its edits and the remedy is to supply a value")
        void theBlankStateBecomesMissing() {
            final FieldErrorDecorator subject = FieldErrorDecorator.none()
                    .mark(FIELD, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.BLANK);
            assertThat(subject.fieldErrors()).hasSize(1);
            assertThat(subject.fieldErrors().get(0).state())
                    .isEqualTo(ErrorResponse.FieldState.MISSING);
        }

        @Test
        @DisplayName("the not-OK state becomes INVALID, which is the state in which the macro applied only its "
                + "first edit and the remedy is to correct the value")
        void theNotOkStateBecomesInvalid() {
            final FieldErrorDecorator subject = FieldErrorDecorator.none()
                    .mark(FIELD, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.NOT_OK);
            assertThat(subject.fieldErrors().get(0).state())
                    .isEqualTo(ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("the two states do not collapse onto one another, which is the assertion that keeps a later "
                + "simplification from discarding the distinction the legacy screen conveyed")
        void theTwoStatesDoNotCollapse() {
            final ErrorResponse.FieldState fromBlank = FieldErrorDecorator.none()
                    .mark(FIELD, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.BLANK)
                    .fieldErrors().get(0).state();
            final ErrorResponse.FieldState fromNotOk = FieldErrorDecorator.none()
                    .mark(FIELD, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.NOT_OK)
                    .fieldErrors().get(0).state();
            assertThat(fromBlank).isNotEqualTo(fromNotOk);
        }

        @ParameterizedTest
        @EnumSource(FieldErrorDecorator.FlagState.class)
        @DisplayName("every declared flag state translates to some published state, so the switch is total over the "
                + "input vocabulary")
        void everyFlagStateTranslates(final FieldErrorDecorator.FlagState flagState) {
            final FieldErrorDecorator subject = FieldErrorDecorator.none()
                    .mark(FIELD, SCREEN_FIELD_ID, flagState);
            assertThat(subject.fieldErrors().get(0).state()).isNotNull();
        }

        @Test
        @DisplayName("the input vocabulary has exactly two constants, because the macro tested exactly two "
                + "conditions - there is deliberately no valid, none or unknown constant")
        void theInputVocabularyHasExactlyTwoConstants() {
            assertThat(FieldErrorDecorator.FlagState.values()).hasSize(2);
            assertThat(FieldErrorDecorator.FlagState.values())
                    .containsExactly(FieldErrorDecorator.FlagState.BLANK,
                            FieldErrorDecorator.FlagState.NOT_OK);
        }
    }

    @Nested
    @DisplayName("What the marked entry carries, and what it does not")
    class EntryContents {

        @Test
        @DisplayName("the entry carries the request-contract field name and the legacy screen field identifier, so "
                + "a response stays traceable to the map it derives from")
        void theEntryCarriesBothIdentifiers() {
            final ErrorResponse.FieldError marked = FieldErrorDecorator.none()
                    .mark(FIELD, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.BLANK)
                    .fieldErrors().get(0);
            assertThat(marked.fieldName()).isEqualTo(FIELD);
            assertThat(marked.screenFieldId()).isEqualTo(SCREEN_FIELD_ID);
        }

        @Test
        @DisplayName("no per-field message is produced, which is faithful: the legacy macro emitted no text of its "
                + "own because the explanatory text lived in the single summary line the caller owns")
        void noPerFieldMessageIsProduced() {
            final ErrorResponse.FieldError marked = FieldErrorDecorator.none()
                    .mark(FIELD, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.NOT_OK)
                    .fieldErrors().get(0);
            assertThat(marked.message()).isNull();
        }

        @Test
        @DisplayName("the two identifiers are stored verbatim, untrimmed and un-recased, because the screen field "
                + "identifier is an opaque label")
        void theTwoIdentifiersAreStoredVerbatim() {
            final ErrorResponse.FieldError marked = FieldErrorDecorator.none()
                    .mark("  spaced Name  ", "  acsttus  ", FieldErrorDecorator.FlagState.BLANK)
                    .fieldErrors().get(0);
            assertThat(marked.fieldName()).isEqualTo("  spaced Name  ");
            assertThat(marked.screenFieldId()).isEqualTo("  acsttus  ");
        }
    }

    @Nested
    @DisplayName("Purity: marking returns a new accumulation and leaves the receiver untouched")
    class Purity {

        @Test
        @DisplayName("the receiver is unchanged by a mark, so an accumulation already handed to a caller cannot "
                + "acquire entries behind that caller's back")
        void theReceiverIsUnchangedByAMark() {
            final FieldErrorDecorator before = FieldErrorDecorator.none();
            final FieldErrorDecorator after =
                    before.mark(FIELD, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.BLANK);
            assertThat(before.isEmpty()).isTrue();
            assertThat(before.fieldErrors()).isEmpty();
            assertThat(after.isEmpty()).isFalse();
            assertThat(after).isNotSameAs(before);
        }

        @Test
        @DisplayName("marking twice from the same receiver produces two independent one-entry accumulations, so a "
                + "branching caller cannot cross-contaminate them")
        void markingTwiceFromTheSameReceiverProducesIndependentResults() {
            final FieldErrorDecorator root = FieldErrorDecorator.none();
            final FieldErrorDecorator left =
                    root.mark("left", "LEFT", FieldErrorDecorator.FlagState.BLANK);
            final FieldErrorDecorator right =
                    root.mark("right", "RIGHT", FieldErrorDecorator.FlagState.NOT_OK);
            assertThat(left.fieldErrors()).hasSize(1);
            assertThat(right.fieldErrors()).hasSize(1);
            assertThat(left.fieldErrors().get(0).fieldName()).isEqualTo("left");
            assertThat(right.fieldErrors().get(0).fieldName()).isEqualTo("right");
            assertThat(left).isNotEqualTo(right);
        }
    }

    @Nested
    @DisplayName("Accumulation over many fields, which is what 39 macro expansions actually do")
    class Accumulation {

        @Test
        @DisplayName("entries appear in insertion order, because the macro appended in source order and re-ordering "
                + "would change which field an operator is pointed at first")
        void entriesAppearInInsertionOrder() {
            final FieldErrorDecorator subject = FieldErrorDecorator.none()
                    .mark("first", "FIRST", FieldErrorDecorator.FlagState.BLANK)
                    .mark("second", "SECOND", FieldErrorDecorator.FlagState.NOT_OK)
                    .mark("third", "THIRD", FieldErrorDecorator.FlagState.BLANK);
            assertThat(subject.fieldErrors())
                    .extracting(ErrorResponse.FieldError::fieldName)
                    .containsExactly("first", "second", "third");
        }

        @Test
        @DisplayName("each entry keeps its own state through the accumulation, so the states are not homogenised")
        void eachEntryKeepsItsOwnState() {
            final FieldErrorDecorator subject = FieldErrorDecorator.none()
                    .mark("first", "FIRST", FieldErrorDecorator.FlagState.BLANK)
                    .mark("second", "SECOND", FieldErrorDecorator.FlagState.NOT_OK);
            assertThat(subject.fieldErrors())
                    .extracting(ErrorResponse.FieldError::state)
                    .containsExactly(ErrorResponse.FieldState.MISSING,
                            ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("marking the same field twice yields TWO entries: nothing is de-duplicated, because "
                + "suppressing one would be a decision this type has no standing to make")
        void markingTheSameFieldTwiceYieldsTwoEntries() {
            final FieldErrorDecorator subject = FieldErrorDecorator.none()
                    .mark(FIELD, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.BLANK)
                    .mark(FIELD, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.BLANK);
            assertThat(subject.fieldErrors()).hasSize(2);
            assertThat(subject.fieldErrors().get(0)).isEqualTo(subject.fieldErrors().get(1));
        }

        @Test
        @DisplayName("the same field marked in both states yields two entries carrying both states, rather than one "
                + "winning")
        void theSameFieldInBothStatesYieldsBothEntries() {
            final FieldErrorDecorator subject = FieldErrorDecorator.none()
                    .mark(FIELD, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.BLANK)
                    .mark(FIELD, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.NOT_OK);
            assertThat(subject.fieldErrors())
                    .extracting(ErrorResponse.FieldError::state)
                    .containsExactly(ErrorResponse.FieldState.MISSING,
                            ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("an accumulation scales to all 39 decorated fields of the account-update screen without loss "
                + "or re-ordering")
        void anAccumulationScalesToThirtyNineFields() {
            FieldErrorDecorator subject = FieldErrorDecorator.none();
            for (int index = 1; index <= 39; index++) {
                subject = subject.mark("field" + index, "SCRN" + index,
                        (index % 2 == 0) ? FieldErrorDecorator.FlagState.BLANK
                                : FieldErrorDecorator.FlagState.NOT_OK);
            }
            assertThat(subject.fieldErrors()).hasSize(39);
            assertThat(subject.isEmpty()).isFalse();
            assertThat(subject.fieldErrors().get(0).fieldName()).isEqualTo("field1");
            assertThat(subject.fieldErrors().get(38).fieldName()).isEqualTo("field39");
            assertThat(subject.fieldErrors().get(0).state())
                    .isEqualTo(ErrorResponse.FieldState.INVALID);
            assertThat(subject.fieldErrors().get(1).state())
                    .isEqualTo(ErrorResponse.FieldState.MISSING);
        }

        @Test
        @DisplayName("the presence test is the exact inverse of holding entries, matching the decision the legacy "
                + "program made between re-displaying a decorated screen and committing the update")
        void thePresenceTestIsTheExactInverseOfHoldingEntries() {
            assertThat(FieldErrorDecorator.none().isEmpty()).isTrue();
            final FieldErrorDecorator marked = FieldErrorDecorator.none()
                    .mark(FIELD, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.BLANK);
            assertThat(marked.isEmpty()).isEqualTo(marked.fieldErrors().isEmpty());
            assertThat(marked.isEmpty()).isFalse();
        }
    }

    @Nested
    @DisplayName("Mandatory arguments, all three of them load-bearing")
    class MandatoryArguments {

        @Test
        @DisplayName("a null field name is rejected, because without it a client cannot locate the field")
        void aNullFieldNameIsRejected() {
            final FieldErrorDecorator subject = FieldErrorDecorator.none();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> subject.mark(null, SCREEN_FIELD_ID,
                            FieldErrorDecorator.FlagState.BLANK))
                    .withMessageContaining("field");
        }

        @Test
        @DisplayName("a null screen field identifier is rejected, because without it the entry loses its "
                + "traceability to the legacy map")
        void aNullScreenFieldIdentifierIsRejected() {
            final FieldErrorDecorator subject = FieldErrorDecorator.none();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> subject.mark(FIELD, null,
                            FieldErrorDecorator.FlagState.BLANK))
                    .withMessageContaining("bmsFieldId");
        }

        @Test
        @DisplayName("a null flag state is rejected, because without it the client cannot tell the operator whether "
                + "to supply a value or correct one")
        void aNullFlagStateIsRejected() {
            final FieldErrorDecorator subject = FieldErrorDecorator.none();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> subject.mark(FIELD, SCREEN_FIELD_ID, null))
                    .withMessageContaining("flagState");
        }

        @Test
        @DisplayName("a rejected mark leaves the receiver untouched, so a caller defect cannot corrupt an "
                + "accumulation part way through")
        void aRejectedMarkLeavesTheReceiverUntouched() {
            final FieldErrorDecorator subject = FieldErrorDecorator.none()
                    .mark(FIELD, SCREEN_FIELD_ID, FieldErrorDecorator.FlagState.BLANK);
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> subject.mark(null, null, null));
            assertThat(subject.fieldErrors()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("two accumulations built by the same sequence of marks are equal and hash alike")
        void twoAccumulationsBuiltIdenticallyAreEqual() {
            final FieldErrorDecorator left = FieldErrorDecorator.none()
                    .mark("a", "A", FieldErrorDecorator.FlagState.BLANK)
                    .mark("b", "B", FieldErrorDecorator.FlagState.NOT_OK);
            final FieldErrorDecorator right = FieldErrorDecorator.none()
                    .mark("a", "A", FieldErrorDecorator.FlagState.BLANK)
                    .mark("b", "B", FieldErrorDecorator.FlagState.NOT_OK);
            assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);
        }

        @Test
        @DisplayName("two accumulations built in a different order are NOT equal, which confirms order is part of "
                + "the value rather than incidental")
        void orderIsPartOfTheValue() {
            final FieldErrorDecorator forward = FieldErrorDecorator.none()
                    .mark("a", "A", FieldErrorDecorator.FlagState.BLANK)
                    .mark("b", "B", FieldErrorDecorator.FlagState.BLANK);
            final FieldErrorDecorator reversed = FieldErrorDecorator.none()
                    .mark("b", "B", FieldErrorDecorator.FlagState.BLANK)
                    .mark("a", "A", FieldErrorDecorator.FlagState.BLANK);
            assertThat(forward).isNotEqualTo(reversed);
        }

        @Test
        @DisplayName("an accumulation is not equal to null and not equal to a foreign type")
        void anAccumulationIsNotEqualToNullOrAForeignType() {
            final FieldErrorDecorator subject = FieldErrorDecorator.none();
            assertThat(subject).isNotEqualTo(null);
            assertThat(subject.equals(List.of())).isFalse();
        }

        @Test
        @DisplayName("the rendering names the type and does not throw on an empty accumulation")
        void theRenderingNamesTheType() {
            assertThat(FieldErrorDecorator.none().toString()).contains("FieldErrorDecorator");
        }
    }

    /** Builds a projected entry with no message, which is the shape this type publishes. */
    private static ErrorResponse.FieldError entry(final String fieldName,
            final ErrorResponse.FieldState state) {
        return new ErrorResponse.FieldError(fieldName, "SCRN", state, null);
    }

    /** Builds a neutral accumulated entry, which is the shape this type carries. */
    private static FieldErrorDecorator.MarkedField marked(final String fieldName,
            final FieldErrorDecorator.FlagState flagState) {
        return new FieldErrorDecorator.MarkedField(fieldName, "SCRN", flagState);
    }
}
