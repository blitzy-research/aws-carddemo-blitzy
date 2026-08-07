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
 * Immutable response contract for adding a transaction &mdash; the REST-era replacement for the
 * screen that CICS transaction {@code CT02} presented through program
 * {@code app/cbl/COTRN02C.cbl} (783 lines, 18 paragraphs) and map {@code COTRN2A} of mapset
 * {@code COTRN02}.
 *
 * <p>The field-level contract is taken from the generated symbolic map
 * {@code app/cpy-bms/COTRN02.CPY}: the input group {@code COTRN2AI} occupies lines 17 to 144 and
 * the output redefinition {@code COTRN2AO} begins at line 145. Between them the map declares
 * <strong>twenty-one</strong> screen items, and all twenty-one are modelled here &mdash; fourteen
 * that the operator fills in and this response echoes back, and seven that only ever travel
 * outward. Every declared width is corroborated by the {@code LENGTH=} operand of the
 * corresponding field definition in {@code app/bms/COTRN02.bms}. The persisted record the screen
 * feeds is {@code app/cpy/CVTRA05Y.cpy}, a 350-byte layout whose amount field is a two-decimal
 * zoned quantity.
 *
 * <p>Provenance for this translation: repository commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19, which is the trailer recorded at
 * {@code app/cbl/COTRN02C.cbl} line 782. No statement, picture clause or other text of the legacy
 * source is reproduced anywhere in this file; the legacy estate is cited by path and line only.
 * The operator-visible message texts declared further down are the single exception, and they are
 * not source text at all &mdash; they are the external interface contract this response has to
 * reproduce character for character.
 *
 * <h2>What this type does not do</h2>
 *
 * <p>This is a data-transfer type and nothing else. It generates no identifier, assembles no
 * message, parses no date, validates no field, resolves no route, performs no lookup, applies no
 * rounding and reaches no database. Every one of those responsibilities belongs to the service
 * that populates this response. The type also models no 3270 artefact: the per-field length,
 * modified-data and attribute sub-items that {@code COTRN2AI} and {@code COTRN2AO} carry beside
 * every one of the twenty-one screen items, the twelve-byte terminal-buffer prefix each group
 * opens with, the field positions, the highlighting the map declares over the message line at
 * {@code app/bms/COTRN02.bms} lines 293 to 296, and the two attribute values the program writes
 * over the message line at {@code app/cbl/COTRN02C.cbl} line 727 are all rendering mechanisms of
 * a terminal that no longer exists. None of them appears here in any form.
 *
 * <h2>The success path deliberately returns blank echoed values</h2>
 *
 * <p>When the write succeeds, {@code app/cbl/COTRN02C.cbl} line 725 clears the screen before
 * redisplaying it: the field-initialisation paragraph at lines 762 to 779 blanks all fourteen
 * input fields, and only then is the success text placed on the message line. A successful
 * response therefore legitimately carries a new transaction identifier and a message alongside
 * fourteen <em>blank or absent</em> echoed values. That is the reason not one echoed component
 * here is marked mandatory: requiring any of them would reject the response shape the legacy
 * program actually produces.
 *
 * <h2>The message line carries exactly one text</h2>
 *
 * <p>The legacy validation cascade is first-error-wins. Each arm moves one text into the
 * program's single message field and immediately redisplays the screen, so the operator never
 * sees two texts at once; the screen paragraph at line 516 then copies that one text onto the
 * message item at line 520. This response reproduces that shape exactly: <strong>one</strong>
 * summary message component, carried by {@link #message()}, plus any number of
 * <em>independent</em> per-field errors in {@link #fieldErrors()}. The two are not alternatives
 * and neither is derived from the other.
 *
 * <h2>Identifiers and codes are text, never numbers</h2>
 *
 * <p>The new transaction identifier is produced by the paragraph at lines 442 to 466 as the
 * highest existing key plus one: the program positions past the end of the file, reads backwards
 * for the highest key, adds one to it and writes the result into a sixteen-character key. The
 * transaction table starts empty, so the very first identifier the system ever issues is
 * {@code "0000000000000001"} &mdash; sixteen characters, fifteen of them leading zeros &mdash;
 * and never {@code "1"}. Carrying it as any numeric type would discard those zeros and shorten
 * the external width that the byte-equivalence acceptance criterion compares directly. The same
 * reasoning applies to the eleven-character account key, the sixteen-character card number, the
 * four-character category code (which must serialize as {@code "0002"} and never as {@code "2"})
 * and the nine-character merchant identifier (which must keep all nine characters, as in
 * {@code "999999999"}). Two of these are declared numeric in the legacy layouts and are
 * nevertheless text here for precisely that reason. None of them is a surrogate: every one is the
 * genuine business key, so nothing here is generated, renumbered or reformatted.
 *
 * <h2>Money is exact, and surplus digits are dropped rather than rounded</h2>
 *
 * <p>The persisted amount is a {@link BigDecimal} whose contractual scale is two, matching the
 * two-decimal zoned quantity declared in {@code app/cpy/CVTRA05Y.cpy} and the two-decimal numeric
 * column it is persisted into. The separately carried {@link #amountEntered()} is the twelve-character
 * screen image and survives confirmation and validation turns before a record exists. No floating-point
 * type may ever stand in for the persisted value. Just as importantly, the
 * legacy estate contains <strong>no rounding clause at all</strong> &mdash; not one arithmetic
 * statement anywhere in the twenty-eight programs asks for rounding &mdash; and a store into a
 * two-decimal field without one discards the surplus digits toward zero. The module therefore
 * applies that one rounding policy in exactly one place, the zoned-decimal codec of the utility
 * layer, and half-even and half-up rounding are forbidden module-wide. This type consequently
 * performs no scaling and no arithmetic of any kind: it carries whatever exact value it is handed,
 * so that the single place where digits are discarded stays single. The edited display form the
 * screen shows, cited by the shape message at line 345 and matching the map's twelve-character
 * width, is carried independently and is never substituted for the persisted value.
 *
 * <h2>Widths diverge between screens on purpose</h2>
 *
 * <p>Four widths here differ from the same logical field elsewhere in the estate, and every one
 * of those differences is preserved rather than reconciled. The description is sixty characters
 * on this map against twenty-six on the transaction-list map and one hundred in the persisted
 * record; the merchant name is thirty against fifty in the record; the merchant city is
 * twenty-five against fifty in the record; and the two dates are ten characters here where the
 * record carries twenty-six-character stamps. Narrowing or widening any of them would silently
 * shorten or pad a value that the acceptance criteria compare byte for byte, so no width
 * constant, no base type, no interface and no mixin is shared with any other transaction
 * contract.
 *
 * <h2>Two twenty-six-character stamp shapes exist and are never unified</h2>
 *
 * <p>The dates on this screen are ten characters. The persisted record they are stored into
 * carries twenty-six-character origination and processing stamps in two deliberately different
 * shapes, one written by the online tier and one by the batch tier, and a stamp of twenty-six
 * blanks is a legitimate value that has to survive a round trip unchanged. Converting between
 * those shapes, or into any date or time type, is neither this type's business nor permitted
 * anywhere in the module, which is why no date or time type is imported here at all.
 *
 * <h2>Validation policy</h2>
 *
 * <p>The only constraint applied is a maximum length at each measured width. A length constraint
 * measures and never alters, so leading and trailing spaces, blank-filled values and fully blank
 * values all survive validation exactly as supplied. Nothing is marked mandatory, pattern-matched,
 * digit-checked or range-bounded: the success path deliberately blanks every echoed field, the
 * amount may legitimately be negative, and the twenty-three-check cascade the legacy performs is
 * an <em>ordered</em>, message-bearing chain that unordered constraint validation cannot express.
 * Reproducing it declaratively here would change which single message an operator sees.
 *
 * <h2>Wire contract</h2>
 *
 * <p>The module configures property inclusion so that absent values are omitted and configures
 * exact decimals to be written in plain notation, and it tolerates unknown properties globally.
 * No custom serializer is registered here and no serialization setting is overridden. Combined
 * with the normalisation in the constructor below, the payload shape is fixed: the field-error
 * collection is always present and is emitted as an empty array when there is nothing in it, so a
 * client never has to test it for absence, while every other component is omitted when absent.
 * This type is also not, and must not become, a wrapper for or a stand-in for the standard
 * problem-detail representation: that representation is deliberately switched off for this module
 * and {@link ErrorResponse} is the module's error body.
 *
 * <p>Every component is either text, an exact decimal, a primitive flag, an immutable list or an
 * immutable value type, so an instance is deeply immutable and safe to share across threads, with
 * canonical value equality and hashing.
 *
 * @param newTransactionId the identifier the write assigned, sixteen characters, produced by the
 *     paragraph at {@code app/cbl/COTRN02C.cbl} lines 442 to 466 as the highest existing key plus
 *     one. Alphanumeric text: {@code "0000000000000001"} is the first value the system can ever
 *     issue and is not the same value as {@code "1"}. This type neither generates, computes,
 *     advances nor derives it. {@code null} before a write has succeeded.
 * @param accountId the account key the operator supplied, eleven characters, from map item
 *     {@code ACTIDIN} at {@code app/cpy-bms/COTRN02.CPY} line 60. Text, so leading zeros survive.
 *     Blank or {@code null} on the success path, where the screen is cleared.
 * @param cardNumber the card number in force, sixteen characters, from map item {@code CARDNIN}
 *     at line 66. Carried whole: never shortened, never partially replaced and never obscured, so
 *     that the value a client receives is the value the legacy screen showed. Blank or
 *     {@code null} on the success path.
 * @param typeCode the transaction type code, two characters, from map item {@code TTYPCD} at
 *     line 72. Blank or {@code null} on the success path.
 * @param categoryCode the transaction category code, four characters, from map item
 *     {@code TCATCD} at line 78. Text, so a value such as {@code "0002"} keeps all four
 *     characters. Blank or {@code null} on the success path.
 * @param source the originating channel, ten characters, from map item {@code TRNSRC} at line 84.
 *     Raw and space-padded: never trimmed and never narrowed to an enumerated type, because the
 *     legacy field is a fixed-width channel label whose padding is part of the record image.
 *     Blank or {@code null} on the success path.
 * @param description the transaction description, sixty characters, from map item {@code TDESC}
 *     at line 90. Sixty is this map's width and is deliberately neither the transaction-list
 *     map's narrower width nor the persisted record's wider one. Blank or {@code null} on the
 *     success path.
 * @param amountEntered the twelve-character amount image the operator entered, from map item
 *     {@code TRNAMT} at line 96. Carried independently of {@code amount} so validation and
 *     confirmation turns redisplay the exact screen text even though no record has yet been written.
 * @param amount the transaction amount, an exact decimal whose contractual scale is two, from map
 *     the successful persisted projection and the two-decimal zoned field of
 *     {@code app/cpy/CVTRA05Y.cpy}. Neither scaled nor rounded nor formatted by this type, and
 *     legitimately negative. {@code null} until a write succeeds.
 * @param originationDate the origination date the operator supplied, ten characters, from map item
 *     {@code TORIGDT} at line 102. Opaque text in the shape the format message at
 *     {@code app/cbl/COTRN02C.cbl} line 360 names; never a date type. Blank or {@code null} on the
 *     success path.
 * @param processingDate the processing date the operator supplied, ten characters, from map item
 *     {@code TPROCDT} at line 108. Opaque text, as for the origination date. Blank or
 *     {@code null} on the success path.
 * @param merchantId the merchant identifier, nine characters, from map item {@code MID} at
 *     line 114. Text, so all nine characters survive. Blank or {@code null} on the success path.
 * @param merchantName the merchant name, thirty characters, from map item {@code MNAME} at
 *     line 120. Thirty is this map's width and is deliberately not the persisted record's wider
 *     one. Blank or {@code null} on the success path.
 * @param merchantCity the merchant city, twenty-five characters, from map item {@code MCITY} at
 *     line 126. Twenty-five is this map's width and is deliberately not the persisted record's
 *     wider one. Blank or {@code null} on the success path.
 * @param merchantZip the merchant postal code, ten characters, from map item {@code MZIP} at
 *     line 132. Blank or {@code null} on the success path.
 * @param confirmationFlag the operator's confirmation answer, exactly one character, from map item
 *     {@code CONFIRM} at line 138. Text and not a two-valued flag, because the program's
 *     confirmation branch at {@code app/cbl/COTRN02C.cbl} lines 169 to 188 distinguishes three
 *     outcomes: an affirmative answer proceeds, a negative or absent answer re-prompts with
 *     {@link #MESSAGE_CONFIRM_PROMPT}, and <em>any other character</em> is reported with
 *     {@link #MESSAGE_CONFIRM_INVALID}. A boolean cannot represent that third outcome and would
 *     silently reclassify it. Blank or {@code null} on the success path.
 * @param transactionName the four-character transaction identifier of this screen, from map item
 *     {@code TRNNAME} at line 24, populated by the header paragraph at
 *     {@code app/cbl/COTRN02C.cbl} line 558 from the program's own transaction literal declared at
 *     line 37. Despite the map item's name it holds an identifier, not a display name.
 * @param title01 the first screen title, forty characters, from map item {@code TITLE01} at
 *     line 30, populated at {@code app/cbl/COTRN02C.cbl} line 556 from the shared message
 *     catalogue rather than from anything this screen owns. Carried space-padded to its full
 *     width.
 * @param currentDate the eight-character current date the header shows, from map item
 *     {@code CURDATE} at line 36, assembled at {@code app/cbl/COTRN02C.cbl} lines 561 to 565 as a
 *     two-digit month, day and year. Opaque text in exactly that shape; never a date type.
 * @param programName the eight-character program name the header shows, from map item
 *     {@code PGMNAME} at line 42, populated at {@code app/cbl/COTRN02C.cbl} line 559 from the
 *     program's own name literal declared at line 36.
 * @param title02 the second screen title, forty characters, from map item {@code TITLE02} at
 *     line 48, populated at {@code app/cbl/COTRN02C.cbl} line 557 from the shared message
 *     catalogue. Carried space-padded to its full width.
 * @param currentTime the eight-character current time the header shows, from map item
 *     {@code CURTIME} at line 54, assembled at {@code app/cbl/COTRN02C.cbl} lines 567 onward as
 *     two-digit hours, minutes and seconds. Opaque text in exactly that shape; never a time type.
 * @param message the single summary text for the whole response, seventy-eight characters, from
 *     map item {@code ERRMSG} at {@code app/cpy-bms/COTRN02.CPY} line 144 and corroborated by
 *     {@code app/bms/COTRN02.bms} line 295. <strong>Seventy-eight, not eighty:</strong> the card
 *     detail and card update maps use eighty for their own message line and this map does not.
 *     The same item carries the success text and the first failing check's text, because the
 *     legacy program writes both to one field; the difference between them was signalled on the
 *     terminal by an attribute this type does not model. Selecting and assembling this text
 *     belongs to the service. {@code null} when there is no message.
 * @param generalError whether this response reports a failure of the request as a whole. Carried
 *     explicitly and never inferred from the presence of a message, because the message item also
 *     carries the success text and a confirmation prompt, so inferring failure from a non-absent
 *     message would misreport both.
 * @param fieldErrors the independent per-field errors, never {@code null} and never mutable, each
 *     entry an {@link ErrorResponse.FieldError} carrying one of the two states of
 *     {@link ErrorResponse.FieldState}. Empty means no field-level error, which is also the
 *     first-submission shape. Two states rather than one flag because the parameterised validation
 *     macro at {@code app/cpy/CSSETATY.cpy} distinguishes a field left blank from a field filled in
 *     wrongly, and only decorates at all once a screen has been re-submitted.
 * @param focusScreenFieldId the identifier of the screen field that input focus belongs on, or
 *     {@code null} when the response gives no hint. An opaque label and nothing else: not a row,
 *     not a column, not a cursor offset and not an attribute value. It travels
 *     <em>independently</em> of the failing field because the legacy does the same &mdash; the
 *     duplicate-key arm at {@code app/cbl/COTRN02C.cbl} line 740 reports a failure about the
 *     transaction identifier while placing focus on the account-id input, and the screen-clearing
 *     paragraph at line 764 places focus there too on the success path.
 * @param nextRoute the route the client should call next, carried as an opaque string, or
 *     {@code null} when the response nominates none. The legacy dispatched program to program and
 *     re-armed the next turn itself; the REST translation returns a route in the body and forwards
 *     nothing server-side, so the client drives the next call. This type holds no route table, no
 *     route vocabulary and no dispatch behaviour: the navigation service owns all three.
 * @param navigationContext the client-echoed navigation state for the next turn, or {@code null}.
 *     Echoed request state and not a server session, exactly as {@link NavigationContext}
 *     documents. Its legacy antecedent is the shared work area declared in
 *     {@code app/cpy/COCOM01Y.cpy}, which every online program included textually and passed from
 *     one turn to the next; here the client carries it back instead.
 * @since 1.0.0
 */
public record TransactionAddResponse(

        /* The generated key. Sixteen characters, alphanumeric, response-only: the map has no
           input item for it because the legacy program derives it rather than reading it. */
        @Size(max = 16) String newTransactionId,

        /* ---- The fourteen echoed input items, in COTRN2AI declaration order ---- */

        /* ACTIDIN, width 11 - COTRN02.CPY:60. */
        @Size(max = 11) String accountId,

        /* CARDNIN, width 16 - COTRN02.CPY:66. */
        @Size(max = 16) String cardNumber,

        /* TTYPCD, width 2 - COTRN02.CPY:72. */
        @Size(max = 2) String typeCode,

        /* TCATCD, width 4 - COTRN02.CPY:78. Text, so "0002" keeps four characters. */
        @Size(max = 4) String categoryCode,

        /* TRNSRC, width 10 - COTRN02.CPY:84. Raw and space-padded; never an enumerated type. */
        @Size(max = 10) String source,

        /* TDESC, width 60 - COTRN02.CPY:90. Sixty on this map; not the list map's 26 and not the
           persisted record's 100. */
        @Size(max = 60) String description,

        /* TRNAMT, width 12 on the map - COTRN02.CPY:96. The exact text redisplayed on every turn,
           independent of whether a record has been written. */
        @Size(max = TransactionAddResponse.AMOUNT_ENTERED_LENGTH) String amountEntered,

        /* Persisted TRAN-AMT. Carried as an exact decimal of contractual scale 2 only after a
           successful write. The scale is stated in the published schema and enforced by the compact
           constructor, because a scale that is documented and unchecked is a scale a producer can
           silently break. */
        @Schema(description = "Transaction amount. Record field TRAN-AMT of CVTRA05Y.cpy line 10: a "
                + "signed zoned decimal with nine integer digits and two decimal places, so total "
                + "precision 11 and scale exactly 2. Present only after a successful write; "
                + "amountEntered independently carries the screen's twelve-character image.")
        BigDecimal amount,

        /* TORIGDT, width 10 - COTRN02.CPY:102. Opaque text; no date type anywhere in this file. */
        @Size(max = 10) String originationDate,

        /* TPROCDT, width 10 - COTRN02.CPY:108. Opaque text. */
        @Size(max = 10) String processingDate,

        /* MID, width 9 - COTRN02.CPY:114. Text, so "999999999" keeps nine characters. */
        @Size(max = 9) String merchantId,

        /* MNAME, width 30 - COTRN02.CPY:120. Thirty on this map; not the record's 50. */
        @Size(max = 30) String merchantName,

        /* MCITY, width 25 - COTRN02.CPY:126. Twenty-five on this map; not the record's 50. */
        @Size(max = 25) String merchantCity,

        /* MZIP, width 10 - COTRN02.CPY:132. */
        @Size(max = 10) String merchantZip,

        /* CONFIRM, width 1 - COTRN02.CPY:138. One character, never a two-valued flag: the
           confirmation branch distinguishes three outcomes. */
        @Size(max = 1) String confirmationFlag,

        /* ---- The seven response-only items, in COTRN2AI declaration order ---- */

        /* TRNNAME, width 4 - COTRN02.CPY:24. */
        @Size(max = 4) String transactionName,

        /* TITLE01, width 40 - COTRN02.CPY:30. */
        @Size(max = 40) String title01,

        /* CURDATE, width 8 - COTRN02.CPY:36. */
        @Size(max = 8) String currentDate,

        /* PGMNAME, width 8 - COTRN02.CPY:42. */
        @Size(max = 8) String programName,

        /* TITLE02, width 40 - COTRN02.CPY:48. */
        @Size(max = 40) String title02,

        /* CURTIME, width 8 - COTRN02.CPY:54. */
        @Size(max = 8) String currentTime,

        /* ERRMSG, width 78 - COTRN02.CPY:144, corroborated by COTRN02.bms:295. Seventy-eight on
           this map, not the eighty the card detail and card update maps use. Exactly one
           summary text, because the legacy cascade is first-error-wins. */
        @Size(max = 78) String message,

        /* ---- Protocol components with no map item of their own ---- */

        /* Explicit, never inferred from the message being present. */
        boolean generalError,

        /* N independent per-field states, normalised in the constructor below. */
        List<ErrorResponse.FieldError> fieldErrors,

        /* Seven is the widest of the twenty-one field identifiers this map declares. The bound
           measures a label; it is not a coordinate and not an attribute. */
        @Size(max = 7) String focusScreenFieldId,

        /* Deliberately unbounded: a route is a REST path with no legacy fixed width, so any
           bound here would be an invented limit rather than a measured one. */
        String nextRoute,

        NavigationContext navigationContext) {

    /**
     * Fixed stand-in written by {@link #toString()} in place of each cardholder-bearing component.
     *
     * <p>A constant rather than any transformation of the value, so nothing about a withheld
     * component &mdash; not its length, not a leading or trailing fragment, not a digest &mdash;
     * can be recovered from a stringified instance. Showing part of a primary account number was
     * rejected deliberately: a shortened account number is still cardholder data, and a digest of
     * a nine-character identifier is reversible by enumeration.
     *
     * <p><strong>This affects the diagnostic rendering only.</strong> Every accessor of this record
     * returns exactly the value the producer supplied: {@link #cardNumber()} returns all sixteen
     * characters, {@link #amount()} returns the exact decimal, and the serialized payload carries
     * both in full. Nothing here shortens, replaces, obscures or otherwise alters a carried value.
     *
     * <p>Private because it is a rendering detail and not part of the response contract, and
     * identical in value to the stand-in used by the sibling contracts in this package so that one
     * recognisable marker identifies a withheld value everywhere the module records a line.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    // =================================================================================
    // OPERATOR-VISIBLE MESSAGE TEXTS
    //
    // Every text below is reproduced character for character from the program that emits it,
    // app/cbl/COTRN02C.cbl, at the line cited on each constant. They are an external interface
    // contract, verified by the interface-contract acceptance criterion, so the punctuation is
    // load bearing throughout: the capitalised negation in the eleven emptiness texts, the three
    // trailing dots with no space before them, the parentheses around the two confirmation
    // values, the lower-case word in the two date-validity texts and their spaced separator, the
    // abbreviated forms in the two cross-reference failures, the singular verb in the duplicate
    // text, and the leading and trailing spaces on the two success fragments.
    //
    // The service that populates this response selects one of them. This type performs no
    // selection, no composition, no joining and no formatting, and it applies no trimming,
    // padding or case folding to any of them.
    //
    // The common texts the program shares with the other sixteen online transactions - the
    // invalid-key text it uses at line 150 and the two screen titles it uses at lines 556 and 557
    // - are deliberately absent: those belong to the shared message catalogue of the service
    // layer, and duplicating them here would create two sources for one text.
    // =================================================================================

    /**
     * Re-prompt shown when the confirmation answer is negative, blank or absent, from
     * {@code app/cbl/COTRN02C.cbl} line 178.
     *
     * <p>Not a failure: the program's confirmation branch treats a negative, blank or absent
     * answer as "not confirmed yet" and redisplays the screen with this text so the operator can
     * confirm. A response carrying it therefore reports no general error.
     */
    public static final String MESSAGE_CONFIRM_PROMPT = "Confirm to add this transaction...";

    /**
     * Reported when the confirmation answer is neither affirmative nor negative, from
     * {@code app/cbl/COTRN02C.cbl} line 184.
     *
     * <p>The parentheses around the two permitted values are part of the text. This is the third
     * outcome of the confirmation branch and the reason {@link #confirmationFlag()} is a
     * one-character string rather than a two-valued flag: a flag cannot carry a value that is
     * neither of the two permitted ones, so this text would become unreachable.
     */
    public static final String MESSAGE_CONFIRM_INVALID = "Invalid value. Valid values are (Y/N)...";

    /**
     * Reported when a supplied account key is not numeric, from {@code app/cbl/COTRN02C.cbl}
     * line 199. First arm of the two-key lookup branch: the account key is examined first, and
     * only when it is absent does the branch fall through to the card number.
     */
    public static final String MESSAGE_ACCOUNT_ID_NOT_NUMERIC = "Account ID must be Numeric...";

    /**
     * Reported when a supplied card number is not numeric, from {@code app/cbl/COTRN02C.cbl}
     * line 213. Second arm of the two-key lookup branch.
     */
    public static final String MESSAGE_CARD_NUMBER_NOT_NUMERIC = "Card Number must be Numeric...";

    /**
     * Reported when neither key was supplied, from {@code app/cbl/COTRN02C.cbl} line 226. Final
     * arm of the two-key lookup branch, reached only when both the account key and the card
     * number are absent.
     */
    public static final String MESSAGE_KEY_NOT_ENTERED =
            "Account or Card Number must be entered...";

    // ---- The eleven emptiness checks, in the order the legacy branch evaluates them ----

    /** First emptiness check, from {@code app/cbl/COTRN02C.cbl} line 254. */
    public static final String MESSAGE_TYPE_CODE_EMPTY = "Type CD can NOT be empty...";

    /** Second emptiness check, from {@code app/cbl/COTRN02C.cbl} line 260. */
    public static final String MESSAGE_CATEGORY_CODE_EMPTY = "Category CD can NOT be empty...";

    /** Third emptiness check, from {@code app/cbl/COTRN02C.cbl} line 266. */
    public static final String MESSAGE_SOURCE_EMPTY = "Source can NOT be empty...";

    /** Fourth emptiness check, from {@code app/cbl/COTRN02C.cbl} line 272. */
    public static final String MESSAGE_DESCRIPTION_EMPTY = "Description can NOT be empty...";

    /** Fifth emptiness check, from {@code app/cbl/COTRN02C.cbl} line 278. */
    public static final String MESSAGE_AMOUNT_EMPTY = "Amount can NOT be empty...";

    /** Sixth emptiness check, from {@code app/cbl/COTRN02C.cbl} line 284. */
    public static final String MESSAGE_ORIGINATION_DATE_EMPTY = "Orig Date can NOT be empty...";

    /** Seventh emptiness check, from {@code app/cbl/COTRN02C.cbl} line 290. */
    public static final String MESSAGE_PROCESSING_DATE_EMPTY = "Proc Date can NOT be empty...";

    /** Eighth emptiness check, from {@code app/cbl/COTRN02C.cbl} line 296. */
    public static final String MESSAGE_MERCHANT_ID_EMPTY = "Merchant ID can NOT be empty...";

    /** Ninth emptiness check, from {@code app/cbl/COTRN02C.cbl} line 302. */
    public static final String MESSAGE_MERCHANT_NAME_EMPTY = "Merchant Name can NOT be empty...";

    /** Tenth emptiness check, from {@code app/cbl/COTRN02C.cbl} line 308. */
    public static final String MESSAGE_MERCHANT_CITY_EMPTY = "Merchant City can NOT be empty...";

    /** Eleventh emptiness check, from {@code app/cbl/COTRN02C.cbl} line 314. */
    public static final String MESSAGE_MERCHANT_ZIP_EMPTY = "Merchant Zip can NOT be empty...";

    // ---- Numeric checks ----

    /**
     * Reported when the transaction type code is not numeric, from
     * {@code app/cbl/COTRN02C.cbl} line 325. The code is still carried as text by
     * {@link #typeCode()}, because the field is a fixed-width code whose leading zeros are part
     * of the record image; only the check is numeric.
     */
    public static final String MESSAGE_TYPE_CODE_NOT_NUMERIC = "Type CD must be Numeric...";

    /**
     * Reported when the transaction category code is not numeric, from
     * {@code app/cbl/COTRN02C.cbl} line 331. As above, the code itself stays text.
     */
    public static final String MESSAGE_CATEGORY_CODE_NOT_NUMERIC =
            "Category CD must be Numeric...";

    /**
     * Reported when the merchant identifier is not numeric, from
     * {@code app/cbl/COTRN02C.cbl} line 432. This check sits outside the branch that produces the
     * other numeric texts and is evaluated after both date-validity checks.
     */
    public static final String MESSAGE_MERCHANT_ID_NOT_NUMERIC = "Merchant ID must be Numeric...";

    // ---- Shape checks ----

    /**
     * Reported when the amount does not match the screen's presentation shape, from
     * {@code app/cbl/COTRN02C.cbl} line 345.
     *
     * <p>The shape named inside this text is the twelve-character edited form the map displays,
     * and it is part of the message and of nothing else. It is never carried in place of
     * {@link #amount()}, which is an exact decimal, and it is never used to format one.
     */
    public static final String MESSAGE_AMOUNT_FORMAT = "Amount should be in format -99999999.99";

    /**
     * Reported when the origination date does not match the screen's ten-character shape, from
     * {@code app/cbl/COTRN02C.cbl} line 360. A shape check only; calendar validity is checked
     * separately and reported by {@link #MESSAGE_ORIGINATION_DATE_INVALID}.
     */
    public static final String MESSAGE_ORIGINATION_DATE_FORMAT =
            "Orig Date should be in format YYYY-MM-DD";

    /**
     * Reported when the processing date does not match the screen's ten-character shape, from
     * {@code app/cbl/COTRN02C.cbl} line 375.
     */
    public static final String MESSAGE_PROCESSING_DATE_FORMAT =
            "Proc Date should be in format YYYY-MM-DD";

    // ---- Calendar-validity checks, delegated to the shared date utility ----

    /**
     * Reported when the origination date passes the shape check but is not a real calendar date,
     * from {@code app/cbl/COTRN02C.cbl} line 401, following the utility invocation at line 393.
     *
     * <p>Two details of this text are contractual and easy to lose: the word describing the date
     * is lower case, unlike the capitalised words around it, and the separator after the field
     * label is a hyphen with a single space on each side.
     */
    public static final String MESSAGE_ORIGINATION_DATE_INVALID = "Orig Date - Not a valid date...";

    /**
     * Reported when the processing date passes the shape check but is not a real calendar date,
     * from {@code app/cbl/COTRN02C.cbl} line 421, following the utility invocation at line 413.
     * The same lower-case word and spaced separator apply.
     */
    public static final String MESSAGE_PROCESSING_DATE_INVALID = "Proc Date - Not a valid date...";

    // ---- Cross-reference lookup outcomes ----

    /**
     * Reported when the supplied account key has no cross-reference entry, from
     * {@code app/cbl/COTRN02C.cbl} line 593.
     */
    public static final String MESSAGE_ACCOUNT_ID_NOT_FOUND = "Account ID NOT found...";

    /**
     * Reported when the account cross-reference lookup fails for any reason other than a missing
     * entry, from {@code app/cbl/COTRN02C.cbl} line 600.
     *
     * <p>Two details distinguish this from its card-number counterpart and must not be
     * normalised: the account word is abbreviated, and the cross-reference is named with its
     * alternate-index qualifier because this lookup goes through the alternate index while the
     * card-number lookup goes through the base cross-reference.
     *
     * <p>The legacy program writes the underlying response and reason codes to its console on
     * this path, at line 598. Those codes are diagnostic detail and this response deliberately
     * carries neither them nor any other internal detail.
     */
    public static final String MESSAGE_ACCOUNT_XREF_LOOKUP_FAILED =
            "Unable to lookup Acct in XREF AIX file...";

    /**
     * Reported when the supplied card number has no cross-reference entry, from
     * {@code app/cbl/COTRN02C.cbl} line 626.
     */
    public static final String MESSAGE_CARD_NUMBER_NOT_FOUND = "Card Number NOT found...";

    /**
     * Reported when the card cross-reference lookup fails for any reason other than a missing
     * entry, from {@code app/cbl/COTRN02C.cbl} line 633.
     *
     * <p>The card is named in its abbreviated, symbol-suffixed form and the cross-reference is
     * named without an alternate-index qualifier, both differing from the account counterpart
     * above. As there, the response and reason codes the program writes to its console at
     * line 631 are not carried here.
     */
    public static final String MESSAGE_CARD_XREF_LOOKUP_FAILED =
            "Unable to lookup Card # in XREF file...";

    /**
     * Reported when positioning in the transaction file finds no entry, from
     * {@code app/cbl/COTRN02C.cbl} line 657.
     *
     * <p>Reachable on the copy-last-transaction path, which positions past the end of the file
     * and reads backwards before any write is attempted.
     */
    public static final String MESSAGE_TRANSACTION_ID_NOT_FOUND = "Transaction ID NOT found...";

    /**
     * Reported when reading the transaction file fails for any reason other than a missing entry,
     * from {@code app/cbl/COTRN02C.cbl} lines 664 and 693.
     *
     * <p>One text emitted from two sites &mdash; the positioning path and the backward-read path
     * &mdash; so it is declared once. Declaring it twice would imply the two sites can be told
     * apart from the operator's side, and they cannot.
     */
    public static final String MESSAGE_TRANSACTION_LOOKUP_FAILED =
            "Unable to lookup Transaction...";

    // ---- Write outcomes ----

    /**
     * First fragment of the success text, from {@code app/cbl/COTRN02C.cbl} line 728.
     *
     * <p><strong>The trailing space is part of the fragment.</strong> The legacy program builds
     * the success text from this fragment, then {@link #MESSAGE_SUCCESS_ID_LABEL}, then the new
     * transaction identifier, then {@link #MESSAGE_SUCCESS_TERMINATOR}, taking each of the three
     * literals whole. Because this one ends with a space and the next one begins with a space, the
     * assembled text contains <strong>two consecutive spaces</strong> after the first full stop:
     * {@code "Transaction added successfully.  Your Tran ID is 0000000000000001."} is the exact
     * text a first-ever successful add produces.
     *
     * <p>The two adjacent spaces are an estate idiom rather than a defect &mdash; the bill-payment
     * screen assembles its success text the same way &mdash; and byte equivalence depends on them,
     * so the three fragments are declared separately and are never joined here. Joining them into
     * one constant, or applying any trimming or whitespace collapsing to the result, would break
     * the contract. Assembly belongs to the service that owns this screen's behaviour.
     */
    public static final String MESSAGE_SUCCESS_PREFIX = "Transaction added successfully. ";

    /**
     * Second fragment of the success text, from {@code app/cbl/COTRN02C.cbl} line 730.
     *
     * <p><strong>Both the leading and the trailing space are part of the fragment.</strong> The
     * leading one is what, together with the trailing space of {@link #MESSAGE_SUCCESS_PREFIX},
     * produces the two consecutive spaces described there. The trailing one separates the label
     * from the identifier that follows it.
     */
    public static final String MESSAGE_SUCCESS_ID_LABEL = " Your Tran ID is ";

    /**
     * Final fragment of the success text, from {@code app/cbl/COTRN02C.cbl} line 732: the full
     * stop that closes the sentence after the new transaction identifier.
     *
     * <p>Declared rather than written inline at the assembly site so that all three fragments of
     * one external contract text live in one place and can be verified together.
     *
     * <p><strong>How the identifier joins them.</strong> The legacy assembly takes the two labels
     * and this terminator whole but takes the identifier only up to its first space. Since the
     * identifier is a sixteen-character all-digit key, every character of it reaches the message.
     * The distinction still matters and is recorded here: the <em>message</em> drops any trailing
     * space of the key, while {@link #newTransactionId()} keeps the field at its full sixteen
     * characters. The two are different views of the same value and neither is derived from the
     * other by this type.
     */
    public static final String MESSAGE_SUCCESS_TERMINATOR = ".";

    /**
     * Reported when the write is refused because the key already exists, from
     * {@code app/cbl/COTRN02C.cbl} line 738.
     *
     * <p>The verb is singular, which is a deviation from ordinary English and is reproduced
     * exactly because the text is an external contract.
     *
     * <p>This is the arm that proves focus has to travel independently of the failing field: the
     * text is about the transaction identifier, yet line 740 places focus on the account-id input.
     * A response carrying this text therefore sets {@link #focusScreenFieldId()} to the account-id
     * field, not to a transaction-identifier field &mdash; which the screen has no input item for
     * in any case.
     */
    public static final String MESSAGE_DUPLICATE_TRANSACTION_ID = "Tran ID already exist...";

    /**
     * Reported when the write fails for any reason other than an existing key, from
     * {@code app/cbl/COTRN02C.cbl} line 745.
     *
     * <p>The legacy program writes the underlying response and reason codes to its console first,
     * at line 743. Those codes name internal storage conditions and this response carries neither
     * them nor any stack detail, failure class name, internal path, query text, schema name or
     * table name. The operator sees this text and the diagnostic detail stays in the module's own
     * structured diagnostic output.
     */
    public static final String MESSAGE_ADD_FAILED = "Unable to Add Transaction...";

    /**
     * Normalises the field-error collection so that the component is never {@code null}, never
     * aliased to caller-owned state and never mutable.
     *
     * <p>A {@code null} collection becomes the empty immutable list rather than being stored, so
     * every accessor and every serialized payload sees a usable collection and a client never has
     * to test for absence. A non-{@code null} collection is defensively copied with
     * {@link List#copyOf(java.util.Collection)}, which both detaches it from the caller and
     * rejects a {@code null} element &mdash; an entry with no state would be meaningless, and
     * dropping it silently would hide an error the client has to show. This mirrors
     * {@link ErrorResponse} exactly, so the two bodies normalise the same way.
     *
     * <p><strong>Nothing else is touched.</strong> Every other component is stored precisely as
     * supplied, including {@code null} and including every leading and trailing space. That is
     * deliberate and load bearing: the legacy fields these components derive from are fixed-width
     * and space-significant, the success path supplies blanks for all fourteen echoed values, and
     * the two success fragments carry contractual spaces at their joins. No component is trimmed,
     * padded, case-folded, scaled, rounded, reformatted or canonicalised here, and this
     * constructor performs no defaulting and no business logic of any kind.
     *
     * <p><strong>The one thing it refuses.</strong> The amount is checked against the decimal shape
     * of the record field it represents, and a value of the wrong shape is rejected rather than
     * repaired. This is a refusal and not a normalisation, and the distinction is the whole point:
     * nothing here rescales, rounds, truncates or reformats the amount, so the value a producer
     * published still crosses this boundary at exactly the scale it published it at. What changes is
     * that a producer which published the wrong scale now finds out at construction instead of
     * emitting a payload whose precision silently contradicts the schema this type publishes. A
     * {@code null} amount is accepted untouched, because the success path deliberately blanks every
     * echoed value.
     *
     * @throws IllegalArgumentException if {@code amount} carries a scale other than
     *     {@link #AMOUNT_SCALE} or needs more than {@link #AMOUNT_INTEGER_DIGITS} integer digits
     */
    public TransactionAddResponse {
        fieldErrors = (fieldErrors == null) ? List.of() : List.copyOf(fieldErrors);
        requireRecordShape(amount);
    }

    /**
     * The number of decimal places the amount carries, from the two decimal places of
     * {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy} line 10.
     *
     * <p>Public because it is part of the numeric contract rather than an implementation choice: the
     * service that builds a response, and the tests that check one, need a single authority for the
     * figure instead of each restating it. Declaring it here and not sharing it with the
     * transaction-list contract is deliberate for the same reason the widths are not shared - each
     * map is its own contract - even though both happen to derive from the same record field.
     */
    public static final int AMOUNT_SCALE = 2;

    /** Width of the transaction-add screen's edited amount item {@code TRNAMT}. */
    public static final int AMOUNT_ENTERED_LENGTH = 12;

    /**
     * The number of integer digits the amount may carry, from the nine integer digits of the same
     * record field. With {@link #AMOUNT_SCALE} this gives the total precision of eleven that the
     * relational column declares.
     */
    public static final int AMOUNT_INTEGER_DIGITS = 9;

    /**
     * Confirms that the amount has the decimal shape of the record field it represents.
     *
     * <p>Reads only the amount's own scale and precision. It performs no arithmetic on the value,
     * does not re-scale it, does not round it and does not format it, so it cannot change what the
     * client receives. The failure text names the offending scale or digit count and never the amount
     * itself, so a rejected value cannot reach a log through the diagnostic that reports it.
     *
     * @param amount the amount to check, or {@code null} on the success path where every echoed value
     *     is deliberately blank
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
     * Tests whether this response carries any per-field error.
     *
     * <p>A convenience test over {@link #fieldErrors()} for callers that only need to branch on
     * presence, such as a controller choosing a status code. It is <em>not</em> a substitute for
     * inspecting the individual states: a caller that has to tell an operator what to do must
     * read {@link ErrorResponse.FieldError#state()} on each entry, because a blank field and a
     * badly filled field need different remedies.
     *
     * <p>It is also <em>not</em> a substitute for {@link #generalError()}. The two are
     * independent: a response can report a general failure with no per-field error at all, which
     * is exactly what every arm of the write-outcome branch produces, and the first submission of
     * a screen reports a summary message with no per-field error either.
     *
     * @return {@code true} when at least one field error is present
     */
    public boolean hasFieldErrors() {
        return !fieldErrors.isEmpty();
    }

    /**
     * Returns a diagnostic representation that identifies the response and discloses nothing about
     * the cardholder, the amount or the merchant.
     *
     * <p><strong>Why the implicit rendering could not stand.</strong> The generated rendering of a
     * record prints every component, and two of this one's components are a primary account number
     * and an account key. This type is constructed on every add attempt, so the generated form
     * would place cardholder data one interpolation away from every log line, assertion message
     * and diagnostic dump on the busiest write path in the online tier.
     *
     * <p><strong>What is retained.</strong> The new transaction identifier, the type and category
     * codes, the transaction source, both ten-character dates, the confirmation answer, all six
     * header items, the summary message, the general-error flag, the field errors, the focus hint
     * and the route. That set is what a diagnostic actually needs: it says which attempt this was,
     * how it was classified, what the operator was told, what the client should do next and which
     * field to look at, and none of it is cardholder data or personal data. The field errors carry
     * only field labels and states, and the navigation state renders itself with its own
     * identifying values withheld.
     *
     * <p><strong>What is withheld, and why the list is wider than the card number alone.</strong>
     * The card number is withheld because it is a primary account number, and the account key
     * because it identifies the same cardholder. The amount, the description and the four merchant
     * components are withheld too, because together with the retained transaction identifier they
     * reconstruct what a specific cardholder spent and where, which no diagnostic channel needs in
     * order to be useful. Fail-closed is the right default: the retained set was chosen because it
     * is sufficient, not because the rest happened to look harmless.
     *
     * <p>{@code equals} and {@code hashCode} remain exactly as the record contract generates them.
     * They compare every component by value and emit nothing, so byte-for-byte fixture comparison
     * and value-based assertions are unaffected by anything withheld here.
     *
     * @return the response identification, with every cardholder-bearing component replaced by a
     *     fixed stand-in
     */
    @Override
    public String toString() {
        return "TransactionAddResponse["
                + "newTransactionId=" + newTransactionId
                + ", accountId=" + REDACTION_PLACEHOLDER
                + ", cardNumber=" + REDACTION_PLACEHOLDER
                + ", typeCode=" + typeCode
                + ", categoryCode=" + categoryCode
                + ", source=" + source
                + ", description=" + REDACTION_PLACEHOLDER
                + ", amountEntered=" + REDACTION_PLACEHOLDER
                + ", amount=" + REDACTION_PLACEHOLDER
                + ", originationDate=" + originationDate
                + ", processingDate=" + processingDate
                + ", merchantId=" + REDACTION_PLACEHOLDER
                + ", merchantName=" + REDACTION_PLACEHOLDER
                + ", merchantCity=" + REDACTION_PLACEHOLDER
                + ", merchantZip=" + REDACTION_PLACEHOLDER
                + ", confirmationFlag=" + confirmationFlag
                + ", transactionName=" + transactionName
                + ", title01=" + title01
                + ", currentDate=" + currentDate
                + ", programName=" + programName
                + ", title02=" + title02
                + ", currentTime=" + currentTime
                + ", message=" + message
                + ", generalError=" + generalError
                + ", fieldErrors=" + fieldErrors
                + ", focusScreenFieldId=" + focusScreenFieldId
                + ", nextRoute=" + nextRoute
                + ", navigationContext=" + navigationContext
                + "]";
    }
}
