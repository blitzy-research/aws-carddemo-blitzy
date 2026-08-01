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
 * Immutable, sanitized REST error body for the screen-derived endpoints of the migrated
 * CardDemo application. It carries exactly one summary message alongside {@code N}
 * independent <em>per-field</em> error states.
 *
 * <p>The 3270 presentation layer had no error object at all: it decorated the individual input
 * fields of a map in place, through the parameterized {@code PROCEDURE DIVISION} macro
 * {@code app/cpy/CSSETATY.cpy}, whose executable body is lines 18 to 27. The body fires when a
 * field's validation flag is either not-OK or blank - two different operator mistakes that the
 * legacy screen told apart - writing an error highlight into the field's indicator sub-field in
 * both states and, in the blank state only, additionally writing a marker character over the
 * displayed value. Both edits are 3270 rendering mechanisms with no REST analogue, so this type
 * exposes the two <em>states</em> ({@link FieldState#INVALID} and {@link FieldState#MISSING})
 * and discards the mechanisms entirely: it holds no highlight value, indicator byte, flag
 * character, map coordinate or terminal presentation detail of any kind.
 * {@link FieldErrorDecorator} documents the macro translation in full.
 *
 * <p>The macro is expanded exactly 39 times in {@code app/cbl/COACTUPC.cbl}, between lines 3208
 * and 3432, so 39 fields bound the universe of entries this body can carry.
 * {@code app/bms/COACTUP.bms} corroborates the set: the map defines 43 unprotected input fields,
 * all 39 decorated identifiers are among them, and the four unprotected-but-undecorated fields
 * are {@code ACCTSID}, {@code AADDGRP}, {@code ACSTNUM} and {@code ACSGOVT}, for which no entry
 * may ever be invented. Two of the 39 - the middle name (line 3345) and the second address line
 * (line 3369) - are decorated but never validated, and nothing in this type implies otherwise
 * (decision log entry D-34).
 *
 * <p><strong>Contract rules.</strong>
 *
 * <ul>
 *   <li><b>No collapse.</b> The per-field states must never be flattened into a single overall
 *       boolean, a single message string or an "is valid" flag. A client told only "this field
 *       is wrong" cannot tell the operator whether to supply a value or to correct one.</li>
 *   <li><b>The state enum is duplicated on purpose.</b> The validation-failure carrier in the
 *       {@code com.carddemo.exception} package declares its own structurally identical
 *       two-constant state type. The duplication is deliberate: the module's layering forbids
 *       {@code api.dto} from depending on the failure-carrier package, and the global failure
 *       handler one level up in {@code com.carddemo.api} owns the translation. De-duplicating
 *       the two enums would invert the dependency direction.</li>
 *   <li><b>The re-entry gate is not evaluated here.</b> Whether field errors may be populated
 *       at all is decided by the service from the re-enter condition echoed back on
 *       {@link NavigationContext}; this type only has to be constructible with no field errors
 *       whatsoever, which is what {@link #ErrorResponse(String)} is for (decision log entry
 *       D-33).</li>
 *   <li><b>Sanitized by construction.</b> No component of this type, and no component of
 *       {@link FieldError}, may ever carry stack detail, a failure class name, an internal file
 *       path, a SQL fragment, a schema or table name, a secret, a password or a password
 *       digest.</li>
 *   <li><b>This type is the error body.</b> The standard problem-detail representation is
 *       deliberately switched off for this module - {@code application.yml} declares no
 *       problem-detail setting at all - so this type is neither a wrapper for it nor a stand-in
 *       to be replaced by it.</li>
 *   <li><b>Nothing is trimmed.</b> Values are carried exactly as supplied. Legacy fixed-width
 *       screen and record fields are space-significant, so no component is trimmed, case-folded
 *       or truncated, and no length constraint is imposed - a single maximum would in any case
 *       be arbitrary, because the estate's summary message widths differ per screen and per
 *       catalog entry.</li>
 * </ul>
 *
 * <p><strong>Wire contract.</strong> The module configures
 * {@code jackson.default-property-inclusion: non_null} globally, which omits {@code null}
 * values and nothing else. Combined with the normalization in the canonical constructor that
 * fixes the payload shape: {@code fieldErrors} is always present and is emitted as an empty
 * array when there are none, so a client never has to test it for {@code null}, while
 * {@code message} and {@code focusScreenFieldId} are omitted when absent. Instances are deeply
 * immutable and therefore safe to share across threads.
 *
 * @param message            the single summary message for the whole response, or
 *                           {@code null} when there is none. The legacy screens showed one
 *                           summary line - a first-error-wins message gate - alongside any
 *                           number of independently flagged fields, and that shape is
 *                           reproduced here. Composing this text is the service's job, not
 *                           this type's.
 * @param fieldErrors        the independent per-field errors, never {@code null} and never
 *                           mutable. Empty means "no field-level error", which is also the
 *                           first-submission case, because the legacy macro was gated on
 *                           re-entry.
 * @param focusScreenFieldId the legacy screen field identifier that input focus should be
 *                           placed on, or {@code null} when the response gives no hint. It
 *                           is an opaque label only, exactly as in {@link FieldError}.
 * @since 1.0.0
 */
public record ErrorResponse(String message,
                            List<FieldError> fieldErrors,
                            String focusScreenFieldId) {

    /**
     * Normalizes the field-error collection so that the component is never {@code null},
     * never aliased to caller-owned state, and never mutable.
     *
     * <p>A {@code null} collection becomes the empty immutable list rather than being
     * stored, so every accessor and every serialized payload sees a usable collection. A
     * non-{@code null} collection is defensively copied with {@link List#copyOf(java.util.Collection)},
     * which both detaches it from the caller and rejects a {@code null} element - a field
     * error with no state would be meaningless and silently dropping it would hide an error
     * the client has to show.
     *
     * <p>{@code message} and {@code focusScreenFieldId} are deliberately left exactly as
     * supplied, including {@code null} and including any leading or trailing space, because
     * the legacy fields they derive from are fixed-width and space-significant.
     */
    public ErrorResponse {
        fieldErrors = (fieldErrors == null) ? List.of() : List.copyOf(fieldErrors);
    }

    /**
     * Builds a response that carries a summary message and no field errors at all.
     *
     * <p>This is the first-submission shape. The legacy macro was gated on the
     * program-context re-enter condition, so on a first submission the screen showed the
     * summary line and left every input field undecorated.
     *
     * @param message the summary message, or {@code null} when there is none
     */
    public ErrorResponse(String message) {
        this(message, List.of(), null);
    }

    /**
     * Builds a response that carries a summary message and per-field errors, without a
     * focus hint.
     *
     * <p>This is the re-entry shape: one summary line plus every field that the validation
     * cascade flagged, in whatever sequence the caller assembled them. No sequencing and no
     * de-duplication is applied here, because the legacy emitted one summary message and
     * {@code N} independently set field flags and the service owns both.
     *
     * @param message     the summary message, or {@code null} when there is none
     * @param fieldErrors the per-field errors; {@code null} is treated as none
     */
    public ErrorResponse(String message, List<FieldError> fieldErrors) {
        this(message, fieldErrors, null);
    }

    /**
     * Tests whether this response carries any per-field error.
     *
     * <p>This is a convenience test over {@link #fieldErrors()} for callers that only need
     * to branch on presence, such as a handler choosing a status code. It is <em>not</em> a
     * substitute for inspecting the per-field states: a caller that has to tell an operator
     * what to do must read {@link FieldError#state()} on each entry, because a blank field
     * and a badly filled field need different remedies.
     *
     * @return {@code true} when at least one {@link FieldError} is present
     */
    public boolean hasFieldErrors() {
        return !fieldErrors.isEmpty();
    }

    /**
     * The per-field error state, with exactly two constants because the legacy screen
     * distinguished exactly two operator mistakes.
     *
     * <p>Both constants map to a decoration that the {@code app/cpy/CSSETATY.cpy} macro
     * performed on the 3270 map. The <em>mechanism</em> of each decoration is discarded and
     * only the state it signified is exposed, so nothing here names a highlight value, an
     * indicator byte or a flag character.
     *
     * <p>There is deliberately no third constant. A field with no error simply has no entry
     * in {@link ErrorResponse#fieldErrors()}, so an "OK", "none" or "unknown" constant would
     * be unreachable state that clients would have to handle for no reason.
     *
     * @since 1.0.0
     */
    public enum FieldState {

        /**
         * The field was left blank when a value was needed.
         *
         * <p>Corresponds to the legacy blank-flag case, in which the macro applied the error
         * highlight <em>and additionally</em> overwrote the field's displayed-value position
         * with a single-character flag. The remedy the client must offer the operator is
         * "supply a value".
         */
        MISSING,

        /**
         * The field was filled in, but the value failed its edit.
         *
         * <p>Corresponds to the legacy not-OK case, in which the macro applied the error
         * highlight and left the operator's own keystrokes on the screen so they could be
         * corrected. The remedy the client must offer the operator is "correct the value".
         */
        INVALID
    }

    /**
     * One independent per-field error.
     *
     * <p>Each instance stands for one firing of the {@code app/cpy/CSSETATY.cpy} macro at
     * one of its 39 expansion sites in {@code app/cbl/COACTUPC.cbl}. The macro's three
     * substitution tokens map onto this record as follows: the validation flag becomes
     * {@link #state()}, the screen field becomes {@link #screenFieldId()}, and the map is
     * dropped because a single REST resource replaces the single map the 39 sites all
     * decorated.
     *
     * @param fieldName      the name of the field in the request contract, for example the
     *                       property a client sent. Mandatory.
     * @param screenFieldId  the legacy screen field identifier, carried as an opaque label
     *                       so that a response can be correlated with the map it derives from.
     *                       It is a label and nothing more - it is not a byte, not a
     *                       coordinate and not a terminal presentation value - and a client
     *                       may ignore it entirely. Mandatory.
     * @param state          which of the two legacy states this field is in. Mandatory.
     * @param message        an optional human-readable explanation for this one field, or
     *                       {@code null}. It is omitted from the payload when absent, and
     *                       it must never carry stack detail, a failure class name, an
     *                       internal path, a SQL fragment or a secret.
     * @since 1.0.0
     */
    public record FieldError(String fieldName,
                             String screenFieldId,
                             FieldState state,
                             String message) {

        /**
         * Rejects a {@code null} for any of the three mandatory components.
         *
         * <p>{@code fieldName}, {@code screenFieldId} and {@code state} are all load-bearing:
         * without the name a client cannot locate the field, without the identifier the
         * entry cannot be correlated with the legacy map, and without the state the client
         * cannot tell the operator whether to supply a value or to correct one. Failing here
         * is preferable to emitting an entry a client cannot act on.
         *
         * <p>{@code message} is optional and is stored exactly as supplied, untrimmed.
         */
        public FieldError {
            Objects.requireNonNull(fieldName, "fieldName must not be null");
            Objects.requireNonNull(screenFieldId, "screenFieldId must not be null");
            Objects.requireNonNull(state, "state must not be null");
        }

        /**
         * Builds a field error with no per-field explanation.
         *
         * <p>This three-component shape is the direct counterpart of the legacy macro
         * invocation, which supplied a validation flag and a screen field and produced no
         * text of its own: the legacy text lived in the single summary line, not on the
         * field.
         *
         * @param fieldName     the name of the field in the request contract
         * @param screenFieldId the legacy screen field identifier
         * @param state         which of the two legacy states this field is in
         */
        public FieldError(String fieldName, String screenFieldId, FieldState state) {
            this(fieldName, screenFieldId, state, null);
        }
    }
}
