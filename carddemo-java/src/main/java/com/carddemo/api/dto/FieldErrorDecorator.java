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
 * The single Java replacement for the 39 textual expansions of the parameterized
 * {@code PROCEDURE DIVISION} macro {@code app/cpy/CSSETATY.cpy}: an immutable accumulation of
 * per-field error entries, grown one entry at a time by the one operation this type exposes,
 * {@link #mark(String, String, FlagState)}.
 *
 * <p>{@code CSSETATY} is not a data structure and does not become one. It is a 31-line macro
 * whose executable body is lines 18 to 27 and whose descriptive line 17 is corrupted, trailing
 * off into an unrelated screen field name; the body was translated and the commentary ignored.
 * The macro takes three substitution tokens - the validation flag to test, the screen field to
 * decorate and the map to decorate it on - and because expansion is textual, its 39 sites in
 * {@code app/cbl/COACTUPC.cbl} (lines 3208 to 3432) generate roughly 234 lines of
 * near-identical logic. Collapsing those into 39 invocations of one helper is the largest
 * single de-duplication in this migration, and this type is that helper. The third token is
 * dropped deliberately rather than by omission: all 39 sites substitute the same map, so the
 * token carries no information to preserve, which is why {@code mark} takes two identifiers and
 * a state rather than three identifiers and a state.
 *
 * <p><strong>Two error states, never one boolean.</strong> The macro fires when the field's
 * validation flag is either not-OK (lines 18 to 19) or blank, and the two are different
 * operator mistakes needing different remedies. In both states the macro wrote an error
 * highlight into the field's indicator sub-field, which becomes
 * {@link ErrorResponse.FieldState#INVALID}; in the blank state it <em>additionally</em> wrote a
 * marker character over the displayed value, which becomes
 * {@link ErrorResponse.FieldState#MISSING}. Both edits were 3270 rendering mechanisms with no
 * REST counterpart, so only the two states they signified survive: {@link FlagState} carries
 * exactly those two and nothing else, because a field that passed its edit is never marked at
 * all. The mapping is total and one-way and is expressed as a switch with no default clause, so
 * adding a constant to either enum becomes a compile error rather than a silently mishandled
 * case. Nothing here holds, names or emits a highlight value, an indicator sub-field, a marker
 * character, a control byte, a map coordinate, a field length or any other terminal
 * presentation detail; a client receives two named states and decides its own presentation.
 * Decision log entry D-33 records the two-state contract.
 *
 * <p>The output states are {@link ErrorResponse}'s own, so entries assembled here are handed
 * straight on with no further translation, which keeps the two types one contract instead of
 * two. The structurally identical state type declared by the validation-failure carrier in
 * {@code com.carddemo.exception} is a deliberate duplicate that this package must not
 * reference, because doing so would invert the module's layer direction; the global failure
 * handler in {@code com.carddemo.api} owns that translation.
 *
 * <p><strong>The re-entry gate belongs to the caller.</strong> Macro line 20 conjoined the
 * whole decoration with the program-context re-enter condition, so field-level errors were
 * absent on a first submission and appeared only once the operator had re-submitted. That gate
 * is reproduced by <em>invocation</em>, not by a parameter: {@code mark} marks unconditionally
 * whenever it is called, and the caller decides whether to call it at all. A first submission
 * is represented by {@link #none()} with nothing marked. Accepting a re-entry flag here would
 * put a presentation-lifecycle decision inside a value type and give all 39 call sites a fourth
 * argument to pass identically.
 *
 * <p><strong>Purity.</strong> {@code mark} is a pure function: it returns a new instance and
 * mutates nothing - not its receiver, not its arguments, not any shared state. This type
 * declares no field other than its single component, holds no static state and is deeply
 * immutable, so instances are freely shareable across threads and two instances built from the
 * same calls in the same sequence are equal. An accumulation is therefore <em>threaded</em>
 * rather than collected: each call yields the value the next proceeds from, and
 * {@link #fieldErrors()} is the terminal operation. There is deliberately no accumulator,
 * collector, cache, thread-local, singleton holder or registry of marked fields, because any of
 * them would reinstate the shared mutable state an immutable value type exists to avoid and
 * would let one request's errors leak into another's.
 *
 * <p><strong>The 39 pairings are the caller's data, not this type's.</strong> This type is
 * generic over a field name, a screen field identifier and a state, and holds no table, map,
 * array or enumeration of the 39 decorated fields. The pairings belong at the 39 call sites,
 * exactly as the 39 macro expansions belonged at 39 points in the legacy program; materialising
 * them here would rebuild in Java the duplication this type was created to delete.
 * {@code app/bms/COACTUP.bms} bounds the set: the map defines 43 unprotected input fields, all
 * 39 decorated identifiers are among them, and the four unprotected but undecorated fields are
 * {@code ACCTSID}, {@code AADDGRP}, {@code ACSTNUM} and {@code ACSGOVT}, for which no entry may
 * ever be invented.
 *
 * <p>Entries are appended in call sequence, with no re-ordering and no de-duplication. That is
 * faithful: the legacy expansions executed in source sequence and that sequence is itself
 * irregular - the state field is decorated between the two address lines and the postal code
 * ahead of city and country. Reproducing the caller's sequence rather than imposing one
 * preserves the irregularity without encoding it. Two further source oddities in the same range
 * are recorded as row 15 of the source anomaly register and must not be
 * "corrected" here: the descriptive lines at 3426 and 3431 are transposed and line 3375 repeats
 * the state label ahead of the postal-code expansion, so the substitution tokens govern in both
 * cases. Lines 3345 and 3369 state that the middle name and the second address line have no
 * edits coded; both are decorated by the macro, so this type must be able to mark them even
 * though no caller ever will (decision log entry D-34).
 *
 * <p>Illustrative call sequence - the response types it would feed are not part of this
 * contract and the fragment is not compilable as written:
 *
 * <pre>{@code
 * FieldErrorDecorator errors = FieldErrorDecorator.none();
 * if (flags.accountStatusBlank()) {
 *     errors = errors.mark("accountStatus", "ACSTTUS", FieldErrorDecorator.FlagState.BLANK);
 * }
 * if (flags.creditLimitNotOk()) {
 *     errors = errors.mark("creditLimit", "ACRDLIM", FieldErrorDecorator.FlagState.NOT_OK);
 * }
 * List<ErrorResponse.FieldError> perField = errors.isEmpty() ? List.of() : errors.fieldErrors();
 * }</pre>
 *
 * @param fieldErrors the entries accumulated so far, in the sequence they were marked. Never
 *                    {@code null} and never mutable: a {@code null} argument is normalised to
 *                    the empty list and any other argument is defensively copied. This is also
 *                    the terminal accessor, so the collection it returns is handed directly to
 *                    {@link ErrorResponse} with no further copying needed.
 * @since 1.0.0
 */
public record FieldErrorDecorator(List<ErrorResponse.FieldError> fieldErrors) {

    /**
     * Normalizes the accumulated entries so the component is never {@code null}, never aliased
     * to caller-owned state and never mutable.
     *
     * <p>A {@code null} collection becomes the empty immutable list rather than being stored,
     * so {@link #fieldErrors()} and {@link #isEmpty()} are always usable and no caller has to
     * test for {@code null}. Any other collection is defensively copied with
     * {@link List#copyOf(java.util.Collection)}, which detaches it from the caller and rejects
     * a {@code null} element - an entry with no state would be meaningless, and dropping it
     * silently would hide an error the client has to show.
     *
     * <p>The copy is what makes {@link #mark(String, String, FlagState)} safe to describe as
     * pure: the value threaded out of one call cannot be altered through a reference the
     * previous caller still holds.
     */
    public FieldErrorDecorator {
        fieldErrors = (fieldErrors == null) ? List.of() : List.copyOf(fieldErrors);
    }

    /**
     * The empty starting value of an accumulation: nothing marked.
     *
     * <p>This is also the first-submission shape. The legacy macro was gated on the
     * program-context re-enter condition, so on a first submission every input field was left
     * undecorated; here that is simply an accumulation on which
     * {@link #mark(String, String, FlagState)} was never called.
     *
     * <p>A fresh instance is returned rather than a shared constant. The type is immutable, so
     * a constant would be safe, but returning a new value keeps this class free of static
     * state altogether and makes that property verifiable by inspection. Equality is by
     * component, so two empty accumulations are equal regardless.
     *
     * @return an accumulation holding no entries
     */
    public static FieldErrorDecorator none() {
        return new FieldErrorDecorator(List.of());
    }

    /**
     * Records that one field is in one of the two legacy error states, returning a new
     * accumulation and leaving this one untouched.
     *
     * <p>This is the whole of the macro body's observable effect, and the only operation this
     * type performs. It records an outcome that has <em>already</em> been determined elsewhere:
     * it evaluates no rule, tests no range, inspects no character class, performs no lookup and
     * reads no source of its own. The caller has already run the validation cascade and has a
     * flag; this turns that flag into a client-visible entry.
     *
     * <p>The state translation is fixed: {@link FlagState#BLANK} becomes
     * {@link ErrorResponse.FieldState#MISSING} and {@link FlagState#NOT_OK} becomes
     * {@link ErrorResponse.FieldState#INVALID}. No per-field message is produced, which is
     * faithful - the legacy macro emitted no text of its own, because the explanatory text
     * lived in the single summary line that the caller owns.
     *
     * <p>No re-entry condition is tested. The caller decides whether the gate is open; see the
     * class documentation. Nothing is de-duplicated and nothing is re-ordered: marking the same
     * field twice yields two entries, because suppressing one would be a decision this type has
     * no standing to make.
     *
     * @param field       the name of the field in the request contract, as a client sent it -
     *                    for example the record component of the account-update request that
     *                    failed. Mandatory.
     * @param bmsFieldId  the legacy screen field identifier, carried through as an opaque label
     *                    so a response can be correlated with the map it derives from. It is a
     *                    label and nothing more - not a byte, not a coordinate, not a
     *                    presentation value - and a client may ignore it entirely. Mandatory.
     * @param flagState   which of the two legacy error states the field's validation flag is
     *                    in. Mandatory.
     * @return a new accumulation holding every entry of this one, in sequence, followed by the
     *         entry just marked
     * @throws NullPointerException if any argument is {@code null}. All three are load-bearing:
     *                              without the field name a client cannot locate the field,
     *                              without the identifier the entry cannot be correlated with
     *                              the legacy map, and without the state the client cannot tell
     *                              the operator whether to supply a value or correct one.
     */
    public FieldErrorDecorator mark(String field, String bmsFieldId, FlagState flagState) {
        Objects.requireNonNull(field, "field must not be null");
        Objects.requireNonNull(bmsFieldId, "bmsFieldId must not be null");
        Objects.requireNonNull(flagState, "flagState must not be null");

        ErrorResponse.FieldError entry =
                new ErrorResponse.FieldError(field, bmsFieldId, fieldStateOf(flagState));

        return new FieldErrorDecorator(
                Stream.concat(fieldErrors.stream(), Stream.of(entry)).toList());
    }

    /**
     * Tests whether nothing has been marked.
     *
     * <p>A caller uses this to decide whether to build an error body at all, which is the
     * decision the legacy program made when it chose between re-displaying a decorated screen
     * and committing the update. It is the inverse of
     * {@link ErrorResponse#hasFieldErrors()} on the response this accumulation feeds.
     *
     * <p>It is a presence test only. A caller that has to tell an operator what to do must read
     * {@link ErrorResponse.FieldError#state()} on each entry, because a field left blank and a
     * field filled in wrongly need different remedies.
     *
     * @return {@code true} when no entry has been marked
     */
    public boolean isEmpty() {
        return fieldErrors.isEmpty();
    }

    /**
     * Maps an incoming legacy flag state onto the client-visible state that
     * {@link ErrorResponse} publishes.
     *
     * <p>Written as an exhaustive switch expression with no default clause. Both enums have
     * exactly two constants, so every case is covered by name; should a constant ever be added
     * to either, this method stops compiling instead of quietly funnelling the new state into a
     * catch-all. Under the module's warnings-as-errors compilation that is a build failure,
     * which is the intended safeguard.
     *
     * <p>Kept private so that {@link #mark(String, String, FlagState)} remains the only
     * operation this type offers, and so that no caller can convert a flag without also
     * producing the entry that makes the conversion meaningful.
     *
     * @param flagState the legacy flag state, already known to be non-{@code null}
     * @return the corresponding published state
     */
    private static ErrorResponse.FieldState fieldStateOf(FlagState flagState) {
        return switch (flagState) {
            case BLANK -> ErrorResponse.FieldState.MISSING;
            case NOT_OK -> ErrorResponse.FieldState.INVALID;
        };
    }

    /**
     * The incoming legacy validation-flag state, with exactly two constants because the macro
     * tested exactly two conditions.
     *
     * <p>Each of the 39 decorated fields had a one-character validation flag whose condition
     * names distinguished a valid value, a value that failed its edit, and a value that was
     * never supplied. Only the latter two ever reached the macro, so only those two are modelled
     * here. There is deliberately no valid, none or unknown constant: a field that passed its
     * edit produces no entry at all, so a third constant would be unreachable state that every
     * caller and every client would have to handle for no reason.
     *
     * <p>Nested rather than declared as a top-level type, and nested here rather than added to
     * the shared enumeration package, because these two constants exist only as the input to
     * {@link FieldErrorDecorator#mark(String, String, FlagState)}. The shared enumeration
     * package models persisted and cross-layer domain values; a screen-validation flag state is
     * neither.
     *
     * @since 1.0.0
     */
    public enum FlagState {

        /**
         * The field was never supplied - the legacy blank flag, {@code 'B'} for the fields this
         * type serves and a space for the two key-filter flags that use the same idiom.
         *
         * <p>Translates to {@link ErrorResponse.FieldState#MISSING}. This is the state in which
         * the legacy macro applied both of its edits, and the remedy a client must offer the
         * operator is "supply a value".
         */
        BLANK,

        /**
         * The field was supplied but the value failed its edit - the legacy not-OK flag,
         * {@code '0'}.
         *
         * <p>Translates to {@link ErrorResponse.FieldState#INVALID}. This is the state in which
         * the legacy macro applied only its first edit, leaving the operator's own keystrokes in
         * place to be corrected, and the remedy a client must offer the operator is "correct the
         * value".
         */
        NOT_OK
    }
}
