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
import java.math.BigDecimal;

/**
 * Immutable transaction-add request contract for legacy CICS transaction {@code CT02}, derived from
 * symbolic map {@code app/cpy-bms/COTRN02.CPY}, mapset {@code app/bms/COTRN02.bms} and program
 * {@code app/cbl/COTRN02C.cbl} - 783 lines across 18 paragraphs. The persisted layout that accepted
 * values eventually reach is the 350-byte transaction record of {@code app/cpy/CVTRA05Y.cpy}.
 * Consumed by the transaction controller in the API layer and by the transaction-add service, which
 * owns every behavioural rule described below.
 *
 * <p>Provenance: repository commit {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Every line number cited here is a line
 * of the legacy member named beside it at that commit. No COBOL text is reproduced in this file; the
 * citations are pointers into a read-only reference tree that this migration never copies from.</p>
 *
 * <h2>What the map contributes, and what it deliberately omits</h2>
 *
 * <p>The symbolic map declares 21 field families. Fourteen of them are operator-editable input
 * values and are the fourteen components declared below, in map declaration order; the mapset marks
 * all fourteen unprotected and field-set, and the input and output halves of the map agree on every
 * width, so the same widths serve a request and its echo. Seven families are excluded because the
 * server produces them rather than receiving them: the six header families - the transaction name,
 * the two title lines, the current date, the program name and the current time - and the
 * seventy-eight character error-message family, all of which belong to the response contract.</p>
 *
 * <p>None of the generated 3270 plumbing is modelled. The map interleaves a length halfword, a
 * flag byte with its redefinition, four bytes of filler before each input value, and four attribute
 * bytes before each output value, behind a twelve-byte terminal-buffer header. Those are artefacts of
 * a terminal data stream, not of the business contract, so no length, attribute, colour, highlight,
 * cursor or screen-position component appears here.</p>
 *
 * <h2>There is no transaction identifier on this map, and none may be added</h2>
 *
 * <p>A mechanical search of both the symbolic map and the mapset finds no transaction-identifier
 * field of any kind: the operator never supplies one. The program derives it instead, immediately
 * before writing, by browsing the transaction file backwards from its high key, reading the highest
 * existing key, and adding one; the receiving work field is sixteen digits wide, so the result is
 * always sixteen characters with its leading zeros intact and the first identifier written against an
 * empty table is {@code "0000000000000001"} rather than {@code "1"}.</p>
 *
 * <p>Three rules follow from that, and all three bind this contract. There is no identifier
 * component, no hint at how one should be produced and no name of any generator: a database-side
 * generated identity or an auto-increment column would drift permanently from the legacy value the
 * first time a write rolled back and left a gap, so derivation is a highest-key-plus-one step inside
 * the same transaction as the write and belongs to the service alone. The identifier is likewise
 * never carried inbound, so a client cannot propose one. And the derivation is never performed here:
 * this type computes nothing at all.</p>
 *
 * <h2>Both lookup keys are optional, and neither has a default</h2>
 *
 * <p>The account key and the card key are alternative ways of naming the same cardholder record. The
 * program requires that at least one arrive and reports the omission of both at line 226, so neither
 * component may be defaulted to an empty value, coalesced away or given a placeholder: substituting
 * anything for an absent key would satisfy a check the legacy program fails, and substituting
 * anything for a present key would corrupt a lookup. Absence is expressed by {@code null} and by
 * nothing else.</p>
 *
 * <p><strong>The account key wins when both are supplied, and that decision stays in the
 * service.</strong> The key-validation construct tests the account key in its first clause at line
 * 196 and the card key only in its second at line 210, so a submission carrying both is resolved by
 * account and the card key it carried is overwritten from the cross-reference record; the mirror
 * happens when only a card key arrives. Ordered clause evaluation is the whole of that rule, and
 * clause order is preserved in the service. This record therefore declares no precedence, no
 * lookup-mode discriminator and no derived effective key - adding any of them would move a decision
 * out of the layer that owns it and let two callers disagree about which key applied.</p>
 *
 * <h2>Identifiers, codes and dates are text</h2>
 *
 * <p>The account key, the category code and the merchant identifier are declared numeric in the
 * legacy layouts and are nevertheless carried as text, because all three are fixed-width identifiers
 * whose leading zeros are contractual. A category code must serialize as {@code "0002"} and never as
 * {@code "2"}; an eleven-character account key must keep every leading zero it arrived with; a
 * merchant identifier is nine characters of digits, not a number. Coercing any of them to a
 * whole-number type would discard those zeros and shorten the external width, and that width is
 * compared directly by the byte-equivalence acceptance criterion. These are also the genuine business
 * keys of the estate rather than surrogates, so nothing here renumbers, reformats or generates one.
 * </p>
 *
 * <p><strong>The transaction source stays raw.</strong> It is ten characters, space padded, and is
 * carried exactly as received. A domain enumeration over source values does exist elsewhere in this
 * module and is deliberately not used here: mapping to it at this boundary would erase an
 * undeclared value that the legacy program accepts and stores verbatim, and would silently rewrite
 * the padding that the fixed-width persisted layout depends on. The value is neither shortened,
 * padded, case folded nor resolved to a constant anywhere in this record.</p>
 *
 * <p><strong>Both dates stay ten characters of text.</strong> The expected shape is a four-digit
 * year, a two-digit month and a two-digit day separated by hyphens, and the program checks that shape
 * positionally at line 360 for the origination date and line 375 for the processing date before
 * calling a shared date-validation utility at lines 393 and 413. That utility's acceptance test is
 * two-level: a clean severity code passes, and so does one specific advisory message number, so a
 * date the platform date library would reject outright can still be accepted. Reproducing that
 * two-level test is the business of the date-validation service, and it cannot be expressed by
 * platform date parsing at all. No date type, formatter, resolver style or parsing of any kind
 * appears in this file, and neither date is reformatted on the way through.</p>
 *
 * <h2>The amount is the only numeric component</h2>
 *
 * <p>The transaction amount is a decimal value of scale two by contract - the persisted field of
 * {@code app/cpy/CVTRA05Y.cpy} is a signed nine-integer-digit, two-fraction-digit zoned decimal, and
 * the mapped column is a fixed-point numeric of precision eleven and scale two. It is therefore an
 * exact decimal type and never a binary floating-point one, which could not represent a cent
 * exactly. The map presents the value in a signed, fixed-position external form with two fractional
 * digits, verified positionally at line 345; this record carries the numeric value alone and never
 * that external form, never a pre-rendered string and never an edited screen field.</p>
 *
 * <p><strong>No scaling, rounding or arithmetic happens here.</strong> The estate declares no
 * rounding clause anywhere, which means a store into a two-decimal field discards its surplus
 * fractional digits toward zero rather than rounding them. Reproducing that is the sole
 * responsibility of the utility-layer zoned-decimal codec, which applies one downward policy in one
 * place for the whole module; half-even and half-up policies are forbidden module-wide precisely
 * because either would differ by a cent on about half of all interest computations. Concentrating the
 * policy in the codec is only effective if nothing else scales, so this record performs no scale
 * adjustment, declares no rounding policy, and applies no decimal or number formatting.</p>
 *
 * <h2>The confirmation value is one character, not a two-state flag</h2>
 *
 * <p>The program accepts {@code Y} and {@code N} in either case, treats an absent or all-space value
 * as an unconfirmed submission that is echoed back with a prompt, and reports every other value with
 * its own message at line 184. A two-valued logical type could carry only the first two of those
 * three outcomes: the third - a value that is neither yes nor no - would have to be discarded at
 * construction, and the message the operator is contractually owed could never be produced. The
 * component is therefore a single character of text, and the yes-or-no rule is delegated in full to
 * the service. It is not a two-state flag and not an enumeration.</p>
 *
 * <h2>Why only a length bound may appear, and nothing else</h2>
 *
 * <p>The program runs <strong>23 message-bearing checks in a fixed order</strong> and the operator
 * sees exactly <strong>one</strong> message - the earliest stage that fails. Bean Validation cannot
 * express that: its constraints are evaluated in an unspecified order and it reports every violation
 * at once, so a single presence constraint on any component below would surface a message from the
 * wrong stage and break the interface contract that the acceptance criteria verify character for
 * character. Every one of the 23 checks is therefore a source-ordered, message-bearing service
 * validation, and the texts belong to the response contract rather than to this file. The stages, in
 * the order the program evaluates them, are:</p>
 *
 * <ol>
 *   <li>the confirmation value, line 184;</li>
 *   <li>the two lookup keys - account key numeric at line 199, card key numeric at line 213, neither
 *       supplied at line 226;</li>
 *   <li>eleven emptiness checks, in one ordered construct, enumerated below;</li>
 *   <li>two code-is-numeric checks - type code at line 325, category code at line 331;</li>
 *   <li>three positional shape checks - amount at line 345, origination date at line 360, processing
 *       date at line 375;</li>
 *   <li>two calendar validations through the shared date utility, called at lines 393 and 413 and
 *       reported at lines 401 and 421;</li>
 *   <li>the merchant-identifier numeric check at line 432, which the program evaluates <em>after</em>
 *       both calendar validations rather than beside the other numeric checks;</li>
 *   <li>the cross-reference lookup outcomes and the write outcome, which arise after this contract
 *       has been consumed.</li>
 * </ol>
 *
 * <p>The eleven emptiness checks share one ordered construct and must be reproduced in exactly this
 * order, because the first match ends the construct and every later check is then unreached:</p>
 *
 * <ol>
 *   <li>type code, line 254;</li>
 *   <li>category code, line 260;</li>
 *   <li>transaction source, line 266;</li>
 *   <li>description, line 272;</li>
 *   <li>amount, line 278;</li>
 *   <li>origination date, line 284;</li>
 *   <li>processing date, line 290;</li>
 *   <li>merchant identifier, line 296;</li>
 *   <li>merchant name, line 302;</li>
 *   <li>merchant city, line 308;</li>
 *   <li>merchant zip, line 314.</li>
 * </ol>
 *
 * <p>Note that the ordering is not the map's: the amount is checked for emptiness fifth, between the
 * description and the origination date, whereas the map declares it seventh. Component declaration
 * order below follows the map, and cascade order belongs to the service; the two are independent and
 * neither may be derived from the other.</p>
 *
 * <p>The single permitted constraint is therefore a maximum length, which measures a value and never
 * alters one. It is present so that a value longer than the field is rejected before it can overflow a
 * fixed-width persisted field, and it is absent from the amount because a length bound has no meaning
 * for a decimal value. No presence, emptiness, pattern, digit, range, sign, chronology or membership
 * constraint appears anywhere in this file: each would either fire ahead of the cascade and report
 * the wrong message, or reject input that the legacy program accepts.</p>
 *
 * <h2>Widths are honoured, not normalised</h2>
 *
 * <p>Three of the fourteen widths differ from the same logical field elsewhere in the estate, and the
 * divergence is preserved rather than reconciled. The description is sixty characters on this screen
 * while the persisted transaction record declares it one hundred characters wide and the
 * transaction-list screen declares it twenty-six; the merchant name is thirty characters here against
 * fifty in the persisted record; the merchant city is twenty-five here against fifty. The screen
 * width is the width the operator may enter and is what this contract enforces. No width constant is
 * shared with another transfer object, and no base record, interface or mixin spans the transaction
 * contracts - unifying them would quietly widen or narrow a screen contract that the interface
 * acceptance criterion checks directly.</p>
 *
 * <h2>Nothing is validated, defaulted or normalised in this type</h2>
 *
 * <p>There is no canonical constructor, because there is nothing for one to do. Every component may
 * legitimately be {@code null} - an entirely empty submission is a real state that the program
 * answers with a message rather than a failure - and values cross this boundary character for
 * character. Nothing here is shortened, padded, case folded, stripped of white space, canonicalised
 * or re-encoded; legacy fixed-width fields are space padded and that padding is contract, which
 * matters most for the ten-character source and the three merchant text fields. The record is a value
 * carrier: no setter, no mutable component, no collection, no static state, no builder and no
 * generated code, so instances are safe to share across threads without qualification.</p>
 *
 * <p>The card number is carried at its full sixteen characters. The legacy design applies no
 * field-level encryption or obfuscation to a primary account number or a card verification code
 * anywhere, and no requirement of this migration introduces one, so none is introduced here either;
 * that residual gap is recorded as a finding in {@code docs/decision-log.md} rather than closed by
 * unrequested work. Nothing in this record hides, shortens or re-encodes the value, and the record
 * contract's generated rendering, equality and hash behaviour stand unaltered - a deliberate,
 * documented divergence from the credential-bearing and personal-data-bearing request contracts in
 * this package, which do replace their rendering.</p>
 *
 * <p>Serialization is left entirely to the module-wide configuration, which already writes decimal
 * values in plain notation, omits absent values and tolerates unknown incoming properties. This file
 * registers no serializer, overrides no setting and suppresses no property.</p>
 *
 * @param accountId the account lookup key - map field {@code ACTIDIN}, width 11, eleven digits whose
 *     leading zeros are contract. <strong>Optional</strong>, and never defaulted: {@code null} means
 *     the operator supplied none. Tested first, at {@code COTRN02C:196}, so it wins over the card key
 *     when both arrive; checked numeric at line 199 and reported jointly with the card key at line
 *     226 when both are absent. May be {@code null}.
 * @param cardNumber the card lookup key - map field {@code CARDNIN}, width 16, the full sixteen
 *     characters of the card number, never shortened or hidden. <strong>Optional</strong>, and never
 *     defaulted. Tested second, at {@code COTRN02C:210}; checked numeric at line 213. When only the
 *     account key arrives the service fills this value from the cross-reference record. May be
 *     {@code null}.
 * @param typeCode the transaction type code - map field {@code TTYPCD}, width 2. First of the eleven
 *     emptiness checks at line 254 and first of the numeric checks at line 325. May be {@code null}.
 * @param categoryCode the transaction category code - map field {@code TCATCD}, width 4, four digits
 *     whose leading zeros are contract and which must survive as text. Emptiness checked second at
 *     line 260, numeric checked at line 331. May be {@code null}.
 * @param transactionSource the transaction source - map field {@code TRNSRC}, width 10, carried raw
 *     and space padded, never resolved to a domain constant and never stripped of its padding.
 *     Emptiness checked third at line 266. May be {@code null}.
 * @param description the transaction description - map field {@code TDESC}, width 60 on this screen,
 *     which is narrower than the persisted field and wider than the list screen; the screen width
 *     governs here. Emptiness checked fourth at line 272. May be {@code null}.
 * @param amount the transaction amount, an exact decimal of scale two by contract and never a binary
 *     floating-point value - map field {@code TRNAMT}, width 12 as an external form whose shape is
 *     checked positionally at line 345. Carries the numeric value only, with no scaling, rounding,
 *     arithmetic or formatting applied here. Emptiness checked fifth at line 278. Unannotated because
 *     a length bound has no meaning for a decimal value. May be {@code null}.
 * @param originationDate the origination date - map field {@code TORIGDT}, width 10, ten characters
 *     of text and never a date type. Shape checked positionally at line 360, then validated by the
 *     shared date utility called at line 393 and reported at line 401. Emptiness checked sixth at
 *     line 284. Never parsed or reformatted here. May be {@code null}.
 * @param processingDate the processing date - map field {@code TPROCDT}, width 10, ten characters of
 *     text and never a date type. Shape checked positionally at line 375, then validated by the
 *     shared date utility called at line 413 and reported at line 421. Emptiness checked seventh at
 *     line 290. Never parsed or reformatted here. May be {@code null}.
 * @param merchantId the merchant identifier - map field {@code MID}, width 9, nine digits whose
 *     leading zeros are contract. Emptiness checked eighth at line 296; its numeric check sits at
 *     line 432, after both calendar validations rather than with the other numeric checks. May be
 *     {@code null}.
 * @param merchantName the merchant name - map field {@code MNAME}, width 30 on this screen against
 *     fifty in the persisted record; the screen width governs here. Emptiness checked ninth at line
 *     302. May be {@code null}.
 * @param merchantCity the merchant city - map field {@code MCITY}, width 25 on this screen against
 *     fifty in the persisted record; the screen width governs here. Emptiness checked tenth at line
 *     308. May be {@code null}.
 * @param merchantZip the merchant postal code - map field {@code MZIP}, width 10. Last of the eleven
 *     emptiness checks, at line 314. May be {@code null}.
 * @param confirm the confirmation value - map field {@code CONFIRM}, width 1. A single character
 *     rather than a two-state flag, so that a value which is neither yes nor no remains reportable
 *     through the message at line 184. The yes-or-no rule is delegated entirely to the service. May
 *     be {@code null}, which the program treats as an unconfirmed submission.
 * @param keyAction the attention key the operator pressed, resolved to a domain constant. The
 *     program dispatches on it to add, to leave, to clear the screen or to copy the previous
 *     transaction's values forward, and reports an unmapped key with a common message. There is
 *     deliberately no default: an absent value is absent, not an implied enter. Folding the higher
 *     function keys onto the lower twelve happens in the utility-layer key translator, which this
 *     contract does not reach into. May be {@code null}.
 * @param navigationContext the client-echoed navigation state that replaces the communication area
 *     carried across a legacy pseudo-conversational turn. Echoed request state, not a server
 *     session, and immutable like the rest of this record. May be {@code null}.
 */
