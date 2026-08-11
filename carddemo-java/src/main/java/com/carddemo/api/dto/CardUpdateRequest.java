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

import com.carddemo.domain.enums.KeyAction;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Size;

/**
 * Immutable card-update request for legacy transaction {@code CCUP}, derived from symbolic map
 * {@code app/cpy-bms/COCRDUP.CPY}, mapset {@code app/bms/COCRDUP.bms} and program
 * {@code app/cbl/COCRDUPC.cbl}; the target field kinds come from the 150-byte card record
 * {@code app/cpy/CVACT02Y.cpy} and the attention-key work area from {@code app/cpy/CVCRD01Y.cpy}.
 *
 * <p>Seven of the ten components are map fields, two are conversation state and one is a concurrency
 * proof. Every map component carries a width bound and nothing else, because the legacy program
 * validates each field itself and answers with its own message and field focus.
 *
 * <h2>The two protected components are carry-through, and the wire may not supply them</h2>
 *
 * <p>A protected attribute is a property of a terminal and not of a JSON body, so both protected values
 * are taken from the concurrency proof rather than from the request body - which is what the legacy write
 * does too when it moves the account id from its work area at line 1463 and assembles the expiry day from
 * the guaranteed-unaltered dark echo at line 1471.
 *
 * <p><strong>The expiry day is invisible, protected in every state, and still transmitted.</strong>
 * {@code app/bms/COCRDUP.bms} line 142 declares it dark, field-set and protected - invisible to the
 * operator, impossible to type into, and returned by the terminal on every submission regardless - and
 * the statement that would have rewritten its attribute is commented out in all four screen states (lines
 * 1178, 1185, 1197 and 1205), so no state exists in which an operator could supply it. It is the one
 * input the program reads with no guard at all: every other input is staged through a marker-or-blank
 * test at lines 589 to 628, and the day alone is taken unconditionally at line 621. It must survive the
 * round trip because the program captures the stored day from the ninth position of the ten-character
 * expiry value at line 1365 and compares that same position during change detection at line 1507. It is
 * therefore declared <strong>non-bindable</strong> - serialized outbound, discarded inbound - and the
 * service substitutes the freshly loaded value, which is faithful rather than merely safer because the
 * terminal could only ever have returned the fetched day. That is a serialization directive and not a
 * validation constraint: <strong>no constraint of any kind is attached to the day, not even a width one,
 * and none ever may be.</strong> The same-named field on the account-update screen is unprotected and
 * operator-editable, and that treatment must not be copied here.
 *
 * <p><strong>The account id stays bindable, and its absence is required only on the turn that
 * writes.</strong> Paragraph {@code 3300-SETUP-SCREEN-ATTRS} at line 1169 rewrites the attribute byte on
 * every send, so the runtime attribute rather than the mapset decides what the terminal could have
 * carried: unprotected while details are not yet fetched (line 1174) and in the fall-through state (line
 * 1201), protected once details are shown or changes rejected (line 1183), and protected together with
 * the other five once changes are accepted or written (line 1193). The unprotected states are where the
 * operator names the record, and the program reads the value there as the search filter - line 594, with
 * the diagnostic at line 745 naming it a filter in as many words - so making it non-bindable outright
 * would mean a client could never name the record to update. Instead the constraint follows the state
 * that writes: the rewrite at lines 1461 to 1474 executes only from the confirmed state, where the field
 * is protected, so {@link ConfirmSave} names that turn and on it the component must be absent while the
 * service takes the owning account from the freshly loaded record. The constraint is scoped to that group
 * alone, so it is inert on the searching turn and leaves the ordered cascade below untouched.
 * {@link ConfirmSave} is evaluated by the boundary against the <em>opened</em> proof rather than by the
 * framework's default group, because which turn a submission stands on is knowable only from that proof
 * and never from the body.
 *
 * <h2>Identifiers and date parts are text, never numbers</h2>
 *
 * <p>The account id is eleven characters and the card number sixteen, both with contractual leading zeros
 * and fixed external widths, so {@code 00000000001} must never arrive as {@code 1} and
 * {@code 0000000000000001} must never arrive as {@code 1}; coercing either to a numeric type would
 * discard leading zeros, shorten the external width and break the byte-equivalence acceptance criterion.
 * The three expiry parts are text for the same reason plus one more: the persisted expiry value is a
 * ten-character alphanumeric field at {@code app/cpy/CVACT02Y.cpy} line 9, from which the program slices
 * year, month and day by position at lines 1361 to 1366. No calendar type appears anywhere in this file -
 * nothing is parsed, assembled, resolved against a calendar or checked for a real date.
 *
 * <p>That persisted field carries a long-standing spelling defect in its name. The layout position is
 * authoritative and is preserved by the entity and the record mapper; the Java property here is spelled
 * correctly, and the defect is recorded in {@code docs/decision-log.md} rather than reproduced in any
 * identifier.
 *
 * <p><strong>The active status is the raw single character, not an enumeration.</strong> The domain
 * enumeration declares exactly the two values the screen prompt names and deliberately declares no
 * unknown, other or default constant, so holding it here would either reject or silently discard a third
 * character - and the persistence column carries no membership constraint, so a value outside the pair
 * must round-trip without raising.
 *
 * <h2>Every business rule is delegated, and that is a faithfulness requirement</h2>
 *
 * <p>The program runs an ordered, first-error-wins cascade: each stage is gated on the summary-message
 * slot still being empty, so a submission with four bad fields produces <em>one</em> summary message -
 * the earliest failing stage in source order - alongside independently set per-field flags that drive
 * decoration. Bean Validation evaluates constraints in an unspecified order and reports every violation
 * at once, which would produce a different message for the same input. The whole cascade therefore
 * belongs to {@code CardUpdateService}, and this request <strong>accepts null, blank,
 * over-long-by-content and out-of-range values without rejecting them</strong>. The rules it delegates:
 *
 * <ul>
 * <li>Account id supplied (line 178), and numeric and non-zero at eleven digits (lines 190 and 192, where
 *     the same rule is declared twice), plus the upper-case filter variant at line 745.</li>
 * <li>Card number supplied (line 180) and numeric at sixteen digits (line 194), plus the upper-case
 *     filter variant at line 789.</li>
 * <li>Embossed name supplied (line 182) and letters-or-spaces (line 184).</li>
 * <li>Active status restricted to the two declared characters (line 196).</li>
 * <li>Expiry month within the <strong>inclusive range 1 through 12</strong> (line 198).</li>
 * <li>Expiry year within the <strong>inclusive range 1950 through 2099</strong> (line 200).</li>
 * <li>Nothing typed at all (line 186), and nothing actually altered (line 188).</li>
 * </ul>
 *
 * <p>Neither range is encoded here as an annotation, constant, range object or private check, because
 * hoisting either out of the cascade would change which single message a bad submission produces. The
 * message texts - declared across lines 135 to 214 and continuing at lines 745, 789 and 1023, several
 * with inconsistent punctuation reproduced byte for byte - are <strong>owned by
 * {@code CardUpdateResponse}</strong> and not one is declared in this file.
 *
 * <p><strong>The letters-or-spaces rule admits embedded spaces, and a letters-only pattern would break
 * existing data.</strong> The check is the COBOL blank-and-test idiom: the program blanks every character
 * appearing in a 52-character table of upper- and lower-case ASCII letters, declared at line 257, then
 * tests whether any residue remains. A space is not in the table but a space is also what blanking leaves
 * behind, so a value made only of letters and spaces leaves nothing and <strong>passes</strong> -
 * {@code MARY ANN} is valid input the legacy system accepts. No letters-only pattern constraint may ever
 * be attached to the embossed name, and no downstream predicate may test that every character is a
 * letter; the faithful predicate is "every character is a letter or a space" and it lives in the utility
 * layer.
 *
 * <p><strong>The embossed name is upper-folded twice, in place, and this request must not fold it at
 * all.</strong> The program declares strict 26-character upper- and lower-case tables at lines 261 and
 * 263 and applies the fold at two points: line 1357, immediately <em>before</em> the fetched image is
 * captured at line 1360, and line 1499, at the head of change detection immediately <em>before</em> the
 * six-way comparison at lines 1503 to 1508. Because both sides have been folded by the time it runs,
 * <strong>an edit differing from the fetched value only in letter case is not detected as a change at
 * all</strong> and the operator is told no change was detected rather than that the update succeeded.
 * That outcome is the contract, and it survives only if the operator's exact bytes reach the service, so
 * this request folds nothing, alters no letter case, trims nothing, adds no spaces and normalises
 * nothing. The fold is performed by the service against an ASCII-only 26-character table; the platform's
 * own case-conversion method on {@code String} is unusable for it module-wide, being locale-sensitive and
 * Unicode-aware, and would transform characters the legacy table leaves untouched. Change detection
 * itself, including the retry loop formed by the backward jump at line 1518 to the write-processing exit
 * at line 1494, is service control flow and contributes no component here.
 *
 * <h2>Nothing is transformed on the way through</h2>
 *
 * <p>Every component crosses this boundary byte for byte. Legacy screen fields arrive filled with spaces
 * to their declared width, and those spaces are contract rather than incidental whitespace, so no value
 * is trimmed, space-filled, re-cased, canonicalised or re-rendered here, and no stored or transmitted
 * component is abbreviated, partially hidden or replaced by a placeholder. The card number in particular
 * is carried at its full sixteen characters, because it is the key of the record being updated and a
 * shortened key selects nothing. The width bounds measure and never alter, and the one other constraint
 * asserts an <em>absence</em>, which alters nothing either.
 *
 * <p>That rule governs the values this contract carries and says nothing about how an instance renders
 * itself; the two must not be conflated. {@link #toString()} discloses no component at all, because a
 * record's generated rendering would print the card number in full beside the name it is embossed with.
 *
 * <h2>The concurrency proof</h2>
 *
 * <p>The legacy transaction carries state across its turns that the map never showed. A program work area
 * declared at line 274, whose second group from line 291 is the fetched image of the card as it stood when
 * the screen was built, is filled by {@code 9000-READ-DATA} at line 1344; line 550 moves it into the
 * shared communication area returned with the screen and lines 392 to 400 slice it back off on the next
 * turn. When the operator confirms, {@code 9200-WRITE-PROCESSING} reads the record under lock and only
 * then does {@code 9300-CHECK-CHANGE-IN-REC} compare the locked record against that image at lines 1503
 * to 1508, abandoning the write on any single difference by jumping from line 1518 back to line 1494.
 * Re-reading the record at the start of the confirming turn would detect nothing, because the point is to
 * catch a change made <em>after</em> the screen was displayed, so the compared state has to travel with
 * the conversation.
 *
 * <p>In the legacy that state was safe because the communication area is held by the transaction manager
 * and the terminal never sees it. Echoed to a client it is not, so the proof is opaque and
 * integrity-protected rather than a readable version number or entity tag: a client can return it and
 * cannot read, edit, fabricate or reuse one minted for another card.
 * {@code com.carddemo.service.CardConcurrencyTokenService} seals it with the state each turn settles on
 * and the image that turn fetched, returns it on {@code CardUpdateResponse}, and opens it at the start of
 * the next turn - raising the module's optimistic-lock conflict, whose text is the legacy concurrency
 * notice, when it is present and cannot be authenticated. It carries the two protected values and the
 * state machine's own position, so the arm a turn may reach is the arm the previous turn left it on
 * rather than one a client asserted. An <em>absent</em> proof is not a conflict: it is the genuine first
 * turn, the zero-length communication area the legacy answers at line 388. A present one that cannot be
 * opened is refused rather than downgraded to a first turn, because downgrading would let a client
 * discard a state it disliked by corrupting a byte of it. Recorded as decision {@code DL-109}.
 *
 * <p><strong>The card entity's version column is a different check, not this one.</strong> The provider's
 * version check catches a change made between reading the record for update and writing it; this proof
 * catches a change made between presenting the screen and confirming it. A confirming request that begins
 * by loading the current row loads the current version with it and then agrees with itself, so the version
 * column cannot see into that window at all, and the two mechanisms are complementary.
 *
 * <h2>What this request deliberately does not carry</h2>
 *
 * <p>No readable concurrency value - no version number, entity tag, timestamp or fetched-image snapshot -
 * because every one of those is a value a client could assert for itself. No screen work area either: the
 * shared work-area transfer object would duplicate the attention key and both business keys already
 * present here. And no screen artefact of any kind - no field-length, flag, attribute, colour, highlight
 * or cursor value, no terminal-input-area prefix, no map coordinate and no error-marker character -
 * because the attribute semantics of this map become the two-state per-field error contract on
 * {@code ErrorResponse} and nothing else.
 *
 * <p>This request is a {@code record}: immutable, constructed in one step, with no setter, builder, code
 * generator or annotation processor. It depends only on the platform library, the validation API, the
 * serialization annotation that marks the expiry day non-bindable, the attention-key enumeration and one
 * transfer object in its own package. It holds no logging, input or output, persistence, reflection or
 * business logic, and it is consumed by {@code CardController} and {@code CardUpdateService}.
 *
 * @param accountId account id of the card's owning account - map field {@code ACCTSID}, width 11,
 *        {@code app/cpy-bms/COCRDUP.CPY} line 60. Operator-typed filter on the searching turn and
 *        carry-through for re-keying afterwards, as the attribute states above set out. Text, never a
 *        number, so its eleven characters and any leading zeros survive exactly. <strong>Must be absent
 *        on a {@link ConfirmSave} submission</strong>, the only turn that writes. The width bound is the
 *        <em>screen field's</em> width and not the stored width, deliberately: a 3270 field transmits
 *        whatever was typed, so a blank or part-typed identifier is a value this contract must carry and
 *        the legacy program answers it with a field-level message rather than refusing the transmission.
 *        Exact width and digit class are enforced where the value can do damage - {@code Card} refuses
 *        anything but eleven digits before a write, and {@code V1__create_schema.sql} carries the same
 *        rule as a check constraint. The service reports {@code Account number not provided} when absent
 *        and {@code Account number must be a non zero 11 digit number} when unusable. May be
 *        {@code null}.
 * @param cardNumber the sixteen-character card number being updated - map field {@code CARDSID}, width
 *        16, {@code app/cpy-bms/COCRDUP.CPY} line 66, unprotected at {@code app/bms/COCRDUP.bms} line 96.
 *        The record key the update targets, carried at full width and never shortened or obscured,
 *        because a partial key selects no record. Text, never a number. The service reports
 *        {@code Card number not provided} when absent and
 *        {@code Card number if supplied must be a 16 digit number} when it is not sixteen digits. May be
 *        {@code null}.
 * @param embossedName the name embossed on the card - map field {@code CRDNAME}, width 50,
 *        {@code app/cpy-bms/COCRDUP.CPY} line 72, unprotected at {@code app/bms/COCRDUP.bms} line 107.
 *        Editable, and carried <strong>exactly as the operator typed it</strong>: not upper-folded,
 *        trimmed or normalised, for the reasons the fold section above sets out. The service reports
 *        {@code Card name not provided} when absent and
 *        {@code Card name can only contain alphabets and spaces} when it holds anything other than
 *        letters and spaces - <strong>embedded spaces are valid</strong>. May be {@code null}.
 * @param activeStatus the card's active status as the raw single character - map field {@code CRDSTCD},
 *        width 1, {@code app/cpy-bms/COCRDUP.CPY} line 78, unprotected at {@code app/bms/COCRDUP.bms}
 *        line 117, whose adjacent caption names the two permitted characters. Editable, held raw so an
 *        undeclared character round-trips. The service reports
 *        {@code Card Active Status must be Y or N}. May be {@code null}.
 * @param expiryMonth expiry date, month part - map field {@code EXPMON}, width 2,
 *        {@code app/cpy-bms/COCRDUP.CPY} line 84, unprotected at {@code app/bms/COCRDUP.bms} line 127.
 *        Editable, declared on the map <em>before</em> the year part. Text, so {@code 01} is not reduced
 *        to {@code 1}. The <strong>inclusive 1-through-12 bound is documented, not annotated</strong>;
 *        the service reports {@code Card expiry month must be between 1 and 12} from inside the cascade.
 *        May be {@code null}.
 * @param expiryYear expiry date, year part - map field {@code EXPYEAR}, width 4,
 *        {@code app/cpy-bms/COCRDUP.CPY} line 90, unprotected at {@code app/bms/COCRDUP.bms} line 135.
 *        Editable. Text, so a four-character value keeps its width. The <strong>inclusive
 *        1950-through-2099 bound is documented, not annotated</strong>; the service reports
 *        {@code Invalid card expiry year}, a diagnostic that names no range even though the rule has one.
 *        May be {@code null}.
 * @param expiryDay expiry date, day part - map field {@code EXPDAY}, width 2,
 *        {@code app/cpy-bms/COCRDUP.CPY} line 96. <strong>Hidden, protected, carry-through only</strong>,
 *        for the reasons above. It is <strong>never editable and never validated</strong>, therefore
 *        carries <strong>no validation constraint of any kind - not even a width one</strong>, and is
 *        non-bindable: serialized outbound so the echo survives, ignored inbound so the wire cannot reach
 *        the stored expiry date assembled at lines 1467 to 1473, with the service supplying the value
 *        captured at line 1366. May be {@code null}.
 * @param keyAction the attention key the operator pressed, resolved to one of the sixteen values
 *        {@code app/cpy/CVCRD01Y.cpy} declares. Carried, never interpreted: this screen's save gate is
 *        program-function key 5, which is why the confirmation prompt reads
 *        {@code Changes validated.Press F5 to save} at line 167, but the branching belongs to the
 *        service. <strong>Deliberately never defaulted</strong>: the legacy key mapping has no catch-all
 *        branch, so an unrecognised key leaves the previously held value in place, {@link KeyAction}
 *        models that absence as an empty result and declares no default constant, and the fold of keys 13
 *        through 24 onto keys 1 through 12 lives in the utility layer. May be {@code null}.
 * @param navigationContext the client-echoed navigation state for this turn - not a server session. It
 *        carries, among the rest, the first-entry or re-entry flag that gates whether field-level error
 *        decoration applies at all, so per-field errors appear only on a re-submission of the same screen.
 *        Read by the service; nothing here evaluates it. Its own component constraints are cascaded into,
 *        so an echoed value that could not have occupied its legacy field is rejected at the boundary
 *        rather than reaching the service - without that cascade Bean Validation stops at this level. May
 *        be {@code null}, and an absent context is not a violation.
 * @param concurrencyToken the opaque, integrity-protected description of the card as it stood when this
 *        screen was presented, minted by {@code com.carddemo.service.CardConcurrencyTokenService},
 *        returned on {@code CardUpdateResponse} and echoed back here unchanged. Not a map field: it is
 *        the sealed counterpart of the work area the program carries across the turn at line 550, and it
 *        seals the two protected values the service takes from it rather than from the components above.
 *        Absent, altered or stale is a conflict the service reports with the legacy concurrency notice,
 *        not a binding failure, so <strong>no constraint is attached</strong> - a width or pattern rule on
 *        an opaque sealed value would couple this contract to the envelope's internal encoding.
 */
