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
 * <p>Layout authority is split across two members, and the layout below is derived from both of
 * them without transcribing either. The seven report groups are declared in the report-formatting
 * copybook [app/cpy/CVTRA07Y.cpy], which the report driver includes [app/cbl/CBTRN03C.cbl:L113].
 * The record itself, the blank line, the page size and the date-parameter structure are declared in
 * the driver.
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
 * <p><strong>The per-group field layout is declared by the named offset and width constants below,
 * and each builder method states its own group, native width and substituted positions.</strong> The
 * groups are: the report name header at 115 native bytes; the transaction detail line at 114; the
 * column header line at 114; the rule line at 133; and the three total lines at 112 each.
 * {@code app/cpy/CVTRA07Y.cpy} is the authority for every offset and every literal, and
 * {@code docs/traceability-matrix.md} carries the group-by-group mapping. What follows is not the
 * layout but the handful of places where a plausible implementation of it goes wrong.
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
    private static final char SPACE = ' ';

    private static final char HYPHEN = '-';

    private static final char DOT = '.';

    private static final char DECIMAL_POINT = '.';

    private static final char GROUP_SEPARATOR = ',';

    private static final char PLUS_SIGN = '+';

    private static final char MINUS_SIGN = '-';

    private static final char ZERO_DIGIT = '0';

    private static final char NINE_DIGIT = '9';

    private static final char FIRST_PRINTABLE_ASCII = ' ';

    private static final char LAST_PRINTABLE_ASCII = '~';

    public static final int REPORT_RECORD_WIDTH = 133;

    public static final int NAME_HEADER_NATIVE_WIDTH = 115;

    public static final int DETAIL_LINE_NATIVE_WIDTH = 114;

    public static final int COLUMN_HEADER_NATIVE_WIDTH = 114;

    public static final int RULE_LINE_NATIVE_WIDTH = 133;

    public static final int PAGE_TOTAL_LINE_NATIVE_WIDTH = 112;

    public static final int ACCOUNT_TOTAL_LINE_NATIVE_WIDTH = 112;

    public static final int GRAND_TOTAL_LINE_NATIVE_WIDTH = 112;

    public static final int NAME_HEADER_PAD_WIDTH = 18;

    public static final int DETAIL_LINE_PAD_WIDTH = 19;

    public static final int COLUMN_HEADER_PAD_WIDTH = 19;

    public static final int RULE_LINE_PAD_WIDTH = 0;

    public static final int PAGE_TOTAL_LINE_PAD_WIDTH = 21;

    public static final int ACCOUNT_TOTAL_LINE_PAD_WIDTH = 21;

    public static final int GRAND_TOTAL_LINE_PAD_WIDTH = 21;

    public static final int AMOUNT_MASK_WIDTH = 15;

    public static final int TYPE_CODE_SEPARATOR_OFFSET = 31;

    public static final int CATEGORY_CODE_SEPARATOR_OFFSET = 52;

    public static final int AMOUNT_OFFSET = 97;

    public static final int PAGE_TOTAL_DOT_FILL_WIDTH = 86;

    public static final int ACCOUNT_TOTAL_DOT_FILL_WIDTH = 84;

    public static final int GRAND_TOTAL_DOT_FILL_WIDTH = 86;

    public static final int TYPE_DESCRIPTION_WIDTH = 15;

    public static final int CATEGORY_DESCRIPTION_WIDTH = 29;

    public static final int DATE_WIDTH = 10;

    public static final int TRANSACTION_ID_WIDTH = 16;

    public static final int ACCOUNT_ID_WIDTH = 11;

    public static final int TYPE_CODE_WIDTH = 2;

    public static final int CATEGORY_CODE_WIDTH = 4;

    public static final int SOURCE_WIDTH = 10;

    public static final int PAGE_SIZE = 20;

    public static final int DATE_PARAMETER_STRUCTURED_WIDTH = 21;

    public static final int DATE_PARAMETER_CARD_WIDTH = 80;

    public static final int DATE_PARAMETER_SEPARATOR_WIDTH = 1;

    public static final int DATE_PARAMETER_SEPARATOR_OFFSET = 10;

    public static final int DATE_PARAMETER_START_DATE_OFFSET = 0;

    public static final int DATE_PARAMETER_END_DATE_OFFSET = 11;

    public static final int HEADER_BLOCK_RECORD_COUNT = 4;

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

    private static final String COLUMN_HEADER_AMOUNT_TEXT = "        Amount";

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

    private static final int MASK_SIGN_POSITION = 0;

    private static final int MASK_DECIMAL_POINT_POSITION = 12;

    private static final int MASK_FIRST_FRACTION_POSITION = 13;

    private static final int MASK_SECOND_FRACTION_POSITION = 14;

    private static final int MASK_FIRST_GROUP_SEPARATOR_POSITION = 4;

    private static final int MASK_SECOND_GROUP_SEPARATOR_POSITION = 8;

    private static final int MASK_FIRST_GROUP_LAST_DIGIT_INDEX = 2;

    private static final int MASK_SECOND_GROUP_LAST_DIGIT_INDEX = 5;

    private static final int MASK_INTEGER_DIGITS = 9;

    private static final int MASK_FRACTION_DIGITS = 2;

    private static final int MASK_MAGNITUDE_DIGITS = 11;

    private static final int REQUIRED_AMOUNT_SCALE = 2;

    private static final int MINIMUM_CATEGORY_CODE = 0;

    private static final int MAXIMUM_CATEGORY_CODE = 9999;

    /** The blank line, 133 spaces: a real emitted record of the header block, not padding. */
    public static final String BLANK_LINE = fixedFill("BLANK_LINE", SPACE, REPORT_RECORD_WIDTH);

    /** The rule line, 133 hyphens: the only group already 133 bytes wide, so never padded. */
    public static final String RULE_LINE = fixedFill("RULE_LINE", HYPHEN, RULE_LINE_NATIVE_WIDTH);

    private ReportLineFormatter() {
        throw new AssertionError("ReportLineFormatter is a static utility and is not instantiable");
    }

    /**
     * Builds group 1, the report name header: 115 native bytes padded to 133.
     *
     * @param startDate the ten-character start date placed at offset 91
     * @param endDate the ten-character end date placed at offset 105
     * @return the 133-byte record
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
     * Builds group 2, the transaction detail line: 114 native bytes padded to 133.
     *
     * <p>The two hyphens at offsets 31 and 52 are emitted unconditionally, the category code is left
     * zero-filled to four digits because it is the layout's only numeric-display field, and the two
     * descriptions are right-truncated at 15 and 29 bytes exactly as a COBOL move performs it.
     *
     * @param transactionId the sixteen-character identifier at offset 0
     * @param accountId the eleven-character account identifier at offset 17
     * @param transactionTypeCode the two-character type code at offset 29
     * @param transactionTypeDescription the type description, truncated into 15 bytes at offset 32
     * @param transactionCategoryCode the category code, rendered as four digits at offset 48
     * @param transactionCategoryDescription the category description, truncated into 29 bytes at 53
     * @param transactionSource the ten-character source at offset 83
     * @param amount the amount rendered through the detail mask at offset 97
     * @return the 133-byte record
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
     * Builds group 3, the column header line: 114 native bytes padded to 133. The eight leading
     * spaces inside the amount literal are what right-align it with the detail amount field.
     *
     * @return the 133-byte record
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
     * @return group 4, the 133-hyphen rule line
     */
    public static String buildRuleLine() {
        return padToRecordWidth("ruleLine", RULE_LINE, RULE_LINE_NATIVE_WIDTH, RULE_LINE_PAD_WIDTH);
    }

    /**
     * Builds group 5, the page total line: an 11-byte label, 86 dots and the always-signed total
     * mask, so the amount begins at offset 97 like every other amount in the report.
     *
     * @param amount the page total, at scale 2
     * @return the 133-byte record
     */
    public static String buildPageTotalLine(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null for the page total line");
        return buildTotalLine("pageTotalLine", PAGE_TOTAL_LABEL_TEXT, PAGE_TOTAL_LABEL_WIDTH,
                PAGE_TOTAL_DOT_FILL_OFFSET, PAGE_TOTAL_DOT_FILL_WIDTH,
                PAGE_TOTAL_LINE_NATIVE_WIDTH, PAGE_TOTAL_LINE_PAD_WIDTH, amount);
    }

    /**
     * Builds group 6, the account total line: a 13-byte label and 84 dots, which is how its amount
     * still begins at offset 97.
     *
     * @param amount the account total, at scale 2
     * @return the 133-byte record
     */
    public static String buildAccountTotalLine(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null for the account total line");
        return buildTotalLine("accountTotalLine", ACCOUNT_TOTAL_LABEL_TEXT,
                ACCOUNT_TOTAL_LABEL_WIDTH, ACCOUNT_TOTAL_DOT_FILL_OFFSET,
                ACCOUNT_TOTAL_DOT_FILL_WIDTH, ACCOUNT_TOTAL_LINE_NATIVE_WIDTH,
                ACCOUNT_TOTAL_LINE_PAD_WIDTH, amount);
    }

    /**
     * Builds group 7, the grand total line: an 11-byte label and 86 dots.
     *
     * @param amount the grand total, at scale 2
     * @return the 133-byte record
     */
    public static String buildGrandTotalLine(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null for the grand total line");
        return buildTotalLine("grandTotalLine", GRAND_TOTAL_LABEL_TEXT, GRAND_TOTAL_LABEL_WIDTH,
                GRAND_TOTAL_DOT_FILL_OFFSET, GRAND_TOTAL_DOT_FILL_WIDTH,
                GRAND_TOTAL_LINE_NATIVE_WIDTH, GRAND_TOTAL_LINE_PAD_WIDTH, amount);
    }

    /**
     * @return the blank line as an emitted record
     */
    public static String buildBlankLine() {
        return BLANK_LINE;
    }

    /**
     * Builds the four header records in their one legal order - name header, blank line, column
     * header, rule line - so the order cannot be got wrong at a call site. Each record advances the
     * driver's line counter by one.
     *
     * @param startDate the ten-character start date
     * @param endDate the ten-character end date
     * @return an unmodifiable four-element list of 133-byte records
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

    /**
     * Builds the 21 structured bytes of the date-parameter card: start date, a one-byte space
     * separator, end date. These are the leading 21 bytes of card 15 of the job-submission image, so
     * this class and the card-image builder must agree on them byte for byte.
     *
     * @param startDate the ten-character start date
     * @param endDate the ten-character end date
     * @return the 21-byte structured record
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
     * Reads the start date out of a date-parameter card. Reading is deliberately more permissive
     * than writing: the legacy driver moves the card into the structured record without validating
     * the separator byte, so no separator is required here.
     *
     * @param dateParameterCard the card, at least 21 characters wide
     * @return the ten-character start date
     */
    public static String readStartDate(String dateParameterCard) {
        String card = requireDateParameterCard(dateParameterCard);
        return card.substring(DATE_PARAMETER_START_DATE_OFFSET,
                DATE_PARAMETER_START_DATE_OFFSET + DATE_WIDTH);
    }

    /**
     * Reads the end date out of a date-parameter card, on the same permissive terms as
     * {@link #readStartDate(String)}.
     *
     * @param dateParameterCard the card, at least 21 characters wide
     * @return the ten-character end date
     */
    public static String readEndDate(String dateParameterCard) {
        String card = requireDateParameterCard(dateParameterCard);
        return card.substring(DATE_PARAMETER_END_DATE_OFFSET,
                DATE_PARAMETER_END_DATE_OFFSET + DATE_WIDTH);
    }

    /**
     * Renders the detail mask: fifteen characters, a fixed leftmost sign position that carries a
     * minus for a negative value and a <em>space</em> otherwise - never a plus - and fifteen spaces
     * for a value of exactly zero.
     *
     * @param amount the amount, which must be at scale 2 with at most nine integer digits
     * @return the fifteen-character rendering
     */
    public static String renderDetailAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null for the detail amount mask");

        String magnitude = magnitudeDigits("detailAmount", amount);

        char[] mask = new char[AMOUNT_MASK_WIDTH];
        Arrays.fill(mask, SPACE);

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
     * Renders the totals mask: fifteen characters, always signed, so a non-negative value carries a
     * plus where the detail mask carries a space. Zero blanks the whole field here too.
     *
     * <p>Deliberately independent of {@link #renderDetailAmount(BigDecimal)}: the two masks differ in
     * sign presence, and a shared renderer with a flag would eventually apply the wrong one.
     *
     * @param amount the total, which must be at scale 2 with at most nine integer digits
     * @return the fifteen-character rendering
     */
    public static String renderTotalAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null for the total amount mask");

        String magnitude = magnitudeDigits("totalAmount", amount);

        char[] mask = new char[AMOUNT_MASK_WIDTH];
        Arrays.fill(mask, SPACE);

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

    private static char[] blankGroup(int width) {
        if (width <= 0) {
            throw new IllegalStateException("Group width must be positive but is " + width);
        }
        char[] group = new char[width];
        Arrays.fill(group, SPACE);
        return group;
    }

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

    private static int firstSignificantIntegerDigit(String magnitude) {
        for (int index = 0; index < MASK_INTEGER_DIGITS; index++) {
            if (magnitude.charAt(index) != ZERO_DIGIT) {
                return index;
            }
        }
        return -1;
    }

    private static int[] integerDigitPositions() {
        return new int[] {1, 2, 3, 5, 6, 7, 9, 10, 11};
    }

    private static String fixedFill(String fieldName, char fill, int width) {
        if (width < 0) {
            throw new IllegalStateException("Fill width for field " + fieldName
                    + " must not be negative but is " + width);
        }
        String value = repeated(fill, width);
        requireExactWidth(fieldName, value, width);
        return value;
    }

    private static String singleSpace() {
        return fixedFill("singleSpaceFiller", SPACE, SINGLE_BYTE_FILLER_WIDTH);
    }

    private static String singleHyphen() {
        return fixedFill("singleHyphenFiller", HYPHEN, SINGLE_BYTE_FILLER_WIDTH);
    }

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

    private static void requirePrintableAscii(String fieldName, String value) {
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

    private static void requireExactWidth(String fieldName, String value, int expectedWidth) {
        int measured = asciiWidth(value);
        if (measured != expectedWidth) {
            throw new IllegalStateException("Field " + fieldName + " must measure "
                    + expectedWidth + " encoded bytes but measures " + measured);
        }
    }

    private static int asciiWidth(String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

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
