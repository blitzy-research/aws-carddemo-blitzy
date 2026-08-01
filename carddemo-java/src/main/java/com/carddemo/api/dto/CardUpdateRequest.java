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
import jakarta.validation.constraints.Size;

/**
 * Immutable card-update request contract for legacy CICS transaction {@code CCUP}, derived from
 * symbolic map {@code app/cpy-bms/COCRDUP.CPY}, mapset {@code app/bms/COCRDUP.bms} and program
 * {@code app/cbl/COCRDUPC.cbl} - 1,560 lines and 48 measured procedure paragraphs. The persisted
 * layout consulted for the target field kinds is {@code app/cpy/CVACT02Y.cpy}, the 150-byte card
 * record, and the attention-key work area is {@code app/cpy/CVCRD01Y.cpy}.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy estate is read-only
 * reference: no COBOL statement and no picture clause is reproduced anywhere in this file, and every
 * assertion below is a citation into a member that remains byte-identical.
 *
 * <p><strong>Seven of the map's seventeen input families are carried here.</strong> The input group
 * {@code CCRDUPAI} begins at {@code app/cpy-bms/COCRDUP.CPY} line 17 and declares seventeen families;
 * the output redefinition {@code CCRDUPAO} begins at line 121 and is width-parallel to it. Ten
 * families are absent from this request, in three groups. Six are screen metadata produced by the
 * server - the transaction name at line 24, the two title lines at lines 30 and 48, the current date
 * at line 36, the program name at line 42 and the current time at line 54 - and they belong on the
 * corresponding response contract, which echoes them back to the operator. Two are server-produced
 * message text: the information line at line 102, width 40, and the error line at line 108, width 80.
 * Both widths are specific to this map and differ from the widths the same two families take on other
 * screens, which matters when the response contract is written and not here. The last two are the
 * function-key legends at lines 114 and 120, widths 21 and 18, carrying the fixed captions declared at
 * {@code app/bms/COCRDUP.bms} lines 162 and 167; they are 3270 furniture with no REST meaning and
 * belong nowhere.
 *
 * <p>Component order follows the declaration order of the symbolic map, which on this screen places
 * the expiry month before the expiry year and the expiry day last - lines 84, 90 and 96. That inverts
 * the order in which the persisted record holds the same three values, and it also inverts the order
 * the account-update screen uses, so the map is stated as the authority explicitly rather than
 * assumed. JSON binding is by name, so nothing downstream depends on the ordering.
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
 *     <strong>no annotation of any kind</strong>: it is not user input, so no constraint of any sort
 *     may be attached to it and no validation may ever be applied to it.
 *     <strong>The account-update screen genuinely differs</strong> - there the same-named field is
 *     unprotected and operator-editable - so the treatment given there must not be copied here.</li>
 * <li><strong>The account id is protected once the record has been fetched.</strong>
 *     {@code app/bms/COCRDUP.bms} line 84 declares it field-set, insert-cursor, normal-intensity and
 *     protected. It is carried because the service needs it to re-key the record it is about to
 *     update, and it is not an editable value.</li>
 * </ul>
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
 * <li>Account id supplied - {@code Account number not provided} (line 178); numeric and non-zero at
 *     eleven digits - {@code Account number must be a non zero 11 digit number}, declared twice, at
 *     lines 190 and 192; and the upper-case filter variant
 *     {@code ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER} at line 745, which has no space
 *     after its comma and reads "A 11" rather than "an 11".</li>
 * <li>Card number supplied - {@code Card number not provided} (line 180); numeric at sixteen digits -
 *     {@code Card number if supplied must be a 16 digit number} (line 194); and the upper-case filter
 *     variant {@code CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER} at line 789.</li>
 * <li>Embossed name supplied - {@code Card name not provided} (line 182); and letters-or-spaces -
 *     {@code Card name can only contain alphabets and spaces} (line 184).</li>
 * <li>Active status restricted to the two declared characters -
 *     {@code Card Active Status must be Y or N} (line 196).</li>
 * <li>Expiry month within the <strong>inclusive range 1 through 12</strong> -
 *     {@code Card expiry month must be between 1 and 12} (line 198). The text names the bounds
 *     unzeroed, which is itself part of the contract.</li>
 * <li>Expiry year within the <strong>inclusive range 1950 through 2099</strong> -
 *     {@code Invalid card expiry year} (line 200). The diagnostic deliberately names no range even
 *     though the rule has one.</li>
 * <li>Nothing typed at all - {@code No input received} (line 186); and nothing actually altered -
 *     {@code No change detected with respect to values fetched.} (line 188).</li>
 * </ul>
 *
 * <p>Neither range is encoded here as an annotation, a constant, a range object or a private check;
 * both are documented above and enforced by the service inside the cascade, because hoisting either
 * out of the cascade would change which single message a bad submission produces. The full catalogue,
 * declared across {@code app/cbl/COCRDUPC.cbl} lines 135 to 214 and continuing at lines 745, 789 and
 * 1023, is <strong>owned by {@code CardUpdateResponse}</strong>, which reproduces every text byte for
 * byte including the ones whose punctuation is inconsistent - the file-error prefix at line 135 ends
 * in a space; the confirmation prompt {@code Changes validated.Press F5 to save} at line 167 has no
 * space after its period while the failure notice at line 171 does; the exit notice at line 176 both
 * omits that space and carries trailing spaces; the concurrency notice at line 208 spells "some one"
 * as two words; and the placeholder at line 214 ends in four dots. <strong>Not one of those texts is
 * declared in this file.</strong>
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
 * no component is abbreviated, shortened, partially hidden or replaced by a placeholder. The card
 * number in particular is carried at its full sixteen characters because it is the key of the record
 * being updated and a shortened key selects nothing; keeping request bodies out of log output is the
 * logging configuration's responsibility and is not discharged by altering a value that the service
 * must receive intact. The one declarative constraint used, a maximum length at each component's
 * measured map width, measures and never alters, so leading and trailing spaces survive validation
 * untouched.
 *
 * <p><strong>What this request deliberately does not carry.</strong> There is no concurrency
 * component - no version, no entity tag, no timestamp and no fetched-image snapshot. The legacy
 * program detects a competing update by re-reading the record and comparing the before and after
 * images itself; the Java target uses an optimistic-locking version on the card entity, which is an
 * entity and service concern, and the conflict it raises is an exception type this file neither
 * imports nor names. There is no screen work area either: the shared work-area transfer object would
 * duplicate the attention key and both business keys already present here, so the two values actually
 * needed are carried directly. No screen artefact of any kind appears - no field-length, flag,
 * attribute, colour, highlight or cursor value, no terminal-input-area prefix, no map coordinate and
 * no error-marker character - because the attribute semantics of this map become the two-state
 * per-field error contract on {@code ErrorResponse} and nothing else.
 *
 * <p>This request is a {@code record}: immutable, constructed in one step, with no setter, no builder
 * and no code generator or annotation processor involved. It depends only on the platform library, the
 * validation API, the attention-key enumeration and one transfer object in its own package. It holds
 * no logging, no input or output, no persistence, no reflection and no business logic, and it is
 * consumed by {@code CardController} and {@code CardUpdateService}.
 *
 * @param accountId account id of the card's owning account - map field {@code ACCTSID}, width 11,
 *        declared at {@code app/cpy-bms/COCRDUP.CPY} line 60. <strong>Protected once the record has
 *        been fetched</strong> ({@code app/bms/COCRDUP.bms} line 84 declares it field-set,
 *        insert-cursor, normal and protected), so it is carried for re-keying and is not an editable
 *        value. Text, never a number, so its eleven characters and any leading zeros survive exactly.
 *        The service reports {@code Account number not provided} when it is absent and
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
 *        therefore carries <strong>no annotation of any kind - not even a width constraint</strong>,
 *        because it is not user input and any constraint on it would reject a round trip the legacy
 *        system completes. The same-named field on the account-update screen is unprotected and
 *        editable; that treatment must not be applied here. May be {@code null}.
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
 *        same screen. Read by the service; nothing here evaluates it. May be {@code null}.
 */
