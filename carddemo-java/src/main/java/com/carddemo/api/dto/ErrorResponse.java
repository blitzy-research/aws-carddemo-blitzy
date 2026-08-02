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

/**
 * Immutable, sanitized REST error body for the screen-derived endpoints: one summary message
 * alongside any number of independent <em>per-field</em> error states.
 *
 * <p>The 3270 layer had no error object. It decorated the individual input fields of a map in place,
 * through the parameterized macro {@code app/cpy/CSSETATY.cpy}, which fires when a field's validation
 * flag is either not-OK or blank &mdash; two different operator mistakes the legacy screen told apart
 * &mdash; applying an error highlight in both states and, in the blank state only, additionally
 * writing a marker character over the displayed value. Both edits are terminal rendering mechanisms
 * with no REST analogue, so this type exposes the two <em>states</em> and discards the mechanisms
 * entirely: no highlight value, indicator byte, flag character, map coordinate or presentation detail
 * appears anywhere. {@link FieldErrorDecorator} documents the macro translation in full.
 *
 * <p>The macro is expanded 39 times in {@code app/cbl/COACTUPC.cbl}, so 39 fields bound the universe
 * of entries this body can carry; the map's remaining unprotected fields are never decorated and no
 * entry may be invented for them. Two of the 39, the middle name and the second address line, are
 * decorated but never validated, and nothing here implies otherwise (decision log D-34).
 *
 * <p>Contract rules that a maintainer must not relax:
 * <ul>
 *   <li><b>No collapse.</b> The per-field states must never be flattened into one boolean, one string
 *       or an "is valid" flag: a client told only that a field is wrong cannot tell the operator
 *       whether to supply a value or to correct one.</li>
 *   <li><b>The state enum is duplicated on purpose.</b> The validation-failure carrier in the
 *       exception package declares a structurally identical two-constant type. The module's layering
 *       forbids {@code api.dto} from depending on that package, and the global failure handler owns
 *       the translation, so de-duplicating them would invert the dependency direction.</li>
 *   <li><b>The re-entry gate is not evaluated here.</b> Whether field errors may be populated at all
 *       is decided by the service from the re-enter state echoed on {@link NavigationContext}; this
 *       type only has to be constructible with none, which {@link #ErrorResponse(String)} is for
 *       (decision log D-33).</li>
 *   <li><b>Sanitized by construction.</b> No component here or on {@link FieldError} may carry stack
 *       detail, a failure class name, an internal path, a SQL fragment, a schema or table name, or a
 *       secret in any form.</li>
 *   <li><b>This type is the error body.</b> The standard problem-detail representation is deliberately
 *       not enabled for this module, so this type is neither a wrapper for it nor a stand-in.</li>
 *   <li><b>Nothing is trimmed.</b> Values are carried exactly as supplied, because the legacy screen
 *       and record fields are space-significant. No length constraint is imposed: a single maximum
 *       would be arbitrary, since summary widths differ per screen and per catalog entry.</li>
 * </ul>
 *
 * <p>The module omits {@code null} properties from the serialized form, so {@code message} and the
 * focus hint disappear when absent while {@code fieldErrors} is always present and is emitted as an
 * empty array when there are none, and a client never has to test it for {@code null}. Instances are
 * deeply immutable and safe to share across threads.
 *
 * @since 1.0.0
 */