public record TransactionAddRequest(

        /* 1. ACTIDIN, width 11 - optional lookup key; first branch at COTRN02C:196. */
        @Size(max = 11) String accountId,

        /* 2. CARDNIN, width 16 - optional lookup key; second branch at COTRN02C:210. */
        @Size(max = 16) String cardNumber,

        /* 3. TTYPCD, width 2 - empty check COTRN02C:254, numeric check COTRN02C:325. */
        @Size(max = 2) String typeCode,

        /* 4. TCATCD, width 4 - empty check COTRN02C:260, numeric check COTRN02C:331. */
        @Size(max = 4) String categoryCode,

        /* 5. TRNSRC, width 10 - empty check COTRN02C:266; raw and space padded. */
        @Size(max = 10) String transactionSource,

        /* 6. TDESC, width 60 - empty check COTRN02C:272; screen width, not record width. */
        @Size(max = 60) String description,

        /* 7. TRNAMT, external width 12 - empty check COTRN02C:278, shape check COTRN02C:345.
         * Exact decimal of scale 2; unannotated because a length bound cannot apply. */
        BigDecimal amount,

        /* 8. TORIGDT, width 10 - empty check COTRN02C:284, shape check COTRN02C:360,
         * calendar check called at COTRN02C:393 and reported at COTRN02C:401. */
        @Size(max = 10) String originationDate,

        /* 9. TPROCDT, width 10 - empty check COTRN02C:290, shape check COTRN02C:375,
         * calendar check called at COTRN02C:413 and reported at COTRN02C:421. */
        @Size(max = 10) String processingDate,

        /* 10. MID, width 9 - empty check COTRN02C:296; numeric check COTRN02C:432, which the
         * program evaluates after both calendar checks rather than with the other numeric checks. */
        @Size(max = 9) String merchantId,

        /* 11. MNAME, width 30 - empty check COTRN02C:302; screen width, not record width. */
        @Size(max = 30) String merchantName,

        /* 12. MCITY, width 25 - empty check COTRN02C:308; screen width, not record width. */
        @Size(max = 25) String merchantCity,

        /* 13. MZIP, width 10 - empty check COTRN02C:314, the last of the eleven. */
        @Size(max = 10) String merchantZip,

        /* 14. CONFIRM, width 1 - one character, not a two-state flag; COTRN02C:184 must stay
         * reportable for a value that is neither yes nor no. */
        @Size(max = 1) String confirm,

        /* Attention key, resolved to a domain constant. No default; absence is absence. */
        KeyAction keyAction,

        /* Client-echoed navigation state; replaces the legacy communication area. */
        NavigationContext navigationContext) {
}
