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
 * {@code app/cpy/CSSETATY.cpy}: an immutable accumulation of per-field error entries, grown one entry
 * at a time by the one operation this type exposes, {@link #mark(String, String, FlagState)}.
 *
 * <p>The macro is not a data structure and does not become one. It takes three substitution tokens -
 * the validation flag to test, the screen field to decorate and the map to decorate it on - and
 * because expansion is textual its 39 sites in {@code app/cbl/COACTUPC.cbl} generate roughly 234 lines
 * of near-identical logic. Collapsing those into 39 invocations of one helper is the largest single
 * de-duplication in this migration, and this type is that helper. The third token is dropped
 * deliberately rather than by omission: all 39 sites substitute the same map, so it carries no
 * information to preserve.
 *
 * <p><strong>Two error states, never one boolean.</strong> The macro fires when a field's validation
 * flag is either not-OK or blank, two different operator mistakes needing different remedies. In both
 * states it wrote an error highlight, which becomes {@link ErrorResponse.FieldState#INVALID}; in the
 * blank state it additionally wrote a marker character over the displayed value, which becomes
 * {@link ErrorResponse.FieldState#MISSING}. Both edits were terminal rendering mechanisms with no REST
 * counterpart, so only the states they signified survive, and {@link FlagState} carries exactly those
 * two because a field that passed its edit is never marked at all. The mapping is expressed as a
 * switch with no default clause, so adding a constant to either enum becomes a compile error rather
 * than a silently mishandled case. Nothing here holds or emits a highlight value, indicator
 * sub-field, marker character, control byte, map coordinate or field length; a client receives two
 * named states and decides its own presentation (decision log D-33).
 *
 * <p><strong>What is accumulated is neutral.</strong> An entry is a {@link MarkedField} - field name,
 * screen field identifier, legacy flag state - naming neither the REST error body nor the
 * validation-failure carrier, so one accumulation serves both tiers and a service that decorates need
 * know only this type. Two projections read it, each owned by the tier it feeds: {@link #fieldErrors()}
 * renders it in the response contract's terms, and {@code service/FieldErrorTranslationService} renders
 * it as the validation failure a service throws and is the only place that conversion happens. The
 * structurally identical state type in the exception package therefore stays a deliberate duplicate
 * that this package must not reference (decision log DL-080).
 *
 * <p><strong>The re-entry gate belongs to the caller.</strong> The macro conjoined the whole decoration
 * with the re-enter condition, so field errors were absent on a first submission. That gate is
 * reproduced by <em>invocation</em> rather than by a parameter: {@code mark} marks unconditionally
 * whenever it is called, and a first submission is {@link #none()} with nothing marked. Accepting a
 * re-entry flag here would put a presentation-lifecycle decision inside a value type and give all 39
 * call sites a fourth argument to pass identically.
 *
 * <p><strong>Purity.</strong> {@code mark} returns a new instance and mutates nothing - not its
 * receiver, not its arguments, not any shared state. The type holds no static state and is deeply
 * immutable, so an accumulation is <em>threaded</em> rather than collected: each call yields the value
 * the next proceeds from, and a projection is the terminal operation. There is deliberately no
 * accumulator, collector, cache, thread-local, singleton holder or registry, because any of them would
 * reinstate shared mutable state and could let one request's errors leak into another's.
 *
 * <p><strong>The 39 pairings are the caller's data.</strong> This type is generic over a field name, a
 * screen field identifier and a state, and holds no table, map or enumeration of the decorated fields;
 * materialising them here would rebuild in Java the duplication it exists to delete. The map bounds
 * the set, and its unprotected but undecorated fields must never be given an entry. Entries are
 * appended in call sequence with no re-ordering and no de-duplication, which is faithful: the legacy
 * expansions ran in source sequence and that sequence is itself irregular, decorating the state field
 * between the two address lines and the postal code ahead of city and country. Two descriptive
 * oddities in the same range - transposed comment lines and a repeated field label - are recorded in
 * the source anomaly register and must not be "corrected" here, because the substitution tokens
 * govern. The middle name and the second address line have no edits coded yet are decorated by the
 * macro, so this type must be able to mark them even though no caller ever will (decision log D-34).
 *
 * <p>The accumulation is threaded through a validating service, and the returned value must be kept:
 *
 * <pre>{@code
 * FieldErrorDecorator errors = FieldErrorDecorator.none();
 * if (flags.accountStatusBlank()) {
 *     errors = errors.mark("accountStatus", "ACSTTUS", FieldErrorDecorator.FlagState.BLANK);
 * }
 * if (!errors.isEmpty()) {
 *     throw translator.toValidationException(errors, messages.accountUpdateRejected());
 * }
 * }</pre>
 *
 * @param markedFields the fields marked into this accumulation, in the sequence they were marked.
 *                     Never {@code null} and never mutable: a {@code null} argument is normalised to
 *                     the empty list and any other argument is defensively copied.
 * @since 1.0.0
 */
public record FieldErrorDecorator(List<MarkedField> markedFields) {

    /**
     * Normalizes the accumulated entries so the component is never {@code null}, never aliased to
     * caller-owned state and never mutable: {@code null} becomes the empty immutable list and any other
     * collection is defensively copied, which also rejects a {@code null} element. The copy is what
     * makes {@link #mark(String, String, FlagState)} genuinely pure, since the value threaded out of one
     * call cannot be altered through a reference the previous caller still holds.
     */
    public FieldErrorDecorator {
        markedFields = (markedFields == null) ? List.of() : List.copyOf(markedFields);
    }

    /**
     * The empty starting value of an accumulation, which is also the first-submission shape: the legacy
     * macro was gated on re-entry, so on a first submission every input field was left undecorated.
     *
     * <p>A fresh instance is returned rather than a shared constant. A constant would be safe, but
     * returning a new value keeps this class free of static state and makes that verifiable by
     * inspection; equality is by component, so two empty accumulations are equal regardless.
     *
     * @return an accumulation holding no entries
     */
    public static FieldErrorDecorator none() {
        return new FieldErrorDecorator(List.of());
    }

    /**
     * Records that one field is in one of the two legacy error states, returning a new accumulation and
     * leaving this one untouched. This is the whole of the macro body's observable effect and the only
     * operation that grows an accumulation.
     *
     * <p>It records an outcome already determined elsewhere: it evaluates no rule, tests no range,
     * inspects no character class and performs no lookup. The flag state is stored in the legacy terms
     * {@link FlagState} carries and is translated only when a projection is taken. No per-field message
     * is produced, which is faithful - the macro emitted no text, because the explanation lived in the
     * summary line the caller owns. No re-entry condition is tested, nothing is de-duplicated and
     * nothing is re-ordered: marking the same field twice yields two entries, because suppressing one
     * would be a decision this type has no standing to make.
     *
     * @param field      the field's name in the request contract, as a client sent it. Mandatory.
     * @param bmsFieldId the legacy screen field identifier, an opaque label that lets a response be
     *                   correlated with the map it derives from - never a byte, a coordinate or a
     *                   presentation value. Mandatory.
     * @param flagState  which of the two legacy error states the field's flag is in. Mandatory.
     * @return a new accumulation holding every entry of this one, in sequence, then the new entry
     * @throws NullPointerException if any argument is {@code null}; see {@link MarkedField}
     */
    public FieldErrorDecorator mark(String field, String bmsFieldId, FlagState flagState) {
        MarkedField entry = new MarkedField(field, bmsFieldId, flagState);

        return new FieldErrorDecorator(
                Stream.concat(markedFields.stream(), Stream.of(entry)).toList());
    }

    /**
     * Projects the accumulation into the response contract's per-field detail: one entry per marked
     * field, in marking sequence, with {@link FlagState#BLANK} translated to
     * {@link ErrorResponse.FieldState#MISSING} and {@link FlagState#NOT_OK} to
     * {@link ErrorResponse.FieldState#INVALID}. No per-field message is attached, because none was ever
     * produced. The returned list is unmodifiable and built fresh, so it can be handed to
     * {@link ErrorResponse} without further copying.
     *
     * <p>This is the projection for a response assembled inside this layer. A service that validates
     * and then fails takes the other projection, so its failure reaches a client along the single path
     * the global failure handler owns.
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
     * Tests whether nothing has been marked - the decision the legacy program made when it chose
     * between re-displaying a decorated screen and committing the update. A presence test only: a
     * caller that has to tell an operator what to do must read {@link MarkedField#flagState()} on each
     * entry, because a blank field and a badly filled field need different remedies.
     *
     * @return {@code true} when no entry has been marked
     */
    public boolean isEmpty() {
        return markedFields.isEmpty();
    }

    /**
     * Maps an incoming legacy flag state onto the state {@link ErrorResponse} publishes. Written as an
     * exhaustive switch with no default clause, so adding a constant to either enum stops this method
     * compiling instead of funnelling a new state into a catch-all - a build failure under the module's
     * warnings-as-errors compilation, which is the intended safeguard. Private, so a state is converted
     * only as part of {@link #fieldErrors()} and the conversion stays in one place.
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
     * One decorated field in the legacy macro's own terms: which field, which screen field, and which
     * of the two flag states it was in. This is the neutral model both tiers read - it references no
     * response type and no exception type, so it can cross a layer boundary and be projected either
     * way. It holds no message, because the macro produced none, and it never holds a submitted field
     * value, so a failure on a credential field cannot echo what was typed (decision log D-16).
     *
     * @param field      the field's name in the request contract, as a client sent it. Mandatory.
     * @param bmsFieldId the legacy screen field identifier, an opaque label. Mandatory, and never
     *                   trimmed or folded, because the legacy labels are fixed-width.
     * @param flagState  which of the two legacy error states the field's flag is in. Mandatory.
     * @since 1.0.0
     */
    public record MarkedField(String field, String bmsFieldId, FlagState flagState) {

        /**
         * Rejects an absent component, because all three are load-bearing: without the field name a
         * client cannot locate the field, without the screen identifier the entry cannot be correlated
         * with the legacy map, and without the state the client cannot tell the operator whether to
         * supply a value or correct one. The single enforcement point, so
         * {@link #mark(String, String, FlagState)} and direct construction reject alike.
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
     * The incoming legacy validation-flag state, with exactly two constants because the macro tested
     * exactly two conditions. Each decorated field had a one-character flag distinguishing a valid
     * value, a value that failed its edit and a value never supplied; only the latter two ever reached
     * the macro. There is deliberately no valid, none or unknown constant, since a field that passed
     * produces no entry and a third constant would be unreachable state.
     *
     * <p>Nested here rather than added to the shared enumeration package, which models persisted and
     * cross-layer domain values: these two constants exist only as the input to
     * {@link FieldErrorDecorator#mark(String, String, FlagState)}.
     *
     * @since 1.0.0
     */
    public enum FlagState {

        /**
         * The field was never supplied. Translates to {@link ErrorResponse.FieldState#MISSING}: the
         * state in which the legacy macro applied both of its edits, and the remedy to offer the
         * operator is "supply a value".
         */
        BLANK,

        /**
         * The field was supplied but the value failed its edit. Translates to
         * {@link ErrorResponse.FieldState#INVALID}: the state in which the legacy macro applied only
         * its highlight, leaving the operator's keystrokes in place, and the remedy to offer the
         * operator is "correct the value".
         */
        NOT_OK
    }
}
