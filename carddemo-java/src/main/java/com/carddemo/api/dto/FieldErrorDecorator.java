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
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The single Java replacement for the 39 textual expansions of the parameterized macro
 * {@code app/cpy/CSSETATY.cpy} in the legacy account-update program: an immutable accumulation of
 * per-field error entries grown one at a time through {@link #mark(String, String, FlagState)}.
 *
 * <p>The macro distinguished two states and so does this type, because the REST error contract has
 * to expose them separately: it colours a field when its validation flag is not-OK and
 * <em>additionally</em> writes a marker when the flag is specifically blank. {@link FlagState#BLANK}
 * therefore becomes {@code MISSING} and {@link FlagState#NOT_OK} becomes {@code INVALID}; collapsing
 * them into one boolean would lose the distinction the screen made.
 *
 * <p>The macro fired only when the program had been re-entered, so a caller populates this
 * accumulation only on re-submission - first entry carries no field errors even when fields are
 * empty. Entry order is preserved because it is the order the legacy expansions ran in, which is the
 * order an operator saw the fields marked.
 *
 * <p>Two of the 39 decorated fields - the middle name and the second address line - are decorated
 * but never validated, as the legacy source states in place. No validation constraint may be added
 * for them anywhere, because that would reject input the legacy system accepts.
 */
public record FieldErrorDecorator(List<MarkedField> markedFields) {
    public FieldErrorDecorator {
        markedFields = (markedFields == null) ? List.of() : List.copyOf(markedFields);
    }

    public static FieldErrorDecorator none() {
        return new FieldErrorDecorator(List.of());
    }

    /**
     * Records one marked field, returning a new accumulation; the receiver is unchanged.
     *
     * @param field the request/response property name the error is reported against
     * @param bmsFieldId the legacy screen field name, carried so a reader can trace the mapping
     * @param flagState the legacy validation-flag state this entry was raised from
     * @return a new accumulation carrying this entry after the existing ones
     */
    public FieldErrorDecorator mark(String field, String bmsFieldId, FlagState flagState) {
        MarkedField entry = new MarkedField(field, bmsFieldId, flagState);

        return new FieldErrorDecorator(
                Stream.concat(markedFields.stream(), Stream.of(entry)).toList());
    }

    public List<ErrorResponse.FieldError> fieldErrors() {
        return markedFields.stream()
                .map(marked -> new ErrorResponse.FieldError(marked.field(), marked.bmsFieldId(),
                        fieldStateOf(marked.flagState())))
                .toList();
    }

    public boolean isEmpty() {
        return markedFields.isEmpty();
    }

    private static ErrorResponse.FieldState fieldStateOf(FlagState flagState) {
        return switch (flagState) {
            case BLANK -> ErrorResponse.FieldState.MISSING;
            case NOT_OK -> ErrorResponse.FieldState.INVALID;
        };
    }

    public record MarkedField(String field, String bmsFieldId, FlagState flagState) {
        public MarkedField {
            Objects.requireNonNull(field, "field must not be null");
            Objects.requireNonNull(bmsFieldId, "bmsFieldId must not be null");
            Objects.requireNonNull(flagState, "flagState must not be null");
        }
    }

    /** The two legacy validation-flag states the macro distinguished: left blank, and failed. */
    public enum FlagState {
        BLANK,

        NOT_OK
    }
}
