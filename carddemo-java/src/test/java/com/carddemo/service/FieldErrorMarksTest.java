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
package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.carddemo.exception.ValidationException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FieldErrorMarks}, the accumulation a validation cascade grows one field at a time.
 *
 * <p><strong>What this file is really guarding.</strong> That marking sequence survives, because the 39
 * legacy macro expansions ran in an irregular source order and that order is what an operator saw; that the
 * two flag states stay distinguishable, because a field left blank and a field filled in wrongly need
 * different remedies; that a duplicate mark on one field is kept rather than de-duplicated, since the legacy
 * expansions could fire twice on one field and dropping one would change what the screen showed; that the
 * accumulation is immutable and unaliased, so a caller cannot reach back into an accumulation it has already
 * handed on; and that no per-field message is ever invented, because the macro emitted none.
 *
 * <p>A pure unit test: no Spring context, no connection, no container.
 *
 * <p>Provenance: {@code app/cpy/CSSETATY.cpy} and its expansion sites in {@code app/cbl/COACTUPC.cbl},
 * read as read-only reference at checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * @since 1.0.0
 */
@DisplayName("FieldErrorMarks :: the validation accumulation, owned by the service layer")
final class FieldErrorMarksTest {

    @Nested
    @DisplayName("The empty accumulation")
    class TheEmptyAccumulation {

        @Test
        @DisplayName("is shared, carries nothing, and reports itself empty, which is what a first "
                + "submission carries even when fields are blank")
        void isSharedAndCarriesNothing() {
            assertThat(FieldErrorMarks.none()).isSameAs(FieldErrorMarks.none());
            assertThat(FieldErrorMarks.none().markedFields()).isEmpty();
            assertThat(FieldErrorMarks.none().isEmpty()).isTrue();
            assertThat(FieldErrorMarks.none().fieldErrors()).isEmpty();
        }

