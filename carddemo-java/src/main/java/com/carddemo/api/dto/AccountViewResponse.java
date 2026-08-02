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
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * Immutable, display-only account view response for legacy transaction {@code CAVW}: the thirty-seven
 * value items of the symbolic map {@code app/cpy-bms/COACTVW.CPY} in map order, plus four response-only
 * components - the rejection flag, the field to focus, the next route and the echoed navigation state.
 *
 * <p><strong>Display-only, so it validates nothing.</strong> The mapset declares a single unprotected
 * input field and the transaction only reads, so the only constraint used here is a width bound taken
 * from the map - the map width being the externally observable contract even where the program's own
 * working field is narrower. The credit score is the decisive case: it carries no range constraint,
 * because twenty-one of the fifty seeded customers score below the screen's documented range and a
 * constraint would reject the reference data itself.
 *
 * <p><strong>Nothing is altered on the way out.</strong> Values arrive as the service layer projected
 * them and are transported verbatim, padding and formatting included. Monetary components are exact
 * decimals of scale two, and no scaling, rounding or sign styling happens here; the legacy screen's sign
 * glyph is 3270 presentation and is deliberately not reproduced. Dates and identifiers stay text so that
 * leading zeros and external widths survive. Items whose width or source differs from the stored field
 * are marked on their own component below.
 *
 * <p>The transaction joins the card cross-reference, the account and the customer to fill one screen;
 * this record is the flattened result and performs no lookup, join, formatting or navigation. The route is
 * an opaque value - naming and resolving routes belongs to the service layer.
 *
 * <p><strong>Regulated values are transported, never altered.</strong> The response carries the displayed
 * national identifier and the government-issued identifier because the legacy screen displays both;
 * decryption happens in the service layer before a value reaches here, and nothing here masks, shortens
 * or substitutes anything, since that would change what the screen shows. An instance therefore holds
 * regulated response data and its generated {@code toString()} renders every component: an instance must
 * not be written to unrestricted diagnostics, echoed into an error payload or attached to a monitoring
 * event, and a caller needing to log this exchange logs it through a surface that redacts identifiers.
 * The record carries no credential, no card number and no verification code, and none may be added; the
 * legacy design protects neither a card number nor a verification code at field level anywhere, which is
 * recorded in {@code docs/decision-log.md} rather than closed by unrequested work.
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
 * <p><strong>Scale two is a contract this record states and checks, never one it applies.</strong>
 * No scaling, rounding, negation, absolute value, group separation, sign formatting or arithmetic of
 * any kind happens here or anywhere in this package. Conversion between the legacy zoned
 * representation and a decimal value is the single responsibility of the codec in the utility layer,
 * reached through the service layer, and it truncates toward zero because the estate contains no
 * rounding clause anywhere - so every legacy store into a two-decimal field discards the excess
 * rather than rounding it. Applying a scale here would duplicate that policy at a second site and
 * invite the two to diverge.
 *
 * <p>Stating it and checking it are a different matter, and both are needed. A decimal type is
 * unbounded, so the declaration alone tells a reader nothing: a value of any scale and any magnitude
 * satisfies it while breaking the contract, and precision twelve with scale two is the whole of the
 * numeric agreement. Each of the five therefore carries schema documentation naming its record
 * field, its total precision and its scale, which is what reaches the published interface
 * description a client can actually read, and the canonical constructor refuses a value whose scale
 * is not two or whose integer part needs more than ten digits.
 *
 * <p>Refusal is the only available response, not a preference. The alternative to refusing a
 * wrongly-shaped value is re-scaling it, and re-scaling is precisely the rounding decision the
 * preceding paragraph concentrates in one place; a display-only response quietly altering a monetary
 * figure would be the worst site in the module for it. A value that does not describe its record
 * field is a fault in the caller and is reported as one, and the check reads only the value's own
 * scale and precision without performing arithmetic on it. A declarative digit-count annotation was
 * considered instead and rejected twice over: nothing validates an outbound response, so it would
 * enforce nothing, and the schema generator does not map that annotation, so it would publish
 * nothing either.
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
 * @param title01 the first title line, from {@code TITLE01O} (forty
 *     characters, map line 254). Populated from the shared title copybook at
 *     {@code app/cbl/COACTVWC.cbl} line 436. Named for the map item it carries, which is the
 *     spelling every screen contract in this package uses. May be {@code null}.
 * @param currentDate the current date as shown, from {@code CURDATEO} (eight
 *     characters, map line 260). The program assembles a two-digit month, day and year into exactly
 *     eight characters at {@code app/cbl/COACTVWC.cbl} line 447. Carried as text: it is screen
 *     furniture, not a date value. May be {@code null}.
 * @param programName the program identifier shown top-left, from {@code PGMNAMEO}
 *     (eight characters, map line 266). Populated from the program's own name
 *     literal at {@code app/cbl/COACTVWC.cbl} line 439. May be {@code null}.
 * @param title02 the second title line, from {@code TITLE02O} (forty
 *     characters, map line 272). Populated from the shared title copybook at
 *     {@code app/cbl/COACTVWC.cbl} line 437. Named for the map item it carries, matching
 *     {@code title01}. May be {@code null}.
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
 *     {@code null} when the response nominates none. Bounded at <strong>seven</strong> characters,
 *     which is the widest map field name that exists: the map generator reserves the eighth position
 *     of a symbolic name for the suffix it appends when deriving the control items, so a declared
 *     field name can never occupy it, and no field name in any of the estate's seventeen mapsets
 *     does - the longest in this one are exactly seven. The bound is therefore the exact width of
 *     the vocabulary rather than a margin above it, which is what makes it the same bound every
 *     other screen contract in this package carries. On this screen the program positions the cursor
 *     on the account-number field in every branch of its evaluation at
 *     {@code app/cbl/COACTVWC.cbl} lines 549 and 551, so that is the only value it ever nominates.
 *     Named to match the equivalent hint on {@link ErrorResponse}. Carried as the legacy identifier
 *     rather than as a coordinate: no cursor position, row or column appears on this record.
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

        @Size(max = 4) String transactionName,

        /* 2. TITLE01O, width 40, COACTVW.CPY line 254. Named for the map item, which is the one
         * spelling every screen contract in this package shares. */
        @Size(max = 40) String title01,

        @Size(max = 8) String currentDate,

        @Size(max = 8) String programName,

        /* 5. TITLE02O, width 40, COACTVW.CPY line 272. */
        @Size(max = 40) String title02,

        /* Eight characters, measured on the map; the sign-on map declares its own time item at another width. */
        @Size(max = 8) String currentTime,

        @Size(max = 11) String accountId,

        /* Raw character, so an undeclared code round-trips; see {@code resolvedAccountStatus()}. */
        @Size(max = 1) String accountStatus,

        @Size(max = 10) String openDate,

        /* 10. ACRDLIMO, COACTVW.CPY line 302. Exact decimal, scale 2; the screen edit is absent by
         * design and no scaling happens on this record. Record counterpart ACCT-CREDIT-LIMIT at
         * CVACT01Y.cpy line 8. */
        @Schema(description = "Account credit limit. Record field ACCT-CREDIT-LIMIT of "
                + "CVACT01Y.cpy line 8: a signed zoned decimal with ten integer digits and two "
                + "decimal places, so total precision 12 and scale exactly 2. The map's "
                + "numeric-edited screen picture is deliberately not reproduced.")
        BigDecimal creditLimit,

        /* The stored field name is misspelled in the copybook; this component is spelled correctly. */
        @Size(max = 10) String expirationDate,

        /* 12. ACSHLIMO, COACTVW.CPY line 314. Exact decimal, scale 2. Record counterpart
         * ACCT-CASH-CREDIT-LIMIT at CVACT01Y.cpy line 9. */
        @Schema(description = "Account cash credit limit. Record field ACCT-CASH-CREDIT-LIMIT of "
                + "CVACT01Y.cpy line 9: a signed zoned decimal with ten integer digits and two "
                + "decimal places, so total precision 12 and scale exactly 2.")
        BigDecimal cashCreditLimit,

        @Size(max = 10) String reissueDate,

        /* 14. ACURBALO, COACTVW.CPY line 326. Exact decimal, scale 2; legitimately negative. Record
         * counterpart ACCT-CURR-BAL at CVACT01Y.cpy line 7. */
        @Schema(description = "Account current balance. Record field ACCT-CURR-BAL of "
                + "CVACT01Y.cpy line 7: a signed zoned decimal with ten integer digits and two "
                + "decimal places, so total precision 12 and scale exactly 2. Legitimately "
                + "negative.")
        BigDecimal currentBalance,

        /* 15. ACRCYCRO, COACTVW.CPY line 332. Exact decimal, scale 2. Record counterpart
         * ACCT-CURR-CYC-CREDIT at CVACT01Y.cpy line 13. */
        @Schema(description = "Current cycle credit. Record field ACCT-CURR-CYC-CREDIT of "
                + "CVACT01Y.cpy line 13: a signed zoned decimal with ten integer digits and two "
                + "decimal places, so total precision 12 and scale exactly 2.")
        BigDecimal currentCycleCredit,

        /* Ten spaces in every seeded row, and those spaces are the value. */
        @Size(max = 10) String accountGroupId,

        /* 17. ACRCYDBO, COACTVW.CPY line 344. Exact decimal, scale 2. Record counterpart
         * ACCT-CURR-CYC-DEBIT at CVACT01Y.cpy line 14. */
        @Schema(description = "Current cycle debit. Record field ACCT-CURR-CYC-DEBIT of "
                + "CVACT01Y.cpy line 14: a signed zoned decimal with ten integer digits and two "
                + "decimal places, so total precision 12 and scale exactly 2.")
        BigDecimal currentCycleDebit,

        @Size(max = 9) String customerId,

        /* The displayed hyphenated form the program assembles; regulated data, carried verbatim. */
        @Size(max = 12) String ssn,

        @Size(max = 10) String dateOfBirth,

        /* No range constraint - see the class documentation. */
        @Size(max = 3) String ficoScore,

        @Size(max = 25) String firstName,

        @Size(max = 25) String middleName,

        @Size(max = 25) String lastName,

        @Size(max = 50) String addressLine1,

        /* Declared between the two address lines; map order is followed rather than regrouped. */
        @Size(max = 2) String stateCode,

        @Size(max = 50) String addressLine2,

        /* Five characters, fed from the wider stored postal code; the narrowing happens upstream. */
        @Size(max = 5) String zipCode,

        /* Fed from the third address line - the customer record has no city field. */
        @Size(max = 50) String city,

        @Size(max = 3) String countryCode,

        /* Already-formatted text, carried verbatim; never parsed or reassembled. */
        @Size(max = 13) String phoneNumber1,

        @Size(max = 20) String governmentIssuedId,

        @Size(max = 13) String phoneNumber2,

        @Size(max = 10) String eftAccountId,

        @Size(max = 1) String primaryCardHolderIndicator,

        @Size(max = 45) String infoMessage,

        /* Presence does not imply a rejection; the flag below is independent of this text. */
        @Size(max = 78) String errorMessage,

        /* Stated explicitly, never inferred from the presence of message text. */
        boolean inputError,

        /* 39. Legacy screen field identifier to focus, bounded at 7 - the widest map field name
         * there is. Seven is measured rather than assumed: across all 17 mapsets in the estate no
         * DFHMDF field name exceeds seven characters, because the map generator appends a
         * one-character suffix to build the eight-character symbolic names, and this mapset's own
         * longest names are exactly seven. An eighth character could therefore never name a field
         * on any screen, so admitting one admitted only values that cannot be honoured. On this
         * screen the program always nominates the account-number field (COACTVWC.cbl 549 and 551).
         * No coordinate, no cursor position. */
        @Size(max = 7) String focusScreenFieldId,

        /* Opaque route value; no route table, constant or dispatch lives here. */
        String nextRoute,

        NavigationContext navigationContext) {

    /**
     * The number of decimal places every monetary component carries, from the two decimal places of
     * the five signed zoned decimals at {@code app/cpy/CVACT01Y.cpy} lines 7, 8, 9, 13 and 14.
     *
     * <p>Public because it is part of the numeric contract rather than an implementation choice: the
     * service that builds a response, and the tests that check one, need a single authority for the
     * figure instead of each restating it.
     */
    public static final int MONEY_SCALE = 2;

    /**
     * The number of integer digits every monetary component may carry, from the ten integer digits of
     * the same five record fields. With {@link #MONEY_SCALE} this gives the total precision of twelve
     * that the relational columns declare.
     */
    public static final int MONEY_INTEGER_DIGITS = 10;

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of every carried value.
     *
     * <p>A constant rather than any transformation of the values, so nothing about them - not a
     * length, not a prefix, not a digest, not a partial obscuring - can be recovered from a
     * stringified instance.
     *
     * <p>Private because it is a rendering detail and not part of the response contract.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Confirms that each monetary component has the decimal shape of the record field it represents.
     *
     * <p>Nothing else is normalized or checked, and that is deliberate. Every other component is
     * stored exactly as supplied, including {@code null} and including every leading and trailing
     * space, because the map items they derive from are fixed-width and space-significant: the
     * account group id is ten spaces in all fifty seeded rows and those spaces are the value, so a
     * component that came back shortened would be a parity defect. No value is trimmed, padded,
     * re-cased, truncated, scaled, rounded or reformatted here, and no value is obscured - the
     * national identifier and the government-issued identifier are carried exactly as the legacy
     * screen displayed them.
     *
     * @throws IllegalArgumentException if any monetary component carries a scale other than
     *     {@link #MONEY_SCALE} or needs more than {@link #MONEY_INTEGER_DIGITS} integer digits
     */
    public AccountViewResponse {
        requireRecordShape("creditLimit", creditLimit);
        requireRecordShape("cashCreditLimit", cashCreditLimit);
        requireRecordShape("currentBalance", currentBalance);
        requireRecordShape("currentCycleCredit", currentCycleCredit);
        requireRecordShape("currentCycleDebit", currentCycleDebit);
    }

    /**
     * Confirms that an amount has the decimal shape of the record field it represents.
     *
     * <p>Reads only the amount's own scale and precision; it performs no arithmetic on the value,
     * does not re-scale it, does not round it and does not format it. The failure text names the
     * component and the offending scale or digit count and never the amount itself, so a rejected
     * value cannot reach a log through the diagnostic that reports it.
     *
     * @param component the component name, for the failure text
     * @param amount the amount to check, or {@code null} for a field the screen leaves blank
     * @throws IllegalArgumentException if the amount does not fit the record field
     */
    private static void requireRecordShape(final String component, final BigDecimal amount) {
        if (amount == null) {
            return;
        }
        if (amount.scale() != MONEY_SCALE) {
            throw new IllegalArgumentException(component + " must carry scale " + MONEY_SCALE
                    + ", because its record field stores two decimal places, but its scale is "
                    + amount.scale());
        }
        final int integerDigits = amount.precision() - amount.scale();
        if (integerDigits > MONEY_INTEGER_DIGITS) {
            throw new IllegalArgumentException(component + " must fit " + MONEY_INTEGER_DIGITS
                    + " integer digits, because that is the width of its record field, but it "
                    + "needs " + integerDigits);
        }
    }

    /**
     * Interprets the raw status character without throwing.
     *
     * @return the matching constant, or an empty {@link Optional}
     */
    public Optional<AccountStatus> resolvedAccountStatus() {
        return AccountStatus.fromCode(this.accountStatus);
    }

    /**
     * Reports whether the carried status character is the active code - the field's own documented meaning
     * on the mapset rather than an interpretation added here. An unresolvable status answers {@code false}
     * rather than raising.
     *
     * @return {@code true} only when the carried character is the active code
     */
    public boolean active() {
        return AccountStatus.isActiveCode(this.accountStatus);
    }

    /**
     * Renders this response for diagnostics with every regulated component withheld.
     *
     * <p>This is the widest response in the module and the one carrying the most regulated data:
     * twenty-five of its forty-one components are replaced by a fixed stand-in. They divide into five
     * groups. The account and customer identifiers and the external funds-transfer account identifier
     * are durable keys to stored records. The five monetary components and the credit score are
     * financial data about a named person. The government-issued identifier, the tax identifier, the
     * date of birth, the three name parts, the two address lines, the city, the postal code, the state
     * and country codes and the two telephone numbers are personal data outright. The two message
     * lines are withheld for the reason given below. Nothing in that list is needed to diagnose a
     * response.
     *
     * <p>The two coarse geography codes are withheld even though a two-character state and a
     * three-character country identify nobody on their own. That is deliberate fail-closed
     * conservatism: the review that required this rendering names addresses as regulated without
     * carving out their coarser components, the diagnostic value of a state code is close to nil, and
     * withholding it costs nothing that retaining it would buy.
     *
     * <p><strong>Why both message lines are withheld as well.</strong> This is where this response
     * differs from the account-update response, which does render its summary line. That one is
     * composed onto a field <em>label</em>, so it names which box is wrong without saying what was
     * typed. This program's texts are not: the not-found text it builds at
     * {@code app/cbl/COACTVWC.cbl} lines 747 to 757 concatenates the account identifier itself into
     * the message, and the two sibling texts at 796 to 806 and 846 to 856 are built the same way. A
     * message line on this response can therefore contain a business key, so rendering it would
     * reintroduce through the message exactly what withholding the identifiers removed.
     *
     * <p>Sixteen components are shown as-is, and the retained set was chosen because it is sufficient
     * to diagnose a response rather than because the remainder looked harmless. The header items, the
     * account status code, the three account calendar dates, the account group code, the
     * primary-cardholder flag, the error flag, the focus field and the route describe the shape and
     * outcome of a response without describing a person. The three dates are retained on the same
     * footing as the sibling transaction contracts retain theirs: a calendar date is not identifying,
     * and it is what makes an expiry or reissue defect diagnosable. The navigation state renders
     * itself, which is safe rather than merely conventional, because
     * {@link NavigationContext#toString()} withholds its own six identifying values.
     *
     * <p>Every component is named in the rendering, each either as its own value or as the stand-in,
     * rather than collapsed into a single summary entry as {@link AccountUpdateResponse#toString()}
     * does. Both styles are fail-closed, and the difference matters for a different reason: naming
     * each component keeps the rendering structurally parallel to the declaration, so completeness can
     * be checked component by component instead of taken on trust. On a record this wide that check is
     * the only practical way to know a regulated component has not been quietly printed, and the
     * sixteen retained values are genuinely useful on a read-only view screen in a way that an update
     * response's echoed input is not.
     *
     * <p>This override changes only the stringified form. The component accessors and the serialized
     * payload are unaffected and continue to carry the full untouched values, which is the contract
     * the legacy screen established, and which this type is under standing instruction not to alter:
     * nothing is obscured, shortened or transformed anywhere except on this one diagnostic path.
     * Protection at rest for the government identifier remains a separate concern handled in the
     * service and persistence layers.
     *
     * <p>{@code equals} and {@code hashCode} are deliberately left as the record contract generates
     * them. They compare every component by value, which is what a wire contract requires, and neither
     * emits anything: an in-memory comparison is not a disclosure surface. Withholding belongs on the
     * rendering path alone.
     *
     * @return a diagnostic rendering in which no regulated component appears in whole or in part
     */
    @Override
    public String toString() {
        return "AccountViewResponse["
                + "transactionName=" + transactionName
                + ", title01=" + title01
                + ", currentDate=" + currentDate
                + ", programName=" + programName
                + ", title02=" + title02
                + ", currentTime=" + currentTime
                + ", accountId=" + REDACTION_PLACEHOLDER
                + ", accountStatus=" + accountStatus
                + ", openDate=" + openDate
                + ", creditLimit=" + REDACTION_PLACEHOLDER
                + ", expirationDate=" + expirationDate
                + ", cashCreditLimit=" + REDACTION_PLACEHOLDER
                + ", reissueDate=" + reissueDate
                + ", currentBalance=" + REDACTION_PLACEHOLDER
                + ", currentCycleCredit=" + REDACTION_PLACEHOLDER
                + ", accountGroupId=" + accountGroupId
                + ", currentCycleDebit=" + REDACTION_PLACEHOLDER
                + ", customerId=" + REDACTION_PLACEHOLDER
                + ", ssn=" + REDACTION_PLACEHOLDER
                + ", dateOfBirth=" + REDACTION_PLACEHOLDER
                + ", ficoScore=" + REDACTION_PLACEHOLDER
                + ", firstName=" + REDACTION_PLACEHOLDER
                + ", middleName=" + REDACTION_PLACEHOLDER
                + ", lastName=" + REDACTION_PLACEHOLDER
                + ", addressLine1=" + REDACTION_PLACEHOLDER
                + ", stateCode=" + REDACTION_PLACEHOLDER
                + ", addressLine2=" + REDACTION_PLACEHOLDER
                + ", zipCode=" + REDACTION_PLACEHOLDER
                + ", city=" + REDACTION_PLACEHOLDER
                + ", countryCode=" + REDACTION_PLACEHOLDER
                + ", phoneNumber1=" + REDACTION_PLACEHOLDER
                + ", governmentIssuedId=" + REDACTION_PLACEHOLDER
                + ", phoneNumber2=" + REDACTION_PLACEHOLDER
                + ", eftAccountId=" + REDACTION_PLACEHOLDER
                + ", primaryCardHolderIndicator=" + primaryCardHolderIndicator
                + ", infoMessage=" + REDACTION_PLACEHOLDER
                + ", errorMessage=" + REDACTION_PLACEHOLDER
                + ", inputError=" + inputError
                + ", focusScreenFieldId=" + focusScreenFieldId
                + ", nextRoute=" + nextRoute
                + ", navigationContext=" + navigationContext
                + "]";
    }
}
