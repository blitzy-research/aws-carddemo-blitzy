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
import java.math.BigDecimal;

/**
 * Immutable response contract for the CardDemo online bill-payment transaction, which pays an
 * account balance in full and posts one transaction recording that payment.
 *
 * <p>The legacy antecedent is the program {@code app/cbl/COBIL00C.cbl} &mdash; 572 lines and
 * sixteen paragraphs, registered as transaction {@code CB00} &mdash; together with the screen it
 * drives, whose layout is declared in {@code app/bms/COBIL00.bms} and whose field-level contract is
 * declared in the generated symbolic map {@code app/cpy-bms/COBIL00.CPY}: the input group at line 17
 * and the output group that redefines it at line 79. Every component below is either one of that
 * map's ten named fields, one value the program computes and displays, or one piece of state a
 * stateless client has to be handed back. The provenance of the whole translation is checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec} and upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19, which is the stamp carried in this member's
 * own trailer comment at line 571.
 *
 * <p><strong>The two balances are the substance of this response, and they mean different
 * things.</strong> {@code currentBalance} is the <em>pre-payment</em> balance: the program moves the
 * account's balance to the screen at lines 193 to 194, <em>before</em> any payment is posted, so that
 * the operator sees the amount that is about to be paid and can decide whether to confirm it.
 * {@code paymentBalance} is the <em>resulting</em> balance, the balance the account is left holding.
 * On a successful payment that value is exactly zero, because the amount paid is always the whole
 * current balance: the program offers no partial-payment field, no amount input and no amount
 * validation, so the only payable amount is the balance itself. Both values are carried, never
 * derived: this type performs no arithmetic whatsoever - no difference, no comparison, no zero test
 * and no sign test - and it does not assert that the resulting balance is zero. Its two accessors
 * simply return what was handed to them. The account read, the balance computation and the account
 * rewrite all belong to the service, and the ordering among them belongs there too.
 *
 * <p><strong>Money is a {@link BigDecimal} at scale two, and nothing here scales it.</strong> The
 * underlying field is {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy} line 7 -
 * zoned decimal under {@code USAGE DISPLAY}, two implied decimal places, no packed representation
 * anywhere in the path - and the persisted column is a fixed-point numeric of scale two. An
 * approximate binary numeric type is therefore inadmissible for either component, and so is text:
 * the former cannot represent a cent exactly and the latter would smuggle a presentation decision
 * into the contract. The scale itself is applied in exactly one place in the module, the
 * zoned-decimal codec, and the rounding mode it applies is truncation toward zero rather than either
 * half-way policy. That is not a stylistic preference: a search for the rounding keyword across all
 * twenty-eight programs and all twenty-eight copybooks of the estate returns zero occurrences, and a
 * COBOL arithmetic store without it truncates, so a conventional half-even policy would differ by one
 * cent on a large share of computed values and the difference would be invisible to a test written
 * under the same wrong assumption. See decision log entries D-02 and DL-013. Consequently this type
 * contains no scaling call, no rounding mode, no decimal-format object and no numeric formatting of
 * any kind, and a caller must not add one: a value arrives already at its contractual scale or it
 * never reaches this boundary at all.
 *
 * <p><strong>The map's balance display width is deliberately not modelled.</strong> The screen field
 * that shows the balance is a fixed-width character field, and the program moves an edited numeric
 * work field into it. That width is a property of a 3270 screen, not of a monetary value: it says how
 * many columns the terminal reserved, and it says nothing about scale, precision or significant
 * digits. Exposing it here - as a length constraint, as a precision, or as a formatting hint - would
 * turn a terminal geometry detail into part of a REST contract and would mislead every client that
 * read it. The edited screen mask that produced those characters is likewise absent: a client
 * receives a decimal number and formats it however it renders.
 *
 * <p><strong>Identifiers are text, never numbers.</strong> The account identifier is declared as
 * eleven digits and its leading zeros are contractual, so an account of {@code "00000000001"} must
 * round-trip as those eleven characters and never as {@code "1"}; the same
 * reasoning applies to the sixteen-character transaction identifier. {@code newTransactionId} is the
 * identifier of the transaction the payment posted, and it has no field on the screen at all - the
 * legacy carries it only inside the assembled success message. It is produced server-side as the
 * highest existing key plus one, browsed backward from the high value and seeded so that the first
 * identifier issued against an empty table is {@code "0000000000000001"} rather than {@code "1"}.
 * That generation is entirely a service concern and is recorded in decision log entries D-28 and
 * DL-018: no database sequence is used, because a sequence diverges permanently after the first gap
 * and a rolled-back payment guarantees a gap. Nothing here generates, increments, parses or reformats
 * an identifier, and no surrogate key exists anywhere in the module (D-29 and DL-017).
 *
 * <p><strong>The confirmation answer is one character of text, not a boolean.</strong> The program
 * distinguishes seven cases in a single evaluation at lines 173 to 191: an upper-case and a lower-case
 * affirmative, an upper-case and a lower-case negative, a blank field, an untyped field, and anything
 * else - and only the last of the seven is rejected, with its own message at line 187. A boolean has
 * room for two of them and no room at all for the one that has to be reportable, namely a character
 * the operator typed that is none of the accepted answers. Carrying the raw character preserves every
 * case, including which letter case was typed, and leaves the decision to the service where the
 * ordered cascade lives.
 *
 * <p><strong>There is exactly one summary message, and an explicit flag beside it.</strong> The
 * legacy screen has a single message field, eighty characters wide in working storage and
 * seventy-eight on the map, and every arm of the program writes one text into it. The fourteen texts
 * the program can emit are published below as constants so that the service selects contract text
 * rather than inventing it; each names its own source line. Two of them are the halves of the success
 * message and are deliberately left unjoined - see {@link #MSG_PAYMENT_SUCCESSFUL_PREFIX} and
 * {@link #MSG_TRANSACTION_ID_FRAGMENT} - because each half contributes a space at the join, so the
 * assembled text carries two consecutive spaces after its first period. That two-space run is
 * reproduced, never tidied: it is a byte of an external contract, and the same pattern occurs on the
 * transaction-add screen, which makes it an estate idiom rather than a local slip. The assembly
 * itself belongs to the service, which appends the identifier with its trailing spaces dropped and
 * then a period.
 *
 * <p><strong>The general-error flag is explicit and must never be inferred from the message.</strong>
 * The program carries a separate one-character error flag with two condition names at lines 43 to 45,
 * and two paths prove the flag cannot be derived from message presence. At line 237 the program
 * writes the confirmation prompt into the message field and leaves the error flag off, because asking
 * the operator to confirm is not a failure. At lines 525 to 531 it writes the success text into the
 * same field with the flag still off. A client that inferred failure from a non-null message would
 * therefore report two successes as errors. {@code paymentAccepted} is equally explicit: it is true
 * only once the transaction write has returned normally at line 523 and the account rewrite has
 * completed, and it is emphatically not the confirmation answer - the operator's yes merely selects
 * the posting path, and every posting step can still fail after it.
 *
 * <p><strong>A successful response legitimately carries blank or absent echoed values.</strong> On a
 * successful write the program clears every input field before redisplaying the screen: line 524
 * invokes the initialise-all-fields paragraph, which blanks the account identifier, the displayed
 * balance and the confirmation answer at lines 560 to 566. The success message and the new
 * transaction identifier are then written into the cleared screen. Reproducing that faithfully means
 * no component may be required to be present, which is one of the reasons no presence constraint
 * appears anywhere below.
 *
 * <p><strong>Documented divergence: the legacy sends the screen twice on a successful payment; this
 * contract returns one response.</strong> In the legacy flow the write-success arm sends the screen at
 * line 532 and the enclosing paragraph sends it again at line 242, which a 3270 terminal simply
 * repaints. A REST endpoint has no repaint semantics and returning two bodies for one request is not
 * expressible, so one response carries the final state. Nothing here models a second response, a
 * resend flag or a duplicate-send indicator. This divergence is recorded in
 * {@code docs/decision-log.md}.
 *
 * <p><strong>What is deliberately absent, and why each omission is a decision.</strong> No terminal
 * artefact appears: none of the generated per-field control items of the symbolic map, not the
 * twelve-byte prefix filler at line 18 of the copybook, no map coordinate, no field attribute, no
 * highlight, no colour value and no cursor position. The screen's colours - the success text in green
 * and error text in red - are presentation decisions of a terminal and have no place in a machine
 * contract; where severity has to travel it travels as the semantic {@code generalError} flag. The
 * focus hint is an identity and nothing more, as described on {@link #fieldToFocus()}. None of the
 * fixed values the service stamps into the posted transaction appear either - its type, category,
 * source channel, description, merchant identity or timestamps - because they are properties of the
 * posted record rather than of this response, and the two twenty-six-character timestamp forms are
 * constructed by the service against the module's single clock. There is accordingly no date or time
 * type in this file at all. Neither is there any raw platform response or reason code: the legacy
 * writes those to its diagnostic channel on six failure arms, at lines 366, 397, 430, 461, 490 and
 * 541, and a REST body must never carry them, nor a stack trace, a failure class name, an internal
 * path, a query fragment or a schema name. Decision log entry D-16 states the general form of that
 * rule. Finally there is no route table, no route enumeration and no dispatch: the route is opaque
 * text, and the navigation vocabulary belongs to the navigation service.
 *
 * <p><strong>The shared screen work area is absent, and that is a measured omission rather than an
 * oversight.</strong> Several sibling response types in this package carry one, so its absence here
 * invites the question. The copybook that declares that work area is included by exactly five
 * programs - the account view and update pair together with the three card programs - and those same
 * five are the only ones that include the shared attention-key copybook and the only ones that arm an
 * abend handler. This program includes neither copybook and arms no handler: its ten inclusions are
 * the communication area, its own symbolic map, the title, date and message copybooks, the account,
 * cross-reference and transaction records, and the two platform constant copybooks. Adopting the work
 * area here would hand this transaction state the legacy never gave it, and would imply an
 * attention-key and abend surface it does not have.
 *
 * <p><strong>Validation policy.</strong> This is a response, so the only constraint used is a maximum
 * length on the text components, and a maximum length measures without altering: it never trims,
 * pads, re-cases or normalises, so leading and trailing spaces of a fixed-width legacy value survive
 * it untouched. No presence, blank, pattern, digit, range or assertion constraint appears anywhere.
 * Each would be wrong for a specific, verified reason: every echoed field is deliberately blank on
 * success, a balance may legitimately be zero or negative, the confirmation answer is legitimately
 * blank, and every check the program performs is an ordered cascade that produces one of the message
 * texts below - whereas constraint violations are reported in an unspecified order and would replace
 * that cascade with an arbitrary one.
 *
 * <p><strong>Immutability and rendering.</strong> Every component is a {@code String}, a
 * {@link BigDecimal}, a {@code boolean} or an immutable {@link NavigationContext}, all of them
 * immutable, so an instance is deeply immutable and safe to share across threads. There is no
 * compact constructor because there is nothing for one to do: no defaulting, no normalisation and no
 * validation belong here, and every value crosses this boundary byte for byte. There is no collection
 * component, so no defensive copy is required. Serialization settings are not overridden here and no
 * custom serializer is registered: the module's shared configuration already renders decimals in
 * plain notation and omits null-valued properties, which is what guarantees a scale-two balance is
 * rendered as plain digits and an absent value is simply absent. For the richer error shape - a
 * summary message plus independently flagged fields in the two states missing and invalid - see
 * {@link ErrorResponse}, which the global exception handler produces; per decision log entries D-33
 * and DL-051 that field-level detail appears only on a re-submission.
 *
 * @param accountId the account whose balance was to be paid, echoed from the eleven-character account
 *     identifier field of the map at {@code app/cpy-bms/COBIL00.CPY} line 60, whose width the screen
 *     definition at {@code app/bms/COBIL00.bms} lines 85 to 89 corroborates and the account key
 *     {@code ACCT-ID} at {@code app/cpy/CVACT01Y.cpy} line 5 matches. Text, so its leading
 *     zeros and its eleven-character external width survive exactly; never a numeric type. May be
 *     {@code null} or blank, in particular on a successful payment, because line 524 clears it.
 * @param currentBalance the <strong>pre-payment</strong> balance displayed to the operator, written to
 *     the screen at {@code app/cbl/COBIL00C.cbl} lines 193 to 194 before any payment occurs, from
 *     {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy} line 7. A
 *     {@link BigDecimal} at scale two, already scaled by the codec; never scaled, rounded, formatted
 *     or rearranged here. May be {@code null} when no account was read.
 * @param paymentBalance the <strong>resulting</strong> balance the account is left holding, which is
 *     exactly zero on a successful payment because the amount paid is always the whole current
 *     balance. A {@link BigDecimal} at scale two, carried and never computed here, and not asserted
 *     to be zero. May be {@code null} when no payment was attempted.
 * @param confirm the operator's confirmation answer, echoed from the one-character field of the map at
 *     {@code app/cpy-bms/COBIL00.CPY} line 72, whose width the screen definition at
 *     {@code app/bms/COBIL00.bms} lines 115 to 119 corroborates. One character of text rather than a
 *     boolean, so that a value which is neither an affirmative, a negative nor a blank stays
 *     reportable through
 *     {@link #MSG_INVALID_CONFIRM_VALUE} at line 187, and so that the letter case the operator typed
 *     is preserved. May be {@code null} or blank; line 524 clears it on success.
 * @param newTransactionId the identifier of the transaction the payment posted, sixteen characters
 *     wide as declared at {@code app/cpy/CVTRA05Y.cpy} line 5. It has no field on the screen and
 *     reaches the operator only inside the assembled success message. Produced server-side as the
 *     highest existing key plus one, so the first identifier issued against an empty table is
 *     {@code "0000000000000001"}; never generated, incremented or derived here. May be {@code null}
 *     when no transaction was posted.
 * @param transactionName the transaction code of the screen, echoed from the four-character field of
 *     the map at {@code app/cpy-bms/COBIL00.CPY} line 24 and populated by the header paragraph at
 *     {@code app/cbl/COBIL00C.cbl} line 325. May be {@code null}.
 * @param title01 the first screen title line, echoed from the forty-character field of the map at
 *     {@code app/cpy-bms/COBIL00.CPY} line 30 and populated at {@code app/cbl/COBIL00C.cbl} line 323
 *     from the shared title catalogue. Space-padded in the legacy and carried byte for byte. May be
 *     {@code null}.
 * @param currentDate the date as the screen showed it, echoed from the eight-character field of the
 *     map at {@code app/cpy-bms/COBIL00.CPY} line 36 and populated at {@code app/cbl/COBIL00C.cbl}
 *     line 332. Carried verbatim as the characters the screen displayed; this type holds no date or
 *     time value and performs no formatting. May be {@code null}.
 * @param programName the program name the screen displayed, echoed from the eight-character field of
 *     the map at {@code app/cpy-bms/COBIL00.CPY} line 42 and populated at
 *     {@code app/cbl/COBIL00C.cbl} line 326. May be {@code null}.
 * @param title02 the second screen title line, echoed from the forty-character field of the map at
 *     {@code app/cpy-bms/COBIL00.CPY} line 48 and populated at {@code app/cbl/COBIL00C.cbl} line 324
 *     from the shared title catalogue. May be {@code null}.
 * @param currentTime the time as the screen showed it, echoed from the eight-character field of the
 *     map at {@code app/cpy-bms/COBIL00.CPY} line 54 and populated at {@code app/cbl/COBIL00C.cbl}
 *     line 338. Characters only, exactly as for {@code currentDate}. May be {@code null}.
 * @param errorMessage the single summary message for the whole response, seventy-eight characters
 *     wide as declared for the message field of the map at {@code app/cpy-bms/COBIL00.CPY} line 78.
 *     It carries one of the constants published below, or the assembled success text, and the service
 *     selects it. Not restricted to failures: the confirmation prompt at line 237 and the success text
 *     at lines 527 to 531 both travel here with {@code generalError} false. The program's own message
 *     work area is eighty characters wide, so the map field is the narrower of the two and is
 *     therefore the binding external width; every text published below fits inside it, and so does the
 *     assembled success message, whose two fragments plus a sixteen-character identifier plus a period
 *     total sixty-one characters. Never trimmed and never normalised. May be {@code null} when the
 *     screen showed no message.
 * @param paymentAccepted whether the payment was actually posted, which is true only once the
 *     transaction write returned normally at {@code app/cbl/COBIL00C.cbl} line 523 and the account
 *     rewrite completed. Explicitly not the confirmation answer: the operator's affirmative at line
 *     176 only selects the posting path, and any step after it can still fail.
 * @param generalError whether this response reports a failure, the typed form of the program's own
 *     error flag whose condition names are declared at {@code app/cbl/COBIL00C.cbl} lines 43 to 45.
 *     Explicit by contract and never inferred from {@code errorMessage} being present, because the
 *     confirmation prompt at line 237 and the success text at lines 525 to 531 both set a message
 *     while leaving the flag off.
 * @param fieldToFocus the identity of the screen field input focus belongs on, one of
 *     {@link #ACCOUNT_ID_FIELD_ID} or {@link #CONFIRM_FIELD_ID}. An identity only: it is not a cursor
 *     row or column, not a negative sentinel and not an attribute byte. It travels independently of
 *     which check failed, because the program decides it per arm - the account-identifier input on
 *     every arm except the two that concern the confirmation answer, at lines 189 and 239. Bounded at
 *     seven characters, which is the ceiling a generated symbolic map imposes on a field name: the
 *     generator forms several data names by appending one suffix character to the name, as the length,
 *     flag, attribute and value names of the transaction-name field at
 *     {@code app/cpy-bms/COBIL00.CPY} lines 19 to 24 show, and those data names are capped at eight.
 *     May be {@code null} when the response gives no hint.
 * @param route the declarative next route, carried as opaque text that this type never interprets.
 *     The legacy transferred control to another program directly - to the caller or the main menu on
 *     the back key at lines 129 to 135 - whereas here the client drives the next call and the server
 *     forwards nothing. The route vocabulary belongs to the navigation service; no route table, route
 *     enumeration or dispatch appears here. May be {@code null} when the response nominates no route.
 * @param navigation the client-echoed navigation state, the replacement for the communication area
 *     declared at {@code app/cpy/COCOM01Y.cpy} line 19 that the legacy program received and returned
 *     on every pseudo-conversational turn. Echoed request state, never a server session. May be
 *     {@code null}.
 * @since 1.0.0
 */
