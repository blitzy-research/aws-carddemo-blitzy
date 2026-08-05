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

import jakarta.validation.constraints.Size;

/**
 * Immutable sign-on response contract for legacy transaction {@code CC00} &mdash; everything the
 * sign-on screen sent outbound. The authority is {@code app/cbl/COSGN00C.cbl} with map
 * {@code app/cpy-bms/COSGN00.CPY}; the paired inbound contract is {@link SignOnRequest} and the state
 * handed to the successor screen is {@link NavigationContext}.
 *
 * <p>Nine of the map's eleven value items are response-side and are carried here: the transaction
 * name, both title lines, the date, the time, the program name, the application and system
 * identifiers and the message. The two the operator types belong to the request. Each crosses the API
 * as a bounded {@link String} at its measured screen width and never as a numeric or temporal type,
 * so a rendered leading zero survives. Per-field length, flag and attribute items, the terminal
 * input/output filler, the terminal identity, cursor coordinates and the map and mapset names are
 * generated 3270 plumbing and are not modelled.
 *
 * <h2>Nine written items, plus the one safe echo, and never the credential</h2>
 *
 * <p>The symbolic map declares eleven value items. Nine are screen metadata the program
 * <em>writes</em>: {@code TRNNAME}, {@code TITLE01}, {@code TITLE02}, {@code CURDATE},
 * {@code CURTIME}, {@code PGMNAME}, {@code APPLID}, {@code SYSID} and {@code ERRMSG}. Eight of the
 * nine are written by the header paragraph on lines 181 to 204 of {@code COSGN00C} and the ninth, the
 * message, is written on line 149 immediately before the screen is transmitted. The remaining two -
 * the user id and the operator's credential - are typed by the operator and belong inbound to
 * {@link SignOnRequest}.
 *
 * <p>The user id is nevertheless carried outbound as well, because the map declares an eight-character
 * output item for it, {@code USERIDO} on line 140 of {@code app/cpy-bms/COSGN00.CPY}, and because seven
 * of the transaction's nine outcomes display or redisplay the sign-on screen rather than leaving it. A
 * client that cannot restate the user id cannot reproduce those screens, and the operator would have to
 * retype an identifier that was never in question - the credential is what failed. {@link #userId()}
 * is therefore the one operator-typed value this response echoes, and it is what the finding this
 * component closes calls the map's safe output.
 *
 * <p><strong>Recorded observation: the program never assigns that output item, and what the legacy
 * screen actually redisplayed is a terminal artefact.</strong> {@code POPULATE-HEADER-INFO} on lines
 * 181 to 204 writes eight items and {@code SEND-SIGNON-SCREEN} writes the ninth; no statement anywhere
 * in {@code COSGN00C} moves anything into {@code USERIDO}. The output map redefines the input map, and
 * within each fifteen-byte field group the output value sits three bytes ahead of the input value - the
 * input group is a two-byte length, a flag byte, four filler bytes and then the eight-character value,
 * while the output group is four attribute bytes, the eight-character value and three filler bytes. So
 * on a redisplay the item transmitted overlaps the received user id without coinciding with it, and
 * what the operator saw was neither the typed identifier nor a blank field. That is generated 3270
 * plumbing of exactly the kind this contract does not model, alongside the length, flag and attribute
 * items and the terminal input/output area filler. This contract carries the identifier itself, which
 * is the value the screen was trying and failing to restate, and the artefact is recorded here rather
 * than reproduced.
 *
 * <p><strong>The credential is not echoed, and the map declaring an output item for it changes
 * nothing.</strong> {@code PASSWDO} is declared on line 146 of the same copybook, immediately after
 * {@code USERIDO} and at the same width, and it is deliberately absent from this contract. Being
 * declared by a generated symbolic map is not a reason to publish a value; the credential rule below
 * governs, and it admits no exception for a field the map happens to name.
 *
 * <p>Those nine written items and that one echo, plus the outcome facts the screen conveyed through
 * cursor placement and the transfer of control, are exactly what this response carries.
 *
 * <p>The transaction also emits two shared texts &mdash; the thank-you on the exit-key path and the
 * invalid-key text on an unmapped key. Both are owned by the common-message catalog service and are
 * deliberately not restated here, because a second copy would be a second source of truth. Each is a
 * forty-nine-character literal held in a fifty-character field, so its stored value is fifty
 * characters including one filling space; this type neither trims nor pads nor re-cases, so it
 * conveys all fifty. A separate forty-character courtesy text, in a different copybook and naming the
 * application by an older abbreviation, is a different value entirely and must never be merged with
 * it. The title values this response echoes come from that same copybook, whose second title has a
 * commented-out alternative that must remain inactive.
 *
 * <p><strong>A failed credential comparison is not a general error.</strong> The program raises its
 * error flag on five of its nine outcomes and leaves it lowered on four: a successful sign-on, an empty
 * communication area, the exit key, and &mdash; the subtle one &mdash; a failed comparison, which composes a
 * message and moves the cursor without assigning the flag at all. {@link #generalError()} is
 * therefore an explicit primitive that the service sets from the path it took and is never inferred
 * from the presence of {@link #message()}; deriving it would raise the flag on two paths where the
 * legacy leaves it lowered, which is a behavioural change rather than a simplification.
 *
 * <p>{@link #fieldToFocus()} carries only the <em>identity</em> of the field the operator should be
 * returned to, using the mapset's own symbolic field name. The legacy sentinel, the cursor row and
 * column and the attribute bytes are terminal mechanics with no place in a REST contract.
 *
 * <p><strong>Routing is declarative, and the split is two-way.</strong> On a successful comparison
 * the administrator code routes to the administrative menu and <em>everything else</em> routes to the
 * user main menu: there is no third branch and no failure path, so a value outside the two the record
 * defines &mdash; including the administrator letter in lower case &mdash; reaches the main menu.
 * {@link #nextRoute()} conveys the destination as an opaque value supplied by the navigation service,
 * which owns the vocabulary; this type declares no route table, enumeration or registry and resolves,
 * executes and forwards nothing. The two constants it does declare are <em>this</em> transaction's own
 * identity that the screen echoes back, never a destination.
 *
 * <p>{@link #userType()} optionally reports the resolved role so a client knows which menu it is
 * being sent to, and is nullable on purpose: {@link UserType} resolves its two values by exact match
 * with no case folding and no synthetic unknown, so a character outside them yields no type here
 * while the route still carries the main menu. Absence models the legacy silence; an exception would
 * invent a failure the program does not have.
 *
 * <p>{@link #navigationContext()} is the successor state the program assembled before transferring
 * control, echoed by the client on its next call &mdash; request state, not a session. Its enter
 * versus re-enter member is load-bearing well beyond sign-on: on later screens it decides whether
 * field-level error decoration applies at all, and a successful sign-on sets it to first entry.
 *
 * <p><strong>This response never carries a credential in any form</strong> &mdash; not the submitted
 * value, not the stored value, not a one-way transformation of either, and no salt or work factor
 * &mdash; and no signed bearer artefact, expiry, signing key or claim payload. Authorisation travels
 * on the transport's own header, minted and read by the security layer. It carries no cardholder or
 * customer identity beyond what {@link NavigationContext} defines and redacts in its own rendering,
 * which is why this type needs no rendering override of its own.
 *
 * <p>The transaction also emits two of the shared messages declared in {@code app/cpy/CSMSG01Y.cpy}:
 * the thank-you text on the exit-key path at line 89, and the invalid-key text on the unmapped-key
 * path at line 93. Both are declared once, in the common-message catalog service that owns that
 * copybook, and are deliberately <strong>not</strong> restated here - a second copy would be a second
 * source of truth. The obligation this contract does carry is that it can convey them <em>unaltered</em>,
 * which the eighty-character message bound and the total absence of normalization logic guarantee.
 *
 * <p><strong>Fifty characters, not forty-nine.</strong> Both facts about those two texts are true at
 * once and neither may be dropped. The literal written in each copybook value clause is
 * <strong>forty-nine</strong> characters long, and the field holding it is <strong>fifty</strong>
 * characters wide, so the stored content is the forty-nine-character literal followed by one filling
 * space - exactly <strong>fifty</strong> characters. Fifty is the figure this contract conveys and the
 * figure the catalog service guarantees. Those trailing spaces are part of the contract: this type
 * neither trims, strips, re-fills, re-cases nor otherwise normalizes any value, and the module's
 * serialization settings do not trim either, so a fifty-character value arrives at the client as fifty
 * characters.
 *
 * <h2>The forty-character thank-you is a different value entirely</h2>
 *
 * <p>{@code app/cpy/COTTL01Y.cpy} declares a <strong>separate</strong> forty-character courtesy text
 * naming the application by its older abbreviation. It is different text, a different width and a
 * different copybook from the fifty-character thank-you above. The two must never be merged,
 * cross-referenced or de-duplicated. That copybook also supplies the two active forty-character title
 * lines this response echoes, and a commented-out alternative for the second of them that must remain
 * inactive. The title constants themselves belong to the menu response contract; this type carries
 * whatever title <em>values</em> the service supplies and declares none of them.
 *
 * <h2>A failed credential comparison is not a general error</h2>
 *
 * <p>This is the subtlest fact in the whole sign-on contract, and it is why
 * {@link #generalError()} exists as its own component. The program raises its error flag on five of its
 * nine outcomes and leaves it alone on the other four:
 *
 * <ul>
 *   <li>empty communication area, lines 80 to 83 - <strong>no</strong> flag; the screen is simply sent
 *       with the cursor on the user-id field.</li>
 *   <li>exit key, lines 88 to 90 - <strong>no</strong> flag; the courtesy text is sent as plain text.</li>
 *   <li>unmapped key, lines 91 to 95 - flag raised.</li>
 *   <li>user id empty, lines 118 to 123 - flag raised, cursor on the user-id field.</li>
 *   <li>{@code PASSWD} empty, lines 124 to 129 - flag raised, cursor on that field.</li>
 *   <li>credential admitted, lines 223 to 240 - <strong>no</strong> flag; control transfers to the
 *       selected menu.</li>
 *   <li><strong>comparison failed, lines 240 to 245 - no flag is assigned on this path at all</strong>,
 *       yet a message <em>is</em> composed and the cursor <em>is</em> moved to the {@code PASSWD}
 *       field.</li>
 *   <li>record not found, lines 246 to 251 - flag raised, cursor on the user-id field.</li>
 *   <li>read failed otherwise, lines 252 to 257 - flag raised, cursor on the user-id field.</li>
 * </ul>
 *
 * <p>So "carries a message" and "is an error" are genuinely different facts. Deriving the flag from
 * the presence of a message would raise it on two paths where the legacy leaves it lowered - the failed
 * comparison and the exit key - and that is a behavioural change, not a simplification. The flag is
 * therefore an explicit primitive that the service sets from the legacy path it took, and it is never
 * inferred from any other component.
 *
 * <h2>Focus is a field identity, and nothing more</h2>
 *
 * <p>The legacy screen placed the cursor by moving a sentinel into a generated per-field length item.
 * That sentinel, the cursor row and column, and the attribute bytes are terminal mechanics with no
 * place in a REST contract. {@link #focusScreenFieldId()} therefore carries only the <em>identity</em>
 * of the field the operator should be returned to, using the mapset's own symbolic field name, whose
 * widest value in this mapset is {@link #SCREEN_FIELD_ID_LENGTH} characters.
 *
 * <p>The component is named for the concept rather than for the action, and the same name and the same
 * seven-character bound carry it on every screen contract in this package. A client that follows a
 * navigation route from one screen to the next therefore reads one field name throughout, instead of
 * discovering a different spelling of the same idea on each response it receives.
 *
 * <h2>Routing is declarative: the administrator code routes to CA00, everything else to CM00</h2>
 *
 * <p>On a successful comparison the program copies the stored user-type character into the
 * communication area and then makes a single two-way decision on lines 230 to 240: the administrator
 * condition transfers control to the administrative menu program, which is transaction {@code CA00},
 * and the alternative transfers control to the user main menu program, which is transaction
 * {@code CM00}. <strong>There is no third branch and no failure path.</strong> Any stored value that is
 * not the administrator code - including a value outside the two the record defines, and including the
 * administrator letter in lower case - therefore reaches {@code CM00} rather than an error.
 *
 * <p>Those two identifiers are named here for the record only. {@link #nextRoute()} conveys the
 * destination as an opaque declarative value supplied by the navigation service, which owns the route
 * vocabulary; neither identifier is declared as a constant of this type. This type declares no route
 * table, no route enumeration, no constant holder of destinations and no registry, and it resolves,
 * executes and forwards nothing: there is no server-side dispatch, so the client reads the value and
 * drives the next call itself. The two constants this type <em>does</em> declare,
 * {@link #TRANSACTION_NAME} and {@link #PROGRAM_NAME}, are the identity of <em>this</em> transaction
 * that the screen echoes back - never a destination.
 *
 * <p>{@link #userType()} reports the stored user-type character itself so a client knows which menu it
 * is being sent to. It is nullable on purpose, and it is <strong>the raw one-character code, not a
 * resolved role</strong>. Line 227 copies {@code SEC-USR-TYPE} into the communication area as a single
 * character and lines 230 to 240 compare that character; nothing in the estate names the two values,
 * and no third value is rejected. Carrying the character keeps a code outside the two the record defines
 * intact through the round trip - exactly as {@link #nextRoute()} still carries the user main menu for
 * it - whereas resolving it here would erase it and would publish a vocabulary the screen never used.
 *
 * <p>That is also what makes this contract agree with every other one in the module. The same raw
 * one-character code, at the same one-character bound, is what {@link NavigationContext} carries from
 * the very same communication-area item and what the user-administration request and response contracts
 * carry from the security record. One wire vocabulary, spelled the same way on every screen. A client
 * that reads the character from a sign-on response and echoes it into a later call sends back what it
 * received, with no translation table in between.
 *
 * <p>Interpretation is available where it belongs and never on the wire:
 * {@link NavigationContext#resolvedUserType()} resolves the echoed character by exact match, returning
 * an empty result for an absent, blank, wrong-length or undeclared code and applying no case fold, so a
 * lower-case administrator character is not an administrator. The service layer resolves the stored
 * character the same way before it decides the route. Absence models the legacy silence; an exception
 * would invent a failure the program does not have.
 *
 * <h2>Echoed navigation state, not a server session</h2>
 *
 * <p>{@link #navigationContext()} is the immutable successor state the legacy program assembled on
 * lines 224 to 228 before transferring control - the originating transaction and program, the user id,
 * the user-type character and the program context. The client echoes it back on its next call; it is
 * request state, not a session, and any route-shaped member inside it is likewise declarative only. Its
 * enter versus re-enter member is load-bearing well beyond sign-on: on later screens it is what decides
 * whether field-level error decoration is applied at all. The successful sign-on path sets that member
 * to first entry.
 *
 * <h2>What this response never carries</h2>
 *
 * <p><strong>No credential, in any form.</strong> Not the value the operator submitted, not the value
 * the security record stores, not a one-way transformation of either, and no salt or work factor. No
 * component is named for one, and no such literal appears - the seed identities carried in
 * {@code app/jcl/DUSRSECJ.jcl} are never restated here.
 *
 * <p><strong>No signed bearer artefact either.</strong> No access grant and no refresh grant, no
 * expiry, no issue time, no signing key and no claim payload of any kind. No component is named for
 * one, none is derived from one, and none appears indirectly: the successor state below carries screen
 * navigation facts only. That much is a property of this file and is verifiable by reading it.
 *
 * <p><strong>Where authorisation actually travels, and why this contract's silence about it is now an
 * assurance rather than only a property of this file.</strong> The grant is carried on the transport's own
 * header, minted and validated by the security layer, and never echoed into a response body. Every part
 * of that is delivered: {@code api.AuthController} produces this type at {@code /api/auth/signon};
 * {@code service.AuthenticationService} verifies the presented credential against the stored digest before
 * anything is minted; {@code service.SessionTokenIssuer} and {@code config.JwtTokenProvider} mint the
 * grant; and the bearer filter in {@code config.SecurityConfig} validates it on every subsequent request,
 * re-checking it against the current authoritative record so that a demotion, a deletion or a credential
 * change revokes it at the next request instead of at its expiry.
 *
 * <p>The three obligations that were written here for a future endpoint are therefore met rather than
 * outstanding: the grant is minted only after a successful verification, it is returned by way of the
 * transport header rather than by adding a component to this record, and this contract's component set is
 * unchanged - still fifteen, as enumerated below. What remains true, and is the reason the paragraph above
 * is worth keeping, is that this type carries no credential material of any kind; that is a property of
 * the type, it is asserted by this package's own tests, and the mechanism it defers to is now a mechanism
 * that exists.
 *
 * <p>No cardholder or customer identity beyond what {@link NavigationContext} defines and redacts in
 * its own rendering. Consequently this type needs no rendering override of its own, and the absence of
 * one is a deliberate finding rather than an omission - it is the one response contract in this package
 * whose every component is safe to print. Exhaustively, across all fifteen: nine are screen text
 * supplied by the server (the message line, the two title lines, the transaction and program names, the
 * clock date and time, and the application and system identifiers), one is a role, one is a route label,
 * one is a screen-field identifier, one is an error flag, one is the sign-on identifier, and the last is
 * the nested navigation state, which withholds its own six identifying values. No identifier of an
 * account, card, customer or transaction appears, no monetary value, no personal data and no credential,
 * so there is nothing here for a withholding rendering to withhold. The sign-on identifier is the one
 * component worth justifying rather than merely listing: it is not newly disclosed by appearing here,
 * because {@link NavigationContext} carries the same value from the same communication-area item and
 * renders it in the clear, so it already reaches this type's rendering through the nested context - and
 * the screen it came from restates it. What is withheld is withheld absolutely: no credential in any
 * form and no bearer artefact, as stated above. Sibling contracts that do carry regulated values -
 * among them {@link AccountViewResponse}, {@link CardDetailResponse} and {@link TransactionViewResponse}
 * - each override {@code toString()} for exactly that reason.
 *
 * <p>Bean Validation is used for measurement only. Every component is optional in the legacy sense -
 * each can legitimately be absent, empty or space-filled - so no presence, pattern or format constraint
 * appears anywhere, and the width constraints that are present measure without ever altering a value.
 * Absent components are omitted from the serialized form rather than sent as nulls, which is the
 * module-wide inclusion policy.
 *
 * <p>Recorded as decision-log entries DL-001, which fixes the routing split and the verbatim
 * reproduction of all seven sign-on message texts, and DL-054, which scopes that guarantee to the
 * sign-on flow itself rather than to the generic error boundary.
 *
 * <p>Traceability: {@code app/cbl/COSGN00C.cbl} lines 38, 80-83, 88-90, 91-95, 118-131, 149, 181-204,
 * 224-228, 230-240, 240-245, 246-251 and 252-257; {@code app/cpy-bms/COSGN00.CPY};
 * {@code app/bms/COSGN00.bms}; {@code app/cpy/COCOM01Y.cpy}; {@code app/cpy/CSMSG01Y.cpy};
 * {@code app/cpy/COTTL01Y.cpy}. Source checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec},
 * upstream release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * @param message the operator-facing text the screen displayed, from {@code WS-MESSAGE} on line 38 of
 *     {@code COSGN00C} and rendered through {@code ERRMSG} by line 149. Carried at the program-side
 *     width of {@link #MESSAGE_LENGTH} rather than the rendered width of
 *     {@link #SCREEN_MESSAGE_LENGTH}. Conveyed exactly as supplied, including every trailing space, so
 *     a fifty-character shared message survives intact. Absent when the transaction emitted no text.
 * @param generalError whether the program raised its error flag, from {@code WS-ERR-FLG} and its two
 *     condition names on lines 40 to 42, which line 75 lowers on entry. An explicit primitive and never
 *     derived from {@code message}: the failed-comparison path on lines 240 to 245 composes a message
 *     while leaving this {@code false}, and so does the exit-key path on lines 88 to 90.
 * @param focusScreenFieldId the symbolic name of the screen field the operator should be returned to,
 *     replacing the cursor placement performed on lines 82, 121, 126, 244, 250 and 255. At most
 *     {@link #SCREEN_FIELD_ID_LENGTH} characters, the widest symbolic field name in
 *     {@code app/bms/COSGN00.bms}. The identity only: never the legacy sentinel, never a row or column,
 *     never an attribute byte. Absent when no field is singled out.
 * @param nextRoute the declarative destination value for the client's next call, replacing the two
 *     transfers of control on lines 230 to 240. Opaque here and unconstrained in width by design: the
 *     vocabulary belongs to the navigation service, and this type neither resolves nor dispatches it.
 *     Absent on every path that redisplays the sign-on screen.
 * @param navigationContext the successor state assembled on lines 224 to 228 of {@code COSGN00C} from
 *     the communication area of {@code app/cpy/COCOM01Y.cpy}, echoed by the client on its next call.
 *     Absent until a sign-on succeeds.
 * @param userId the user identifier the screen restates, the map's safe output item {@code USERIDO} on
 *     line 140 of {@code app/cpy-bms/COSGN00.CPY}, at most {@link #USER_ID_LENGTH} characters. Present
 *     on the six paths that redisplay the sign-on screen, so a client can rebuild it without making the
 *     operator retype an identifier that was not what failed. Absent on the first-entry path, where
 *     lines 80 to 83 clear the whole output area before sending, and absent on a successful sign-on,
 *     where the screen is replaced and {@link #navigationContext()} carries the identifier onward.
 *     Conveyed exactly as supplied, with no trim, no fold and no fill, so the value the operator typed
 *     is the value the client receives. There is deliberately no credential counterpart, whatever the
 *     map declares alongside it.
 * @param userType the raw one-character user-type code, from {@code SEC-USR-TYPE} as copied on line 227,
 *     at most {@link #USER_TYPE_LENGTH} character. The code itself and never a resolved role, so that a
 *     value outside the two the record defines survives the round trip rather than being erased, and so
 *     that this response spells the code the same way {@link NavigationContext} and the
 *     user-administration contracts do. Nullable on purpose: the route still carries the user main menu
 *     for any code other than the administrator one. Resolve it through
 *     {@link NavigationContext#resolvedUserType()}, which never throws.
 * @param transactionName the transaction identifier the screen echoes, from {@code WS-TRANID} on line
 *     37 written to {@code TRNNAME} by line 183. Four characters, and for this transaction always
 *     {@link #TRANSACTION_NAME}.
 * @param programName the program name the screen echoes, from {@code WS-PGMNAME} on line 36 written to
 *     {@code PGMNAME} by line 184. Eight characters, and for this transaction always
 *     {@link #PROGRAM_NAME}.
 * @param title01 the first title line, from {@code CCDA-TITLE01} of {@code app/cpy/COTTL01Y.cpy}
 *     written to {@code TITLE01} by line 181. Forty characters including its leading and trailing
 *     spaces, conveyed exactly as supplied.
 * @param title02 the second title line, from the <em>active</em> {@code CCDA-TITLE02} value of
 *     {@code app/cpy/COTTL01Y.cpy} written to {@code TITLE02} by line 182. Forty characters, conveyed
 *     exactly as supplied. The commented-out alternative in that copybook is inactive and is never
 *     carried here.
 * @param currentDate the current date as the screen rendered it, written to {@code CURDATE} by line
 *     190. Eight characters of text, never a date or numeric type, so the rendered form including any
 *     leading zero survives unchanged.
 * @param currentTime the current time as the screen rendered it, written to {@code CURTIME} by line
 *     196. Nine characters - the only nine-character item across all seventeen mapsets in the estate -
 *     and text for the same reason as the date.
 * @param applicationId the region's application identifier, assigned into {@code APPLID} by line 199.
 *     Eight characters. Absent outside a region that supplies one.
 * @param systemId the region's system identifier, assigned into {@code SYSID} by line 203. Eight
 *     characters. Absent outside a region that supplies one.
 */