public record CardUpdateRequest(

        /* 1. ACCTSID, width 11, COCRDUP.CPY:60 - PROTECTED at COCRDUP.bms:84; carry-through for
         * re-keying, not an editable value. */
        @Size(max = 11) String accountId,

        /* 2. CARDSID, width 16, COCRDUP.CPY:66 - unprotected at COCRDUP.bms:96; the record key being
         * updated, carried at full width. */
        @Size(max = 16) String cardNumber,

        /* 3. CRDNAME, width 50, COCRDUP.CPY:72 - unprotected at COCRDUP.bms:107; editable. Carried
         * verbatim: the two in-place folds at COCRDUPC:1357 and COCRDUPC:1499 make a case-only
         * edit indistinguishable from no change, and the service performs that fold. */
        @Size(max = 50) String embossedName,

        /* 4. CRDSTCD, width 1, COCRDUP.CPY:78 - unprotected at COCRDUP.bms:117; editable. Raw
         * character, not the domain enumeration; the yes-or-no rule is a service check. */
        @Size(max = 1) String activeStatus,

        /* 5. EXPMON, width 2, COCRDUP.CPY:84 - unprotected at COCRDUP.bms:127; editable. Declared
         * before the year part on this map. Inclusive 1-12 bound is delegated, never annotated. */
        @Size(max = 2) String expiryMonth,

        /* 6. EXPYEAR, width 4, COCRDUP.CPY:90 - unprotected at COCRDUP.bms:135; editable. Inclusive
         * 1950-2099 bound is delegated, never annotated. */
        @Size(max = 4) String expiryYear,

        /* 7. EXPDAY, width 2, COCRDUP.CPY:96 - dark, field-set and PROTECTED at COCRDUP.bms:142.
         * Hidden protected carry-through, never user input, never validated.
         * INTENTIONALLY UNANNOTATED - DO NOT ADD ANY CONSTRAINT, NOT EVEN A WIDTH ONE. The @param
         * tag above carries the measured evidence. */
        String expiryDay,

        /* 8. CCARD-AID, width 5, CVCRD01Y:3 - typed as the enum of its 16 condition names. Nullable
         * on purpose: the legacy key mapping has no otherwise branch, so it is never defaulted. */
        KeyAction keyAction,

        /* 9. Client-echoed navigation state; carries the enter/re-enter gate on field decoration. */
        NavigationContext navigationContext) {
}
