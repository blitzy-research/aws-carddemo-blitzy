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
package com.carddemo.util;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Sole holder of the 133-byte Daily Transaction Report record layout and of the two
 * distinct fifteen-character numeric-edited amount masks that the legacy report uses.
 *
 * <h2>The record</h2>
 * <p>The report record is a single fixed-length alphanumeric item named
 * {@code FD-REPTFILE-REC}, 133 bytes wide, declared at [app/cbl/CBTRN03C.cbl:L85]; the output
 * dataset that receives it is declared with a record length of 133 and fixed-blocked records
 * [app/proc/TRANREPT.prc]. Every record written to the report is therefore exactly 133 bytes.
 *
 * <p>Layout authority is split across two members and both were read verbatim. The seven
 * report groups are declared in the report-formatting copybook [app/cpy/CVTRA07Y.cpy], which
 * the report driver includes [app/cbl/CBTRN03C.cbl:L113]. The record itself, the blank line,
 * the page size and the date-parameter structure are declared in the driver.
 *
 * <p><strong>Six of the seven groups are narrower than 133 bytes</strong> and are therefore
 * left-justified and space-padded on the right to 133, exactly as a COBOL move of a short group into
 * a {@code PIC X(133)} record does. Only the rule line is natively 133 bytes and therefore the only
 * group needing no padding. The 133-byte image carries <strong>no line terminator</strong>: record
 * separation is the writer's concern in the batch layer (decision D-30), so no carriage return, line
 * feed or platform line separator is ever embedded here, and every text field is additionally
 * rejected outright if it contains a control character, so a terminator cannot enter through caller
 * data either.
 *
 * <p><strong>Group 1 - report name header. Native width 115, padded with 18 spaces.</strong>
 * <pre>
 * offset  width  content
 * ------  -----  ---------------------------------------------------------------
 *      0     38  literal 'DALYREPT' (8 characters) then 30 spaces
 *     38     41  literal 'Daily Transaction Report' (24 characters) then 17 spaces
 *     79     12  literal 'Date Range: ' - 12 characters including the trailing space
 *     91     10  start date, substituted
 *    101      4  literal ' to ' - a leading space, 'to', a trailing space
 *    105     10  end date, substituted
 *
 * 38 + 41 + 12 + 10 + 4 + 10 = 115, then 115 + 18 = 133
 * </pre>
 *
 * <p><strong>Group 2 - transaction detail line. Native width 114, padded with 19 spaces.</strong>
 * <pre>
 * offset  width  content
 * ------  -----  ---------------------------------------------------------------
 *      0     16  transaction id, X(16)
 *     16      1  filler, space
 *     17     11  account id, X(11)
 *     28      1  filler, space
 *     29      2  transaction type code, X(02)
 *     31      1  FILLER with a literal value of '-'
 *     32     15  transaction type description, X(15), truncated from X(50)
 *     47      1  filler, space
 *     48      4  transaction category code, PIC 9(04), left zero-filled
 *     52      1  FILLER with a literal value of '-'
 *     53     29  transaction category description, X(29), truncated from X(50)
 *     82      1  filler, space
 *     83     10  transaction source, X(10)
 *     93      4  filler, spaces
 *     97     15  amount, PIC -ZZZ,ZZZ,ZZZ.ZZ
 *    112      2  filler, spaces
 *
 * 16+1+11+1+2+1+15+1+4+1+29+1+10+4+15+2 = 114, then 114 + 19 = 133
 * </pre>
 *
 * <p><strong>The two hyphen separators at offsets 31 and 52 always survive.</strong> The driver's
 * detail-writing paragraph initialises the group before moving field values into it
 * [app/cbl/CBTRN03C.cbl:L361-L362], and an {@code INITIALIZE} does not touch {@code FILLER} items.
 * Both hyphens are declared as {@code FILLER} carrying a literal value [app/cpy/CVTRA07Y.cpy:L21] and
 * [app/cpy/CVTRA07Y.cpy:L25], so they persist across every re-use of the group and are emitted
 * unconditionally here: they are fixed layout bytes, not decoration.
 *
 * <p><strong>The category code is {@code PIC 9(04)}, not {@code X(04)}</strong>
 * [app/cpy/CVTRA07Y.cpy:L24]. It is the only numeric-display field in the seven groups and therefore
 * the only place left zero-fill applies: category 1 renders as 0001, never as three spaces followed
 * by 1 and never as 1 followed by three spaces.
 *
 * <p><strong>Two truncations, at two different widths, in one line.</strong> The type description
 * arrives from a {@code PIC X(50)} reference field [app/cpy/CVTRA03Y.cpy] into {@code X(15)}; the
 * category description arrives from a {@code PIC X(50)} reference field [app/cpy/CVTRA04Y.cpy] into
 * {@code X(29)}. Both are silent right-hand truncations exactly as a COBOL move performs them: no
 * abbreviation, no ellipsis, no word wrap. The seeded category reference data contains a description
 * of exactly 29 characters, 'Sales draft credit adjustment', which sits precisely on the boundary, so
 * an off-by-one in the 29-byte field is immediately visible.
 *
 * <p><strong>Group 3 - column header line. Native width 114, padded with 19 spaces.</strong>
 * <pre>
 * offset  width  content
 * ------  -----  ---------------------------------------------------------------
 *      0     17  literal 'Transaction ID' (14 characters) then 3 spaces
 *     17     12  literal 'Account ID' (10 characters) then 2 spaces
 *     29     19  literal 'Transaction Type' (16 characters) then 3 spaces
 *     48     35  literal 'Tran Category' (13 characters) then 22 spaces
 *     83     14  literal 'Tran Source' (11 characters) then 3 spaces
 *     97      1  filler, space - declared as a bare PIC X, which is ONE byte
 *     98     16  literal with EIGHT leading spaces then 'Amount', 14 characters,
 *                then 2 trailing spaces
 *
 * 17 + 12 + 19 + 35 + 14 + 1 + 16 = 114, then 114 + 19 = 133
 * </pre>
 *
 * <p><strong>The bare {@code PIC X} at offset 97 is one byte</strong> [app/cpy/CVTRA07Y.cpy:L44], not
 * the default width of some wider field; widening it would shift the whole Amount column by the error
 * and break alignment with the detail line. The eight leading spaces inside the Amount header literal
 * are equally significant: they right-align the word Amount against the detail amount field, so the
 * header's Amount occupies offsets 106 through 111 and the detail amount field ends at offset 111.
 * That shared right edge is the visual contract of the report.
 *
 * <p><strong>Group 4 - the rule line. Native width 133, padded with 0 spaces.</strong> A single
 * elementary item of 133 hyphens [app/cpy/CVTRA07Y.cpy:L48], the only group already 133 bytes wide.
 * It must not be confused with the statement rule lines: the text statement carries three rule lines
 * of 80 hyphens each, held in the statement text templates, while the report carries one of 133
 * hyphens. Four rule lines exist across the two output formats at two different widths and they are
 * not interchangeable.
 *
 * <p><strong>Groups 5, 6 and 7 - the three total lines. Native width 112 each, padded with 21
 * spaces.</strong>
 * <pre>
 * group           source                     label field          dot fill  amount field
 * --------------  -------------------------  -------------------  --------  ----------------------
 * page totals     CVTRA07Y.cpy L50-L54       X(11) 'Page Total'         86  X(15) +ZZZ,ZZZ,ZZZ.ZZ
 * account totals  CVTRA07Y.cpy L56-L60       X(13) 'Account Total'      84  X(15) +ZZZ,ZZZ,ZZZ.ZZ
 * grand totals    CVTRA07Y.cpy L62-L66       X(11) 'Grand Total'        86  X(15) +ZZZ,ZZZ,ZZZ.ZZ
 *
 * 11 + 86 + 15 = 112,  13 + 84 + 15 = 112,  11 + 86 + 15 = 112, each then + 21 = 133
 * </pre>
 *
 * <p><strong>The dot-fill widths differ - 86, 84, 86 - and the difference is deliberate.</strong> The
 * three label fields are 11, 13 and 11 bytes wide and the dot fill compensates so that all three
 * amount fields begin at offset 97: 11 + 86 = 97, 13 + 84 = 97 and 11 + 86 = 97. Offset 97 is the
 * detail line's amount offset, and offset 111 is the shared right edge with the column header's
 * Amount. That column alignment across four different groups is the report's contract, so each
 * dot-fill width is hardcoded from the copybook as its own named constant; none is computed from
 * another and none is shared. The fill character is the ASCII full stop, not a hyphen, an underscore
 * or a middle dot.
 *
 * <p><strong>The two fifteen-character masks - not interchangeable.</strong> Both are exactly 15
 * characters wide and both place the sign in a fixed leftmost position, because a single plus or minus
 * in a COBOL picture is a <em>fixed</em> insertion character and not a floating one. Both suppress
 * leading zeros, and the comma insertions inside the suppressed region are suppressed along with the
 * digits.
 * <pre>
 * mask                  used by                                     sign behaviour
 * --------------------  ------------------------------------------  --------------------------
 * PIC -ZZZ,ZZZ,ZZZ.ZZ   the detail amount only, group 2 offset 97   negative gives '-' in
 *                       [app/cpy/CVTRA07Y.cpy:L30]                  position 1; zero or
 *                                                                   positive gives a SPACE.
 *                                                                   Never a plus.
 * PIC +ZZZ,ZZZ,ZZZ.ZZ   all three total amounts, groups 5, 6 and 7  negative gives '-' in
 *                       at offset 97 [app/cpy/CVTRA07Y.cpy:L54],    position 1; zero or
 *                       [app/cpy/CVTRA07Y.cpy:L60],                 positive gives '+'.
 *                       [app/cpy/CVTRA07Y.cpy:L66]                  ALWAYS SIGNED.
 * </pre>
 *
 * <p>Composition of each mask: 1 sign position, then nine zero-suppression digit positions carrying
 * two embedded commas for 11 positions, then the decimal point, then two more zero-suppression digit
 * positions. 1 + 11 + 1 + 2 = 15.
 * <pre>
 * position  0  1  2  3  4  5  6  7  8  9 10 11 12 13 14
 * content   S  d1 d2 d3  ,  d4 d5 d6  ,  d7 d8 d9  .  f1 f2
 * </pre>
 *
 * <p><strong>Worked examples.</strong> A positive detail amount of 500.47 renders as nine spaces then
 * 500.47, fifteen characters in all. A negative 603.22 renders as a minus in position 1, then
 * <em>eight</em> spaces, then 603.22: the sign sits at the far left, separated from the digits by the
 * suppressed positions, and <strong>does not float up against the first digit</strong>, so seven spaces
 * followed by a minus and 603.22 is wrong. The totals mask behaves identically with a plus for the
 * space.
 *
 * <p><strong>A value of exactly zero blanks the entire field.</strong> Every digit position in both
 * masks is a zero-suppression symbol, including the two decimal positions, and under the COBOL
 * zero-suppression rule an item whose every numeric position is a suppression symbol is set to spaces
 * <em>including its editing characters</em> when the value is zero - fifteen spaces, no sign, no zero
 * digit, no decimal point. Counterintuitive, and required. For a value that is <em>not</em> zero,
 * suppression affects only the leading zeros left of the decimal point, so the two decimal digits
 * always print and a comma prints only when a digit to its left has printed; 0.47 therefore renders as
 * twelve spaces followed by the decimal point and 47.
 *
 * <p><strong>These are not the statement masks.</strong> The statement text templates hold two
 * <em>thirteen</em>-character <em>trailing-minus</em> masks, one zero-suppressed and one not; this
 * class holds two <em>fifteen</em>-character <em>leading-sign, comma-grouped</em> masks. Four distinct
 * masks therefore exist across the two output formats and none is reusable: the differences in width,
 * sign position, sign presence and grouping mean any shared abstraction would silently corrupt one of
 * the four. The two masks here are rendered by two fully independent methods with no shared mask
 * helper, no sign parameter, no boolean flag and no enum switch between them.
 *
 * <p><strong>Numeric representation, and why the formatting library is not used.</strong>
 * {@code BigDecimal.toString()} is never called, and neither is anything in {@code java.text} nor
 * {@code String.format} with a locale-sensitive conversion (decision D-27). Each mask is assembled
 * digit by digit from an explicitly scaled {@code BigDecimal} so that no locale can substitute a
 * decimal comma for the decimal point, a non-breaking space for the group separator or a typographic
 * minus glyph for the ASCII hyphen-minus; {@code BigDecimal.toString()} is further unsuitable because
 * it may emit scientific notation. Digits come from the unscaled value's absolute magnitude, whose
 * radix-ten string form contains only ASCII digits, and every character is nevertheless validated.
 *
 * <p><strong>Scale and magnitude contract.</strong> The detail amount arrives from a
 * {@code PIC S9(09)V99} transaction amount [app/cpy/CVTRA05Y.cpy] and the three totals accumulate in
 * {@code PIC S9(09)V99} work fields [app/cbl/CBTRN03C.cbl:L134-L136] - nine integer digits and two
 * decimals, exactly matching the mask - so values arrive already scaled to two decimal places and
 * already rounded down by the zoned-decimal codec and the services. Two rejections follow, and both
 * are deliberate divergences from the legacy silent move recorded in the decision log rather than
 * decided here. A value whose scale is not exactly 2 is rejected and never re-scaled, per decision
 * D-05, because re-scaling in a formatter would put a second rounding policy into the system; note in
 * particular that a zero must be supplied at scale 2, so a scale-zero zero is rejected. A value whose
 * integer part exceeds nine digits is rejected rather than left-truncated, per decision D-06, because
 * COBOL would silently drop the high-order digits and a silently corrupted money figure in a printed
 * report is worse than a deterministic failure.
 *
 * <p>No arithmetic of any kind is performed on an amount by this class. The report driver contains
 * <strong>zero</strong> {@code COMPUTE} statements - verified by count over [app/cbl/CBTRN03C.cbl] -
 * so the report path introduces no arithmetic and neither does this formatter. There is no addition,
 * no accumulation, no scale adjustment and no rounding mode, and no {@code double}, {@code float} or
 * their wrappers appear anywhere in this class.
 *
 * <p><strong>The blank line and the 21-byte date-parameter record.</strong> The blank line is 133
 * spaces [app/cbl/CBTRN03C.cbl:L133]. It is a real emitted record and not padding, so it is exposed
 * as a constant and takes its place in the header block.
 *
 * <p>The date-parameter input card is 133-unrelated: it is 80 bytes wide
 * [app/cbl/CBTRN03C.cbl:L88], but only its leading 21 bytes are structured
 * [app/cbl/CBTRN03C.cbl:L122-L125] - a 10-byte start date, a one-byte filler space, and a
 * 10-byte end date, so 10 + 1 + 10 = 21. The separator is one space, declared as an unnamed
 * filler exactly one byte wide: not two, not a tab and not a comma.
 *
 * <p>Those 21 bytes are the leading 21 bytes of card 15 of the job-submission image built by
 * the JCL card-image builder - that card is a 10-byte start date, a single-byte space
 * separator, a 10-byte end date and 59 trailing spaces, totalling 80. <strong>The two classes
 * must agree byte for byte on the first 21 bytes.</strong> Both the 21-byte structured width and
 * the 80-byte card width are therefore exposed here as named constants. The 80 named here is
 * the date-parameter card width and nothing else; it is emphatically not the 80-byte statement
 * text record width, which lives in the statement text templates and is never referenced from
 * this class. This class never emits an 80-byte or a 100-byte record.
 *
 * <p>The reader is deliberately asymmetric with the builder: the builder always <em>writes</em>
 * a space separator, while the reader does not <em>require</em> one, because the legacy driver
 * moves the card into the structured record without validating the filler byte. Reading is
 * faithful; writing is canonical.
 *
 * <p><strong>Page breaks and the header block - supported here, owned elsewhere.</strong> Accumulation,
 * page counting and break decisions belong to the transaction report service; this class is stateless
 * and holds no line counter, page number, running total, first-pass flag or card-number break tracker.
 * It nevertheless exposes everything the service needs so that no 133-byte knowledge migrates out of
 * the utility layer: the page size is 20 [app/cbl/CBTRN03C.cbl:L131], a factual layout figure and not a
 * tuning parameter; the break test is a modulo of the line counter against it
 * [app/cbl/CBTRN03C.cbl:L282], with counter and test both living in the service; and the header block
 * is four records in one exact order - name header, blank line, column header, rule line - each
 * incrementing the line counter by one [app/cbl/CBTRN03C.cbl:L324-L341], exposed as an ordered,
 * unmodifiable four-element list so the order cannot be got wrong at the call site. The page size is
 * declared as a packed-decimal work field, one of the estate's nine such sites, all transient
 * working-storage and none a persisted layout, so no packed-decimal decoding is required anywhere in
 * this module (decision D-01).
 *
 * <p><strong>Anomaly - duplicate paragraph-number prefixes in the report driver.</strong> The prefix
 * 1110 appears twice, on the page-totals writer [app/cbl/CBTRN03C.cbl:L293] and the grand-totals
 * writer [app/cbl/CBTRN03C.cbl:L318]; the prefix 1120 appears three times, on the account-totals
 * writer [app/cbl/CBTRN03C.cbl:L306], the header writer [app/cbl/CBTRN03C.cbl:L324] and the detail
 * writer [app/cbl/CBTRN03C.cbl:L361]. The Java methods here are distinctly named and the original
 * paragraph names are carried as row 23 of the source anomaly register.
 *
 * <p><strong>Failure contract.</strong> Every builder asserts its result at exactly 133 encoded bytes
 * before returning; every mask renderer asserts 15; the date-parameter builder asserts 21. All widths
 * are US-ASCII encoded bytes and never character counts. A {@code null} argument raises
 * {@link NullPointerException} through {@link Objects#requireNonNull(Object, String)} with a message
 * naming the field. A non-null caller-supplied value that cannot be rendered - a wrong scale, an
 * integer part that is too large, a category code outside four unsigned digits, or text containing a
 * character outside printable US-ASCII - raises {@link IllegalArgumentException}. An internal
 * invariant breach, such as a constant that no longer measures its declared width or a field placed
 * at an offset that does not fit its group, raises {@link IllegalStateException}.
 *
 * <p>Nothing from the application's own exception package is imported, per decision D-11: none of those
 * types models a fixed-width rendering violation, since they model file status, record-not-found,
 * business-input validation, optimistic-lock conflict, job submission and abend. Every message names
 * the field together with the expected and actual width or scale, so a byte-parity failure is
 * diagnosable from the message alone. Text fields are rejected rather than silently blanked when
 * {@code null}, because a missing description in a printed report is a defect worth surfacing;
 * characters outside printable US-ASCII are likewise rejected, which keeps one encoded byte per
 * character and structurally prevents a control character or line terminator from entering the record.
 *
 * <p>This class is stateless, side-effect free and thread-safe. It performs no input or output, reads
 * no clock, no environment and no randomness, holds no mutable static state and exposes no mutable
 * array or collection.
 */
public final class ReportLineFormatter {

    // Character constants. Several share an ASCII code point but carry distinct contractual
    // roles, and are therefore named separately so that a change to one cannot silently move
    // the other.

    /** ASCII space, the padding character for every alphanumeric field and every group pad. */
    private static final char SPACE = ' ';

    /** ASCII hyphen, the rule-line fill and the two detail-line filler separators. */
    private static final char HYPHEN = '-';

    /** ASCII full stop, the total-line dot fill. */
    private static final char DOT = '.';

    /** ASCII full stop in its role as the mask decimal point. */
    private static final char DECIMAL_POINT = '.';

    /** ASCII comma, the mask group separator. */
    private static final char GROUP_SEPARATOR = ',';

    /** ASCII plus, the always-signed total mask's non-negative sign. */
    private static final char PLUS_SIGN = '+';

    /** ASCII hyphen in its role as the mask negative sign. */
    private static final char MINUS_SIGN = '-';

    /** ASCII zero, the left-fill character of the {@code PIC 9(04)} category code. */
    private static final char ZERO_DIGIT = '0';

    /** ASCII nine, the upper bound of the digit range accepted from a magnitude string. */
    private static final char NINE_DIGIT = '9';

    /** Lowest character accepted in an alphanumeric field: ASCII space, 0x20. */
    private static final char FIRST_PRINTABLE_ASCII = ' ';

    /** Highest character accepted in an alphanumeric field: ASCII tilde, 0x7E. */
    private static final char LAST_PRINTABLE_ASCII = '~';

    // Record and group widths. [app/cbl/CBTRN03C.cbl:L85], [app/proc/TRANREPT.prc],
    // [app/cpy/CVTRA07Y.cpy].

    /** Width in bytes of every record written to the report: {@code PIC X(133)}. */
    public static final int REPORT_RECORD_WIDTH = 133;

    /** Native width of the report name header group before padding. */
    public static final int NAME_HEADER_NATIVE_WIDTH = 115;

    /** Native width of the transaction detail group before padding. */
    public static final int DETAIL_LINE_NATIVE_WIDTH = 114;

    /** Native width of the column header group before padding. */
    public static final int COLUMN_HEADER_NATIVE_WIDTH = 114;

    /** Native width of the rule line: the only group already at the record width. */
    public static final int RULE_LINE_NATIVE_WIDTH = 133;

    /** Native width of the page-total group before padding. */
    public static final int PAGE_TOTAL_LINE_NATIVE_WIDTH = 112;

    /** Native width of the account-total group before padding. */
    public static final int ACCOUNT_TOTAL_LINE_NATIVE_WIDTH = 112;

    /** Native width of the grand-total group before padding. */
    public static final int GRAND_TOTAL_LINE_NATIVE_WIDTH = 112;

    /** Spaces appended to the report name header to reach the record width. */
    public static final int NAME_HEADER_PAD_WIDTH = 18;

    /** Spaces appended to the transaction detail line to reach the record width. */
    public static final int DETAIL_LINE_PAD_WIDTH = 19;

    /** Spaces appended to the column header line to reach the record width. */
    public static final int COLUMN_HEADER_PAD_WIDTH = 19;

    /** Spaces appended to the rule line to reach the record width: none. */
    public static final int RULE_LINE_PAD_WIDTH = 0;

    /** Spaces appended to the page-total line to reach the record width. */
    public static final int PAGE_TOTAL_LINE_PAD_WIDTH = 21;

    /** Spaces appended to the account-total line to reach the record width. */
    public static final int ACCOUNT_TOTAL_LINE_PAD_WIDTH = 21;

    /** Spaces appended to the grand-total line to reach the record width. */
    public static final int GRAND_TOTAL_LINE_PAD_WIDTH = 21;

    // Load-bearing offsets and field widths shared across groups.

    /** Width of both numeric-edited amount masks: {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} is 15 bytes. */
    public static final int AMOUNT_MASK_WIDTH = 15;

    /**
     * Offset of the detail line's first filler separator, which always holds a hyphen
     * [app/cpy/CVTRA07Y.cpy:L21].
     */
    public static final int TYPE_CODE_SEPARATOR_OFFSET = 31;

    /**
     * Offset of the detail line's second filler separator, which always holds a hyphen
     * [app/cpy/CVTRA07Y.cpy:L25].
     */
    public static final int CATEGORY_CODE_SEPARATOR_OFFSET = 52;

    /**
     * Offset at which the amount field begins in the detail line and in all three total lines.
     * The shared offset is what aligns the four amount columns and their right edge at 111.
     */
    public static final int AMOUNT_OFFSET = 97;

    /** Dot-fill width of the page-total line [app/cpy/CVTRA07Y.cpy:L53]. 11 + 86 = 97. */
    public static final int PAGE_TOTAL_DOT_FILL_WIDTH = 86;

    /** Dot-fill width of the account-total line [app/cpy/CVTRA07Y.cpy:L59]. 13 + 84 = 97. */
    public static final int ACCOUNT_TOTAL_DOT_FILL_WIDTH = 84;

    /** Dot-fill width of the grand-total line [app/cpy/CVTRA07Y.cpy:L65]. 11 + 86 = 97. */
    public static final int GRAND_TOTAL_DOT_FILL_WIDTH = 86;

    /** Width of the detail line's transaction type description: truncated from 50 bytes. */
    public static final int TYPE_DESCRIPTION_WIDTH = 15;

    /** Width of the detail line's transaction category description: truncated from 50 bytes. */
    public static final int CATEGORY_DESCRIPTION_WIDTH = 29;

    /** Width of a date field wherever one appears in the report or the parameter card. */
    public static final int DATE_WIDTH = 10;

    /** Width of the detail line's transaction id field. */
    public static final int TRANSACTION_ID_WIDTH = 16;

    /** Width of the detail line's account id field. */
    public static final int ACCOUNT_ID_WIDTH = 11;

    /** Width of the detail line's transaction type code field. */
    public static final int TYPE_CODE_WIDTH = 2;

    /**
     * Width of the detail line's transaction category code field. The field is
     * {@code PIC 9(04)}, so it is left zero-filled rather than space-padded.
     */
    public static final int CATEGORY_CODE_WIDTH = 4;

    /** Width of the detail line's transaction source field. */
    public static final int SOURCE_WIDTH = 10;

    // Page break and date parameter contract. [app/cbl/CBTRN03C.cbl:L131],
    // [app/cbl/CBTRN03C.cbl:L88], [app/cbl/CBTRN03C.cbl:L122-L125].

    /**
     * Report page size, 20 detail-and-header lines [app/cbl/CBTRN03C.cbl:L131]. Published for
     * the service that owns the line counter and the modulo break test; this class implements
     * neither. This is a factual layout constant read from the source, not a tuning figure.
     */
    public static final int PAGE_SIZE = 20;

    /**
     * Structured width of the date-parameter record: a 10-byte start date, a one-byte space
     * separator and a 10-byte end date [app/cbl/CBTRN03C.cbl:L122-L125]. 10 + 1 + 10 = 21.
     */
    public static final int DATE_PARAMETER_STRUCTURED_WIDTH = 21;

    /**
     * Full width of the date-parameter card as the driver declares it
     * [app/cbl/CBTRN03C.cbl:L88], which is also the width of card 15 of the job-submission
     * image. This is the parameter card width and nothing else; it is not the statement text
     * record width, and this class never emits a record of this width.
     */
    public static final int DATE_PARAMETER_CARD_WIDTH = 80;

    /** Width of the one-byte filler separating the two dates on the parameter card. */
    public static final int DATE_PARAMETER_SEPARATOR_WIDTH = 1;

    /** Offset of the one-byte filler separating the two dates on the parameter card. */
    public static final int DATE_PARAMETER_SEPARATOR_OFFSET = 10;

    /** Offset of the start date on the parameter card. */
    public static final int DATE_PARAMETER_START_DATE_OFFSET = 0;

    /** Offset of the end date on the parameter card. */
    public static final int DATE_PARAMETER_END_DATE_OFFSET = 11;

    /** Number of records in the header block: name header, blank line, column header, rule. */
    public static final int HEADER_BLOCK_RECORD_COUNT = 4;

    // Group 1 - report name header field widths and offsets [app/cpy/CVTRA07Y.cpy:L4-L13].

    private static final int NAME_HEADER_SHORT_NAME_OFFSET = 0;
    private static final int NAME_HEADER_SHORT_NAME_WIDTH = 38;
    private static final int NAME_HEADER_LONG_NAME_OFFSET = 38;
    private static final int NAME_HEADER_LONG_NAME_WIDTH = 41;
    private static final int NAME_HEADER_DATE_LABEL_OFFSET = 79;
    private static final int NAME_HEADER_DATE_LABEL_WIDTH = 12;
    private static final int NAME_HEADER_START_DATE_OFFSET = 91;
    private static final int NAME_HEADER_TO_LITERAL_OFFSET = 101;
    private static final int NAME_HEADER_TO_LITERAL_WIDTH = 4;
    private static final int NAME_HEADER_END_DATE_OFFSET = 105;

    private static final String NAME_HEADER_SHORT_NAME_TEXT = "DALYREPT";
    private static final String NAME_HEADER_LONG_NAME_TEXT = "Daily Transaction Report";
    private static final String NAME_HEADER_DATE_LABEL_TEXT = "Date Range: ";
    private static final String NAME_HEADER_TO_LITERAL_TEXT = " to ";

    // Group 2 - transaction detail field offsets [app/cpy/CVTRA07Y.cpy:L15-L31].

    private static final int DETAIL_TRANSACTION_ID_OFFSET = 0;
    private static final int DETAIL_FILLER_1_OFFSET = 16;
    private static final int DETAIL_ACCOUNT_ID_OFFSET = 17;
    private static final int DETAIL_FILLER_2_OFFSET = 28;
    private static final int DETAIL_TYPE_CODE_OFFSET = 29;
    private static final int DETAIL_TYPE_DESCRIPTION_OFFSET = 32;
    private static final int DETAIL_FILLER_3_OFFSET = 47;
    private static final int DETAIL_CATEGORY_CODE_OFFSET = 48;
    private static final int DETAIL_CATEGORY_DESCRIPTION_OFFSET = 53;
    private static final int DETAIL_FILLER_4_OFFSET = 82;
    private static final int DETAIL_SOURCE_OFFSET = 83;
    private static final int DETAIL_FILLER_5_OFFSET = 93;
    private static final int DETAIL_FILLER_5_WIDTH = 4;
    private static final int DETAIL_FILLER_6_OFFSET = 112;
    private static final int DETAIL_FILLER_6_WIDTH = 2;
    private static final int SINGLE_BYTE_FILLER_WIDTH = 1;

    // Group 3 - column header field widths and offsets [app/cpy/CVTRA07Y.cpy:L33-L46].

    private static final int COLUMN_HEADER_TRANSACTION_ID_OFFSET = 0;
    private static final int COLUMN_HEADER_TRANSACTION_ID_WIDTH = 17;
    private static final int COLUMN_HEADER_ACCOUNT_ID_OFFSET = 17;
    private static final int COLUMN_HEADER_ACCOUNT_ID_WIDTH = 12;
    private static final int COLUMN_HEADER_TYPE_OFFSET = 29;
    private static final int COLUMN_HEADER_TYPE_WIDTH = 19;
    private static final int COLUMN_HEADER_CATEGORY_OFFSET = 48;
    private static final int COLUMN_HEADER_CATEGORY_WIDTH = 35;
    private static final int COLUMN_HEADER_SOURCE_OFFSET = 83;
    private static final int COLUMN_HEADER_SOURCE_WIDTH = 14;
    private static final int COLUMN_HEADER_BARE_FILLER_OFFSET = 97;
    private static final int COLUMN_HEADER_BARE_FILLER_WIDTH = 1;
    private static final int COLUMN_HEADER_AMOUNT_OFFSET = 98;
    private static final int COLUMN_HEADER_AMOUNT_WIDTH = 16;

    private static final String COLUMN_HEADER_TRANSACTION_ID_TEXT = "Transaction ID";
    private static final String COLUMN_HEADER_ACCOUNT_ID_TEXT = "Account ID";
    private static final String COLUMN_HEADER_TYPE_TEXT = "Transaction Type";
    private static final String COLUMN_HEADER_CATEGORY_TEXT = "Tran Category";
    private static final String COLUMN_HEADER_SOURCE_TEXT = "Tran Source";

    /**
     * The Amount column heading, carrying the eight leading spaces that right-align the word
     * against the detail amount field [app/cpy/CVTRA07Y.cpy:L45-L46]. Placed at offset 98
     * within a 16-byte field, the word occupies offsets 106 through 111.
     */
    private static final String COLUMN_HEADER_AMOUNT_TEXT = "        Amount";

    // Groups 5, 6 and 7 - total line label widths and offsets
    // [app/cpy/CVTRA07Y.cpy:L50-L66].

    private static final int TOTAL_LINE_LABEL_OFFSET = 0;
    private static final int PAGE_TOTAL_LABEL_WIDTH = 11;
    private static final int ACCOUNT_TOTAL_LABEL_WIDTH = 13;
    private static final int GRAND_TOTAL_LABEL_WIDTH = 11;
    private static final int PAGE_TOTAL_DOT_FILL_OFFSET = 11;
    private static final int ACCOUNT_TOTAL_DOT_FILL_OFFSET = 13;
    private static final int GRAND_TOTAL_DOT_FILL_OFFSET = 11;

    private static final String PAGE_TOTAL_LABEL_TEXT = "Page Total";
    private static final String ACCOUNT_TOTAL_LABEL_TEXT = "Account Total";
    private static final String GRAND_TOTAL_LABEL_TEXT = "Grand Total";

    // Numeric-edit mask geometry. Both masks are PIC ?ZZZ,ZZZ,ZZZ.ZZ, differing only in the
    // sign character emitted for a non-negative value.

    /** The mask's sign position, a fixed insertion character that never floats. */
    private static final int MASK_SIGN_POSITION = 0;

    /** The mask's decimal point position. */
    private static final int MASK_DECIMAL_POINT_POSITION = 12;

    /** The mask's first fractional digit position. */
    private static final int MASK_FIRST_FRACTION_POSITION = 13;

    /** The mask's second fractional digit position. */
    private static final int MASK_SECOND_FRACTION_POSITION = 14;

    /** The mask's first group separator position, after the third integer digit. */
    private static final int MASK_FIRST_GROUP_SEPARATOR_POSITION = 4;

    /** The mask's second group separator position, after the sixth integer digit. */
    private static final int MASK_SECOND_GROUP_SEPARATOR_POSITION = 8;

    /** Integer digit index at or before which the first group separator still prints. */
    private static final int MASK_FIRST_GROUP_LAST_DIGIT_INDEX = 2;

    /** Integer digit index at or before which the second group separator still prints. */
    private static final int MASK_SECOND_GROUP_LAST_DIGIT_INDEX = 5;

    /** Integer digit count of the mask, matching {@code PIC S9(09)V99}. */
    private static final int MASK_INTEGER_DIGITS = 9;

    /** Fractional digit count of the mask, matching {@code PIC S9(09)V99}. */
    private static final int MASK_FRACTION_DIGITS = 2;

    /** Total significant digit count of the mask: 9 integer digits plus 2 fractional. */
    private static final int MASK_MAGNITUDE_DIGITS = 11;

    /** The scale every amount must already carry when it reaches this class. */
    private static final int REQUIRED_AMOUNT_SCALE = 2;

    /** Smallest value accepted in a {@code PIC 9(04)} category code field. */
    private static final int MINIMUM_CATEGORY_CODE = 0;

    /** Largest value accepted in a {@code PIC 9(04)} category code field. */
    private static final int MAXIMUM_CATEGORY_CODE = 9999;

    // Emitted constants. Built through the validating fill helper so that a width regression
    // fails at class initialisation rather than at Gate 1.

    /**
     * The blank report record: 133 spaces [app/cbl/CBTRN03C.cbl:L133]. This is a real emitted
     * record, the second of the four records in the header block, and not padding.
     */
    public static final String BLANK_LINE = fixedFill("BLANK_LINE", SPACE, REPORT_RECORD_WIDTH);

    /**
     * The report rule line: 133 hyphens [app/cpy/CVTRA07Y.cpy:L48]. The only group already at
     * the record width, and not to be confused with the statement's 80-hyphen rule lines.
     */
    public static final String RULE_LINE = fixedFill("RULE_LINE", HYPHEN, RULE_LINE_NATIVE_WIDTH);

    /** Not instantiable: this class exposes static members only and holds no state. */
    private ReportLineFormatter() {
        throw new AssertionError("ReportLineFormatter is a static utility and is not instantiable");
    }

    // Group builders. One per report group, each returning exactly 133 encoded bytes.

    /**
     * Builds the report name header, group 1 [app/cpy/CVTRA07Y.cpy:L4-L13]. Traces to the
     * driver's header paragraph, originally named {@code 1120-WRITE-HEADERS}
     * [app/cbl/CBTRN03C.cbl:L324-L341], where the two dates are moved into the group
     * [app/cbl/CBTRN03C.cbl:L277-L278].
     *
     * <p>The group is 115 bytes wide natively and is padded with 18 spaces to the record width.
     * Each date is moved into a 10-byte alphanumeric field, so a shorter date is space-padded on
     * the right and a longer one is truncated, exactly as the legacy move does.
     *
     * @param startDate the reporting window's inclusive start date, carried through verbatim as
     *                  a 10-byte character field; never parsed or reformatted here
     * @param endDate   the reporting window's inclusive end date, same treatment
     * @return the 133-byte report name header record, without a line terminator
     * @throws NullPointerException     if either date is {@code null}
     * @throws IllegalArgumentException if either date contains a character outside printable
     *                                  US-ASCII
     * @throws IllegalStateException    if an internal width invariant is breached
     */
    public static String buildReportNameHeader(String startDate, String endDate) {
        Objects.requireNonNull(startDate, "startDate must not be null for the report name header");
        Objects.requireNonNull(endDate, "endDate must not be null for the report name header");

        char[] group = blankGroup(NAME_HEADER_NATIVE_WIDTH);
        placeField(group, NAME_HEADER_SHORT_NAME_OFFSET,
                moveToAlphanumeric("reportShortName", NAME_HEADER_SHORT_NAME_TEXT,
                        NAME_HEADER_SHORT_NAME_WIDTH),
                NAME_HEADER_SHORT_NAME_WIDTH, "reportShortName");
        placeField(group, NAME_HEADER_LONG_NAME_OFFSET,
                moveToAlphanumeric("reportLongName", NAME_HEADER_LONG_NAME_TEXT,
                        NAME_HEADER_LONG_NAME_WIDTH),
                NAME_HEADER_LONG_NAME_WIDTH, "reportLongName");
        placeField(group, NAME_HEADER_DATE_LABEL_OFFSET,
                moveToAlphanumeric("dateRangeLabel", NAME_HEADER_DATE_LABEL_TEXT,
                        NAME_HEADER_DATE_LABEL_WIDTH),
                NAME_HEADER_DATE_LABEL_WIDTH, "dateRangeLabel");
        placeField(group, NAME_HEADER_START_DATE_OFFSET,
                moveToAlphanumeric("startDate", startDate, DATE_WIDTH),
                DATE_WIDTH, "startDate");
        placeField(group, NAME_HEADER_TO_LITERAL_OFFSET,
                moveToAlphanumeric("toLiteral", NAME_HEADER_TO_LITERAL_TEXT,
                        NAME_HEADER_TO_LITERAL_WIDTH),
                NAME_HEADER_TO_LITERAL_WIDTH, "toLiteral");
        placeField(group, NAME_HEADER_END_DATE_OFFSET,
                moveToAlphanumeric("endDate", endDate, DATE_WIDTH),
                DATE_WIDTH, "endDate");

        return padToRecordWidth("reportNameHeader", new String(group),
                NAME_HEADER_NATIVE_WIDTH, NAME_HEADER_PAD_WIDTH);
    }

    /**
     * Builds the transaction detail line, group 2 [app/cpy/CVTRA07Y.cpy:L15-L31]. Traces to the
     * driver's detail paragraph, originally named {@code 1120-WRITE-DETAIL}
     * [app/cbl/CBTRN03C.cbl:L361-L374] - one of the three paragraphs sharing the 1120 prefix.
     *
     * <p>The group is 114 bytes wide natively and is padded with 19 spaces to the record width.
     * Three behaviours are load-bearing and are reproduced unconditionally:
     * <ul>
     *   <li>a hyphen is written at offset 31 and at offset 52. Both are declared as filler with
     *       a literal value, and the driver's initialise of the group does not touch filler, so
     *       they survive every re-use of the group;</li>
     *   <li>the category code is left zero-filled because the field is {@code PIC 9(04)};</li>
     *   <li>the type description is truncated to 15 bytes and the category description to 29,
     *       both silently, both from 50-byte reference fields.</li>
     * </ul>
     *
     * @param transactionId                   transaction identifier, moved into 16 bytes
     * @param accountId                       account identifier, moved into 11 bytes
     * @param transactionTypeCode             transaction type code, moved into 2 bytes
     * @param transactionTypeDescription      transaction type description, truncated to 15 bytes
     * @param transactionCategoryCode         transaction category code, an unsigned value that
     *                                        must fit four digits, left zero-filled
     * @param transactionCategoryDescription  transaction category description, truncated to 29
     *                                        bytes
     * @param transactionSource               transaction source, moved into 10 bytes
     * @param amount                          the transaction amount, already scaled to two
     *                                        decimal places and already rounded down by the
     *                                        codec, rendered with the detail mask
     * @return the 133-byte transaction detail record, without a line terminator
     * @throws NullPointerException     if any reference argument is {@code null}
     * @throws IllegalArgumentException if any text contains a character outside printable
     *                                  US-ASCII, if the category code is negative or exceeds
     *                                  four digits, or if the amount's scale is not exactly 2
     *                                  or its integer part exceeds nine digits
     * @throws IllegalStateException    if an internal width invariant is breached
     */
    public static String buildTransactionDetailLine(String transactionId,
                                                    String accountId,
                                                    String transactionTypeCode,
                                                    String transactionTypeDescription,
                                                    int transactionCategoryCode,
                                                    String transactionCategoryDescription,
                                                    String transactionSource,
                                                    BigDecimal amount) {
        Objects.requireNonNull(transactionId, "transactionId must not be null for a detail line");
        Objects.requireNonNull(accountId, "accountId must not be null for a detail line");
        Objects.requireNonNull(transactionTypeCode,
                "transactionTypeCode must not be null for a detail line");
        Objects.requireNonNull(transactionTypeDescription,
                "transactionTypeDescription must not be null for a detail line");
        Objects.requireNonNull(transactionCategoryDescription,
                "transactionCategoryDescription must not be null for a detail line");
        Objects.requireNonNull(transactionSource,
                "transactionSource must not be null for a detail line");
        Objects.requireNonNull(amount, "amount must not be null for a detail line");

        char[] group = blankGroup(DETAIL_LINE_NATIVE_WIDTH);
        placeField(group, DETAIL_TRANSACTION_ID_OFFSET,
                moveToAlphanumeric("transactionId", transactionId, TRANSACTION_ID_WIDTH),
                TRANSACTION_ID_WIDTH, "transactionId");
        placeField(group, DETAIL_FILLER_1_OFFSET, singleSpace(), SINGLE_BYTE_FILLER_WIDTH,
                "detailFiller1");
        placeField(group, DETAIL_ACCOUNT_ID_OFFSET,
                moveToAlphanumeric("accountId", accountId, ACCOUNT_ID_WIDTH),
                ACCOUNT_ID_WIDTH, "accountId");
        placeField(group, DETAIL_FILLER_2_OFFSET, singleSpace(), SINGLE_BYTE_FILLER_WIDTH,
                "detailFiller2");
        placeField(group, DETAIL_TYPE_CODE_OFFSET,
                moveToAlphanumeric("transactionTypeCode", transactionTypeCode, TYPE_CODE_WIDTH),
                TYPE_CODE_WIDTH, "transactionTypeCode");

        // The two hyphen separators are fixed layout bytes: filler carrying a literal value,
        // untouched by the driver's initialise, and therefore emitted unconditionally.
        placeField(group, TYPE_CODE_SEPARATOR_OFFSET, singleHyphen(), SINGLE_BYTE_FILLER_WIDTH,
                "typeCodeSeparator");

        placeField(group, DETAIL_TYPE_DESCRIPTION_OFFSET,
                moveToAlphanumeric("transactionTypeDescription", transactionTypeDescription,
                        TYPE_DESCRIPTION_WIDTH),
                TYPE_DESCRIPTION_WIDTH, "transactionTypeDescription");
        placeField(group, DETAIL_FILLER_3_OFFSET, singleSpace(), SINGLE_BYTE_FILLER_WIDTH,
                "detailFiller3");
        placeField(group, DETAIL_CATEGORY_CODE_OFFSET,
                moveToCategoryCode("transactionCategoryCode", transactionCategoryCode),
                CATEGORY_CODE_WIDTH, "transactionCategoryCode");

        placeField(group, CATEGORY_CODE_SEPARATOR_OFFSET, singleHyphen(),
                SINGLE_BYTE_FILLER_WIDTH, "categoryCodeSeparator");

        placeField(group, DETAIL_CATEGORY_DESCRIPTION_OFFSET,
                moveToAlphanumeric("transactionCategoryDescription",
                        transactionCategoryDescription, CATEGORY_DESCRIPTION_WIDTH),
                CATEGORY_DESCRIPTION_WIDTH, "transactionCategoryDescription");
        placeField(group, DETAIL_FILLER_4_OFFSET, singleSpace(), SINGLE_BYTE_FILLER_WIDTH,
                "detailFiller4");
        placeField(group, DETAIL_SOURCE_OFFSET,
                moveToAlphanumeric("transactionSource", transactionSource, SOURCE_WIDTH),
                SOURCE_WIDTH, "transactionSource");
        placeField(group, DETAIL_FILLER_5_OFFSET,
                fixedFill("detailFiller5", SPACE, DETAIL_FILLER_5_WIDTH),
                DETAIL_FILLER_5_WIDTH, "detailFiller5");
        placeField(group, AMOUNT_OFFSET, renderDetailAmount(amount), AMOUNT_MASK_WIDTH,
                "detailAmount");
        placeField(group, DETAIL_FILLER_6_OFFSET,
                fixedFill("detailFiller6", SPACE, DETAIL_FILLER_6_WIDTH),
                DETAIL_FILLER_6_WIDTH, "detailFiller6");

        return padToRecordWidth("transactionDetailLine", new String(group),
                DETAIL_LINE_NATIVE_WIDTH, DETAIL_LINE_PAD_WIDTH);
    }

    /**
     * Builds the column header line, group 3 [app/cpy/CVTRA07Y.cpy:L33-L46]. Traces to the
     * driver's header paragraph [app/cbl/CBTRN03C.cbl:L333].
     *
     * <p>The group is fully literal: it takes no argument and is 114 bytes wide natively, padded
     * with 19 spaces to the record width. Two details are load-bearing: the filler at offset 97
     * is a bare {@code PIC X} and therefore exactly one byte, and the Amount heading carries
     * eight leading spaces so that the word lands on offsets 106 through 111 - the same right
     * edge as the detail amount field.
     *
     * @return the 133-byte column header record, without a line terminator
     * @throws IllegalStateException if an internal width invariant is breached
     */
    public static String buildColumnHeaderLine() {
        char[] group = blankGroup(COLUMN_HEADER_NATIVE_WIDTH);
        placeField(group, COLUMN_HEADER_TRANSACTION_ID_OFFSET,
                moveToAlphanumeric("columnHeaderTransactionId", COLUMN_HEADER_TRANSACTION_ID_TEXT,
                        COLUMN_HEADER_TRANSACTION_ID_WIDTH),
                COLUMN_HEADER_TRANSACTION_ID_WIDTH, "columnHeaderTransactionId");
        placeField(group, COLUMN_HEADER_ACCOUNT_ID_OFFSET,
                moveToAlphanumeric("columnHeaderAccountId", COLUMN_HEADER_ACCOUNT_ID_TEXT,
                        COLUMN_HEADER_ACCOUNT_ID_WIDTH),
                COLUMN_HEADER_ACCOUNT_ID_WIDTH, "columnHeaderAccountId");
        placeField(group, COLUMN_HEADER_TYPE_OFFSET,
                moveToAlphanumeric("columnHeaderType", COLUMN_HEADER_TYPE_TEXT,
                        COLUMN_HEADER_TYPE_WIDTH),
                COLUMN_HEADER_TYPE_WIDTH, "columnHeaderType");
        placeField(group, COLUMN_HEADER_CATEGORY_OFFSET,
                moveToAlphanumeric("columnHeaderCategory", COLUMN_HEADER_CATEGORY_TEXT,
                        COLUMN_HEADER_CATEGORY_WIDTH),
                COLUMN_HEADER_CATEGORY_WIDTH, "columnHeaderCategory");
        placeField(group, COLUMN_HEADER_SOURCE_OFFSET,
                moveToAlphanumeric("columnHeaderSource", COLUMN_HEADER_SOURCE_TEXT,
                        COLUMN_HEADER_SOURCE_WIDTH),
                COLUMN_HEADER_SOURCE_WIDTH, "columnHeaderSource");
        placeField(group, COLUMN_HEADER_BARE_FILLER_OFFSET,
                fixedFill("columnHeaderBareFiller", SPACE, COLUMN_HEADER_BARE_FILLER_WIDTH),
                COLUMN_HEADER_BARE_FILLER_WIDTH, "columnHeaderBareFiller");
        placeField(group, COLUMN_HEADER_AMOUNT_OFFSET,
                moveToAlphanumeric("columnHeaderAmount", COLUMN_HEADER_AMOUNT_TEXT,
                        COLUMN_HEADER_AMOUNT_WIDTH),
                COLUMN_HEADER_AMOUNT_WIDTH, "columnHeaderAmount");

        return padToRecordWidth("columnHeaderLine", new String(group),
                COLUMN_HEADER_NATIVE_WIDTH, COLUMN_HEADER_PAD_WIDTH);
    }

    /**
     * Builds the rule line, group 4 [app/cpy/CVTRA07Y.cpy:L48]: 133 hyphens. Traces to the
     * driver, which writes it after the column header and again after each total line
     * [app/cbl/CBTRN03C.cbl:L337], [app/cbl/CBTRN03C.cbl:L300],
     * [app/cbl/CBTRN03C.cbl:L312].
     *
     * <p>This is the only group already at the record width, so no padding is applied.
     *
     * @return the 133-byte rule line record, without a line terminator
     * @throws IllegalStateException if the rule line no longer measures the record width
     */
    public static String buildRuleLine() {
        return padToRecordWidth("ruleLine", RULE_LINE, RULE_LINE_NATIVE_WIDTH, RULE_LINE_PAD_WIDTH);
    }

    /**
     * Builds the page-total line, group 5 [app/cpy/CVTRA07Y.cpy:L50-L54]. Traces to the driver
     * paragraph originally named {@code 1110-WRITE-PAGE-TOTALS} [app/cbl/CBTRN03C.cbl:L293] -
     * the first of the two paragraphs sharing the 1110 prefix.
     *
     * <p>An 11-byte label, then 86 dots, then the always-signed amount mask at offset 97;
     * 11 + 86 + 15 = 112 natively, padded with 21 spaces to the record width. The 86 is
     * hardcoded from the copybook and is neither derived from nor shared with the other two
     * dot fills.
     *
     * @param amount the page total, already scaled to two decimal places, rendered with the
     *               always-signed total mask
     * @return the 133-byte page-total record, without a line terminator
     * @throws NullPointerException     if the amount is {@code null}
     * @throws IllegalArgumentException if the amount's scale is not exactly 2 or its integer
     *                                  part exceeds nine digits
     * @throws IllegalStateException    if an internal width invariant is breached
     */
    public static String buildPageTotalLine(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null for the page total line");
        return buildTotalLine("pageTotalLine", PAGE_TOTAL_LABEL_TEXT, PAGE_TOTAL_LABEL_WIDTH,
                PAGE_TOTAL_DOT_FILL_OFFSET, PAGE_TOTAL_DOT_FILL_WIDTH,
                PAGE_TOTAL_LINE_NATIVE_WIDTH, PAGE_TOTAL_LINE_PAD_WIDTH, amount);
    }

    /**
     * Builds the account-total line, group 6 [app/cpy/CVTRA07Y.cpy:L56-L60]. Traces to the
     * driver paragraph originally named {@code 1120-WRITE-ACCOUNT-TOTALS}
     * [app/cbl/CBTRN03C.cbl:L306] - one of the three paragraphs sharing the 1120 prefix.
     *
     * <p>A 13-byte label, then 84 dots, then the always-signed amount mask at offset 97;
     * 13 + 84 + 15 = 112 natively, padded with 21 spaces to the record width. The 84 differs
     * from the other two fills precisely because this label is two bytes wider, which is what
     * keeps the amount at offset 97.
     *
     * @param amount the account total, already scaled to two decimal places, rendered with the
     *               always-signed total mask
     * @return the 133-byte account-total record, without a line terminator
     * @throws NullPointerException     if the amount is {@code null}
     * @throws IllegalArgumentException if the amount's scale is not exactly 2 or its integer
     *                                  part exceeds nine digits
     * @throws IllegalStateException    if an internal width invariant is breached
     */
    public static String buildAccountTotalLine(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null for the account total line");
        return buildTotalLine("accountTotalLine", ACCOUNT_TOTAL_LABEL_TEXT,
                ACCOUNT_TOTAL_LABEL_WIDTH, ACCOUNT_TOTAL_DOT_FILL_OFFSET,
                ACCOUNT_TOTAL_DOT_FILL_WIDTH, ACCOUNT_TOTAL_LINE_NATIVE_WIDTH,
                ACCOUNT_TOTAL_LINE_PAD_WIDTH, amount);
    }

    /**
     * Builds the grand-total line, group 7 [app/cpy/CVTRA07Y.cpy:L62-L66]. Traces to the driver
     * paragraph originally named {@code 1110-WRITE-GRAND-TOTALS} [app/cbl/CBTRN03C.cbl:L318] -
     * the second of the two paragraphs sharing the 1110 prefix.
     *
     * <p>An 11-byte label, then 86 dots, then the always-signed amount mask at offset 97;
     * 11 + 86 + 15 = 112 natively, padded with 21 spaces to the record width.
     *
     * @param amount the grand total, already scaled to two decimal places, rendered with the
     *               always-signed total mask
     * @return the 133-byte grand-total record, without a line terminator
     * @throws NullPointerException     if the amount is {@code null}
     * @throws IllegalArgumentException if the amount's scale is not exactly 2 or its integer
     *                                  part exceeds nine digits
     * @throws IllegalStateException    if an internal width invariant is breached
     */
    public static String buildGrandTotalLine(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null for the grand total line");
        return buildTotalLine("grandTotalLine", GRAND_TOTAL_LABEL_TEXT, GRAND_TOTAL_LABEL_WIDTH,
                GRAND_TOTAL_DOT_FILL_OFFSET, GRAND_TOTAL_DOT_FILL_WIDTH,
                GRAND_TOTAL_LINE_NATIVE_WIDTH, GRAND_TOTAL_LINE_PAD_WIDTH, amount);
    }

    /**
     * Returns the blank report record: 133 spaces [app/cbl/CBTRN03C.cbl:L133]. Provided as a
     * method as well as a constant so that every emitted record is obtainable through a uniform
     * accessor at the single write point.
     *
     * @return the 133-byte blank record, without a line terminator
     */
    public static String buildBlankLine() {
        return BLANK_LINE;
    }

    /**
     * Returns the four records of the report header block, in the one order the driver writes
     * them [app/cbl/CBTRN03C.cbl:L324-L341]: the report name header, the blank line, the column
     * header, then the rule line. Each of the four increments the driver's line counter by one.
     *
     * <p>The order is published as an ordered, unmodifiable list precisely so that it cannot be
     * got wrong at the call site. The line counter itself, and the modulo break test that
     * consumes {@link #PAGE_SIZE}, both belong to the service layer and are not implemented
     * here.
     *
     * @param startDate the reporting window's inclusive start date, 10-byte character field
     * @param endDate   the reporting window's inclusive end date, 10-byte character field
     * @return an unmodifiable list of exactly four records, each exactly 133 bytes
     * @throws NullPointerException     if either date is {@code null}
     * @throws IllegalArgumentException if either date contains a character outside printable
     *                                  US-ASCII
     * @throws IllegalStateException    if the block does not contain exactly four records of the
     *                                  record width
     */
    public static List<String> buildHeaderBlock(String startDate, String endDate) {
        Objects.requireNonNull(startDate, "startDate must not be null for the header block");
        Objects.requireNonNull(endDate, "endDate must not be null for the header block");

        List<String> block = List.of(
                buildReportNameHeader(startDate, endDate),
                BLANK_LINE,
                buildColumnHeaderLine(),
                buildRuleLine());

        if (block.size() != HEADER_BLOCK_RECORD_COUNT) {
            throw new IllegalStateException("headerBlock must contain exactly "
                    + HEADER_BLOCK_RECORD_COUNT + " records but contains " + block.size());
        }
        for (int index = 0; index < block.size(); index++) {
            requireExactWidth("headerBlock[" + index + "]", block.get(index),
                    REPORT_RECORD_WIDTH);
        }
        return block;
    }

    // Date parameter record. [app/cbl/CBTRN03C.cbl:L88], [app/cbl/CBTRN03C.cbl:L122-L125].

    /**
     * Builds the structured date-parameter record: a 10-byte start date, a one-byte space
     * separator and a 10-byte end date, 21 bytes in total
     * [app/cbl/CBTRN03C.cbl:L122-L125].
     *
     * <p>These 21 bytes are the leading 21 bytes of card 15 of the job-submission image built by
     * the JCL card-image builder, so the two classes must agree on them byte for byte. The
     * separator written here is always a single space, matching the bare one-byte filler in the
     * source: never two spaces, never a tab and never a comma.
     *
     * @param startDate the inclusive start date, moved into a 10-byte character field
     * @param endDate   the inclusive end date, moved into a 10-byte character field
     * @return the 21-byte structured date-parameter record, without a line terminator
     * @throws NullPointerException     if either date is {@code null}
     * @throws IllegalArgumentException if either date contains a character outside printable
     *                                  US-ASCII
     * @throws IllegalStateException    if the result does not measure the structured width
     */
    public static String buildDateParameterRecord(String startDate, String endDate) {
        Objects.requireNonNull(startDate,
                "startDate must not be null for the date parameter record");
        Objects.requireNonNull(endDate, "endDate must not be null for the date parameter record");

        char[] record = blankGroup(DATE_PARAMETER_STRUCTURED_WIDTH);
        placeField(record, DATE_PARAMETER_START_DATE_OFFSET,
                moveToAlphanumeric("startDate", startDate, DATE_WIDTH), DATE_WIDTH, "startDate");
        placeField(record, DATE_PARAMETER_SEPARATOR_OFFSET, singleSpace(),
                DATE_PARAMETER_SEPARATOR_WIDTH, "dateParameterSeparator");
        placeField(record, DATE_PARAMETER_END_DATE_OFFSET,
                moveToAlphanumeric("endDate", endDate, DATE_WIDTH), DATE_WIDTH, "endDate");

        String result = new String(record);
        requireExactWidth("dateParameterRecord", result, DATE_PARAMETER_STRUCTURED_WIDTH);
        return result;
    }

    /**
     * Extracts the start date from a date-parameter card, returning its 10 bytes verbatim.
     *
     * <p>The card may be either the 21-byte structured record or the full 80-byte card
     * [app/cbl/CBTRN03C.cbl:L88], because the structured prefix is identical in both. The 10
     * bytes are returned without trimming, exactly as the legacy 10-byte character field holds
     * them. The separator byte is deliberately not validated on the read path: the driver moves
     * the card into the structured record without checking the filler, so reading stays faithful
     * while writing stays canonical.
     *
     * @param dateParameterCard the parameter card, either 21 or 80 bytes wide
     * @return the 10-byte start date field, untrimmed
     * @throws NullPointerException     if the card is {@code null}
     * @throws IllegalArgumentException if the card contains a character outside printable
     *                                  US-ASCII or is neither 21 nor 80 bytes wide
     */
    public static String readStartDate(String dateParameterCard) {
        String card = requireDateParameterCard(dateParameterCard);
        return card.substring(DATE_PARAMETER_START_DATE_OFFSET,
                DATE_PARAMETER_START_DATE_OFFSET + DATE_WIDTH);
    }

    /**
     * Extracts the end date from a date-parameter card, returning its 10 bytes verbatim.
     *
     * <p>Accepts either the 21-byte structured record or the full 80-byte card, on the same
     * terms as {@link #readStartDate(String)}.
     *
     * @param dateParameterCard the parameter card, either 21 or 80 bytes wide
     * @return the 10-byte end date field, untrimmed
     * @throws NullPointerException     if the card is {@code null}
     * @throws IllegalArgumentException if the card contains a character outside printable
     *                                  US-ASCII or is neither 21 nor 80 bytes wide
     */
    public static String readEndDate(String dateParameterCard) {
        String card = requireDateParameterCard(dateParameterCard);
        return card.substring(DATE_PARAMETER_END_DATE_OFFSET,
                DATE_PARAMETER_END_DATE_OFFSET + DATE_WIDTH);
    }

    // The two numeric-edited amount masks.
    //
    // These two methods are deliberately NOT factored together. They differ in exactly one
    // character position - the sign emitted for a non-negative value - and a shared helper
    // taking a sign parameter, a boolean flag or an enum would make it possible to corrupt one
    // mask while the other's tests still pass. The duplication below is the decision, not an
    // oversight: see decision-log entries 1 and 2 in the class documentation. Only genuinely
    // mask-agnostic primitives are shared, namely scale validation, magnitude extraction and
    // width assertion; none of them knows the sign character or the field geometry.

    /**
     * Renders an amount with the <strong>detail</strong> mask, {@code PIC -ZZZ,ZZZ,ZZZ.ZZ}
     * [app/cpy/CVTRA07Y.cpy:L30]: a negative value places a minus in position 1 and a zero or
     * positive value places a <strong>space</strong> there. This mask never emits a plus.
     *
     * <p>The sign is a fixed insertion character and does not float: a negative 603.22 renders
     * as a minus, then eight spaces, then 603.22. A positive 500.47 renders as nine spaces then
     * 500.47. A value of exactly zero renders as fifteen spaces, because every numeric position
     * of the mask is a zero-suppression symbol.
     *
     * @param amount the amount to render, already scaled to two decimal places and already
     *               rounded down by the codec
     * @return exactly 15 bytes
     * @throws NullPointerException     if the amount is {@code null}
     * @throws IllegalArgumentException if the scale is not exactly 2, or the integer part
     *                                  exceeds nine digits
     * @throws IllegalStateException    if the rendered mask does not measure 15 bytes
     */
    public static String renderDetailAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null for the detail amount mask");

        String magnitude = magnitudeDigits("detailAmount", amount);

        char[] mask = new char[AMOUNT_MASK_WIDTH];
        Arrays.fill(mask, SPACE);

        // Zero blanks the entire item, editing characters included.
        if (amount.signum() != 0) {
            mask[MASK_SIGN_POSITION] = amount.signum() < 0 ? MINUS_SIGN : SPACE;
            mask[MASK_DECIMAL_POINT_POSITION] = DECIMAL_POINT;
            mask[MASK_FIRST_FRACTION_POSITION] = magnitude.charAt(MASK_INTEGER_DIGITS);
            mask[MASK_SECOND_FRACTION_POSITION] = magnitude.charAt(MASK_INTEGER_DIGITS + 1);

            int firstSignificant = firstSignificantIntegerDigit(magnitude);
            if (firstSignificant >= 0) {
                int[] digitPositions = integerDigitPositions();
                for (int index = firstSignificant; index < MASK_INTEGER_DIGITS; index++) {
                    mask[digitPositions[index]] = magnitude.charAt(index);
                }
                if (firstSignificant <= MASK_FIRST_GROUP_LAST_DIGIT_INDEX) {
                    mask[MASK_FIRST_GROUP_SEPARATOR_POSITION] = GROUP_SEPARATOR;
                }
                if (firstSignificant <= MASK_SECOND_GROUP_LAST_DIGIT_INDEX) {
                    mask[MASK_SECOND_GROUP_SEPARATOR_POSITION] = GROUP_SEPARATOR;
                }
            }
        }

        String rendered = new String(mask);
        requireExactWidth("detailAmount", rendered, AMOUNT_MASK_WIDTH);
        return rendered;
    }

    /**
     * Renders an amount with the <strong>always-signed total</strong> mask,
     * {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} [app/cpy/CVTRA07Y.cpy:L54],
     * [app/cpy/CVTRA07Y.cpy:L60], [app/cpy/CVTRA07Y.cpy:L66]: a negative value places a minus in
     * position 1 and a zero or positive value places a <strong>plus</strong> there. All three
     * total lines use this mask.
     *
     * <p>The sign is a fixed insertion character and does not float: a positive 500.47 renders
     * as a plus, then eight spaces, then 500.47. A value of exactly zero renders as fifteen
     * spaces - no plus, no zero digit and no decimal point - because every numeric position of
     * the mask is a zero-suppression symbol.
     *
     * @param amount the amount to render, already scaled to two decimal places and already
     *               rounded down by the codec
     * @return exactly 15 bytes
     * @throws NullPointerException     if the amount is {@code null}
     * @throws IllegalArgumentException if the scale is not exactly 2, or the integer part
     *                                  exceeds nine digits
     * @throws IllegalStateException    if the rendered mask does not measure 15 bytes
     */
    public static String renderTotalAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null for the total amount mask");

        String magnitude = magnitudeDigits("totalAmount", amount);

        char[] mask = new char[AMOUNT_MASK_WIDTH];
        Arrays.fill(mask, SPACE);

        // Zero blanks the entire item, editing characters included - including the plus sign.
        if (amount.signum() != 0) {
            mask[MASK_SIGN_POSITION] = amount.signum() < 0 ? MINUS_SIGN : PLUS_SIGN;
            mask[MASK_DECIMAL_POINT_POSITION] = DECIMAL_POINT;
            mask[MASK_FIRST_FRACTION_POSITION] = magnitude.charAt(MASK_INTEGER_DIGITS);
            mask[MASK_SECOND_FRACTION_POSITION] = magnitude.charAt(MASK_INTEGER_DIGITS + 1);

            int firstSignificant = firstSignificantIntegerDigit(magnitude);
            if (firstSignificant >= 0) {
                int[] digitPositions = integerDigitPositions();
                for (int index = firstSignificant; index < MASK_INTEGER_DIGITS; index++) {
                    mask[digitPositions[index]] = magnitude.charAt(index);
                }
                if (firstSignificant <= MASK_FIRST_GROUP_LAST_DIGIT_INDEX) {
                    mask[MASK_FIRST_GROUP_SEPARATOR_POSITION] = GROUP_SEPARATOR;
                }
                if (firstSignificant <= MASK_SECOND_GROUP_LAST_DIGIT_INDEX) {
                    mask[MASK_SECOND_GROUP_SEPARATOR_POSITION] = GROUP_SEPARATOR;
                }
            }
        }

        String rendered = new String(mask);
        requireExactWidth("totalAmount", rendered, AMOUNT_MASK_WIDTH);
        return rendered;
    }

    // Private layout primitives. Every width is measured as US-ASCII encoded bytes.

    /**
     * Assembles one of the three total lines: a label field, a dot fill, then the always-signed
     * amount mask at offset 97. The dot-fill width is supplied by the caller from its own named
     * constant, so that no total line can inherit another's fill.
     *
     * @param groupName    diagnostic name of the group under construction
     * @param labelText    the label literal
     * @param labelWidth   the label field's declared width
     * @param dotFillOffset the dot fill's offset, which equals the label width
     * @param dotFillWidth the dot fill's declared width, hardcoded per group
     * @param nativeWidth  the group's native width before padding
     * @param padWidth     the spaces required to reach the record width
     * @param amount       the already-scaled total
     * @return the 133-byte total record
     */
    private static String buildTotalLine(String groupName,
                                         String labelText,
                                         int labelWidth,
                                         int dotFillOffset,
                                         int dotFillWidth,
                                         int nativeWidth,
                                         int padWidth,
                                         BigDecimal amount) {
        if (labelWidth + dotFillWidth != AMOUNT_OFFSET) {
            throw new IllegalStateException("Group " + groupName + " must place its amount at "
                    + "offset " + AMOUNT_OFFSET + " but its label width " + labelWidth
                    + " plus its dot fill width " + dotFillWidth + " gives "
                    + (labelWidth + dotFillWidth));
        }

        char[] group = blankGroup(nativeWidth);
        placeField(group, TOTAL_LINE_LABEL_OFFSET,
                moveToAlphanumeric(groupName + ".label", labelText, labelWidth),
                labelWidth, groupName + ".label");
        placeField(group, dotFillOffset, fixedFill(groupName + ".dotFill", DOT, dotFillWidth),
                dotFillWidth, groupName + ".dotFill");
        placeField(group, AMOUNT_OFFSET, renderTotalAmount(amount), AMOUNT_MASK_WIDTH,
                groupName + ".amount");

        return padToRecordWidth(groupName, new String(group), nativeWidth, padWidth);
    }

    /**
     * Returns a mutable working buffer of the requested width pre-filled with spaces. The buffer
     * is local to the caller and is converted to a {@code String} before it leaves this class,
     * so no mutable array escapes.
     *
     * @param width the group's native width
     * @return a space-filled buffer of exactly {@code width} characters
     */
    private static char[] blankGroup(int width) {
        if (width <= 0) {
            throw new IllegalStateException("Group width must be positive but is " + width);
        }
        char[] group = new char[width];
        Arrays.fill(group, SPACE);
        return group;
    }

    /**
     * Places a field of exactly the declared width at the declared offset within a group,
     * verifying both the field's encoded width and the group's capacity before writing. The
     * verification is what makes the offset tables in the class documentation enforceable rather
     * than merely descriptive.
     *
     * @param group     the working buffer
     * @param offset    the field's zero-based offset within the group
     * @param field     the field's fully fitted content
     * @param width     the field's declared width
     * @param fieldName diagnostic name of the field
     * @throws IllegalStateException if the field does not measure its declared width, or does
     *                               not fit the group at the requested offset
     */
    private static void placeField(char[] group, int offset, String field, int width,
                                   String fieldName) {
        int measured = asciiWidth(field);
        if (measured != width) {
            throw new IllegalStateException("Field " + fieldName + " must measure " + width
                    + " encoded bytes but measures " + measured);
        }
        if (offset < 0 || offset + width > group.length) {
            throw new IllegalStateException("Field " + fieldName + " of width " + width
                    + " does not fit at offset " + offset + " in a group of width "
                    + group.length);
        }
        for (int index = 0; index < width; index++) {
            group[offset + index] = field.charAt(index);
        }
    }

    /**
     * Applies COBOL move semantics for a receiving {@code PIC X(n)} field: content longer than
     * the field is truncated on the right, content shorter than the field is space-padded on the
     * right. Truncation is silent, exactly as the legacy move is.
     *
     * @param fieldName diagnostic name of the receiving field
     * @param value     the sending value
     * @param width     the receiving field's width
     * @return exactly {@code width} bytes
     * @throws IllegalArgumentException if the value contains a character outside printable
     *                                  US-ASCII
     */
    private static String moveToAlphanumeric(String fieldName, String value, int width) {
        Objects.requireNonNull(fieldName, "fieldName must not be null");
        Objects.requireNonNull(value, "Value for field " + fieldName + " must not be null");
        requirePrintableAscii(fieldName, value);

        int measured = asciiWidth(value);
        String fitted;
        if (measured > width) {
            fitted = value.substring(0, width);
        } else if (measured < width) {
            fitted = value + repeated(SPACE, width - measured);
        } else {
            fitted = value;
        }

        int fittedWidth = asciiWidth(fitted);
        if (fittedWidth != width) {
            throw new IllegalStateException("Field " + fieldName + " must measure " + width
                    + " encoded bytes after the move but measures " + fittedWidth);
        }
        return fitted;
    }

    /**
     * Renders the {@code PIC 9(04)} transaction category code: a numeric-display field, and the
     * only field in the layout that is left zero-filled rather than space-padded
     * [app/cpy/CVTRA07Y.cpy:L24]. The picture is unsigned, so a negative value is rejected, and
     * a value that will not fit four digits is rejected rather than truncated.
     *
     * @param fieldName    diagnostic name of the field
     * @param categoryCode the category code
     * @return exactly 4 bytes, left zero-filled
     * @throws IllegalArgumentException if the code is negative or exceeds four digits
     */
    private static String moveToCategoryCode(String fieldName, int categoryCode) {
        if (categoryCode < MINIMUM_CATEGORY_CODE || categoryCode > MAXIMUM_CATEGORY_CODE) {
            throw new IllegalArgumentException("Field " + fieldName
                    + " is PIC 9(04) and accepts values from " + MINIMUM_CATEGORY_CODE + " to "
                    + MAXIMUM_CATEGORY_CODE + " but was " + categoryCode);
        }

        String digits = Integer.toString(categoryCode);
        int measured = asciiWidth(digits);
        String fitted = repeated(ZERO_DIGIT, CATEGORY_CODE_WIDTH - measured) + digits;

        int fittedWidth = asciiWidth(fitted);
        if (fittedWidth != CATEGORY_CODE_WIDTH) {
            throw new IllegalStateException("Field " + fieldName + " must measure "
                    + CATEGORY_CODE_WIDTH + " encoded bytes but measures " + fittedWidth);
        }
        return fitted;
    }

    /**
     * Pads a completed group on the right with spaces to the 133-byte record width, reproducing
     * a COBOL move of a short group into the {@code PIC X(133)} record
     * [app/cbl/CBTRN03C.cbl:L85]. Both the group's native width and the resulting record width
     * are asserted, so a layout regression cannot reach the writer.
     *
     * @param groupName   diagnostic name of the group
     * @param group       the completed group at its native width
     * @param nativeWidth the group's declared native width
     * @param padWidth    the declared number of pad spaces
     * @return exactly 133 bytes
     * @throws IllegalStateException if the native width, the pad width or the record width does
     *                               not hold
     */
    private static String padToRecordWidth(String groupName, String group, int nativeWidth,
                                           int padWidth) {
        int measured = asciiWidth(group);
        if (measured != nativeWidth) {
            throw new IllegalStateException("Group " + groupName + " must measure its native "
                    + "width of " + nativeWidth + " encoded bytes but measures " + measured);
        }
        if (nativeWidth + padWidth != REPORT_RECORD_WIDTH) {
            throw new IllegalStateException("Group " + groupName + " declares a native width of "
                    + nativeWidth + " and a pad width of " + padWidth + ", which gives "
                    + (nativeWidth + padWidth) + " rather than the record width of "
                    + REPORT_RECORD_WIDTH);
        }

        String record = padWidth == 0 ? group : group + repeated(SPACE, padWidth);
        requireExactWidth(groupName, record, REPORT_RECORD_WIDTH);
        return record;
    }

    /**
     * Validates the scale of an amount and returns its magnitude as an 11-character string of
     * ASCII digits: nine integer digits followed by two fractional digits, left zero-filled,
     * matching {@code PIC S9(09)V99}.
     *
     * <p>This primitive is mask-agnostic: it knows nothing of the sign character, the field
     * geometry or the suppression rule, so sharing it between the two mask renderers cannot make
     * one behave like the other.
     *
     * <p>The digits are taken from the unscaled value's absolute magnitude, whose radix-ten
     * string form is specified to contain ASCII digits only and is therefore locale-independent -
     * unlike {@code BigDecimal.toString()}, which may emit scientific notation, and unlike the
     * {@code java.text} formatters, which substitute locale separators and minus glyphs. Every
     * character is nevertheless validated before use.
     *
     * @param fieldName diagnostic name of the amount field
     * @param amount    the amount
     * @return exactly 11 ASCII digits
     * @throws IllegalArgumentException if the scale is not exactly 2, or the integer part exceeds
     *                                  nine digits
     */
    private static String magnitudeDigits(String fieldName, BigDecimal amount) {
        if (amount.scale() != REQUIRED_AMOUNT_SCALE) {
            throw new IllegalArgumentException("Field " + fieldName + " requires an amount already"
                    + " scaled to " + REQUIRED_AMOUNT_SCALE + " decimal places but the supplied"
                    + " value has scale " + amount.scale()
                    + "; amounts are scaled and rounded by the zoned decimal codec and are never"
                    + " re-scaled by this formatter");
        }

        String magnitude = amount.unscaledValue().abs().toString();
        int digitCount = asciiWidth(magnitude);
        if (digitCount > MASK_MAGNITUDE_DIGITS) {
            throw new IllegalArgumentException("Field " + fieldName + " accepts at most "
                    + MASK_INTEGER_DIGITS + " integer digits and " + MASK_FRACTION_DIGITS
                    + " decimal digits, that is " + MASK_MAGNITUDE_DIGITS
                    + " significant digits, but the supplied value has " + digitCount
                    + "; an oversized amount is rejected rather than left-truncated");
        }

        String padded = repeated(ZERO_DIGIT, MASK_MAGNITUDE_DIGITS - digitCount) + magnitude;
        for (int index = 0; index < MASK_MAGNITUDE_DIGITS; index++) {
            char digit = padded.charAt(index);
            if (digit < ZERO_DIGIT || digit > NINE_DIGIT) {
                throw new IllegalStateException("Magnitude of field " + fieldName
                        + " must consist of ASCII digits only but position " + index
                        + " holds code point " + (int) digit);
            }
        }

        int paddedWidth = asciiWidth(padded);
        if (paddedWidth != MASK_MAGNITUDE_DIGITS) {
            throw new IllegalStateException("Magnitude of field " + fieldName + " must measure "
                    + MASK_MAGNITUDE_DIGITS + " digits but measures " + paddedWidth);
        }
        return padded;
    }

    /**
     * Returns the index of the first non-zero integer digit within an 11-digit magnitude, or -1
     * when all nine integer digits are zero. Zero suppression runs from the left and stops at
     * that digit, or at the decimal point when there is none.
     *
     * @param magnitude an 11-character digit string
     * @return the index in the range 0 to 8, or -1 when the integer part is entirely zero
     */
    private static int firstSignificantIntegerDigit(String magnitude) {
        for (int index = 0; index < MASK_INTEGER_DIGITS; index++) {
            if (magnitude.charAt(index) != ZERO_DIGIT) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Returns the mask positions of the nine integer digit symbols, in order. The two group
     * separators sit at positions 4 and 8, which is why positions 4 and 8 are absent from this
     * list. A fresh array is returned on each call so that no mutable static state exists and
     * nothing shared can be modified.
     *
     * @return the nine integer digit positions within the 15-character mask
     */
    private static int[] integerDigitPositions() {
        return new int[] {1, 2, 3, 5, 6, 7, 9, 10, 11};
    }

    /**
     * Builds a fixed run of one character and asserts its encoded width. Used for the dot fills,
     * the rule line, the blank line and the multi-byte space fillers, so that any of them
     * failing its declared width is caught at the point of construction.
     *
     * @param fieldName diagnostic name of the field
     * @param fill      the fill character
     * @param width     the required width
     * @return exactly {@code width} bytes of {@code fill}
     * @throws IllegalStateException if the width is negative or the result mismeasures
     */
    private static String fixedFill(String fieldName, char fill, int width) {
        if (width < 0) {
            throw new IllegalStateException("Fill width for field " + fieldName
                    + " must not be negative but is " + width);
        }
        String value = repeated(fill, width);
        requireExactWidth(fieldName, value, width);
        return value;
    }

    /** @return a one-byte space, the value of every bare single-byte space filler. */
    private static String singleSpace() {
        return fixedFill("singleSpaceFiller", SPACE, SINGLE_BYTE_FILLER_WIDTH);
    }

    /**
     * @return a one-byte hyphen, the literal value carried by the detail line's two filler
     *         separators at offsets 31 and 52
     */
    private static String singleHyphen() {
        return fixedFill("singleHyphenFiller", HYPHEN, SINGLE_BYTE_FILLER_WIDTH);
    }

    /**
     * Validates that a date-parameter card is either the 21-byte structured record or the full
     * 80-byte card, and that it holds only printable US-ASCII.
     *
     * @param dateParameterCard the card
     * @return the card, unchanged
     * @throws NullPointerException     if the card is {@code null}
     * @throws IllegalArgumentException if the card holds a character outside printable US-ASCII
     *                                  or is neither 21 nor 80 bytes wide
     */
    private static String requireDateParameterCard(String dateParameterCard) {
        Objects.requireNonNull(dateParameterCard, "dateParameterCard must not be null");
        requirePrintableAscii("dateParameterCard", dateParameterCard);

        int measured = asciiWidth(dateParameterCard);
        if (measured != DATE_PARAMETER_STRUCTURED_WIDTH
                && measured != DATE_PARAMETER_CARD_WIDTH) {
            throw new IllegalArgumentException("Field dateParameterCard must measure either "
                    + DATE_PARAMETER_STRUCTURED_WIDTH + " or " + DATE_PARAMETER_CARD_WIDTH
                    + " encoded bytes but measures " + measured);
        }
        return dateParameterCard;
    }

    /**
     * Rejects any character outside printable US-ASCII, that is outside the range from space to
     * tilde. The report is a fixed-width print artefact in which a character outside that range
     * has no defined rendering, and rejecting it keeps one encoded byte per character while
     * structurally preventing a control character, a tab or a line terminator from entering the
     * record.
     *
     * @param fieldName diagnostic name of the field
     * @param value     the value to inspect
     * @throws IllegalArgumentException on the first character outside the accepted range
     */
    private static void requirePrintableAscii(String fieldName, String value) {
        // The character count bounds this loop because the characters themselves are what must
        // be inspected: encoding first would already have replaced an unmappable character. This
        // is a charset check, not a width assertion - every width in this class is measured in
        // encoded bytes by asciiWidth. Once this check passes, one character is one encoded byte.
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < FIRST_PRINTABLE_ASCII || character > LAST_PRINTABLE_ASCII) {
                throw new IllegalArgumentException("Field " + fieldName + " accepts printable "
                        + "US-ASCII only, that is code points " + (int) FIRST_PRINTABLE_ASCII
                        + " to " + (int) LAST_PRINTABLE_ASCII + ", but position " + index
                        + " holds code point " + (int) character);
            }
        }
    }

    /**
     * Asserts that a value measures exactly the expected number of US-ASCII encoded bytes. Width
     * is always measured in encoded bytes and never as a character count, because the 133-byte
     * record is a byte contract.
     *
     * @param fieldName     diagnostic name of the field
     * @param value         the value to measure
     * @param expectedWidth the required width in encoded bytes
     * @throws IllegalStateException if the measured width differs
     */
    private static void requireExactWidth(String fieldName, String value, int expectedWidth) {
        int measured = asciiWidth(value);
        if (measured != expectedWidth) {
            throw new IllegalStateException("Field " + fieldName + " must measure "
                    + expectedWidth + " encoded bytes but measures " + measured);
        }
    }

    /**
     * Measures a value in US-ASCII encoded bytes. The charset is named explicitly so that the
     * platform default charset can never influence a width.
     *
     * @param value the value to measure
     * @return the number of encoded bytes
     */
    private static int asciiWidth(String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Repeats a single character a given number of times.
     *
     * @param character the character to repeat
     * @param count     the repetition count, which may be zero
     * @return the resulting run, empty when the count is zero
     * @throws IllegalStateException if the count is negative
     */
    private static String repeated(char character, int count) {
        if (count < 0) {
            throw new IllegalStateException("Repetition count must not be negative but is "
                    + count);
        }
        if (count == 0) {
            return "";
        }
        char[] run = new char[count];
        Arrays.fill(run, character);
        return new String(run);
    }
}
