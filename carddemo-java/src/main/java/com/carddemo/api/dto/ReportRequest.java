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
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * Immutable report-request contract for legacy transaction {@code CR00} &mdash; the inbound half of
 * the transaction-report request screen, and the inbound half only. The authority is
 * {@code app/cbl/CORPT00C.cbl} with map {@code app/cpy-bms/CORPT00.CPY}.
 *
 * <p>Ten of the map's inbound items are operator input - three report-type markers, six date parts and
 * one confirmation position - and they become the eight components below, the three markers collapsing
 * into one. The screen furniture the program writes (transaction name, both titles, date, time,
 * program name) and the error line it fills from its own message field belong to the response
 * contract and are deliberately absent, because a request carrying them would let a client dictate
 * text the server alone produces. Per-field length, flag, colour, highlight, protection and validation
 * control items and the terminal input/output filler are generated plumbing and are not modelled. The
 * numeric-entry attribute the mapset puts on the date items is a keyboard shift, not a data type: the
 * underlying items are character items and are carried here as text.
 *
 * <p><strong>The three selection markers collapse into one period component.</strong> The operator
 * marks exactly one of three single-character positions and the program tests them in a fixed order,
 * stopping at the first marked one, so they are mutually exclusive by construction rather than by
 * validation. Three independent characters would admit combinations the screen cannot express and
 * would push the tie-break into every consumer; one {@link ReportPeriod} makes the wrong state
 * unrepresentable. There is no constant for the unmarked case: the program falls to a catch-all clause
 * and reports an input error, so absence is modelled by the component being absent and the message
 * that names it belongs to the response (decision log DL-024, also carried as D-20).
 *
 * <p>The inbound group declares seventeen value items. Ten of them are typed by the operator: three
 * single-character report-type markers, six date parts and one confirmation position. Those ten
 * become ten of the twelve components below - one component per typed position, with no collapsing -
 * and together with the attention key and the echoed navigation state they are the entire content of
 * this contract.
 *
 * <p><strong>The six date parts stay separate, and stay text.</strong> Each part is validated on its
 * own and reports its own message naming that part, so a merged value could not say which part failed;
 * leading zeros are contractual, and a numeric component type would drop them and change the text that
 * is ultimately submitted; and the shape the operator types is not the shape the batch tier receives,
 * which is one assembled ten-character value. That assembly is the report-request service's work, so
 * this record performs no assembly, separator insertion, parsing, calendar arithmetic or range
 * derivation and names no date-and-time type.
 *
 * <p><strong>The confirmation position is one character of text, not a two-state marker</strong>,
 * because it has four outcomes rather than two: unmarked prompts and re-displays the screen, so it is
 * neither acceptance nor refusal; the yes characters proceed; the no characters reset the screen and
 * raise the error flag with <em>no message text at all</em>, which is observable precisely because it
 * is silent; and any other value is quoted back to the operator inside its own message, so the exact
 * character typed must survive as far as the response. It is carried exactly as submitted - not
 * re-cased, not trimmed, not defaulted - and the branch that reads it belongs to the service.
 *
 * <h2>The three selection markers stay three</h2>
 *
 * <p>On the 3270 the operator marks one of three single-character positions:
 * {@code MONTHLYI} (symbolic map line 60; mapset line 80, one character at row 7 column 10),
 * {@code YEARLYI} (symbolic map line 66; mapset line 94, row 9) and {@code CUSTOMI} (symbolic map
 * line 72; mapset line 108, row 11). They are three separate one-character items on the wire, and
 * this record carries them as three separate one-character components.
 *
 * <p><strong>Why they are not collapsed into a single selector.</strong> The exclusivity the screen
 * exhibits is produced by the <em>program</em>, not asserted by the map. The evaluation at
 * {@code CORPT00C} line 213 tests the three positions in the fixed order monthly, yearly, custom and
 * stops at the first one it finds marked, falling to its catch-all clause at line 438 when none is.
 * That is first-match-wins over three independent inputs, and it has observable consequences a single
 * selector erases:
 *
 * <ul>
 *   <li>An operator can mark two positions. The 3270 does not prevent it, and the program does not
 *       reject it - it silently honours the earlier one. A single-valued component cannot represent
 *       the submission at all, so a client that made it would receive a transport rejection where the
 *       legacy screen produced a report.</li>
 *   <li>The tie-break order is itself contract. Preserving three components keeps the order where the
 *       legacy put it, in the service that reproduces line 213's evaluation, rather than dissolving it
 *       into a deserializer that would have to pick a winner with no citation for its choice.</li>
 *   <li>The unmarked case needs no synthetic constant. All three components are simply absent or
 *       blank, which is exactly the state line 438 reports, and the message that names it belongs to
 *       the response contract. This is the module-wide rule recorded as decision {@code DL-024} (also
 *       carried as {@code D-20}): an absent or unrecognised selector yields absence, never an
 *       invented value.</li>
 * </ul>
 *
 * <p>Each marker is therefore one character of text, bounded at its screen width and carried exactly
 * as submitted. Nothing here decides which of the three won, because deciding that is the ordered
 * service evaluation's work.
 *
 * <h2>The report name is produced, not submitted</h2>
 *
 * <p>The short text that identifies the chosen report - the values the program moves into its
 * ten-character report-name work field, declared at {@code CORPT00C} line 58 and written at lines
 * 214, 240 and 433 - is an <em>output</em> of the selection, not an input to it. The operator marks a
 * position; the program derives the name. It therefore appears on the response contract beside the
 * echoed markers and is deliberately absent from this record, because a request that carried it would
 * let a client name a report the selection did not choose.
 *
 * <p>Two properties of that text are load-bearing where it does appear, and neither may be
 * regularised: it has a capital initial letter followed by a lowercase remainder, and it is bare
 * rather than padded out to the width of the work field. The program reads the field twice only, at
 * lines 449 and 468, and both reads consume it delimited by space - so the padding never reaches an
 * operator, while the casing always does.
 *
 * <h2>The six date parts stay separate, and stay text</h2>
 *
 * <p>The start date and the end date are each three separate inbound items, in the screen order
 * month, day, year: {@code SDTMMI} (symbolic map line 78; mapset line 127, two characters at row 13
 * column 29), {@code SDTDDI} (line 84; mapset line 138, column 34) and {@code SDTYYYYI} (line 90;
 * mapset line 149, four characters at column 39), with {@code EDTMMI} (line 96; mapset line 166),
 * {@code EDTDDI} (line 102; mapset line 177) and {@code EDTYYYYI} (line 108; mapset line 188)
 * occupying the row below at the same columns.
 *
 * <p>They stay separate, and they stay text, for three reasons.
 *
 * <ul>
 *   <li>Each part is validated on its own and reports its own message naming that part. A merged
 *       value could not say which part failed without being split apart again.</li>
 *   <li>Leading zeros are contractual. A month is the two-character zero-filled form, and the
 *       program re-writes each part through a fixed-width numeric edit at lines 305 to 327 before
 *       comparing it, so a numeric component type would drop the leading zero and change the text
 *       that is ultimately submitted.</li>
 *   <li>The shape the operator types is not the shape the batch tier receives. The screen collects
 *       {@code MM}, {@code DD} and {@code YYYY} as three pieces, whereas the program assembles one
 *       ten-character {@code YYYY-MM-DD} value for the request it hands downstream: the two work
 *       groups at lines 60 to 71 and the mask at line 72. That conversion is the report-request
 *       service's, never this record's.</li>
 * </ul>
 *
 * <p>Consequently this record performs no assembly, no separator insertion, no parsing, no calendar
 * arithmetic and no range derivation, and it names no type from the platform's date-and-time API.
 *
 * <h2>The confirmation position is one character of text, not a two-state marker</h2>
 *
 * <p>The confirmation position {@code CONFIRMI} (symbolic map line 114; mapset line 206, one
 * character at row 19 column 66) is carried as one character of text rather than as a two-state
 * marker, because the position has four distinct outcomes rather than two, and two of those four
 * depend on information a two-state marker cannot hold.
 *
 * <ul>
 *   <li>An unmarked position is not a refusal. The program prompts for confirmation and re-displays
 *       the screen at line 464, so the request is neither accepted nor rejected.</li>
 *   <li>{@code Y} and {@code y} proceed, at line 478.</li>
 *   <li>{@code N} and {@code n} reset the screen and raise the error flag with no message text at
 *       all, at lines 480 to 483. That silent rejection is observable precisely because it produces
 *       no message, and a two-state marker would erase the distinction between it and the prompted
 *       state.</li>
 *   <li>Any other value is quoted back to the operator inside its own message, whose fragments sit
 *       at lines 486 and 488. The exact character the operator typed must therefore survive as far
 *       as the response, which a two-state marker would have discarded before the message could be
 *       built.</li>
 * </ul>
 *
 * <p>The character is carried exactly as submitted - not re-cased, not trimmed, not defaulted - and
 * the branch that reads it belongs to the report-request service.
 *
 * <h2>The ordered fourteen-stage date cascade belongs to the service</h2>
 *
 * <p>When the custom period is selected the program runs a strictly ordered fourteen-stage
 * validation over the six date parts, and the first failing stage decides both the message and the
 * item the cursor lands on. The order, with each stage's citation in {@code app/cbl/CORPT00C.cbl}:
 *
 * <ol>
 *   <li>start month absent - line 261</li>
 *   <li>start day absent - line 268</li>
 *   <li>start year absent - line 275</li>
 *   <li>end month absent - line 282</li>
 *   <li>end day absent - line 289</li>
 *   <li>end year absent - line 296</li>
 *   <li>start month not numeric, or above its upper bound - line 331</li>
 *   <li>start day not numeric, or above its upper bound - line 340</li>
 *   <li>start year not numeric - line 348</li>
 *   <li>end month not numeric, or above its upper bound - line 357</li>
 *   <li>end day not numeric, or above its upper bound - line 366</li>
 *   <li>end year not numeric - line 374</li>
 *   <li>start date not a valid calendar date - line 400</li>
 *   <li>end date not a valid calendar date - line 420</li>
 * </ol>
 *
 * <p>Three casing patterns run through those fourteen texts, and every one of them is contract
 * rather than accident: the six absence stages spell the negation word entirely in capitals, the six
 * range stages capitalise the calendar-unit word, and the two closing stages spell the calendar word
 * entirely in lower case. None of the three may be regularised. The texts themselves belong to the
 * response contract, so not one of them is declared in this file; only their order and their
 * citations are recorded here.
 *
 * <p>The two closing stages are decided by the date utility the program calls at lines 392 and 412,
 * under a two-level acceptance test: a severity check first, at lines 396 and 416, then an exemption
 * for one specific message number, at lines 399 and 419. That logic belongs to the module's
 * date-validation service; it is neither replicated nor referenced here.
 *
 * <p>The monthly and yearly periods never enter the cascade. They derive their own ranges from the
 * current date at lines 215 to 236 and 241 to 253 respectively, which is service work for the same
 * reason.
 *
 * <h2>Why no presence, format or calendar constraint appears here</h2>
 *
 * <p>Bean Validation is unordered and reports every violation at once, whereas the cascade above is
 * ordered and first-failure-wins. Annotating those fourteen checks would produce several messages
 * where the legacy screen produces exactly one, and in an order the legacy screen never uses. This
 * record therefore carries no presence constraint of any kind - no {@code NotNull},
 * {@code NotBlank}, {@code NotEmpty} - no character-class or pattern constraint, no numeric bound
 * and no calendar constraint. Each of those checks is a message-bearing, source-ordered service
 * validation instead, which is the module-wide reading of decision {@code DL-021}: clause evaluation
 * order is preserved verbatim.
 *
 * <p>The one constraint on a carried value bounds each text component to its screen width. It measures
 * and never alters, so a value that arrives with leading or trailing spaces keeps them - which
 * matters, because the legacy absence test treats an all-spaces value as absent and the service must
 * be able to see that for itself. The six date parts are legitimately blank whenever the period is
 * monthly or yearly, and the period itself is legitimately absent, so rejecting either at the
 * transport boundary would refuse input the legacy screen accepts.
 *
 * <p><strong>The navigation component carries a cascade, which is a different kind of thing.</strong>
 * It constrains no component this record declares and adds no rule the nested type does not already
 * state; it makes the widths that component declares for itself actually evaluated, because a nested
 * constraint fires only when the enclosing component asks for it, and a width that nothing evaluates
 * is a width that is documented rather than enforced. It cannot pre-empt the fourteen-stage cascade
 * either, because an over-wide value inside an echoed context is a state no 3270 submission could
 * produce, so the estate has no ordered check and no message for it. What it prevents is an unbounded
 * echoed value crossing this boundary on its way to a query, not a bad date.
 *
 * <h2>What this record deliberately does not contain</h2>
 *
 * <p>Once validation passes, the legacy program hands the request to the batch tier and tolerates a
 * hand-over failure rather than aborting the transaction - the paragraph at line 462 and the
 * hand-over at lines 515 to 535, recorded as decision {@code D-36}. The whole of that mechanism -
 * the fixed image the program fills, the two date substitution slots it fills them into, the fixed
 * record width, the terminating sentinel, the resource written to and the tolerate-and-continue
 * failure path - lives in the module's utility and service layers. None of it appears here, because
 * this record is the operator's request and not the payload built from it.
 *
 * <p>Nor does it carry any of the response side: no message text, no screen furniture, no cursor
 * position, no attribute or colour byte and no edited display mask. It executes no navigation and
 * folds no keys: the upper twelve program-function keys fold onto the lower twelve in the module's
 * key translator, per decision {@code DL-025}, and this record only carries which action arrived.
 *
 * <p>This is a pure transport value type: immutable, with no framework binding beyond the single
 * length constraint, no persistence mapping and no behaviour. Instances are therefore safe to share
 * across threads, and every component may be absent so that the ordered service validation - not the
 * transport boundary - decides what an absent value means.
 *
 * <h2>Provenance</h2>
 *
 * <p>Translated from the CardDemo COBOL estate at checkout commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The estate under {@code app/} is read-only
 * reference: it is cited here by member name, item name, item width, screen position and line number
 * only, and no COBOL text is reproduced. Divergences from idiomatic Java are recorded in
 * {@code docs/decision-log.md}.
 *
 * @param monthlySelection   the monthly marker, from {@code MONTHLYI} at symbolic map line 60,
 *                           bounded to the one-character screen width and carried exactly as
 *                           submitted. Tested first by the evaluation at line 213. May be absent or
 *                           blank, which is the unmarked state; no value is inferred and none is
 *                           defaulted.
 * @param yearlySelection    the yearly marker, from {@code YEARLYI} at symbolic map line 66, bounded
 *                           to one character and carried verbatim on the same terms. Tested second.
 * @param customSelection    the custom-range marker, from {@code CUSTOMI} at symbolic map line 72,
 *                           bounded to one character and carried verbatim on the same terms. Tested
 *                           third, and the only one of the three that brings the six date parts into
 *                           play. When all three are absent or blank the catch-all clause at line 438
 *                           reports the input error, and that message belongs to the response
 *                           contract.
 * @param startMonth         the start date's month part, from {@code SDTMMI}, bounded to the
 *                           two-character screen width and carried verbatim including any leading or
 *                           trailing space. Legitimately blank unless the custom period is selected.
 * @param startDay           the start date's day part, from {@code SDTDDI}, bounded to two
 *                           characters and carried verbatim on the same terms.
 * @param startYear          the start date's year part, from {@code SDTYYYYI}, bounded to the
 *                           four-character screen width and carried verbatim on the same terms.
 * @param endMonth           the end date's month part, from {@code EDTMMI}, bounded to two
 *                           characters and carried verbatim on the same terms.
 * @param endDay             the end date's day part, from {@code EDTDDI}, bounded to two characters
 *                           and carried verbatim on the same terms.
 * @param endYear            the end date's year part, from {@code EDTYYYYI}, bounded to four
 *                           characters and carried verbatim on the same terms.
 * @param confirm            the confirmation character, from {@code CONFIRMI}, bounded to the
 *                           one-character screen width and carried exactly as submitted so that the
 *                           accept, silent-reset and quoted-back outcomes at lines 478, 480 and 486
 *                           remain distinguishable. Absent means the confirmation has not been
 *                           answered yet, which is the prompt at line 464 rather than a refusal.
 * @param keyAction          the attention key that arrived, from the module's five-character key
 *                           vocabulary. The screen maps two of them, per {@code app/bms/CORPT00.bms}
 *                           line 226 and the key evaluation at {@code CORPT00C} line 184: the enter
 *                           key drives the request, the third program-function key returns to the
 *                           previous screen, and anything else is an unmapped key the service reports.
 *                           May be absent; no default is applied and none may be inferred.
 * @param navigationContext  the echoed navigation state carried across the pseudo-conversational
 *                           turn in place of the legacy communication area. It is client-echoed
 *                           request state rather than a server session, and may be absent on a first
 *                           entry.
 */