        @Test
        @DisplayName("normalises an absent entry list to the empty one, so no accessor has to null-check")
        void normalisesAnAbsentEntryList() {
            assertThat(new FieldErrorMarks(null).markedFields()).isEmpty();
            assertThat(new FieldErrorMarks(null).isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("Marking")
    class Marking {

        @Test
        @DisplayName("appends each entry after the existing ones and never mutates the receiver, so an "
                + "accumulation already handed on cannot change underneath its holder")
        void appendsWithoutMutatingTheReceiver() {
            final FieldErrorMarks first =
                    FieldErrorMarks.none().mark("accountStatus", "ACSTTUS", FieldErrorMarks.FlagState.BLANK);
            final FieldErrorMarks second =
                    first.mark("creditLimit", "ACRDLIM", FieldErrorMarks.FlagState.NOT_OK);

            assertThat(FieldErrorMarks.none().markedFields()).isEmpty();
            assertThat(first.markedFields()).hasSize(1);
            assertThat(second.markedFields()).hasSize(2);
            assertThat(second.markedFields().get(0).field()).isEqualTo("accountStatus");
            assertThat(second.markedFields().get(1).field()).isEqualTo("creditLimit");
            assertThat(second.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("keeps a repeated mark on one field rather than de-duplicating it, because the legacy "
                + "expansions could fire more than once on one field")
        void keepsARepeatedMark() {
            final FieldErrorMarks twice = FieldErrorMarks.none()
                    .mark("accountStatus", "ACSTTUS", FieldErrorMarks.FlagState.BLANK)
                    .mark("accountStatus", "ACSTTUS", FieldErrorMarks.FlagState.NOT_OK);

            assertThat(twice.markedFields()).hasSize(2);
            assertThat(twice.markedFields().get(0).flagState())
                    .isEqualTo(FieldErrorMarks.FlagState.BLANK);
            assertThat(twice.markedFields().get(1).flagState())
                    .isEqualTo(FieldErrorMarks.FlagState.NOT_OK);
        }

        @Test
        @DisplayName("refuses an entry with no property name, no screen field or no state, because none of "
                + "the three can be inferred on an operator's behalf")
        void refusesAnIncompleteEntry() {
            assertThatNullPointerException().isThrownBy(() ->
                    new FieldErrorMarks.MarkedField(null, "ACSTTUS", FieldErrorMarks.FlagState.BLANK));
            assertThatNullPointerException().isThrownBy(() ->
                    new FieldErrorMarks.MarkedField("accountStatus", null,
                            FieldErrorMarks.FlagState.BLANK));
            assertThatNullPointerException().isThrownBy(() ->
                    new FieldErrorMarks.MarkedField("accountStatus", "ACSTTUS", null));
        }

        @Test
        @DisplayName("detaches the supplied entry list, so a later change to the caller's collection cannot "
                + "reach the accumulation")
        void detachesTheSuppliedEntryList() {
            final List<FieldErrorMarks.MarkedField> supplied = new ArrayList<>();
            supplied.add(new FieldErrorMarks.MarkedField("accountStatus", "ACSTTUS",
                    FieldErrorMarks.FlagState.BLANK));
            final FieldErrorMarks marks = new FieldErrorMarks(supplied);

            supplied.add(new FieldErrorMarks.MarkedField("creditLimit", "ACRDLIM",
                    FieldErrorMarks.FlagState.NOT_OK));

            assertThat(marks.markedFields()).hasSize(1);
            assertThat(marks.markedFields()).isUnmodifiable();
        }
    }

    @Nested
    @DisplayName("The translation into a validation failure")
    class TheTranslationIntoAValidationFailure {

        @Test
        @DisplayName("emits one entry per mark, in the same sequence, mapping blank to missing and not-ok "
                + "to invalid so the two remedies stay distinguishable")
        void emitsOneEntryPerMarkInSequence() {
            final List<ValidationException.FieldError> errors = FieldErrorMarks.none()
                    .mark("accountStatus", "ACSTTUS", FieldErrorMarks.FlagState.BLANK)
                    .mark("creditLimit", "ACRDLIM", FieldErrorMarks.FlagState.NOT_OK)
                    .fieldErrors();

            assertThat(errors).hasSize(2);
            assertThat(errors.get(0).field()).isEqualTo("accountStatus");
            assertThat(errors.get(0).bmsFieldId()).isEqualTo("ACSTTUS");
            assertThat(errors.get(0).state()).isEqualTo(ValidationException.FieldState.MISSING);
            assertThat(errors.get(1).field()).isEqualTo("creditLimit");
            assertThat(errors.get(1).bmsFieldId()).isEqualTo("ACRDLIM");
            assertThat(errors.get(1).state()).isEqualTo(ValidationException.FieldState.INVALID);
        }

        @Test
        @DisplayName("invents no per-field message, because the macro emitted none and the explanatory text "
                + "belongs to the single summary line")
        void inventsNoPerFieldMessage() {
            final List<ValidationException.FieldError> errors = FieldErrorMarks.none()
                    .mark("accountStatus", "ACSTTUS", FieldErrorMarks.FlagState.BLANK)
                    .fieldErrors();

            assertThat(errors.get(0).message()).isNull();
        }

        @Test
        @DisplayName("builds an unmodifiable list fresh on each call, so no caller can alter what a later "
                + "caller reads")
        void buildsAnUnmodifiableListFreshEachCall() {
            final FieldErrorMarks marks = FieldErrorMarks.none()
                    .mark("accountStatus", "ACSTTUS", FieldErrorMarks.FlagState.BLANK);

            assertThat(marks.fieldErrors()).isUnmodifiable();
            assertThat(marks.fieldErrors()).isEqualTo(marks.fieldErrors());
        }

        @Test
        @DisplayName("declares exactly the two legacy flag states, so no third state can be funnelled "
                + "into a catch-all")
        void declaresExactlyTheTwoFlagStates() {
            assertThat(FieldErrorMarks.FlagState.values())
                    .containsExactly(FieldErrorMarks.FlagState.BLANK,
                            FieldErrorMarks.FlagState.NOT_OK);
        }
    }
}