public record CardUpdateRequest(
        @Null(groups = ConfirmSave.class) @Size(max = 11) String accountId,
        @Size(max = 16) String cardNumber,
        @Size(max = 50) String embossedName,
        @Size(max = 1) String activeStatus,
        @Size(max = 2) String expiryMonth,
        @Size(max = 4) String expiryYear,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY) String expiryDay,
        KeyAction keyAction,
        @Valid NavigationContext navigationContext,
        String concurrencyToken) {

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of the whole component set.
     *
     * <p>A constant rather than any transformation of the values, so nothing about them - not a length,
     * prefix, digest or partial mask - can be recovered from a stringified instance. A partial mask was
     * rejected deliberately: a truncated primary account number is still cardholder data.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Validation group naming the submission that confirms and writes the update.
     *
     * <p>A marker interface and nothing else. It exists so that the account id can be required to be
     * absent on the one turn that persists, without that requirement leaking into the turn on which the
     * operator types it. The turn it names is the one the program reaches with changes accepted and
     * confirmed - the state whose attribute branch at line 1193 leaves the account id and card number
     * protected, and the only state from which the rewrite at lines 1461 to 1474 executes.
     *
     * <p><strong>Nothing here participates in the default group.</strong> The constraint scoped to this
     * group asserts an absence rather than a presence, adds no mandatory field, contributes no message
     * and does not fire unless a caller names the group explicitly, so the ordered first-error-wins
     * cascade is untouched and an unqualified validation is unaffected.
     */
    public interface ConfirmSave {
    }

    /**
     * Returns a diagnostic representation that names the type and discloses none of its values.
     *
     * <p>A record's generated {@code toString()} prints every component, and on this type that set
     * includes the card number at full width, the account it belongs to, the embossed cardholder name and
     * the sealed concurrency proof - so any structured logger, framework diagnostic, failed assertion,
     * exception message or string interpolation touching an instance would have emitted the primary
     * account number beside the name it is embossed with, and would additionally have written out a live
     * integrity credential.
     *
     * <p><strong>Why nothing is retained and nothing is partially masked.</strong> The account identifier
     * and card number look like correlation handles, and in isolation they nearly are; here they sit
     * beside the cardholder's name on the same object, so emitting either would let a reader join a
     * subject to a card across two log lines. A partial rendering is no better - a fragment of a card
     * number is still card data, and a length or digest still discriminates between candidates - and the
     * genuine correlation need is met by the request-scoped trace identifier the observability
     * configuration attaches to every log event. The remaining components are withheld with the rest
     * because a whole-object placeholder cannot leak by omission the way an enumerated renderer can when
     * a component is later added.
     *
     * <p>This override changes only the stringified form: the accessors and the serialized payload
     * continue to carry the full untouched values, which the service needs intact. {@code equals} and
     * {@code hashCode} are left as the record contract generates them, comparing every component by
     * value, and neither emits anything - an in-memory comparison is not a disclosure surface.
     *
     * @return the type name followed by a fixed placeholder, carrying no component value
     */
    @Override
    public String toString() {
        return "CardUpdateRequest[" + REDACTION_PLACEHOLDER + "]";
    }
}
