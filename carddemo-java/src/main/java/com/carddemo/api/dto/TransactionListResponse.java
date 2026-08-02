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
 * Immutable response contract for the transaction-list screen, legacy transaction {@code CT00}.
 *
 * <p>REST projection of the 3270 screen driven by {@code app/cbl/COTRN00C.cbl} and described field
 * by field by {@code app/cpy-bms/COTRN00.CPY} and {@code app/bms/COTRN00.bms}; the row values
 * originate in the transaction record declared at {@code app/cpy/CVTRA05Y.cpy}. It is a carrier:
 * nothing here validates, defaults, normalises, orders, counts, formats or navigates. Every such
 * decision belongs to {@code service/TransactionListService}.</p>
 *
 * <p><strong>The browse emits five different boundary texts, and collapsing them would be a
 * behavioural regression.</strong> Three announce the top of the browse and two the bottom, and they
 * are not stylistic variants: two come from the attention-key paths, where the operator asked to move
 * past a boundary the screen already occupies and nothing moved, which is why those two say
 * "already"; three come from the browse primitives themselves, where an access was attempted and the
 * boundary was discovered by its outcome. All five are published below as separate constants, none
 * parameterised, derived or deduplicated, because each is compared character for character, and all
 * five are informational rather than errors. Punctuation differs between messages and is reproduced
 * exactly as emitted: the five boundary texts attach their three dots directly to the preceding word,
 * the not-numeric message separates its three dots by a space, and the invalid-selection message
 * carries none. Choosing among them is the service's responsibility.</p>
 *
 * <p>The map declares ten row families, each holding a selection indicator, an identifier, a date, a
 * description and an amount. Those five values are modelled once, by the nested
 * {@link TransactionRow}, carried in a list. The generated per-family name suffixes are not
 * reproduced - they are inconsistent artefacts of how the map generator forms names and carry no
 * meaning - and neither are the generated 3270 length, flag and attribute items or the terminal-area
 * filler, which are terminal plumbing with no counterpart in a REST contract. No screen coordinate,
 * attribute value, colour constant or marker character appears anywhere in this file.</p>
 *
 * <p><strong>Three widths on this screen coincide with differently-sized fields elsewhere in the
 * estate and are declared independently for that reason.</strong> The description is twenty-six
 * characters here, a genuine truncation of the hundred-character stored value that the transaction
 * view and add screens present at sixty. The row date is eight characters here, where those screens
 * carry ten-character dates. The page indicator is eight characters here, where the card-list screen
 * declares three. A shared constant across any of those pairs would silently change what this screen
 * presents. Both the row date and the two screen-furniture timestamps are text rather than temporal
 * types and are never parsed or reformatted, and the transaction identifier is sixteen alphanumeric
 * characters and never numeric: an identifier of {@code "0000000000000001"} is not the number one,
 * and coercing it would shorten the value the byte-equivalence criterion compares.</p>
 *
 * <p><strong>Nothing here scales, rounds or computes.</strong> The row amount is a
 * {@link BigDecimal} at a contractual scale of two, because its zoned-decimal origin cannot be
 * represented exactly by a binary floating-point type. The estate carries no rounding clause on any
 * arithmetic statement, so every store into a two-decimal field truncates toward zero, and that
 * truncation is applied in exactly one place in the module - the zoned-decimal codec, reached through
 * the service layer. No scaling call, rounding mode, precision context or numeric formatter appears
 * in this package at all, which is what prevents a second, subtly different rounding rule from
 * appearing at a call site. The twelve-character edited form the screen displays is a rendering of
 * the value, not the value, and is never carried here.</p>
 *
 * <p><strong>A short page returns fewer rows and is never padded</strong> - the legacy screen fills
 * only as many lines as the browse yields and leaves the remainder blank, which this contract
 * expresses by absence. <strong>Row order is the service's and is never altered here.</strong> A
 * forward page is filled top-down; a backward page is filled from the last line upward, so its
 * presented order is the reverse of its read order and the service performs that reversal before
 * building this response. This record sorts nothing, reverses nothing and accepts no comparator.</p>
 *
 * <p><strong>No total row count or total page count exists, and none is invented.</strong> The legacy
 * browse never counts the cluster; it discovers whether a further page exists by attempting one more
 * access. The two conditions the screen actually knows travel in {@link PageMetadata} as independent
 * flags, alongside the browse cursor and direction. No page-size constant is declared or referenced
 * here: the screen depth is a property of the screen's shape and is neither a fetch size, a chunk
 * size nor any kind of limit. {@link NavigationContext} is carried as client-echoed request state,
 * never as a server session, and the next route travels as an opaque string: this type declares no
 * route table, route constant or dispatch method, and there is no server-side forwarding.</p>
 *
 * <p>Beyond bounding the fixed-width screen fields there is nothing for validation to do. A maximum
 * length measures and never alters, so leading and trailing spaces - which on a space-padded legacy
 * field are contract - survive untouched. No presence, pattern, digit, range or sign constraint
 * appears on any component: blank rows are ordinary, amounts are legitimately negative for returns,
 * and every check the program performs is a message-bearing validation the service emits in source
 * order, which Bean Validation's unspecified reporting order could not preserve. The general-error
 * indicator is explicit and is never inferred from whether the message component is populated,
 * because the legacy program sets its error flag independently of the message text and an
 * informational boundary message is not an error.</p>
 *
 * <p>Deeply immutable and safe to share between threads: the row list is defensively copied into an
 * unmodifiable list at construction, a {@code null} list becomes empty, and every other component is
 * a primitive, a string or an immutable value.</p>
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
 * @param pageMetadata the browse cursor and direction for this page. May be {@code null} where a caller has
 *     no paging state to report, such as a rejected request redisplayed without a browse.
 * @param navigationContext the client-echoed navigation state for the next call. May be
 *     {@code null}.
 * @param nextRoute the route the client should call next, opaque to this contract and deliberately
 *     unbounded because it is a service-owned identifier rather than a legacy fixed-width field.
 *     Declarative only: nothing here resolves or performs navigation. May be {@code null}.
 * @param transactionIdFilter the search key echoed back into the identifier entry field of the map,
 *     sixteen characters wide. Present so the screen can redisplay what the operator typed. Carried
 *     exactly as received and never parsed as a number. May be {@code null}.
 * @param displayedPageNumber the page indicator the screen displays, eight characters wide and alphanumeric
 *     rather than numeric, which is why it is text. Display value only; the browse cursors in
 *     {@link PageMetadata} are authoritative for navigation. May be {@code null}.
 * @param message the single summary message for this response, seventy-eight characters wide to
 *     match the map's message field. Populated with one of the published constants, or with a
 *     service-supplied text, exactly as supplied and never trimmed or reformatted. {@code null} when
 *     the screen has nothing to report.
 * @param error whether this response reports an error condition, stated explicitly rather than
 *     inferred from {@code message}. The five boundary messages are informational and are reported
 *     with this indicator clear.
 * @param focusScreenFieldId the identity of the field the client should place the cursor in, named by its
 *     map field name and bounded at the widest such name in this mapset. An identity only: never a
 *     row or column position, never a sentinel index and never a terminal attribute value. May be
 *     {@code null} when no field is nominated.
 * @param title01 the first screen title line, forty characters wide. May be {@code null}.
 * @param title02 the second screen title line, forty characters wide. Declared separately
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
        PageMetadata pageMetadata,
        NavigationContext navigationContext,
        String nextRoute,
        @Size(max = TransactionListResponse.TRANSACTION_ID_LENGTH) String transactionIdFilter,
        @Size(max = TransactionListResponse.DISPLAYED_PAGE_NUMBER_LENGTH) String displayedPageNumber,
        @Size(max = TransactionListResponse.MESSAGE_LENGTH) String message,
        boolean error,
        @Size(max = TransactionListResponse.SCREEN_FIELD_ID_LENGTH) String focusScreenFieldId,
        @Size(max = TransactionListResponse.SCREEN_TITLE_LENGTH) String title01,
        @Size(max = TransactionListResponse.SCREEN_TITLE_LENGTH) String title02,
        @Size(max = TransactionListResponse.CURRENT_DATE_LENGTH) String currentDate,
        @Size(max = TransactionListResponse.CURRENT_TIME_LENGTH) String currentTime,
        @Size(max = TransactionListResponse.TRANSACTION_NAME_LENGTH) String transactionName,
        @Size(max = TransactionListResponse.PROGRAM_NAME_LENGTH) String programName) {

    public static final int SELECTION_LENGTH = 1;

    public static final int TRANSACTION_ID_LENGTH = 16;

    /**
     * Width in characters of the row date as this screen presents it: 8. The program builds a
     * two-digit-year presentation string rather than a calendar value, and the work field it is built
     * in is initialised to something that is not a valid date at all, so no temporal type could hold
     * it and parsing or reformatting would alter what the screen shows.
     */
    public static final int DISPLAYED_DATE_LENGTH = 8;

    /**
     * Width in characters of the row description as this screen presents it: 26. A genuine truncation
     * of the hundred-character stored description; neither that width nor the sixty characters the
     * transaction view and add screens present may be substituted here.
     */
    public static final int DESCRIPTION_LENGTH = 26;

    /**
     * Scale of the row amount: 2 &mdash; the two decimal places of the zoned-decimal, display-usage
     * amount field {@code TRAN-AMT PIC S9(09)V99} declared at {@code app/cpy/CVTRA05Y.cpy}, matching
     * the fixed-scale numeric column the value is persisted in.
     *
     * <p><strong>Stated as the contract, and now checked - but still never applied.</strong> This
     * constant states the scale that amounts crossing this boundary carry; it is not an instruction to
     * rescale one. No scaling call, rounding mode, precision context or numeric formatter appears
     * anywhere in this package. The estate specifies no rounding on any arithmetic statement, so every
     * store into a two-decimal field truncates toward zero, and that truncation is applied in exactly
     * one component &mdash; the module's zoned-decimal codec &mdash; so that a single rounding policy
     * governs the whole module.</p>
     *
     * <p>A value published here is already at this scale, and a value that is not is a defect in the
     * service that produced it. Where this constant previously only <em>described</em> that
     * expectation, the row's canonical constructor now <em>refuses</em> a value that contradicts it.
     * The distinction between refusing and correcting is the whole of the difference: a wrong scale is
     * reported to the producer at construction, and is never quietly repaired into a value the client
     * would then receive as though it had been published that way. A scale that is documented but
     * unchecked is a scale a producer can break without anyone noticing, which is precisely what a
     * published schema promising exact precision must not allow.</p>
     */
    public static final int AMOUNT_SCALE = 2;

    /**
     * The number of integer digits the row amount may carry: 9 &mdash; the nine integer digits of the
     * same {@code TRAN-AMT PIC S9(09)V99} field. With {@link #AMOUNT_SCALE} this gives the total
     * precision of eleven that the relational column declares.
     *
     * <p>Public for the same reason as the scale: a service that builds a row and a test that checks
     * one need one authority for the figure rather than each restating it.</p>
     */
    public static final int AMOUNT_INTEGER_DIGITS = 9;

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
    public static final int DISPLAYED_PAGE_NUMBER_LENGTH = 8;

    public static final int MESSAGE_LENGTH = 78;

    /**
     * Width in characters of a nominated field's identity: 7 - the widest field name this mapset
     * declares.
     */
    public static final int SCREEN_FIELD_ID_LENGTH = 7;

    public static final int SCREEN_TITLE_LENGTH = 40;

    public static final int CURRENT_DATE_LENGTH = 8;

    public static final int CURRENT_TIME_LENGTH = 8;

    public static final int TRANSACTION_NAME_LENGTH = 4;

    public static final int PROGRAM_NAME_LENGTH = 8;

    /**
     * Message reporting that the selection character entered against a row is not the one the screen
     * accepts. <strong>Singular by contract:</strong> this screen accepts exactly one selection
     * character, whereas the administrative user-list screen accepts two and phrases its equivalent
     * in the plural. The two are separate external contracts and must never be unified, generalised
     * or generated from a shared template.
     */
    public static final String MESSAGE_INVALID_SELECTION = "Invalid selection. Valid value is S";

    /**
     * Message reporting that the identifier entered in the search field is not numeric. That the
     * program applies a numeric test to operator input does not make the stored identifier a number:
     * it remains a sixteen-character alphanumeric value in this contract, and the test itself is a
     * service responsibility.
     */
    public static final String MESSAGE_TRAN_ID_NOT_NUMERIC = "Tran ID must be Numeric ...";

    /**
     * Top-of-browse message emitted from the backward attention-key path, before any access is
     * attempted - which is why it says "already". The first of three distinct top-of-browse texts and
     * interchangeable with neither of the others. Informational, not an error.
     */
    public static final String MESSAGE_ALREADY_AT_TOP = "You are already at the top of the page...";

    /**
     * Bottom-of-browse message emitted from the forward attention-key path, before any access is
     * attempted. The counterpart of {@link #MESSAGE_ALREADY_AT_TOP}, and not interchangeable with
     * {@link #MESSAGE_REACHED_BOTTOM}. Informational, not an error.
     */
    public static final String MESSAGE_ALREADY_AT_BOTTOM = "You are already at the bottom of the page...";

    /**
     * Top-of-browse message emitted while positioning the browse, and <strong>deliberately without
     * the word "already"</strong>: the access itself reports where the browse landed rather than
     * declining a request. Informational, not an error.
     */
    public static final String MESSAGE_AT_TOP = "You are at the top of the page...";

    /**
     * Bottom-of-browse message emitted when a forward read reaches the end of the browse, phrased
     * "have reached" because an access was attempted and the end was discovered by its outcome.
     * Informational, not an error.
     */
    public static final String MESSAGE_REACHED_BOTTOM = "You have reached the bottom of the page...";

    /**
     * Top-of-browse message emitted when a backward read reaches the start of the browse, phrased
     * "have reached" for the same reason as {@link #MESSAGE_REACHED_BOTTOM}. The three top texts
     * report the same physical boundary reached by three different mechanisms and each is published
     * separately because each is compared character for character. Informational, not an error.
     */
    public static final String MESSAGE_REACHED_TOP = "You have reached the top of the page...";

    /**
     * Canonical constructor. Replaces the row list with an unmodifiable copy - a {@code null} list
     * becoming an empty one - and leaves every other component exactly as supplied.
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
     * a backward page is deliberately the reverse of its read order. It is not truncated, because
     * discarding a row the browse returned would hide the defect rather than surface it. No component
     * is trimmed, padded, case-folded or reformatted, and no amount is rescaled or rounded: legacy
     * fixed-width values are space-significant, and rescaling belongs to the module's zoned-decimal
     * codec alone.</p>
     *
     * <p><strong>The row count is checked, and checking is not truncating.</strong> The screen declares
     * {@link #ROW_COUNT} row families, so a list holding more rows than that describes a screen that
     * does not exist and could not be presented. It is <em>rejected</em>, which surfaces the defect at
     * the boundary where it can still be attributed, rather than silently dropping the surplus - the
     * outcome the paragraph above rules out. A <em>shorter</em> list is accepted exactly as supplied,
     * because a short final page legitimately carries fewer rows and the legacy screen simply leaves
     * the surplus lines blank, so this is an upper bound and never a fixed length.</p>
     *
     * @throws IllegalArgumentException if the row list holds more rows than the screen has row families
     */
    public TransactionListResponse {
        rows = (rows == null) ? List.of() : List.copyOf(rows);
        if (rows.size() > ROW_COUNT) {
            throw new IllegalArgumentException("rows may hold at most " + ROW_COUNT
                    + " entries, because that is how many row families the transaction-list screen"
                    + " declares, but it holds " + rows.size());
        }
    }

    /**
     * The number of row families the transaction-list screen declares: 10.
     *
     * <p>Screen shape rather than a tuning figure - the number of lines the operator sees. The map
     * declares ten row families in {@code app/cpy-bms/COTRN00.CPY}, and the program's own bounds agree:
     * the fill loop runs while the index is not greater than ten at {@code app/cbl/COTRN00C.cbl} line
     * 290 and the row walk stops at eleven at line 297. Stated here because it is the one thing about
     * a page this contract can check, and left absent from the request contract's row-count reasoning
     * for the reason recorded there.</p>
     *
     * <p>It is not a page size a caller may choose, not a limit a client may raise or lower, and not a
     * performance guard; decision log entry DL-073 records that the module asserts no performance
     * target at all.</p>
     */
    public static final int ROW_COUNT = 10;

    /**
     * One row of the transaction-list screen &mdash; the five values a single row family of
     * {@code app/cpy-bms/COTRN00.CPY} presents, modelled once instead of ten times.
     *
     * <p>Every family has an identical shape, so one record type carried in a list reproduces the
     * screen exactly while collapsing what would otherwise be fifty discrete components. The
     * generated field-name suffixes are not reproduced - they are inconsistent artefacts of how the
     * map generator forms names and carry no meaning - and a row's position is its index in the
     * enclosing list. The generated length, flag and attribute items and the terminal-area filler are
     * likewise absent as terminal plumbing.</p>
     *
     * <p>Every component may be {@code null} or blank, because a row the browse did not fill is blank
     * on the legacy screen, and none is validated beyond its declared width.</p>
     *
     * @param selection the selection indicator echoed back for this row. Echoed only: nothing here
     *     interprets it. Blank is the ordinary state of an unselected row.
     * @param transactionId the transaction identifier, sixteen alphanumeric characters. Text, never a
     *     number: leading zeros and the external width are contractual.
     * @param displayedDate the date as this screen renders it, carried verbatim and never parsed,
     *     reformatted or widened; see {@link TransactionListResponse#DISPLAYED_DATE_LENGTH}.
     * @param description the description as this screen presents it - a genuine truncation of the
     *     stored value; see {@link TransactionListResponse#DESCRIPTION_LENGTH}.
     * @param amount the transaction amount as an exact decimal at a scale of two. Legitimately
     *     negative for returns, which is why no sign constraint applies. The numeric value only: the
     *     edited form the screen displays is never carried here, and nothing in this package
     *     rescales, rounds or formats it. May be {@code null} where the row is blank.
     */
    public record TransactionRow(
            @Size(max = TransactionListResponse.SELECTION_LENGTH) String selection,
            @Size(max = TransactionListResponse.TRANSACTION_ID_LENGTH) String transactionId,
            @Size(max = TransactionListResponse.DISPLAYED_DATE_LENGTH) String displayedDate,
            @Size(max = TransactionListResponse.DESCRIPTION_LENGTH) String description,
            @Schema(description = "Row amount. Record field TRAN-AMT of CVTRA05Y.cpy: a signed zoned "
                    + "decimal with nine integer digits and two decimal places, so total precision 11 "
                    + "and scale exactly 2. The twelve-character edited form the screen displays is a "
                    + "rendering built by the program and is deliberately not carried here; this is "
                    + "the numeric value alone.")
            BigDecimal amount) {

        /**
         * Canonical constructor. Stores every component exactly as supplied and refuses an amount whose
         * decimal shape contradicts the record field it represents.
         *
         * <p>Nothing is copied, because every component is already immutable. Nothing is defaulted or
         * normalised: blank and space-padded values are ordinary states of a legacy fixed-width screen
         * row and cross this boundary untouched.</p>
         *
         * <p><strong>The amount is still not rescaled.</strong> It must cross this boundary at exactly
         * the scale the service published it at, and it does - this constructor performs no rescaling,
         * no rounding, no truncation and no formatting, and it changes no byte of any component. What it
         * adds is a refusal: an amount whose scale is not {@link #AMOUNT_SCALE}, or which needs more
         * than {@link #AMOUNT_INTEGER_DIGITS} integer digits, cannot be what the record field holds, so
         * the producer is told at construction instead of the client receiving a payload whose precision
         * silently contradicts the published schema. Refusing a wrong value and repairing one are
         * different acts, and only the second would change the bytes the screen presents. A
         * {@code null} amount is accepted untouched, because a blank row legitimately carries none.</p>
         *
         * @throws IllegalArgumentException if {@code amount} carries a scale other than
         *     {@link #AMOUNT_SCALE} or needs more than {@link #AMOUNT_INTEGER_DIGITS} integer digits
         */
        public TransactionRow {
            requireRecordShape(amount);
        }

        /**
         * Returns a diagnostic representation of one row that discloses neither the transaction it
         * identifies nor the amount it moved.
         *
         * <p><strong>Why the implicit record rendering could not stand.</strong> A record's generated
         * {@code toString()} prints every component. A page holds up to ten rows, so a single
         * stringified response would have emitted ten transaction identifiers beside their descriptions
         * and their amounts - a complete statement extract in one log line. Any structured logger,
         * framework diagnostic, failed assertion, exception message or string interpolation touching a
         * row would have produced it.</p>
         *
         * <p><strong>Why the remainder is retained.</strong> The echoed selection character and the
         * displayed date identify nobody on their own: the date is the screen's own rendering of when a
         * movement was processed, and the selection is which line the operator marked. They are the part
         * of a row worth seeing in a diagnostic.</p>
         *
         * <p><strong>Withholding is confined to this method.</strong> Every accessor returns its
         * component unaltered and the serialized payload is unaffected, because the screen presents the
         * identifier and the amount in full.</p>
         *
         * @return the row layout with the identifier, the description and the amount replaced by a
         *     fixed placeholder
         */
        @Override
        public String toString() {
            return "TransactionRow["
                    + "selection=" + selection
                    + ", transactionId=" + REDACTION_PLACEHOLDER
                    + ", displayedDate=" + displayedDate
                    + ", description=" + REDACTION_PLACEHOLDER
                    + ", amount=" + REDACTION_PLACEHOLDER
                    + "]";
        }
    }

    /**
     * Confirms that a row amount has the decimal shape of the record field it represents.
     *
     * <p>Reads only the amount's own scale and precision. It performs no arithmetic on the value, does
     * not re-scale it, does not round it and does not format it, so it cannot change what the client
     * receives. The failure text names the offending scale or digit count and never the amount itself,
     * so a rejected value cannot reach a log through the diagnostic that reports it.
     *
     * @param amount the amount to check, or {@code null} for a blank row
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

    /** Fixed text substituted for every regulated value in the two renderings below. */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Returns a diagnostic representation that mirrors the response layout and discloses no regulated
     * value.
     *
     * <p><strong>Why the implicit record rendering could not stand.</strong> A record's generated
     * {@code toString()} prints every component, and two of these carry regulated content: the row list
     * holds up to ten transaction identifiers with their descriptions and amounts, and the browse cursor
     * retains the boundary record keys of the displayed page. The row list is withheld whole rather than
     * per row, as a second line of defence behind each row's own rendering, and the cursor is withheld
     * whole because its own rendering does not withhold its keys and this type must not become the path
     * by which they surface. The echoed search key is withheld for the same reason the rows are: it is
     * the identifier the operator was looking for.</p>
     *
     * <p><strong>Why the remainder is retained.</strong> The screen furniture, the page indicator, the
     * summary message, the error indicator and the nominated field are presentation state that
     * identifies nobody, and they are the part of a response worth seeing in a diagnostic. The row count
     * is reported in place of the rows, because how many rows a page carried is the useful
     * non-identifying fact about it. The navigation state is printed by delegation because it withholds
     * its own identifying values.</p>
     *
     * <p><strong>Withholding is confined to this method.</strong> Every accessor returns its component
     * exactly as supplied and the serialized payload is unaffected.</p>
     *
     * @return the response layout with each regulated component replaced by a fixed placeholder
     */
    @Override
    public String toString() {
        return "TransactionListResponse["
                + "rowCount=" + rows.size()
                + ", rows=" + REDACTION_PLACEHOLDER
                + ", pageMetadata=" + REDACTION_PLACEHOLDER
                + ", navigationContext=" + navigationContext
                + ", nextRoute=" + nextRoute
                + ", transactionIdFilter=" + REDACTION_PLACEHOLDER
                + ", displayedPageNumber=" + displayedPageNumber
                + ", message=" + message
                + ", error=" + error
                + ", focusScreenFieldId=" + focusScreenFieldId
                + ", title01=" + title01
                + ", title02=" + title02
                + ", currentDate=" + currentDate
                + ", currentTime=" + currentTime
                + ", transactionName=" + transactionName
                + ", programName=" + programName
                + "]";
    }
}
