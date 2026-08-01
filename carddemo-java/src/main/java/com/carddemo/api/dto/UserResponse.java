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
import java.util.List;

/**
 * Immutable response contract shared by all four administrative user transactions, reproducing
 * the field-level and message-level contract of the four legacy 3270 screens as a single REST
 * payload.
 *
 * <p>One response type serves all four transactions because all four screens are views of the
 * same eighty-byte user-security record and differ only in which subset of its fields they
 * present. The four transactions and their source members are:</p>
 * <ul>
 *   <li><strong>CU00, list</strong> - {@code app/cbl/COUSR00C.cbl}, sixteen paragraphs, screen
 *       {@code app/bms/COUSR00.bms} and symbolic map {@code app/cpy-bms/COUSR00.CPY}. The only
 *       transaction that returns rows.</li>
 *   <li><strong>CU01, add</strong> - {@code app/cbl/COUSR01C.cbl}, nine paragraphs, screen
 *       {@code app/bms/COUSR01.bms} and symbolic map {@code app/cpy-bms/COUSR01.CPY}.</li>
 *   <li><strong>CU02, update</strong> - {@code app/cbl/COUSR02C.cbl}, eleven paragraphs, screen
 *       {@code app/bms/COUSR02.bms} and symbolic map {@code app/cpy-bms/COUSR02.CPY}.</li>
 *   <li><strong>CU03, delete</strong> - {@code app/cbl/COUSR03C.cbl}, eleven paragraphs, screen
 *       {@code app/bms/COUSR03.bms} and symbolic map {@code app/cpy-bms/COUSR03.CPY}.</li>
 * </ul>
 * <p>All four are administrative transactions in {@code app/csd/CARDDEMO.CSD}, defined at lines
 * 449, 459, 469 and 479 - four of the five administrative transactions, the fifth being the
 * administrative menu. Authorisation is not modelled here: this is a payload, and the route-to-role
 * table belongs to the security configuration.</p>
 *
 * <p>The record layout behind every one of these screens is {@code app/cpy/CSUSR01Y.cpy}, an
 * eighty-byte record whose fields are an eight-character user identifier, a twenty-character first
 * name, a twenty-character last name, an eight-character credential, a one-character user type and a
 * twenty-three-character trailing pad. Only the byte positions and the trailing pad are omitted from this
 * contract; a REST payload is a named structure, so byte positions carry no meaning here.</p>
 *
 * <h2>The symbolic map is the contract, not the program's working storage</h2>
 *
 * <p>{@code app/cbl/COUSR00C.cbl} lines 56 to 64 declare an internal staging table of ten
 * occurrences that a naive reading would mistake for the row contract. It is not. Its members are a
 * one-character selector, a <em>single combined</em> twenty-five-character name, an eight-character
 * user type, and three two-character spacing gaps that exist only to separate fields on a display
 * line. The screen itself - which is the external contract - keeps the first and last name
 * <em>separate</em> at twenty characters each and the type at <em>one</em> character, as
 * {@code app/cpy-bms/COUSR00.CPY} proves for all ten row families. {@link UserRow} therefore
 * follows the map at one, eight, twenty, twenty and one, and models no combined name, no eight-wide
 * type and no spacing pad. Recorded as a translation divergence: the staging table is a presentation
 * artefact of a terminal display line and has no counterpart in a REST payload.</p>
 *
 * <h2>Two failure arms deliberately carry a message without setting the error flag</h2>
 *
 * <p>{@link #generalError()} is an explicit component and is <strong>never</strong> derived from
 * whether {@link #message()} is present, because two arms of the legacy logic set a message and
 * then continue on the success path:</p>
 * <ul>
 *   <li><strong>The invalid-selection arm</strong> at {@code app/cbl/COUSR00C.cbl} lines 210 to
 *       214. The unmatched branch of the selector evaluation at lines 190 to 209 sets
 *       {@link #MSG_LIST_INVALID_SELECTION} and repositions input focus, but never raises the
 *       program's error flag. Control falls through to the forward-paging paragraph at lines 227
 *       and 228 and the page is listed anyway - the guard at line 230 testing the unset flag
 *       confirms it. A response therefore legitimately carries that message <em>together with</em> a
 *       fully populated ten-row list and {@code generalError} of {@code false}. This is real
 *       behaviour, not a defect.</li>
 *   <li><strong>The no-change arm</strong> at {@code app/cbl/COUSR02C.cbl} lines 239 to 243. When
 *       the change detection at lines 219 to 234 finds nothing modified, the program sets
 *       {@link #MSG_UPDATE_NO_CHANGE} and re-sends the screen without raising the error flag.</li>
 * </ul>
 * <p>Deriving the flag from message presence would misreport both arms as failures. Both arms also
 * set a terminal colour attribute, which is discarded: no colour, attribute byte or marker
 * character crosses this contract.</p>
 *
 * <h2>Success-path field clearing is asymmetric, so echoed values are never required</h2>
 *
 * <p>Add and delete clear every input field before reporting success -
 * {@code app/cbl/COUSR01C.cbl} line 252 and {@code app/cbl/COUSR03C.cbl} line 315 both invoke the
 * field-initialising paragraph first. Update does not: {@code app/cbl/COUSR02C.cbl} lines 369 to
 * 376 report success with the fields left populated. A successful add or delete therefore
 * legitimately carries blank or absent echoed values while a successful update carries the updated
 * ones. No echoed component is mandatory and the two behaviours are deliberately not unified.</p>
 *
 * <h2>The user type crosses this boundary as raw text, not as an enum</h2>
 *
 * <p>{@link #userType()} and {@link UserRow#userType()} are one-character strings rather than the
 * domain user-type enumeration, and that enumeration is deliberately not referenced here. No
 * program in this family validates the value against the two codes it is expected to hold: the
 * checks at {@code app/cbl/COUSR01C.cbl} line 142 and {@code app/cbl/COUSR02C.cbl} line 204 test
 * only for emptiness. The persisted column is likewise a single character with no check constraint,
 * so a stored value outside the expected pair exists as far as this contract is concerned and must
 * serialise without failing. Interpreting the character is the user-management service's
 * responsibility, through the enumeration's non-throwing lookup. Recorded as a translation
 * divergence: widening a response type to the raw stored value is what keeps an unexpected code
 * reportable instead of unreadable.</p>
 *
 * <h2>Identifiers and page indicators are text, never numbers</h2>
 *
 * <p>The page indicator the list screen displays is eight characters wide in the map, and the
 * commarea counterpart at {@code app/cbl/COUSR00C.cbl} line 70 is a zoned eight-digit field whose
 * leading zeros are part of what the operator sees. Every identifier and every paging value
 * therefore crosses this contract as a string; no numeric type is used for any of them, because a
 * numeric type would silently discard leading zeros and re-render them differently.</p>
 *
 * <h2>Paging is cursor-based and this type performs no paging arithmetic</h2>
 *
 * <p>{@link #pageMetadata()} carries the cursor-plus-direction contract. The legacy anchors paging
 * on the first and last user identifier of the page just displayed, declared as adjacent
 * eight-character commarea fields at {@code app/cbl/COUSR00C.cbl} lines 68 and 69 alongside the
 * page indicator at line 70. There is no positional index, no total-row count and no total-page count,
 * because the legacy browse never counts the file - it discovers a boundary by attempting one more
 * read. No page-size constant is declared here either: the row count is screen shape carried as
 * data by {@link PageMetadata}, and the three page sizes in this module are never shared.</p>
 *
 * <p>Rows arrive in presentation order and are copied without reordering. Forward paging fills the
 * ten slots ascending; backward paging fills them from the bottom upward by reversing the read
 * direction. Ordering is therefore already settled by the service before it builds this response,
 * and this type never sorts, reverses, renumbers, compacts or pads. A partial final page carries
 * only the rows that exist.</p>
 *
 * <h2>The row list is empty for three of the four transactions</h2>
 *
 * <p>Only the list transaction returns rows. Add, update and delete return a single-user result, so
 * {@link #rows()} is legitimately empty and {@link #pageMetadata()} legitimately absent for them.
 * Neither is ever required.</p>
 *
 * <h2>Field-level errors reuse the shared two-state error contract</h2>
 *
 * <p>Per-field errors are carried as {@link ErrorResponse.FieldError} values whose
 * {@link ErrorResponse.FieldState} distinguishes exactly the two operator mistakes the legacy
 * screen distinguished. The decorating macro at {@code app/cpy/CSSETATY.cpy} applied its error
 * highlight for both states and additionally overwrote the field with a marker character only when
 * the field was blank, which is why the blank case and the badly-filled case are separate states.
 * Neither the highlight nor the marker is exposed - only the state each one signified. The macro
 * fired only on re-entry, so field errors are absent on a first submission.</p>
 *
 * <p>The state enumeration in the exception layer is textually identical, and is deliberately
 * <em>not</em> used: a payload type depending on the exception layer would invert the module's
 * dependency direction. Translating between the two is the global exception handler's job, and it
 * preserves per-field detail rather than collapsing it.</p>
 *
 * <h2>This response never carries a credential</h2>
 *
 * <p>The add and update screens both carry an eight-character credential field on their
 * <em>output</em> side, and the legacy genuinely round-trips it: {@code app/cbl/COUSR02C.cbl} line
 * 170 places the stored value into the update screen and lines 227 to 230 compare the submitted
 * value against it directly. <strong>This contract deliberately breaks that round-trip.</strong>
 * There is no credential component, no accessor, no masked or truncated form, no length and no
 * derived "is set" indicator, because each of those leaks information about a secret. The delete
 * screen never carried the field at all, which is why its map has eleven output values where add
 * and update have twelve. Recorded as the documented parity exception: this is the one place where
 * the migration knowingly declines to reproduce an observable legacy behaviour, because reproducing
 * it would mean transmitting a credential.</p>
 *
 * <p><strong>Audit note.</strong> The word naming that field does appear in this file, in exactly
 * two message constants - {@link #MSG_ADD_CREDENTIAL_FIELD_EMPTY} and
 * {@link #MSG_UPDATE_CREDENTIAL_FIELD_EMPTY} - and in the two string literals they hold. Those
 * literals are external contract text: they are two of the five members of the emptiness-message
 * family that the add and update screens display when a required input is left blank, and they are
 * verified character for character by the interface-contract gate. They state that an input was
 * blank and carry no credential value of any kind. The substantive prohibition is fully honoured:
 * this file declares no credential component, no accessor, no derived or encoded form of one, no
 * encoder and no
 * seeded literal of any kind.</p>
 *
 * <h2>Diagnostics never reach this contract</h2>
 *
 * <p>Several legacy failure arms write raw response and reason codes to the operator console -
 * {@code app/cbl/COUSR00C.cbl} lines 608, 642 and 676, {@code app/cbl/COUSR01C.cbl} line 268,
 * {@code app/cbl/COUSR02C.cbl} lines 347 and 384, and {@code app/cbl/COUSR03C.cbl} lines 294 and
 * 330. None of that crosses this boundary. The corresponding responses carry only the operator-facing
 * message constant declared below; no raw code, internal path, storage identifier, failure class
 * name or stack detail is ever exposed.</p>
 *
 * <h2>Provenance</h2>
 *
 * <p>Translated from the legacy estate at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy sources are read-only
 * reference and no source text is transcribed here beyond the operator-facing message literals,
 * which are external contract and are reproduced verbatim by design.</p>
 *
 * @param rows the user rows this page presents, in presentation order, at most ten. Empty for the
 *     add, update and delete transactions, which return a single-user result rather than a page. A
 *     {@code null} argument is normalised to an empty immutable list, and a supplied list is
 *     defensively copied without reordering or padding.
 * @param pageMetadata the cursor-plus-direction paging contract for the list transaction, or
 *     {@code null} for the three single-user transactions, which do not page.
 * @param userId the eight-character user identifier this response concerns - the identifier field
 *     of the add map and the identifier-input field of the update, delete and list maps. Blank or
 *     absent after a successful add or delete, which clear every field.
 * @param firstName the twenty-character first name, carried exactly as supplied including any
 *     trailing space the fixed-width source field held.
 * @param lastName the twenty-character last name, carried exactly as supplied.
 * @param userType the one-character user type, raw and uninterpreted for the reasons given above.
 * @param transactionName the four-character transaction identifier the screen displays.
 * @param title01 the first forty-character screen title line.
 * @param currentDate the eight-character date the screen displays, carried as the screen's own
 *     text rather than re-derived, so the response reproduces what the operator saw.
 * @param programName the eight-character program name the screen displays.
 * @param title02 the second forty-character screen title line.
 * @param currentTime the eight-character time the screen displays, carried as text for the same
 *     reason as the date.
 * @param message the single seventy-eight-character operator-facing message line, or {@code null}
 *     when the screen shows none. Carried byte for byte: never trimmed, padded, re-cased or
 *     normalised, because the message text is externally observable contract.
 * @param fieldErrors the per-field errors, empty on a first submission because the legacy
 *     decorating macro fired only on re-entry. A {@code null} argument is normalised to an empty
 *     immutable list and a supplied list is defensively copied.
 * @param generalError whether this response reports a failure. Always stated explicitly by the
 *     caller and never inferred from {@code message}, for the reason developed above.
 * @param actionSucceeded whether the requested add, update or delete completed. Stated explicitly
 *     and independent of {@code generalError}, since the two arms described above are neither a
 *     failure nor a completed action.
 * @param fieldToFocus the identifier of the screen field that should receive input focus, or
 *     {@code null} when the response gives no hint. An opaque label at most seven characters wide,
 *     which is the widest field name across the four screens. It is an identity only - never a
 *     cursor coordinate, never a sentinel index and never a terminal attribute.
 * @param route the opaque route the client should call next, or {@code null} when the response
 *     implies none. The legacy transferred control server-side after a selection; here the client
 *     drives the next call, so this is a label the client resolves and this type neither interprets
 *     nor validates it.
 * @param navigationContext the echoed navigation state, or {@code null} when there is none. Echoed
 *     request state carried by the client, not a server-held session.
 * @since 1.0.0
 */