public record ReportRequest(

        /* 1. MONTHLYI, width 1 - symbolic map line 60, mapset line 80. Tested FIRST by the ordered
              evaluation at CORPT00C line 213. Kept as its own character rather than folded into a
              single selector: the three positions are independent on the wire, an operator can mark
              more than one, and the first-match-wins order is the service's to reproduce. */
        @Size(max = 1) String monthlySelection,

        /* 2. YEARLYI, width 1 - symbolic map line 66, mapset line 94. Tested SECOND. */
        @Size(max = 1) String yearlySelection,

        /* 3. CUSTOMI, width 1 - symbolic map line 72, mapset line 108. Tested THIRD, and the only
              marker that brings the six date parts below into the validation cascade. All three
              absent or blank is the state the catch-all clause at line 438 reports. */
        @Size(max = 1) String customSelection,

        /* 4. SDTMMI, width 2 - start month, first of the three screen parts (mapset line 127). */
        @Size(max = 2) String startMonth,

        /* 5. SDTDDI, width 2 - start day, second screen part (mapset line 138). */
        @Size(max = 2) String startDay,

        /* 6. SDTYYYYI, width 4 - start year, third screen part (mapset line 149). */
        @Size(max = 4) String startYear,

        /* 7. EDTMMI, width 2 - end month, first of the three screen parts (mapset line 166). */
        @Size(max = 2) String endMonth,

        /* 8. EDTDDI, width 2 - end day, second screen part (mapset line 177). */
        @Size(max = 2) String endDay,

        /* 9. EDTYYYYI, width 4 - end year, third screen part (mapset line 188). */
        @Size(max = 4) String endYear,

        /* 10. CONFIRMI, width 1 - carried verbatim: accept, silent reset and quoted-back are three
              distinct outcomes (lines 478, 480 and 486), so the character itself must survive. */
        @Size(max = 1) String confirm,

        /* 11. The attention key that arrived. No default, and no upper-key fold: the fold belongs to
              the module's key translator (decision DL-025). */
        KeyAction keyAction,

        /* 12. Echoed navigation state standing in for the legacy communication area; client-echoed
               request state, never a server session. Marked @Valid so the bounds the nested type
               declares are actually applied: Bean Validation does not descend into a nested object
               unless it is told to, so without this every bound inside it is decorative and an
               over-long identifier reaches the service unreported. Cascading a bound is not the
               same as adding one - no new constraint is introduced here, and the ordered
               service-tier cascade this file describes is untouched. */
        @Valid NavigationContext navigationContext) {

    // No member is declared beyond the twelve components above, and the omission is deliberate rather
    // than unfinished work. Every remaining concern of transaction CR00 - the ordered fourteen-stage
    // validation, the monthly and yearly range derivation, the three-part to single-value date
    // conversion, the confirmation branch, the downstream hand-over and every response text - sits
    // in a layer this transport contract must not reach into. A convenience member here would put a
    // fragment of that behaviour on the transport boundary, where the service-tier tests that own
    // the behaviour would never exercise it, and where a second implementation of it could drift
    // away from the one the parity fixtures measure.
}
