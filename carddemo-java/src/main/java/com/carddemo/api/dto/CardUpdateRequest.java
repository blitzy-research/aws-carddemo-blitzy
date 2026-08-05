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
 * <p>Two components the wire may <em>not</em> supply on a confirmed save are sealed instead: the
 * account identifier is refused on that operation, and the expiry day is read-only in the serialised
 * contract. Both are taken from the concurrency proof the service issued rather than from the request
 * body, so a client cannot re-target a confirmed save at another account or alter a value the screen
 * never offered.
 *
 * <p>The concurrency component is what makes a stale update detectable: the service issues it,
 * returns it on the response, and verifies it before writing - raising the module's optimistic-lock
 * conflict, whose text is the legacy concurrency notice, when it is absent, altered, or no longer
 * describes the stored record. It is a different check from the card entity's version column, which
 * guards the write itself. Recorded as decision {@code DL-109}.
 *
 * <p>Every other component carries a width bound and nothing else, because the legacy program
 * validates each field itself and answers with its own message and field focus; the embossed-name
 * upper-casing is a character-table fold performed by the service, never here.
 *
 * <p><strong>Two of the seven components are protected on the screen and are pure
 * carry-through.</strong> They exist so that a round trip does not lose stored state, not because an
 * operator may type into them, and the distinction is load-bearing.
 *
 * <ul>
 * <li><strong>The expiry day is invisible, protected, and still transmitted.</strong>
 *     {@code app/bms/COCRDUP.bms} line 142 declares it dark, field-set and protected: dark so the
 *     operator never sees it, protected so the operator cannot type into it, and field-set so the
 *     terminal returns it on every submission regardless. The program reads the stored day from the
 *     ninth position of the ten-character expiry value when it captures the fetched image
 *     ({@code app/cbl/COCRDUPC.cbl} line 1365) and compares that same position again during change
 *     detection (line 1507), so the value must survive the round trip intact or a comparison that
 *     should succeed will fail. It is therefore carried and, uniquely among the seven, carries
 *     <strong>no validation constraint of any kind</strong>: it is not user input, so no constraint
 *     of any sort may be attached to it and no validation may ever be applied to it.
 *     <strong>The account-update screen genuinely differs</strong> - there the same-named field is
 *     unprotected and operator-editable - so the treatment given there must not be copied here.</li>
 * <li><strong>The account id is protected once the record has been fetched, and unprotected before
 *     that.</strong> {@code app/bms/COCRDUP.bms} line 84 declares it field-set, insert-cursor,
 *     normal-intensity and protected, but that is only the attribute of the very first send: the
 *     program rewrites it on every subsequent send, and in one of its states it rewrites it to
 *     unprotected. It is carried because the operator types it there, and because the service needs
 *     it to re-key the record it is about to update.</li>
 * </ul>
 *
 * <h2>Which of the two the wire may supply, and why the answer differs</h2>
 *
 * <p>The static attribute a mapset declares is the attribute of the first send only.
 * {@code app/cbl/COCRDUPC.cbl} paragraph {@code 3300-SETUP-SCREEN-ATTRS} at line 1169 opens with an
 * ordered state test that rewrites the attribute byte of the account id, the card number and the four
 * editable fields on <em>every</em> send, so the runtime attribute - not the mapset - is what decides
 * whether the terminal could have carried an operator's keystroke. The four branches measured:
 *
 * <ul>
 * <li>Details not yet fetched (line 1174): the account id and card number are made <strong>field-set
 *     and unprotected</strong>, while the embossed name, active status, expiry month and expiry year
 *     are made protected. This is the state in which the operator names the record.</li>
 * <li>Details shown, or changes rejected (line 1183): the account id and card number are made
 *     <strong>protected</strong> and field-set, and the four editable fields become unprotected.</li>
 * <li>Changes accepted but not confirmed, or accepted and written (line 1193): all six are made
 *     <strong>protected</strong>.</li>
 * <li>Any other state (line 1201): as the first branch - the account id and card number unprotected
 *     again.</li>
 * </ul>
 *
 * <p><strong>The expiry day appears in none of those branches.</strong> In all four the statement that
 * would have rewritten its attribute is commented out - lines 1178, 1185, 1197 and 1205 - and nothing
 * else in the program writes that attribute, so the mapset's declaration at line 142 governs in every
 * state: the field is dark, protected and field-set for the whole life of the conversation. It is
 * consequently the one input the program reads with no guard at all. Every other input is staged through
 * a test for the marker character or all spaces before it is accepted, at lines 589 to 628, and the day
 * alone is taken unconditionally at line 621 - the program itself recording that it treats the value as
 * machinery rather than as something a person typed.
 *
 * <p>Both values reach the stored record: the write at lines 1461 to 1474 keys the rewrite on the card
 * number, moves the account id into the record's owning-account field, and assembles the ten-character
 * expiry value out of the year, the month and <em>the day the terminal returned</em>. On a 3270 that is
 * safe twice over - the day was protected, and the only value ever sent into that field is the one
 * captured from the record at lines 1110, 1123 and 1127. Over HTTP neither guarantee survives, because a
 * request body is not a terminal buffer and any client may put two characters of its choosing into a
 * field the screen never showed.
 *
 * <p><strong>The expiry day is therefore accepted from the server and never from the wire.</strong>
 * It remains a component, because the response contract echoes it and the round trip must not lose
 * it, and it is declared non-bindable: a value present in an inbound body is discarded rather than
 * honoured. This is faithful rather than merely safer, because the terminal could only ever have
 * returned the fetched day, so substituting the freshly loaded value reproduces every outcome the
 * legacy system can produce and closes only the outcomes it cannot. The service takes it from the
 * position it was captured from at line 1366. That is a serialization directive and not a validation
 * constraint: no constraint annotation is attached to the day, and none ever may be.
 *
 * <p><strong>The account id stays bindable, and its absence is required only on the turn that
 * writes.</strong> Making it non-bindable outright would be the same treatment and would be wrong:
 * the first and last branches above leave it unprotected, and the program reads it there as the
 * search filter - line 594, with the diagnostic at line 745 naming it a filter in as many words - so
 * a client that could not supply it could never name the record to update, which is a behavioural
 * change rather than a hardening. Instead the constraint follows the state that actually writes.
 * The rewrite happens only from the confirmed state, and in that state and the two before it the
 * field is protected, so a confirming submission has no legitimate reason to carry an account id at
 * all. {@link ConfirmSave} names that turn, and on it the component must be absent; the service
 * takes the owning account from the freshly loaded card record, which is where the legacy takes it
 * from too. The constraint is scoped to that group alone and so is inert on the searching turn,
 * which leaves the ordered first-error-wins cascade described below exactly as it was.
 *
 * <p><strong>Neither of the two survives as a value the service may believe, and the concurrency
 * proof is what replaces the attribute byte.</strong> A protected attribute is a property of a
 * terminal and not of a JSON body. On the confirming turn the service therefore takes the owning
 * account and the expiry day from the sealed proof described below -
 * {@code com.carddemo.service.CardConcurrencyTokenService} verifies the proof and hands both values
 * back - and never from these components, which is what the legacy write does too when it moves the
 * account id from the work area at line 1463 and assembles the day from the guaranteed-unaltered dark
 * echo at line 1471. The two declarations above are how that is enforced at the boundary: the day is
 * non-bindable in every state, and the account id, which the searching turn still needs as its
 * filter, is required to be absent once {@link ConfirmSave} names the turn that writes.
 *
 * <p><strong>Identifiers and date parts are text, never numbers.</strong> The account id is eleven
 * characters and the card number sixteen, and both have contractual leading zeros and fixed external
 * widths, so an account id of {@code 00000000001} must never arrive as {@code 1} and a card number of
 * {@code 0000000000000001} must never arrive as {@code 1}. Coercing either to a numeric type would
 * discard leading zeros, shorten the external width and break the byte-equivalence acceptance
 * criterion. The three expiry parts are text for the same reason plus one more: the persisted expiry
 * value is a ten-character alphanumeric field, declared at {@code app/cpy/CVACT02Y.cpy} line 9, from
 * which the program slices the year, the month and the day by position (lines 1361 to 1366). No
 * calendar type appears anywhere in this file - nothing is parsed, assembled, resolved against a
 * calendar or checked for a real date - because none of that is this contract's work.
 *
 * <p>That same persisted field carries a spelling defect in its name, which the record layout has
 * always had. The layout position is authoritative and is preserved by the entity and the record
 * mapper; the Java property here is spelled correctly, and the defect is recorded in
 * {@code docs/decision-log.md} rather than reproduced in any identifier.
 *
 * <p><strong>The active status is the raw single character, not an enumeration.</strong> The domain
 * enumeration declares exactly the two values the screen prompt names, and it deliberately declares no
 * unknown, other or default constant. Holding it here would therefore either reject or silently
 * discard any third character, and the persistence column carries no membership constraint, so a
 * value outside the pair must round-trip without raising. The yes-or-no rule is a message-bearing
 * service check, not a type constraint - see the delegation section below.
 *
 * <p><strong>Every business rule is delegated, and that is a faithfulness requirement rather than a
 * convenience.</strong> {@code app/cbl/COCRDUPC.cbl} runs an ordered, first-error-wins cascade: each
 * stage is gated on the summary-message slot still being empty, so a submission with four bad fields
 * produces <em>one</em> summary message - the one belonging to the earliest failing stage in source
 * order - alongside independently set per-field flags that drive decoration. Bean Validation evaluates
 * constraints in an unspecified order and reports every violation at once, which would produce a
 * different message for the same input and would therefore change observable behaviour. The whole
 * cascade consequently belongs to {@code CardUpdateService}, and this request
 * <strong>accepts null, blank, over-long-by-content and out-of-range values without rejecting
 * them</strong>. The rules it delegates, with the diagnostic each produces:
 *
 * <ul>
 * <li>Account id supplied (line 178), and numeric and non-zero at eleven digits (lines 190 and 192,
 *     where the same rule is declared twice), plus the upper-case filter variant at line 745.</li>
 * <li>Card number supplied (line 180) and numeric at sixteen digits (line 194), plus the upper-case
 *     filter variant at line 789.</li>
 * <li>Embossed name supplied (line 182) and letters-or-spaces (line 184).</li>
 * <li>Active status restricted to the two declared characters (line 196).</li>
 * <li>Expiry month within the <strong>inclusive range 1 through 12</strong> (line 198).</li>
 * <li>Expiry year within the <strong>inclusive range 1950 through 2099</strong> (line 200).</li>
 * <li>Nothing typed at all (line 186), and nothing actually altered (line 188).</li>
 * </ul>
 *
 * <p>Neither range is encoded here as an annotation, a constant, a range object or a private check;
 * both are documented above and enforced by the service inside the cascade, because hoisting either
 * out of the cascade would change which single message a bad submission produces. The message texts
 * themselves - declared across {@code app/cbl/COCRDUPC.cbl} lines 135 to 214 and continuing at lines
 * 745, 789 and 1023, several with inconsistent punctuation that is reproduced byte for byte - are
 * <strong>owned by {@code CardUpdateResponse}</strong>. <strong>Not one of them is declared in this
 * file.</strong>
 *
 * <p><strong>The letters-or-spaces rule admits embedded spaces, and a letters-only pattern would
 * break existing data.</strong> The check is the COBOL blank-and-test idiom: the program blanks every
 * character that appears in a 52-character table of the upper-case and lower-case ASCII letters,
 * declared at {@code app/cbl/COCRDUPC.cbl} line 257, and then tests whether any residue remains.
 * A space is not in the table, but a space is also what blanking leaves behind, so a value made only
 * of letters and spaces leaves nothing and <strong>passes</strong>: {@code MARY ANN} is valid input
 * that the legacy system accepts. No letters-only pattern constraint may ever be attached to the
 * embossed name, and no downstream predicate may test that every character is a letter; the faithful
 * predicate is "every character is a letter or a space" and it lives in the utility layer. Decision
 * log entry for the alphabetic idiom records this.
 *
 * <p><strong>The embossed name is upper-folded twice, in place, and this request must not fold it at
 * all.</strong> The program declares a strict 26-character upper-case table at line 261 and a strict
 * 26-character lower-case table at line 263 and applies the fold with a converting instruction at two
 * points. The first is at line 1357, immediately <em>before</em> the fetched image is captured at line
 * 1360. The second is at line 1499, at the very head of change detection, immediately <em>before</em>
 * the six-way comparison at lines 1503 to 1508. Because both sides of that comparison have been folded
 * by the time it runs, <strong>an edit that differs from the fetched value only in letter case is not
 * detected as a change at all</strong> and the operator is told
 * {@code No change detected with respect to values fetched.} rather than being told the update
 * succeeded. That outcome is the contract. It survives only if the operator's exact bytes reach the
 * service, so this request folds nothing, alters no letter case, trims nothing, adds no spaces and
 * normalises nothing; the fold is performed by {@code CardUpdateService} against an ASCII-only
 * 26-character table. The platform's own case-conversion method on {@code String} is unusable for it
 * module-wide, being locale-sensitive and Unicode-aware, and would transform characters the legacy
 * table leaves untouched.
 *
 * <p>Change detection itself, including the retry loop formed by the backward jump at line 1518 to the
 * write-processing exit at line 1494, is service control flow and contributes no component here.
 *
 * <p><strong>Nothing is transformed on the way through, and that is the single rule this contract
 * enforces.</strong> Every component crosses this boundary byte for byte. Legacy screen fields arrive
 * filled with spaces to their declared width, and those spaces are contract rather than incidental
 * whitespace, so no value is trimmed, space-filled, re-cased, canonicalised or re-rendered here, and
 * no <em>stored or transmitted</em> component is abbreviated, shortened, partially hidden or replaced by
 * a placeholder. The card number in particular is carried at its full sixteen characters because it is
 * the key of the record being updated and a shortened key selects nothing. The one declarative
 * constraint used on a map component, a maximum length at its measured map width, measures and never
 * alters, so leading and trailing spaces survive validation untouched.
 * The only other constraint anywhere in this file asserts that the account id is <em>absent</em> on
 * the confirming turn, and an assertion of absence alters nothing either.
 *
 * <p>That rule governs the values this contract carries; it says nothing about how an instance renders
 * itself, and the two must not be conflated. {@link #toString()} discloses no component at all, because
 * a record's generated rendering would have printed the card number in full beside the name it is
 * embossed with. Withholding at the rendering boundary alters nothing a caller receives, so it costs
 * this contract nothing, whereas relying on logging configuration alone would leave every failed
 * assertion, framework diagnostic and interpolated exception message as an uncovered disclosure path.
 *
 * <p><strong>The concurrency proof, and why one component is not a map field.</strong> Seven of the
 * components below are map fields and two are conversation state; the tenth is a concurrency proof,
 * and it is present because the legacy transaction carries state across its turns that the map never
 * showed. {@code app/cbl/COCRDUPC.cbl} declares a program work area at line 274 whose second group
 * (line 291 onward) is the fetched image of the card as it stood when the screen was built, filled by
 * paragraph {@code 9000-READ-DATA} at line 1344. Line 550 moves that work area into the shared
 * communication area returned with the screen and lines 392 to 400 slice it back off on the next turn.
 * When the operator confirms, paragraph {@code 9200-WRITE-PROCESSING} reads the record under lock and
 * only then does {@code 9300-CHECK-CHANGE-IN-REC} compare the locked record against that image, line
 * 1503 to 1508, abandoning the write on any single difference by jumping back to line 1494 from line
 * 1518. Re-reading the record at the start of the confirming turn would detect nothing: the whole
 * point is to catch a change made <em>after</em> the screen was displayed, so the compared state has
 * to have travelled with the conversation.
 *
 * <p>In the legacy that state was safe because the communication area is held by the transaction
 * manager and the terminal never sees it. Echoed to a client it is no longer safe, so the proof is
 * opaque and integrity-protected rather than a readable version number or entity tag: a client can
 * return it and cannot read, edit, fabricate or reuse one minted for another card.
 * {@code com.carddemo.service.CardConcurrencyTokenService} mints it when the card is presented,
 * returns it on {@code CardUpdateResponse}, and verifies it before the update - raising the module's
 * optimistic-lock conflict, whose text is the legacy concurrency notice, when it is absent, altered or
 * no longer describes the stored record. It also hands back the two protected values it seals, so the
 * account id and the expiry day the service writes come from the proof rather than from this body.
 * Decision {@code DL-109} in {@code docs/decision-log.md} records this arrangement and the
 * stale-update parity requirement that makes carrying a proof mandatory rather than optional.
 *
 * <p><strong>The card entity's version column is a different check, not this one.</strong> The
 * provider's version check catches a change made between reading the record for update and writing it;
 * this proof catches a change made between presenting the screen and confirming it. A confirming
 * request that begins by loading the current row loads the current version with it and then agrees
 * with itself, so the version column cannot see into that window at all, and the two mechanisms are
 * complementary rather than alternatives.
 *
 * <p><strong>What this request deliberately does not carry.</strong> There is no readable concurrency
 * value - no version number, no entity tag, no timestamp and no fetched-image snapshot - because every
 * one of those is a value a client could assert for itself. There is no screen work area either: the
 * shared work-area transfer object would duplicate the attention key and both business keys already
 * present here, so the two values actually needed are carried directly. No screen artefact of any kind
 * appears - no field-length, flag, attribute, colour, highlight or cursor value, no terminal-input-area
 * prefix, no map coordinate and no error-marker character - because the attribute semantics of this map
 * become the two-state per-field error contract on {@code ErrorResponse} and nothing else.
 *
 * <p>This request is a {@code record}: immutable, constructed in one step, with no setter, no builder
 * and no code generator or annotation processor involved. It depends only on the platform library, the
 * validation API, the serialization annotation that marks the expiry day non-bindable, the
 * attention-key enumeration and one transfer object in its own package. It holds no logging, no input
 * or output, no persistence, no reflection and no business logic, and it is consumed by
 * {@code CardController} and {@code CardUpdateService}.
 *
 * @param accountId account id of the card's owning account - map field {@code ACCTSID}, width 11,
 *        declared at {@code app/cpy-bms/COCRDUP.CPY} line 60. <strong>Unprotected while the record
 *        has not been fetched and protected afterwards</strong>: {@code app/bms/COCRDUP.bms} line 84
 *        declares the first-send attribute field-set, insert-cursor, normal and protected, and
 *        {@code app/cbl/COCRDUPC.cbl} line 1174 rewrites it to unprotected in the not-yet-fetched
 *        state and line 1183 back to protected once details are on the screen. It is therefore an
 *        operator-typed filter on the searching turn - read at line 594, and named a filter by the
 *        diagnostic at line 745 - and carry-through for re-keying afterwards. Text, never a number,
 *        so its eleven characters and any leading zeros survive exactly. <strong>Must be absent on a
 *        {@link ConfirmSave} submission</strong>, which is the only turn that writes: the rewrite at
 *        lines 1461 to 1474 moves this value into the record's owning-account field, and in the
 *        confirming state the screen has it protected, so the service takes it from the freshly
 *        loaded card record rather than from the body. The service reports
 *        {@code Account number not provided} when it is absent and
 *        {@code Account number must be a non zero 11 digit number} when it is unusable. May be
 *        {@code null}; an empty submission is a real state that the legacy screen accepts and prompts
 *        against.
 * @param cardNumber the sixteen-character card number being updated - map field {@code CARDSID},
 *        width 16, declared at {@code app/cpy-bms/COCRDUP.CPY} line 66 and unprotected at
 *        {@code app/bms/COCRDUP.bms} line 96. This is the record key the update targets, carried at
 *        full width and never shortened or obscured in any way, because a partial key selects no
 *        record. Text, never a number, so {@code 0000000000000001} is not reduced to {@code 1}. The
 *        service reports {@code Card number not provided} when it is absent and
 *        {@code Card number if supplied must be a 16 digit number} when it is not sixteen digits. May
 *        be {@code null}.
 * @param embossedName the name embossed on the card - map field {@code CRDNAME}, width 50, declared at
 *        {@code app/cpy-bms/COCRDUP.CPY} line 72 and unprotected at {@code app/bms/COCRDUP.bms} line
 *        107. Editable. Carried <strong>exactly as the operator typed it</strong>: not upper-folded,
 *        not trimmed and not normalised, because the service folds it against an ASCII-only
 *        26-character table and a case-only edit must remain undetectable as a change, per the class
 *        notes on lines 1357, 1360, 1499 and 1503 to 1508. The service reports
 *        {@code Card name not provided} when it is absent and
 *        {@code Card name can only contain alphabets and spaces} when it holds anything other than
 *        letters and spaces - <strong>embedded spaces are valid</strong>, so {@code MARY ANN} passes.
 *        May be {@code null}.
 * @param activeStatus the card's active status as the raw single character - map field
 *        {@code CRDSTCD}, width 1, declared at {@code app/cpy-bms/COCRDUP.CPY} line 78 and unprotected
 *        at {@code app/bms/COCRDUP.bms} line 117, whose adjacent caption names the two permitted
 *        characters. Editable. Held raw rather than as the domain enumeration so that an undeclared
 *        character round-trips instead of being rejected at construction or silently dropped; the
 *        persistence column carries no membership constraint either. The service reports
 *        {@code Card Active Status must be Y or N}. May be {@code null}.
 * @param expiryMonth expiry date, month part - map field {@code EXPMON}, width 2, declared at
 *        {@code app/cpy-bms/COCRDUP.CPY} line 84 and unprotected at {@code app/bms/COCRDUP.bms} line
 *        127. Editable, and declared on the map <em>before</em> the year part. Text, so a value of
 *        {@code 01} is not reduced to {@code 1}. The <strong>inclusive 1-through-12 bound is
 *        documented, not annotated</strong>; the service reports
 *        {@code Card expiry month must be between 1 and 12} from inside the ordered cascade. May be
 *        {@code null}.
 * @param expiryYear expiry date, year part - map field {@code EXPYEAR}, width 4, declared at
 *        {@code app/cpy-bms/COCRDUP.CPY} line 90 and unprotected at {@code app/bms/COCRDUP.bms} line
 *        135. Editable. Text, so a four-character value keeps its width. The <strong>inclusive
 *        1950-through-2099 bound is documented, not annotated</strong>; the service reports
 *        {@code Invalid card expiry year}, a diagnostic that names no range even though the rule has
 *        one. May be {@code null}.
 * @param expiryDay expiry date, day part - map field {@code EXPDAY}, width 2, declared at
 *        {@code app/cpy-bms/COCRDUP.CPY} line 96. <strong>Hidden, protected, carry-through
 *        only.</strong> {@code app/bms/COCRDUP.bms} line 142 declares it dark, field-set and
 *        protected: invisible to the operator, impossible for the operator to type into, and returned
 *        by the terminal on every submission all the same. It exists here solely so the stored day
 *        survives the round trip for the comparison at {@code app/cbl/COCRDUPC.cbl} line 1507, having
 *        been captured at line 1365. It is <strong>never editable and never validated</strong>, and it
 *        therefore carries <strong>no validation constraint of any kind - not even a width
 *        one</strong>, because it is not user input and any constraint on it would reject a round trip
 *        the legacy system completes. <strong>It is also non-bindable</strong>: the attribute rewrite
 *        that would have unprotected it is commented out in all four states (lines 1178, 1185, 1197
 *        and 1205), so no state exists in which an operator could supply it, and the only value the
 *        program ever sends into the field is the one it captured from the record at line 1366 -
 *        so the freshly loaded value reproduces every outcome the terminal could produce. Serialized
 *        outbound, ignored inbound; that is a serialization directive and not a constraint. The
 *        same-named field on the account-update screen is unprotected and editable; that treatment
 *        must not be applied here. May be {@code null}.
 * @param keyAction the attention key the operator pressed, resolved to one of the sixteen values the
 *        work area {@code app/cpy/CVCRD01Y.cpy} declares. Carried, never interpreted: this screen's
 *        save gate is program-function key 5, which is why the confirmation prompt reads
 *        {@code Changes validated.Press F5 to save} at {@code app/cbl/COCRDUPC.cbl} line 167 and why
 *        the legend at {@code app/bms/COCRDUP.bms} line 167 offers save and cancel, but the branching
 *        on it belongs entirely to the service. <strong>Deliberately never defaulted.</strong> The
 *        legacy key mapping has no catch-all branch, so an unrecognised key leaves the previously held
 *        value in place; {@link KeyAction} models that absence as an empty result and declares no
 *        default constant, and the fold of keys 13 through 24 onto keys 1 through 12 lives in the
 *        utility layer, which this file does not depend on. May be {@code null}.
 * @param navigationContext the client-echoed navigation state for this turn - not a server session.
 *        Carries, among the rest, the first-entry or re-entry flag that gates whether field-level
 *        error decoration applies at all, so per-field errors appear only on a re-submission of the
 *        same screen. Read by the service; nothing here evaluates it. Its own component constraints
 *        are cascaded into, so an echoed value that could not have occupied its legacy field is
 *        rejected at the boundary rather than reaching the service. May be {@code null}, and an absent
 *        context is not a violation.
 * @param concurrencyToken the opaque, integrity-protected description of the card as it stood when
 *        this screen was presented, minted by
 *        {@code com.carddemo.service.CardConcurrencyTokenService}, returned on
 *        {@code CardUpdateResponse} and echoed back here unchanged by the client. Not a map field: it
 *        is the sealed counterpart of the program work area {@code app/cbl/COCRDUPC.cbl} carries across
 *        the turn at line 550. It also seals the two protected carry-through values, which the service
 *        takes from it rather than from the components above. Absent, altered or stale is a conflict
 *        the service reports with the legacy concurrency notice, not a binding failure, so <strong>no
 *        constraint is attached</strong>: a width or pattern rule on an opaque sealed value would
 *        couple this contract to the envelope's internal encoding.
 */
public record CardUpdateRequest(

        /* 1. ACCTSID, width 11, COCRDUP.CPY:60 - first-send attribute PROTECTED at COCRDUP.bms:84,
         * but rewritten UNPROTECTED at COCRDUPC:1174 while details are not fetched and PROTECTED
         * again at COCRDUPC:1183 once they are. Operator-typed filter on the searching turn (read at
         * COCRDUPC:594), carry-through for re-keying afterwards. Must be ABSENT on a ConfirmSave
         * submission: the write at COCRDUPC:1461-1474 moves it into the record's owning-account
         * field, and the confirming state has it protected, so the service takes the owning account
         * from the verified proof rather than from the body.
         *
         * THE BOUND IS THE SCREEN FIELD'S WIDTH, NOT THE STORED WIDTH, AND THE DIFFERENCE IS
         * DELIBERATE. A 3270 field transmits whatever the operator typed into it, so a blank or
         * part-typed account identifier is a value this contract must be able to carry - the legacy
         * program answers it with a field-level screen message rather than refusing the transmission.
         * An exact-width bound here would turn that message into a rejected request, which is a
         * behavioural change dressed as rigour. Exact width and digit class are enforced where the
         * value can actually do damage: com.carddemo.domain.Card refuses anything but eleven digits
         * before an insert or an update, and V1__create_schema.sql carries the same rule as a check
         * constraint. So a short identifier can be typed, is reported as a field error, and can never
         * be stored. */
        @Null(groups = ConfirmSave.class) @Size(max = 11) String accountId,

        @Size(max = 16) String cardNumber,

        @Size(max = 50) String embossedName,

        @Size(max = 1) String activeStatus,

        @Size(max = 2) String expiryMonth,

        @Size(max = 4) String expiryYear,

        /* 7. EXPDAY, width 2, COCRDUP.CPY:96 - dark, field-set and PROTECTED at COCRDUP.bms:142, and
         * protected in EVERY state because the attribute rewrite is commented out at COCRDUPC:1178,
         * 1185, 1197 and 1205. Hidden protected carry-through, never user input, never validated.
         * INTENTIONALLY CARRIES NO VALIDATION CONSTRAINT - DO NOT ADD ONE, NOT EVEN A WIDTH ONE. The
         * @param tag above carries the measured evidence. Non-bindable: serialized outbound so the
         * echo survives, ignored inbound so the wire cannot reach the stored expiry date assembled at
         * COCRDUPC:1467-1473; the service supplies the value captured at COCRDUPC:1366. A
         * serialization directive is not a constraint. */
        @JsonProperty(access = JsonProperty.Access.READ_ONLY) String expiryDay,

        KeyAction keyAction,

        /* 9. Client-echoed navigation state; carries the enter/re-enter gate on field decoration.
         * @Valid so the nested widths declared on that contract are actually evaluated: without it
         * Bean Validation stops at this level and an over-long echoed identifier crosses unchecked. */
        @Valid NavigationContext navigationContext,

        /* 10. Not a map field. The sealed counterpart of the program work area COCRDUPC carries
         * across the pseudo-conversational turn at line 550, described on the type above. Opaque and
         * unbounded by design, and deliberately unannotated: its absence is a conflict for the
         * service to report, not a binding failure for the framework to reject. */
        String concurrencyToken) {

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of the whole component set.
     *
     * <p>A constant rather than any transformation of the values, so nothing about them - not a length,
     * not a prefix, not a digest, not a partial mask - can be recovered from a stringified instance. A
     * partial mask was rejected deliberately: a truncated primary account number is still cardholder
     * data.
     *
     * <p>Private because it is a rendering detail and not part of the request contract. It stands in
     * only on the rendering path: every accessor returns its component untouched.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Validation group naming the submission that confirms and writes the update.
     *
     * <p>A marker interface and nothing else: it declares no method, is never instantiated, and
     * carries no behaviour. It exists so that one component - the account id - can be required to be
     * absent on the one turn that persists, without that requirement leaking into the turn on which
     * the operator types it.
     *
     * <p>The turn it names is the one {@code app/cbl/COCRDUPC.cbl} reaches with changes accepted and
     * confirmed, the state whose attribute branch at line 1193 leaves the account id and the card
     * number protected, and the only state from which the rewrite at lines 1461 to 1474 executes. A
     * submission validated against this group therefore stands where the legacy screen offered the
     * operator nothing to type into but the save key.
     *
     * <p><strong>Nothing here participates in the default group.</strong> The ordered,
     * first-error-wins validation cascade the class notes describe is untouched: the constraint scoped
     * to this group asserts an absence rather than a presence, adds no mandatory field, contributes no
     * message and does not fire at all unless a caller names the group explicitly. A submission
     * validated the ordinary unqualified way is therefore unaffected by this group.
     */
    public interface ConfirmSave {
    }

    /**
     * Returns a diagnostic representation that names the type and discloses none of its values.
     *
     * <p><strong>Why the implicit record rendering could not stand.</strong> A record's generated
     * {@code toString()} prints every component. On this type that set includes the card number at its
     * full sixteen characters, the account identifier it belongs to, the embossed cardholder name and
     * the sealed concurrency proof. Any structured logger, framework diagnostic, failed assertion,
     * exception message or string interpolation touching an instance would have emitted the primary
     * account number in full alongside the name it is embossed with - which is the disclosure a payment
     * record exists to avoid - and would additionally have written out a live integrity credential.
     *
     * <p><strong>Why nothing at all is retained, and why nothing is partially masked.</strong> The
     * account identifier and the card number look like correlation handles, and in isolation they nearly
     * are; here they are not in isolation, because they sit beside the cardholder's name on the same
     * object, so emitting either would still let a reader join a subject to a card across two log lines.
     * A partial rendering is no better: a leading or trailing fragment of a card number is still card
     * data, and a length or a digest still discriminates between candidate values. The correlation need
     * is genuine and is met properly elsewhere - by the request-scoped trace identifier the
     * observability configuration attaches to every log event - rather than by leaking a business key
     * out of a request body. The expiry parts and the status code are withheld with the rest simply
     * because a whole-object placeholder cannot leak by omission the way an enumerated renderer can when
     * a component is later added.
     *
     * <p>This override changes only the stringified form. The component accessors and the serialized
     * payload are unaffected and continue to carry the full untouched values, which the service needs
     * intact: the card number is the key of the record being updated and a shortened key selects
     * nothing.
     *
     * <p>{@code equals} and {@code hashCode} are deliberately left as the record contract generates
     * them. They compare every component by value, which is what a request contract requires, and
     * neither emits anything: an in-memory comparison is not a disclosure surface. Redaction belongs on
     * the rendering path alone.
     *
     * @return the type name followed by a fixed placeholder, carrying no component value
     */
    @Override
    public String toString() {
        return "CardUpdateRequest[" + REDACTION_PLACEHOLDER + "]";
    }
}