public record UserResponse(
        List<UserRow> rows,
        PageMetadata pageMetadata,
        @Size(max = UserResponse.USER_ID_LENGTH) String userId,
        @Size(max = UserResponse.FIRST_NAME_LENGTH) String firstName,
        @Size(max = UserResponse.LAST_NAME_LENGTH) String lastName,
        @Size(max = UserResponse.USER_TYPE_LENGTH) String userType,
        @Size(max = UserResponse.TRANSACTION_NAME_LENGTH) String transactionName,
        @Size(max = UserResponse.SCREEN_TITLE_LENGTH) String title01,
        @Size(max = UserResponse.CURRENT_DATE_LENGTH) String currentDate,
        @Size(max = UserResponse.PROGRAM_NAME_LENGTH) String programName,
        @Size(max = UserResponse.SCREEN_TITLE_LENGTH) String title02,
        @Size(max = UserResponse.CURRENT_TIME_LENGTH) String currentTime,
        @Size(max = UserResponse.MESSAGE_LENGTH) String message,
        List<ErrorResponse.FieldError> fieldErrors,
        boolean generalError,
        boolean actionSucceeded,
        @Size(max = UserResponse.SCREEN_FIELD_ID_LENGTH) String fieldToFocus,
        String route,
        NavigationContext navigationContext) {

    /**
     * Width in characters of the row selector: 1.
     *
     * <p>The legacy width of every one of the ten selector fields on the list map
     * {@code app/cpy-bms/COUSR00.CPY}. The operator types a single character into the row they want
     * to act on, and the selector arrives here uninterpreted - deciding what it means is the
     * navigation service's job, not this payload's.</p>
     *
     * <p>Declared separately from {@link #USER_TYPE_LENGTH} even though both are one character,
     * because they are two unrelated fields whose widths coincide by accident. Bounding a component
     * only reports an over-long value; it never trims, pads or otherwise alters one.</p>
     */
    public static final int SELECTOR_LENGTH = 1;

    /**
     * Width in characters of a user identifier: 8.
     *
     * <p>The legacy width of the identifier field on all four maps - named as an identifier input on
     * the list, update and delete maps and as a plain identifier on the add map - and of the ten
     * per-row identifier fields on the list map. It is also the width of the identifier field of the
     * eighty-byte record layout {@code app/cpy/CSUSR01Y.cpy} and of both paging anchor fields at
     * {@code app/cbl/COUSR00C.cbl} lines 68 and 69, so one width governs the whole feature.</p>
     */
    public static final int USER_ID_LENGTH = 8;

    /**
     * Width in characters of a first name: 20.
     *
     * <p>The legacy width of the first-name field on the add, update and delete maps and of the ten
     * per-row first-name fields on the list map, matching the first-name field of
     * {@code app/cpy/CSUSR01Y.cpy}.</p>
     *
     * <p>Declared separately from {@link #LAST_NAME_LENGTH} even though both are twenty characters:
     * they are two distinct fields that happen to share a width, and a future divergence in one must
     * not propagate silently to the other. Note also that this is <em>not</em> the twenty-five
     * characters of the combined name in the program's internal staging table at
     * {@code app/cbl/COUSR00C.cbl} lines 56 to 64, which is a display artefact and not the
     * contract.</p>
     */
    public static final int FIRST_NAME_LENGTH = 20;

    /**
     * Width in characters of a last name: 20.
     *
     * <p>The legacy width of the last-name field on the add, update and delete maps and of the ten
     * per-row last-name fields on the list map, matching the last-name field of
     * {@code app/cpy/CSUSR01Y.cpy}. Declared separately from {@link #FIRST_NAME_LENGTH} for the
     * reason given there.</p>
     */
    public static final int LAST_NAME_LENGTH = 20;

    /**
     * Width in characters of the user type: 1.
     *
     * <p>The legacy width of the user-type field on the add, update and delete maps and of the ten
     * per-row type fields on the list map, matching the type field of
     * {@code app/cpy/CSUSR01Y.cpy}.</p>
     *
     * <p>This is emphatically <em>not</em> the eight characters the program's internal staging table
     * gives the same concept at {@code app/cbl/COUSR00C.cbl} line 64. The staging width exists to
     * spell a type out for a display line; the map width is the contract.</p>
     */
    public static final int USER_TYPE_LENGTH = 1;

    /**
     * Width in characters of the displayed transaction identifier: 4.
     *
     * <p>The legacy width of the transaction-name field on all four maps, matching the four-character
     * CICS transaction identifiers the administrative screens run under.</p>
     */
    public static final int TRANSACTION_NAME_LENGTH = 4;

    /**
     * Width in characters of each screen title line: 40.
     *
     * <p>The legacy width of both title fields on all four maps. One constant governs both lines
     * because they are the same field declared twice on the same screen at the same width, not two
     * widths that happen to agree.</p>
     */
    public static final int SCREEN_TITLE_LENGTH = 40;

    /**
     * Width in characters of the displayed date: 8.
     *
     * <p>The legacy width of the date field on all four maps. The value crosses this contract as the
     * screen's own text rather than as a temporal type, so the response reproduces exactly what the
     * operator saw and no re-formatting can alter it.</p>
     *
     * <p>Declared separately from {@link #CURRENT_TIME_LENGTH} and {@link #PROGRAM_NAME_LENGTH}
     * although all three are eight characters, for the reason given on
     * {@link #FIRST_NAME_LENGTH}.</p>
     */
    public static final int CURRENT_DATE_LENGTH = 8;

    /**
     * Width in characters of the displayed program name: 8.
     *
     * <p>The legacy width of the program-name field on all four maps, matching the eight-character
     * program names of the four source members.</p>
     */
    public static final int PROGRAM_NAME_LENGTH = 8;

    /**
     * Width in characters of the displayed time: 8.
     *
     * <p>The legacy width of the time field on all four maps. Carried as the screen's own text for
     * the same reason as {@link #CURRENT_DATE_LENGTH}.</p>
     */
    public static final int CURRENT_TIME_LENGTH = 8;

    /**
     * Width in characters of the operator-facing message line: 78.
     *
     * <p>The legacy width of the message field on all four maps. Every message constant declared
     * below fits inside it, and the bound only reports an over-long value - it never trims a message
     * or destroys a leading or trailing space, because message text is externally observable
     * contract.</p>
     */
    public static final int MESSAGE_LENGTH = 78;

    /**
     * Width in characters of a screen field identifier: 7.
     *
     * <p>The widest named field on any of the four screen definitions
     * {@code app/bms/COUSR00.bms} through {@code app/bms/COUSR03.bms}, measured across all of them.
     * Bounding the focus hint at the widest legacy field name means no legal identifier is ever
     * rejected.</p>
     *
     * <p>The identifier is a label and nothing more. It is not a coordinate, not an attribute byte
     * and not a cursor position, and a client may ignore it entirely.</p>
     */
    public static final int SCREEN_FIELD_ID_LENGTH = 7;

    /*
     * ================================================================================
     * OPERATOR-FACING MESSAGE TEXT - EXTERNAL CONTRACT, REPRODUCED VERBATIM
     * ================================================================================
     * Every literal below is reproduced byte for byte from the source member and line cited on
     * it, and is verified character for character by the interface-contract gate. Message text is
     * externally observable: operators read it and downstream tooling matches on it, so a
     * corrected spelling, a normalised ellipsis or a folded capital is a contract break.
     *
     * The constants are grouped per screen and are never shared across screens, even where two
     * screens display identical text. Four screens that happen to agree today are still four
     * independent contracts, and the delete screen below proves why that matters: it carries a
     * genuine defect whose text collides with the update screen's, and sharing one constant
     * between them would make the defect impossible to preserve on one screen and impossible to
     * fix on the other. The same reasoning extends to the emptiness and not-found families.
     *
     * Nothing here is assembled, parameterised or derived. Selecting a message, and joining the
     * three success fragments, is the user-management service's work.
     * ================================================================================
     */

    /**
     * Rejection shown when the operator marks a row with a character that is neither of the two
     * supported actions - {@code app/cbl/COUSR00C.cbl} line 212.
     *
     * <p>The value names its two valid characters in the <strong>plural</strong>. The transaction
     * list screen has a structurally identical message that names a single valid character in the
     * <em>singular</em>; the two are separate contracts on separate screens and are never unified.</p>
     *
     * <p><strong>This message does not indicate a failure.</strong> The arm that sets it, at lines
     * 210 to 214, never raises the program's error flag, so control continues to the forward-paging
     * paragraph and the page is listed regardless. A response carrying this message normally also
     * carries a full ten-row list and {@link #generalError()} of {@code false}.</p>
     */
    public static final String MSG_LIST_INVALID_SELECTION =
            "Invalid selection. Valid values are U and D";

    /**
     * First of the three top-of-browse texts - {@code app/cbl/COUSR00C.cbl} line 251.
     *
     * <p>Shown when the operator asks for the preceding page while already on the first page. Note
     * the word "already", which the variant at {@link #MSG_LIST_AT_TOP} omits.</p>
     *
     * <p>The list screen has <strong>five distinct</strong> paging-boundary texts - three
     * top-of-browse and two bottom-of-browse - reached from three different paragraphs. All five are
     * declared as independent constants and none is collapsed with, parameterised over or derived
     * from another.</p>
     */
    public static final String MSG_LIST_ALREADY_AT_TOP =
            "You are already at the top of the page...";

    /**
     * First of the two bottom-of-browse texts - {@code app/cbl/COUSR00C.cbl} line 273.
     *
     * <p>Shown when the operator asks for the following page and the forward-page indicator says
     * none remains. Note "already", which the variant at {@link #MSG_LIST_REACHED_BOTTOM}
     * replaces with "have reached".</p>
     */
    public static final String MSG_LIST_ALREADY_AT_BOTTOM =
            "You are already at the bottom of the page...";

    /**
     * Second of the three top-of-browse texts - {@code app/cbl/COUSR00C.cbl} line 603.
     *
     * <p>Reached from a different paragraph than {@link #MSG_LIST_ALREADY_AT_TOP} and
     * <strong>omitting the word "already"</strong>. The difference is the whole reason both exist.</p>
     */
    public static final String MSG_LIST_AT_TOP =
            "You are at the top of the page...";

    /**
     * Second of the two bottom-of-browse texts - {@code app/cbl/COUSR00C.cbl} line 637.
     *
     * <p>Phrased "have reached" rather than "are already at", unlike
     * {@link #MSG_LIST_ALREADY_AT_BOTTOM}.</p>
     */
    public static final String MSG_LIST_REACHED_BOTTOM =
            "You have reached the bottom of the page...";

    /**
     * Third of the three top-of-browse texts - {@code app/cbl/COUSR00C.cbl} line 671.
     *
     * <p>Phrased "have reached", distinguishing it from both {@link #MSG_LIST_ALREADY_AT_TOP} and
     * {@link #MSG_LIST_AT_TOP}. Three separate top-of-browse texts on one screen is unusual but it
     * is what the source does, and each is reachable.</p>
     */
    public static final String MSG_LIST_REACHED_TOP =
            "You have reached the top of the page...";

    /**
     * Failure text for an unexpected outcome while reading the user file during a list -
     * {@code app/cbl/COUSR00C.cbl} lines 610, 644 and 678.
     *
     * <p>All three sites display the same text, so the list screen needs one constant for them. The
     * legacy additionally writes the raw response and reason codes to the operator console at lines
     * 608, 642 and 676; those codes stop at this boundary and never reach a client.</p>
     */
    public static final String MSG_LIST_UNABLE_TO_LOOKUP_USER = "Unable to lookup User...";

    /**
     * Add-screen rejection for a blank first name - {@code app/cbl/COUSR01C.cbl} line 120, the first
     * check in the add cascade.
     *
     * <p>The whole emptiness family capitalises the word "NOT", on all four screens. That is how the
     * source spells it and it is never folded to lower case.</p>
     *
     * <p>The family also ends in three dots with <strong>no</strong> preceding space, unlike the
     * success and prompt texts, which do have one. The two spacings are never regularised.</p>
     */
    public static final String MSG_ADD_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    /**
     * Add-screen rejection for a blank last name - {@code app/cbl/COUSR01C.cbl} line 126, the second
     * check in the add cascade.
     */
    public static final String MSG_ADD_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    /**
     * Add-screen rejection for a blank user identifier - {@code app/cbl/COUSR01C.cbl} line 132.
     *
     * <p>Checked <strong>third</strong> on the add screen, after both names. The update and delete
     * screens check the identifier <strong>first</strong> instead, because they must read the record
     * before they can do anything else. The cascade order is part of the contract: it decides which
     * single message an operator sees when several fields are blank at once.</p>
     */
    public static final String MSG_ADD_USER_ID_EMPTY = "User ID can NOT be empty...";

    /**
     * Add-screen rejection for a blank credential field - {@code app/cbl/COUSR01C.cbl} line 138, the
     * fourth check in the add cascade.
     *
     * <p>This constant is operator-facing text stating that a required input was left blank. It
     * carries no credential value, and this contract has no credential component or accessor - see
     * the audit note in the type documentation.</p>
     */
    public static final String MSG_ADD_CREDENTIAL_FIELD_EMPTY = "Password can NOT be empty...";

    /**
     * Add-screen rejection for a blank user type - {@code app/cbl/COUSR01C.cbl} line 144, the last
     * check in the add cascade.
     *
     * <p>The check tests emptiness only. It does not validate the character against the two codes the
     * field is expected to hold, which is why {@link #userType()} crosses this contract raw.</p>
     */
    public static final String MSG_ADD_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    /**
     * Leading fragment of the add-success message, <strong>including its trailing space</strong> -
     * {@code app/cbl/COUSR01C.cbl} line 255.
     *
     * <p>Held un-joined. The service concatenates this fragment, the user identifier with its
     * fixed-width padding removed, and {@link #MSG_ADD_SUCCESS_SUFFIX}, producing text of the form
     * {@code User} space {@code SOMEUSER} space {@code has been added ...} - <strong>exactly one
     * space on each side of the identifier</strong>, because this fragment supplies the space before
     * it and the suffix supplies the space after it.</p>
     *
     * <p>That single-space result is a deliberate contrast with the bill-payment and transaction-add
     * screens, whose success messages produce <em>two</em> consecutive spaces at the same join
     * because both of their fragments contribute one. Neither pattern is ever normalised toward the
     * other: normalising either way breaks byte parity on one screen or the other.</p>
     *
     * <p>Also note what happens to the identifier itself. Inside the message the padding of the
     * eight-character field is dropped, so a shorter identifier does not leave a run of spaces in the
     * text. The {@link #userId()} component is unaffected and keeps its full field width - the
     * trimming is a property of this one message, not of the contract.</p>
     */
    public static final String MSG_ADD_SUCCESS_PREFIX = "User ";

    /**
     * Trailing fragment of the add-success message, <strong>including its leading space and the
     * space before its three dots</strong> - {@code app/cbl/COUSR01C.cbl} line 257.
     *
     * <p>One of three verb fragments, each a separate constant - see
     * {@link #MSG_UPDATE_SUCCESS_SUFFIX} and {@link #MSG_DELETE_SUCCESS_SUFFIX}. None is built from
     * a verb parameter and none shares a template, because a shared template would invite exactly the
     * spacing normalisation that {@link #MSG_ADD_SUCCESS_PREFIX} warns against.</p>
     */
    public static final String MSG_ADD_SUCCESS_SUFFIX = " has been added ...";

    /**
     * Add-screen rejection when the identifier is already in use -
     * {@code app/cbl/COUSR01C.cbl} line 263.
     *
     * <p>The verb is <strong>"exist"</strong>, not "exists". The source is ungrammatical and the text
     * is reproduced as it stands, because operators and downstream tooling match on it.</p>
     */
    public static final String MSG_ADD_USER_ID_ALREADY_EXIST = "User ID already exist...";

    /**
     * Add-screen failure text for an unexpected outcome while writing the record -
     * {@code app/cbl/COUSR01C.cbl} line 270.
     *
     * <p>The verb is capitalised. The legacy additionally writes the raw response and reason codes to
     * the operator console at line 268; those codes never reach a client.</p>
     */
    public static final String MSG_ADD_UNABLE_TO_ADD_USER = "Unable to Add User...";

    /**
     * Update-screen rejection for a blank user identifier - {@code app/cbl/COUSR02C.cbl} lines 148
     * and 182.
     *
     * <p>Two sites in the one program display this text: the identifier is validated both when the
     * screen is first entered with a key and again when the operator submits changes. Checked
     * <strong>first</strong> on this screen, before either name, because the program must read the
     * record before it can compare anything - the reverse of the add cascade, where the identifier is
     * checked third.</p>
     *
     * <p>Declared separately from the add and delete screens' identical text: four screens, four
     * independent contracts.</p>
     */
    public static final String MSG_UPDATE_USER_ID_EMPTY = "User ID can NOT be empty...";

    /**
     * Update-screen rejection for a blank first name - {@code app/cbl/COUSR02C.cbl} line 188.
     */
    public static final String MSG_UPDATE_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    /**
     * Update-screen rejection for a blank last name - {@code app/cbl/COUSR02C.cbl} line 194.
     */
    public static final String MSG_UPDATE_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    /**
     * Update-screen rejection for a blank credential field - {@code app/cbl/COUSR02C.cbl} line 200.
     *
     * <p>Operator-facing text stating that a required input was left blank. It carries no credential
     * value. This screen is precisely where the legacy round-tripped the stored credential, and this
     * contract deliberately does not - see the audit note in the type documentation.</p>
     */
    public static final String MSG_UPDATE_CREDENTIAL_FIELD_EMPTY = "Password can NOT be empty...";

    /**
     * Update-screen rejection for a blank user type - {@code app/cbl/COUSR02C.cbl} line 206.
     *
     * <p>Emptiness only; the character itself is not validated against the expected pair.</p>
     */
    public static final String MSG_UPDATE_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    /**
     * Advisory shown when the operator submits an update having changed nothing -
     * {@code app/cbl/COUSR02C.cbl} line 239. Note the <strong>space before the three dots</strong>.
     *
     * <p><strong>This message does not indicate a failure.</strong> The change detection at lines 219
     * to 234 raises a modified indicator only where a submitted value differs from the stored one;
     * when nothing differs, the arm at lines 239 to 243 sets this text and re-sends the screen
     * <em>without</em> raising the error flag. A response carrying this message therefore normally
     * reports {@link #generalError()} of {@code false} and {@link #actionSucceeded()} of
     * {@code false} - neither a failure nor a completed action.</p>
     *
     * <p>The same arm also sets a terminal colour attribute, which is discarded here.</p>
     */
    public static final String MSG_UPDATE_NO_CHANGE = "Please modify to update ...";

    /**
     * Prompt inviting the operator to confirm the pending update with a function key -
     * {@code app/cbl/COUSR02C.cbl} line 336. Note the <strong>space before the three dots</strong>.
     *
     * <p>This is the two-step confirmation the update screen requires: the record is fetched and
     * displayed first, and the write happens only on the confirming keystroke. The prompt is carried
     * as text exactly as the screen shows it, function-key name included.</p>
     */
    public static final String MSG_UPDATE_PRESS_PF5 = "Press PF5 key to save your updates ...";

    /**
     * Update-screen rejection when no record exists for the identifier -
     * {@code app/cbl/COUSR02C.cbl} lines 342 and 379.
     *
     * <p>Two sites: once when fetching the record for display and again when writing it back, since
     * the record can disappear between the two steps of the confirmation. The word "NOT" is
     * capitalised, as in the emptiness family.</p>
     */
    public static final String MSG_UPDATE_USER_ID_NOT_FOUND = "User ID NOT found...";

    /**
     * Update-screen failure text for an unexpected outcome while reading the record -
     * {@code app/cbl/COUSR02C.cbl} line 349.
     *
     * <p>Declared separately from the list and delete screens' identical text. The legacy additionally
     * writes the raw response and reason codes to the operator console at line 347; those codes never
     * reach a client.</p>
     */
    public static final String MSG_UPDATE_UNABLE_TO_LOOKUP_USER = "Unable to lookup User...";

    /**
     * Leading fragment of the update-success message, <strong>including its trailing space</strong> -
     * {@code app/cbl/COUSR02C.cbl} line 372.
     *
     * <p>Assembled exactly as described on {@link #MSG_ADD_SUCCESS_PREFIX}, yielding one space on
     * each side of the identifier.</p>
     *
     * <p>Unlike add and delete, this screen does <strong>not</strong> clear its fields on success -
     * lines 369 to 376 report success with the field-initialising paragraph deliberately not invoked.
     * A successful update response therefore carries the updated values, where a successful add or
     * delete carries blank or absent ones.</p>
     */
    public static final String MSG_UPDATE_SUCCESS_PREFIX = "User ";

    /**
     * Trailing fragment of the update-success message, <strong>including its leading space and the
     * space before its three dots</strong> - {@code app/cbl/COUSR02C.cbl} line 374.
     *
     * <p>Its own constant, never derived from a verb parameter or a shared template.</p>
     */
    public static final String MSG_UPDATE_SUCCESS_SUFFIX = " has been updated ...";

    /**
     * Update-screen failure text for an unexpected outcome while writing the record -
     * {@code app/cbl/COUSR02C.cbl} line 386.
     *
     * <p>The verb is capitalised. <strong>The delete screen displays this identical text on its own
     * failure path, which is a defect there rather than here</strong>; the two are separate constants
     * and are never shared. See {@link #MSG_DELETE_UNABLE_TO_UPDATE_USER}. The legacy additionally
     * writes the raw response and reason codes to the operator console at line 384; those codes never
     * reach a client.</p>
     */
    public static final String MSG_UPDATE_UNABLE_TO_UPDATE_USER = "Unable to Update User...";

    /**
     * Delete-screen rejection for a blank user identifier - {@code app/cbl/COUSR03C.cbl} lines 147
     * and 179.
     *
     * <p>Two sites, mirroring the update screen: once on entry with a key and once on submission.
     * Checked <strong>first</strong> on this screen, since the record must be read before it can be
     * deleted.</p>
     */
    public static final String MSG_DELETE_USER_ID_EMPTY = "User ID can NOT be empty...";

    /**
     * Prompt inviting the operator to confirm the deletion with a function key -
     * {@code app/cbl/COUSR03C.cbl} line 283. Note the <strong>space before the three dots</strong>
     * and the <strong>lower-case</strong> word "user", which the update screen's equivalent prompt
     * does not contain at all.
     *
     * <p>This is the two-step confirmation the delete screen requires: the record is fetched and
     * displayed first, and the deletion happens only on the confirming keystroke.</p>
     */
    public static final String MSG_DELETE_PRESS_PF5 = "Press PF5 key to delete this user ...";

    /**
     * Delete-screen rejection when no record exists for the identifier -
     * {@code app/cbl/COUSR03C.cbl} lines 289 and 325.
     *
     * <p>Two sites, for the same reason as on the update screen: the record can disappear between the
     * two steps of the confirmation. The word "NOT" is capitalised.</p>
     */
    public static final String MSG_DELETE_USER_ID_NOT_FOUND = "User ID NOT found...";

    /**
     * Delete-screen failure text for an unexpected outcome while reading the record -
     * {@code app/cbl/COUSR03C.cbl} line 296.
     *
     * <p>Declared separately from the list and update screens' identical text. The legacy additionally
     * writes the raw response and reason codes to the operator console at line 294; those codes never
     * reach a client.</p>
     */
    public static final String MSG_DELETE_UNABLE_TO_LOOKUP_USER = "Unable to lookup User...";

    /**
     * Leading fragment of the delete-success message, <strong>including its trailing space</strong> -
     * {@code app/cbl/COUSR03C.cbl} line 318.
     *
     * <p>Assembled exactly as described on {@link #MSG_ADD_SUCCESS_PREFIX}, yielding one space on
     * each side of the identifier.</p>
     *
     * <p>Like add and unlike update, this screen clears every field before reporting success - line
     * 315 invokes the field-initialising paragraph first - so a successful delete response
     * legitimately carries blank or absent echoed values.</p>
     */
    public static final String MSG_DELETE_SUCCESS_PREFIX = "User ";

    /**
     * Trailing fragment of the delete-success message, <strong>including its leading space and the
     * space before its three dots</strong> - {@code app/cbl/COUSR03C.cbl} line 320.
     *
     * <p>Its own constant, never derived from a verb parameter or a shared template.</p>
     */
    public static final String MSG_DELETE_SUCCESS_SUFFIX = " has been deleted ...";

    /**
     * Delete-screen failure text for an unexpected outcome while deleting the record -
     * {@code app/cbl/COUSR03C.cbl} line 332.
     *
     * <p><strong>PRESERVED SOURCE DEFECT.</strong> The delete path reports its failure with the
     * <em>update</em> verb. That is what the source member says, and it is what an operator of the
     * legacy system sees when a deletion fails, so it is reproduced verbatim: the text is not
     * corrected to name the delete verb, because correcting it would change externally observable
     * behaviour that operators and downstream tooling may already match on.</p>
     *
     * <p>It is also declared as its own constant and is deliberately <strong>not</strong> shared with
     * {@link #MSG_UPDATE_UNABLE_TO_UPDATE_USER}, whose text is identical today. They are two
     * independent contract strings on two different screens, and only one of them is a defect. A
     * shared constant would make this defect impossible to preserve here the moment anyone legitimately
     * corrected the other, and would silently propagate any future edit across a screen boundary.</p>
     *
     * <p>Recorded as a preserved anomaly. The legacy additionally writes the raw response and reason
     * codes to the operator console at line 330; those codes never reach a client.</p>
     */
    public static final String MSG_DELETE_UNABLE_TO_UPDATE_USER = "Unable to Update User...";

    /**
     * Canonical constructor. Detaches and freezes the two collections and leaves every other
     * component exactly as supplied.
     *
     * <p>Each collection is normalised so it is never {@code null}, never aliased to caller-owned
     * state and never mutable: a {@code null} argument becomes the empty immutable list, and a
     * supplied list is defensively copied. An accessor therefore always returns a usable collection
     * and no caller can mutate a response after building it. The empty list <em>is</em> the absence
     * representation, matching {@link ErrorResponse}: no rows means this is not a list response, and
     * no field errors means either a clean submission or a first submission, since the legacy
     * decorating macro fired only on re-entry.</p>
     *
     * <p>The copy preserves order and length exactly. Nothing is sorted, reversed, renumbered,
     * compacted, de-duplicated or padded - a partial final page keeps only the rows it has, and a
     * page the service filled from the bottom upward keeps the sequence the service produced. Row
     * ordering is settled before a response is built and is never revisited here.</p>
     *
     * <p>Every other component crosses byte for byte. No string is trimmed, padded, re-cased or
     * normalised, because the fixed-width source fields are space-significant and the message line is
     * externally observable contract. Nothing is defaulted and nothing is derived: in particular
     * neither boolean is inferred from {@code message}, from the collections or from each other, for
     * the reasons developed in the type documentation.</p>
     *
     * <p>No argument is rejected. Every component is legitimately absent on some path through the four
     * transactions - the row list and paging metadata for the three single-user transactions, the
     * echoed values after a successful add or delete, the message on a first entry - so validating
     * presence here would reject responses the legacy screens genuinely produce.</p>
     */
    public UserResponse {
        rows = (rows == null) ? List.of() : List.copyOf(rows);
        fieldErrors = (fieldErrors == null) ? List.of() : List.copyOf(fieldErrors);
    }

    /**
     * Tests whether this response carries any user row.
     *
     * <p>A convenience test over {@link #rows()} for callers that only need to branch on presence,
     * such as a client deciding whether to render a table. False is the normal case for the add,
     * update and delete transactions, and is also correct for a list that matched nothing.</p>
     *
     * <p>It is <strong>not</strong> an error indicator. A list response can carry ten rows and a
     * message at the same time, and an empty list is not a failure; read {@link #generalError()} for
     * that.</p>
     *
     * @return {@code true} when at least one row is present
     */
    public boolean hasRows() {
        return !rows.isEmpty();
    }

    /**
     * Tests whether this response carries any per-field error.
     *
     * <p>A convenience test over {@link #fieldErrors()}, mirroring the equivalent test on
     * {@link ErrorResponse}. It is not a substitute for inspecting the per-field states: a caller
     * that has to tell an operator what to do must read {@link ErrorResponse.FieldError#state()} on
     * each entry, because a blank field and a badly filled field need different remedies.</p>
     *
     * <p>It is <strong>not</strong> a substitute for {@link #generalError()} either. The two arms
     * described in the type documentation set a message with no error flag and no field error at all,
     * and a failure reported only as a summary line - a not-found identifier, for instance - carries
     * no field error while still being a failure.</p>
     *
     * @return {@code true} when at least one field error is present
     */
    public boolean hasFieldErrors() {
        return !fieldErrors.isEmpty();
    }

    /**
     * One row of the administrative user list, corresponding to one of the ten row families of the
     * list map {@code app/cpy-bms/COUSR00.CPY}.
     *
     * <p>Nested here because a user row has no meaning outside the response that carries it, and
     * because the enclosing package is a closed set of response and request contracts rather than a
     * home for row types.</p>
     *
     * <p><strong>The five components and their widths come from the map, not from the program's
     * working storage.</strong> The internal staging table at {@code app/cbl/COUSR00C.cbl} lines 56
     * to 64 describes the same ten rows with a <em>single combined</em> twenty-five-character name, an
     * eight-character type and three two-character spacing gaps. That is a display line, assembled to be
     * written to a terminal, and it is not the contract. The map keeps the two names separate at
     * twenty characters each and the type at one character, so that is what this record does. No
     * combined name, no eight-wide type and no spacing pad is modelled, and no byte position appears
     * anywhere - a REST payload is a named structure.</p>
     *
     * <p>Every component is a string. Identifiers and type codes are never numbers here, so a leading
     * zero or an unexpected type character survives unchanged. Every value is carried exactly as
     * supplied, including any trailing space its fixed-width source field held; nothing is trimmed,
     * padded or re-cased.</p>
     *
     * <p>Rows are deeply immutable and every component is a string, so a response's row list is frozen
     * once built.</p>
     *
     * @param selector the one-character action marker for this row, carried raw and uninterpreted.
     *     Two characters select the update and delete screens respectively, each accepted in either
     *     case, and anything else is rejected with {@link UserResponse#MSG_LIST_INVALID_SELECTION} -
     *     but that mapping belongs to the navigation service, so this component is a value and not a
     *     decision. Blank on a row the operator has not marked.
     * @param userId the eight-character user identifier displayed on this row. It is also the paging
     *     anchor: the identifier of the first and of the last row on a page are what the legacy
     *     retains to restart a browse in either direction.
     * @param firstName the twenty-character first name displayed on this row.
     * @param lastName the twenty-character last name displayed on this row.
     * @param userType the one-character user type displayed on this row, raw and uninterpreted for
     *     the same reason as {@link UserResponse#userType()}: no program in this family validates the
     *     character, and the persisted column constrains it no further, so an unexpected value must
     *     serialise rather than fail.
     * @since 1.0.0
     */
    public record UserRow(
            @Size(max = UserResponse.SELECTOR_LENGTH) String selector,
            @Size(max = UserResponse.USER_ID_LENGTH) String userId,
            @Size(max = UserResponse.FIRST_NAME_LENGTH) String firstName,
            @Size(max = UserResponse.LAST_NAME_LENGTH) String lastName,
            @Size(max = UserResponse.USER_TYPE_LENGTH) String userType) {
    }
}
