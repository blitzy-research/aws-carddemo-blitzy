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

import com.carddemo.domain.enums.AccountStatus;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * Immutable, display-only account view response for legacy CICS transaction {@code CAVW}, derived
 * from symbolic map {@code app/cpy-bms/COACTVW.CPY}, mapset {@code app/bms/COACTVW.bms} and program
 * {@code app/cbl/COACTVWC.cbl} (941 lines).
 *
 * <p>Provenance: repository checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19, which appears in the trailer
 * comment of every legacy member including {@code app/cpy/CVACT01Y.cpy} line 19 and
 * {@code app/cpy/CVCUS01Y.cpy} line 25. No COBOL source text is reproduced here; every citation
 * names a member, a field, a width or a line number.
 *
 * <h2>The thirty-seven value items, and nothing else from the map</h2>
 *
 * <p>The symbolic map declares an input group {@code CACTVWAI} at line 17 and an output group
 * {@code CACTVWAO} that redefines it at line 241. The first thirty-seven components below are the
 * complete set of <em>value</em> items of that output group, in the order the group declares them,
 * each at the width the group declares. Six carry screen furniture, eleven carry the account,
 * eighteen carry the customer, and two carry the message lines.
 *
 * <p>Everything else the generated map declares is 3270 plumbing and is deliberately absent: the
 * twelve-byte terminal-buffer filler at line 242, the three-byte filler that precedes every output
 * field group, and the per-field colour, programmed-symbol, highlight and validation control bytes
 * that follow it. Their input-side counterparts - the halfword length item, the flag item and its
 * attribute redefinition - are equally absent. Those bytes exist so a terminal can be driven; a JSON
 * response has no terminal, so carrying them would put device state in an interface contract. For
 * the same reason there is no protected or unprotected indicator, no map coordinate, no cursor
 * position, no colour constant and no marker byte anywhere on this record, even though the program
 * sets all of them: {@code 1300-SETUP-SCREEN-ATTRS} at lines 541 to 572 writes a colour into the
 * account-id control byte, and writes a marker character into the account-id value item when the
 * filter was left empty on a resubmission. The two error states that behaviour distinguishes are
 * exposed as field states by {@link ErrorResponse} and {@link FieldErrorDecorator}, not here.
 *
 * <h2>Display-only, and therefore validating nothing</h2>
 *
 * <p>The mapset proves the screen is a display: across its 378 lines it declares exactly <strong>one
 * unprotected field, {@code ACCTSID} at line 84</strong>, and nineteen auto-skip fields. Every other
 * component of this response corresponds to a field the operator cannot type into. The account
 * identifier is the sole input, and it does not travel on this record at all in that role - it
 * arrives as a request parameter on the controller, which is why there is deliberately no
 * {@code AccountViewRequest} type.
 *
 * <p>Consequently <strong>this record validates nothing</strong>. The only constraint used anywhere
 * below is a maximum length at the measured map width, which measures and never alters a value. No
 * presence, pattern, character-class, digit-count or numeric-range constraint appears, and none may
 * be added. Each would reject data the legacy screen displays without complaint, and a response DTO
 * that refuses to render stored data is a behavioural regression rather than a safeguard.
 *
 * <p><strong>The credit score is the decisive case.</strong> The score is carried as three
 * characters because {@code CUST-FICO-CREDIT-SCORE} is three digits wide
 * ({@code app/cpy/CVCUS01Y.cpy} line 22) and the map field {@code ACSTFCOO} is three characters
 * wide (line 368). It carries <strong>no range constraint</strong>, and that is a measurement rather
 * than an oversight: the relational column has no check constraint, and in the fifty seeded customer
 * records the score occupies bytes 330 to 332, where <strong>twenty-one of the fifty values are
 * below 300 and the lowest is {@code 001}</strong>. A lower bound of 300 and an upper bound of 850
 * here would make forty-two per cent of real seeded accounts unviewable. That range is a rule of the
 * update path and belongs to {@link AccountUpdateRequest} alone. Holding the score as text also
 * keeps {@code 001} as three characters instead of collapsing it to a single digit.
 *
 * <h2>Nothing is altered on the way out</h2>
 *
 * <p>Values cross this boundary byte for byte. Nothing here shortens a value, removes padding,
 * pads a value, changes its case, reorders it, re-formats it, parses it, substitutes a default for
 * it or replaces an empty value with an absent one. Legacy fixed-width fields are space-padded and
 * that padding is part of the contract. Three measured cases make the point concrete.
 *
 * <ul>
 *   <li><strong>The account group identifier is ten spaces in every seeded row.</strong> Those ten
 *       spaces are the stored value. Discarding the padding, or reading the result as absent and
 *       substituting an empty or absent value, changes what the screen shows and breaks
 *       byte-equivalence.</li>
 *   <li><strong>Both telephone numbers arrive already formatted.</strong> The customer record holds
 *       fifteen characters ({@code CUST-PHONE-NUM-1} and {@code CUST-PHONE-NUM-2} at
 *       {@code app/cpy/CVCUS01Y.cpy} lines 15 and 16) whose seeded content is a parenthesised area
 *       code, the exchange, a hyphen and the line number, thirteen characters of text followed by
 *       two spaces. The map fields are thirteen characters wide (lines 428 and 440), so the screen
 *       carries exactly the formatted text. It is transported unchanged: not parsed, not split into
 *       parts, not re-formatted and not checked against the area-code tables, all of which belong to
 *       the update path.</li>
 *   <li><strong>The displayed national identifier is assembled by the program, not by this
 *       record.</strong> The stored field is nine digits ({@code app/cpy/CVCUS01Y.cpy} line 17) but
 *       the map field is twelve characters (line 356), because {@code 1200-SETUP-SCREEN-VARS}
 *       abandons a direct move - left commented out at {@code app/cbl/COACTVWC.cbl} line 495 - and
 *       instead builds a hyphenated three-two-four form at lines 496 to 504: nine digits and two
 *       hyphens inside a twelve-character field. This record carries the assembled result and
 *       performs no assembly of its own.</li>
 * </ul>
 *
 * <h2>Identifiers are text, never numbers</h2>
 *
 * <p>The account identifier is carried as eleven characters and the customer identifier as nine,
 * matching {@code ACCTSIDO} at line 284 and {@code ACSTNUMO} at line 350 of the symbolic map. Both
 * are declared as digits in their record layouts - {@code ACCT-ID} at {@code app/cpy/CVACT01Y.cpy}
 * line 5 and {@code CUST-ID} at {@code app/cpy/CVCUS01Y.cpy} line 5 - and both are nonetheless text
 * here, because leading zeros and fixed external widths are contractual and are compared directly by
 * the byte-equivalence acceptance criterion. A seeded identifier such as {@code 00000000011} must
 * round-trip as those eleven characters and never as eleven. These are also the genuine business
 * keys of the estate rather than surrogates, so nothing here is generated or renumbered.
 *
 * <p><strong>A typing asymmetry in the map is worth recording, because it is the only one of its
 * kind.</strong> The input twin of the account identifier, {@code ACCTSIDI} at line 60, is declared
 * as eleven digits and is the sole non-character input item across all seventeen symbolic maps in the
 * estate; the mapset reinforces it at line 84 with an input picture of eleven digits and a
 * must-fill validation. The <em>output</em> twin at line 284 is eleven characters. This response is
 * the output side, so eleven characters is the correct and faithful width.
 *
 * <p>The account identifier may also legitimately be absent. {@code 1200-SETUP-SCREEN-VARS} clears
 * the output item when the filter was left empty ({@code app/cbl/COACTVWC.cbl} line 466) and moves
 * the working identifier into it otherwise (line 468), so an empty account-number field is a real
 * displayed state and every component here tolerates {@code null}.
 *
 * <h2>The five monetary values</h2>
 *
 * <p>Five output items - {@code ACRDLIMO} at line 302, {@code ACSHLIMO} at line 314,
 * {@code ACURBALO} at line 326, {@code ACRCYCRO} at line 332 and {@code ACRCYDBO} at line 344 -
 * are declared in the symbolic map with a numeric-edited picture: a leading sign position,
 * zero-suppressed digit positions grouped by commas, and two fixed decimal positions. The mapset
 * declares the same edit five times, at lines 120, 141, 162, 174 and 195, as the output picture of
 * the corresponding fields.
 *
 * <p><strong>That screen edit is not reproduced.</strong> It is 3270 presentation - a sign glyph,
 * suppressed leading zeros and group separators laid out for a fixed column position - and it has no
 * place in a machine contract. The five components are decimal values, and their underlying record
 * fields are all signed with ten integral and two decimal digits: {@code ACCT-CURR-BAL},
 * {@code ACCT-CREDIT-LIMIT} and {@code ACCT-CASH-CREDIT-LIMIT} at {@code app/cpy/CVACT01Y.cpy}
 * lines 7, 8 and 9, and {@code ACCT-CURR-CYC-CREDIT} and {@code ACCT-CURR-CYC-DEBIT} at lines 13
 * and 14. The relational columns are exact decimals of precision twelve and scale two. A decimal
 * type with an exact scale is therefore the only faithful representation; an approximate binary
 * numeric type would violate the mapping table's requirement of identical decimal precision, and a
 * pre-formatted string would smuggle the screen edit back into the contract.
 *
 * <p><strong>Scale two is a contract this record carries, never one it applies.</strong> No scaling,
 * rounding, negation, absolute value, group separation, sign formatting or arithmetic of any kind
 * happens here or anywhere in this package. Conversion between the legacy zoned representation and a
 * decimal value is the single responsibility of the codec in the utility layer, reached through the
 * service layer, and it truncates toward zero because the estate contains no rounding clause
 * anywhere - so every legacy store into a two-decimal field discards the excess rather than rounding
 * it. Applying a scale here would duplicate that policy at a second site and invite the two to
 * diverge.
 *
 * <p>Serialization needs no help. The module configuration already renders decimals plainly rather
 * than in scientific notation, already omits absent properties rather than emitting nulls, and
 * already tolerates unknown properties on the way in. This record therefore declares no serializer,
 * no serialization annotation and no unknown-property setting.
 *
 * <h2>Dates remain text</h2>
 *
 * <p>Four items are dates and all four are ten characters: the open date at line 296, the expiration
 * date at line 308, the reissue date at line 320 and the date of birth at line 362. They are carried
 * as text and are never converted to a date or timestamp type, never re-formatted, never normalised
 * and never padded. The stored forms are ten-character fields in the record layouts
 * ({@code app/cpy/CVACT01Y.cpy} lines 10, 11 and 12; {@code app/cpy/CVCUS01Y.cpy} line 19) and a
 * conversion would impose a calendar interpretation that the legacy display does not perform, then
 * lose whatever the field actually contains when the interpretation fails.
 *
 * <p><strong>A legacy misspelling is recorded rather than reproduced.</strong> The account record
 * declares the expiration date as {@code ACCT-EXPIRAION-DATE} at {@code app/cpy/CVACT01Y.cpy} line
 * 11, missing a letter, and the program moves that field into the map item at
 * {@code app/cbl/COACTVWC.cbl} line 488 using the same spelling. The card record repeats the defect
 * in {@code CARD-EXPIRAION-DATE}. The layout position is preserved exactly, so record images stay
 * byte-compatible, while the Java component below is spelled correctly. The defect is entered in
 * {@code docs/decision-log.md} rather than propagated into a Java identifier.
 *
 * <h2>Two map items whose source is not what their name suggests</h2>
 *
 * <p>Both are recorded because a later reader would otherwise assume a mapping that does not exist,
 * and both are service-layer concerns that this record neither performs nor conceals.
 *
 * <ul>
 *   <li>The city item at line 416 is populated from the <strong>third address line</strong>,
 *       {@code CUST-ADDR-LINE-3} ({@code app/cbl/COACTVWC.cbl} line 513). There is no city field in
 *       the customer layout.</li>
 *   <li>The postal-code item at line 410 is five characters and is populated from the ten-character
 *       {@code CUST-ADDR-ZIP} (line 515), so the screen shows the leading five characters of a wider
 *       stored value. The narrowing happens where the value is projected, never here.</li>
 * </ul>
 *
 * <h2>Message widths follow the map, not the working field</h2>
 *
 * <p>The informational line is forty-five characters and the error line is seventy-eight, matching
 * {@code INFOMSGO} at line 458 and {@code ERRMSGO} at line 464. The program's own working fields are
 * narrower - forty and seventy-five characters at {@code app/cbl/COACTVWC.cbl} lines 110 and 117 -
 * and are moved into the wider map items at lines 534 and 532. The map width is the externally
 * observable contract, so the map width is what is declared here.
 *
 * <h2>Composition, and what it costs to get right</h2>
 *
 * <p>The transaction joins three records to fill one screen: it resolves the card cross-reference by
 * account, then reads the account, then reads the customer, in the paragraphs beginning at
 * {@code app/cbl/COACTVWC.cbl} lines 723, 774 and 825. <strong>None of that happens here.</strong>
 * This record is the flattened result. It performs no lookup, no join and no navigation, holds no
 * repository, service or configuration reference, and reads no legacy source file at run time. The
 * cross-reference finder returns a list rather than a single optional value, which is noted only so
 * that no resolution logic is ever tempted into a data-transfer type.
 *
 * <h2>Routing is declarative</h2>
 *
 * <p>The estate dispatches program to program with transfer-of-control commands and re-arms the next
 * turn with return-with-transaction commands. All of them become route values returned in a response
 * body, so the client drives the next call and the server forwards nothing. The route component
 * below is therefore an opaque string: this record declares <strong>no route table, no route
 * constant, no route enumeration and no dispatch method</strong>. Naming and resolving routes belongs
 * to the service layer, and a shared top-level route holder is excluded module-wide.
 *
 * <h2>Sensitive values are transported, never altered</h2>
 *
 * <p>This response carries a national identifier in its displayed hyphenated form and a
 * government-issued identifier of twenty characters ({@code ACSGOVTO} at line 434), because the
 * legacy screen displays both. Protecting them at rest is a persistence concern: both columns hold
 * application-produced ciphertext, and reversing that protection happens in the service layer before
 * a value ever reaches this record. <strong>Nothing here obscures, shortens, substitutes or
 * transforms any value</strong>, in either direction - doing so would change what the screen shows.
 * The national identifier is also the one intentionally nullable column in the schema and is left
 * unset by the reference seed, so that component must and does tolerate an absent value with no
 * constraint rejecting it. This response carries neither a card number nor a card verification code,
 * and the legacy design protects neither at field level anywhere; that gap is recorded as a finding
 * in {@code docs/decision-log.md} and is deliberately not closed by unrequested work here.
 *
 * <p>No credential of any kind appears on this record and none may be added. There is no logger,
 * console write or stack trace anywhere in this file, so nothing here emits a value to a log.
 * Diagnostic rendering is left exactly as the record contract generates it, which is a deliberate
 * and measured divergence from {@link NavigationContext} and {@link AccountUpdateRequest}: those two
 * are request-side and echoed-state types that framework binding, validation and session paths can
 * stringify outside this module's control, whereas this type is constructed by a service, returned
 * to the one operator already authorised to see the screen, and serialized by the configured mapper.
 * Equality is likewise exactly what the record contract generates, comparing every component by
 * value, which is what a response contract requires.
 *
 * <h2>A source anomaly recorded for traceability</h2>
 *
 * <p>The program declares the paragraph {@code 0000-MAIN-EXIT} <strong>twice, at lines 408 and 411
 * of {@code app/cbl/COACTVWC.cbl}</strong>. Of the thirty-eight area-A labels in the member,
 * thirty-five belong to the procedure division, and the duplication is one of them. The two labels
 * collapse to a single method in {@code AccountViewService} and the defect is entered in
 * {@code docs/decision-log.md}. Nothing in this record changes because of it; it is recorded here so
 * the traceability matrix accounts for both line numbers.
 *
 * @param transactionName the transaction identifier shown top-left, from {@code TRNNAMEO}
 *     (four characters, map line 248). Populated from the program's own
 *     transaction-identifier literal at {@code app/cbl/COACTVWC.cbl} line 438. May be {@code null}.
 * @param screenTitle1 the first title line, from {@code TITLE01O} (forty
 *     characters, map line 254). Populated from the shared title copybook at
 *     {@code app/cbl/COACTVWC.cbl} line 436. May be {@code null}.
 * @param currentDate the current date as shown, from {@code CURDATEO} (eight
 *     characters, map line 260). The program assembles a two-digit month, day and year into exactly
 *     eight characters at {@code app/cbl/COACTVWC.cbl} line 447. Carried as text: it is screen
 *     furniture, not a date value. May be {@code null}.
 * @param programName the program identifier shown top-left, from {@code PGMNAMEO}
 *     (eight characters, map line 266). Populated from the program's own name
 *     literal at {@code app/cbl/COACTVWC.cbl} line 439. May be {@code null}.
 * @param screenTitle2 the second title line, from {@code TITLE02O} (forty
 *     characters, map line 272). Populated from the shared title copybook at
 *     {@code app/cbl/COACTVWC.cbl} line 437. May be {@code null}.
 * @param currentTime the current time as shown, from {@code CURTIMEO} (<strong>eight</strong>
 *     characters, map line 278). Eight is measured, not assumed: the
 *     sign-on map declares its own time item one character wider, uniquely in the estate, so this
 *     width must not be copied from there. The program assembles hours, minutes and seconds at
 *     {@code app/cbl/COACTVWC.cbl} line 453. May be {@code null}.
 * @param accountId the eleven-character account identifier, from {@code ACCTSIDO}
 *     (eleven characters, map line 284). Text, never a numeric type: leading
 *     zeros and the fixed width are contractual. Its input twin at map line 60 is declared as eleven
 *     digits, the only non-character input item in any symbolic map in the estate; this is the output
 *     side. Legitimately {@code null}, which is how an empty account-number field is displayed.
 * @param accountStatus the raw one-character active-status flag, from {@code ACSTTUSO}
 *     (one character, map line 290), stored as {@code ACCT-ACTIVE-STATUS} at
 *     {@code app/cpy/CVACT01Y.cpy} line 6 and moved to the map at {@code app/cbl/COACTVWC.cbl} line
 *     473. Deliberately the raw character rather than {@link AccountStatus}: the column carries no
 *     check constraint and only the update program validates the field, so a value outside the
 *     declared vocabulary reaches the screen in the legacy system and must round-trip here rather
 *     than fail construction. {@link #resolvedAccountStatus()} interprets it without throwing. May
 *     be {@code null}.
 * @param openDate the account open date as stored, from {@code ADTOPENO} (ten
 *     characters, map line 296), stored as {@code ACCT-OPEN-DATE} at {@code app/cpy/CVACT01Y.cpy}
 *     line 10. Text, never a date type. May be {@code null}.
 * @param creditLimit the credit limit, from {@code ACRDLIMO} (map line 302), stored as
 *     {@code ACCT-CREDIT-LIMIT} at {@code app/cpy/CVACT01Y.cpy} line 8 with ten integral and two
 *     decimal digits, signed. Carried as an exact decimal at scale two, with the screen edit
 *     deliberately absent. May be {@code null}.
 * @param expirationDate the account expiration date as stored, from {@code AEXPDTO}
 *     (ten characters, map line 308). The stored field is misspelled
 *     {@code ACCT-EXPIRAION-DATE} at {@code app/cpy/CVACT01Y.cpy} line 11; the layout is preserved
 *     and this identifier is spelled correctly. Text, never a date type. May be {@code null}.
 * @param cashCreditLimit the cash credit limit, from {@code ACSHLIMO} (map line 314), stored as
 *     {@code ACCT-CASH-CREDIT-LIMIT} at {@code app/cpy/CVACT01Y.cpy} line 9. Exact decimal at scale
 *     two. May be {@code null}.
 * @param reissueDate the account reissue date as stored, from {@code AREISDTO} (ten characters,
 *     map line 320), stored as {@code ACCT-REISSUE-DATE} at
 *     {@code app/cpy/CVACT01Y.cpy} line 12. Text, never a date type. May be {@code null}.
 * @param currentBalance the current balance, from {@code ACURBALO} (map line 326), stored as
 *     {@code ACCT-CURR-BAL} at {@code app/cpy/CVACT01Y.cpy} line 7. Exact decimal at scale two, and
 *     legitimately negative - the screen edit reserves a leading sign position precisely because a
 *     balance can be. May be {@code null}.
 * @param currentCycleCredit the current cycle credit total, from {@code ACRCYCRO} (map line 332),
 *     stored as {@code ACCT-CURR-CYC-CREDIT} at {@code app/cpy/CVACT01Y.cpy} line 13. Exact decimal
 *     at scale two. May be {@code null}.
 * @param accountGroupId the account group identifier, from {@code AADDGRPO} (ten
 *     characters, map line 338), stored as {@code ACCT-GROUP-ID} at {@code app/cpy/CVACT01Y.cpy}
 *     line 16 and moved to the map at {@code app/cbl/COACTVWC.cbl} line 490. <strong>Ten spaces in
 *     all fifty seeded rows, and those ten spaces are the value.</strong> The padding is never
 *     removed and a blank value is never read as absent. May be {@code null}.
 * @param currentCycleDebit the current cycle debit total, from {@code ACRCYDBO} (map line 344),
 *     stored as {@code ACCT-CURR-CYC-DEBIT} at {@code app/cpy/CVACT01Y.cpy} line 14. Exact decimal
 *     at scale two. May be {@code null}.
 * @param customerId the nine-character customer identifier, from {@code ACSTNUMO}
 *     (nine characters, map line 350), stored as {@code CUST-ID} at
 *     {@code app/cpy/CVCUS01Y.cpy} line 5. Text, never a numeric type. May be {@code null}.
 * @param ssn the national identifier <strong>in its displayed hyphenated form</strong>, from
 *     {@code ACSTSSNO} (twelve characters, map line 356). Assembled by the
 *     program from the nine stored digits at {@code app/cbl/COACTVWC.cbl} lines 496 to 504, which is
 *     why the map field is twelve characters wide while {@code CUST-SSN} at
 *     {@code app/cpy/CVCUS01Y.cpy} line 17 is nine digits. Transported exactly as supplied and never
 *     obscured, shortened or transformed. This is the one intentionally nullable column in the
 *     schema and the reference seed leaves it unset, so {@code null} and blank are both normal and
 *     no constraint rejects either.
 * @param dateOfBirth the customer date of birth as stored, from {@code ACSTDOBO}
 *     (ten characters, map line 362), stored as {@code CUST-DOB-YYYY-MM-DD} at
 *     {@code app/cpy/CVCUS01Y.cpy} line 19. Text, never a date type. May be {@code null}.
 * @param ficoScore the credit score as stored, from {@code ACSTFCOO} (three
 *     characters, map line 368), stored as {@code CUST-FICO-CREDIT-SCORE} at
 *     {@code app/cpy/CVCUS01Y.cpy} line 22. <strong>Three characters and deliberately
 *     unconstrained.</strong> Twenty-one of the fifty seeded values are below 300 and the lowest is
 *     {@code 001}, and the column has no check constraint, so a bounded range here would refuse to
 *     display real data. Text rather than a numeric type so that {@code 001} keeps its three
 *     characters. May be {@code null}.
 * @param firstName the customer first name, from {@code ACSFNAMO} (twenty-five
 *     characters, map line 374), stored at {@code app/cpy/CVCUS01Y.cpy} line 6. Space padding is
 *     preserved exactly. May be {@code null}.
 * @param middleName the customer middle name, from {@code ACSMNAMO} (twenty-five
 *     characters, map line 380), stored at {@code app/cpy/CVCUS01Y.cpy} line 7. Space padding is
 *     preserved exactly. May be {@code null}.
 * @param lastName the customer last name, from {@code ACSLNAMO} (twenty-five
 *     characters, map line 386), stored at {@code app/cpy/CVCUS01Y.cpy} line 8. Space padding is
 *     preserved exactly. May be {@code null}.
 * @param addressLine1 the first address line, from {@code ACSADL1O} (fifty
 *     characters, map line 392), stored at {@code app/cpy/CVCUS01Y.cpy} line 9. May be {@code null}.
 * @param stateCode the state code, from {@code ACSSTTEO} (two characters, map
 *     line 398), stored as {@code CUST-ADDR-STATE-CD} at {@code app/cpy/CVCUS01Y.cpy} line 12. The
 *     map declares it between the two address lines and that order is followed here rather than
 *     conventional postal order. Not checked against the state table, which belongs to the update
 *     path. May be {@code null}.
 * @param addressLine2 the second address line, from {@code ACSADL2O} (fifty
 *     characters, map line 404), stored at {@code app/cpy/CVCUS01Y.cpy} line 10. May be
 *     {@code null}.
 * @param zipCode the postal code as shown, from {@code ACSZIPCO} (five
 *     characters, map line 410). Populated from the ten-character {@code CUST-ADDR-ZIP}
 *     ({@code app/cpy/CVCUS01Y.cpy} line 14) at {@code app/cbl/COACTVWC.cbl} line 515, so the screen
 *     shows the leading five characters of a wider stored value; the narrowing happens where the
 *     value is projected, never here. May be {@code null}.
 * @param city the city as shown, from {@code ACSCITYO} (fifty characters, map
 *     line 416). Populated from the <strong>third</strong> address line,
 *     {@code CUST-ADDR-LINE-3} ({@code app/cpy/CVCUS01Y.cpy} line 11), at
 *     {@code app/cbl/COACTVWC.cbl} line 513; the customer layout declares no city field. May be
 *     {@code null}.
 * @param countryCode the country code, from {@code ACSCTRYO} (three characters,
 *     map line 422), stored as {@code CUST-ADDR-COUNTRY-CD} at {@code app/cpy/CVCUS01Y.cpy} line 13.
 *     May be {@code null}.
 * @param phoneNumber1 the primary telephone number as shown, from {@code ACSPHN1O}
 *     (thirteen characters, map line 428). The stored field is fifteen characters
 *     ({@code CUST-PHONE-NUM-1} at {@code app/cpy/CVCUS01Y.cpy} line 15) holding thirteen characters
 *     of already-formatted text and two trailing spaces. Carried verbatim: never parsed, split,
 *     re-formatted or checked against the area-code tables. May be {@code null}.
 * @param governmentIssuedId the government-issued identifier, from {@code ACSGOVTO}
 *     (twenty characters, map line 434), stored as {@code CUST-GOVT-ISSUED-ID} at
 *     {@code app/cpy/CVCUS01Y.cpy} line 18. Transported exactly as supplied; protection at rest is a
 *     persistence concern and is undone in the service layer before it reaches this record. May be
 *     {@code null}.
 * @param phoneNumber2 the secondary telephone number as shown, from {@code ACSPHN2O}
 *     (thirteen characters, map line 440), stored as {@code CUST-PHONE-NUM-2} at
 *     {@code app/cpy/CVCUS01Y.cpy} line 16. Carried verbatim, exactly as the primary number. May be
 *     {@code null}.
 * @param eftAccountId the electronic-transfer account identifier, from {@code ACSEFTCO}
 *     (ten characters, map line 446), stored as {@code CUST-EFT-ACCOUNT-ID} at
 *     {@code app/cpy/CVCUS01Y.cpy} line 20. Text, never a numeric type. May be {@code null}.
 * @param primaryCardHolderIndicator the primary-cardholder flag, from {@code ACSPFLGO}
 *     (one character, map line 452), stored as
 *     {@code CUST-PRI-CARD-HOLDER-IND} at {@code app/cpy/CVCUS01Y.cpy} line 21. Carried as the raw
 *     character and not resolved to a boolean: the layout attaches no condition name to it and no
 *     program in the estate compares it against a literal, so inventing a two-valued vocabulary
 *     would assert something the source does not. May be {@code null}.
 * @param infoMessage the informational line, from {@code INFOMSGO} (forty-five
 *     characters, map line 458). The program's working field is forty characters
 *     ({@code app/cbl/COACTVWC.cbl} line 110) and is moved into the wider map item at line 534; the
 *     map width is the observable contract and is what is declared. Reproduced verbatim, because
 *     screen message text is an external contract. May be {@code null}.
 * @param errorMessage the error line, from {@code ERRMSGO} (seventy-eight
 *     characters, map line 464). The program's working field is seventy-five characters
 *     ({@code app/cbl/COACTVWC.cbl} line 117) and is moved into the wider map item at line 532.
 *     Reproduced verbatim. Its presence does <strong>not</strong> imply {@code inputError}: the two
 *     are independent, and the flag is the authority. May be {@code null}.
 * @param inputError whether the request was rejected, stated <strong>explicitly</strong> and never
 *     inferred from the presence of a message. The legacy authority is the dedicated one-character
 *     working flag at {@code app/cbl/COACTVWC.cbl} line 50, whose condition names enumerate accepted
 *     at line 51, rejected at line 52 and not-yet-evaluated at line 53. A primitive rather than a
 *     boxed value, so the contract has two states and not three: the legacy third state means the
 *     edit has not run, which cannot be true of a response that has already been produced.
 * @param focusScreenFieldId the legacy screen field identifier that should receive input focus, or
 *     {@code null} when the response nominates none. Bounded at eight characters because generated
 *     map field names are at most seven, the generator reserving the eighth position for the suffix
 *     it appends when deriving the control items; the bound therefore cannot reject a legitimate
 *     identifier. On this screen the program positions the cursor on the account-number field in
 *     every branch of its evaluation at {@code app/cbl/COACTVWC.cbl} lines 549 and 551, so that is
 *     the only value it ever nominates. Named to match the equivalent hint on
 *     {@link ErrorResponse}. Carried as the legacy identifier rather than as a coordinate: no cursor
 *     position, row or column appears on this record.
 * @param nextRoute the declarative route the client should call next, or {@code null} when the
 *     response nominates none. An opaque string deliberately: the service layer owns the route
 *     vocabulary, no route table or enumeration exists here, and no forwarding occurs on the server.
 *     Unconstrained in length because a route is not a legacy fixed-width field and has no measured
 *     width to bound it by; inventing one would assert a limit no source declares.
 * @param navigationContext the client-echoed conversation state to return with this response, or
 *     {@code null} when there is none. Replaces the communication area the legacy transaction
 *     carried across a pseudo-conversational turn, and is echoed rather than held: there is no
 *     server-side session behind it.
 */
public record AccountViewResponse(

        /* ------------------------------------------------------------------
         * Screen furniture: items 1-6 of the output group. Populated by
         * 1100-SCREEN-INIT at app/cbl/COACTVWC.cbl lines 431-455.
         * ------------------------------------------------------------------ */

        /* 1. TRNNAMEO, width 4, COACTVW.CPY line 248. */
        @Size(max = 4) String transactionName,

        /* 2. TITLE01O, width 40, COACTVW.CPY line 254. */
        @Size(max = 40) String screenTitle1,

        /* 3. CURDATEO, width 8, COACTVW.CPY line 260. */
        @Size(max = 8) String currentDate,

        /* 4. PGMNAMEO, width 8, COACTVW.CPY line 266. */
        @Size(max = 8) String programName,

        /* 5. TITLE02O, width 40, COACTVW.CPY line 272. */
        @Size(max = 40) String screenTitle2,

        /* 6. CURTIMEO, width 8, COACTVW.CPY line 278 - EIGHT, measured. The sign-on map declares
         * its own time item one character wider; that width belongs to that map alone. */
        @Size(max = 8) String currentTime,

        /* ------------------------------------------------------------------
         * Account: items 7-17. Populated by 1200-SETUP-SCREEN-VARS at
         * app/cbl/COACTVWC.cbl lines 466-490.
         * ------------------------------------------------------------------ */

        /* 7. ACCTSIDO, width 11, COACTVW.CPY line 284 - the OUTPUT twin. Text, never numeric: the
         * input twin at line 60 is the estate's only non-character input item, and this is not it. */
        @Size(max = 11) String accountId,

        /* 8. ACSTTUSO, width 1, COACTVW.CPY line 290. Raw character so an undeclared code
         * round-trips; see resolvedAccountStatus() below. */
        @Size(max = 1) String accountStatus,

        /* 9. ADTOPENO, width 10, COACTVW.CPY line 296. Text, never a date type. */
        @Size(max = 10) String openDate,

        /* 10. ACRDLIMO, COACTVW.CPY line 302. Exact decimal, scale 2; the screen edit is absent by
         * design and no scaling happens on this record. */
        BigDecimal creditLimit,

        /* 11. AEXPDTO, width 10, COACTVW.CPY line 308. Stored field name is misspelled in
         * CVACT01Y.cpy line 11; this identifier is spelled correctly. */
        @Size(max = 10) String expirationDate,

        /* 12. ACSHLIMO, COACTVW.CPY line 314. Exact decimal, scale 2. */
        BigDecimal cashCreditLimit,

        /* 13. AREISDTO, width 10, COACTVW.CPY line 320. Text, never a date type. */
        @Size(max = 10) String reissueDate,

        /* 14. ACURBALO, COACTVW.CPY line 326. Exact decimal, scale 2; legitimately negative. */
        BigDecimal currentBalance,

        /* 15. ACRCYCRO, COACTVW.CPY line 332. Exact decimal, scale 2. */
        BigDecimal currentCycleCredit,

        /* 16. AADDGRPO, width 10, COACTVW.CPY line 338. ten spaces in all 50 seeded rows and those
         * spaces are the value - the padding is never removed and blank is never read as absent. */
        @Size(max = 10) String accountGroupId,

        /* 17. ACRCYDBO, COACTVW.CPY line 344. Exact decimal, scale 2. */
        BigDecimal currentCycleDebit,

        /* ------------------------------------------------------------------
         * Customer: items 18-35. Populated by 1200-SETUP-SCREEN-VARS at
         * app/cbl/COACTVWC.cbl lines 494-522.
         * ------------------------------------------------------------------ */

        /* 18. ACSTNUMO, width 9, COACTVW.CPY line 350. Text, never numeric. */
        @Size(max = 9) String customerId,

        /* 19. ACSTSSNO, width 12, COACTVW.CPY line 356 - the DISPLAYED hyphenated form the program
         * assembles at COACTVWC.cbl 496-504 from nine stored digits. Nullable in the schema and
         * unset by the reference seed, so null and blank are both normal here. */
        @Size(max = 12) String ssn,

        /* 20. ACSTDOBO, width 10, COACTVW.CPY line 362. Text, never a date type. */
        @Size(max = 10) String dateOfBirth,

        /* 21. ACSTFCOO, width 3, COACTVW.CPY line 368. NO RANGE CONSTRAINT - DO NOT ADD ONE. The
         * column has no check constraint and 21 of the 50 seeded values are below 300, the lowest
         * being 001; the 300-850 rule belongs to AccountUpdateRequest alone. */
        @Size(max = 3) String ficoScore,

        /* 22. ACSFNAMO, width 25, COACTVW.CPY line 374. */
        @Size(max = 25) String firstName,

        /* 23. ACSMNAMO, width 25, COACTVW.CPY line 380. */
        @Size(max = 25) String middleName,

        /* 24. ACSLNAMO, width 25, COACTVW.CPY line 386. */
        @Size(max = 25) String lastName,

        /* 25. ACSADL1O, width 50, COACTVW.CPY line 392. */
        @Size(max = 50) String addressLine1,

        /* 26. ACSSTTEO, width 2, COACTVW.CPY line 398 - declared between the two address lines;
         * map order is followed rather than postal order. */
        @Size(max = 2) String stateCode,

        /* 27. ACSADL2O, width 50, COACTVW.CPY line 404. */
        @Size(max = 50) String addressLine2,

        /* 28. ACSZIPCO, width 5, COACTVW.CPY line 410 - fed from the ten-character stored postal
         * code at COACTVWC.cbl 515; the narrowing happens there, never here. */
        @Size(max = 5) String zipCode,

        /* 29. ACSCITYO, width 50, COACTVW.CPY line 416 - fed from the THIRD address line at
         * COACTVWC.cbl 513. The customer layout declares no city field. */
        @Size(max = 50) String city,

        /* 30. ACSCTRYO, width 3, COACTVW.CPY line 422. */
        @Size(max = 3) String countryCode,

        /* 31. ACSPHN1O, width 13, COACTVW.CPY line 428 - already-formatted text carried verbatim;
         * never parsed, split or re-formatted. */
        @Size(max = 13) String phoneNumber1,

        /* 32. ACSGOVTO, width 20, COACTVW.CPY line 434. Transported exactly as supplied. */
        @Size(max = 20) String governmentIssuedId,

        /* 33. ACSPHN2O, width 13, COACTVW.CPY line 440 - as the primary number. */
        @Size(max = 13) String phoneNumber2,

        /* 34. ACSEFTCO, width 10, COACTVW.CPY line 446. Text, never numeric. */
        @Size(max = 10) String eftAccountId,

        /* 35. ACSPFLGO, width 1, COACTVW.CPY line 452. Raw character: the layout attaches no
         * condition name and no program compares it against a literal. */
        @Size(max = 1) String primaryCardHolderIndicator,

        /* ------------------------------------------------------------------
         * Message lines: items 36-37. Widths follow the MAP, not the program's
         * narrower working fields. Moved at app/cbl/COACTVWC.cbl 534 and 532.
         * ------------------------------------------------------------------ */

        /* 36. INFOMSGO, width 45, COACTVW.CPY line 458. Text reproduced verbatim. */
        @Size(max = 45) String infoMessage,

        /* 37. ERRMSGO, width 78, COACTVW.CPY line 464. Text reproduced verbatim. Presence does NOT
         * imply inputError; the flag below is the authority. */
        @Size(max = 78) String errorMessage,

        /* ------------------------------------------------------------------
         * Response content beyond the map. Not map items, so no map width
         * applies to any of them.
         * ------------------------------------------------------------------ */

        /* 38. Explicit rejection flag, never inferred from message presence. Legacy authority is
         * the one-character working flag at app/cbl/COACTVWC.cbl 50 and its condition names at
         * 51-53. A primitive: two states, not three. */
        boolean inputError,

        /* 39. Legacy screen field identifier to focus, bounded at 8 because generated map field
         * names are at most 7 characters. On this screen the program always nominates the
         * account-number field (COACTVWC.cbl 549 and 551). No coordinate, no cursor position. */
        @Size(max = 8) String focusScreenFieldId,

        /* 40. Opaque declarative route. No route table, constant, enumeration or dispatch here;
         * unbounded because a route has no legacy width to bound it by. */
        String nextRoute,

        /* 41. Client-echoed conversation state; no server-side session stands behind it. */
        NavigationContext navigationContext) {

    /**
     * Interprets the raw status character without throwing.
     *
     * <p>The raw character is what this record carries and what a client echoes; this is a derived
     * projection over it, exactly as {@link NavigationContext#resolvedUserType()} is over the raw
     * user-type character. Resolution is delegated to
     * {@link AccountStatus#fromCode(String)}, which tolerates an absent, empty, over-wide or
     * undeclared value by returning an empty result and applies no case folding, because the legacy
     * editor tests the raw character. An empty result is therefore a normal answer and not an error:
     * the status column carries no check constraint, only the account-update program validates the
     * field, and every batch reader takes the value straight from the record, so a value outside the
     * declared vocabulary genuinely reaches this screen in the legacy system.
     *
     * <p>Nothing is validated, defaulted or substituted: this method reports and never alters, and
     * {@link #accountStatus()} keeps returning the unmodified character regardless of what this
     * returns.
     *
     * @return the matching constant, or an empty {@link Optional} when the carried character is
     *     absent or outside the declared vocabulary
     */
    public Optional<AccountStatus> resolvedAccountStatus() {
        return AccountStatus.fromCode(this.accountStatus);
    }

    /**
     * Reports whether the carried status character is the active code.
     *
     * <p>The screen labels this field as an active yes-or-no indicator at
     * {@code app/bms/COACTVW.bms} line 96, so the predicate is the field's own documented meaning
     * rather than an interpretation added here. Delegates to
     * {@link AccountStatus#isActiveCode(String)}, which never throws and answers {@code false} for
     * an absent or undeclared value rather than raising - an unresolvable status is reported as not
     * active, never as a failure.
     *
     * @return {@code true} only when the carried character is the active code
     */
    public boolean active() {
        return AccountStatus.isActiveCode(this.accountStatus);
    }
}
