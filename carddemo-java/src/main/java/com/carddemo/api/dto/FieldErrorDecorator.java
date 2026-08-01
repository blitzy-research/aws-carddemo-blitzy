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
 * <p><strong>What is accumulated is neutral.</strong> An entry is a {@link MarkedField}: the
 * field name, the screen field identifier and the legacy flag state, and nothing else. That
 * triple names neither the REST error body nor the validation-failure carrier, so one
 * accumulation serves both tiers without either contract reaching into the other, and a service
 * that decorates need know only this type. Two projections read it, each owned by the tier it
 * feeds. {@link #fieldErrors()} renders the accumulation in the response contract's own terms,
 * which is why the published state is {@link ErrorResponse.FieldState} rather than a third
 * enumeration. {@code service/FieldErrorTranslationService} renders the same accumulation as the
 * validation failure a service throws, and is the only place that conversion happens. The
 * structurally identical state type declared by that carrier in {@code com.carddemo.exception}
 * therefore stays a deliberate duplicate this package must not reference: converting into it
 * belongs to the service layer and converting back out of it belongs to the global failure
 * handler in {@code com.carddemo.api}. Decision log entry DL-080 records the arrangement.
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
 * rather than collected: each call yields the value the next proceeds from, and a projection is
 * the terminal operation. There is deliberately no accumulator,
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
 * <p>Illustrative call sequence, as a validating service would run it - the collaborators it names
 * are not part of this contract and the fragment is not compilable as written:
 *
 * <pre>{@code
 * FieldErrorDecorator errors = FieldErrorDecorator.none();
 * if (flags.accountStatusBlank()) {
 *     errors = errors.mark("accountStatus", "ACSTTUS", FieldErrorDecorator.FlagState.BLANK);
 * }
 * if (flags.creditLimitNotOk()) {
 *     errors = errors.mark("creditLimit", "ACRDLIM", FieldErrorDecorator.FlagState.NOT_OK);
 * }
 * if (!errors.isEmpty()) {
 *     throw translator.toValidationException(errors, messages.accountUpdateRejected());
 * }
 * }</pre>
 *
 * @param markedFields the fields marked so far, in the sequence they were marked. Never
 *                     {@code null} and never mutable: a {@code null} argument is normalised to
 *                     the empty list and any other argument is defensively copied.
 * @since 1.0.0
 */
public record FieldErrorDecorator(List<MarkedField> markedFields) {

    /**
     * Normalizes the accumulated entries so the component is never {@code null}, never aliased
     * to caller-owned state and never mutable.
     *
     * <p>A {@code null} collection becomes the empty immutable list rather than being stored,
     * so {@link #markedFields()} and {@link #isEmpty()} are always usable and no caller has to
     * test for {@code null}. Any other collection is defensively copied with
     * {@link List#copyOf(java.util.Collection)}, which detaches it from the caller and rejects
     * a {@code null} element - an entry naming no field would be meaningless, and dropping it
     * silently would hide an error the client has to show.
     *
     * <p>The copy is what makes {@link #mark(String, String, FlagState)} safe to describe as
     * pure: the value threaded out of one call cannot be altered through a reference the
     * previous caller still holds.
     */
    public FieldErrorDecorator {
        markedFields = (markedFields == null) ? List.of() : List.copyOf(markedFields);
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
     * <p>This is the whole of the macro body's observable effect, and the only operation that grows
     * an accumulation. It records an outcome that has <em>already</em> been determined elsewhere:
     * it evaluates no rule, tests no range, inspects no character class, performs no lookup and
     * reads no source of its own. The caller has already run the validation cascade and has a
     * flag; this turns that flag into one {@link MarkedField}.
     *
     * <p>The flag state is recorded as given, in the legacy terms {@link FlagState} carries; it
     * is translated only when a projection is taken. No per-field message is produced, which is
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
     * @throws NullPointerException if any argument is {@code null}; see {@link MarkedField}
     */
    public FieldErrorDecorator mark(String field, String bmsFieldId, FlagState flagState) {
        MarkedField entry = new MarkedField(field, bmsFieldId, flagState);

        return new FieldErrorDecorator(
                Stream.concat(markedFields.stream(), Stream.of(entry)).toList());
    }

    /**
     * Projects the accumulation into the response contract's per-field detail.
     *
     * <p>One entry per marked field, in the sequence it was marked, with the legacy flag state
     * translated: {@link FlagState#BLANK} becomes {@link ErrorResponse.FieldState#MISSING} and
     * {@link FlagState#NOT_OK} becomes {@link ErrorResponse.FieldState#INVALID}. No per-field
     * message is attached, because none was ever produced. The returned list is unmodifiable and
     * is built fresh on each call, so it can be handed to {@link ErrorResponse} without further
     * copying.
     *
     * <p>This is the projection for a response assembled inside this layer. A service that
     * validates and then fails takes the other projection instead, through
     * {@code service/FieldErrorTranslationService}, so that its failure reaches a client along
     * the single path the global failure handler owns.
     *
     * @return the response-contract entries, in marking sequence; empty when nothing was marked
     */
    public List<ErrorResponse.FieldError> fieldErrors() {
        return markedFields.stream()
                .map(marked -> new ErrorResponse.FieldError(marked.field(), marked.bmsFieldId(),
                        fieldStateOf(marked.flagState())))
                .toList();
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
     * {@link MarkedField#flagState()} on each entry, because a field left blank and a field
     * filled in wrongly need different remedies.
     *
     * @return {@code true} when no entry has been marked
     */
    public boolean isEmpty() {
        return markedFields.isEmpty();
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
     * <p>Kept private so that no caller can convert a flag on its own: a state is translated only
     * as part of {@link #fieldErrors()}, which is what makes the conversion meaningful and keeps
     * it in one place.
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
     * One decorated field, expressed in the legacy macro's own terms: which field, which screen
     * field, and which of the two flag states it was in.
     *
     * <p>This is the neutral model both tiers read. It references no response type and no
     * exception type, so it can be produced by a service, carried across a layer boundary and
     * projected either way without a contract choosing the shape of the accumulation. It holds no
     * message, because the macro produced none, and it never holds a submitted field value, so a
     * failure on a credential field cannot echo what was typed (decision log entry D-16).
     *
     * @param field      the name of the field in the request contract, as a client sent it.
     *                   Mandatory.
     * @param bmsFieldId the legacy screen field identifier, carried through as an opaque label so
     *                   a response can be correlated with the map it derives from. Mandatory, and
     *                   never trimmed or folded: the legacy labels are fixed-width.
     * @param flagState  which of the two legacy error states the field's validation flag is in.
     *                   Mandatory.
     * @since 1.0.0
     */
    public record MarkedField(String field, String bmsFieldId, FlagState flagState) {

        /**
         * Rejects an absent component, because all three are load-bearing: without the field name
         * a client cannot locate the field, without the identifier the entry cannot be correlated
         * with the legacy map, and without the state the client cannot tell the operator whether
         * to supply a value or correct one.
         *
         * <p>This is the single enforcement point, so {@link #mark(String, String, FlagState)}
         * and any direct construction reject the same arguments with the same wording.
         *
         * @throws NullPointerException if any component is {@code null}
         */
        public MarkedField {
            Objects.requireNonNull(field, "field must not be null");
            Objects.requireNonNull(bmsFieldId, "bmsFieldId must not be null");
            Objects.requireNonNull(flagState, "flagState must not be null");
        }
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