public record SignOnResponse(

        @Size(max = SignOnResponse.MESSAGE_LENGTH) String message,

        boolean generalError,

        /* Cursor placement of lines 82, 121, 126, 244, 250 and 255, reduced to the field's symbolic
         * name. Identity only - no sentinel, no coordinates, no attribute byte. Named and bounded
         * identically on every screen contract in this package. */
        @Size(max = SignOnResponse.SCREEN_FIELD_ID_LENGTH) String focusScreenFieldId,

        String nextRoute,

        NavigationContext navigationContext,

        /* USERIDO, width 8, app/cpy-bms/COSGN00.CPY line 140 - the map's safe output item for the
         * operator's own entry, echoed so a client can rebuild the six redisplay screens. Absent on
         * first entry (lines 80-83 clear the output area) and on success. No credential counterpart
         * exists here, whatever PASSWDO on line 146 of the same copybook declares. */
        @Size(max = SignOnResponse.USER_ID_LENGTH) String userId,

        /* The stored user-type character of line 227, raw. One character, never a resolved role, so an
         * undeclared code survives the round trip; the same spelling NavigationContext and the
         * user-administration contracts use. Nullable on purpose: any code other than the administrator
         * one still routes to the main menu. Resolve via NavigationContext.resolvedUserType(). */
        @Size(max = SignOnResponse.USER_TYPE_LENGTH) String userType,

        @Size(max = SignOnResponse.TRANSACTION_NAME_LENGTH) String transactionName,

        @Size(max = SignOnResponse.PROGRAM_NAME_LENGTH) String programName,

        @Size(max = SignOnResponse.SCREEN_TITLE_LENGTH) String title01,

        /* The active title value only; the copybook's commented-out alternative stays inactive. */
        @Size(max = SignOnResponse.SCREEN_TITLE_LENGTH) String title02,

        @Size(max = SignOnResponse.CURRENT_DATE_LENGTH) String currentDate,

        @Size(max = SignOnResponse.CURRENT_TIME_LENGTH) String currentTime,

        @Size(max = SignOnResponse.APPLICATION_ID_LENGTH) String applicationId,

        @Size(max = SignOnResponse.SYSTEM_ID_LENGTH) String systemId) {

    /**
     * Width of the message this contract conveys: the value the program composes, which is the wider
     * of the two widths in play and comfortably admits the fifty-character shared messages with their
     * trailing spaces intact. See {@link #SCREEN_MESSAGE_LENGTH} for the rendered width.
     */
    public static final int MESSAGE_LENGTH = 80;

    /**
     * Width at which the screen actually rendered the message, recorded rather than enforced: the
     * program's wider work field is moved into this narrower item, so its last two characters never
     * reached the terminal. Declared so the difference is documented instead of lost; no text the
     * transaction can emit is long enough to be affected.
     */
    public static final int SCREEN_MESSAGE_LENGTH = 78;

    /**
     * Bound on a screen-field identity: the widest symbolic field name in the sign-on mapset, so the
     * bound is the mapset's own rather than an arbitrary one.
     */
    public static final int SCREEN_FIELD_ID_LENGTH = 7;

    /**
     * Width of the echoed user identifier: 8 characters.
     *
     * <p>The width of the map's safe output item {@code USERIDO} on line 140 of
     * {@code app/cpy-bms/COSGN00.CPY}, of its inbound counterpart {@code USERIDI} on line 72, of the
     * screen field defined at length eight on line 159 of {@code app/bms/COSGN00.bms}, and of the
     * program's own {@code WS-USER-ID} work field on line 45 of {@code app/cbl/COSGN00C.cbl}. All four
     * agree, and the security record's key agrees with them, so eight is the width the whole sign-on
     * path uses.
     *
     * <p>The same figure bounds the identifier on {@link NavigationContext} and on the
     * user-administration contracts, each of which declares it for itself: one width, declared where it
     * is used, so no contract depends on another for its own measurement.
     */
    public static final int USER_ID_LENGTH = 8;

    /**
     * Width of the echoed user-type code: 1 character.
     *
     * <p>The width of {@code SEC-USR-TYPE} in the security record and of {@code CDEMO-USER-TYPE} in the
     * communication area of {@code app/cpy/COCOM01Y.cpy}, which line 227 of
     * {@code app/cbl/COSGN00C.cbl} copies into and lines 230 to 240 compare. One character, carried as
     * one character, because the comparison the program makes is a single-character comparison.
     *
     * <p>Declared separately from every other one-character figure in the module even though the values
     * coincide, so that changing what one field measures cannot silently change another.
     */
    public static final int USER_TYPE_LENGTH = 1;

    /** Width of the echoed transaction identifier: the map's {@code TRNNAME} item. */
    public static final int TRANSACTION_NAME_LENGTH = 4;

    /** Width of the echoed program name: the map's {@code PGMNAME} item. */
    public static final int PROGRAM_NAME_LENGTH = 8;

    public static final int SCREEN_TITLE_LENGTH = 40;

    /** Width of the rendered current date: the map's {@code CURDATE} item. */
    public static final int CURRENT_DATE_LENGTH = 8;

    /**
     * Width of the rendered current time: nine characters, the only nine-character value item across
     * the seventeen mapsets, which is why it is not folded into the date width.
     */
    public static final int CURRENT_TIME_LENGTH = 9;

    /** Width of the region's application identifier: the map's {@code APPLID} item. */
    public static final int APPLICATION_ID_LENGTH = 8;

    /** Width of the region's system identifier: the map's {@code SYSID} item. */
    public static final int SYSTEM_ID_LENGTH = 8;

    /**
     * Identifier of the sign-on transaction itself, echoed back into the screen's transaction-name
     * item. This transaction's <strong>own</strong> identity, never a destination; destinations reach
     * a client through {@link #nextRoute()}.
     */
    public static final String TRANSACTION_NAME = "CC00";

    /**
     * Name of the sign-on program itself, echoed back into the screen's program-name item and copied
     * into the originating-program member of the successor state. Like {@link #TRANSACTION_NAME}, an
     * origin identity and never a destination.
     */
    public static final String PROGRAM_NAME = "COSGN00C";

    /**
     * Prompt emitted when the user id arrives empty - {@code app/cbl/COSGN00C.cbl} line 120, the first
     * clause of the ordered cascade. Because the construct stops at its first matching clause, a
     * submission with both entry fields empty yields this text and never {@link #MSG_PROMPT_PASSWD}.
     */
    public static final String MSG_PROMPT_USERID = "Please enter User ID ...";

    /**
     * Prompt emitted when the credential field arrives empty - {@code app/cbl/COSGN00C.cbl} line 125,
     * the second clause of the same cascade, reached only when the user id was supplied. External
     * interface wording reproduced verbatim; no component of this contract carries the value it names.
     */
    public static final String MSG_PROMPT_PASSWD = "Please enter Password ...";

    /**
     * Message emitted when the record was found but the comparison failed -
     * {@code app/cbl/COSGN00C.cbl} line 242. The one failure in the flow that leaves
     * {@link #generalError()} {@code false}: the path composes this text and moves the cursor without
     * assigning the error flag at all.
     */
    public static final String MSG_WRONG_PASSWD = "Wrong Password. Try again ...";

    /**
     * Message emitted when the keyed read reported no such record - {@code app/cbl/COSGN00C.cbl}
     * line 249. Unlike {@link #MSG_WRONG_PASSWD}, this path raises the error flag.
     */
    public static final String MSG_USER_NOT_FOUND = "User not found. Try again ...";

    /**
     * Message emitted for every read failure other than a missing record -
     * {@code app/cbl/COSGN00C.cbl} line 254, the catch-all outcome, which also raises the error flag.
     */
    public static final String MSG_UNABLE_TO_VERIFY = "Unable to verify the User ...";
}
