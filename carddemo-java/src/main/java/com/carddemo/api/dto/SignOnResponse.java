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

import com.carddemo.domain.enums.UserType;
import jakarta.validation.constraints.Size;

/**
 * Immutable sign-on response contract for legacy CICS transaction {@code CC00}.
 *
 * <p>The REST-era replacement for everything the 3270 sign-on screen sent <em>outbound</em>. Program
 * {@code app/cbl/COSGN00C.cbl} drives map {@code COSGN0A} of mapset {@code COSGN00}; the symbolic form
 * of that map is {@code app/cpy-bms/COSGN00.CPY} and its layout authority is
 * {@code app/bms/COSGN00.bms}. The paired inbound contract is {@link SignOnRequest}, and the state the
 * transaction hands to its successor is {@link NavigationContext}, modelled from
 * {@code app/cpy/COCOM01Y.cpy}.
 *
 * <h2>Nine of the map's eleven value items are response-side</h2>
 *
 * <p>The symbolic map declares eleven value items. Two of them - the user id and the operator's
 * credential - are typed by the operator and therefore belong to {@link SignOnRequest}. The remaining
 * nine are screen metadata the program <em>writes</em>: {@code TRNNAME}, {@code TITLE01},
 * {@code TITLE02}, {@code CURDATE}, {@code CURTIME}, {@code PGMNAME}, {@code APPLID}, {@code SYSID}
 * and {@code ERRMSG}. Eight of the nine are written by the header paragraph on lines 181 to 204 of
 * {@code COSGN00C} and the ninth, the message, is written on line 149 immediately before the screen is
 * transmitted. Those nine, plus the outcome facts the screen conveyed through cursor placement and the
 * transfer of control, are exactly what this response carries.
 *
 * <p>Every one of them crosses the API as a bounded {@link String} at its measured screen width, never
 * as a numeric type. The current date and the current time are text as the screen rendered them, so a
 * leading zero survives; typing either as a number would destroy it. The map's per-field length, flag
 * and attribute items, its leading twelve-character terminal input/output area filler, the terminal
 * identity, the cursor coordinates, the attribute bytes and the map and mapset names are generated
 * 3270 plumbing rather than contract, and none of them is modelled here.
 *
 * <h2>The message is eighty characters wide, not seventy-eight</h2>
 *
 * <p>Two widths are in play and both are recorded deliberately. The program's own message work field
 * {@code WS-MESSAGE}, declared on line 38 of {@code COSGN00C}, is an <strong>eighty</strong>-character
 * alphanumeric field, while the map's error item {@code ERRMSG} is <strong>seventy-eight</strong>.
 * Line 149 moves the wider field into the narrower one, so the screen renders at most seventy-eight of
 * the eighty characters and silently drops the final two. This contract carries the message at the
 * wider, program-side width of {@link #MESSAGE_LENGTH}, because that is the value the program actually
 * composed; {@link #SCREEN_MESSAGE_LENGTH} records the narrower rendered width so the difference is
 * visible rather than lost. In practice the narrowing never bites: the longest text the transaction can
 * emit is fifty characters, well inside both bounds.
 *
 * <h2>The five message literals this program emits directly</h2>
 *
 * <p>Reproduced verbatim as the constants below, because they are external interface text that
 * operators and downstream tooling match on. Each ends with a single space followed by exactly three
 * dots, and each is checked character for character by the interface-contract gate.
 *
 * <ul>
 *   <li>{@link #MSG_PROMPT_USERID} - line 120, first clause of the ordered cascade, when the user id
 *       arrives empty.</li>
 *   <li>{@link #MSG_PROMPT_PASSWD} - line 125, second clause, when the {@code PASSWD} field arrives
 *       empty. Because the cascade stops at its first matching clause, a submission with both fields
 *       empty produces the user-id prompt <em>only</em>, never both.</li>
 *   <li>{@link #MSG_WRONG_PASSWD} - line 242, when the record was found but the comparison failed.</li>
 *   <li>{@link #MSG_USER_NOT_FOUND} - line 249, when the keyed read reported no such record.</li>
 *   <li>{@link #MSG_UNABLE_TO_VERIFY} - line 254, for every other read failure.</li>
 * </ul>
 *
 * <h2>Two further messages this response carries but does not own</h2>
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
 * eight outcomes and leaves it alone on the other three:
 *
 * <ul>
 *   <li>empty communication area, lines 80 to 83 - <strong>no</strong> flag; the screen is simply sent
 *       with the cursor on the user-id field.</li>
 *   <li>exit key, lines 88 to 90 - <strong>no</strong> flag; the courtesy text is sent as plain text.</li>
 *   <li>unmapped key, lines 91 to 95 - flag raised.</li>
 *   <li>user id empty, lines 118 to 123 - flag raised, cursor on the user-id field.</li>
 *   <li>{@code PASSWD} empty, lines 124 to 129 - flag raised, cursor on that field.</li>
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
 * place in a REST contract. {@link #fieldToFocus()} therefore carries only the <em>identity</em> of the
 * field the operator should be returned to, using the mapset's own symbolic field name, whose widest
 * value in this mapset is {@link #SCREEN_FIELD_ID_LENGTH} characters.
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
 * <p>{@link #userType()} optionally reports the resolved role so a client knows which menu it is being
 * sent to. It is nullable on purpose. {@link UserType} defines exactly two values and resolves them by
 * exact match, with no synthetic unknown value and no case folding, so a stored character outside those
 * two simply yields <em>no</em> type here while {@link #nextRoute()} still carries the user main menu.
 * Absence models the legacy silence; an exception would invent a failure the program does not have.
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
 * expiry, no issue time, no signing key and no claim payload of any kind. Authorisation travels on the
 * transport's own header, is minted and read by the security layer, and is never echoed into this body.
 * Nor does any of it appear indirectly: the successor state below carries screen navigation facts only.
 *
 * <p>No cardholder or customer identity beyond what {@link NavigationContext} defines and redacts in
 * its own rendering. Consequently this type needs no rendering override of its own: its default
 * rendering exposes screen text, a route value, a role and a nested context that redacts itself.
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
 * @param fieldToFocus the symbolic name of the screen field the operator should be returned to,
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
 * @param userType the resolved role behind the stored user-type character, from
 *     {@code SEC-USR-TYPE} as copied on line 227. Nullable on purpose: a character outside the two the
 *     record defines resolves to nothing, and the route still carries the user main menu.
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

        /* WS-MESSAGE, program-side width 80, COSGN00C line 38; rendered through ERRMSG (width 78) by
         * line 149. Carried at the wider width and never normalized, so a 50-character shared message
         * keeps its trailing spaces. */
        @Size(max = SignOnResponse.MESSAGE_LENGTH) String message,

        /* WS-ERR-FLG, COSGN00C lines 40-42, lowered on entry by line 75. Explicit and never derived:
         * lines 240-245 compose a message with the flag left lowered, and so do lines 88-90. */
        boolean generalError,

        /* Cursor placement of lines 82, 121, 126, 244, 250 and 255, reduced to the field's symbolic
         * name. Identity only - no sentinel, no coordinates, no attribute byte. */
        @Size(max = SignOnResponse.SCREEN_FIELD_ID_LENGTH) String fieldToFocus,

        /* The transfers of control on COSGN00C lines 230-240, as a declarative value. Opaque and
         * deliberately unbounded here: the navigation service owns the vocabulary. Never dispatched. */
        String nextRoute,

        /* Successor state assembled on COSGN00C lines 224-228 from the communication area of
         * app/cpy/COCOM01Y.cpy. Client-echoed request state, not a server session. */
        NavigationContext navigationContext,

        /* The stored user-type character of line 227, resolved. Nullable on purpose: a character
         * outside the two defined values resolves to nothing and still routes to the main menu. */
        UserType userType,

        /* WS-TRANID, width 4, COSGN00C line 37, written to TRNNAME by line 183. */
        @Size(max = SignOnResponse.TRANSACTION_NAME_LENGTH) String transactionName,

        /* WS-PGMNAME, width 8, COSGN00C line 36, written to PGMNAME by line 184. */
        @Size(max = SignOnResponse.PROGRAM_NAME_LENGTH) String programName,

        /* CCDA-TITLE01, width 40, app/cpy/COTTL01Y.cpy line 19, written to TITLE01 by line 181. */
        @Size(max = SignOnResponse.SCREEN_TITLE_LENGTH) String title01,

        /* CCDA-TITLE02, width 40, the ACTIVE value on app/cpy/COTTL01Y.cpy line 22 - not the
         * commented-out alternative on line 21 - written to TITLE02 by line 182. */
        @Size(max = SignOnResponse.SCREEN_TITLE_LENGTH) String title02,

        /* CURDATE, width 8, written by COSGN00C line 190. Text, never a date or numeric type. */
        @Size(max = SignOnResponse.CURRENT_DATE_LENGTH) String currentDate,

        /* CURTIME, width 9, written by COSGN00C line 196 - the estate's only nine-character map item. */
        @Size(max = SignOnResponse.CURRENT_TIME_LENGTH) String currentTime,

        /* APPLID, width 8, assigned by COSGN00C line 199. */
        @Size(max = SignOnResponse.APPLICATION_ID_LENGTH) String applicationId,

        /* SYSID, width 8, assigned by COSGN00C line 203. */
        @Size(max = SignOnResponse.SYSTEM_ID_LENGTH) String systemId) {

    /**
     * Width of the message this contract conveys: the program's own message work field
     * {@code WS-MESSAGE}, declared on line 38 of {@code app/cbl/COSGN00C.cbl}.
     *
     * <p>The wider of the two widths in play, and the one carried, because it is the value the program
     * composed before the screen narrowed it. Comfortably admits the fifty-character shared messages of
     * {@code app/cpy/CSMSG01Y.cpy} with their trailing spaces intact, and every one of the five texts
     * this program emits directly.
     */
    public static final int MESSAGE_LENGTH = 80;

    /**
     * Width at which the screen actually rendered the message: the map's {@code ERRMSG} item.
     *
     * <p>Recorded rather than enforced. Line 149 of {@code app/cbl/COSGN00C.cbl} moves the
     * {@link #MESSAGE_LENGTH}-character work field into this narrower item, so the last two characters
     * never reached the terminal. Declared so the two-character difference is documented instead of
     * lost; no text the transaction can emit is long enough to be affected.
     */
    public static final int SCREEN_MESSAGE_LENGTH = 78;

    /**
     * Bound on a screen-field identity: the widest symbolic field name in {@code app/bms/COSGN00.bms}.
     *
     * <p>Six of that mapset's fields are named with seven characters and none with more, which makes
     * seven the mapset's own bound rather than an arbitrary one. It coincides with the bound
     * {@link NavigationContext} applies to a map name.
     */
    public static final int SCREEN_FIELD_ID_LENGTH = 7;

    /** Width of the echoed transaction identifier: the map's {@code TRNNAME} item. */
    public static final int TRANSACTION_NAME_LENGTH = 4;

    /** Width of the echoed program name: the map's {@code PGMNAME} item. */
    public static final int PROGRAM_NAME_LENGTH = 8;

    /**
     * Width of each screen title line: the map's {@code TITLE01} and {@code TITLE02} items, matching the
     * two title values of {@code app/cpy/COTTL01Y.cpy}.
     */
    public static final int SCREEN_TITLE_LENGTH = 40;

    /** Width of the rendered current date: the map's {@code CURDATE} item. */
    public static final int CURRENT_DATE_LENGTH = 8;

    /**
     * Width of the rendered current time: the map's {@code CURTIME} item.
     *
     * <p>Nine characters, and the only nine-character value item across all seventeen mapsets in the
     * estate - which is why it is declared separately rather than folded into the date width.
     */
    public static final int CURRENT_TIME_LENGTH = 9;

    /** Width of the region's application identifier: the map's {@code APPLID} item. */
    public static final int APPLICATION_ID_LENGTH = 8;

    /** Width of the region's system identifier: the map's {@code SYSID} item. */
    public static final int SYSTEM_ID_LENGTH = 8;

    /**
     * Identifier of the sign-on transaction itself, from {@code WS-TRANID} on line 37 of
     * {@code app/cbl/COSGN00C.cbl} and echoed into the map's {@code TRNNAME} item by line 183.
     *
     * <p>This transaction's <strong>own</strong> identity, never a destination. Destinations reach a
     * client through {@link #nextRoute()} and their vocabulary belongs to the navigation service; this
     * type declares no route table, enumeration or registry.
     */
    public static final String TRANSACTION_NAME = "CC00";

    /**
     * Name of the sign-on program itself, from {@code WS-PGMNAME} on line 36 of
     * {@code app/cbl/COSGN00C.cbl} and echoed into the map's {@code PGMNAME} item by line 184.
     *
     * <p>Like {@link #TRANSACTION_NAME}, this is the origin's identity and never a destination. It is
     * also the value the program copies into the originating-program member of the successor state on
     * line 225.
     */
    public static final String PROGRAM_NAME = "COSGN00C";

    /**
     * Prompt emitted when the user id arrives empty - line 120 of {@code app/cbl/COSGN00C.cbl}.
     *
     * <p>The first clause of the ordered cascade on lines 118 to 131. Because the construct stops at its
     * first matching clause, a submission with both entry fields empty yields this text and never
     * {@link #MSG_PROMPT_PASSWD}. Twenty-four characters, ending in a space and exactly three dots.
     */
    public static final String MSG_PROMPT_USERID = "Please enter User ID ...";

    /**
     * Prompt emitted when the {@code PASSWD} field arrives empty - line 125 of
     * {@code app/cbl/COSGN00C.cbl}.
     *
     * <p>The second clause of the same cascade, reached only when the user id was supplied.
     * Twenty-five characters, ending in a space and exactly three dots. The text is external interface
     * wording reproduced verbatim; no component of this contract carries the value it refers to.
     */
    public static final String MSG_PROMPT_PASSWD = "Please enter Password ...";

    /**
     * Message emitted when the record was found but the comparison failed - line 242 of
     * {@code app/cbl/COSGN00C.cbl}.
     *
     * <p>The one failure in the entire flow that leaves {@link #generalError()} {@code false}: lines 240
     * to 245 compose this text and move the cursor without assigning the error flag at all. Twenty-nine
     * characters, including the sentence-ending dot after the first two words, and ending in a space and
     * exactly three dots.
     */
    public static final String MSG_WRONG_PASSWD = "Wrong Password. Try again ...";

    /**
     * Message emitted when the keyed read reported no such record - line 249 of
     * {@code app/cbl/COSGN00C.cbl}.
     *
     * <p>Unlike {@link #MSG_WRONG_PASSWD}, this path raises the error flag. Twenty-nine characters,
     * including the sentence-ending dot after the first three words, and ending in a space and exactly
     * three dots.
     */
    public static final String MSG_USER_NOT_FOUND = "User not found. Try again ...";

    /**
     * Message emitted for every read failure other than a missing record - line 254 of
     * {@code app/cbl/COSGN00C.cbl}.
     *
     * <p>The catch-all clause of the read outcome, which also raises the error flag. Twenty-nine
     * characters, with a capital letter on the final word, and ending in a space and exactly three dots.
     */
    public static final String MSG_UNABLE_TO_VERIFY = "Unable to verify the User ...";
}

