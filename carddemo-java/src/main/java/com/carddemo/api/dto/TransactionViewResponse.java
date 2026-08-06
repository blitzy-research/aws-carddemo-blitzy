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
import java.math.BigDecimal;
import java.util.List;

/**
 * Immutable response contract for the transaction-view screen &mdash; the REST-era form of legacy
 * CICS transaction {@code CT01}.
 *
 * <p>Every component below is a field of one screen map. The authority for the field inventory and
 * for every width is the generated symbolic map {@code app/cpy-bms/COTRN01.CPY}, whose input group
 * {@code COTRN1AI} opens at line 17 and whose output group {@code COTRN1AO} redefines it at line
 * 145; the map definition {@code app/bms/COTRN01.bms} supplies the screen geometry and the
 * attribute behaviour that shaped the error components. The behaviour these components describe is
 * that of {@code app/cbl/COTRN01C.cbl}, a 330-line program of nine paragraphs. Provenance for all
 * three artefacts is checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec} and upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy tree is read as evidence
 * only: no COBOL, screen-map or job-control text is reproduced here, and nothing in this module
 * reads that tree at run time.
 *
 * <p>The map holds twenty-one named fields and carries full input and output width parity &mdash;
 * each field is declared at the same width in both groups &mdash; so one record models both
 * directions of the screen. Fourteen of the twenty-one are the value fields of a transaction, and
 * the remaining seven are supplied by the server alone: the transaction name, the two title lines,
 * the clock date and time, the program name and the message line. Everything else in the copybook
 * is generated 3270 plumbing: a leading twelve-byte terminal-buffer filler, then for each field a
 * length halfword, an attribute byte with its redefinition and a four-byte filler on the input
 * side, and a three-byte filler with colour, highlight, programmed-symbol and validation bytes on
 * the output side. None of it is modelled, because it describes how a terminal renders a field
 * rather than what the field means. The map definition also declares a function-key legend on its
 * last screen line, but that is an unnamed skip-protected literal with no symbolic-map entry, so it
 * is a caption rather than data and is likewise absent.
 *
 * <p><strong>Two distinct sixteen-character transaction identifiers, deliberately not merged.</strong>
 * {@link #searchTransactionId()} comes from {@code TRNIDIN} (input line 60, output line 188) and is
 * the identifier the operator asked for, echoed back. {@link #transactionId()} comes from
 * {@code TRNID} (input line 66, output line 194) and is the identifier of the record that was
 * actually found. Collapsing the two into one component looks like obvious de-duplication and would
 * destroy the not-found contract. The program proves why: before it reads anything it blanks the
 * retrieved identifier together with all twelve other record display fields, at lines 159 to 171,
 * and it does <em>not</em> blank the search key; only afterwards, at lines 178 to 190, does it fill
 * those fields from the record it read. So on the record-absent path the search key comes back
 * populated while every record field comes back blank, and a client can tell "this is what you
 * asked for" from "this is what was found". One component cannot express that. The clear-screen path
 * at lines 311 to 326 is the one place that blanks the search key as well, and it blanks it as a
 * separate act.
 *
 * <p><strong>Three widths diverge from the underlying record and none of them may be normalised.</strong>
 * The transaction record at {@code app/cpy/CVTRA05Y.cpy} declares a description of 100 characters, a
 * merchant name of 50 and a merchant city of 50. This screen shows 60, 30 and 25, at map lines 96
 * and 224, 126 and 254, and 132 and 260 respectively. The transaction-list screen shows a
 * description of 26. All of those numbers are correct for their own surface, and
 * {@link StatementSummary} carries the record widths because the statement writers need the whole
 * record. This record therefore declares its widths as literals of its own, shares no width with
 * any sibling, and takes part in no base record, interface or mixin: a shared width would let one
 * screen's layout silently change what another screen's endpoint returns. The two transaction dates
 * are a fourth instance of the same trap. They are ten characters here, at map lines 108 and 236 and
 * lines 114 and 242, against eight on the transaction-list screen. The eight-character widths that
 * do appear on this record belong to the clock date and the clock time in the screen header and to
 * the program name; they are not transaction dates.
 *
 * <p><strong>Identifiers and codes are text, never numbers.</strong> The category code and the
 * merchant identifier are declared with numeric pictures in the underlying record, and both are
 * nevertheless carried as {@code String}, because their leading zeros and their fixed external
 * widths are contractual. A four-character category code must be rendered as {@code "0002"} and
 * never as {@code 2}; a nine-character merchant identifier must be rendered as {@code "999999999"}
 * and never as a number. The transaction identifier is alphanumeric in the record and sixteen
 * characters wide, so {@code "0000000000000001"} is not {@code "1"}, and it is the genuine business
 * key of the entity rather than a generated surrogate. Coercing any of these to a numeric type would
 * discard the leading zeros and shorten the external width, and that width is compared directly by
 * the byte-equivalence acceptance criterion.
 *
 * <p><strong>The transaction source stays a raw, space-padded ten-character value.</strong> The
 * domain layer does declare an enumeration of the three source values the sample data uses, and it
 * is deliberately not used here. Two reasons. First, the stored column has no membership check, so a
 * value outside the declared set is legal in the database and must survive the round trip instead of
 * failing conversion on the way out of a read-only view. Second, the declared values are themselves
 * padded to the full ten characters, so an enumeration would buy no normalisation while adding a
 * conversion that can throw. The value is passed through exactly as read: never trimmed, never
 * re-cased, never resolved.
 *
 * <p><strong>Both dates stay ten-character text and no date or time type appears anywhere.</strong>
 * The underlying record keeps its origination and processing stamps as twenty-six characters at
 * zero-based offsets 278 and 304, and it keeps them in two deliberately different shapes: an online
 * shape whose date and time are parted by a space in position eleven and whose fractional digits are
 * invariantly zero, and a batch shape carrying hyphens in positions five, eight and eleven and dots
 * in positions fourteen, seventeen and twenty. Neither shape is canonical and neither may be
 * converted into the other; a stamp of twenty-six spaces is a real value and must survive byte for
 * byte. This screen shows only the leading ten characters of each stamp, the calendar date, because
 * the program moves a twenty-six character stamp into a ten-character screen field at lines 185 and
 * 186. That is what these two components carry, verbatim. Parsing either of them here would require
 * choosing one of the two shapes and would reject the other, so no parsing, reformatting or
 * normalising happens in this type at all.
 *
 * <p><strong>The amount is a fixed-scale decimal and nothing here scales it.</strong> The record
 * field is {@code TRAN-AMT PIC S9(09)V99}, a zoned decimal held as display characters, and the
 * mapped column is an eleven-digit numeric of scale two, so the component is a {@link BigDecimal}
 * whose scale is two by contract. Every binary approximation of a fraction is excluded outright,
 * because no such representation holds a two-place decimal amount exactly and the acceptance
 * criterion compares emitted bytes rather than values within a tolerance. This type performs no
 * arithmetic and no scaling of any kind. That work belongs to the single decimal codec in the
 * utility layer, and it belongs there alone for a reason worth recording: the legacy estate contains
 * no {@code ROUNDED} clause anywhere, so a store into a two-place field discards the surplus
 * low-order digits toward zero rather than rounding, and the codec applies exactly that downward
 * mode in exactly one place. Half-even and half-up rounding are forbidden across the module, and
 * letting a data-transfer type re-scale a value it received would be the easiest way to reintroduce
 * them by accident. The map declares this field twelve characters wide, at lines 102 and 230,
 * because the program routes the amount through an edited presentation field before display; that
 * presentation form is not part of this contract and the numeric value is carried instead. Rendering
 * is already settled centrally, so this type declares no serializer, no serialization annotation and
 * no override of a serialization setting.
 *
 * <p><strong>One summary message, and a separate explicit error indicator.</strong> The screen has a
 * single message line, {@code ERRMSG} at map lines 144 and 272, and it is seventy-eight characters
 * wide &mdash; not the eighty of the card detail and card update screens, and not the eighty of the
 * program's own message work field, which is sent into a seventy-eight character screen field. One
 * component therefore carries every message this screen can show, and the three the program itself
 * produces are published as constants below so that a service, a controller and a test all name the
 * same text: {@link #EMPTY_TRANSACTION_ID_MESSAGE} at program line 149,
 * {@link #TRANSACTION_NOT_FOUND_MESSAGE} at line 285 and
 * {@link #TRANSACTION_LOOKUP_FAILED_MESSAGE} at line 292. The same component also carries the shared
 * common messages from {@code app/cpy/CSMSG01Y.cpy}; this program emits the invalid-key one, at line
 * 130. Those shared values are fifty characters wide, being a forty-nine character literal in a
 * fifty-character field plus one pad space, and they must arrive here untouched at that full width.
 * They are not the forty-character thank-you value in the screen-title catalogue
 * {@code app/cpy/COTTL01Y.cpy}, which is a different value at a different width for a different
 * purpose, and the two must never be conflated. {@link #generalError()} is a primitive
 * {@code boolean} carried in its own right and never inferred from the message being present: the
 * program keeps a dedicated error flag and tests it independently of the message text, a blank
 * message alongside a raised flag is a state it can reach, and a client that inferred one from the
 * other would be reading the wrong signal. The per-field detail behind that flag <em>is</em> part of
 * this contract, in {@link #fieldErrors()}: the screen has one input field but that field has two
 * distinct failure states, and the program marks them differently - the marker beside a key that was
 * left blank, only a colour change on one that was supplied and cannot be used - so a client that saw
 * the flag alone could not tell an operator which of the two to fix. The entries reuse
 * {@link ErrorResponse.FieldError}, the module's own two-state carrier, so a finding reported through a
 * returned screen and the same finding reported through a raised error reach a client in one shape.
 *
 * <p><strong>Nothing diagnostic leaks into this contract.</strong> When the read fails for a reason
 * other than an absent record, the program writes the raw response and reason codes to the operator
 * console for diagnosis, at line 290, and separately puts a plain sentence on the screen. Only the
 * sentence belongs here. No response code, no reason code, no exception type, no stack trace, no
 * internal path, no query text, no table name and no schema name appears in any component, and this
 * type holds no logger and writes to no stream.
 *
 * <p><strong>Navigation is declarative and there is no server-side forwarding.</strong> The estate
 * dispatched program to program with transfer-control commands and re-armed the next turn with
 * return-with-transaction commands; both become data. {@link #nextRoute()} is an opaque route label
 * the client is expected to call next, and this type deliberately declares no route table, no route
 * enumeration, no route holder and no dispatch method &mdash; that vocabulary belongs to the
 * navigation service, and putting it here would move a navigation decision into a data-transfer
 * type. {@link #navigationContext()} carries the echoed conversation state that replaced the
 * communication area; it is client-held state and not a server session. No screen work area is
 * carried, because the transaction programs are not members of the five-program family that includes
 * that copybook, and no attention-key value is echoed, because this response describes what to
 * display rather than which key was pressed.
 *
 * <p><strong>Nothing is validated, defaulted or normalised.</strong> There is no compact constructor
 * because there is nothing for one to do. Every component may legitimately be {@code null} or blank:
 * the program blanks the whole display area before every read, so a response in which only the
 * search key and the message are populated is the ordinary not-found shape rather than an anomaly.
 * The only annotation used is a maximum length, which measures a value and never alters one, so
 * leading and trailing spaces &mdash; contract in a fixed-width estate &mdash; survive validation
 * untouched. No presence, pattern, character-class, digit or numeric-range check appears anywhere:
 * each would reject input the legacy system accepts, amounts here are legitimately negative for a
 * refund, and the emptiness test that produces {@link #EMPTY_TRANSACTION_ID_MESSAGE} is a
 * message-bearing service validation whose ordered cascade would be replaced by unordered violation
 * reporting. The amount carries no length bound at all: a length bound measures character sequences
 * and collections, and declaring one on a decimal would be an invalid declaration.
 *
 * <p><strong>The primary account number is carried in full, and is withheld from diagnostics.</strong>
 * The sixteen-character card number is returned exactly as stored, because the legacy design applies
 * no field-level protection to it anywhere and closing that gap here would be unrequested behaviour
 * change on a contract this migration is required to preserve. The gap is recorded in
 * {@code docs/decision-log.md} as a finding instead. Those two things are separate channels and only
 * one of them is a contract: what a client receives is fixed by the screen, whereas what a log line
 * receives is fixed by nothing at all. {@link #toString()} is therefore overridden to withhold every
 * regulated component, so the card number, both transaction identifiers, the description, the amount
 * and the four merchant components cannot reach a log, a diagnostic message or a failure report
 * through a stringified instance. The component accessors and the serialized payload are untouched
 * and still carry every value in full, so preserving the wire contract and refusing to print it are
 * not in tension.
 *
 * <p>There is deliberately no matching request type. The transaction identifier reaches the endpoint
 * as a path or query parameter, so the read has nothing to bind from a body.
 *
 * @param transactionName the transaction identifier of this screen, from {@code TRNNAME}
 *     (four characters, map lines 24 and 152). The program stamps its own transaction name here,
 *     at line 249. Screen identity, not transaction data. May be {@code null}.
 * @param title01 the first screen title line, from {@code TITLE01} (forty characters, map lines
 *     30 and 158), supplied from the shared screen-title catalogue at line 247. Carried at its full
 *     width including the padding that centres it. May be {@code null}.
 * @param currentDate the server clock date shown in the screen header, from {@code CURDATE} (eight
 *     characters, map lines 36 and 164), assembled by the program at lines 252 to 256. Eight
 *     characters because it is a header clock value; it is not a transaction date and must not be
 *     confused with one. Carried verbatim, with no parsing and no reformatting. May be {@code null}.
 * @param programName the name of the legacy program behind this screen, from {@code PGMNAME} (eight
 *     characters, map lines 42 and 170), stamped by the program at line 250. Screen identity. May be
 *     {@code null}.
 * @param title02 the second screen title line, from {@code TITLE02} (forty characters, map lines
 *     48 and 176), supplied from the same catalogue at line 248. The catalogue holds a commented-out
 *     alternative for this line which stays inactive, so the live value is the one the program
 *     actually moves. May be {@code null}.
 * @param currentTime the server clock time shown in the screen header, from {@code CURTIME} (eight
 *     characters, map lines 54 and 182), assembled at lines 258 to 262. Carried verbatim. May be
 *     {@code null}.
 * @param searchTransactionId the transaction identifier that was searched for, echoed back, from
 *     {@code TRNIDIN} (sixteen characters, map lines 60 and 188). Distinct from
 *     {@code transactionId} and never merged with it: this one is what the operator asked for and it
 *     is deliberately left populated on the not-found path. It may also arrive pre-filled from a
 *     row selected on the transaction-list screen, as at program lines 103 to 106. Carried as text
 *     so its leading zeros and its sixteen-character width survive. May be {@code null}.
 * @param transactionId the identifier of the transaction that was actually retrieved, from
 *     {@code TRNID} (sixteen characters, map lines 66 and 194), filled from the record at line 178.
 *     Blank whenever no record was retrieved, because the program clears it before every read at
 *     line 159. Text, never a number. May be {@code null}.
 * @param cardNumber the card number the retrieved transaction was made on, from {@code CARDNUM}
 *     (sixteen characters, map lines 72 and 200), filled from the record at line 179. Returned in
 *     full and unaltered, for the reason recorded above. Text, never a number. May be {@code null}.
 * @param typeCode the transaction type code, from {@code TTYPCD} (two characters, map lines 78 and
 *     206), filled at line 180. A code, so it is text. May be {@code null}.
 * @param categoryCode the transaction category code, from {@code TCATCD} (four characters, map lines
 *     84 and 212), filled at line 181. Declared with a numeric picture in the record and carried as
 *     text so that a category of two is rendered as four characters with its leading zeros intact.
 *     May be {@code null}.
 * @param source the channel the transaction originated from, from {@code TRNSRC} (ten characters,
 *     map lines 90 and 218), filled at line 182. A raw, space-padded ten-character value: never
 *     trimmed and never resolved to an enumeration, so a channel outside the declared set round
 *     trips instead of failing. May be {@code null}.
 * @param description the transaction description, from {@code TDESC} (sixty characters, map lines 96
 *     and 224), filled at line 184. Sixty here, against one hundred in the underlying record and
 *     twenty-six on the transaction-list screen; this width is this screen's and is shared with
 *     nothing. May be {@code null}.
 * @param amount the transaction amount, from {@code TRNAMT} (map lines 102 and 230), filled at line
 *     183 by way of an edited presentation field whose form is not carried here. Scale two by
 *     contract, from {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy} line 10, which
 *     also fixes the nine integer digits; the published schema states both figures and the canonical
 *     constructor refuses a value that contradicts them. Legitimately negative for a refund, so no
 *     sign check is applied. Never scaled, never rounded and never rendered as a string by this type.
 *     May be {@code null}.
 * @param originationDate the calendar date the transaction originated on, from {@code TORIGDT} (ten
 *     characters, map lines 108 and 236), filled at line 185 from the leading portion of the
 *     twenty-six character origination stamp held at zero-based offset 278 of the record. Ten
 *     characters, against eight on the transaction-list screen. Text, carried verbatim, with no
 *     parsing and no conversion between the online and batch stamp shapes. May be {@code null}.
 * @param processingDate the calendar date the transaction was processed on, from {@code TPROCDT}
 *     (ten characters, map lines 114 and 242), filled at line 186 from the leading portion of the
 *     twenty-six character processing stamp held at zero-based offset 304 of the record &mdash; the
 *     same offset the legacy sort specification addresses. Ten characters and text, on the same terms
 *     as {@code originationDate}. May be {@code null}.
 * @param merchantId the merchant identifier, from {@code MID} (nine characters, map lines 120 and
 *     248), filled at line 187. Declared with a numeric picture in the record and carried as text so
 *     its nine-character width and leading zeros survive. May be {@code null}.
 * @param merchantName the merchant name, from {@code MNAME} (thirty characters, map lines 126 and
 *     254), filled at line 188. Thirty here, against fifty in the underlying record; not unified
 *     with it. May be {@code null}.
 * @param merchantCity the merchant city, from {@code MCITY} (twenty-five characters, map lines 132
 *     and 260), filled at line 189. Twenty-five here, against fifty in the underlying record; not
 *     unified with it. May be {@code null}.
 * @param merchantZip the merchant postal code, from {@code MZIP} (ten characters, map lines 138 and
 *     266), filled at line 190. Text, and never re-cased or re-spaced. May be {@code null}.
 * @param errorMessage the single summary message line, from {@code ERRMSG} (seventy-eight
 *     characters, map lines 144 and 272). Seventy-eight, not eighty. Carries the three program
 *     messages published as constants below and any shared common message, each at its own natural
 *     width and none of them altered. {@code null} when the screen has nothing to say. May be
 *     {@code null}.
 * @param generalError whether the screen is reporting a failure. A primitive {@code boolean} carried
 *     explicitly, mirroring the program's own dedicated error flag, and never inferred from
 *     {@code errorMessage} being present.
 * @param focusScreenFieldId the identifier of the screen field that input focus belongs on, carried
 *     as an opaque label and bounded at the seven characters a symbolic screen-field name may occupy -
 *     the generator appends a one-character suffix to form the eight-character symbolic names, so a
 *     longer name could not exist. Identity only: the legacy mechanism was a length value assigned to
 *     a named field,
 *     and neither that value nor any cursor row, column or attribute byte is reproduced. On this
 *     screen the only field it ever names is the search key, which the program re-selects at lines
 *     151, 154, 287 and 294. May be {@code null} when focus is unspecified.
 * @param nextRoute the route the client should call next, as an opaque label. Declarative only: the
 *     server forwards nothing and resolves nothing, and the vocabulary of routes is owned by the
 *     navigation service rather than by this type. May be {@code null} when the client stays where
 *     it is.
 * @param navigationContext the echoed conversation state that replaced the communication area
 *     carried across a pseudo-conversational turn. Client-held state, not a server session. May be
 *     {@code null}.
 */
