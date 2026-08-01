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
import java.util.List;

/**
 * Immutable response contract for the transaction-list screen &mdash; the REST projection of legacy
 * transaction {@code CT00}, whose 3270 presentation is defined by mapset {@code COTRN00}.
 *
 * <p>The legacy antecedents are the program {@code app/cbl/COTRN00C.cbl} (699 lines across sixteen
 * procedure paragraphs), the generated symbolic map {@code app/cpy-bms/COTRN00.CPY}, and the mapset
 * definition {@code app/bms/COTRN00.bms} that supplies the field lengths corroborating every width
 * declared below. The row values originate in the 350-byte transaction record declared at
 * {@code app/cpy/CVTRA05Y.cpy}. Provenance is by citation only: no source text is copied here.
 * Checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}; upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <h2>Five distinct page-boundary messages, not two</h2>
 *
 * <p><strong>The browse emits five different boundary texts, and collapsing them would be a
 * behavioural regression.</strong> Three announce the top of the browse and two announce the bottom,
 * and they are not stylistic variants of one another: they are emitted from five different
 * paragraphs at five different points in the browse, so the text an operator sees reports
 * <em>which</em> mechanism detected the boundary.
 *
 * <ul>
 *   <li><strong>Two come from the attention-key paragraphs</strong>, where the operator asked to
 *       move past a boundary the screen already occupies. The backward-key paragraph begins at
 *       {@code app/cbl/COTRN00C.cbl} line 234 and reports at line 248; the forward-key paragraph
 *       begins at line 257 and reports at line 270. Both use the wording "already", because nothing
 *       moved.</li>
 *   <li><strong>Three come from the file-access paragraphs</strong>, where the browse primitive
 *       itself reached the boundary. Browse positioning begins at line 591 and reports at line 608;
 *       the forward read begins at line 624 and reports at line 642; the backward read begins at
 *       line 658 and reports at line 676. These use "at the top" and "have reached", because an
 *       access was attempted and the boundary was discovered by its outcome.</li>
 * </ul>
 *
 * <p>All five are therefore published below as five separate constants. None is parameterised, none
 * is derived from another, and none is deduplicated, because each is compared character for
 * character by the interface-contract acceptance criterion. Note the punctuation: each of the five
 * ends in three dots with no space before them, whereas the numeric-identifier message at line 214
 * carries a space before its three dots. Both patterns are reproduced exactly as emitted.
 *
 * <p>Selecting among them is the service's responsibility and not this type's. This record carries
 * exactly one summary message component and never composes, formats or chooses a message.
 *
 * <h2>Row shape, and three widths that must not be unified</h2>
 *
 * <p>The symbolic map declares ten row families, each family holding a selection indicator, a
 * transaction identifier, a date, a description and an amount. Those five values are modelled once,
 * by the nested {@link TransactionRow}, carried in a list of at most ten. The generated per-family
 * suffixes are deliberately not reproduced: they are inconsistent artefacts of the map generator
 * &mdash; four digits on the selection indicator, two on the identifier, date and description, three
 * on the amount &mdash; and they carry no meaning. Ten discrete component sets would encode that
 * artefact into the API.
 *
 * <p>Equally deliberately absent are the generated 3270 control items. Every map field is
 * accompanied by generated length, flag and attribute items, and the map begins with a
 * twelve-character terminal-buffer filler. All of it is terminal plumbing with no counterpart in a
 * REST contract, so none of it is modelled, and no screen coordinate, attribute value, colour
 * constant or marker character appears anywhere in this file.
 *
 * <p><strong>Three widths on this screen coincide with differently-sized fields elsewhere in the
 * estate and are declared independently for that reason:</strong>
 *
 * <ul>
 *   <li><strong>The description is twenty-six characters here.</strong> The stored description is a
 *       hundred characters in the transaction record at {@code app/cpy/CVTRA05Y.cpy}, and the
 *       transaction view and add screens present sixty. The row-population paragraph at
 *       {@code app/cbl/COTRN00C.cbl} line 395 moves the hundred-character value into a
 *       twenty-six-character map field, so this screen genuinely shows a truncation. A shared
 *       constant or a common base type across the three widths would silently change what this
 *       screen presents.</li>
 *   <li><strong>The row date is eight characters here</strong>, where the view and add screens carry
 *       ten-character origination and processing dates. It is text and is never a temporal type:
 *       lines 385 to 388 build it as a two-digit-year presentation string, and the work field it is
 *       built in is initialised to a value that is not a valid calendar date at all. No date library
 *       could hold that value, and parsing or reformatting it would alter what the screen shows.</li>
 *   <li><strong>The page indicator is eight characters here</strong>, where the card-list screen has
 *       a three-character indicator under a different field name. Both are alphanumeric, so the
 *       indicator crosses this API as text rather than as a number, bounded at this map's own width
 *       by this type's own constant.</li>
 * </ul>
 *
 * <p>The transaction identifier is sixteen alphanumeric characters and is never a numeric type. Its
 * leading zeros and its external width are contractual: an identifier of {@code "0000000000000001"}
 * is not the number one, and coercing it would shorten the value the byte-equivalence acceptance
 * criterion compares.
 *
 * <h2>Money discipline</h2>
 *
 * <p>The row amount is a {@link BigDecimal} at a contractual scale of two, and no other numeric
 * representation is permitted. Its origin is the zoned-decimal, display-usage field
 * {@code TRAN-AMT PIC S9(09)V99} declared at {@code app/cpy/CVTRA05Y.cpy}, persisted as a
 * fixed-scale numeric column. A binary floating-point type would not represent those values exactly.
 *
 * <p><strong>Nothing here scales, rounds or computes.</strong> The estate contains no rounding
 * clause on any arithmetic statement, so every store into a two-decimal field truncates toward zero.
 * That truncation is applied in exactly one place in the module &mdash; the zoned-decimal codec,
 * reached through the service layer &mdash; which is why no scaling call, rounding mode, precision
 * context or numeric formatter appears in this package at all. Half-up and half-even rounding are
 * excluded module-wide. Concentrating the policy in one component is what prevents a second, subtly
 * different rounding rule from appearing at a call site.
 *
 * <p>The amount also has an edited presentation form on the screen, twelve characters wide including
 * a sign, a decimal point and two decimal places, built in the work field declared at
 * {@code app/cbl/COTRN00C.cbl} line 56. That mask is a rendering of the value, not the value. This
 * contract carries the numeric value and never the mask, so no formatted or masked string is
 * produced here.
 *
 * <h2>Rows, paging and navigation</h2>
 *
 * <p><strong>A short page returns fewer rows and is never padded.</strong> The legacy screen clears
 * its row area and then fills only as many lines as the browse yields, leaving the remainder blank;
 * this contract expresses that by absence. Padding the list to the full screen depth would invent
 * rows that the browse did not return.
 *
 * <p><strong>Row order is the service's, and is never altered here.</strong> A forward page is filled
 * ascending: the row index is reset to one at {@code app/cbl/COTRN00C.cbl} line 295 and the fill loop
 * at lines 297 to 303 advances it. A backward page is filled from the bottom upward: the backward
 * paragraph begins at line 333, seeds the row index to the last screen line at line 349, and the loop
 * at lines 351 to 357 reads backward at line 352 while decrementing, so the presented order is the
 * reverse of the read order. The service performs that reversal before building this response. This
 * record sorts nothing, reverses nothing and accepts no comparator; the rows arrive in presentation
 * order and are carried in it.
 *
 * <p><strong>No total row count or total page count exists, and none is invented.</strong> The legacy
 * browse never counts the cluster; it discovers whether a further page exists by attempting one more
 * access and observing the outcome. A total would require a counting query the original never issued.
 * The two conditions the screen actually knows travel in {@link PageMetadata} as independent flags.
 *
 * <p>{@link PageMetadata} is carried for the browse cursor and direction. No page-size constant is
 * declared or referenced here: the screen depth is a property of the screen's shape, it is neither a
 * fetch size, a chunk size, a batch size nor any kind of limit, and this contract neither enforces
 * nor restates it.
 *
 * <p>{@link NavigationContext} is carried as client-echoed request state, never as a server session.
 * The route the client should call next travels as an opaque string. This type declares no route
 * table, no route constant and no dispatch method: that vocabulary belongs to the navigation service,
 * and there is no server-side forwarding, so the client drives the next call. The screen work area
 * used by the account and card programs is deliberately absent, because the transaction programs are
 * not part of the program family that includes it.
 *
 * <h2>Nothing is validated, defaulted or normalised</h2>
 *
 * <p>This is a response contract, so beyond bounding the fixed-width screen fields there is nothing
 * for validation to do. The only constraint used is a maximum length, which measures and never
 * alters, so leading and trailing spaces survive untouched. Legacy fixed-width fields are
 * space-padded and that padding is contract. No value is trimmed, stripped, padded, case-folded or
 * re-formatted anywhere in this file.
 *
 * <p>No presence, pattern, digit, range or sign constraint appears on any component. Blank rows and
 * space-padded values are ordinary states of this screen; amounts are legitimately negative for
 * returns; and every check the program performs is a message-bearing validation owned by the service,
 * emitted in source order. Bean Validation reports violations in an unspecified order and would
 * therefore replace an ordered cascade with an unordered set.
 *
 * <p>The general-error indicator is an explicit boolean and is never inferred from whether the
 * message component is populated. The legacy program sets an error flag independently of the message
 * text, and an informational boundary message is not an error, so the two must be able to disagree.
 *
 * <p>Instances are deeply immutable: the row list is defensively copied into an unmodifiable list at
 * construction, a null list becomes an empty list rather than a null component, and every other
 * component is a primitive, a string, or an immutable value. Instances are therefore safe to share
 * between threads and carry the canonical equality, hashing and text rendering the record contract
 * provides.
 *
 * @param rows the transaction rows this page presents, in presentation order, holding at most as
 *     many entries as the map declares row families. Never {@code null}: a {@code null} argument
 *     becomes an empty list. Defensively copied and unmodifiable. A short final page carries fewer
 *     rows and is never padded.
 * @param page the browse cursor and direction for this page. May be {@code null} where a caller has
 *     no paging state to report, such as a rejected request redisplayed without a browse.
 * @param navigation the client-echoed navigation state for the next call. May be {@code null}.
 * @param nextRoute the route the client should call next, opaque to this contract and deliberately
 *     unbounded because it is a service-owned identifier rather than a legacy fixed-width field.
 *     Declarative only: nothing here resolves or performs navigation. May be {@code null}.
 * @param transactionIdFilter the search key echoed back into the identifier entry field of the map,
 *     sixteen characters wide. Present so the screen can redisplay what the operator typed. Carried
 *     exactly as received and never parsed as a number. May be {@code null}.
 * @param pageNumber the page indicator the screen displays, eight characters wide and alphanumeric
 *     rather than numeric, which is why it is text. Display value only; the browse cursors in
 *     {@link PageMetadata} are authoritative for navigation. May be {@code null}.
 * @param message the single summary message for this response, seventy-eight characters wide to
 *     match the map's message field. Populated with one of the published constants, or with a
 *     service-supplied text, exactly as supplied and never trimmed or reformatted. {@code null} when
 *     the screen has nothing to report.
 * @param error whether this response reports an error condition, stated explicitly rather than
 *     inferred from {@code message}. The five boundary messages are informational and are reported
 *     with this indicator clear.
 * @param focusFieldName the identity of the field the client should place the cursor in, named by its
 *     map field name and bounded at the widest such name in this mapset. An identity only: never a
 *     row or column position, never a sentinel index and never a terminal attribute value. May be
 *     {@code null} when no field is nominated.
 * @param screenTitleLine1 the first screen title line, forty characters wide. May be {@code null}.
 * @param screenTitleLine2 the second screen title line, forty characters wide. Declared separately
 *     from the first because the map declares two independent title fields. May be {@code null}.
 * @param currentDate the current date as the screen renders it, eight characters wide. Text, not a
 *     temporal type, and never reformatted. May be {@code null}.
 * @param currentTime the current time as the screen renders it, eight characters wide. Text, not a
 *     temporal type, and never reformatted. May be {@code null}.
 * @param transactionName the transaction identifier the screen displays, four characters wide. May be
 *     {@code null}.
 * @param programName the program name the screen displays, eight characters wide. May be
 *     {@code null}.
 */
