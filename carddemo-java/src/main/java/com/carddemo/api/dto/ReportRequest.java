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
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * Immutable inbound half of the transaction-report request screen, legacy transaction {@code CR00}
 * ({@code app/cbl/CORPT00C.cbl} with map {@code app/cpy-bms/CORPT00.CPY}).
 *
 * <p>The inbound group declares seventeen value items, ten of which the operator types: three
 * single-character report-type markers, six date parts and one confirmation position. Those ten, plus the
 * attention key and the echoed navigation state, are the twelve components of this contract and its
 * entire content.
 *
 * <h2>The three selection markers stay three markers</h2>
 *
 * <p>The mapset declares three separate unprotected one-character fields - {@code MONTHLYI},
 * {@code YEARLYI} and {@code CUSTOMI} - and nothing on the terminal prevents an operator marking two, so
 * a multiply-marked submission is a state the legacy system can genuinely produce. The program does not
 * reject it: the evaluation at {@code CORPT00C} line 213 tests the month-to-date marker, then the
 * year-to-date marker at line 239, then the operator-range marker at line 256, acting on the first that
 * is non-blank. A single enumerated component could not express "monthly and custom were both marked",
 * so it could not reproduce that resolution - it would force the client to pick, and a client picking
 * differently from the legacy order would produce a different report from the same keystrokes. It would
 * also discard the marker characters, which are carried verbatim because the program tests only that a
 * field is non-blank and never which character it holds. All three blank - absent or explicitly blank -
 * is the state the catch-all arm at line 437 reports, per decision {@code DL-024} (also carried as
 * {@code D-20}): an absent selector yields absence, never an invented value.
 *
 * <p><strong>The resolved period and the report name are produced, not submitted.</strong> The service
 * runs the ordered evaluation, resolves at most one {@link ReportPeriod} and publishes it on the
 * response, which is where a server-derived value belongs. {@link ReportPeriod}'s three members carry the
 * bare mixed-case forms {@code Monthly}, {@code Yearly} and {@code Custom}; the legacy holds them in a
 * ten-character work item, so the item content is the short mixed-case form followed by pad spaces,
 * never the upper-case member name and never a ten-character value. Two properties of that text are
 * load-bearing and may not be regularised - the capital initial with lowercase remainder, and the bare
 * rather than padded form - because both reads of the item consume it delimited by space, so the padding
 * never reaches an operator while the casing always does. Nothing here re-cases, pads or re-derives it,
 * and the name is absent from both this record and the response's components: it surfaces only inside
 * the two composed message texts the response assembles.
 *
 * <h2>The six date parts stay separate, and stay text</h2>
 *
 * <p>Start and end date are each three inbound items in screen order month, day, year. They stay
 * separate because each part is validated on its own and reports its own message naming that part, which
 * a merged value could not do without being split apart again. They stay text because leading zeros are
 * contractual - the program re-writes each part through a fixed-width numeric edit at lines 305 to 327
 * before comparing it, so a numeric component type would drop the leading zero and change the text
 * ultimately submitted. And the shape the operator types is not the shape the batch tier receives: the
 * screen collects three pieces whereas the program assembles one ten-character {@code YYYY-MM-DD} value
 * for the request it hands downstream, a conversion that belongs to the report-request service.
 * Consequently this record performs no assembly, separator insertion, parsing, calendar arithmetic or
 * range derivation, and names no type from the platform's date-and-time API.
 *
 * <h2>The confirmation position is one character of text, not a two-state marker</h2>
 *
 * <p>{@code CONFIRMI} has four distinct outcomes, two of which a two-state marker cannot hold: unmarked
 * is a prompt and a re-display at line 464, neither acceptance nor refusal; {@code Y} and {@code y}
 * proceed at line 478; {@code N} and {@code n} reset the screen and raise the error flag with no message
 * text at all at lines 480 to 483, a silent rejection observable precisely because it produces no
 * message; and any other value is quoted back to the operator inside its own message at lines 486 and
 * 488, so the exact character typed must survive as far as the response. It is carried exactly as
 * submitted - not re-cased, trimmed or defaulted - and the branch that reads it belongs to the service.
 *
 * <h2>Why no presence, format or calendar constraint appears here</h2>
 *
 * <p>When the custom period is selected the program runs a strictly ordered fourteen-stage validation
 * over the six date parts - six absence stages, then six numeric-and-bound stages, then two
 * calendar-validity stages - and the first failing stage decides both the message and the item the cursor
 * lands on. That order is the contract and it is reproduced stage by stage, with line citations, in the
 * report-request service. Three casing patterns run through those fourteen texts and every one is
 * contract rather than accident: the absence stages spell the negation word entirely in capitals, the
 * range stages capitalise the calendar-unit word, and the two closing stages spell the calendar word
 * entirely in lower case. The texts belong to the response contract, so none is declared here. The two
 * closing stages are decided by the date utility the program calls at lines 392 and 412 under a
 * two-level acceptance test - a severity check, then an exemption for one specific message number -
 * which belongs to the date-validation service; the monthly and yearly periods never enter the cascade
 * and derive their ranges from the current date, which is service work for the same reason.
 *
 * <p>Bean Validation is unordered and reports every violation at once, so annotating those fourteen
 * checks would produce several messages where the legacy screen produces exactly one, in an order it
 * never uses. This record therefore carries no presence constraint of any kind, no character-class or
 * pattern constraint, no numeric bound and no calendar constraint - the module-wide reading of decision
 * {@code DL-021}, that clause evaluation order is preserved verbatim.
 *
 * <p>The one constraint on a carried value bounds each text component to its screen width. It measures
 * and never alters, so a value arriving with leading or trailing spaces keeps them - which matters,
 * because the legacy absence test treats an all-spaces value as absent and the service must see that for
 * itself. The six date parts are legitimately blank whenever the period is monthly or yearly, and the
 * period itself is legitimately absent, so rejecting either at the transport boundary would refuse input
 * the legacy screen accepts.
 *
 * <p><strong>The navigation component carries a cascade, which is a different kind of thing.</strong> It
 * adds no rule the nested type does not already state; it makes the widths that component declares
 * actually evaluated, because a nested constraint fires only when the enclosing component asks for it.
 * It cannot pre-empt the fourteen-stage cascade, because an over-wide value inside an echoed context is a
 * state no 3270 submission could produce, so the estate has no ordered check and no message for it. What
 * it prevents is an unbounded echoed value crossing this boundary on its way to a query.
 *
 * <h2>What this record deliberately does not contain</h2>
 *
 * <p>Once validation passes, the legacy program hands the request to the batch tier and tolerates a
 * hand-over failure rather than aborting the transaction - the paragraph at line 462 and the hand-over
 * at lines 515 to 535, recorded as decision {@code D-36}. The whole of that mechanism lives in the
 * module's utility and service layers, because this record is the operator's request and not the payload
 * built from it. Nor does it carry any of the response side: no message text, screen furniture, cursor
 * position, attribute or colour byte, or edited display mask. It executes no navigation and folds no
 * keys - the upper twelve program-function keys fold onto the lower twelve in the module's key
 * translator, per decision {@code DL-025}.
 *
 * <p>No member is declared beyond the twelve components, and the omission is deliberate rather than
 * unfinished work: a convenience member would put a fragment of service behaviour on the transport
 * boundary, where the tests that own that behaviour would never exercise it. This is a pure transport
 * value type - immutable, no framework binding beyond the length constraints, no persistence mapping and
 * no behaviour - so instances are safe to share across threads, and every component may be absent so
 * that the ordered service validation decides what an absent value means.
 *
 * @param monthlySelection   the month-to-date marker {@code MONTHLYI}, bounded to its one-character
 *                           screen width and carried exactly as transmitted. The <strong>first</strong>
 *                           position the ordered evaluation at line 213 tests, and therefore the one that
 *                           wins when more than one is marked. Any non-blank character is a mark. May be
 *                           absent, which is indistinguishable from blank and is not a refusal.
 * @param yearlySelection    the year-to-date marker {@code YEARLYI}, on the same terms. The
 *                           <strong>second</strong> position tested, at line 239.
 * @param customSelection    the operator-supplied range marker {@code CUSTOMI}, on the same terms. The
 *                           <strong>third</strong> position tested, at line 256, and the only one that
 *                           brings the six date parts into the validation cascade.
 * @param startMonth         the start date's month part {@code SDTMMI}, bounded to two characters and
 *                           carried verbatim including any leading or trailing space. Legitimately blank
 *                           unless the custom period is selected.
 * @param startDay           the start date's day part {@code SDTDDI}, two characters, same terms.
 * @param startYear          the start date's year part {@code SDTYYYYI}, four characters, same terms.
 * @param endMonth           the end date's month part {@code EDTMMI}, two characters, same terms.
 * @param endDay             the end date's day part {@code EDTDDI}, two characters, same terms.
 * @param endYear            the end date's year part {@code EDTYYYYI}, four characters, same terms.
 * @param confirm            the confirmation character {@code CONFIRMI}, one character, carried exactly
 *                           as submitted so the accept, silent-reset and quoted-back outcomes at lines
 *                           478, 480 and 486 remain distinguishable. Absent means not yet answered,
 *                           which is the prompt at line 464 rather than a refusal.
 * @param keyAction          the attention key that arrived, from the module's five-character vocabulary.
 *                           The screen maps two, per the key evaluation at {@code CORPT00C} line 184:
 *                           enter drives the request and the third program-function key returns to the
 *                           previous screen; anything else is an unmapped key the service reports. May be
 *                           absent, and no default is applied or inferred.
 * @param navigationContext  the echoed navigation state carried across the pseudo-conversational turn in
 *                           place of the legacy communication area. Client-echoed request state rather
 *                           than a server session, and may be absent on a first entry.
 */
public record ReportRequest(
        @Size(max = 1) String monthlySelection,
        @Size(max = 1) String yearlySelection,
        @Size(max = 1) String customSelection,
        @Size(max = 2) String startMonth,
        @Size(max = 2) String startDay,
        @Size(max = 4) String startYear,
        @Size(max = 2) String endMonth,
        @Size(max = 2) String endDay,
        @Size(max = 4) String endYear,
        @Size(max = 1) String confirm,
        KeyAction keyAction,
        @Valid NavigationContext navigationContext) {
}