public record TransactionViewResponse(
        @Size(max = 4) String transactionName,
        @Size(max = 40) String title01,
        @Size(max = 8) String currentDate,
        @Size(max = 8) String programName,
        @Size(max = 40) String title02,
        @Size(max = 8) String currentTime,
        @Size(max = 16) String searchTransactionId,
        @Size(max = 16) String transactionId,
        @Size(max = 16) String cardNumber,
        @Size(max = 2) String typeCode,
        @Size(max = 4) String categoryCode,
        @Size(max = 10) String source,
        @Size(max = 60) String description,
        @Schema(description = "Transaction amount. Record field TRAN-AMT of CVTRA05Y.cpy line 10: a "
                + "signed zoned decimal with nine integer digits and two decimal places, so total "
                + "precision 11 and scale exactly 2. Legitimately negative for a refund. The edited "
                + "presentation field the screen displays is deliberately not reproduced; this is the "
                + "numeric value alone.")
        BigDecimal amount,
        @Size(max = 10) String originationDate,
        @Size(max = 10) String processingDate,
        @Size(max = 9) String merchantId,
        @Size(max = 30) String merchantName,
        @Size(max = 25) String merchantCity,
        @Size(max = 10) String merchantZip,
        @Size(max = 78) String errorMessage,
        boolean generalError,

        /* The field-level detail behind the flag above, in the order the turn established it. Never
         * null; empty when the turn raised nothing. Two states rather than one, because the legacy
         * screen distinguishes a search key that was left blank from one that was supplied and cannot be
         * used - it writes the marker beside the first and only changes the colour of the second - and a
         * single flag cannot say which of the two happened. */
        List<ErrorResponse.FieldError> fieldErrors,

        @Size(max = TransactionViewResponse.SCREEN_FIELD_ID_LENGTH) String focusScreenFieldId,
        String nextRoute,
        NavigationContext navigationContext) {

    // A record is used rather than a class with accessors so that immutability, the accessors, the
    // equality contract and the parameter metadata all come from the language instead of from a
    // code generator; no annotation processor participates in this module's build.
    //
    // That decision governs construction only. It says nothing about rendering, which is settled
    // separately by the toString override at the foot of this type.

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of each regulated component.
     *
     * <p>A constant rather than any transformation of the value, so nothing about a withheld
     * component - not its length, not a prefix or suffix, not a digest - can be recovered from a
     * stringified instance. A partial stand-in was rejected deliberately: a shortened card number is
     * still cardholder data, and a masked amount whose digit count survives still discloses the order
     * of magnitude. The same literal is used by every redacting contract in this package so that the
     * absence of a regulated value is auditable by one search across the whole DTO surface.
     *
     * <p>Private because it is a rendering detail rather than part of the transaction-view contract.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Canonical constructor. Stores every component exactly as supplied and refuses an amount whose
     * decimal shape contradicts the record field it represents.
     *
     * <p>Every value still crosses this boundary byte for byte, which is what lets a blank display
     * area, a space-padded channel value, a fifty-character shared message and a leading-zero code
     * reach the client unchanged. Nothing is defaulted, nothing is normalised, nothing is trimmed,
     * padded, case-folded or reformatted, and no component is copied because every one is already
     * immutable.
     *
     * <p><strong>One value is refused rather than altered.</strong> The amount is checked against the
     * decimal shape of {@code TRAN-AMT PIC S9(09)V99}: a scale other than {@link #AMOUNT_SCALE}, or a
     * value needing more than {@link #AMOUNT_INTEGER_DIGITS} integer digits, cannot be what that field
     * holds. No rescaling, rounding, truncation or formatting occurs, so the amount a producer
     * published is the amount the client receives, unchanged to the last cent. The only thing that
     * changes is that a producer publishing the wrong shape learns of it at construction, instead of
     * emitting a payload whose precision silently contradicts the schema this type publishes - which is
     * the difference between a decimal contract that is documented and one that holds. A {@code null}
     * amount is accepted untouched, because the record-absent path deliberately blanks every retrieved
     * field.
     *
     * @throws IllegalArgumentException if {@code amount} carries a scale other than
     *     {@link #AMOUNT_SCALE} or needs more than {@link #AMOUNT_INTEGER_DIGITS} integer digits
     */
    public TransactionViewResponse {
        fieldErrors = (fieldErrors == null) ? List.of() : List.copyOf(fieldErrors);
        requireRecordShape(amount);
    }

    /**
     * Width in characters of a nominated screen field's identity: 7.
     *
     * <p>Derived from the mapset definition {@code app/bms/COTRN01.bms}, in which no named field has a
     * name longer than seven characters. Seven is the generator's own ceiling rather than a limit
     * invented here: it appends a one-character suffix to each field name to form the eight-character
     * symbolic names in {@code app/cpy-bms/COTRN01.CPY}, so a longer name could not be generated.</p>
     *
     * <p>Declared here rather than shared with a sibling contract, exactly as this type's widths are,
     * so that no change to another map can silently alter this one. The bound reports an over-long
     * value and never shortens one.</p>
     */
    public static final int SCREEN_FIELD_ID_LENGTH = 7;

    /**
     * Scale of the amount: 2 &mdash; the two decimal places of {@code TRAN-AMT PIC S9(09)V99} at
     * {@code app/cpy/CVTRA05Y.cpy} line 10, matching the fixed-scale numeric column the value is
     * persisted in.
     *
     * <p>Public because it is part of the numeric contract rather than an implementation choice: a
     * service that builds a response and a test that checks one need one authority for the figure.
     * Stating it is not an instruction to rescale anything - no scaling call, rounding mode, precision
     * context or numeric formatter appears anywhere in this file.</p>
     */
    public static final int AMOUNT_SCALE = 2;

    /**
     * The number of integer digits the amount may carry: 9 &mdash; the nine integer digits of the same
     * record field. With {@link #AMOUNT_SCALE} this gives the total precision of eleven that the
     * relational column declares.
     */
    public static final int AMOUNT_INTEGER_DIGITS = 9;

    /**
     * Confirms that the amount has the decimal shape of the record field it represents.
     *
     * <p>Reads only the amount's own scale and precision. It performs no arithmetic on the value, does
     * not re-scale it, does not round it and does not format it, so it cannot change what the client
     * receives. The failure text names the offending scale or digit count and never the amount itself,
     * so a rejected value cannot reach a log through the diagnostic that reports it.
     *
     * @param amount the amount to check, or {@code null} on the record-absent path where every
     *     retrieved field is deliberately blank
     * @throws IllegalArgumentException if the amount does not fit the record field
     */
    private static void requireRecordShape(final BigDecimal amount) {
        if (amount == null) {
            return;
        }
        if (amount.scale() != AMOUNT_SCALE) {
            throw new IllegalArgumentException("amount must carry scale " + AMOUNT_SCALE
                    + ", because its record field stores two decimal places, but its scale is "
                    + amount.scale());
        }
        final int integerDigits = amount.precision() - amount.scale();
        if (integerDigits > AMOUNT_INTEGER_DIGITS) {
            throw new IllegalArgumentException("amount must fit " + AMOUNT_INTEGER_DIGITS
                    + " integer digits, because that is the width of its record field, but it needs "
                    + integerDigits);
        }
    }

    /**
     * Returns a diagnostic representation that mirrors the screen layout and discloses no regulated
     * value.
     *
     * <p><strong>Why the implicit record rendering could not stand.</strong> A record's generated
     * {@code toString()} prints every component, and this screen is the one place in the package where a
     * single instance carries a primary account number in full, the amount of the movement made on it,
     * the merchant that received the movement and the two dates it happened on. Any structured logger,
     * framework diagnostic, failed assertion, exception message or string interpolation touching an
     * instance would have emitted the whole set together.</p>
     *
     * <p><strong>Why the remainder is retained.</strong> The screen furniture, the type and category
     * codes, the raw channel value, the summary message, the error indicator and the nominated field
     * identify nobody, and they are the part of a view response worth seeing in a diagnostic. The
     * navigation state is printed by delegation because
     * {@link NavigationContext#toString()} withholds its own six identifying values.</p>
     *
     * <p><strong>Why both transaction identifiers go, and why the two dates go with them.</strong>
     * Each identifier is a durable key to a stored record: {@link #transactionId()} retrieves the row
     * directly and {@link #searchTransactionId()} is the same key echoed back, so withholding one
     * while printing the other would withhold nothing. That is a deliberate divergence from
     * {@link TransactionAddResponse} and {@link BillPaymentResponse}, which both print their new
     * transaction identifier: theirs is an outcome the caller already holds, reported once at the
     * moment of creation, whereas this screen's identifier is a lookup key presented to correlate
     * against stored history. The two dates are withheld on this contract rather than retained as the
     * sibling account contracts retain theirs, because here they date a single movement made by one
     * cardholder rather than describing an account's lifecycle, and the payload still carries them in
     * full for any caller that needs them.</p>
     *
     * <p><strong>Withholding is confined to this method.</strong> Every accessor returns its component
     * exactly as supplied and the serialized payload is unaffected, because the screen presents each
     * value in full and a client echoes it back unchanged.</p>
     *
     * @return the screen layout with each regulated component replaced by a fixed placeholder
     */
    @Override
    public String toString() {
        return "TransactionViewResponse["
                + "transactionName=" + transactionName
                + ", title01=" + title01
                + ", currentDate=" + currentDate
                + ", programName=" + programName
                + ", title02=" + title02
                + ", currentTime=" + currentTime
                + ", searchTransactionId=" + REDACTION_PLACEHOLDER
                + ", transactionId=" + REDACTION_PLACEHOLDER
                + ", cardNumber=" + REDACTION_PLACEHOLDER
                + ", typeCode=" + typeCode
                + ", categoryCode=" + categoryCode
                + ", source=" + source
                + ", description=" + REDACTION_PLACEHOLDER
                + ", amount=" + REDACTION_PLACEHOLDER
                + ", originationDate=" + REDACTION_PLACEHOLDER
                + ", processingDate=" + REDACTION_PLACEHOLDER
                + ", merchantId=" + REDACTION_PLACEHOLDER
                + ", merchantName=" + REDACTION_PLACEHOLDER
                + ", merchantCity=" + REDACTION_PLACEHOLDER
                + ", merchantZip=" + REDACTION_PLACEHOLDER
                + ", errorMessage=" + errorMessage
                + ", generalError=" + generalError
                + ", fieldErrors=" + fieldErrors
                + ", focusScreenFieldId=" + focusScreenFieldId
                + ", nextRoute=" + nextRoute
                + ", navigationContext=" + navigationContext
                + "]";
    }

    /**
     * The message shown when the transaction identifier submitted for the search is empty:
     * {@value #EMPTY_TRANSACTION_ID_MESSAGE}.
     *
     * <p>Emitted by {@code app/cbl/COTRN01C.cbl} at line 149, the first branch of the program's
     * enter-key cascade, and reproduced here character for character. Three details are contract
     * rather than accident and none of them is a defect to be tidied. The word {@code NOT} is
     * capitalised, which is the house style for the estate's emptiness messages and appears the same
     * way in the reporting and transaction-add programs. There are exactly three trailing dots. And
     * there is no space before them. Published as a constant so the text is typed once and a service,
     * a controller and an interface-contract test all assert against the same characters.</p>
     */
    public static final String EMPTY_TRANSACTION_ID_MESSAGE = "Tran ID can NOT be empty...";

    /**
     * The message shown when no transaction exists for the identifier searched for:
     * {@value #TRANSACTION_NOT_FOUND_MESSAGE}.
     *
     * <p>Emitted by {@code app/cbl/COTRN01C.cbl} at line 285, on the record-absent outcome of the
     * read. This is the message that accompanies the shape described above: the search key echoed
     * back, every retrieved field blank, and the error indicator raised. Capitalised {@code NOT} and
     * three trailing dots again, both reproduced exactly.</p>
     */
    public static final String TRANSACTION_NOT_FOUND_MESSAGE = "Transaction ID NOT found...";

    /**
     * The message shown when the transaction read fails for any reason other than an absent record:
     * {@value #TRANSACTION_LOOKUP_FAILED_MESSAGE}.
     *
     * <p>Emitted by {@code app/cbl/COTRN01C.cbl} at line 292. The program separately writes the raw
     * response and reason codes to the operator console at line 290 for diagnosis; that detail stays
     * out of this contract, and this deliberately unspecific sentence is the whole of what a client
     * is told.</p>
     */
    public static final String TRANSACTION_LOOKUP_FAILED_MESSAGE = "Unable to lookup Transaction...";

}
