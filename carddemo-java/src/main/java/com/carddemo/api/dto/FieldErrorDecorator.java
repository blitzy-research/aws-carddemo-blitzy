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
 * <h2>Why this type exists</h2>
 *
 * <p>{@code app/cpy/CSSETATY.cpy} is not a data structure and does not become one. It is a
 * 31-line macro whose executable body is lines 18 to 27 and whose descriptive line 17 is
 * corrupted - that line trails off into a screen field name unrelated to the macro, so the
 * body was translated and the commentary was ignored. The macro takes three substitution
 * tokens: the validation flag to test, the screen field to decorate, and the map to decorate
 * it on. Because a macro expansion is textual, its 39 expansion sites in
 * {@code app/cbl/COACTUPC.cbl} generate roughly 234 lines of near-identical logic. Collapsing
 * those into 39 invocations of one small helper is the largest single de-duplication in this
 * migration, and this type is that helper.
 *
 * <p>The third token is dropped deliberately, not by omission. All 39 sites substitute the
 * same map, so the map token carries no information to preserve: a single REST resource
 * replaces the single map that all 39 expansions decorated. That is why
 * {@link #mark(String, String, FlagState)} takes two identifiers and a state rather than
 * three identifiers and a state.
 *
 * <h2>What the macro body did, and what survives here</h2>
 *
 * <p>The body has exactly four semantic properties, and each one is resolved explicitly:
 *
 * <ol>
 *   <li><b>Two error states, never one boolean</b> (lines 18 to 19). The macro fires when the
 *       field's validation flag is either <em>not-OK</em> or <em>blank</em>. Those are two
 *       different operator mistakes needing two different remedies, and the legacy screen told
 *       them apart. {@link FlagState} carries exactly those two and nothing else: a field that
 *       passed its edit is never marked at all, so there is no third state for a client to
 *       handle.</li>
 *   <li><b>A gate this type does not evaluate</b> (line 20). The whole decoration was
 *       conjoined with the program-context re-enter condition. That condition is the caller's
 *       to test - see the section below - and it appears nowhere in this type.</li>
 *   <li><b>An edit applied in both states</b> (lines 21 to 22). The macro wrote an error
 *       highlight value into the field's indicator sub-field on the map's output group. That
 *       is the {@link ErrorResponse.FieldState#INVALID} case.</li>
 *   <li><b>A second edit applied in the blank state only</b> (lines 23 to 26). In the blank
 *       state the macro <em>additionally</em> wrote a single-character flag into the field's
 *       displayed-value position, overwriting whatever the operator could see there. That is
 *       the {@link ErrorResponse.FieldState#MISSING} case, and it is why blank and not-OK are
 *       two states rather than one.</li>
 * </ol>
 *
 * <p>Both edits were 3270 rendering mechanisms with no REST counterpart, so only the two
 * states they signified survive the translation. Nothing in this type holds, names or emits a
 * highlight value, an indicator sub-field, a marker character, a control byte, a map
 * coordinate, a field length, a screen mask or any other terminal presentation detail. A
 * client receives two named states and decides its own presentation.
 *
 * <h2>The two translations</h2>
 *
 * <p>Blank becomes {@link ErrorResponse.FieldState#MISSING}; not-OK becomes
 * {@link ErrorResponse.FieldState#INVALID}. The mapping is total, exhaustive and one-way, and
 * it is expressed as a switch with no default clause, so adding a constant to either enum
 * becomes a compile error rather than a silently mishandled case.
 *
 * <p>The output states are {@link ErrorResponse}'s own, not this type's. There is deliberately
 * no second output enum: entries assembled here are handed straight to {@link ErrorResponse}
 * with no further translation, which is what keeps the two types one contract instead of two.
 * The structurally identical state type declared by the validation-failure carrier in the
 * {@code com.carddemo.exception} package is a separate, deliberate duplicate that this package
 * must not reference, because doing so would invert the module's layer direction; the global
 * failure handler one level up in {@code com.carddemo.api} owns that translation.
 *
 * <h2>The re-entry gate belongs to the caller</h2>
 *
 * <p>Macro line 20 conjoined the entire decoration with the program-context re-enter
 * condition, so field-level errors were <em>absent</em> on a first submission and appeared
 * only once the operator had re-submitted the screen. That gate is reproduced by
 * <em>invocation</em>, not by a parameter: {@link #mark(String, String, FlagState)} marks
 * unconditionally whenever it is called, and the caller decides whether to call it at all.
 * The re-enter state itself travels as client-echoed state on {@code NavigationContext}.
 *
 * <p>This is not an oversight to be corrected. Accepting a re-entry flag here would put a
 * presentation-lifecycle decision inside a value type, would give every one of the 39 call
 * sites a fourth argument to pass identically, and would make the first-submission case
 * depend on a flag rather than on the plain absence of calls. A first submission is
 * represented by {@link #none()} with nothing marked.
 *
 * <h2>Purity</h2>
 *
 * <p>{@link #mark(String, String, FlagState)} is a pure function. It returns a <em>new</em>
 * instance and mutates nothing: not its receiver, not its arguments, not any shared state.
 * This type declares no field other than its single component, holds no static state of any
 * kind, and is deeply immutable, so instances are freely shareable across threads and two
 * instances built from the same calls in the same sequence are equal.
 *
 * <p>An accumulation is therefore <em>threaded</em> rather than collected: each call yields
 * the value the next call proceeds from, and {@link #fieldErrors()} is the terminal operation
 * that hands the finished collection to {@link ErrorResponse}. There is no accumulator object,
 * no collector, no cache, no lookup table, no thread-local, no singleton holder and no
 * registry of marked fields. Providing one would reinstate exactly the shared mutable state
 * that an immutable value type exists to avoid, and it would let one request's errors leak
 * into another's.
 *
 * <h2>The 39 fields are the caller's, not this type's</h2>
 *
 * <p>This type is generic over a field name, a screen field identifier and a state. It holds
 * no table, map, array or enumeration of the 39 decorated fields, and none may be added. The
 * 39 pairings are call-site data and belong at the 39 call sites in the account-update
 * service, exactly as the 39 macro expansions belonged at 39 points in the legacy program.
 * Materialising them here would rebuild, in Java, the very duplication this type was created
 * to delete, and would do it as the shared registry immutability forbids.
 *
 * <p>Entries are appended in call sequence, with no re-ordering and no de-duplication. That is
 * faithful: the legacy expansions executed in source sequence, and that sequence is itself
 * irregular - the state field is decorated between the two address lines and the postal code
 * is decorated ahead of city and country. Reproducing the caller's sequence rather than
 * imposing one preserves that without encoding it.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * FieldErrorDecorator errors = FieldErrorDecorator.none();
 * if (flags.accountStatusBlank()) {
 *     errors = errors.mark("accountStatus", "ACSTTUS", FieldErrorDecorator.FlagState.BLANK);
 * }
 * if (flags.creditLimitNotOk()) {
 *     errors = errors.mark("creditLimit", "ACRDLIM", FieldErrorDecorator.FlagState.NOT_OK);
 * }
 * return errors.isEmpty()
 *         ? new AccountUpdateResponse(account)
 *         : new ErrorResponse(summaryMessage, errors.fieldErrors());
 * }</pre>
 *
 * <h2>Provenance</h2>
 *
 * <p>Translated from the macro body at {@code app/cpy/CSSETATY.cpy} lines 17 to 27. The macro
 * is expanded 39 times in {@code app/cbl/COACTUPC.cbl}, at lines 3208, 3214, 3220, 3226, 3232,
 * 3238, 3244, 3250, 3256, 3262, 3268, 3274, 3280, 3286, 3292, 3298, 3304, 3310, 3316, 3322,
 * 3328, 3334, 3340, 3346, 3352, 3358, 3364, 3370, 3376, 3382, 3388, 3394, 3400, 3405, 3411,
 * 3417, 3422, 3427 and 3432, with 39 distinct validation flags, 39 distinct screen field
 * identifiers and one single map token common to all 39.
 * {@code app/bms/COACTUP.bms} corroborates the expansion set: that map defines 43 unprotected
 * input fields, all 39 decorated identifiers are among them, and the four unprotected but
 * undecorated fields are {@code ACCTSID}, {@code AADDGRP}, {@code ACSTNUM} and
 * {@code ACSGOVT}, for which no entry may ever be invented.
 *
 * <p>Five source oddities in that range were verified and are recorded so that a later reader
 * does not "correct" this contract by trusting the wrong half of the source:
 *
 * <ul>
 *   <li>A hand-written equivalent of the macro sits commented out at lines 3198 to 3205,
 *       inside the banner opened at line 3196. It is inactive and stays inactive; the macro,
 *       not the hand-written copy, is the authority.</li>
 *   <li>Line 3375 repeats the descriptive line that correctly labels the state field at line
 *       3363, but the expansion it introduces is the postal code. The substitution tokens are
 *       authoritative.</li>
 *   <li>Lines 3426 and 3431 are a transposed descriptive pair. Following the tokens instead of
 *       the commentary gives the correct final two mappings: the primary-cardholder flag
 *       decorates {@code ACSPFLG} and the electronic-transfer account identifier decorates
 *       {@code ACSEFTC}.</li>
 *   <li>Line 3345 states that the middle name has no edits coded and line 3369 states the same
 *       of the second address line. Both fields are decorated by the macro, so this type must
 *       be able to mark them, but no caller ever will, and no validation constraint may be
 *       attached to either field anywhere in the request contract.</li>
 *   <li>The expansion sequence is irregular, as described above. It is reported as it is and
 *       not normalised.</li>
 * </ul>
 *
 * <p>Behaviour cited, never transcribed, from the CardDemo COBOL estate at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
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
     *                    so a response stays traceable to the map it derives from. It is a
     *                    label and nothing more - not a byte, not a coordinate, not a
     *                    presentation value - and a client may ignore it entirely. Mandatory.
     * @param flagState   which of the two legacy error states the field's validation flag is
     *                    in. Mandatory.
     * @return a new accumulation holding every entry of this one, in sequence, followed by the
     *         entry just marked
     * @throws NullPointerException if any argument is {@code null}. All three are load-bearing:
     *                              without the field name a client cannot locate the field,
     *                              without the identifier the entry loses its traceability to
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
