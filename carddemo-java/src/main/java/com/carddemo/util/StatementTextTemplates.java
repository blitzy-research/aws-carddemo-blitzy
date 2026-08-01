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
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Fixed-width templates for the plain-text account statement record produced by the legacy statement
 * generator, and the sole holder of the statement text layout.
 *
 * <p>Every literal, filler width, explicit space count and numeric-edited mask below is an external
 * output contract verified byte for byte against the legacy source; a golden-file comparison at exactly
 * {@value #STATEMENT_RECORD_LENGTH} bytes per record is what proves it.
 *
 * <h2>Record geometry</h2>
 *
 * <p>The text output file is selected at {@code [app/cbl/CBSTM03A.CBL:L39]} and its record is
 * declared at {@code [app/cbl/CBSTM03A.CBL:L45]} as a single alphanumeric item named
 * {@code FD-STMTFILE-REC} that is fixed at {@value #STATEMENT_RECORD_LENGTH} bytes. The line
 * templates occupy
 * {@code [app/cbl/CBSTM03A.CBL:L85-L146]} and there are exactly
 * {@value #STATEMENT_LINE_GROUP_COUNT} of them - verified by a raw read with control characters
 * exposed, so the count is seventeen, not sixteen and not eighteen. Both figures are factual layout
 * evidence read out of the source, not tuning or service-level figures.
 *
 * <p><strong>The record image carries no line terminator.</strong> The COBOL source file itself has
 * CRLF line endings, but that is a property of the source text and not of the emitted records. Each
 * emitted record is exactly {@value #STATEMENT_RECORD_LENGTH} data bytes; whether and how records are
 * separated on disk is the writer's decision in the batch layer (decision D-30), so no carriage
 * return, line feed or platform line separator ever appears inside a template produced here.
 *
 * <p><strong>The seventeen line groups.</strong>
 * <pre>{@code
 *  #   Group       Source          Component widths (every row sums to exactly 80)
 * --   ----------  --------------  ---------------------------------------------------------------
 *  1   ST-LINE0    L86-L89         31 asterisk + 18 text 'START OF STATEMENT' + 31 asterisk
 *  2   ST-LINE1    L90-L92         75 ST-NAME + 5 space
 *  3   ST-LINE2    L93-L95         50 ST-ADD1 + 30 space
 *  4   ST-LINE3    L96-L98         50 ST-ADD2 + 30 space
 *  5   ST-LINE4    L99-L100        80 ST-ADD3 -- full width, no filler
 *  6   ST-LINE5    L101-L102       80 hyphen
 *  7   ST-LINE6    L103-L106       33 space + 14 holding 'Basic Details' (13 chars, 1 pad byte)
 *                                     + 33 space
 *  8   ST-LINE7    L107-L110       20 holding 'Account ID' + NINE space + colon,
 *                                  20 ST-ACCT-ID, 40 space
 *  9   ST-LINE8    L111-L115       20 holding 'Current Balance' + FOUR space + colon,
 *                                  13 ST-CURR-BAL (mask A), 7 space, 40 space
 * 10   ST-LINE9    L116-L119       20 holding 'FICO Score' + NINE space + colon,
 *                                  20 ST-FICO-SCORE, 40 space
 * 11   ST-LINE10   L120-L121       80 hyphen
 * 12   ST-LINE11   L122-L125       30 space + 20 holding 'TRANSACTION SUMMARY' (19 chars, ALL
 *                                     CAPS, plus ONE trailing space) + 30 space
 * 13   ST-LINE12   L126-L127       80 hyphen
 * 14   ST-LINE13   L128-L131       16 holding 'Tran ID' + NINE space;
 *                                  51 whose literal is the 16-character 'Tran Details' plus FOUR
 *                                     trailing spaces, padded out to 51;
 *                                  13 holding TWO leading spaces + 'Tran Amount'
 * 15   ST-LINE14   L132-L137       16 ST-TRANID + 1 space + 49 ST-TRANDT + 1 dollar sign
 *                                  + 13 ST-TRANAMT (mask B)
 * 16   ST-LINE14A  L138-L142       10 holding 'Total EXP:' + 56 space + 1 dollar sign
 *                                  + 13 ST-TOTAL-TRAMT (mask B)
 * 17   ST-LINE15   L143-L146       32 asterisk + 16 text 'END OF STATEMENT' + 32 asterisk
 * }</pre>
 *
 * <p><strong>The two banners have different splits - do not unify them.</strong> The start banner is
 * 31 asterisk bytes, an 18-byte text field and 31 asterisk bytes; the end banner is 32, 16 and 32.
 * Both total {@value #STATEMENT_RECORD_LENGTH} and both texts fill their fields exactly -
 * {@code START OF STATEMENT} is 18 characters and {@code END OF STATEMENT} is 16 - so the asterisk
 * counts nevertheless differ, 31 against 32, because the two texts differ in length by two. The two
 * banners are therefore declared independently from their own width constants, deliberately
 * <em>not</em> produced by a shared centring helper that computes the padding from the text length, and
 * neither count is corrected toward the other. A static verification block asserts each split on its
 * own and asserts that the two asterisk counts remain different, so any future attempt to tidy the
 * asymmetry away fails at class initialization rather than silently in a statement.
 *
 * <p><strong>The three rule lines.</strong> Three of the seventeen groups are
 * {@value #RULE_LINE_WIDTH} hyphens: ST-LINE5, ST-LINE10 and ST-LINE12. They are identical in content
 * but occupy three distinct positions in the statement, so the content is exposed once as
 * {@link #RULE_LINE} and referenced by the three position-named constants {@link #ST_LINE5_RULE},
 * {@link #ST_LINE10_RULE} and {@link #ST_LINE12_RULE}. All three positions are genuinely emitted and
 * must not be collapsed out of the emitted sequence.
 *
 * <p><strong>The two numeric-edited masks.</strong>
 * <pre>{@code
 * Mask  PIC         Width  Zero suppression                    Used by
 * ----  ---------   -----  ----------------------------------  --------------------------
 * A     9(9).99-       13  NO  - leading zeros are printed     ST-CURR-BAL     (L113)
 * B     Z(9).99-       13  YES - leading zeros become spaces   ST-TRANAMT      (L137)
 *                                                              ST-TOTAL-TRAMT  (L142)
 * }</pre>
 *
 * <p>Both masks are 9 integer positions, a literal decimal point, 2 fraction positions and a trailing
 * sign position: {@code 9 + 1 + 2 + 1 = 13}. The sign occupies the final character; a negative value
 * emits a minus and a non-negative value a space. A plus is never emitted and no comma or other
 * grouping separator ever appears. Zero suppression for mask B replaces leading zero digits in the
 * integer part with spaces, up to but not past the decimal point; all nine integer positions are
 * suppression positions, so a value below one renders with nine leading spaces before the decimal
 * point, and fraction digits are never suppressed. Mask A suppresses nothing, so the same small value
 * renders with leading zero characters. The two are therefore <strong>not interchangeable</strong> and
 * each is exposed through its own explicitly named method so a caller cannot silently pick the wrong
 * one.
 *
 * <p><strong>Four distinct numeric-edited masks exist across the two output formats.</strong> The
 * transaction report copybook {@code [app/cpy/CVTRA07Y.cpy]} declares 15-character
 * <em>leading</em>-sign masks with comma grouping - {@code -ZZZ,ZZZ,ZZZ.ZZ} and
 * {@code +ZZZ,ZZZ,ZZZ.ZZ} - for a 133-byte report line. The two statement masks here have no commas,
 * are 13 characters wide and place the sign last. Selecting a report mask for a statement field, or
 * the reverse, is a silent byte-parity failure that no compiler can catch.
 *
 * <p><strong>Numeric formatting policy.</strong> {@code BigDecimal.toString()},
 * {@code BigDecimal.toPlainString()}, {@code String.valueOf(BigDecimal)},
 * {@code java.text.NumberFormat}, {@code java.text.DecimalFormat} and every other locale-sensitive
 * formatter are forbidden here (decision D-27): a locale can introduce a grouping comma, a different
 * decimal separator or a different minus glyph, any one of which breaks byte parity. Both masks are
 * rendered character by character into a fixed 13-position buffer from the value's unscaled digits,
 * obtained from {@link BigInteger#toString()} applied to {@code unscaledValue().abs()}. That call is
 * safe where the {@code BigDecimal} equivalents are not: it is specified as the radix-10 representation
 * using ASCII digit characters only, it is locale-independent, it inserts no grouping separator, and
 * taking the absolute value first means no sign glyph is ever involved. The sign is applied separately
 * into the trailing sign position.
 *
 * <p><strong>Scale and magnitude contract.</strong> Values arrive already at scale
 * {@value #REQUIRED_AMOUNT_SCALE} with {@code RoundingMode.DOWN} applied upstream by the zoned-decimal
 * codec and the computing service, and <strong>this class never scales, never rounds and never performs
 * arithmetic on a value</strong>: it only formats one. Two rejections follow, and both are divergences
 * from the legacy silent {@code MOVE} recorded in the decision log rather than justified here. A value
 * at any other scale is rejected and never re-scaled, per decision D-05, because re-scaling would place
 * a second rounding policy alongside the estate-wide truncation policy that the total absence of
 * {@code ROUNDED} clauses in the source mandates. An integer part exceeding nine digits is rejected
 * rather than left-truncated, per decision D-06: a COBOL {@code MOVE} truncates on the left and the
 * legacy program does exactly that when it moves a ten-integer-digit account balance into this
 * nine-integer-digit mask, but emitting a plausible wrong amount into a financial statement is worse
 * than failing, and the estate's own field widths mean valid data cannot reach the condition.
 *
 * <p><strong>Character field semantics: truncate and pad.</strong> A supplied character value longer
 * than its field is truncated to the field width and a shorter one padded on the right with ASCII
 * spaces - never zeros, nulls or tabs. That matches a COBOL {@code MOVE} into a {@code PIC X(n)} field
 * and is genuinely the legacy behaviour rather than a convenience: the generator moves a 100-byte
 * transaction description into the 49-byte detail field of ST-LINE14, so the tail is lost on the
 * mainframe too. Both truncation and padding are measured in encoded bytes using
 * {@link StandardCharsets#US_ASCII} explicitly, never in {@code String} characters and never against
 * the platform default charset.
 *
 * <p><strong>The currency symbol is inconsistent, and the inconsistency is preserved.</strong>
 * ST-LINE14 and ST-LINE14A each carry a literal dollar sign immediately before their amount field;
 * ST-LINE8, the current-balance line, carries none. That inconsistency is in the source: no currency
 * symbol is added to the balance line and none is removed from the transaction lines.
 *
 * <p>An integer part exceeding nine digits is rejected rather than left-truncated. A COBOL
 * {@code MOVE} would truncate on the left, and the legacy program does exactly that when it moves a
 * ten-integer-digit account balance into this nine-integer-digit mask. Reproducing that here would
 * emit a plausible but wrong amount into a financial statement, which is worse than failing, and the
 * estate's own field widths mean valid data cannot produce the situation. This is a deliberate,
 * recorded divergence.</p>
 *
 * <h2>Character field semantics: truncate and pad</h2>
 *
 * <p>A supplied character value longer than its field is truncated to the field width, and a shorter
 * value is padded on the right with ASCII spaces -- never zeros, nulls or tabs. That matches a COBOL
 * {@code MOVE} into a {@code PIC X(n)} field, and it is genuinely the legacy behaviour rather than a
 * convenience: the generator moves a 100-byte transaction description into the 49-byte detail field
 * of ST-LINE14, so the tail is lost on the mainframe too. Both truncation and padding are measured
 * in encoded bytes using {@link StandardCharsets#US_ASCII} explicitly, never in {@code String}
 * characters and never against the platform default charset.</p>
 *
 * <h2>The currency symbol is inconsistent, and the inconsistency is preserved</h2>
 *
 * <p>ST-LINE14 and ST-LINE14A each carry a literal dollar sign immediately before their amount
 * field. ST-LINE8, the current-balance line, carries none. That inconsistency is in the source. No
 * currency symbol is added to the balance line and none is removed from the transaction lines.</p>
 *
 * <h2>Related context, implemented elsewhere</h2>
 *
 * <ul>
 *   <li>The HTML statement output is a different record width -- a single fixed one-hundred-byte
 *       alphanumeric item named {@code FD-HTMLFILE-REC}, declared at
 *       {@code [app/cbl/CBSTM03A.CBL:L47]} -- and its
 *       literals live in a separate holder for that stream. The two are strictly separate: an
 *       80-byte template must never leak into the 100-byte stream or the reverse.</li>
 *   <li>The generator's control flow is a hand-rolled dispatcher driven by a data-definition-name
 *       work field with backward jumps back into the dispatcher. That becomes an explicit state enum
 *       with a loop-over-switch driver in the statement generation service. None of it belongs
 *       here.</li>
 *   <li>The statement work area {@code [app/cpy/COSTM01.CPY]} becomes a request/response type in the
 *       API layer. This class neither imports nor references it, and imports nothing from this
 *       module at all.</li>
 *   <li>{@code [app/jcl/CREASTMT.JCL]} declares the same data-definition name with a record length of
 *       eighty in one step and of one hundred in the next. The conflict is resolved to 80 for the
 *       text stream and 100 for the HTML stream, matching the two record declarations in the
 *       program. It is noted for the record and changes nothing here.</li>
 *   <li>The statement text output has no page-break logic. Page sizing and line counting belong to
 *       the transaction report path, which is a different output format at a different width.</li>
 * </ul>
 *
 * <h2>Decisions recorded for the migration decision log</h2>
 *
 * <ol>
 *   <li>The two banners have different splits, 31 / 18 / 31 and 32 / 16 / 32, because their texts
 *       differ in length by two. Both are declared explicitly rather than centred by computation,
 *       and neither is corrected toward the other.</li>
 *   <li>Two distinct 13-character trailing-minus masks exist in the statement text:
 *       {@code 9(9).99-} without zero suppression for the current balance, and {@code Z(9).99-} with
 *       zero suppression for the transaction amount and the total. They are not interchangeable.</li>
 *   <li>Four distinct numeric-edited masks exist across the two output formats: the two
 *       13-character trailing-minus masks without commas here, and two 15-character leading-sign
 *       comma-grouped masks in the report copybook. Selecting the wrong one is a silent byte-parity
 *       failure.</li>
 *   <li>No locale-sensitive or default formatting is used. Both masks are rendered explicitly from
 *       the unscaled digits, because a locale could introduce a comma, a different decimal separator
 *       or a different minus glyph.</li>
 *   <li>This class never scales and never rounds. Values arrive at scale
 *       {@value #REQUIRED_AMOUNT_SCALE} with {@code RoundingMode.DOWN} already applied, and a value
 *       at another scale is rejected rather than silently re-scaled, so a truncation-policy
 *       violation cannot hide here. The estate contains zero {@code ROUNDED} clauses.</li>
 *   <li>An integer part exceeding nine digits is rejected rather than left-truncated -- a deliberate
 *       divergence from COBOL {@code MOVE} semantics, because emitting a plausible wrong amount into
 *       a financial statement is worse than failing, and valid data cannot produce the
 *       situation.</li>
 *   <li>The dollar sign is present on the transaction and total lines and absent on the
 *       current-balance line. The inconsistency is in the source and is preserved.</li>
 *   <li>The {@value #STATEMENT_RECORD_LENGTH}-byte image carries no line terminator even though the
 *       COBOL source file has CRLF endings. Record separation is the writer's concern in the batch
 *       layer.</li>
 *   <li>A templating engine is forbidden. The lines are literal constants at fixed widths, because
 *       byte-identical output cannot survive an engine's whitespace and ordering variability.</li>
 *   <li>{@code [app/jcl/CREASTMT.JCL]} declares the same data-definition name with a record length of
 *       eighty and of one hundred in consecutive steps; resolved to 80 for the text stream and 100
 *       for the HTML stream, matching the two record declarations.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 *
 * <p>This class is a stateless holder of immutable constants and pure static functions. It holds no
 * mutable static state, performs no input or output, reads no clock, environment or random source, and
 * is therefore safe for unsynchronized concurrent use.
 */
public final class StatementTextTemplates {

    // Record geometry. Factual layout evidence read out of the legacy source.

    /**
     * Length in US-ASCII bytes of one plain-text statement record. The record is a single
     * alphanumeric item named {@code FD-STMTFILE-REC}, fixed at eighty bytes, declared at
     * {@code [app/cbl/CBSTM03A.CBL:L45]}.
     */
    public static final int STATEMENT_RECORD_LENGTH = 80;

    /**
     * Number of distinct line groups declared under the statement lines structure at
     * {@code [app/cbl/CBSTM03A.CBL:L85-L146]}. Verified as seventeen -- not sixteen, not eighteen.
     */
    public static final int STATEMENT_LINE_GROUP_COUNT = 17;

    // Component widths, one named constant per declared component of each of the seventeen groups.

    /** ST-LINE0 asterisk run, declared twice at {@code [app/cbl/CBSTM03A.CBL:L87]} and L89. */
    public static final int ST_LINE0_ASTERISK_WIDTH = 31;

    /** ST-LINE0 banner text field at {@code [app/cbl/CBSTM03A.CBL:L88]}. */
    public static final int ST_LINE0_TEXT_WIDTH = 18;

    /** ST-LINE1 customer name field at {@code [app/cbl/CBSTM03A.CBL:L91]}. */
    public static final int ST_LINE1_NAME_WIDTH = 75;

    /** ST-LINE1 trailing filler at {@code [app/cbl/CBSTM03A.CBL:L92]}. */
    public static final int ST_LINE1_FILLER_WIDTH = 5;

    /** ST-LINE2 first address field at {@code [app/cbl/CBSTM03A.CBL:L94]}. */
    public static final int ST_LINE2_ADDRESS_WIDTH = 50;

    /** ST-LINE2 trailing filler at {@code [app/cbl/CBSTM03A.CBL:L95]}. */
    public static final int ST_LINE2_FILLER_WIDTH = 30;

    /** ST-LINE3 second address field at {@code [app/cbl/CBSTM03A.CBL:L97]}. */
    public static final int ST_LINE3_ADDRESS_WIDTH = 50;

    /** ST-LINE3 trailing filler at {@code [app/cbl/CBSTM03A.CBL:L98]}. */
    public static final int ST_LINE3_FILLER_WIDTH = 30;

    /**
     * ST-LINE4 third address field at {@code [app/cbl/CBSTM03A.CBL:L100]}. This field spans the
     * whole record; the group declares no filler.
     */
    public static final int ST_LINE4_ADDRESS_WIDTH = 80;

    /**
     * Width of the hyphen rule line, declared identically for ST-LINE5, ST-LINE10 and ST-LINE12 at
     * {@code [app/cbl/CBSTM03A.CBL:L102]}, L121 and L127.
     */
    public static final int RULE_LINE_WIDTH = 80;

    /** ST-LINE6 leading filler at {@code [app/cbl/CBSTM03A.CBL:L104]}. */
    public static final int ST_LINE6_LEADING_FILLER_WIDTH = 33;

    /**
     * ST-LINE6 heading field at {@code [app/cbl/CBSTM03A.CBL:L105]}. The literal is 13 characters,
     * so the field carries exactly one trailing pad byte.
     */
    public static final int ST_LINE6_TEXT_WIDTH = 14;

    /** ST-LINE6 trailing filler at {@code [app/cbl/CBSTM03A.CBL:L106]}. */
    public static final int ST_LINE6_TRAILING_FILLER_WIDTH = 33;

    /**
     * ST-LINE7 label field at {@code [app/cbl/CBSTM03A.CBL:L108]}, holding the account-id label,
     * nine spaces and a colon.
     */
    public static final int ST_LINE7_LABEL_WIDTH = 20;

    /** ST-LINE7 account-id value field at {@code [app/cbl/CBSTM03A.CBL:L109]}. */
    public static final int ST_LINE7_ACCOUNT_ID_WIDTH = 20;

    /** ST-LINE7 trailing filler at {@code [app/cbl/CBSTM03A.CBL:L110]}. */
    public static final int ST_LINE7_FILLER_WIDTH = 40;

    /**
     * ST-LINE8 label field at {@code [app/cbl/CBSTM03A.CBL:L112]}, holding the current-balance
     * label, four spaces and a colon.
     */
    public static final int ST_LINE8_LABEL_WIDTH = 20;

    /** ST-LINE8 first trailing filler at {@code [app/cbl/CBSTM03A.CBL:L114]}. */
    public static final int ST_LINE8_FILLER_1_WIDTH = 7;

    /**
     * ST-LINE8 second trailing filler at {@code [app/cbl/CBSTM03A.CBL:L115]}. The group declares two
     * separate fillers rather than one of 47 bytes, and both are reproduced as declared.
     */
    public static final int ST_LINE8_FILLER_2_WIDTH = 40;

    /**
     * ST-LINE9 label field at {@code [app/cbl/CBSTM03A.CBL:L117]}, holding the credit-score label,
     * nine spaces and a colon.
     */
    public static final int ST_LINE9_LABEL_WIDTH = 20;

    /** ST-LINE9 credit-score value field at {@code [app/cbl/CBSTM03A.CBL:L118]}. */
    public static final int ST_LINE9_FICO_SCORE_WIDTH = 20;

    /** ST-LINE9 trailing filler at {@code [app/cbl/CBSTM03A.CBL:L119]}. */
    public static final int ST_LINE9_FILLER_WIDTH = 40;

    /** ST-LINE11 leading filler at {@code [app/cbl/CBSTM03A.CBL:L123]}. */
    public static final int ST_LINE11_LEADING_FILLER_WIDTH = 30;

    /**
     * ST-LINE11 heading field at {@code [app/cbl/CBSTM03A.CBL:L124]}. The literal is 19 upper-case
     * characters plus exactly one trailing space, filling the field.
     */
    public static final int ST_LINE11_TEXT_WIDTH = 20;

    /** ST-LINE11 trailing filler at {@code [app/cbl/CBSTM03A.CBL:L125]}. */
    public static final int ST_LINE11_TRAILING_FILLER_WIDTH = 30;

    /**
     * ST-LINE13 first heading field at {@code [app/cbl/CBSTM03A.CBL:L129]}, holding the
     * transaction-id heading and nine trailing spaces.
     */
    public static final int ST_LINE13_TRAN_ID_WIDTH = 16;

    /**
     * ST-LINE13 second heading field at {@code [app/cbl/CBSTM03A.CBL:L130]}. Its literal is 16
     * characters -- the detail heading plus four trailing spaces -- padded out to this width.
     */
    public static final int ST_LINE13_TRAN_DETAILS_WIDTH = 51;

    /**
     * ST-LINE13 third heading field at {@code [app/cbl/CBSTM03A.CBL:L131]}, holding two leading
     * spaces and the amount heading.
     */
    public static final int ST_LINE13_TRAN_AMOUNT_WIDTH = 13;

    /** ST-LINE14 transaction-id field at {@code [app/cbl/CBSTM03A.CBL:L133]}. */
    public static final int ST_LINE14_TRAN_ID_WIDTH = 16;

    /** ST-LINE14 single-space separator at {@code [app/cbl/CBSTM03A.CBL:L134]}. */
    public static final int ST_LINE14_SEPARATOR_WIDTH = 1;

    /** ST-LINE14 transaction-detail field at {@code [app/cbl/CBSTM03A.CBL:L135]}. */
    public static final int ST_LINE14_TRAN_DETAIL_WIDTH = 49;

    /** ST-LINE14 currency-symbol field at {@code [app/cbl/CBSTM03A.CBL:L136]}. */
    public static final int ST_LINE14_CURRENCY_WIDTH = 1;

    /** ST-LINE14A label field at {@code [app/cbl/CBSTM03A.CBL:L139]}. */
    public static final int ST_LINE14A_LABEL_WIDTH = 10;

    /** ST-LINE14A filler at {@code [app/cbl/CBSTM03A.CBL:L140]}. */
    public static final int ST_LINE14A_FILLER_WIDTH = 56;

    /** ST-LINE14A currency-symbol field at {@code [app/cbl/CBSTM03A.CBL:L141]}. */
    public static final int ST_LINE14A_CURRENCY_WIDTH = 1;

    /**
     * ST-LINE15 asterisk run, declared twice at {@code [app/cbl/CBSTM03A.CBL:L144]} and L146. This
     * is 32, one more per side than the start banner's 31, and the difference is contractual.
     */
    public static final int ST_LINE15_ASTERISK_WIDTH = 32;

    /** ST-LINE15 banner text field at {@code [app/cbl/CBSTM03A.CBL:L145]}. */
    public static final int ST_LINE15_TEXT_WIDTH = 16;

    // Mask geometry, shared by both trailing-minus statement masks.

    /**
     * Rendered width in US-ASCII bytes of either statement amount mask: nine integer positions, a
     * decimal point, two fraction positions and a trailing sign position.
     */
    public static final int AMOUNT_MASK_LENGTH = 13;

    /** Integer digit positions in either statement amount mask. */
    public static final int AMOUNT_MASK_INTEGER_DIGITS = 9;

    /** Fraction digit positions in either statement amount mask. These are never suppressed. */
    public static final int AMOUNT_MASK_FRACTION_DIGITS = 2;

    /**
     * Scale every amount must already carry on arrival. The upstream zoned-decimal codec and the
     * computing services apply {@code RoundingMode.DOWN} at this scale; nothing here re-scales.
     */
    public static final int REQUIRED_AMOUNT_SCALE = 2;

    // Single characters and byte values used to build and pad the records. Padding is always the
    // ASCII space -- never a zero, never a null, never a tab.

    /** The one and only pad character for character fields. */
    private static final char SPACE = ' ';

    /** Fill character of both banner runs. */
    private static final char ASTERISK = '*';

    /** Fill character of the rule line. */
    private static final char HYPHEN = '-';

    /** Digit character that mask A prints and mask B suppresses in leading integer positions. */
    private static final char ZERO_DIGIT = '0';

    /** Literal insertion character between the integer and fraction positions of both masks. */
    private static final char DECIMAL_POINT = '.';

    /**
     * Sign character written into the trailing sign position of both masks for a negative value.
     * Declared separately from {@link #HYPHEN} because the two are semantically distinct: one fills
     * a rule line, the other carries the arithmetic sign of an amount.
     */
    private static final char TRAILING_MINUS = '-';

    /** ASCII space as a byte, used when padding an encoded field image. */
    private static final byte ASCII_SPACE_BYTE = (byte) ' ';

    /**
     * ASCII carriage return, given by code point so that no escape sequence appears anywhere in this
     * file. It exists solely so an assembled record can be rejected for containing one; it is never
     * emitted.
     */
    private static final byte ASCII_CARRIAGE_RETURN_BYTE = 13;

    /**
     * ASCII line feed, given by code point for the same reason as
     * {@link #ASCII_CARRIAGE_RETURN_BYTE}. Detected and rejected, never emitted.
     */
    private static final byte ASCII_LINE_FEED_BYTE = 10;

    /**
     * Currency symbol literal of ST-LINE14 at {@code [app/cbl/CBSTM03A.CBL:L136]} and of ST-LINE14A
     * at L141. ST-LINE8 deliberately has none; see the class documentation.
     */
    private static final String CURRENCY_SYMBOL = "$";

    /** Single-space separator literal of ST-LINE14 at {@code [app/cbl/CBSTM03A.CBL:L134]}. */
    private static final String FIELD_SEPARATOR = " ";

    // Mask picture clauses, carried as text purely so a rejection message can name the mask that
    // rejected the value.

    /** Picture clause of mask A, from {@code [app/cbl/CBSTM03A.CBL:L113]}. */
    private static final String MASK_A_PICTURE = "9(9).99-";

    /** Picture clause of mask B, from {@code [app/cbl/CBSTM03A.CBL:L137]} and L142. */
    private static final String MASK_B_PICTURE = "Z(9).99-";

    // Literal text fragments, transcribed character for character from the legacy declarations.
    // These are private because the assembled records below are the public contract; the static
    // verification block asserts every one of them against the width the source declares.
    //
    // Declared ahead of the assembled records because static fields initialize in textual order.

    /** Start banner text at {@code [app/cbl/CBSTM03A.CBL:L88]}: 18 characters, filling its field. */
    private static final String ST_LINE0_TEXT_LITERAL = "START OF STATEMENT";

    /**
     * Basic-details heading at {@code [app/cbl/CBSTM03A.CBL:L105]}: 13 characters inside a 14-byte
     * field, so exactly one trailing pad byte.
     */
    private static final String ST_LINE6_TEXT_LITERAL = "Basic Details";

    /**
     * Account-id label at {@code [app/cbl/CBSTM03A.CBL:L108]}: the 10-character label, then exactly
     * NINE spaces, then the colon -- 20 bytes in total.
     */
    private static final String ST_LINE7_LABEL_LITERAL = "Account ID         :";

    /**
     * Current-balance label at {@code [app/cbl/CBSTM03A.CBL:L112]}: the 15-character label, then
     * exactly FOUR spaces, then the colon -- 20 bytes in total.
     */
    private static final String ST_LINE8_LABEL_LITERAL = "Current Balance    :";

    /**
     * Credit-score label at {@code [app/cbl/CBSTM03A.CBL:L117]}: the 10-character label, then exactly
     * NINE spaces, then the colon -- 20 bytes in total.
     */
    private static final String ST_LINE9_LABEL_LITERAL = "FICO Score         :";

    /**
     * Transaction-summary heading at {@code [app/cbl/CBSTM03A.CBL:L124]}: 19 upper-case characters
     * plus exactly ONE trailing space, filling its 20-byte field. The trailing space is part of the
     * literal in the source and is preserved rather than tidied away.
     */
    private static final String ST_LINE11_TEXT_LITERAL = "TRANSACTION SUMMARY ";

    /**
     * First column heading at {@code [app/cbl/CBSTM03A.CBL:L129]}: the 7-character heading plus
     * exactly NINE trailing spaces, filling its 16-byte field.
     */
    private static final String ST_LINE13_TRAN_ID_LITERAL = "Tran ID         ";

    /**
     * Second column heading at {@code [app/cbl/CBSTM03A.CBL:L130]}: a 16-character literal -- the
     * 12-character heading plus FOUR trailing spaces -- declared inside a 51-byte field, so it is
     * padded out with a further 35 spaces.
     */
    private static final String ST_LINE13_TRAN_DETAILS_LITERAL = "Tran Details    ";

    /**
     * Third column heading at {@code [app/cbl/CBSTM03A.CBL:L131]}: exactly TWO leading spaces then
     * the 11-character heading, filling its 13-byte field.
     */
    private static final String ST_LINE13_TRAN_AMOUNT_LITERAL = "  Tran Amount";

    /** Total label at {@code [app/cbl/CBSTM03A.CBL:L139]}: 10 characters, filling its field. */
    private static final String ST_LINE14A_LABEL_LITERAL = "Total EXP:";

    /** End banner text at {@code [app/cbl/CBSTM03A.CBL:L145]}: 16 characters, filling its field. */
    private static final String ST_LINE15_TEXT_LITERAL = "END OF STATEMENT";

    // Layout verification. Every literal width, every group's component sum, the banner asymmetry
    // and the mask geometry are checked once, at class initialization. This block is declared
    // BEFORE the assembled records below, and static initializers run in textual order, so the
    // inputs are verified before anything is built from them: a layout regression fails at class
    // initialization instead of showing up later as a golden-file mismatch.

    static {
        // Literals that fill their field exactly.
        requireLiteralByteLength(ST_LINE0_TEXT_LITERAL, ST_LINE0_TEXT_WIDTH,
                "app/cbl/CBSTM03A.CBL:L88");
        requireLiteralByteLength(ST_LINE7_LABEL_LITERAL, ST_LINE7_LABEL_WIDTH,
                "app/cbl/CBSTM03A.CBL:L108 -- label, NINE spaces, colon");
        requireLiteralByteLength(ST_LINE8_LABEL_LITERAL, ST_LINE8_LABEL_WIDTH,
                "app/cbl/CBSTM03A.CBL:L112 -- label, FOUR spaces, colon");
        requireLiteralByteLength(ST_LINE9_LABEL_LITERAL, ST_LINE9_LABEL_WIDTH,
                "app/cbl/CBSTM03A.CBL:L117 -- label, NINE spaces, colon");
        requireLiteralByteLength(ST_LINE11_TEXT_LITERAL, ST_LINE11_TEXT_WIDTH,
                "app/cbl/CBSTM03A.CBL:L124 -- 19 upper-case characters and ONE trailing space");
        requireLiteralByteLength(ST_LINE13_TRAN_ID_LITERAL, ST_LINE13_TRAN_ID_WIDTH,
                "app/cbl/CBSTM03A.CBL:L129 -- heading and NINE trailing spaces");
        requireLiteralByteLength(ST_LINE13_TRAN_AMOUNT_LITERAL, ST_LINE13_TRAN_AMOUNT_WIDTH,
                "app/cbl/CBSTM03A.CBL:L131 -- TWO leading spaces and the heading");
        requireLiteralByteLength(ST_LINE14A_LABEL_LITERAL, ST_LINE14A_LABEL_WIDTH,
                "app/cbl/CBSTM03A.CBL:L139");
        requireLiteralByteLength(ST_LINE15_TEXT_LITERAL, ST_LINE15_TEXT_WIDTH,
                "app/cbl/CBSTM03A.CBL:L145");
        requireLiteralByteLength(CURRENCY_SYMBOL, ST_LINE14_CURRENCY_WIDTH,
                "app/cbl/CBSTM03A.CBL:L136");
        requireLiteralByteLength(FIELD_SEPARATOR, ST_LINE14_SEPARATOR_WIDTH,
                "app/cbl/CBSTM03A.CBL:L134");

        // Literals shorter than their field, where the field carries declared pad bytes.
        requireLiteralByteLength(ST_LINE6_TEXT_LITERAL, 13,
                "app/cbl/CBSTM03A.CBL:L105 -- 13 characters in a 14-byte field, ONE pad byte");
        requireLiteralByteLength(ST_LINE13_TRAN_DETAILS_LITERAL, 16,
                "app/cbl/CBSTM03A.CBL:L130 -- 16 characters padded out to 51 bytes");

        // Component sums, one check per line group. All seventeen must total the record length.
        requireComponentSum("ST-LINE0", ST_LINE0_ASTERISK_WIDTH, ST_LINE0_TEXT_WIDTH,
                ST_LINE0_ASTERISK_WIDTH);
        requireComponentSum("ST-LINE1", ST_LINE1_NAME_WIDTH, ST_LINE1_FILLER_WIDTH);
        requireComponentSum("ST-LINE2", ST_LINE2_ADDRESS_WIDTH, ST_LINE2_FILLER_WIDTH);
        requireComponentSum("ST-LINE3", ST_LINE3_ADDRESS_WIDTH, ST_LINE3_FILLER_WIDTH);
        requireComponentSum("ST-LINE4", ST_LINE4_ADDRESS_WIDTH);
        requireComponentSum("ST-LINE5", RULE_LINE_WIDTH);
        requireComponentSum("ST-LINE6", ST_LINE6_LEADING_FILLER_WIDTH, ST_LINE6_TEXT_WIDTH,
                ST_LINE6_TRAILING_FILLER_WIDTH);
        requireComponentSum("ST-LINE7", ST_LINE7_LABEL_WIDTH, ST_LINE7_ACCOUNT_ID_WIDTH,
                ST_LINE7_FILLER_WIDTH);
        requireComponentSum("ST-LINE8", ST_LINE8_LABEL_WIDTH, AMOUNT_MASK_LENGTH,
                ST_LINE8_FILLER_1_WIDTH, ST_LINE8_FILLER_2_WIDTH);
        requireComponentSum("ST-LINE9", ST_LINE9_LABEL_WIDTH, ST_LINE9_FICO_SCORE_WIDTH,
                ST_LINE9_FILLER_WIDTH);
        requireComponentSum("ST-LINE10", RULE_LINE_WIDTH);
        requireComponentSum("ST-LINE11", ST_LINE11_LEADING_FILLER_WIDTH, ST_LINE11_TEXT_WIDTH,
                ST_LINE11_TRAILING_FILLER_WIDTH);
        requireComponentSum("ST-LINE12", RULE_LINE_WIDTH);
        requireComponentSum("ST-LINE13", ST_LINE13_TRAN_ID_WIDTH, ST_LINE13_TRAN_DETAILS_WIDTH,
                ST_LINE13_TRAN_AMOUNT_WIDTH);
        requireComponentSum("ST-LINE14", ST_LINE14_TRAN_ID_WIDTH, ST_LINE14_SEPARATOR_WIDTH,
                ST_LINE14_TRAN_DETAIL_WIDTH, ST_LINE14_CURRENCY_WIDTH, AMOUNT_MASK_LENGTH);
        requireComponentSum("ST-LINE14A", ST_LINE14A_LABEL_WIDTH, ST_LINE14A_FILLER_WIDTH,
                ST_LINE14A_CURRENCY_WIDTH, AMOUNT_MASK_LENGTH);
        requireComponentSum("ST-LINE15", ST_LINE15_ASTERISK_WIDTH, ST_LINE15_TEXT_WIDTH,
                ST_LINE15_ASTERISK_WIDTH);

        // The banner asymmetry is contractual and must survive every future edit.
        requireBannerAsymmetry(ST_LINE0_ASTERISK_WIDTH, ST_LINE15_ASTERISK_WIDTH);

        // Nine integer positions, a decimal point, two fraction positions, a trailing sign.
        requireMaskGeometry(AMOUNT_MASK_INTEGER_DIGITS, AMOUNT_MASK_FRACTION_DIGITS,
                AMOUNT_MASK_LENGTH);
    }

    // The eight wholly fixed line groups. Each is assembled from explicit literals and explicit
    // padding at the declared component widths, then checked at the full record length.

    /**
     * ST-LINE0 -- the start banner, from {@code [app/cbl/CBSTM03A.CBL:L86-L89]}.
     *
     * <p>Split 31 / 18 / 31. This is <em>not</em> the same split as the end banner, and the two must
     * never be produced by a shared centring computation. See {@link #ST_LINE15_END_BANNER}.</p>
     */
    public static final String ST_LINE0_START_BANNER = requireStatementRecordLength(
            fill(ASTERISK, ST_LINE0_ASTERISK_WIDTH)
                    + fitToField(ST_LINE0_TEXT_LITERAL, ST_LINE0_TEXT_WIDTH)
                    + fill(ASTERISK, ST_LINE0_ASTERISK_WIDTH));

    /**
     * The hyphen rule line, {@value #RULE_LINE_WIDTH} hyphens with nothing else.
     *
     * <p>The content is declared once here and referenced by the three position-named constants
     * {@link #ST_LINE5_RULE}, {@link #ST_LINE10_RULE} and {@link #ST_LINE12_RULE}. All three
     * positions are genuinely emitted by the legacy program and must not be collapsed out of the
     * emitted sequence.</p>
     */
    public static final String RULE_LINE =
            requireStatementRecordLength(fill(HYPHEN, RULE_LINE_WIDTH));

    /**
     * ST-LINE5 -- the rule line below the address block, from
     * {@code [app/cbl/CBSTM03A.CBL:L101-L102]}. Same content as {@link #RULE_LINE}, distinct
     * position.
     */
    public static final String ST_LINE5_RULE = RULE_LINE;

    /**
     * ST-LINE6 -- the basic-details heading, from {@code [app/cbl/CBSTM03A.CBL:L103-L106]}.
     *
     * <p>Split 33 / 14 / 33, where the 14-byte field holds a 13-character literal followed by
     * exactly one pad byte.</p>
     */
    public static final String ST_LINE6_BASIC_DETAILS_HEADING = requireStatementRecordLength(
            fill(SPACE, ST_LINE6_LEADING_FILLER_WIDTH)
                    + fitToField(ST_LINE6_TEXT_LITERAL, ST_LINE6_TEXT_WIDTH)
                    + fill(SPACE, ST_LINE6_TRAILING_FILLER_WIDTH));

    /**
     * ST-LINE10 -- the rule line above the transaction-summary heading, from
     * {@code [app/cbl/CBSTM03A.CBL:L120-L121]}. Same content as {@link #RULE_LINE}, distinct
     * position.
     */
    public static final String ST_LINE10_RULE = RULE_LINE;

    /**
     * ST-LINE11 -- the transaction-summary heading, from
     * {@code [app/cbl/CBSTM03A.CBL:L122-L125]}.
     *
     * <p>Split 30 / 20 / 30, where the 20-byte field holds 19 upper-case characters plus exactly one
     * trailing space.</p>
     */
    public static final String ST_LINE11_TRANSACTION_SUMMARY_HEADING = requireStatementRecordLength(
            fill(SPACE, ST_LINE11_LEADING_FILLER_WIDTH)
                    + fitToField(ST_LINE11_TEXT_LITERAL, ST_LINE11_TEXT_WIDTH)
                    + fill(SPACE, ST_LINE11_TRAILING_FILLER_WIDTH));

    /**
     * ST-LINE12 -- the rule line below the transaction-summary heading and again above the total,
     * from {@code [app/cbl/CBSTM03A.CBL:L126-L127]}. Same content as {@link #RULE_LINE}, distinct
     * position.
     */
    public static final String ST_LINE12_RULE = RULE_LINE;

    /**
     * ST-LINE13 -- the transaction column headings, from
     * {@code [app/cbl/CBSTM03A.CBL:L128-L131]}.
     *
     * <p>Split 16 / 51 / 13: the transaction-id heading with nine trailing spaces, then a
     * 16-character detail heading padded out to 51 bytes, then two leading spaces before the amount
     * heading.</p>
     */
    public static final String ST_LINE13_TRANSACTION_COLUMN_HEADINGS = requireStatementRecordLength(
            fitToField(ST_LINE13_TRAN_ID_LITERAL, ST_LINE13_TRAN_ID_WIDTH)
                    + fitToField(ST_LINE13_TRAN_DETAILS_LITERAL, ST_LINE13_TRAN_DETAILS_WIDTH)
                    + fitToField(ST_LINE13_TRAN_AMOUNT_LITERAL, ST_LINE13_TRAN_AMOUNT_WIDTH));

    /**
     * ST-LINE15 -- the end banner, from {@code [app/cbl/CBSTM03A.CBL:L143-L146]}.
     *
     * <p>Split 32 / 16 / 32. The asterisk run is one byte longer per side than the start banner's
     * because the text is two characters shorter. The asymmetry is contractual; see
     * {@link #ST_LINE0_START_BANNER}.</p>
     */
    public static final String ST_LINE15_END_BANNER = requireStatementRecordLength(
            fill(ASTERISK, ST_LINE15_ASTERISK_WIDTH)
                    + fitToField(ST_LINE15_TEXT_LITERAL, ST_LINE15_TEXT_WIDTH)
                    + fill(ASTERISK, ST_LINE15_ASTERISK_WIDTH));

    /** Not instantiable: a stateless holder of layout constants and pure formatting functions. */
    private StatementTextTemplates() {
        throw new AssertionError("StatementTextTemplates is a static utility and is not instantiable");
    }

    // The nine line groups that carry substituted values. Each returns one complete record of
    // exactly STATEMENT_RECORD_LENGTH US-ASCII bytes, with no line terminator.
    //
    // Character arguments follow COBOL MOVE semantics into a PIC X(n) field: a value longer than
    // its field is truncated to the field width and a shorter one is padded on the right with ASCII
    // spaces. Truncation and padding are measured in encoded bytes, never in String characters.

    /**
     * ST-LINE1 -- the customer name line, from {@code [app/cbl/CBSTM03A.CBL:L90-L92]}.
     *
     * <p>Composition: 75 bytes of name followed by 5 spaces.</p>
     *
     * @param customerName the assembled customer name; truncated to 75 encoded bytes if longer and
     *                     right-padded with spaces if shorter, exactly as the legacy field does
     * @return one statement record of {@value #STATEMENT_RECORD_LENGTH} US-ASCII bytes
     * @throws NullPointerException if {@code customerName} is {@code null}; an absent value and an
     *                              empty value are different, and the legacy field was never absent
     */
    public static String stLine1CustomerName(String customerName) {
        Objects.requireNonNull(customerName, "customerName");
        return requireStatementRecordLength(
                fitToField(customerName, ST_LINE1_NAME_WIDTH)
                        + fill(SPACE, ST_LINE1_FILLER_WIDTH));
    }

    /**
     * ST-LINE2 -- the first address line, from {@code [app/cbl/CBSTM03A.CBL:L93-L95]}.
     *
     * <p>Composition: 50 bytes of address followed by 30 spaces.</p>
     *
     * @param addressLine1 the first address line; truncated to 50 encoded bytes if longer and
     *                     right-padded with spaces if shorter
     * @return one statement record of {@value #STATEMENT_RECORD_LENGTH} US-ASCII bytes
     * @throws NullPointerException if {@code addressLine1} is {@code null}
     */
    public static String stLine2AddressLine1(String addressLine1) {
        Objects.requireNonNull(addressLine1, "addressLine1");
        return requireStatementRecordLength(
                fitToField(addressLine1, ST_LINE2_ADDRESS_WIDTH)
                        + fill(SPACE, ST_LINE2_FILLER_WIDTH));
    }

    /**
     * ST-LINE3 -- the second address line, from {@code [app/cbl/CBSTM03A.CBL:L96-L98]}.
     *
     * <p>Composition: 50 bytes of address followed by 30 spaces. Declared separately from
     * {@link #stLine2AddressLine1} because it is a separate line group in the source, even though the
     * two share the same composition.</p>
     *
     * @param addressLine2 the second address line; truncated to 50 encoded bytes if longer and
     *                     right-padded with spaces if shorter
     * @return one statement record of {@value #STATEMENT_RECORD_LENGTH} US-ASCII bytes
     * @throws NullPointerException if {@code addressLine2} is {@code null}
     */
    public static String stLine3AddressLine2(String addressLine2) {
        Objects.requireNonNull(addressLine2, "addressLine2");
        return requireStatementRecordLength(
                fitToField(addressLine2, ST_LINE3_ADDRESS_WIDTH)
                        + fill(SPACE, ST_LINE3_FILLER_WIDTH));
    }

    /**
     * ST-LINE4 -- the third address line, from {@code [app/cbl/CBSTM03A.CBL:L99-L100]}.
     *
     * <p>Composition: 80 bytes of address spanning the whole record. This group declares no filler,
     * so the field itself provides the padding.</p>
     *
     * @param addressLine3 the third address line, which the legacy program assembles from city,
     *                     state, country and postal code; truncated to 80 encoded bytes if longer
     *                     and right-padded with spaces if shorter
     * @return one statement record of {@value #STATEMENT_RECORD_LENGTH} US-ASCII bytes
     * @throws NullPointerException if {@code addressLine3} is {@code null}
     */
    public static String stLine4AddressLine3(String addressLine3) {
        Objects.requireNonNull(addressLine3, "addressLine3");
        return requireStatementRecordLength(fitToField(addressLine3, ST_LINE4_ADDRESS_WIDTH));
    }

    /**
     * ST-LINE7 -- the account-id line, from {@code [app/cbl/CBSTM03A.CBL:L107-L110]}.
     *
     * <p>Composition: the 20-byte label holding the account-id text, exactly nine spaces and a
     * colon; then 20 bytes of value; then 40 spaces.</p>
     *
     * @param accountId the account identifier as text; truncated to 20 encoded bytes if longer and
     *                  right-padded with spaces if shorter, because the legacy value field is
     *                  alphanumeric rather than numeric-edited
     * @return one statement record of {@value #STATEMENT_RECORD_LENGTH} US-ASCII bytes
     * @throws NullPointerException if {@code accountId} is {@code null}
     */
    public static String stLine7AccountId(String accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return requireStatementRecordLength(
                fitToField(ST_LINE7_LABEL_LITERAL, ST_LINE7_LABEL_WIDTH)
                        + fitToField(accountId, ST_LINE7_ACCOUNT_ID_WIDTH)
                        + fill(SPACE, ST_LINE7_FILLER_WIDTH));
    }

    /**
     * ST-LINE8 -- the current-balance line, from {@code [app/cbl/CBSTM03A.CBL:L111-L115]}.
     *
     * <p>Composition: the 20-byte label holding the balance text, exactly four spaces and a colon;
     * then the 13-byte amount under mask A; then a 7-space filler; then a 40-space filler. The two
     * fillers are declared separately in the source and are reproduced separately here rather than
     * merged into one run of 47.</p>
     *
     * <p>This line carries <strong>no</strong> currency symbol, unlike
     * {@link #stLine14Transaction(String, String, BigDecimal)} and
     * {@link #stLine14aTotalExpenditure(BigDecimal)}. The inconsistency is in the source and is
     * preserved.</p>
     *
     * <p>The amount uses mask A, {@code 9(9).99-}, which prints leading zeros. It is
     * <em>not</em> the zero-suppressed mask used by the transaction and total lines.</p>
     *
     * @param currentBalance the account balance, already at scale {@value #REQUIRED_AMOUNT_SCALE}
     * @return one statement record of {@value #STATEMENT_RECORD_LENGTH} US-ASCII bytes
     * @throws NullPointerException     if {@code currentBalance} is {@code null}
     * @throws IllegalArgumentException if the scale is not {@value #REQUIRED_AMOUNT_SCALE} or the
     *                                  integer part exceeds {@value #AMOUNT_MASK_INTEGER_DIGITS}
     *                                  digits
     */
    public static String stLine8CurrentBalance(BigDecimal currentBalance) {
        Objects.requireNonNull(currentBalance, "currentBalance");
        return requireStatementRecordLength(
                fitToField(ST_LINE8_LABEL_LITERAL, ST_LINE8_LABEL_WIDTH)
                        + formatAmountMaskWithoutZeroSuppression(currentBalance)
                        + fill(SPACE, ST_LINE8_FILLER_1_WIDTH)
                        + fill(SPACE, ST_LINE8_FILLER_2_WIDTH));
    }

    /**
     * ST-LINE9 -- the credit-score line, from {@code [app/cbl/CBSTM03A.CBL:L116-L119]}.
     *
     * <p>Composition: the 20-byte label holding the score text, exactly nine spaces and a colon;
     * then 20 bytes of value; then 40 spaces.</p>
     *
     * @param ficoScore the credit score as text; truncated to 20 encoded bytes if longer and
     *                  right-padded with spaces if shorter, because the legacy value field is
     *                  alphanumeric rather than numeric-edited
     * @return one statement record of {@value #STATEMENT_RECORD_LENGTH} US-ASCII bytes
     * @throws NullPointerException if {@code ficoScore} is {@code null}
     */
    public static String stLine9FicoScore(String ficoScore) {
        Objects.requireNonNull(ficoScore, "ficoScore");
        return requireStatementRecordLength(
                fitToField(ST_LINE9_LABEL_LITERAL, ST_LINE9_LABEL_WIDTH)
                        + fitToField(ficoScore, ST_LINE9_FICO_SCORE_WIDTH)
                        + fill(SPACE, ST_LINE9_FILLER_WIDTH));
    }

    /**
     * ST-LINE14 -- one transaction detail line, from {@code [app/cbl/CBSTM03A.CBL:L132-L137]}.
     *
     * <p>Composition: 16 bytes of transaction id; one space; 49 bytes of detail; one dollar sign; the
     * 13-byte amount under mask B.</p>
     *
     * <p>The 49-byte detail field is narrower than the 100-byte description the legacy program moves
     * into it, so a long description loses its tail on the mainframe exactly as it does here.</p>
     *
     * <p>The amount uses mask B, {@code Z(9).99-}, which replaces leading zeros with spaces. It is
     * <em>not</em> the unsuppressed mask used by the current-balance line.</p>
     *
     * @param transactionId      the transaction identifier; truncated to 16 encoded bytes if longer
     *                           and right-padded with spaces if shorter
     * @param transactionDetails the transaction description; truncated to 49 encoded bytes if longer
     *                           and right-padded with spaces if shorter
     * @param transactionAmount  the transaction amount, already at scale
     *                           {@value #REQUIRED_AMOUNT_SCALE}
     * @return one statement record of {@value #STATEMENT_RECORD_LENGTH} US-ASCII bytes
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if the scale is not {@value #REQUIRED_AMOUNT_SCALE} or the
     *                                  integer part exceeds {@value #AMOUNT_MASK_INTEGER_DIGITS}
     *                                  digits
     */
    public static String stLine14Transaction(String transactionId,
                                             String transactionDetails,
                                             BigDecimal transactionAmount) {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(transactionDetails, "transactionDetails");
        Objects.requireNonNull(transactionAmount, "transactionAmount");
        return requireStatementRecordLength(
                fitToField(transactionId, ST_LINE14_TRAN_ID_WIDTH)
                        + fitToField(FIELD_SEPARATOR, ST_LINE14_SEPARATOR_WIDTH)
                        + fitToField(transactionDetails, ST_LINE14_TRAN_DETAIL_WIDTH)
                        + fitToField(CURRENCY_SYMBOL, ST_LINE14_CURRENCY_WIDTH)
                        + formatAmountMaskWithZeroSuppression(transactionAmount));
    }

    /**
     * ST-LINE14A -- the total expenditure line, from {@code [app/cbl/CBSTM03A.CBL:L138-L142]}.
     *
     * <p>Composition: the 10-byte total label; 56 spaces; one dollar sign; the 13-byte amount under
     * mask B.</p>
     *
     * <p>The total arrives already accumulated. Nothing is summed, scaled or rounded here.</p>
     *
     * @param totalTransactionAmount the accumulated total, already at scale
     *                               {@value #REQUIRED_AMOUNT_SCALE}
     * @return one statement record of {@value #STATEMENT_RECORD_LENGTH} US-ASCII bytes
     * @throws NullPointerException     if {@code totalTransactionAmount} is {@code null}
     * @throws IllegalArgumentException if the scale is not {@value #REQUIRED_AMOUNT_SCALE} or the
     *                                  integer part exceeds {@value #AMOUNT_MASK_INTEGER_DIGITS}
     *                                  digits
     */
    public static String stLine14aTotalExpenditure(BigDecimal totalTransactionAmount) {
        Objects.requireNonNull(totalTransactionAmount, "totalTransactionAmount");
        return requireStatementRecordLength(
                fitToField(ST_LINE14A_LABEL_LITERAL, ST_LINE14A_LABEL_WIDTH)
                        + fill(SPACE, ST_LINE14A_FILLER_WIDTH)
                        + fitToField(CURRENCY_SYMBOL, ST_LINE14A_CURRENCY_WIDTH)
                        + formatAmountMaskWithZeroSuppression(totalTransactionAmount));
    }

    // The two numeric-edited masks. They are exposed as two explicitly named methods, never as one
    // method with a flag argument, so that a caller cannot silently select the wrong mask. The
    // shared private renderer below guarantees that the geometry and the trailing-minus semantics
    // stay identical between them; zero suppression is the only difference.

    /**
     * Mask A -- {@code 9(9).99-}, from {@code [app/cbl/CBSTM03A.CBL:L113]}.
     * <strong>Leading zeros are printed, not suppressed.</strong>
     *
     * <p>Used by the current-balance line only. Nine integer positions, a decimal point, two
     * fraction positions and a trailing sign position: a negative value ends in a minus and a
     * non-negative value ends in a space. A plus sign is never emitted and no grouping separator ever
     * appears. A value of one cent therefore renders as eight zeros, a further zero, the decimal
     * point, {@code 01} and a trailing space.</p>
     *
     * <p>This is not interchangeable with {@link #formatAmountMaskWithZeroSuppression(BigDecimal)},
     * and neither is interchangeable with the 15-character leading-sign comma-grouped report masks in
     * {@code [app/cpy/CVTRA07Y.cpy]}.</p>
     *
     * @param amount the value to render, already at scale {@value #REQUIRED_AMOUNT_SCALE} with
     *               {@code RoundingMode.DOWN} applied upstream
     * @return exactly {@value #AMOUNT_MASK_LENGTH} US-ASCII bytes
     * @throws NullPointerException     if {@code amount} is {@code null}
     * @throws IllegalArgumentException if the scale is not {@value #REQUIRED_AMOUNT_SCALE}, or the
     *                                  integer part needs more than
     *                                  {@value #AMOUNT_MASK_INTEGER_DIGITS} digits
     */
    public static String formatAmountMaskWithoutZeroSuppression(BigDecimal amount) {
        return renderTrailingMinusAmount(amount, false, MASK_A_PICTURE);
    }

    /**
     * Mask B -- {@code Z(9).99-}, from {@code [app/cbl/CBSTM03A.CBL:L137]} and L142.
     * <strong>Leading zeros are replaced by spaces.</strong>
     *
     * <p>Used by the transaction amount and the total. Suppression covers all nine integer positions
     * and stops at the decimal point, so a value below one renders with nine leading spaces before
     * the decimal point. Fraction digits are never suppressed. The trailing sign position behaves
     * exactly as in mask A: a minus for a negative value, a space otherwise, never a plus, and no
     * grouping separator.</p>
     *
     * <p>This is not interchangeable with
     * {@link #formatAmountMaskWithoutZeroSuppression(BigDecimal)}, and neither is interchangeable
     * with the 15-character leading-sign comma-grouped report masks in
     * {@code [app/cpy/CVTRA07Y.cpy]}.</p>
     *
     * @param amount the value to render, already at scale {@value #REQUIRED_AMOUNT_SCALE} with
     *               {@code RoundingMode.DOWN} applied upstream
     * @return exactly {@value #AMOUNT_MASK_LENGTH} US-ASCII bytes
     * @throws NullPointerException     if {@code amount} is {@code null}
     * @throws IllegalArgumentException if the scale is not {@value #REQUIRED_AMOUNT_SCALE}, or the
     *                                  integer part needs more than
     *                                  {@value #AMOUNT_MASK_INTEGER_DIGITS} digits
     */
    public static String formatAmountMaskWithZeroSuppression(BigDecimal amount) {
        return renderTrailingMinusAmount(amount, true, MASK_B_PICTURE);
    }

    /**
     * Encodes one assembled statement record to its {@value #STATEMENT_RECORD_LENGTH}-byte image
     * using {@link StandardCharsets#US_ASCII} explicitly, never the platform default charset.
     *
     * <p>The charset decision for this output format belongs here, with the rest of the fixed-width
     * layout knowledge, so that no writer has to make it. The returned array is freshly allocated on
     * every call and shares no state with this class, so a caller may modify it freely.</p>
     *
     * <p>The image carries no line terminator. Whether and how records are separated on disk is the
     * writer's concern.</p>
     *
     * @param record one record previously produced by a constant or builder of this class
     * @return a fresh array of exactly {@value #STATEMENT_RECORD_LENGTH} US-ASCII bytes
     * @throws NullPointerException  if {@code record} is {@code null}
     * @throws IllegalStateException if the record is not exactly
     *                               {@value #STATEMENT_RECORD_LENGTH} US-ASCII bytes, or contains a
     *                               line terminator
     */
    public static byte[] toStatementRecordBytes(String record) {
        Objects.requireNonNull(record, "record");
        return requireStatementRecordLength(record).getBytes(StandardCharsets.US_ASCII);
    }

    // Private layout primitives. Everything below is pure: no input or output, no clock, no
    // environment, no randomness, no mutable state that outlives a call.

    /**
     * Renders a value into one of the two 13-position trailing-minus statement masks.
     *
     * <p>The digits come from the unscaled value, so no locale-sensitive formatter is involved.
     * {@link BigInteger#toString()} is specified as the radix-10 representation in ASCII digit
     * characters with no grouping separator and no locale sensitivity, and it is applied to the
     * absolute value so no sign glyph can appear among the digits. The sign is written separately
     * into the trailing position.</p>
     *
     * @param amount              the value to render
     * @param suppressLeadingZeros {@code true} for mask B, {@code false} for mask A
     * @param maskPicture         the picture clause, used only to name the mask in a rejection
     * @return exactly {@value #AMOUNT_MASK_LENGTH} US-ASCII bytes
     */
    private static String renderTrailingMinusAmount(BigDecimal amount,
                                                    boolean suppressLeadingZeros,
                                                    String maskPicture) {
        Objects.requireNonNull(amount, "amount");
        if (amount.scale() != REQUIRED_AMOUNT_SCALE) {
            throw new IllegalArgumentException(
                    "Statement amount for mask " + maskPicture + " must arrive at scale "
                            + REQUIRED_AMOUNT_SCALE
                            + " with RoundingMode.DOWN already applied upstream; the actual scale was "
                            + amount.scale()
                            + ". This formatter never re-scales and never rounds, so that a"
                            + " truncation-policy violation cannot hide inside formatting.");
        }

        // The only string conversion of a number in this class, and deliberately not a BigDecimal
        // one: BigInteger renders radix-10 ASCII digits with no grouping separator and no locale
        // sensitivity, and the absolute value carries no sign glyph. The sign is applied below.
        BigInteger magnitude = amount.unscaledValue().abs();
        char[] magnitudeDigits = magnitude.toString().toCharArray();

        int digitCapacity = AMOUNT_MASK_INTEGER_DIGITS + AMOUNT_MASK_FRACTION_DIGITS;
        if (magnitudeDigits.length > digitCapacity) {
            throw new IllegalArgumentException(
                    "Statement amount does not fit mask " + maskPicture + ": the mask provides "
                            + AMOUNT_MASK_INTEGER_DIGITS
                            + " integer digits but the value needs "
                            + (magnitudeDigits.length - AMOUNT_MASK_FRACTION_DIGITS)
                            + ". High-order digits are rejected rather than truncated on the left,"
                            + " because emitting a plausible but wrong amount into a financial"
                            + " statement is worse than failing.");
        }

        // Right-align the magnitude across the fixed integer-plus-fraction capacity, zero filled.
        char[] positions = new char[digitCapacity];
        for (int i = 0; i < digitCapacity; i++) {
            positions[i] = ZERO_DIGIT;
        }
        System.arraycopy(magnitudeDigits, 0, positions,
                digitCapacity - magnitudeDigits.length, magnitudeDigits.length);

        char[] rendered = new char[AMOUNT_MASK_LENGTH];

        // Integer positions. Mask B turns a leading zero into a space and stops suppressing at the
        // first significant digit; mask A prints every digit. Neither ever touches the fraction.
        boolean suppressing = suppressLeadingZeros;
        for (int i = 0; i < AMOUNT_MASK_INTEGER_DIGITS; i++) {
            char digit = positions[i];
            if (suppressing && digit == ZERO_DIGIT) {
                rendered[i] = SPACE;
            } else {
                rendered[i] = digit;
                suppressing = false;
            }
        }

        // The decimal point is a literal insertion character and is never suppressed.
        rendered[AMOUNT_MASK_INTEGER_DIGITS] = DECIMAL_POINT;

        for (int i = 0; i < AMOUNT_MASK_FRACTION_DIGITS; i++) {
            rendered[AMOUNT_MASK_INTEGER_DIGITS + 1 + i] =
                    positions[AMOUNT_MASK_INTEGER_DIGITS + i];
        }

        // Trailing sign position: a minus for a negative value, a space otherwise. Never a plus.
        rendered[AMOUNT_MASK_LENGTH - 1] = amount.signum() < 0 ? TRAILING_MINUS : SPACE;

        return requireAmountMaskLength(new String(rendered), maskPicture);
    }

    /**
     * Moves a character value into a fixed-width field, reproducing a COBOL {@code MOVE} into a
     * {@code PIC X(n)} field: truncate on the right when the value is too long, pad on the right
     * with ASCII spaces when it is too short.
     *
     * <p>Both operations are performed on the {@link StandardCharsets#US_ASCII} encoded image rather
     * than on {@code String} characters, so the field is exactly {@code fieldWidth} bytes even for
     * input that does not encode one byte per character. The image is decoded back to a
     * {@code String} of pure ASCII, which means re-encoding the result yields the same bytes.</p>
     *
     * @param value      the value to move
     * @param fieldWidth the declared field width in bytes
     * @return a string of exactly {@code fieldWidth} US-ASCII bytes
     */
    private static String fitToField(String value, int fieldWidth) {
        byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
        byte[] field = new byte[fieldWidth];
        int copied = Math.min(encoded.length, fieldWidth);
        System.arraycopy(encoded, 0, field, 0, copied);
        for (int i = copied; i < fieldWidth; i++) {
            field[i] = ASCII_SPACE_BYTE;
        }
        return new String(field, StandardCharsets.US_ASCII);
    }

    /**
     * Produces a run of a single fill character at a declared filler width.
     *
     * @param pad   the fill character
     * @param width the declared filler width in bytes
     * @return a string of exactly {@code width} copies of {@code pad}
     */
    private static String fill(char pad, int width) {
        return Character.toString(pad).repeat(width);
    }

    /**
     * Measures a value in {@link StandardCharsets#US_ASCII} encoded bytes. Every width check in this
     * class goes through here, so no width is ever asserted in {@code String} characters or against
     * the platform default charset.
     *
     * @param value the value to measure
     * @return the encoded length in bytes
     */
    private static int encodedByteLength(String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Checks an assembled record at the full statement record length and confirms it carries no line
     * terminator.
     *
     * @param record the assembled record
     * @return the same record, unchanged, so this can wrap a constant initializer or a return value
     * @throws IllegalStateException if the length is wrong or a line terminator is present
     */
    private static String requireStatementRecordLength(String record) {
        byte[] encoded = record.getBytes(StandardCharsets.US_ASCII);
        if (encoded.length != STATEMENT_RECORD_LENGTH) {
            throw new IllegalStateException(
                    "A statement text record must be exactly " + STATEMENT_RECORD_LENGTH
                            + " US-ASCII bytes, per 01 FD-STMTFILE-REC PIC X(80) at"
                            + " [app/cbl/CBSTM03A.CBL:L45], but this one was " + encoded.length
                            + " bytes: '" + record + "'");
        }
        for (byte encodedByte : encoded) {
            if (encodedByte == ASCII_CARRIAGE_RETURN_BYTE || encodedByte == ASCII_LINE_FEED_BYTE) {
                throw new IllegalStateException(
                        "A statement text record must contain no line terminator. The record image is"
                                + " " + STATEMENT_RECORD_LENGTH + " data bytes; record separation is"
                                + " the writer's concern in the batch layer: '" + record + "'");
            }
        }
        return record;
    }

    /**
     * Checks a rendered amount at the mask width.
     *
     * @param rendered    the rendered amount
     * @param maskPicture the picture clause, used only to name the mask in a rejection
     * @return the same rendered amount, unchanged
     * @throws IllegalStateException if the rendered width is wrong
     */
    private static String requireAmountMaskLength(String rendered, String maskPicture) {
        int actualLength = encodedByteLength(rendered);
        if (actualLength != AMOUNT_MASK_LENGTH) {
            throw new IllegalStateException(
                    "Mask " + maskPicture + " must render exactly " + AMOUNT_MASK_LENGTH
                            + " US-ASCII bytes but rendered " + actualLength + ": '" + rendered
                            + "'");
        }
        return rendered;
    }

    /**
     * Checks one transcribed literal against the width the legacy source declares for it, so that a
     * mistyped literal -- one space too few in a label, for instance -- fails at class initialization
     * instead of being silently padded or truncated into a plausible-looking wrong record.
     *
     * @param literal            the transcribed literal
     * @param expectedByteLength the length the source declares
     * @param citation           the source citation, reported on failure
     * @throws IllegalStateException if the literal is the wrong length
     */
    private static void requireLiteralByteLength(String literal,
                                                 int expectedByteLength,
                                                 String citation) {
        int actualLength = encodedByteLength(literal);
        if (actualLength != expectedByteLength) {
            throw new IllegalStateException(
                    "Statement literal from [" + citation + "] must be " + expectedByteLength
                            + " US-ASCII bytes but was " + actualLength + ": '" + literal + "'");
        }
    }

    /**
     * Checks that one line group's declared component widths total the record length.
     *
     * @param group           the legacy group name, reported on failure
     * @param componentWidths the group's declared component widths, in layout order
     * @throws IllegalStateException if the components do not total the record length
     */
    private static void requireComponentSum(String group, int... componentWidths) {
        int total = 0;
        for (int componentWidth : componentWidths) {
            total += componentWidth;
        }
        if (total != STATEMENT_RECORD_LENGTH) {
            throw new IllegalStateException(
                    "Line group " + group + " declares components totalling " + total
                            + " bytes but a statement text record is exactly "
                            + STATEMENT_RECORD_LENGTH + " bytes"
                            + " [app/cbl/CBSTM03A.CBL:L85-L146]");
        }
    }

    /**
     * Checks that the start and end banners keep their different asterisk runs. Taking the two runs
     * as parameters keeps this a genuine runtime comparison rather than a constant expression, so the
     * guard cannot be optimized away.
     *
     * @param startBannerAsteriskRun the start banner's asterisk run per side
     * @param endBannerAsteriskRun   the end banner's asterisk run per side
     * @throws IllegalStateException if the two runs have become equal
     */
    private static void requireBannerAsymmetry(int startBannerAsteriskRun,
                                               int endBannerAsteriskRun) {
        if (startBannerAsteriskRun == endBannerAsteriskRun) {
            throw new IllegalStateException(
                    "The start and end statement banners must keep different asterisk runs: 31 per"
                            + " side for the start banner [app/cbl/CBSTM03A.CBL:L87] and 32 per side"
                            + " for the end banner [app/cbl/CBSTM03A.CBL:L144], because their texts"
                            + " differ in length by two. Both are now " + startBannerAsteriskRun
                            + ", so the asymmetry has been unified away and byte parity is broken.");
        }
    }

    /**
     * Checks that the shared mask geometry adds up: integer positions, one decimal point, fraction
     * positions and one trailing sign position.
     *
     * @param integerDigits  the integer positions
     * @param fractionDigits the fraction positions
     * @param maskLength     the declared rendered width
     * @throws IllegalStateException if the parts do not total the declared width
     */
    private static void requireMaskGeometry(int integerDigits, int fractionDigits, int maskLength) {
        int decimalPointPositions = 1;
        int trailingSignPositions = 1;
        int total = integerDigits + decimalPointPositions + fractionDigits + trailingSignPositions;
        if (total != maskLength) {
            throw new IllegalStateException(
                    "Statement amount mask geometry must total " + maskLength + " positions"
                            + " [app/cbl/CBSTM03A.CBL:L113] but totals " + total);
        }
    }
}
