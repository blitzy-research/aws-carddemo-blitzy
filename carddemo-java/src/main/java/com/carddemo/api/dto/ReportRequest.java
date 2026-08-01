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
import com.carddemo.domain.enums.ReportPeriod;
import jakarta.validation.constraints.Size;

/**
 * Immutable report-request contract for legacy CICS transaction {@code CR00}, the transaction-report
 * request screen.
 *
 * <p>Program {@code CORPT00C} ({@code app/cbl/CORPT00C.cbl}, 649 lines, ten paragraphs) drives
 * mapset {@code CORPT00} ({@code app/bms/CORPT00.bms}) through its generated symbolic map
 * ({@code app/cpy-bms/CORPT00.CPY}), whose inbound group {@code CORPT0AI} at line 17 and outbound
 * redefinition {@code CORPT0AO} at line 121 have identical widths item for item. This record is the
 * REST-era replacement for the inbound half of that screen, and for the inbound half only.
 *
 * <h2>Ten of the map's seventeen inbound items are operator input</h2>
 *
 * <p>The inbound group declares seventeen value items. Ten of them are typed by the operator: three
 * single-character report-type markers, six date parts and one confirmation position. Those ten
 * become the eight components below - the three markers collapse into one - and are the entire
 * content of this contract.
 *
 * <p>Of the remaining seven, six are screen furniture the program writes outbound and never reads as
 * input: the transaction name, the two title lines, the current date, the program name and the
 * current time. The seventh is the error line the program fills from its own message work field
 * (declared at {@code CORPT00C} line 39). All seven belong to the response contract and are
 * deliberately absent here, because a request that carried them would let a client dictate text the
 * server alone produces.
 *
 * <p>The map's per-field length, flag, colour, highlight, protection and validation control items,
 * and the leading twelve-byte terminal input/output area filler at line 18 of the symbolic map, are
 * generated 3270 plumbing rather than contract, so none of them is modelled either. The six date
 * items additionally carry a numeric-entry attribute in the mapset, which is a terminal keyboard
 * shift and not a data type: the underlying items are character items, and they are carried here as
 * text for the reasons set out below.
 *
 * <h2>The three selection markers collapse into one period component</h2>
 *
 * <p>On the 3270 the operator marks exactly one of three single-character positions:
 * {@code MONTHLYI} (symbolic map line 60; mapset line 80, one character at row 7 column 10),
 * {@code YEARLYI} (symbolic map line 66; mapset line 94, row 9) and {@code CUSTOMI} (symbolic map
 * line 72; mapset line 108, row 11). The program tests them in that order and stops at the first
 * marked position, so the three are mutually exclusive by construction rather than by validation.
 *
 * <p>The REST contract therefore carries a single enum-valued component rather than three character
 * positions. Three independent characters would admit combinations the screen cannot express and
 * would push the tie-break into every consumer; one {@link ReportPeriod} makes the exclusivity
 * unrepresentable in the wrong state.
 *
 * <p>There is no constant for the unmarked case. When the operator marks nothing the program falls
 * to its catch-all clause at line 438 and reports that as an input error, so absence is modelled by
 * this component simply being absent, and the message that names it belongs to the response
 * contract. That is the module-wide rule recorded as decision {@code DL-024} (also carried as
 * {@code D-20}): an absent or unrecognised selector yields absence, never a synthetic constant.
 *
 * <h2>The period's carried text is not its constant identifier</h2>
 *
 * <p>Each {@link ReportPeriod} constant carries the exact short text the program moves into its
 * ten-character report-name work field, declared at {@code CORPT00C} line 58 and written at lines
 * 214, 240 and 433. Two properties of that text are load-bearing, and neither can be derived from
 * the constant identifier: the text has a capital initial letter followed by a lowercase remainder,
 * and it is bare rather than padded out to the width of the work field.
 *
 * <p>The program reads that field twice only, at lines 449 and 468, and both reads consume it
 * delimited by space - so the padding never reaches an operator, while the casing always does.
 * Consumers of this record must therefore take the text from the enum's accessor and must never
 * derive it from the constant identifier, re-case it in either direction, or pad it. Each of those
 * would emit text the report contract never produced.
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
 * <p>The one constraint that is present bounds each text component to its screen width. It measures
 * and never alters, so a value that arrives with leading or trailing spaces keeps them - which
 * matters, because the legacy absence test treats an all-spaces value as absent and the service must
 * be able to see that for itself. The six date parts are legitimately blank whenever the period is
 * monthly or yearly, and the period itself is legitimately absent, so rejecting either at the
 * transport boundary would refuse input the legacy screen accepts.
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
 * @param reportPeriod       the report type the operator marked, collapsing the three mutually
 *                           exclusive single-character markers {@code MONTHLYI}, {@code YEARLYI} and
 *                           {@code CUSTOMI} into one selector. May be absent, which is the state the
 *                           catch-all clause at line 438 reports; no constant exists for it. The
 *                           text each constant carries is short, mixed case and unpadded, and is not
 *                           the constant identifier.
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

        /* 1. MONTHLYI / YEARLYI / CUSTOMI, width 1 each - the three mutually exclusive markers
              collapsed into one selector; absent when the operator marked none (line 438). */
        ReportPeriod reportPeriod,

        /* 2. SDTMMI, width 2 - start month, first of the three screen parts (mapset line 127). */
        @Size(max = 2) String startMonth,

        /* 3. SDTDDI, width 2 - start day, second screen part (mapset line 138). */
        @Size(max = 2) String startDay,

        /* 4. SDTYYYYI, width 4 - start year, third screen part (mapset line 149). */
        @Size(max = 4) String startYear,

        /* 5. EDTMMI, width 2 - end month, first of the three screen parts (mapset line 166). */
        @Size(max = 2) String endMonth,

        /* 6. EDTDDI, width 2 - end day, second screen part (mapset line 177). */
        @Size(max = 2) String endDay,

        /* 7. EDTYYYYI, width 4 - end year, third screen part (mapset line 188). */
        @Size(max = 4) String endYear,

        /* 8. CONFIRMI, width 1 - carried verbatim: accept, silent reset and quoted-back are three
              distinct outcomes (lines 478, 480 and 486), so the character itself must survive. */
        @Size(max = 1) String confirm,

        /* 9. The attention key that arrived. No default, and no upper-key fold: the fold belongs to
              the module's key translator (decision DL-025). */
        KeyAction keyAction,

        /* 10. Echoed navigation state standing in for the legacy communication area; client-echoed
               request state, never a server session. */
        NavigationContext navigationContext) {

    // No member is declared beyond the ten components above, and the omission is deliberate rather
    // than unfinished work. Every remaining concern of transaction CR00 - the ordered fourteen-stage
    // validation, the monthly and yearly range derivation, the three-part to single-value date
    // conversion, the confirmation branch, the downstream hand-over and every response text - sits
    // in a layer this transport contract must not reach into. A convenience member here would put a
    // fragment of that behaviour on the transport boundary, where the service-tier tests that own
    // the behaviour would never exercise it, and where a second implementation of it could drift
    // away from the one the parity fixtures measure.
}
