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
package com.carddemo.exception;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Transport for the legacy field-flag validation surface: raised when one or more submitted fields
 * fail the edits the COBOL online layer applied, carrying enough detail for the REST error contract
 * to reproduce the legacy screen decoration exactly.
 *
 * <p>The shape of this type is dictated by {@code app/cpy/CSSETATY.cpy}, which is not a data
 * structure but a parameterised {@code PROCEDURE DIVISION} macro with three substitution tokens -
 * {@code (TESTVAR1)} for the validation flag, {@code (SCRNVAR2)} for the BMS (3270) field name and
 * {@code (MAPNAME3)} for the map name - expanded by {@code COPY ... REPLACING} exactly 39 times in
 * {@code app/cbl/COACTUPC.cbl} between L3208 and L3432, always against the same map, with 39
 * distinct validation flags and 39 distinct BMS field names. Those 39 expansions collapse into a
 * single decorator call ({@code api/dto/FieldErrorDecorator}); this exception is the transport for
 * the errors that decorator produces.</p>
 *
 * <p>Three properties of the macro are the contract this class exists to preserve: it fires only
 * when the re-enter condition is set, so per-field error states are populated only on
 * re-submission and never on first presentation; it highlights the field - the CICS
 * {@code DFHRED} attribute - when the flag is <em>either</em> not-OK <em>or</em> blank; and it
 * <em>additionally</em> writes a {@code '*'} marker when the flag is specifically blank, and not
 * when it is merely not-OK. The third property distinguishes two error states that share the same
 * highlight, so a boolean is insufficient and {@link FieldState} carries exactly two constants.
 * Recorded as decision log entry D-33. Re-entry gating itself is deliberately not modelled here;
 * the caller owns it, exactly as the legacy program owned its re-enter flag.</p>
 *
 * <p>This class is a <strong>generic carrier</strong>: a field name, a legacy BMS field id, a
 * {@link FieldState} and a caller-supplied message, and nothing else. It holds no catalogue of
 * field messages and synthesises no wording of its own, because the legacy message literals belong
 * to the service and message-catalogue layer. It also never carries a submitted field
 * <em>value</em>, so a failure on a credential field cannot echo what was supplied - recorded as
 * decision log entry D-16.</p>
 *
 * <p><strong>Warning for DTO and service authors.</strong> Two of the 39 decorated fields are
 * decorated for display but never actually validated: the middle name (BMS field {@code ACSMNAM},
 * noted at {@code app/cbl/COACTUPC.cbl} L3345) and the second address line (BMS field
 * {@code ACSADL2}, noted at L3369). No validation constraint may be attached to either field, since
 * adding one would reject input the legacy system accepts. Recorded as decision log entry D-34.</p>
 *
 * <p>Instances are immutable: the field-error collection is defensively copied on construction and
 * exposed unmodifiable, and there are no setters. That collection is {@code transient} because
 * {@link FieldError} is intentionally not serialisable, so a {@code ValidationException} survives
 * Java serialisation without its per-field detail and {@link #fieldErrors()} then reports an empty
 * list rather than {@code null}. The message and the cause, which {@code Throwable} itself
 * serialises, are unaffected.</p>
 */
public class ValidationException extends RuntimeException {

    /** Stable serial version: {@code Throwable} is serialisable, so this must be explicit. */
    private static final long serialVersionUID = 1L;

    /**
     * The two distinct error states a validated field can be in.
     *
     * <p>The legacy edit flag is a single character with three states, declared as the same
     * triple for every validated field - see {@code app/cbl/COACTUPC.cbl} L57-L75 and
     * L184-L199: a valid sentinel, a not-OK value of {@code '0'} and a blank value of
     * {@code 'B'}. Only the two error states need representing here, because a field that
     * passed its edits produces no error entry at all. This enum therefore has exactly two
     * constants and no {@code VALID} member.</p>
     *
     * <p>The two account and customer key-filter flags declared at L184-L190 are the one
     * variation in the estate: they use a space rather than {@code 'B'} for their blank
     * state. Both spellings of blank map to {@link #MISSING}.</p>
     */
    public enum FieldState {

        /**
         * The field was not supplied - the legacy blank flag, {@code 'B'} (a space for the
         * two key-filter flags). The legacy screen highlights the field <em>and</em> writes
         * a {@code '*'} marker.
         */
        MISSING,

        /**
         * The field was supplied but failed its edit - the legacy not-OK flag, {@code '0'}.
         * The legacy screen highlights the field only, with no {@code '*'} marker.
         */
        INVALID
    }

    /**
     * One field-level validation error.
     *
     * <p>Immutable, and deliberately not serialisable: the per-field detail is not part of
     * the serialised form of the enclosing exception. The message is whatever the caller
     * supplied - nothing is synthesised here - and no submitted field value is ever held.</p>
     *
     * @param field      the Java or DTO property name of the field that failed; the stable
     *                   identifier a JSON consumer binds to
     * @param bmsFieldId the legacy BMS (3270) field name that the {@code CSSETATY} macro
     *                   decorated, for example {@code ACSTTUS} for the account status. May
     *                   be supplied as {@code null} or empty where a validation is not
     *                   screen-bound; {@code null} is normalised to the empty string so the
     *                   accessor never returns {@code null}
     * @param state      which of the two legacy error states the field is in. Mandatory: the
     *                   legacy flag is never absent when the macro fires, so there is no third
     *                   state to represent and a {@code null} is rejected rather than guessed at
     * @param message    the caller-supplied message, owned by the service or message
     *                   catalogue that raised the failure
     */
    public record FieldError(String field, String bmsFieldId, FieldState state, String message) {

        /**
         * Normalises the optional legacy BMS field id to the empty string when it is absent,
         * so consumers never have to null-check it, and rejects an absent state outright.
         *
         * <p>The state is mandatory because the legacy contract has exactly two meaningful
         * values and the macro at {@code app/cpy/CSSETATY.cpy} L18-27 fires only when the flag
         * holds one of them: it decorates the field when the flag is not-OK <em>or</em> blank,
         * and writes the {@code '*'} marker only for blank. A {@code null} here would therefore
         * describe a state the legacy screen cannot be in, and admitting it would force every
         * consumer - the REST error mapper above all - to invent a value on the producer's
         * behalf. Inventing one is worse than failing: MISSING tells the operator to supply a
         * value they may already have supplied, and INVALID tells them to correct a value they
         * may never have entered. Rejecting the {@code null} at construction keeps the two-state
         * translation total and locates the defect in the producer that omitted the state.
         *
         * @throws NullPointerException if {@code state} is {@code null}
         */
        public FieldError {
            Objects.requireNonNull(state, "state must not be null: the legacy field flag is"
                    + " either MISSING or INVALID and is never absent");
            bmsFieldId = (bmsFieldId == null) ? "" : bmsFieldId;
        }
    }

    /**
     * The per-field detail, already defensively copied and unmodifiable. Never {@code null}
     * as constructed; {@code transient}, so it is {@code null} after deserialisation, which
     * {@link #fieldErrors()} absorbs.
     */
    private final transient List<FieldError> fieldErrors;

    /**
     * Creates a summary-level failure that carries no per-field detail.
     *
     * <p>{@link #fieldErrors()} returns an empty list for exceptions built this way.</p>
     *
     * @param message the caller-supplied message; passed through unchanged
     */
    public ValidationException(String message) {
        this(message, List.of());
    }

    /**
     * Creates a failure describing exactly one field, the common case when a single edit
     * fails.
     *
     * <p>The supplied message becomes both the field-level message and this exception's
     * message; no additional wording is generated.</p>
     *
     * @param field      the Java or DTO property name of the field that failed
     * @param bmsFieldId the legacy BMS (3270) field name, or {@code null} when the
     *                   validation is not screen-bound
     * @param state      MISSING when the field was not supplied, INVALID when it was
     *                   supplied but failed its edit; must not be {@code null}
     * @param message    the caller-supplied message; passed through unchanged
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public ValidationException(String field, String bmsFieldId, FieldState state, String message) {
        this(message, List.of(new FieldError(field, bmsFieldId, state, message)));
    }

    /**
     * Creates a failure carrying per-field detail for one or more fields, which is how the
     * account-update screen reports its 39 decorated fields.
     *
     * @param message     the caller-supplied message; passed through unchanged
     * @param fieldErrors the per-field detail; defensively copied, {@code null} elements are
     *                    dropped, and {@code null} is treated as no detail at all
     */
    public ValidationException(String message, List<FieldError> fieldErrors) {
        super(message);
        this.fieldErrors = immutableCopy(fieldErrors);
    }

    /**
     * Creates a failure carrying per-field detail and the lower-level cause it was wrapped
     * from.
     *
     * @param message     the caller-supplied message; passed through unchanged
     * @param fieldErrors the per-field detail; defensively copied, {@code null} elements are
     *                    dropped, and {@code null} is treated as no detail at all
     * @param cause       the underlying cause, passed straight through to
     *                    {@code Throwable}; may be {@code null}
     */
    public ValidationException(String message, List<FieldError> fieldErrors, Throwable cause) {
        super(message, cause);
        this.fieldErrors = immutableCopy(fieldErrors);
    }

    /**
     * Returns the per-field detail, in the order the caller supplied it.
     *
     * <p>Never {@code null}: an exception with no per-field detail, and an exception revived
     * by deserialisation, both report an empty list. The returned list is unmodifiable, so
     * attempting to change it raises {@code UnsupportedOperationException}.</p>
     *
     * @return an unmodifiable, possibly empty list of field errors
     */
    public List<FieldError> fieldErrors() {
        return (fieldErrors == null) ? List.of() : fieldErrors;
    }

    /**
     * Reports whether any per-field detail is present, letting the REST error mapper choose
     * between a field-level and a summary-level response without inspecting the list.
     *
     * @return {@code true} when at least one field error is carried
     */
    public boolean hasFieldErrors() {
        return !fieldErrors().isEmpty();
    }

    /**
     * Copies the supplied field errors into an unmodifiable list, dropping {@code null}
     * elements and treating a {@code null} or empty argument as no detail at all. This is
     * the single funnel every constructor uses, so the immutability guarantee cannot be
     * bypassed and later mutation of the caller's list cannot be observed here.
     *
     * @param fieldErrors the caller's list, which may be {@code null} or hold {@code null}
     *                    elements
     * @return an unmodifiable, never {@code null} copy
     */
    private static List<FieldError> immutableCopy(List<FieldError> fieldErrors) {
        if (fieldErrors == null || fieldErrors.isEmpty()) {
            return List.of();
        }
        List<FieldError> copy = new ArrayList<>(fieldErrors.size());
        for (FieldError fieldError : fieldErrors) {
            if (fieldError != null) {
                copy.add(fieldError);
            }
        }
        return List.copyOf(copy);
    }
}
