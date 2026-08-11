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

import com.carddemo.exception.ValidationException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The accumulation a validation cascade grows one field at a time: the service-layer replacement for the
 * 39 textual expansions of the parameterized macro {@code app/cpy/CSSETATY.cpy} in the legacy
 * account-update program.
 *
 * <p><strong>Two states, not one boolean.</strong> The macro coloured a field when its validation flag was
 * not-OK and <em>additionally</em> wrote a marker when the flag was specifically blank, so a field left
 * empty and a field filled in wrongly were two distinguishable screen states. {@link FlagState#BLANK}
 * therefore becomes {@code MISSING} and {@link FlagState#NOT_OK} becomes {@code INVALID}; collapsing them
 * would lose the distinction the screen made and would tell an operator to supply a value they had already
 * supplied.
 *
 * <p><strong>Marking sequence is preserved exactly.</strong> The expansions ran in source sequence, that
 * sequence is irregular, and it is the order in which an operator saw the fields marked, so no entry is
 * re-ordered, de-duplicated or dropped.
 *
 * <p><strong>When it fires is the caller's decision.</strong> The macro fired only when the
 * program-context re-enter condition was set, so a first submission carries no field marks even when
 * fields are empty. This type holds no such gate: it accumulates what it is told to accumulate.
 *
 * <p><strong>Why this is not the wire record.</strong> The wire form of the accumulation lives in
 * {@code api.dto} because the two-state error contract is what a client reads, and the module's layering
 * forbids a service depending upward on the API package. This type carries the same entries under the same
 * names, and one adapter in the API layer converts it into the response contract, so a service names only
 * types its own layer owns. It converts in the other direction itself, into
 * {@link ValidationException.FieldError}, because that carrier lives in the exception layer beneath the
 * service layer and is what a service throws.
 *
 * <p><strong>No per-field message is invented.</strong> The macro emitted none - the explanatory text
 * belonged to the single summary line the caller supplies - and no submitted field value is ever carried,
 * so a failure on a credential field cannot echo what was typed.
 *
 * <p>Two of the 39 decorated fields, the middle name and the second address line, are marked but never
 * validated, as the legacy source states in place. No validation constraint may be added for them
 * anywhere, because that would reject input the legacy system accepts.
 *
 * <p>Deeply immutable: the entry list is copied on construction and every mutation returns a new
 * accumulation, so an instance is safe for unsynchronised concurrent use.
 *
 * @param markedFields the entries in marking sequence; never {@code null} and never modifiable
 * @since 1.0.0
 */
public record FieldErrorMarks(List<MarkedField> markedFields) {

    /** The shared empty accumulation: nothing has been marked. */
    private static final FieldErrorMarks NONE = new FieldErrorMarks(List.of());

    /**
     * Normalizes the entry list so the component is never {@code null}, never aliased to caller-owned
     * state and never mutable. Order and length are preserved exactly, because the order is the order the
     * legacy expansions ran in.
     */
    public FieldErrorMarks {
        markedFields = (markedFields == null) ? List.of() : List.copyOf(markedFields);
    }

    /**
     * Returns the empty accumulation, which is what a turn that marked nothing carries.
     *
     * @return the shared empty instance
     */
    public static FieldErrorMarks none() {
        return NONE;
    }

    /**
     * Records one marked field, returning a new accumulation; the receiver is unchanged.
     *
     * @param field the request or response property name the error is reported against
     * @param bmsFieldId the legacy screen field name, carried so a reader can trace the mapping
     * @param flagState the legacy validation-flag state this entry was raised from
     * @return a new accumulation carrying this entry after the existing ones
     */
    public FieldErrorMarks mark(final String field, final String bmsFieldId,
            final FlagState flagState) {
        final MarkedField entry = new MarkedField(field, bmsFieldId, flagState);

        return new FieldErrorMarks(
                Stream.concat(markedFields.stream(), Stream.of(entry)).toList());
    }

    /**
     * Reports whether nothing has been marked, which is the condition a caller tests before failing.
     *
     * @return {@code true} when no field carries a mark
     */
    public boolean isEmpty() {
        return markedFields.isEmpty();
    }

    /**
     * Translates the accumulation into the per-field detail a validation failure carries.
     *
     * <p>One entry per mark, in the same sequence, with no per-field message: the macro emitted none, and
     * the explanatory text belongs to the single summary line the thrower supplies.
     *
     * @return one carrier entry per marked field; empty when nothing was marked. The list is
     *         unmodifiable and built fresh on each call.
     */
    public List<ValidationException.FieldError> fieldErrors() {
        return markedFields.stream()
                .map(marked -> new ValidationException.FieldError(marked.field(), marked.bmsFieldId(),
                        fieldStateOf(marked.flagState()), null))
                .toList();
    }

    /**
     * Maps one legacy flag state onto the failure carrier's state.
     *
     * <p>Exhaustive over the two constants with no default arm, so adding a state to either enumeration
     * stops the build here rather than funnelling a new state into a catch-all. Under warnings-as-errors
     * compilation that is a build failure, which is the intended safeguard.
     *
     * @param flagState the legacy flag state, never {@code null}
     * @return the corresponding carrier state
     */
    private static ValidationException.FieldState fieldStateOf(final FlagState flagState) {
        return switch (flagState) {
            case BLANK -> ValidationException.FieldState.MISSING;
            case NOT_OK -> ValidationException.FieldState.INVALID;
        };
    }

    /**
     * One marked field: the property it is reported against, the legacy screen field it came from, and
     * which of the two legacy flag states raised it.
     *
     * @param field the request or response property name, never {@code null}
     * @param bmsFieldId the legacy screen field name, never {@code null}
     * @param flagState the legacy validation-flag state, never {@code null}
     * @since 1.0.0
     */
    public record MarkedField(String field, String bmsFieldId, FlagState flagState) {

        /**
         * Rejects a {@code null} for any component: without the property name a client cannot locate the
         * field, without the screen identifier the entry cannot be correlated with the legacy map, and
         * without the state nobody can tell an operator whether to supply a value or correct one.
         */
        public MarkedField {
            Objects.requireNonNull(field, "field must not be null");
            Objects.requireNonNull(bmsFieldId, "bmsFieldId must not be null");
            Objects.requireNonNull(flagState, "flagState must not be null");
        }
    }

    /** The two legacy validation-flag states the macro distinguished: left blank, and failed. */
    public enum FlagState {

        /** The field was left blank, which the macro additionally marked with a character. */
        BLANK,

        /** The field carried a value the validation cascade rejected. */
        NOT_OK
    }
}
