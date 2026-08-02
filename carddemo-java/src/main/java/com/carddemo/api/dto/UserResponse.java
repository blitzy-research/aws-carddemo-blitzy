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

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Immutable response contract shared by the four administrative user transactions, reproducing the
 * field-level and message-level contract of their four legacy screens as one REST payload. One type
 * serves all four because all four screens are views of the same eighty-byte user-security record
 * ({@code app/cpy/CSUSR01Y.cpy}) and differ only in which subset they present: list
 * ({@code app/cbl/COUSR00C.cbl}, the only one that returns rows), add
 * ({@code app/cbl/COUSR01C.cbl}), update ({@code app/cbl/COUSR02C.cbl}) and delete
 * ({@code app/cbl/COUSR03C.cbl}). Authorisation is not modelled here; the route-to-role table
 * belongs to the security configuration.
 *
 * <p><strong>The screen map is the contract, not the program's working storage.</strong> The list
 * program stages its display line with a single combined twenty-five-character name, an eight-wide
 * type and spacing gaps. The map keeps the two names separate at twenty characters each and the type
 * at one, so {@link UserRow} follows the map and models no combined name, no eight-wide type and no
 * spacing pad. Recorded as a translation divergence: the staging table is a terminal display
 * artefact with no counterpart in a REST payload, and no byte position appears anywhere here.
 *
 * <p><strong>{@link #generalError()} is explicit and is never derived from the presence of
 * {@link #message()}</strong>, because two legacy arms set a message and continue on the success
 * path: the invalid-selection arm of the list screen sets its message, repositions focus and lists
 * the page anyway, and the no-change arm of the update screen re-sends the screen without raising
 * the error flag. A response therefore legitimately carries a message together with a full page and
 * {@code generalError} of {@code false}. Both arms also set a terminal colour attribute, which is
 * discarded: no colour, attribute byte or marker character crosses this contract.
 *
 * <p><strong>Success-path field clearing is asymmetric, so no echoed component is ever
 * mandatory.</strong> Add and delete clear every input field before reporting success; update does
 * not, and reports success with the fields left populated. The two behaviours are deliberately not
 * unified.
 *
 * <p><strong>The user type crosses this boundary as raw text, not as the domain enumeration.</strong>
 * No program in this family checks the value against the two codes it is expected to hold - the add
 * and update screens test only for emptiness - and the persisted column carries no check
 * constraint, so an unexpected code exists and must serialise rather than fail. Interpreting it is
 * the user-management service's work, through a non-throwing lookup. Identifiers and the displayed
 * page indicator are likewise text and never numbers, because a numeric type would discard
 * contractual leading zeros.
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
 * <p><strong>The two authoritative codes are {@code A} and {@code U}, and this response emits that
 * vocabulary and no other.</strong> {@code CDEMO-USRTYP-ADMIN} carries {@code A} at line 27 of
 * {@code app/cpy/COCOM01Y.cpy} and {@code CDEMO-USRTYP-USER} carries {@code U} at line 28, matching
 * {@code SEC-USR-TYPE} at line 22 of {@code app/cpy/CSUSR01Y.cpy}. Both this type's top-level code
 * and every row's code are published with that vocabulary stated. Width tolerance is not a competing
 * vocabulary: it is the absence of enforcement described above, and it exists so that a character
 * already present in a migrated record can still be read back rather than making the row
 * unserialisable.</p>
 *
 * <p><strong>The same vocabulary spans the whole package.</strong> The sign-on response, the
 * navigation context that travels between turns and inside a signed token claim, the
 * user-administration request and this response all carry the identical raw one-character code. No
 * type in this package emits the enumeration's Java constant names and none emits a spelled-out role
 * word, so a client learns the two codes once and applies them everywhere. Resolving a code to a
 * typed role - and tolerating one that resolves to nothing - happens behind a single adapter, the
 * non-throwing lookup on {@code com.carddemo.domain.enums.UserType}, and never inside a transport
 * type.</p>
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
 * {@link ErrorResponse.FieldState} distinguishes the two operator mistakes the legacy screen
 * distinguished: the decorating macro highlighted a field for both states but overwrote it with a
 * marker character only when it was blank. Neither the highlight nor the marker is exposed, only the
 * state each signified, and the macro fired only on re-entry, so field errors are absent on a first
 * submission. The textually identical enumeration in the exception layer is deliberately not used,
 * because a payload depending on the exception layer would invert the module's dependency direction;
 * translating between the two is the global exception handler's work.
 *
 * <p><strong>This response never carries a credential.</strong> The add and update screens both
 * carry a credential field on their output side and the legacy genuinely round-trips it; this
 * contract deliberately breaks that round-trip, declaring no credential component, no accessor and
 * no masked, truncated, encoded or "is set" form, each of which would leak information about a
 * secret. Recorded as the documented parity exception - the one place where the migration knowingly
 * declines to reproduce an observable legacy behaviour. The two message constants that name the
 * field state only that an input was blank and carry no credential value.
 *
 * <p>Several legacy failure arms write raw response and reason codes to the operator console. None
 * of that crosses this boundary: a response carries only the operator-facing message constant, never
 * a raw code, internal path, storage identifier, failure class name or stack detail.
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
 * <p>What this contract does carry, on both the single-record surface and every row, is a personal
 * name. Names are regulated data, so both this type and {@link UserRow} override {@code toString()} to
 * withhold them, and the outer rendering withholds the whole row list rather than delegating to the
 * rows. Only the stringified form is affected: the accessors and the serialized payload carry every
 * name in full, which is what the four screens displayed.</p>
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
 *     The two authoritative codes are {@code A} for an administrator and {@code U} for a standard
 *     user - the same single vocabulary every other type in this package carries on the wire.
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
 * @param focusScreenFieldId the identifier of the screen field that should receive input focus, or
 *     {@code null} when the response gives no hint. An opaque label at most seven characters wide,
 *     which is the widest field name across the four screens. It is an identity only - never a
 *     cursor coordinate, never a sentinel index and never a terminal attribute. Named and bounded
 *     exactly as every other focus hint in this package, so a client reads one spelling everywhere.
 * @param nextRoute the opaque route the client should call next, or {@code null} when the response
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
        @Schema(
                description =
                        "Raw one-character user-type code. The two authoritative codes are A for an"
                                + " administrator, from CDEMO-USRTYP-ADMIN at app/cpy/COCOM01Y.cpy"
                                + " line 27, and U for a standard user, from CDEMO-USRTYP-USER at"
                                + " line 28; the same two codes travel on every other user-type"
                                + " field in this API. Bounded by width rather than by value,"
                                + " because no program in this family tests the character and the"
                                + " persisted column constrains it no further.")
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
        @Size(max = UserResponse.SCREEN_FIELD_ID_LENGTH) String focusScreenFieldId,
        String nextRoute,
        NavigationContext navigationContext) {

    public static final int SELECTOR_LENGTH = 1;

    /**
     * Number of user rows the list screen presents: 10.
     *
     * <p>The list map {@code app/cpy-bms/COUSR00.CPY} declares exactly ten row families, running from
     * line 72 to line 342, and the program's internal staging table declares the matching
     * {@code 02 USER-REC OCCURS 10 TIMES} at line 57 of {@code app/cbl/COUSR00C.cbl}. The two agree,
     * so ten is the screen's row count and not an arbitrary page size.</p>
     *
     * <p>Applied as an <em>upper</em> bound on {@link #rows()} by the canonical constructor. It is a
     * capacity, not a required length: the legacy screen renders a partial final page, a search that
     * matched fewer users, and no rows at all on the three single-user transactions, so only an
     * over-long collection is a defect.</p>
     *
     * <p>This is a row count and emphatically not a width. It is unrelated to every
     * {@code *_LENGTH} constant on this type, and it is unrelated to the width of the page number the
     * request echoes - that value is eight characters wide and counts pages, not rows. It is also
     * specific to this screen: another list screen in the estate presents a different number of rows,
     * and the two are never reconciled.</p>
     */
    public static final int ROW_COUNT = 10;

    /**
     * Fixed text substituted for every withheld value in {@link #toString()}.
     *
     * <p>A constant rather than a computed mask, so a rendering never varies with the value it hides.
     * A mask derived from the value - its length, its first character, a hash - would leak exactly the
     * attribute the redaction exists to remove, and a variable-length mask would let a reader infer a
     * field's width from the rendering.</p>
     *
     * <p>Private because it is a diagnostic detail of this type rather than part of the wire contract.
     * It never appears in a serialized payload: {@link #toString()} is not a serialization path, and
     * no component ever holds this text.</p>
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

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

    public static final int FIRST_NAME_LENGTH = 20;

    public static final int LAST_NAME_LENGTH = 20;

    public static final int USER_TYPE_LENGTH = 1;

    public static final int TRANSACTION_NAME_LENGTH = 4;

    public static final int SCREEN_TITLE_LENGTH = 40;

    public static final int CURRENT_DATE_LENGTH = 8;

    public static final int PROGRAM_NAME_LENGTH = 8;

    public static final int CURRENT_TIME_LENGTH = 8;

    public static final int MESSAGE_LENGTH = 78;

    /**
     * Width of the focus hint: the widest named field across the four screen definitions, so no legal
     * field identifier is ever rejected. The identifier is a label only - never a coordinate, never an
     * attribute byte, never a cursor position - and a client may ignore it.
     */
    public static final int SCREEN_FIELD_ID_LENGTH = 7;

    /*
     * Operator-facing message text: external contract, reproduced byte for byte from the source
     * member cited on each constant, because operators read it and downstream tooling matches on it -
     * a corrected spelling, a normalised ellipsis or a folded capital is a contract break. Constants
     * are declared per screen and never shared, even where two screens carry the same text: one such
     * collision is a preserved source defect, and a shared constant would make that defect impossible
     * to preserve on one screen and impossible to correct on the other. Nothing here is assembled,
     * parameterised or derived; selecting a message, and joining the success fragments, is the
     * user-management service's work.
     */

    /**
     * Rejection shown when a row is marked with a character that is neither supported action -
     * {@code app/cbl/COUSR00C.cbl} line 212. Names its two valid characters in the plural, unlike the
     * structurally similar singular text on the transaction-list screen; the two are never unified.
     *
     * <p>This message does not indicate a failure: the arm that sets it never raises the error flag,
     * so the page is listed regardless and the response normally also carries a full page and
     * {@link #generalError()} of {@code false}.
     */
    public static final String MSG_LIST_INVALID_SELECTION =
            "Invalid selection. Valid values are U and D";

    /**
     * First of the three top-of-browse texts - {@code app/cbl/COUSR00C.cbl} line 251. Shown when the
     * preceding page is requested from the first page. Includes the word "already", which
     * {@link #MSG_LIST_AT_TOP} omits; the list screen has five distinct paging-boundary texts reached
     * from three paragraphs, and none is collapsed with, parameterised over or derived from another.
     */
    public static final String MSG_LIST_ALREADY_AT_TOP =
            "You are already at the top of the page...";

    /** First of the two bottom-of-browse texts - {@code app/cbl/COUSR00C.cbl} line 273. */
    public static final String MSG_LIST_ALREADY_AT_BOTTOM =
            "You are already at the bottom of the page...";

    /** Second of the three top-of-browse texts - {@code app/cbl/COUSR00C.cbl} line 603. */
    public static final String MSG_LIST_AT_TOP =
            "You are at the top of the page...";

    /** Second of the two bottom-of-browse texts - {@code app/cbl/COUSR00C.cbl} line 637. */
    public static final String MSG_LIST_REACHED_BOTTOM =
            "You have reached the bottom of the page...";

    /** Third of the three top-of-browse texts - {@code app/cbl/COUSR00C.cbl} line 671. */
    public static final String MSG_LIST_REACHED_TOP =
            "You have reached the top of the page...";

    /**
     * Failure text for an unexpected outcome while reading the user file during a list -
     * {@code app/cbl/COUSR00C.cbl} lines 610, 644 and 678, which display the same text. The raw
     * response and reason codes the legacy also writes to the console stop at this boundary.
     */
    public static final String MSG_LIST_UNABLE_TO_LOOKUP_USER = "Unable to lookup User...";

    /**
     * Add-screen rejection for a blank first name - {@code app/cbl/COUSR01C.cbl} line 120, the first
     * check in the add cascade. The whole emptiness family capitalises "NOT" on all four screens and
     * ends in three dots with <strong>no</strong> preceding space, unlike the success and prompt
     * texts, which have one. Neither spelling nor either spacing is ever regularised.
     */
    public static final String MSG_ADD_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    /**
     * Add-screen rejection for a blank last name - {@code app/cbl/COUSR01C.cbl} line 126, the second
     * check in the add cascade.
     */
    public static final String MSG_ADD_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    /** Add-screen rejection for a blank user identifier - {@code app/cbl/COUSR01C.cbl} line 132. */
    public static final String MSG_ADD_USER_ID_EMPTY = "User ID can NOT be empty...";

    /**
     * Add-screen rejection for a blank credential field - {@code app/cbl/COUSR01C.cbl} line 138, the
     * fourth check in the add cascade. States only that the input was blank.
     */
    public static final String MSG_ADD_CREDENTIAL_FIELD_EMPTY = "Password can NOT be empty...";

    /**
     * Add-screen rejection for a blank user type - {@code app/cbl/COUSR01C.cbl} line 144, the last
     * check in the add cascade.
     */
    public static final String MSG_ADD_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    /**
     * Leading fragment of the add-success message, <strong>including its trailing space</strong> -
     * {@code app/cbl/COUSR01C.cbl} line 255. Held un-joined: the service concatenates this fragment,
     * the identifier with its fixed-width padding removed, and {@link #MSG_ADD_SUCCESS_SUFFIX},
     * yielding <strong>exactly one space on each side of the identifier</strong>. That contrasts
     * deliberately with the bill-payment and transaction-add screens, whose fragments each contribute
     * a space and so yield two; neither pattern is normalised toward the other, because either
     * normalisation breaks byte parity on one screen. The padding is dropped inside this message
     * only - {@link #userId()} keeps its full field width.
     */
    public static final String MSG_ADD_SUCCESS_PREFIX = "User ";

    /**
     * Trailing fragment of the add-success message, <strong>including its leading space and the space
     * before its three dots</strong> - {@code app/cbl/COUSR01C.cbl} line 257. One of three verb
     * fragments, each its own constant: none is built from a verb parameter and none shares a
     * template, since a shared template would invite the spacing normalisation warned against on
     * {@link #MSG_ADD_SUCCESS_PREFIX}.
     */
    public static final String MSG_ADD_SUCCESS_SUFFIX = " has been added ...";

    /**
     * Add-screen rejection when the identifier is already in use - {@code app/cbl/COUSR01C.cbl}
     * line 233.
     */
    public static final String MSG_ADD_USER_ID_ALREADY_EXIST = "User ID already exist...";

    /**
     * Add-screen failure text for an unexpected outcome while writing the record -
     * {@code app/cbl/COUSR01C.cbl} line 270.
     */
    public static final String MSG_ADD_UNABLE_TO_ADD_USER = "Unable to Add User...";

    /**
     * Update-screen rejection for a blank user identifier - {@code app/cbl/COUSR02C.cbl} lines 148
     * and 182, validated both on entry with a key and on submission. Checked <strong>first</strong> on
     * this screen, because the record must be read before anything can be compared - the reverse of
     * the add cascade, where the identifier is checked third.
     */
    public static final String MSG_UPDATE_USER_ID_EMPTY = "User ID can NOT be empty...";

    /** Update-screen rejection for a blank first name - {@code app/cbl/COUSR02C.cbl} line 188. */
    public static final String MSG_UPDATE_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    /** Update-screen rejection for a blank last name - {@code app/cbl/COUSR02C.cbl} line 194. */
    public static final String MSG_UPDATE_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    /**
     * Update-screen rejection for a blank credential field - {@code app/cbl/COUSR02C.cbl} line 200.
     * States only that the input was blank.
     */
    public static final String MSG_UPDATE_CREDENTIAL_FIELD_EMPTY = "Password can NOT be empty...";

    /** Update-screen rejection for a blank user type - {@code app/cbl/COUSR02C.cbl} line 206. */
    public static final String MSG_UPDATE_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    /**
     * Advisory shown when an update is submitted with nothing changed -
     * {@code app/cbl/COUSR02C.cbl} line 239. Note the space before the three dots.
     *
     * <p>This message does not indicate a failure: the arm re-sends the screen without raising the
     * error flag, so a response carrying it normally reports {@link #generalError()} of {@code false}
     * and {@link #actionSucceeded()} of {@code false} - neither a failure nor a completed action.
     */
    public static final String MSG_UPDATE_NO_CHANGE = "Please modify to update ...";

    /**
     * Prompt inviting confirmation of the pending update with a function key -
     * {@code app/cbl/COUSR02C.cbl} line 336. Note the space before the three dots. The update screen
     * is a two-step confirmation: the record is displayed first and written only on the confirming
     * keystroke, and the prompt is carried exactly as shown, function-key name included.
     */
    public static final String MSG_UPDATE_PRESS_PF5 = "Press PF5 key to save your updates ...";

    /**
     * Update-screen rejection when no record exists for the identifier -
     * {@code app/cbl/COUSR02C.cbl} line 345.
     */
    public static final String MSG_UPDATE_USER_ID_NOT_FOUND = "User ID NOT found...";

    /**
     * Update-screen failure text for an unexpected outcome while reading the record -
     * {@code app/cbl/COUSR02C.cbl} line 349.
     */
    public static final String MSG_UPDATE_UNABLE_TO_LOOKUP_USER = "Unable to lookup User...";

    /**
     * Leading fragment of the update-success message, <strong>including its trailing space</strong> -
     * {@code app/cbl/COUSR02C.cbl} line 372, joined exactly as described on
     * {@link #MSG_ADD_SUCCESS_PREFIX}. Unlike add and delete, this screen does <strong>not</strong>
     * clear its fields on success, so a successful update response carries the updated values.
     */
    public static final String MSG_UPDATE_SUCCESS_PREFIX = "User ";

    /**
     * Trailing fragment of the update-success message, <strong>including its leading space and the
     * space before its three dots</strong> - {@code app/cbl/COUSR02C.cbl} line 374.
     */
    public static final String MSG_UPDATE_SUCCESS_SUFFIX = " has been updated ...";

    /**
     * Update-screen failure text for an unexpected outcome while writing the record -
     * {@code app/cbl/COUSR02C.cbl} line 386.
     */
    public static final String MSG_UPDATE_UNABLE_TO_UPDATE_USER = "Unable to Update User...";

    /**
     * Delete-screen rejection for a blank user identifier - {@code app/cbl/COUSR03C.cbl} lines 147
     * and 179, mirroring the update screen: once on entry with a key and once on submission. Checked
     * <strong>first</strong>, since the record must be read before it can be deleted.
     */
    public static final String MSG_DELETE_USER_ID_EMPTY = "User ID can NOT be empty...";

    /**
     * Prompt inviting confirmation of the deletion with a function key -
     * {@code app/cbl/COUSR03C.cbl} line 267. The delete screen is a two-step confirmation and the
     * prompt is carried exactly as shown, function-key name included.
     */
    public static final String MSG_DELETE_PRESS_PF5 = "Press PF5 key to delete this user ...";

    /**
     * Delete-screen rejection when no record exists for the identifier -
     * {@code app/cbl/COUSR03C.cbl} line 292.
     */
    public static final String MSG_DELETE_USER_ID_NOT_FOUND = "User ID NOT found...";

    /**
     * Delete-screen failure text for an unexpected outcome while reading the record -
     * {@code app/cbl/COUSR03C.cbl} line 296.
     */
    public static final String MSG_DELETE_UNABLE_TO_LOOKUP_USER = "Unable to lookup User...";

    /**
     * Leading fragment of the delete-success message, <strong>including its trailing space</strong> -
     * {@code app/cbl/COUSR03C.cbl} line 318, joined exactly as described on
     * {@link #MSG_ADD_SUCCESS_PREFIX}.
     */
    public static final String MSG_DELETE_SUCCESS_PREFIX = "User ";

    /**
     * Trailing fragment of the delete-success message, <strong>including its leading space and the
     * space before its three dots</strong> - {@code app/cbl/COUSR03C.cbl} line 320.
     */
    public static final String MSG_DELETE_SUCCESS_SUFFIX = " has been deleted ...";

    /**
     * Delete-screen failure text for an unexpected outcome while deleting the record -
     * {@code app/cbl/COUSR03C.cbl} line 332.
     *
     * <p><strong>Preserved source defect.</strong> The delete path reports its failure with the
     * <em>update</em> verb. That is what the source member says and what an operator of the legacy
     * system sees when a deletion fails, so it is reproduced verbatim rather than corrected. It is
     * also its own constant, deliberately not shared with {@link #MSG_UPDATE_UNABLE_TO_UPDATE_USER},
     * whose text matches: sharing one constant would make this defect impossible to preserve here the
     * moment the other were legitimately corrected. The raw response and reason codes the legacy also
     * writes to the console never reach a client.
     */
    public static final String MSG_DELETE_UNABLE_TO_UPDATE_USER = "Unable to Update User...";

    /**
     * Canonical constructor. Detaches and freezes the two collections and leaves every other component
     * exactly as supplied: a {@code null} list becomes the empty immutable list and a supplied list is
     * defensively copied, so an accessor always returns a usable collection and no caller can mutate a
     * response after building it. The empty list <em>is</em> the absence representation.
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
     * <p>No <em>absence</em> is rejected. Every component is legitimately absent on some path through
     * the four transactions - the row list and paging metadata for the three single-user transactions,
     * the echoed values after a successful add or delete, the message on a first entry - so validating
     * presence here would reject responses the legacy screens genuinely produce.</p>
     *
     * <p><strong>The one thing it refuses.</strong> A row collection carrying more than
     * {@value #ROW_COUNT} rows. The list map declares exactly that many row families and the program's
     * staging table declares exactly that many entries, so a longer collection could not be rendered
     * by the screen this response reproduces and is a producer defect rather than a presentational
     * choice. Refusing it here converts a silently over-long page into an immediate, located failure.
     * </p>
     *
     * <p><strong>Fewer rows than the declared count remain valid, and that is deliberate.</strong>
     * A final partial page, a search that matched two users, and the three single-user transactions
     * that carry no rows at all are all legitimate. Only the upper bound is a contract; a lower bound
     * would reject pages the legacy screens genuinely produce. A short page therefore stays short, and
     * nothing here pads it.</p>
     */
    public UserResponse {
        rows = (rows == null) ? List.of() : List.copyOf(rows);
        if (rows.size() > ROW_COUNT) {
            throw new IllegalArgumentException(
                    "rows must carry at most " + ROW_COUNT
                            + " entries, because the list screen declares that many rows, but it"
                            + " carries " + rows.size());
        }
        fieldErrors = (fieldErrors == null) ? List.of() : List.copyOf(fieldErrors);
    }

    /**
     * Tests whether this response carries any user row. Not an error indicator: a list response can
     * carry a full page and a message at once, and an empty list is not a failure - read
     * {@link #generalError()} for that.
     *
     * @return {@code true} when at least one row is present
     */
    public boolean hasRows() {
        return !rows.isEmpty();
    }

    /**
     * Tests whether this response carries any per-field error. Not a substitute for inspecting the
     * per-field states, since a blank field and a badly filled field need different remedies, and not
     * a substitute for {@link #generalError()}: the two message-without-error arms carry no field
     * error, and a failure reported only as a summary line carries none either.
     *
     * @return {@code true} when at least one field error is present
     */
    public boolean hasFieldErrors() {
        return !fieldErrors.isEmpty();
    }

    /**
     * One row of the administrative user list, corresponding to one row family of the list map
     * {@code app/cpy-bms/COUSR00.CPY}. Nested because a row has no meaning outside the response that
     * carries it.
     *
     * <p>The five widths come from the map rather than from the list program's display staging, which
     * combines the names into one twenty-five-character field and widens the type to eight; the map
     * keeps the names separate at twenty each and the type at one, so no combined name, no eight-wide
     * type and no spacing pad is modelled. Every component is text carried exactly as supplied,
     * including any trailing space of its fixed-width source field, so a leading zero or an unexpected
     * type character survives unchanged. Rows are deeply immutable.
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
     *     serialise rather than fail. The two authoritative codes are {@code A} and {@code U}, the
     *     same vocabulary the enclosing response and every other type in this package carries.
     * @since 1.0.0
     */
    public record UserRow(
            @Size(max = UserResponse.SELECTOR_LENGTH) String selector,
            @Size(max = UserResponse.USER_ID_LENGTH) String userId,
            @Size(max = UserResponse.FIRST_NAME_LENGTH) String firstName,
            @Size(max = UserResponse.LAST_NAME_LENGTH) String lastName,
            @Schema(
                    description =
                            "Raw one-character user-type code for this row. The two authoritative"
                                    + " codes are A for an administrator and U for a standard user,"
                                    + " from CDEMO-USRTYP-ADMIN and CDEMO-USRTYP-USER at"
                                    + " app/cpy/COCOM01Y.cpy lines 27 and 28 - the same vocabulary"
                                    + " every other user-type field in this API carries. Bounded by"
                                    + " width rather than by value.")
            @Size(max = UserResponse.USER_TYPE_LENGTH) String userType) {

        /**
         * Returns a diagnostic rendering that withholds every identifying value this row carries.
         *
         * <p><strong>Why a row needs its own withholding rendering.</strong> A row is the unit that
         * actually carries the personal data: an eight-character user identifier together with a first
         * and a last name is a named individual, and the generated rendering a record supplies by
         * default would print all three. A list page holds up to ten such rows, so one careless log
         * statement over a response would disclose ten identities at once. Redacting only at the
         * enclosing response would not be enough either, because a row is independently reachable
         * through {@link UserResponse#rows()} and a caller may render one directly.</p>
         *
         * <p><strong>What is withheld.</strong> The user identifier, the first name, the last name and
         * the user type. The identifier is withheld because it names the individual and because it
         * doubles as the paging anchor, so printing it would also disclose the browse position. The
         * type is withheld because, joined to an identifier, it discloses which named accounts hold
         * administrative rights - an authorization fact worth more to an attacker than the identifier
         * alone.</p>
         *
         * <p><strong>What is retained.</strong> The selector alone, which is a single operator
         * keystroke naming an intended action rather than any attribute of the person. Retaining it
         * keeps the rendering useful for diagnosing which row an operator marked, while disclosing
         * nothing about who that row describes.</p>
         *
         * @return a rendering carrying the selector and a fixed placeholder for every other component
         */
        @Override
        public String toString() {
            return "UserRow["
                    + "selector=" + selector
                    + ", userId=" + REDACTION_PLACEHOLDER
                    + ", firstName=" + REDACTION_PLACEHOLDER
                    + ", lastName=" + REDACTION_PLACEHOLDER
                    + ", userType=" + REDACTION_PLACEHOLDER
                    + "]";
        }
    }

    /**
     * Returns a diagnostic rendering that withholds every value identifying a person or a page
     * position.
     *
     * <p><strong>Why the generated rendering was unusable.</strong> A record's default rendering
     * prints every component, and this response carries a user identifier, a first name, a last name
     * and a user type at its top level plus up to ten rows carrying the same four values each. A
     * single log statement over one list response would therefore disclose eleven identities. The
     * legacy screens displayed those values on a terminal in front of an authorized administrator,
     * which is not the same as writing them to a durable log an operator may not be cleared to read.
     * </p>
     *
     * <p><strong>What is withheld.</strong> The top-level identifier, first name, last name and user
     * type; the row collection in its entirety; and the paging metadata, whose cursor keys are
     * themselves user identifiers and would otherwise reintroduce the identities the row redaction
     * removes. Each row additionally withholds its own values, so a rendering reached through a row
     * rather than through this response discloses nothing either.</p>
     *
     * <p><strong>What is retained, and why it is sufficient.</strong> The number of rows rather than
     * the rows, which is what a reader diagnosing a paging or cardinality problem actually needs. The
     * operator message and the two booleans are retained because they are externally observable
     * contract text and outcome flags rather than attributes of a person, and they are precisely what
     * makes a failed submission diagnosable. The screen furniture - both title lines, the transaction
     * and program names, the displayed date and time - is retained for the same reason: it identifies
     * which screen produced the response and describes nobody. The field errors are retained in full
     * because that contract carries a field name, a screen field identity, a state and a message, and
     * never a field value. The focus hint and the next route are opaque labels. The navigation context
     * is delegated to its own withholding rendering rather than suppressed here, so it redacts what it
     * knows to be sensitive and reports what it does not.</p>
     *
     * <p><strong>The withholding narrows the surface; the message line is the one seam it cannot
     * close.</strong> The add, update and delete confirmations this contract publishes compose the user
     * identifier into their own text, and {@link #message()} is external contract text that has to be
     * rendered verbatim, so a confirmation rendered here still carries the identifier it names.
     * Withholding the component is worth doing regardless - it covers every response whose message is
     * absent, a validation failure or a text that names no user, which is most of them - but the
     * guarantee this method makes is that <em>it</em> discloses no identity, not that no identity can
     * reach a log through the contract text it is obliged to reproduce. Closing that seam would mean
     * altering message text that the legacy screens established, which is a behavioural change this
     * migration does not make.</p>
     *
     * <p><strong>The row list is withheld rather than delegated, deliberately.</strong> Rendering the
     * list would call {@link UserRow#toString()} on each element, and this response would then be safe
     * only for as long as that method stays correct - a property of a different type, changeable
     * without ever reading this one. Withholding the list outright makes this rendering safe on its own
     * terms, and the same reasoning applies to {@link #pageMetadata()}.</p>
     *
     * <p>No credential appears here for the simplest possible reason: this contract has no credential
     * component to withhold, as set out on the type above.</p>
     *
     * @return a rendering carrying the row count, the outcome and the screen furniture, with a fixed
     *     placeholder for every value identifying a person or a page position
     */
    @Override
    public String toString() {
        return "UserResponse["
                + "rowCount=" + rows.size()
                + ", rows=" + REDACTION_PLACEHOLDER
                + ", pageMetadata=" + REDACTION_PLACEHOLDER
                + ", userId=" + REDACTION_PLACEHOLDER
                + ", firstName=" + REDACTION_PLACEHOLDER
                + ", lastName=" + REDACTION_PLACEHOLDER
                + ", userType=" + REDACTION_PLACEHOLDER
                + ", transactionName=" + transactionName
                + ", title01=" + title01
                + ", currentDate=" + currentDate
                + ", programName=" + programName
                + ", title02=" + title02
                + ", currentTime=" + currentTime
                + ", message=" + message
                + ", fieldErrors=" + fieldErrors
                + ", generalError=" + generalError
                + ", actionSucceeded=" + actionSucceeded
                + ", focusScreenFieldId=" + focusScreenFieldId
                + ", nextRoute=" + nextRoute
                + ", navigationContext=" + navigationContext
                + "]";
    }
}