public record TransactionListResponse(
        List<TransactionRow> rows,
        PageMetadata page,
        NavigationContext navigation,
        String nextRoute,
        @Size(max = TransactionListResponse.TRANSACTION_ID_LENGTH) String transactionIdFilter,
        @Size(max = TransactionListResponse.PAGE_NUMBER_LENGTH) String pageNumber,
        @Size(max = TransactionListResponse.MESSAGE_LENGTH) String message,
        boolean error,
        @Size(max = TransactionListResponse.FOCUS_FIELD_NAME_LENGTH) String focusFieldName,
        @Size(max = TransactionListResponse.SCREEN_TITLE_LENGTH) String screenTitleLine1,
        @Size(max = TransactionListResponse.SCREEN_TITLE_LENGTH) String screenTitleLine2,
        @Size(max = TransactionListResponse.CURRENT_DATE_LENGTH) String currentDate,
        @Size(max = TransactionListResponse.CURRENT_TIME_LENGTH) String currentTime,
        @Size(max = TransactionListResponse.TRANSACTION_NAME_LENGTH) String transactionName,
        @Size(max = TransactionListResponse.PROGRAM_NAME_LENGTH) String programName) {

    /**
     * Width in characters of the row selection indicator: 1.
     *
     * <p>The legacy width of each selection field in the ten row families of
     * {@code app/cpy-bms/COTRN00.CPY}, corroborated by the corresponding mapset field in
     * {@code app/bms/COTRN00.bms}. One character, because the screen accepts a single selection
     * character per row.</p>
     *
     * <p>The bound measures and never alters: it reports an over-long value and leaves a blank or
     * space-valued one exactly as received. It deliberately does not restrict the value to the one
     * character the program accepts, because rejecting an unexpected character here would replace the
     * program's own message-bearing validation with an unordered constraint violation.</p>
     */
    public static final int SELECTION_LENGTH = 1;

    /**
     * Width in characters of a transaction identifier: 16.
     *
     * <p>The legacy width of each row identifier field and of the identifier entry field in
     * {@code app/cpy-bms/COTRN00.CPY}, matching the identifier at the head of the 350-byte
     * transaction record declared at {@code app/cpy/CVTRA05Y.cpy}. Both the row identifier and the
     * echoed search key share this constant because they are the same kind of value at the same
     * declared width, not because two widths happen to coincide.</p>
     *
     * <p>This is an alphanumeric identifier, never a number. Its leading zeros and its
     * sixteen-character external width are contractual, and the width is compared directly by the
     * byte-equivalence acceptance criterion.</p>
     */
    public static final int TRANSACTION_ID_LENGTH = 16;

    /**
     * Width in characters of the row date as this screen presents it: 8.
     *
     * <p>The legacy width of each row date field in {@code app/cpy-bms/COTRN00.CPY}. The value is
     * assembled at {@code app/cbl/COTRN00C.cbl} lines 385 to 388 from the date portion of the
     * origination timestamp of the transaction record, into a work field declared at line 57 as eight
     * characters with a two-digit year, initialised to a value that is not a valid calendar date.</p>
     *
     * <p><strong>This is why the row date is text.</strong> A two-digit-year presentation string is
     * not a date value, and the initial value could not be held by any temporal type, so the row date
     * is carried verbatim and is never parsed, reformatted or widened. Declared separately from
     * {@link #CURRENT_DATE_LENGTH} and {@link #CURRENT_TIME_LENGTH}: three unrelated fields whose
     * widths coincide, none derived from another. The transaction view and add screens present
     * ten-character dates, and that width is theirs and is not shared with this one.</p>
     */
    public static final int DISPLAYED_DATE_LENGTH = 8;

    /**
     * Width in characters of the row description as this screen presents it: 26.
     *
     * <p>The legacy width of each row description field in {@code app/cpy-bms/COTRN00.CPY}, and a
     * genuine truncation rather than the stored width: the row-population paragraph at
     * {@code app/cbl/COTRN00C.cbl} line 395 moves the hundred-character description of the
     * transaction record declared at {@code app/cpy/CVTRA05Y.cpy} into this twenty-six-character
     * field, discarding the remainder.</p>
     *
     * <p><strong>Three different widths exist for one logical value and none may be unified.</strong>
     * A hundred characters are stored, sixty are presented by the transaction view and add screens,
     * and twenty-six are presented here. Sharing a constant, or introducing a common base type or
     * interface across the three, would silently change what one of the three screens presents.</p>
     */
    public static final int DESCRIPTION_LENGTH = 26;

    /**
     * Scale of the row amount: 2 &mdash; the two decimal places of the zoned-decimal, display-usage
     * amount field {@code TRAN-AMT PIC S9(09)V99} declared at {@code app/cpy/CVTRA05Y.cpy}, matching
     * the fixed-scale numeric column the value is persisted in.
     *
     * <p><strong>Declared as the contract, never applied here.</strong> This constant states the scale
     * that amounts crossing this boundary carry; it is not an instruction to rescale one. No scaling
     * call, rounding mode, precision context or numeric formatter appears anywhere in this package.
     * The estate specifies no rounding on any arithmetic statement, so every store into a two-decimal
     * field truncates toward zero, and that truncation is applied in exactly one component &mdash; the
     * module's zoned-decimal codec &mdash; so that a single rounding policy governs the whole module.
     * A value published here is already at this scale; a value that is not is a defect in the service
     * that produced it, not something for a data-transfer type to silently correct.</p>
     */
    public static final int AMOUNT_SCALE = 2;

    /**
     * Width in characters of the displayed page indicator on this map: 8.
     *
     * <p>The legacy width of the page indicator field in {@code app/cpy-bms/COTRN00.CPY}. The field is
     * alphanumeric rather than numeric, which is why the indicator crosses this API as text and never
     * as an integer.</p>
     *
     * <p><strong>Declared here rather than shared.</strong> The card-list screen carries a
     * three-character page indicator under a different field name, so the two widths are genuinely
     * different fields on genuinely different screens. Bounding this screen's indicator at this
     * screen's own width keeps the two contracts independent, so a change to one map cannot silently
     * alter the other.</p>
     */
    public static final int PAGE_NUMBER_LENGTH = 8;

    /**
     * Width in characters of the summary message field on this map: 78.
     *
     * <p>The legacy width of the message field in {@code app/cpy-bms/COTRN00.CPY}, corroborated by the
     * mapset definition in {@code app/bms/COTRN00.bms}.</p>
     *
     * <p><strong>Seventy-eight, not eighty.</strong> The card detail and card update maps declare
     * eighty-character message fields; this map declares seventy-eight. The widths are per-map and are
     * not shared, so no constant crosses maps. Every published message constant below fits within this
     * width, and the bound reports an over-long value rather than truncating one, so a message is never
     * silently shortened.</p>
     */
    public static final int MESSAGE_LENGTH = 78;

    /**
     * Width in characters of a nominated field's identity: 7.
     *
     * <p>Derived from the mapset definition {@code app/bms/COTRN00.bms}, in which every one of the
     * fifty-eight named fields has a name of at most seven characters. Seven is the generator's own
     * ceiling: it appends a one-character suffix to each field name to form the eight-character
     * symbolic names in {@code app/cpy-bms/COTRN00.CPY}, so a longer name could not be generated.</p>
     *
     * <p>The nominated field is an identity and nothing more. No row or column position, no sentinel
     * index and no terminal attribute value is modelled anywhere in this contract.</p>
     */
    public static final int FOCUS_FIELD_NAME_LENGTH = 7;

    /**
     * Width in characters of each screen title line: 40 &mdash; the legacy width of both title fields
     * in {@code app/cpy-bms/COTRN00.CPY}.
     *
     * <p>One constant governs both title lines because they are two instances of the same field kind at
     * the same declared width, which is a different situation from two unrelated fields whose widths
     * coincide. Both are populated by the header paragraph at {@code app/cbl/COTRN00C.cbl} line 567.</p>
     */
    public static final int SCREEN_TITLE_LENGTH = 40;

    /**
     * Width in characters of the displayed current date: 8 &mdash; the legacy width of the current-date
     * field in {@code app/cpy-bms/COTRN00.CPY}, populated by the header paragraph at
     * {@code app/cbl/COTRN00C.cbl} line 567.
     *
     * <p>Declared separately from {@link #DISPLAYED_DATE_LENGTH} and {@link #CURRENT_TIME_LENGTH} even
     * though all three are eight: a screen header date, a row date and a clock time are unrelated
     * fields whose widths coincide by accident, and deriving any one from another would couple three
     * independent parts of the screen contract. Carried as text and never reformatted.</p>
     */
    public static final int CURRENT_DATE_LENGTH = 8;

    /**
     * Width in characters of the displayed current time: 8 &mdash; the legacy width of the current-time
     * field in {@code app/cpy-bms/COTRN00.CPY}, populated by the header paragraph at
     * {@code app/cbl/COTRN00C.cbl} line 567. Declared separately from {@link #CURRENT_DATE_LENGTH} for
     * the reason given there. Carried as text and never reformatted.
     */
    public static final int CURRENT_TIME_LENGTH = 8;

    /**
     * Width in characters of the displayed transaction identifier: 4 &mdash; the legacy width of the
     * transaction-name field in {@code app/cpy-bms/COTRN00.CPY}, matching the four-character CICS
     * transaction identifier width used throughout the estate. Populated by the header paragraph at
     * {@code app/cbl/COTRN00C.cbl} line 567.
     */
    public static final int TRANSACTION_NAME_LENGTH = 4;

    /**
     * Width in characters of the displayed program name: 8 &mdash; the legacy width of the
     * program-name field in {@code app/cpy-bms/COTRN00.CPY}, populated by the header paragraph at
     * {@code app/cbl/COTRN00C.cbl} line 567.
     *
     * <p>Declared separately from the three eight-character date, time and page-indicator constants
     * above: a program name is an unrelated field whose width coincides with theirs, and none of the
     * four is derived from any other.</p>
     */
    public static final int PROGRAM_NAME_LENGTH = 8;

    /**
     * Message reporting that the selection character entered against a row is not the one the screen
     * accepts, emitted from the enter-key paragraph at {@code app/cbl/COTRN00C.cbl} line 199.
     *
     * <p><strong>Singular by contract.</strong> This screen accepts exactly one selection character, so
     * the text names a single valid value. The administrative user-list screen accepts two and phrases
     * its equivalent message in the plural. The two texts are separate external contracts and must
     * never be unified, generalised or generated from a shared template.</p>
     *
     * <p>Unlike the five boundary messages below, this text carries no trailing ellipsis at all.</p>
     */
    public static final String MESSAGE_INVALID_SELECTION = "Invalid selection. Valid value is S";

    /**
     * Message reporting that the identifier entered in the search field is not numeric, emitted from
     * the enter-key paragraph at {@code app/cbl/COTRN00C.cbl} line 214.
     *
     * <p><strong>Note the space before the ellipsis.</strong> This text alone separates its three dots
     * from the preceding word; the five boundary messages below attach theirs directly. Both patterns
     * are reproduced exactly as emitted, because the interface-contract acceptance criterion compares
     * every message character for character.</p>
     *
     * <p>The identifier itself remains a sixteen-character alphanumeric value in this contract. That the
     * program applies a numeric test to operator input does not make the stored identifier a number,
     * and the test itself is a service responsibility: nothing in this file inspects a value.</p>
     */
    public static final String MESSAGE_TRAN_ID_NOT_NUMERIC = "Tran ID must be Numeric ...";

    /**
     * Top-of-browse message emitted when the operator requests the preceding page while the screen
     * already shows the first one, from the backward attention-key paragraph beginning at
     * {@code app/cbl/COTRN00C.cbl} line 234 and reporting at line 248.
     *
     * <p>The first of <strong>three distinct top-of-browse texts</strong>. This one is emitted before
     * any access is attempted, which is why it says "already": the request was declined and nothing
     * moved. It is not interchangeable with {@link #MESSAGE_AT_TOP} or {@link #MESSAGE_REACHED_TOP},
     * which are emitted by the browse primitives themselves. Informational, not an error.</p>
     */
    public static final String MESSAGE_ALREADY_AT_TOP = "You are already at the top of the page...";

    /**
     * Bottom-of-browse message emitted when the operator requests the following page while the screen
     * already shows the last one, from the forward attention-key paragraph beginning at
     * {@code app/cbl/COTRN00C.cbl} line 257 and reporting at line 270.
     *
     * <p>The first of <strong>two distinct bottom-of-browse texts</strong>, and the counterpart of
     * {@link #MESSAGE_ALREADY_AT_TOP} on the forward path: emitted before any access is attempted,
     * which is why it too says "already". Not interchangeable with {@link #MESSAGE_REACHED_BOTTOM}.
     * Informational, not an error.</p>
     */
    public static final String MESSAGE_ALREADY_AT_BOTTOM = "You are already at the bottom of the page...";

    /**
     * Top-of-browse message emitted while positioning the browse, from the browse-positioning paragraph
     * beginning at {@code app/cbl/COTRN00C.cbl} line 591 and reporting at line 608.
     *
     * <p>The second of the three top-of-browse texts, and <strong>deliberately without the word
     * "already"</strong>. It is emitted by the access itself rather than by an attention-key check, so
     * it reports where the browse landed rather than declining a request. Distinct from both
     * {@link #MESSAGE_ALREADY_AT_TOP} and {@link #MESSAGE_REACHED_TOP}; the three are emitted from
     * three different paragraphs and are never merged. Informational, not an error.</p>
     */
    public static final String MESSAGE_AT_TOP = "You are at the top of the page...";

    /**
     * Bottom-of-browse message emitted when a forward read reaches the end of the browse, from the
     * forward-read paragraph beginning at {@code app/cbl/COTRN00C.cbl} line 624 and reporting at
     * line 642.
     *
     * <p>The second of the two bottom-of-browse texts, phrased <strong>"have reached"</strong> because an
     * access was attempted and the end was discovered by its outcome. Distinct from
     * {@link #MESSAGE_ALREADY_AT_BOTTOM}, which is emitted before any access. Informational, not an
     * error.</p>
     */
    public static final String MESSAGE_REACHED_BOTTOM = "You have reached the bottom of the page...";

    /**
     * Top-of-browse message emitted when a backward read reaches the start of the browse, from the
     * backward-read paragraph beginning at {@code app/cbl/COTRN00C.cbl} line 658 and reporting at
     * line 676.
     *
     * <p>The third of the three top-of-browse texts, phrased <strong>"have reached"</strong> for the same
     * reason as {@link #MESSAGE_REACHED_BOTTOM}: the boundary was discovered by an access outcome. The
     * three top texts &mdash; this one, {@link #MESSAGE_ALREADY_AT_TOP} and {@link #MESSAGE_AT_TOP}
     * &mdash; report the same physical boundary reached by three different mechanisms, and each is
     * published separately because each is compared character for character. Informational, not an
     * error.</p>
     */
    public static final String MESSAGE_REACHED_TOP = "You have reached the top of the page...";

    /**
     * Canonical constructor. Makes the row list safe to publish and leaves every other component
     * exactly as supplied.
     *
     * <p>Exactly one thing happens here: the row list is replaced by an unmodifiable copy, and a
     * {@code null} list becomes an empty list so that no caller has to distinguish "no rows" from
     * "absent". Copying rather than storing the caller's reference is what makes an instance genuinely
     * immutable, since a retained reference could still be mutated through the caller's own handle.</p>
     *
     * <p><strong>Everything a reader might expect a paging response to do here, this deliberately does
     * not do.</strong> The list is not padded out to the screen depth, because a short final page
     * legitimately carries fewer rows and the legacy screen simply leaves the surplus lines blank. It is
     * not sorted or reversed, because the service has already placed the rows in presentation order and
     * a backward page is deliberately the reverse of its read order. It is not truncated or
     * size-checked, because enforcing a depth here would silently discard data the browse returned
     * rather than surfacing the defect. No component is trimmed, padded, case-folded or reformatted, and
     * no amount is rescaled or rounded: legacy fixed-width values are space-significant, and rescaling
     * belongs to the module's zoned-decimal codec alone.</p>
     */
    public TransactionListResponse {
        rows = (rows == null) ? List.of() : List.copyOf(rows);
    }

    /**
     * One row of the transaction-list screen &mdash; the five values a single row family of
     * {@code app/cpy-bms/COTRN00.CPY} presents, modelled once instead of ten times.
     *
     * <p>The map declares ten such families and the program fills them by an indexed cascade in the
     * row-population paragraph at {@code app/cbl/COTRN00C.cbl} lines 381 to 449, clearing them in the
     * companion paragraph at line 450. Because every family has an identical shape, one record type
     * carried in a list reproduces the screen exactly while collapsing what would otherwise be fifty
     * discrete components.</p>
     *
     * <p><strong>The generated field-name suffixes are not reproduced.</strong> They are inconsistent
     * &mdash; four digits on the selection indicator, two on the identifier, date and description, and
     * three on the amount &mdash; purely as an artefact of how the map generator forms eight-character
     * names, and they carry no meaning whatsoever. Neither the suffix scheme nor the row position appears
     * in this type: a row's position is its index in the enclosing list, which is the presentation order
     * the service established. The generated length, flag and attribute items that accompany each map
     * field, and the terminal-buffer filler at the head of the map, are likewise absent as terminal
     * plumbing with no place in a REST contract.</p>
     *
     * <p>Every component may be {@code null} or blank. A row the browse did not fill is blank on the
     * legacy screen, so nothing here is required, and no component is validated beyond its declared
     * width.</p>
     *
     * @param selection the selection indicator echoed back for this row, one character wide. Echoed
     *     only: nothing here interprets it, and choosing what a selection means is the service's
     *     responsibility. May be {@code null} or blank, which is the ordinary state of an unselected row.
     * @param transactionId the transaction identifier, sixteen alphanumeric characters, taken from the
     *     head of the transaction record declared at {@code app/cpy/CVTRA05Y.cpy} and moved into the row
     *     at {@code app/cbl/COTRN00C.cbl} line 392. Text, never a number: leading zeros and the
     *     sixteen-character external width are contractual. May be {@code null}.
     * @param displayedDate the date as this screen renders it, eight characters wide, assembled at
     *     {@code app/cbl/COTRN00C.cbl} lines 385 to 388 from the date portion of the transaction's
     *     origination timestamp into a two-digit-year presentation form. Carried verbatim and never
     *     parsed, reformatted or widened; see {@link TransactionListResponse#DISPLAYED_DATE_LENGTH} for
     *     why it cannot be a temporal type. May be {@code null}.
     * @param description the description as this screen presents it, twenty-six characters wide &mdash; a
     *     genuine truncation of the hundred-character stored description, performed at
     *     {@code app/cbl/COTRN00C.cbl} line 395. Neither the stored width nor the sixty characters the
     *     view and add screens present may be substituted here. May be {@code null}.
     * @param amount the transaction amount as an exact decimal at a scale of two, from the zoned-decimal
     *     amount field of the transaction record declared at {@code app/cpy/CVTRA05Y.cpy}. Legitimately
     *     negative for returns, which is why no sign constraint applies. The numeric value only: the
     *     twelve-character edited form the screen displays is a rendering built by the program at
     *     {@code app/cbl/COTRN00C.cbl} line 56 and is never carried here, and nothing in this package
     *     rescales, rounds or formats it. May be {@code null} where the row is blank.
     */
    public record TransactionRow(
            @Size(max = TransactionListResponse.SELECTION_LENGTH) String selection,
            @Size(max = TransactionListResponse.TRANSACTION_ID_LENGTH) String transactionId,
            @Size(max = TransactionListResponse.DISPLAYED_DATE_LENGTH) String displayedDate,
            @Size(max = TransactionListResponse.DESCRIPTION_LENGTH) String description,
            BigDecimal amount) {

        // The canonical constructor generated for this record is intentionally left as generated. Every
        // component is already immutable, so there is nothing to defensively copy, and there is nothing
        // to validate, default or normalise: blank and space-padded values are ordinary states of a
        // legacy fixed-width screen row, and the amount must cross this boundary at exactly the scale
        // the service published it at. Adding a compact constructor that rescaled the amount or trimmed
        // a string would change the bytes the screen presents.
    }
}
