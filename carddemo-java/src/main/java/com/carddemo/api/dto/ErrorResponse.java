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
 * <h2>Legacy antecedent</h2>
 *
 * <p>The 3270 presentation layer had no error object at all. It decorated the individual
 * input fields of a map in place, through the parameterized {@code PROCEDURE DIVISION}
 * macro {@code app/cpy/CSSETATY.cpy}. Lines 17-27 of that member are the whole macro;
 * lines 18-27 are its executable body and are the authority for this type. Line 17 is a
 * corrupted descriptive line - it trails off into an unrelated screen field name - so the
 * body, not the commentary, was translated. The macro takes three substitution tokens: the
 * validation flag to test, the screen field to decorate, and the map to decorate it on.
 *
 * <p>The body has exactly three semantic properties, and all three are encoded here:
 *
 * <ol>
 *   <li><b>Two distinct states, not one boolean</b> (lines 18-19). The macro fires when the
 *       field's validation flag is either <em>not-OK</em> or <em>blank</em>. Those are two
 *       different operator mistakes and the legacy screen told them apart.</li>
 *   <li><b>A re-entry gate</b> (line 20). The macro fires only when the program-context
 *       re-enter condition is set, so field-level errors were <em>absent</em> on a first
 *       submission and appeared only once the operator had re-submitted the screen.</li>
 *   <li><b>Two different in-place edits of the map</b> (lines 21-26). In both states the
 *       macro writes an error highlight value into the field's indicator sub-field, which
 *       is the {@link FieldState#INVALID} case. In the blank state <em>only</em>, it
 *       additionally writes a single-character flag into the field's displayed-value
 *       position, overwriting whatever was on the screen, which is the
 *       {@link FieldState#MISSING} case.</li>
 * </ol>
 *
 * <p>Both of those edits are 3270 rendering mechanisms with no REST analogue. This type
 * therefore exposes the two <em>states</em> and discards the mechanisms entirely: it holds
 * no highlight value, no indicator byte, no flag character, no overwritten display value,
 * no map coordinate and no terminal presentation detail of any kind.
 *
 * <h2>The 39 decorated fields</h2>
 *
 * <p>The macro is expanded exactly 39 times in {@code app/cbl/COACTUPC.cbl}, between lines
 * 3208 and 3432, always against the same map, with 39 distinct validation flags and 39
 * distinct screen field identifiers. {@code app/bms/COACTUP.bms} was parsed to corroborate
 * the expansion set: that map defines 43 unprotected input fields, every one of the 39
 * decorated identifiers is among them, and the four unprotected-but-undecorated fields are
 * {@code ACCTSID}, {@code AADDGRP}, {@code ACSTNUM} and {@code ACSGOVT}. No decoration
 * entry may be invented for those four.
 *
 * <p>Four source oddities in that range were verified and are recorded so a future reader
 * does not "fix" this contract by trusting the wrong half of the source:
 *
 * <ul>
 *   <li>A hand-written equivalent of the macro sits commented out just above the first
 *       expansion, at lines 3198-3205 within the banner that starts at line 3196. It is
 *       inactive and stays inactive.</li>
 *   <li>Three descriptive lines are mislabelled. Line 3375 names one field but introduces
 *       the postal-code expansion, and lines 3426 and 3431 are a transposed pair. Following
 *       the substitution tokens rather than the commentary gives the correct final two
 *       mappings: primary-cardholder decorates {@code ACSPFLG} and the electronic-transfer
 *       account identifier decorates {@code ACSEFTC}.</li>
 *   <li>The expansion sequence itself is irregular: the state field is expanded between the
 *       two address lines, and the postal code is expanded ahead of city and country. That
 *       sequence is described exactly as it is, and this type imposes no sequence of its
 *       own on {@link #fieldErrors()}.</li>
 *   <li>Two of the 39 fields are decorated but never actually validated - the source says
 *       so directly at line 3345 for the middle name and at line 3369 for the second
 *       address line. Nothing in this type implies that either field is validated, and no
 *       constraint may be attached to them anywhere in the request contract.</li>
 * </ul>
 *
 * <h2>Contract rules</h2>
 *
 * <ul>
 *   <li><b>No collapse.</b> The per-field states must never be flattened into a single
 *       overall boolean, a single message string or an "is valid" flag. A client that is
 *       told only "this field is wrong" cannot tell the operator whether to supply a value
 *       or to correct one.</li>
 *   <li><b>The state enum is duplicated on purpose.</b> The validation-failure carrier in
 *       the {@code com.carddemo.exception} package declares its own structurally identical
 *       two-constant state type. The two are structurally identical by design and
 *       semantically identical by contract, and the duplication is deliberate: the layering
 *       of this module forbids {@code api.dto} from depending on the failure-carrier
 *       package. The module's global failure handler, which sits one level up in
 *       {@code com.carddemo.api}, is the component that translates a caught validation
 *       failure into an instance of this type - the translation happens there, never here.
 *       Do not "de-duplicate" the two enums; doing so inverts the dependency direction and
 *       breaks the build's layer discipline.</li>
 *   <li><b>The re-entry gate is not evaluated here.</b> Whether field errors may be
 *       populated at all is decided by the service, from the re-enter condition echoed back
 *       in {@code NavigationContext}. This type only has to be constructible with no field
 *       errors whatsoever, which is what {@link #ErrorResponse(String)} is for.</li>
 *   <li><b>Sanitized by construction.</b> No component of this type, and no component of
 *       {@link FieldError}, may ever carry stack detail, a failure class name, an internal
 *       file path, a SQL fragment, a schema or table name, a secret, a password or a
 *       password digest.</li>
 *   <li><b>This type is the error body.</b> The standard problem-detail representation is
 *       deliberately switched off for this module - {@code application.yml} declares no
 *       problem-detail setting at all - so this type is neither a wrapper for it nor a
 *       stand-in that will later be replaced by it.</li>
 *   <li><b>Nothing is trimmed.</b> Values are carried exactly as supplied. Legacy
 *       fixed-width screen and record fields are space-significant, so no component is
 *       trimmed, case-folded or truncated, and no length constraint is imposed. A single
 *       maximum length would in any case be arbitrary here: the estate's summary message
 *       widths differ per screen and per catalog entry.</li>
 * </ul>
 *
 * <h2>Wire contract</h2>
 *
 * <p>The module configures {@code jackson.default-property-inclusion: non_null} globally,
 * which omits {@code null} values and nothing else. Combined with the normalization in the
 * canonical constructor this fixes the payload shape:
 *
 * <ul>
 *   <li>{@code fieldErrors} is <em>always</em> present, and is emitted as an empty array
 *       when there are no field errors. A client never has to test it for {@code null}.</li>
 *   <li>{@code message} is omitted when absent.</li>
 *   <li>{@code focusScreenFieldId} is omitted when absent.</li>
 * </ul>
 *
 * <p>Instances are deeply immutable and therefore safe to share across threads.
 *
 * <h2>Provenance</h2>
 *
 * <p>Behaviour cited, never transcribed, from the CardDemo COBOL estate at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
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
     *                       so that a response stays traceable to the map it derives from.
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
         * entry loses its traceability to the legacy map, and without the state the client
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