public record ErrorResponse(String message,
                            List<FieldError> fieldErrors,
                            String focusScreenFieldId) {

    /**
     * Normalizes the field-error collection so the component is never {@code null}, never aliased to
     * caller-owned state and never mutable: a {@code null} collection becomes the empty immutable list,
     * and a supplied collection is defensively copied, which also rejects a {@code null} element -
     * silently dropping one would hide an error the client has to show.
     *
     * <p>The summary message and the focus hint are left exactly as supplied, including {@code null}
     * and including any leading or trailing space.
     */
    public ErrorResponse {
        fieldErrors = (fieldErrors == null) ? List.of() : List.copyOf(fieldErrors);
    }

    /**
     * Builds a response carrying a summary message and no field errors: the first-submission shape,
     * where the legacy screen showed the summary line and left every input field undecorated.
     *
     * @param message the summary message, or {@code null} when there is none
     */
    public ErrorResponse(String message) {
        this(message, List.of(), null);
    }

    /**
     * Builds a response carrying a summary message and per-field errors without a focus hint: the
     * re-entry shape. No sequencing and no de-duplication is applied, because the legacy emitted one
     * summary line and independently set field flags, and the service owns both.
     *
     * @param message     the summary message, or {@code null} when there is none
     * @param fieldErrors the per-field errors; {@code null} is treated as none
     */
    public ErrorResponse(String message, List<FieldError> fieldErrors) {
        this(message, fieldErrors, null);
    }

    /**
     * Tests whether this response carries any per-field error. Not a substitute for inspecting the
     * states: a caller that has to tell an operator what to do must read {@link FieldError#state()} on
     * each entry, because a blank field and a badly filled field need different remedies.
     *
     * @return {@code true} when at least one {@link FieldError} is present
     */
    public boolean hasFieldErrors() {
        return !fieldErrors.isEmpty();
    }

    /**
     * The per-field error state, with exactly two constants because the legacy screen distinguished
     * exactly two operator mistakes and only the state each decoration signified is exposed. There is
     * deliberately no third constant: a field with no error simply has no entry, so an "OK" or
     * "unknown" constant would be unreachable state that clients would have to handle for no reason.
     *
     * @since 1.0.0
     */
    public enum FieldState {

        /**
         * The field was left blank when a value was needed - the legacy blank-flag case, where the
         * macro also overwrote the displayed value with a marker character. The remedy to offer the
         * operator is "supply a value".
         */
        MISSING,

        /**
         * The field was filled in but the value failed its edit - the legacy not-OK case, where the
         * macro left the operator's own keystrokes on the screen. The remedy to offer the operator is
         * "correct the value".
         */
        INVALID
    }

    /**
     * One independent per-field error, standing for one firing of the {@code app/cpy/CSSETATY.cpy}
     * macro at one of its 39 expansion sites: the validation flag becomes {@link #state()}, the screen
     * field becomes {@link #screenFieldId()}, and the macro's map token is dropped because one REST
     * resource replaces the single map all 39 sites decorated.
     *
     * @param fieldName     the field's name in the request contract. Mandatory.
     * @param screenFieldId the legacy screen field identifier, an opaque label that lets a response be
     *                      correlated with the map it derives from - never a byte, a coordinate or a
     *                      presentation value, and a client may ignore it. Mandatory.
     * @param state         which of the two legacy states this field is in. Mandatory.
     * @param message       an optional explanation for this one field, omitted from the payload when
     *                      absent, which must never carry stack detail, a failure class name, an
     *                      internal path, a SQL fragment or a secret.
     * @since 1.0.0
     */
    public record FieldError(String fieldName,
                             String screenFieldId,
                             FieldState state,
                             String message) {

        /**
         * Rejects a {@code null} for any of the three mandatory components: without the name a client
         * cannot locate the field, without the screen identifier the entry cannot be correlated with
         * the legacy map, and without the state the client cannot tell the operator whether to supply
         * a value or correct one. Failing here is preferable to emitting an entry no client can act
         * on. The optional explanation is stored exactly as supplied, untrimmed.
         */
        public FieldError {
            Objects.requireNonNull(fieldName, "fieldName must not be null");
            Objects.requireNonNull(screenFieldId, "screenFieldId must not be null");
            Objects.requireNonNull(state, "state must not be null");
        }

        /**
         * Builds a field error with no per-field explanation: the direct counterpart of the legacy
         * macro invocation, which supplied a validation flag and a screen field and produced no text of
         * its own, because the legacy text lived in the single summary line.
         *
         * @param fieldName     the field's name in the request contract
         * @param screenFieldId the legacy screen field identifier
         * @param state         which of the two legacy states this field is in
         */
        public FieldError(String fieldName, String screenFieldId, FieldState state) {
            this(fieldName, screenFieldId, state, null);
        }
    }
}