public record BillPaymentResponse(

        // Every bound below is the declared width of the legacy field the component derives from,
        // cited to its source line in the matching parameter description above. Each is written as a
        // literal at its point of use rather than through a shared name, because these are
        // independent legacy widths that coincide in places by accident and no other type has any
        // business reading them. A bound measures and never alters, so a space-padded value passes
        // through validation untouched. NEVER place a bound on either BigDecimal: the balance display
        // width on the map is terminal geometry, not a scale, a precision or a formatting hint.
        @Size(max = 11) String accountId,
        BigDecimal currentBalance,
        BigDecimal paymentBalance,
        @Size(max = 1) String confirm,
        @Size(max = 16) String newTransactionId,
        @Size(max = 4) String transactionName,
        @Size(max = 40) String title01,
        @Size(max = 8) String currentDate,
        @Size(max = 8) String programName,
        @Size(max = 40) String title02,
        @Size(max = 8) String currentTime,
        @Size(max = 78) String errorMessage,
        boolean paymentAccepted,
        boolean generalError,
        @Size(max = 7) String fieldToFocus,
        String route,
        NavigationContext navigation) {

    // The canonical constructor generated for this record is intentionally left exactly as generated:
    // no compact constructor, no defaulting, no normalisation and no validation. Every value crosses
    // this boundary byte for byte, which is what lets a blank echoed field, a space-padded title and
    // a scale-two balance reach a client unchanged. Scaling belongs to the zoned-decimal codec,
    // message assembly and flag selection belong to the bill-payment service, and neither may be
    // performed here.

    /**
     * Identity of the account-identifier input field: the name that screen definition
     * {@code app/bms/COBIL00.bms} gives it at lines 85 to 89, and that the symbolic map at
     * {@code app/cpy-bms/COBIL00.CPY} line 60 carries.
     *
     * <p>This is the value {@link #fieldToFocus()} takes on all fourteen arms of the program except
     * the two that concern the confirmation answer: the empty-identifier arm at line 163, the
     * nothing-to-pay arm at line 203, the three not-found arms at lines 363, 394 and 427, the three
     * unable-to-access arms at lines 370, 401 and 434, the three transaction-browse arms at lines 458,
     * 465 and 494, the duplicate and unable-to-add arms at lines 538 and 545, and the cleared screen at
     * line 562.
     *
     * <p>It is an opaque label for correlating a response with the screen it derives from, exactly as
     * in {@link ErrorResponse.FieldError}. A client may ignore it entirely, and it carries no
     * coordinate, no attribute and no terminal value.
     */
    public static final String ACCOUNT_ID_FIELD_ID = "ACTIDIN";

    /**
     * Identity of the confirmation-answer input field: the name that screen definition
     * {@code app/bms/COBIL00.bms} gives it at lines 115 to 119, and that the symbolic map at
     * {@code app/cpy-bms/COBIL00.CPY} line 72 carries.
     *
     * <p>This is the value {@link #fieldToFocus()} takes on exactly two arms - the unaccepted
     * confirmation answer at line 189 and the confirmation prompt at line 239 - which is why the focus
     * hint has to travel as its own component rather than being inferred from whichever check failed.
     * Note that the second of those two arms is not a failure at all: it asks the operator to confirm,
     * with {@code generalError} false.
     */
    public static final String CONFIRM_FIELD_ID = "CONFIRM";

    /**
     * The text shown when the account identifier was left empty, from
     * {@code app/cbl/COBIL00C.cbl} line 161.
     *
     * <p>Note the abbreviated form of the word "account" and the capitalised negative. This program is
     * internally inconsistent about both: it abbreviates here while spelling the word out in
     * {@link #MSG_ACCOUNT_ID_NOT_FOUND}. Both spellings are contract text and neither is regularised,
     * because an operator procedure or a downstream monitor may match on either exactly.
     */
    public static final String MSG_ACCT_ID_EMPTY = "Acct ID can NOT be empty...";

    /**
     * The text shown when the confirmation answer was none of the values the program accepts, from
     * {@code app/cbl/COBIL00C.cbl} line 187.
     *
     * <p>The accepted answers are parenthesised in the text exactly as the screen shows them. This is
     * the message that makes a one-character text component necessary rather than a boolean: a boolean
     * has no representation for the value that produces this text.
     */
    public static final String MSG_INVALID_CONFIRM_VALUE = "Invalid value. Valid values are (Y/N)...";

    /**
     * The text shown when the account has no payable balance, from {@code app/cbl/COBIL00C.cbl}
     * line 201.
     *
     * <p>The program emits it when the balance is not positive and an account identifier was supplied,
     * which is the only balance test in the transaction. That test belongs to the service; this
     * constant is only the text it selects.
     */
    public static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

    /**
     * The text that asks the operator to confirm the payment, from {@code app/cbl/COBIL00C.cbl}
     * line 237.
     *
     * <p>Lower-case in the middle of the sentence, exactly as the source writes it. This text travels
     * with {@code generalError} false and {@code paymentAccepted} false: it is a prompt, not a failure,
     * and it is the first of the two proofs that the error flag cannot be inferred from a message being
     * present.
     */
    public static final String MSG_CONFIRM_BILL_PAYMENT = "Confirm to make a bill payment...";

    /**
     * The text shown when a keyed read found no matching record, from {@code app/cbl/COBIL00C.cbl}
     * lines 361, 392 and 425.
     *
     * <p>Declared at three separate sites - the account read, the account rewrite and the
     * cross-reference read - with identical text, so one constant serves all three. Here the word
     * "account" is spelled out, unlike the abbreviation in {@link #MSG_ACCT_ID_EMPTY}; the
     * inconsistency is the source's and is preserved.
     */
    public static final String MSG_ACCOUNT_ID_NOT_FOUND = "Account ID NOT found...";

    /**
     * The text shown when the account read failed for a reason other than not-found, from
     * {@code app/cbl/COBIL00C.cbl} line 368.
     *
     * <p>The legacy also writes the platform response and reason codes to its diagnostic channel on
     * this arm, at line 366. Those codes stay out of the response body: this text is all the client
     * receives, per decision log entry D-16.
     */
    public static final String MSG_UNABLE_TO_LOOKUP_ACCOUNT = "Unable to lookup Account...";

    /**
     * The text shown when the account rewrite failed for a reason other than not-found, from
     * {@code app/cbl/COBIL00C.cbl} line 399.
     *
     * <p>Note the capitalised verb, which the two lookup texts do not have. As with the lookup arm,
     * the platform codes the legacy writes at line 397 are not carried into the response.
     */
    public static final String MSG_UNABLE_TO_UPDATE_ACCOUNT = "Unable to Update Account...";

    /**
     * The text shown when the card cross-reference read failed for a reason other than not-found, from
     * {@code app/cbl/COBIL00C.cbl} line 432.
     *
     * <p>The upper-case naming of the cross-reference alternate index is part of the text and is
     * reproduced as-is, even though the target reaches the same data through a repository finder over
     * an ordinary index rather than through an alternate-index path. The wording describes what the
     * operator was told, not how the data is now reached.
     */
    public static final String MSG_UNABLE_TO_LOOKUP_XREF_AIX = "Unable to lookup XREF AIX file...";

    /**
     * The text shown when positioning the transaction browse reported nothing found, from
     * {@code app/cbl/COBIL00C.cbl} line 456.
     *
     * <p>This text is the easiest of the fourteen to overlook, because it has exactly one site while
     * the two arms surrounding it in the same browse share {@link #MSG_UNABLE_TO_LOOKUP_TRANSACTION}.
     * It is published so that the service selects contract text on that arm too rather than inlining a
     * string. Note that it reports a transaction rather than an account as not found, which
     * distinguishes it from {@link #MSG_ACCOUNT_ID_NOT_FOUND}, and that the word for the posted record
     * is spelled out here as it is in {@link #MSG_TRANSACTION_ID_FRAGMENT} rather than abbreviated as
     * it is in {@link #MSG_TRAN_ID_ALREADY_EXIST}.
     */
    public static final String MSG_TRANSACTION_ID_NOT_FOUND = "Transaction ID NOT found...";

    /**
     * The text shown when reading the transaction file failed for a reason other than end-of-file, from
     * {@code app/cbl/COBIL00C.cbl} lines 463 and 492.
     *
     * <p>Declared at two sites - positioning the browse and reading backward through it - with
     * identical text, so one constant serves both. Reaching the end of the file is <em>not</em> one of
     * those failures: the legacy treats it as the empty-file case and seeds the identifier instead of
     * reporting anything, which is how the first payment against an empty table obtains its identifier.
     */
    public static final String MSG_UNABLE_TO_LOOKUP_TRANSACTION = "Unable to lookup Transaction...";

    /**
     * The first fragment of the success message, from {@code app/cbl/COBIL00C.cbl} line 527, ending in
     * a <strong>trailing space that is part of the literal</strong>.
     *
     * <p><strong>Do not join this fragment to {@link #MSG_TRANSACTION_ID_FRAGMENT} here, and do not
     * trim either of them.</strong> The two are declared separately because that is how the source
     * declares them, and because each contributes a space at the join: this one ends with a space and
     * the next one begins with a space, so the assembled text carries <em>two consecutive spaces</em>
     * after its first period. The service assembles the whole text by appending this fragment, then
     * {@link #MSG_TRANSACTION_ID_FRAGMENT}, then the sixteen-character identifier with its trailing
     * spaces dropped, then a period - the source delimits the identifier by space at line 529 and
     * appends the period at line 530.
     *
     * <p>The two-space run is reproduced byte for byte and never collapsed. It is not a defect to be
     * tidied: the transaction-add screen composes its own success text the same way, which makes it an
     * estate idiom, and byte equivalence is asserted on the exact characters. Collapsing it, trimming
     * either fragment, or pre-joining them into one constant would each break that equivalence.
     */
    public static final String MSG_PAYMENT_SUCCESSFUL_PREFIX = "Payment successful. ";

    /**
     * The second fragment of the success message, from {@code app/cbl/COBIL00C.cbl} line 528, with
     * <strong>both a leading and a trailing space that are part of the literal</strong>.
     *
     * <p>The word for the posted record is <strong>spelled out</strong> here. The structurally
     * identical success message on the transaction-add screen abbreviates it instead, so the two texts
     * differ for the same concept. <strong>They must never be unified and this constant must never be
     * shared with, or derived from, the transaction-add response</strong>: sharing one constant between
     * the two would silently break whichever screen did not own it. For the same reason the
     * abbreviation is not introduced here, and it is not expanded in
     * {@link #MSG_TRAN_ID_ALREADY_EXIST}, which abbreviates it within this very program.
     *
     * <p>The leading space is what pairs with the trailing space of
     * {@link #MSG_PAYMENT_SUCCESSFUL_PREFIX} to produce the two-space run described there. The trailing
     * space is what separates the fragment from the identifier that follows it.
     */
    public static final String MSG_TRANSACTION_ID_FRAGMENT = " Your Transaction ID is ";

    /**
     * The text shown when the transaction write reported a duplicate key or duplicate record, from
     * {@code app/cbl/COBIL00C.cbl} line 536.
     *
     * <p>Two source oddities are preserved exactly. The word for the posted record is
     * <strong>abbreviated</strong> here while {@link #MSG_TRANSACTION_ID_FRAGMENT} spells it out in the
     * same program, and the verb is in the <strong>singular-agreement form</strong> the source uses
     * rather than the grammatically expected one. Neither is corrected: both are contract text.
     */
    public static final String MSG_TRAN_ID_ALREADY_EXIST = "Tran ID already exist...";

    /**
     * The text shown when the transaction write failed for a reason other than a duplicate, from
     * {@code app/cbl/COBIL00C.cbl} line 543.
     *
     * <p>The words naming the payment appear here in <strong>mixed case as two words</strong>, with the
     * second beginning in lower case - a form that occurs nowhere else in the program, which elsewhere
     * writes the same concept either in upper case or in lower case. It is reproduced exactly. As on
     * the other access-failure arms, the platform codes the legacy writes at line 541 are not carried
     * into the response.
     */
    public static final String MSG_UNABLE_TO_ADD_BILL_PAY_TRANSACTION =
            "Unable to Add Bill pay Transaction...";

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of each account-identifying and monetary
     * component.
     *
     * <p>A constant rather than any transformation of the value, so nothing about a redacted component
     * - not its length, not a prefix or suffix, not a digest - can be recovered from a stringified
     * instance. A partial mask was rejected deliberately: a truncated account identifier is still
     * account data, and a digest of an eleven-character identifier is trivially reversible by
     * enumeration. The same placeholder text is used by the peers in this package so that a redacted
     * rendering looks the same wherever it appears.
     *
     * <p>Private because it is a rendering detail and not part of the bill-payment contract.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Returns a diagnostic representation that identifies the response and discloses neither the
     * account nor either balance.
     *
     * <p><strong>Why the implicit record rendering could not stand.</strong> A record's generated
     * rendering prints every component, and the first three components of this one are an account
     * identifier and two balances of that account. One instance exists per bill-payment request, so a
     * default rendering would place an account identifier and the exact amount paid one interpolation
     * away from every log line, assertion message and diagnostic dump on the payment path.
     *
     * <p><strong>What is retained.</strong> The identifier of the posted transaction, the two explicit
     * flags, the focus hint, the route, the four header values and the summary message. That set is
     * what a diagnostic needs: it names <em>which</em> payment attempt this was, whether it was
     * accepted, whether it failed, what the operator was told and where the client was sent next, and
     * it is enough to locate the same attempt again. None of it identifies an account holder. The
     * summary message is retained deliberately, because it is contract text drawn from the constants
     * above and carries no operator input - not even on the success arm, where the only variable part
     * is the posted transaction identifier.
     *
     * <p><strong>What is withheld, and why the list is wider than the account identifier alone.</strong>
     * The account identifier is withheld because it identifies an account. The confirmation answer is
     * withheld because it is operator input and a rejection must never echo the value it rejected
     * (decision log entry D-16). Both balances are withheld because, together with the retained
     * transaction identifier, they reveal exactly what a specific account owed and paid - which is not
     * something a diagnostic channel needs in order to be useful. Fail-closed is the correct default:
     * the retained set was chosen because it is sufficient, not because the remainder happened to look
     * harmless. The navigation state is withheld too, since it carries identifiers of its own; it
     * redacts them in its own rendering, and omitting it here avoids depending on that.
     *
     * <p>Equality and hashing remain exactly as the record contract generates them: they compare every
     * component by value and emit nothing, so byte-for-byte fixture comparison is unaffected.
     *
     * @return the response identification, with every account-identifying and monetary component
     *     replaced by a fixed placeholder
     */
    @Override
    public String toString() {
        return "BillPaymentResponse["
                + "accountId=" + REDACTION_PLACEHOLDER
                + ", currentBalance=" + REDACTION_PLACEHOLDER
                + ", paymentBalance=" + REDACTION_PLACEHOLDER
                + ", confirm=" + REDACTION_PLACEHOLDER
                + ", newTransactionId=" + newTransactionId
                + ", transactionName=" + transactionName
                + ", title01=" + title01
                + ", currentDate=" + currentDate
                + ", programName=" + programName
                + ", title02=" + title02
                + ", currentTime=" + currentTime
                + ", errorMessage=" + errorMessage
                + ", paymentAccepted=" + paymentAccepted
                + ", generalError=" + generalError
                + ", fieldToFocus=" + fieldToFocus
                + ", route=" + route
                + ", navigation=" + REDACTION_PLACEHOLDER
                + "]";
    }

}
